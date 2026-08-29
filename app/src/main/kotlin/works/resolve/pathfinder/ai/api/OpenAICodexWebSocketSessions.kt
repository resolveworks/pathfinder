package works.resolve.pathfinder.ai.api

import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.transport.WebSocketConnection
import works.resolve.pathfinder.ai.transport.WebSocketStreamingTransport
import works.resolve.pathfinder.ai.utils.arr

/**
 * Port of pi's Codex WebSocket connection pool, debug stats, and SSE-fallback
 * session state (packages/ai/src/api/openai-codex-responses.ts
 * `websocketSessionCache` / `websocketDebugStats` / `websocketSseFallbackSessions`
 * module-level state, ~:828-927, plus `acquireWebSocket` ~:1116-1211).
 *
 * Divergences from pi (documented at this boundary):
 * - pi keeps this state in module-level variables on the single-threaded JS
 *   event loop; here it is a process-wide singleton guarded by a [Mutex], and
 *   idle expiry uses a timer coroutine instead of `setTimeout` (pi's
 *   `scheduleSessionWebSocketExpiry`).
 * - pi registers cleanup through `registerSessionResourceCleanup`
 *   (session-resources.ts, a Node session-lifecycle hook with no Android
 *   counterpart); here the public [closeOpenAICodexWebSocketSessions] plus the
 *   idle TTL / max connection age own cleanup.
 * - pi's clock reads `Date.now()`; [clock] (kotlin.time.Clock, per the
 *   translation conventions) is constructor-injected for tests.
 */
class OpenAICodexWebSocketSessions(
    val clock: Clock = Clock.System,
) {

    private fun nowMs(): Long = clock.now().toEpochMilliseconds()

    companion object {
        /** pi SESSION_WEBSOCKET_CACHE_TTL_MS (openai-codex-responses.ts ~:830). */
        const val SESSION_WEBSOCKET_CACHE_TTL_MS: Long = 5 * 60 * 1000

        /** pi SESSION_WEBSOCKET_MAX_AGE_MS (openai-codex-responses.ts ~:831). */
        const val SESSION_WEBSOCKET_MAX_AGE_MS: Long = 55 * 60 * 1000

        /** pi OPENAI_BETA_RESPONSES_WEBSOCKETS (openai-codex-responses.ts ~:829). */
        const val OPENAI_BETA_RESPONSES_WEBSOCKETS = "responses_websockets=2026-02-06"
    }

    private val mutex = Mutex()
    private val timerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** sessionId -> accountId -> pooled connection (pi's nested maps). */
    private val sessionCache = mutableMapOf<String, MutableMap<String, CachedWebSocketEntry>>()
    private val debugStats = mutableMapOf<String, OpenAICodexWebSocketDebugStats>()
    private val sseFallbackSessions = mutableSetOf<String>()

    /**
     * pi's CachedWebSocketConnection: one pooled socket per session/account
     * with busy state, creation time, idle timer, and cached-context
     * continuation.
     */
    internal class CachedWebSocketEntry(val connection: WebSocketConnection, val createdAt: Long) {
        var busy = false
        var idleJob: Job? = null
        var continuation: CachedWebSocketContinuation? = null
    }

    /** pi's CachedWebSocketContinuationState. */
    internal class CachedWebSocketContinuation(
        val lastRequestBody: JsonObject,
        val lastResponseId: String,
        val lastResponseItems: List<JsonObject>,
    )

    /** pi's acquireWebSocket return shape. */
    internal class Acquired(
        val connection: WebSocketConnection,
        val entry: CachedWebSocketEntry?,
        val reused: Boolean,
        val release: suspend (keep: Boolean) -> Unit,
    )

    fun isSseFallbackActive(sessionId: String?): Boolean =
        sessionId != null && sessionId in sseFallbackSessions

    /** pi's recordWebSocketSseFallback. */
    fun recordSseFallback(sessionId: String?) {
        if (sessionId == null) return
        val stats = getOrCreateStats(sessionId)
        stats.sseFallbacks++
        stats.websocketFallbackActive = isSseFallbackActive(sessionId)
    }

    /** pi's recordWebSocketFailure: makes SSE sticky for the session. */
    fun recordWebSocketFailure(sessionId: String?, error: Throwable) {
        if (sessionId == null) return
        sseFallbackSessions.add(sessionId)
        val stats = getOrCreateStats(sessionId)
        stats.websocketFailures++
        stats.lastWebSocketError = error.message ?: error::class.simpleName ?: "unknown"
        stats.websocketFallbackActive = true
    }

    fun getOrCreateStats(sessionId: String): OpenAICodexWebSocketDebugStats =
        debugStats.getOrPut(sessionId) { OpenAICodexWebSocketDebugStats() }

    fun getStats(sessionId: String): OpenAICodexWebSocketDebugStats? = debugStats[sessionId]?.copy()

    fun resetStats(sessionId: String?) {
        if (sessionId != null) {
            debugStats.remove(sessionId)
            sseFallbackSessions.remove(sessionId)
            return
        }
        debugStats.clear()
        sseFallbackSessions.clear()
    }

    /** pi's closeOpenAICodexWebSocketSessions close path (closes, no stats touched). */
    fun closeSessions(sessionId: String?) {
        timerScope.launch {
            mutex.withLock {
                if (sessionId != null) {
                    sessionCache[sessionId]?.values?.forEach(::closeEntry)
                    sessionCache.remove(sessionId)
                } else {
                    sessionCache.values.forEach { entries -> entries.values.forEach(::closeEntry) }
                    sessionCache.clear()
                }
            }
        }
    }

    private fun closeEntry(entry: CachedWebSocketEntry) {
        entry.idleJob?.cancel()
        entry.idleJob = null
        entry.connection.close(1000, "debug_close")
    }

    /**
     * pi's acquireWebSocket (~:1116-1211). No [sessionId] gives a one-shot
     * socket (release closes it); expired-by-age, busy, or non-reusable cached
     * entries fall back to fresh connections exactly as upstream. The connect
     * itself happens outside the lock; insertion afterwards mirrors pi's
     * post-await map insert (last writer wins per session/account).
     */
    internal suspend fun acquire(
        transport: WebSocketStreamingTransport,
        url: String,
        headers: Map<String, String>,
        sessionId: String?,
        accountId: String,
        connectTimeoutMs: Long?,
    ): Acquired {
        val connect: suspend () -> WebSocketConnection = {
            transport.connect(url, headers, connectTimeoutMs ?: WebSocketStreamingTransport.DEFAULT_WEBSOCKET_CONNECT_TIMEOUT_MS)
        }

        if (sessionId == null) {
            val connection = connect()
            return Acquired(connection, entry = null, reused = false) { connection.close() }
        }

        // Cached-entry triage under the lock; anything but a clean reuse
        // falls through to a fresh connect.
        mutex.withLock {
            val accountEntries = sessionCache[sessionId]
            val cached = accountEntries?.get(accountId)
            if (cached != null) {
                cached.idleJob?.cancel()
                cached.idleJob = null
                when {
                    !cached.busy && nowMs() - cached.createdAt >= SESSION_WEBSOCKET_MAX_AGE_MS -> {
                        cached.connection.close(1000, "connection_age_limit")
                        removeEntry(sessionId, accountId, cached)
                    }
                    !cached.busy && cached.connection.isOpen -> {
                        cached.busy = true
                        return Acquired(cached.connection, cached, reused = true) { keep ->
                            withContext(NonCancellable) { release(sessionId, accountId, cached, keep) }
                        }
                    }
                    cached.busy -> {
                        val connection = connect()
                        return Acquired(connection, entry = null, reused = false) { connection.close() }
                    }
                    else -> {
                        cached.connection.close()
                        removeEntry(sessionId, accountId, cached)
                    }
                }
            }
        }

        val connection = connect()
        val entry = CachedWebSocketEntry(connection, createdAt = nowMs())
        mutex.withLock {
            sessionCache.getOrPut(sessionId) { mutableMapOf() }[accountId] = entry
        }
        return Acquired(connection, entry, reused = false) { keep ->
            withContext(NonCancellable) { release(sessionId, accountId, entry, keep) }
        }
    }

    /** pi's release({ keep }) closure. */
    private suspend fun release(sessionId: String, accountId: String, entry: CachedWebSocketEntry, keep: Boolean) {
        mutex.withLock {
            if (!keep || !entry.connection.isOpen) {
                entry.idleJob?.cancel()
                entry.idleJob = null
                entry.connection.close()
                removeEntry(sessionId, accountId, entry)
                return@withLock
            }
            entry.busy = false
            scheduleIdleExpiry(sessionId, accountId, entry)
        }
    }

    /** pi's scheduleSessionWebSocketExpiry (~:1003). */
    private fun scheduleIdleExpiry(sessionId: String, accountId: String, entry: CachedWebSocketEntry) {
        entry.idleJob?.cancel()
        entry.idleJob = timerScope.launch {
            delay(SESSION_WEBSOCKET_CACHE_TTL_MS)
            mutex.withLock {
                if (entry.busy) return@withLock
                entry.connection.close(1000, "idle_timeout")
                removeEntry(sessionId, accountId, entry)
            }
        }
    }

    private fun removeEntry(sessionId: String, accountId: String, entry: CachedWebSocketEntry) {
        val accountEntries = sessionCache[sessionId] ?: return
        if (accountEntries[accountId] === entry) accountEntries.remove(accountId)
        if (accountEntries.isEmpty()) sessionCache.remove(sessionId)
    }

    /** pi's getOpenAICodexWebSocketDebugStats (~:875): a copy, or null. */
    fun getOpenAICodexWebSocketDebugStats(sessionId: String): OpenAICodexWebSocketDebugStats? =
        getStats(sessionId)

    /**
     * pi's resetOpenAICodexWebSocketDebugStats (~:882): drops the session's
     * stats and SSE-fallback stickiness, or everything when null.
     */
    fun resetOpenAICodexWebSocketDebugStats(sessionId: String? = null) {
        resetStats(sessionId)
    }

    /**
     * pi's closeOpenAICodexWebSocketSessions (~:904): closes the session's
     * pooled sockets with 1000/"debug_close" (all of them when null). Note
     * upstream does NOT clear the SSE-fallback set here (only
     * [resetOpenAICodexWebSocketDebugStats] does); this port preserves that.
     */
    fun closeOpenAICodexWebSocketSessions(sessionId: String? = null) {
        closeSessions(sessionId)
    }
}

/** pi's OpenAICodexWebSocketDebugStats (openai-codex-responses.ts ~:845). */
data class OpenAICodexWebSocketDebugStats(
    var requests: Int = 0,
    var connectionsCreated: Int = 0,
    var connectionsReused: Int = 0,
    var cachedContextRequests: Int = 0,
    var storeTrueRequests: Int = 0,
    var fullContextRequests: Int = 0,
    var deltaRequests: Int = 0,
    var lastInputItems: Int = 0,
    var lastDeltaInputItems: Int? = null,
    var lastPreviousResponseId: String? = null,
    var websocketFailures: Int = 0,
    var sseFallbacks: Int = 0,
    var websocketFallbackActive: Boolean? = null,
    var lastWebSocketError: String? = null,
)

// ---------------------------------------------------------------------------
// Cached-context helpers (openai-codex-responses.ts ~:1389-1442)
// ---------------------------------------------------------------------------

/** pi's requestBodyWithoutInput: body minus `input`/`previous_response_id`. */
internal fun requestBodyWithoutInput(body: JsonObject): JsonObject = buildJsonObject {
    body.forEach { (key, value) ->
        if (key != "input" && key != "previous_response_id") put(key, value)
    }
}

/**
 * pi's responseInputsEqual: JSON-string equality. Kotlinx JsonObject/
 * JsonArray toString() is insertion-ordered like JSON.stringify, so canonical
 * string comparison mirrors pi byte-for-byte for identically-built arrays.
 */
internal fun responseInputsEqual(a: JsonArray?, b: JsonArray?): Boolean {
    val empty = JsonArray(emptyList())
    return (a ?: empty).toString() == (b ?: empty).toString()
}

/** pi's requestBodiesMatchExceptInput. */
internal fun requestBodiesMatchExceptInput(a: JsonObject, b: JsonObject): Boolean =
    requestBodyWithoutInput(a).toString() == requestBodyWithoutInput(b).toString()

/** pi's getCachedWebSocketInputDelta: the suffix when the new input extends
 *  the previous input + assistant response items with an equal prefix. */
internal fun getCachedWebSocketInputDelta(
    body: JsonObject,
    continuation: OpenAICodexWebSocketSessions.CachedWebSocketContinuation,
): JsonArray? {
    if (!requestBodiesMatchExceptInput(body, continuation.lastRequestBody)) return null
    val currentInput = body.arr("input") ?: JsonArray(emptyList())
    val lastInput = continuation.lastRequestBody.arr("input") ?: JsonArray(emptyList())
    val baseline = JsonArray(lastInput + continuation.lastResponseItems)
    if (currentInput.size < baseline.size) return null
    val prefix = JsonArray(currentInput.take(baseline.size))
    if (!responseInputsEqual(prefix, baseline)) return null
    return JsonArray(currentInput.drop(baseline.size))
}

/**
 * pi's buildCachedWebSocketRequestBody: sends `previous_response_id` + the
 * input delta when the cached continuation applies; any mismatch invalidates
 * the continuation and sends the full body.
 */
internal fun buildCachedWebSocketRequestBody(
    entry: OpenAICodexWebSocketSessions.CachedWebSocketEntry,
    body: JsonObject,
): JsonObject {
    val continuation = entry.continuation ?: return body
    val delta = getCachedWebSocketInputDelta(body, continuation)
    if (delta == null) {
        entry.continuation = null
        return body
    }
    return buildJsonObject {
        body.forEach { (key, value) -> put(key, value) }
        put("previous_response_id", continuation.lastResponseId)
        put("input", delta)
    }
}
