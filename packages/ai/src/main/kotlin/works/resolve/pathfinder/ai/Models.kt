package works.resolve.pathfinder.ai

import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelCostRates
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TranscriptContext
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.AuthCheck
import works.resolve.pathfinder.ai.auth.AuthContext
import works.resolve.pathfinder.ai.auth.AuthProviderRef
import works.resolve.pathfinder.ai.auth.AuthResolutionOverrides
import works.resolve.pathfinder.ai.auth.AuthResult
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.ai.auth.Credential
import works.resolve.pathfinder.ai.auth.CredentialInfo
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.ai.auth.InMemoryCredentialStore
import works.resolve.pathfinder.ai.auth.LoginOptions
import works.resolve.pathfinder.ai.auth.ModelsError
import works.resolve.pathfinder.ai.auth.ModelsErrorCode
import works.resolve.pathfinder.ai.auth.NoopAuthContext
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.ai.auth.ProviderAuth
import works.resolve.pathfinder.ai.auth.resolveProviderAuth
import works.resolve.pathfinder.ai.mergeHeaders
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.providers.GITHUB_COPILOT_PROVIDER_ID
import works.resolve.pathfinder.ai.providers.filterGitHubCopilotModels
import works.resolve.pathfinder.ai.utils.ClientIdentity
import works.resolve.pathfinder.ai.utils.getOpenCodeSessionHeaders
import works.resolve.pathfinder.ai.utils.mergeClientIdentity
import works.resolve.pathfinder.ai.utils.normalizeContext
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.telemetry.HistogramOptions
import works.resolve.pathfinder.telemetry.InstrumentOptions
import works.resolve.pathfinder.telemetry.NOOP_TELEMETRY_CONTEXT
import works.resolve.pathfinder.telemetry.NOOP_TELEMETRY_METRICS
import works.resolve.pathfinder.telemetry.SpanOptions
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.TelemetryError
import works.resolve.pathfinder.telemetry.TelemetryMetrics
import works.resolve.pathfinder.telemetry.TelemetrySpan
import works.resolve.pathfinder.telemetry.attr

/**
 * A resolved provider credential: the API key (or, for header-auth providers,
 * resolved auth headers), plus provider env values (e.g. Cloudflare
 * account/gateway ids) substituted into base-URL placeholders at request
 * time. Pure AI-layer type; it never exposes how the app stores credentials.
 */
class ResolvedAuth(
    /** Bearer API key; null when auth is carried entirely by [headers]. */
    val apiKey: String? = null,
    val env: Map<String, String> = emptyMap(),
    /** Resolved auth headers: a null value removes the header when merged. */
    val headers: Map<String, String?> = emptyMap(),
    /**
     * Credential-specific request base URL overriding the model's (e.g.
     * GitHub Copilot's per-account proxy endpoint); null keeps the model's
     * own. Not secret.
     */
    val baseUrl: String? = null
) {
    override fun toString(): String = optionsToString(
        "ResolvedAuth",
        "apiKey" to redactedSecret(apiKey),
        "env" to env.keys,
        "headers" to headers.keys,
        "baseUrl" to baseUrl
    )
}

/**
 * Provider identity, auth semantics, and model catalog; [apis] maps each
 * supported model API id to the chat implementation serving it.
 */
class Provider(
    override val id: String,
    val name: String,
    val baseUrl: String,
    /** pi's Provider.auth: the static auth surface (key prompts, OAuth flows). */
    override val auth: ProviderAuth,
    val models: List<Model>,
    val apis: Map<String, ChatApi>,
    /** pi's `filterModels`: credential-specific model availability. */
    val filterModels: ((List<Model>, Credential?) -> List<Model>)? = null
) : AuthProviderRef {
    constructor(
        id: String,
        name: String,
        baseUrl: String,
        auth: ProviderAuth,
        models: List<Model>,
        apiId: String,
        api: ChatApi,
        filterModels: ((List<Model>, Credential?) -> List<Model>)? = null
    ) : this(id, name, baseUrl, auth, models, mapOf(apiId to api), filterModels)
}

/**
 * Tracks in-flight credential modifications so a caller can await
 * persistence before the process dies (pi's `RuntimeCredentials` pending
 * modifications). The delegated refresh can outlive its caller: once an
 * OAuth refresh has spent the refresh token, exiting before its result is
 * written would leave only the spent token stored.
 */
private class ServerCredentialWrites(private val delegate: CredentialStore) : CredentialStore {
    private val pending = ConcurrentHashMap.newKeySet<CompletableDeferred<Unit>>()

    override suspend fun read(providerId: String): Credential? = delegate.read(providerId)

    override suspend fun list(): List<CredentialInfo> = delegate.list()

    override suspend fun modify(
        providerId: String,
        update: suspend (current: Credential?) -> Credential?
    ): Credential? {
        val write = CompletableDeferred<Unit>()
        pending.add(write)
        try {
            return delegate.modify(providerId, update)
        } finally {
            pending.remove(write)
            write.complete(Unit)
        }
    }

    override suspend fun delete(providerId: String) {
        delegate.delete(providerId)
    }

    suspend fun settled() {
        pending.toList().forEach { it.await() }
    }
}

/**
 * The ported `models.ts` runtime: a fixed provider→API→models registry with
 * credential-resolved streaming. Divergence from pi: the dynamic half of
 * `models.ts` is not ported — no `MutableModels` (setProvider/
 * deleteProvider/clearProviders) and no `models-store.ts` `ModelsStore`/
 * `InMemoryModelsStore`, the persistence layer behind upstream
 * `refreshModels` publications (a provider fetches a remote catalog at
 * runtime and persists it with etag/lastModified for conditional
 * revalidation). Pathfinder's catalog is a bundled generated asset
 * (AGENTS.md); providers are registered once at construction and never
 * refreshed or replaced. Runtime-published model lists would need an
 * explicit decision (a store + refresh surface) if ever needed.
 */
class Models(
    providers: List<Provider>,
    credentials: CredentialStore = InMemoryCredentialStore(),
    private val authContext: AuthContext = NoopAuthContext,
    /** Default identity of every request; a request's identity overrides it field by field. */
    private val identity: ClientIdentity? = null,
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic
) {
    private val byId = providers.associateBy { it.id }

    /** pi's in-flight credential writes, including detached OAuth refreshes. */
    private val serverCredentialWrites = ServerCredentialWrites(credentials)

    fun getProviders(): List<Provider> = byId.values.toList()

    fun getProvider(id: String): Provider? = byId[id]

    fun getModel(providerId: String, modelId: String): Model? =
        getProvider(providerId)?.models?.firstOrNull { it.id == modelId }

    /**
     * pi's `getAuth`: resolve cached or ambient auth, applying explicit
     * request overrides through the provider's auth semantics.
     */
    suspend fun getAuth(
        providerId: String,
        overrides: AuthResolutionOverrides? = null
    ): AuthResult? {
        val provider = byId[providerId] ?: return null
        return resolveProviderAuth(
            provider,
            serverCredentialWrites,
            authContext,
            overrides,
            clock
        )
    }

    /** pi's model overload: resolved auth headers merge under the model's own headers. */
    suspend fun getAuth(model: Model, overrides: AuthResolutionOverrides? = null): AuthResult? {
        val result = getAuth(model.provider, overrides) ?: return null
        if (model.headers.isEmpty()) return result
        return result.copy(
            auth = result.auth.copy(
                headers = mergeHeaders(result.auth.headers, model.headers)
            )
        )
    }

    /**
     * pi's `checkAuth`: stored OAuth is reported without resolving or
     * refreshing it; api-key providers use their side-effect-free check when
     * present, else resolve. Credential-store and check failures propagate as
     * [ModelsError] (`auth`).
     */
    suspend fun checkAuth(providerId: String): AuthCheck? {
        val provider = byId[providerId] ?: return null
        val credential = readCredential(providerId)
        return checkProviderAuth(provider, credential)
    }

    /**
     * Cheap configured-provider presence for restore/preflight paths: the
     * stored credential exists, without resolving it (no OAuth refresh). A
     * store read failure reports unconfigured.
     */
    suspend fun hasConfiguredAuth(providerId: String): Boolean = try {
        serverCredentialWrites.read(providerId) != null
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    /** pi ModelRuntime.isUsingOAuth, derived from the stored/resolved credential type. */
    suspend fun isUsingOAuth(providerId: String): Boolean =
        checkAuth(providerId)?.type == AuthType.OAUTH

    /** pi's `getAvailable`: configured providers' models after their credential filter. */
    suspend fun getAvailable(providerId: String? = null): List<Model> =
        authenticatedProviders(providerId).flatMap { (provider, credential) ->
            provider.filterModels?.invoke(provider.models, credential) ?: provider.models
        }

    /** Await in-flight credential writes, including a detached OAuth refresh. */
    suspend fun settleCredentialWrites() {
        serverCredentialWrites.settled()
    }

    /**
     * Persist a successful login. The credential itself is never logged; a
     * store failure surfaces as [ModelsError] (`auth`).
     */
    suspend fun login(
        providerId: String,
        type: AuthType,
        interaction: works.resolve.pathfinder.ai.auth.AuthInteraction,
        options: LoginOptions? = null
    ): Credential {
        val provider = byId[providerId]
            ?: throw ModelsError(ModelsErrorCode.PROVIDER, "Unknown provider: $providerId")
        val unsupported = ModelsError(
            ModelsErrorCode.AUTH,
            "${provider.name} does not support ${type.wire} login"
        )
        val credential: Credential = when (type) {
            AuthType.OAUTH -> {
                val oauth = provider.auth.oauth ?: throw unsupported
                oauth.login(interaction, options)
            }

            AuthType.API_KEY -> {
                val startLogin = provider.auth.apiKey?.login ?: throw unsupported
                startLogin(interaction)
            }
        }
        try {
            serverCredentialWrites.modify(providerId) { credential }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ModelsError(
                ModelsErrorCode.AUTH,
                "Credential store modify failed for $providerId",
                error
            )
        }
        return credential
    }

    /** Remove a stored credential; store failures surface as [ModelsError] (`auth`). */
    suspend fun logout(providerId: String) {
        try {
            serverCredentialWrites.delete(providerId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ModelsError(
                ModelsErrorCode.AUTH,
                "Credential store delete failed for $providerId",
                error
            )
        }
    }

    companion object {
        fun modelsAreEqual(a: Model?, b: Model?): Boolean =
            a != null && b != null && a.id == b.id && a.provider == b.provider
    }

    private fun requireProvider(model: Model): Provider = byId[model.provider]
        ?: throw ModelsError(ModelsErrorCode.PROVIDER, "Unknown provider: ${model.provider}")

    private suspend fun authenticatedProviders(
        providerId: String?
    ): List<Pair<Provider, Credential?>> {
        val providers = if (providerId != null) {
            listOfNotNull(byId[providerId])
        } else {
            getProviders()
        }
        return providers.mapNotNull { provider ->
            val credential = readCredential(provider.id)
            if (checkProviderAuth(provider, credential) == null) {
                null
            } else {
                provider to credential
            }
        }
    }

    private suspend fun checkProviderAuth(provider: Provider, credential: Credential?): AuthCheck? {
        if (credential is OAuthCredential) {
            return if (provider.auth.oauth != null) {
                AuthCheck(source = "OAuth", type = AuthType.OAUTH)
            } else {
                null
            }
        }
        val apiKey = provider.auth.apiKey ?: return null
        val check = apiKey.check
        if (check != null) {
            return try {
                check(
                    authContext,
                    credential as? ApiKeyCredential
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw ModelsError(
                    ModelsErrorCode.AUTH,
                    "API key auth check failed for provider ${provider.id}",
                    error
                )
            }
        }
        val resolution = resolveProviderAuth(
            provider,
            serverCredentialWrites,
            authContext,
            null,
            clock
        )
        return resolution?.let { AuthCheck(source = it.source, type = AuthType.API_KEY) }
    }

    private suspend fun readCredential(providerId: String): Credential? = try {
        serverCredentialWrites.read(providerId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw ModelsError(
            ModelsErrorCode.AUTH,
            "Credential store read failed for $providerId",
            error
        )
    }

    /**
     * pi's lazyStream setup: provider lookup, auth resolution, and adapter
     * dispatch. Throws [ModelsError] (or the adapter's own setup exception)
     * for [stream] to convert into a terminal error event.
     */
    private suspend fun resolveApiStream(
        model: Model,
        transcript: TranscriptContext,
        options: SimpleStreamOptions
    ): Flow<AssistantMessageEvent> {
        val provider = requireProvider(model)
        // Resolve the credential inside setup so stored-credential lookups can
        // suspend without making stream() a suspend call. An explicit request
        // key/env passes through the provider's auth shaping without reading
        // stored credentials; pi's applyAuth then prefers the explicit key.
        val resolution = getAuth(
            model,
            AuthResolutionOverrides(apiKey = options.apiKey, env = options.env)
        ) ?: throw ModelsError(
            ModelsErrorCode.AUTH,
            "Provider is not configured: ${model.provider}"
        )
        val api = provider.apis[model.api]
            ?: throw ModelsError(
                ModelsErrorCode.STREAM,
                "Provider ${provider.id} has no API implementation for \"${model.api}\""
            )
        val requestModel = resolution.auth.baseUrl?.let { model.copy(baseUrl = it) } ?: model
        val sessionHeaders = getOpenCodeSessionHeaders(requestModel, options.sessionId)
        val merged = options.copy(
            apiKey = options.apiKey ?: resolution.auth.apiKey,
            env = resolution.env + options.env,
            headers = mergeHeaders(
                mergeHeaders(sessionHeaders ?: emptyMap(), resolution.auth.headers),
                options.headers
            ),
            identity = mergeClientIdentity(identity, options.identity)
        )
        return api.streamSimple(requestModel, transcript, merged)
    }

    /**
     * Starts a chat stream for [model]: the model's provider must be
     * registered and implement the model's API. Auth is resolved lazily
     * inside the flow and merged with [options]; the explicit [options]
     * apiKey/env still pass through the provider's auth shaping (Cloudflare's
     * header auth) without reading stored credentials. Explicit request
     * fields win: an explicit apiKey overrides the resolved one, env values
     * merge per field with the request on top, and resolved auth headers
     * merge under explicit request headers case-insensitively. OpenCode's
     * session header is applied by request target, then resolved auth headers,
     * then explicit request headers.
     *
     * pi's lazyStream contract: setup failures — unknown provider, absent or
     * failing credential resolution, a missing API implementation, or a
     * synchronous setup throw from the API adapter (streamSimple may throw
     * for missing request auth) — surface as a single terminal
     * [AssistantMessageEvent.Error] event carrying the ModelsError message,
     * never as an exception from this method or the returned flow.
     */
    fun stream(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions()
    ): Flow<AssistantMessageEvent> {
        val transcript = normalizeContext(context)
        val startedAt = clock.now().toEpochMilliseconds()
        val startedMark = timeSource.markNow()
        return flow {
            val startAttributes = buildMap {
                put("provider", attr(model.provider))
                put("api", attr(model.api))
                put("model", attr(model.id))
                options.reasoning?.let { put("reasoning", attr(it.name.lowercase())) }
            }
            val parent = options.telemetry ?: NOOP_TELEMETRY_CONTEXT
            parent.startSpan(SpanOptions("pi.ai.request", startAttributes)) { span ->
                val observer = ModelRequestObserver(model, startedMark, span)
                val apiStream = try {
                    resolveApiStream(model, transcript, options)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // pi's lazyStream reads the abort signal here: a setup
                    // failure after the caller stopped is a cancellation, not
                    // a provider error.
                    val reason = if (currentCoroutineContext().isActive) {
                        StopReason.ERROR
                    } else {
                        StopReason.ABORTED
                    }
                    val message = setupError(model, error, startedAt, reason)
                    val event = AssistantMessageEvent.Error(reason, message)
                        .withStreamDuration(startedAt, startedMark)
                    emit(event)
                    observer.onEvent(event)
                    observer.onResult(message)
                    return@startSpan
                }
                var result: AssistantMessage? = null
                apiStream.collect { raw ->
                    val event = raw.withStreamDuration(startedAt, startedMark)
                    emit(event)
                    observer.onEvent(event)
                    when (event) {
                        is AssistantMessageEvent.Done -> result = event.message
                        is AssistantMessageEvent.Error -> result = event.error
                        else -> {}
                    }
                }
                observer.onResult(result)
            }
        }
    }

    /**
     * Collects [stream] to its terminal AssistantMessage. A terminal `Error`
     * event is returned as the error AssistantMessage, so callers inspect
     * [AssistantMessage.stopReason] to distinguish failure from success;
     * like pi's `result()` promise, a stream that ends without a terminal
     * event never resolves.
     */
    suspend fun completeSimple(
        model: Model,
        context: Context,
        options: SimpleStreamOptions = SimpleStreamOptions()
    ): AssistantMessage {
        val terminal = CompletableDeferred<AssistantMessage>()
        stream(model, context, options).collect { event ->
            when (event) {
                is AssistantMessageEvent.Done -> terminal.complete(event.message)
                is AssistantMessageEvent.Error -> terminal.complete(event.error)
                else -> {}
            }
        }
        return terminal.await()
    }

    /** pi's lazyStream createSetupErrorMessage: zeroed usage, terminal reason, stream-start timestamp. */
    private fun setupError(
        model: Model,
        error: Exception,
        timestamp: Long,
        reason: StopReason
    ): AssistantMessage = AssistantMessage(
        content = emptyList(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = reason,
        errorMessage = error.message,
        timestamp = timestamp
    )
}

private class RequestInstruments(metrics: TelemetryMetrics) {
    val requestDuration = metrics.histogram(
        "pi.ai.request.duration",
        HistogramOptions(description = "Logical request or poll duration.", unit = "ms")
    )
    val firstChunkDuration = metrics.histogram(
        "pi.ai.first_chunk.duration",
        HistogramOptions(description = "Time to the first normalized stream event.", unit = "ms")
    )
    val tokens = metrics.counter(
        "pi.ai.tokens",
        InstrumentOptions(
            description = "Reported model token usage; pending and deferred polls are excluded.",
            unit = "token"
        )
    )
    val cost = metrics.counter(
        "pi.ai.cost",
        InstrumentOptions(
            description = "Reported model cost; pending and deferred polls are excluded.",
            unit = "USD"
        )
    )
}

private val instrumentCacheLock = Any()
private val instrumentCache =
    Collections.synchronizedMap(WeakHashMap<TelemetryMetrics, RequestInstruments>())

private fun requestInstruments(metrics: TelemetryMetrics): RequestInstruments =
    synchronized(instrumentCacheLock) {
        instrumentCache.getOrPut(metrics) { RequestInstruments(metrics) }
    }

/**
 * pi's `observeModelRequest` for the Models stream path. It records metadata
 * and bounded block sizes, never message content, tool arguments, or error
 * text beyond the status message.
 */
private class ModelRequestObserver(
    model: Model,
    private val started: TimeMark,
    private val span: TelemetrySpan
) {
    private val attributes = mapOf("provider" to attr(model.provider), "model" to attr(model.id))
    private val instruments = requestInstruments(span.metrics ?: NOOP_TELEMETRY_METRICS)
    private val toolChars = mutableMapOf<Int, Int>()
    private var first = true

    fun onEvent(event: AssistantMessageEvent) {
        if (first) {
            first = false
            span.addEvent("pi.ai.first_chunk")
            instruments.firstChunkDuration.record(elapsedMs(), attributes, span)
        }
        when (event) {
            is AssistantMessageEvent.TextEnd -> span.addEvent(
                "pi.ai.block",
                mapOf(
                    "type" to attr("text"),
                    "index" to attr(event.contentIndex),
                    "chars" to attr(event.content.length)
                )
            )

            is AssistantMessageEvent.ThinkingEnd -> span.addEvent(
                "pi.ai.block",
                mapOf(
                    "type" to attr("thinking"),
                    "index" to attr(event.contentIndex),
                    "chars" to attr(event.content.length)
                )
            )

            is AssistantMessageEvent.ToolCallDelta -> {
                toolChars[event.contentIndex] =
                    (toolChars[event.contentIndex] ?: 0) + event.delta.length
            }

            is AssistantMessageEvent.ToolCallEnd -> {
                val chars = toolChars.remove(event.contentIndex) ?: 0
                span.addEvent(
                    "pi.ai.block",
                    mapOf(
                        "type" to attr("tool_call"),
                        "index" to attr(event.contentIndex),
                        "chars" to attr(chars),
                        "tool" to attr(event.toolCall.name)
                    )
                )
            }

            else -> {}
        }
    }

    fun onResult(message: AssistantMessage?) {
        if (message == null) return
        val stop = message.stopReason.telemetryWire()
        span.setAttributes(
            buildMap {
                put("stop", attr(stop))
                put("usage.input", attr(message.usage.input))
                put("usage.output", attr(message.usage.output))
                put("usage.cache_read", attr(message.usage.cacheRead))
                put("usage.cache_write", attr(message.usage.cacheWrite))
                put("cost.total", attr(message.usage.cost.total))
                message.responseId?.let { put("response.id", attr(it)) }
            }
        )
        if (message.stopReason == StopReason.ERROR) {
            span.setStatus(
                SpanStatus.Error(
                    TelemetryError("provider_error", message.errorMessage?.take(500) ?: "")
                )
            )
        }
        instruments.requestDuration.record(
            elapsedMs(),
            attributes + ("stop" to attr(stop)),
            span
        )
        // Models.stream is never a poll: pending/deferred results are terminal
        // here and carry usage like any other result (pi's `!poll` branch).
        instruments.tokens.add(
            message.usage.input.toDouble(),
            attributes + ("kind" to attr("input"))
        )
        instruments.tokens.add(
            message.usage.output.toDouble(),
            attributes + ("kind" to attr("output"))
        )
        instruments.tokens.add(
            message.usage.cacheRead.toDouble(),
            attributes + ("kind" to attr("cache_read"))
        )
        instruments.tokens.add(
            message.usage.cacheWrite.toDouble(),
            attributes + ("kind" to attr("cache_write"))
        )
        instruments.cost.add(message.usage.cost.total, attributes)
    }

    private fun elapsedMs(): Double = started.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
}

private fun StopReason.telemetryWire(): String = when (this) {
    StopReason.PENDING -> "pending"
    StopReason.STOP -> "stop"
    StopReason.LENGTH -> "length"
    StopReason.TOOL_USE -> "toolUse"
    StopReason.ERROR -> "error"
    StopReason.ABORTED -> "aborted"
    StopReason.DEFERRED -> "deferred"
}

private fun AssistantMessageEvent.withStreamDuration(
    startedAt: Long,
    startedMark: TimeMark
): AssistantMessageEvent = when (this) {
    is AssistantMessageEvent.Done -> copy(
        message = message.withStreamDuration(startedAt, startedMark)
    )

    is AssistantMessageEvent.Error -> copy(
        error = error.withStreamDuration(startedAt, startedMark)
    )

    else -> this
}

private fun AssistantMessage.withStreamDuration(
    startedAt: Long,
    startedMark: TimeMark
): AssistantMessage {
    if (durationMs != null || timestamp < startedAt) return this
    val elapsed = startedMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS)
    return copy(durationMs = elapsed.roundToLong().coerceAtLeast(0))
}

/**
 * Reference cost in USD from token usage and per-million rates. The tier with
 * the highest `inputTokensAbove` threshold that the request's total input
 * usage strictly exceeds applies its rates to the full request.
 */
fun calculateCost(model: Model, usage: Usage): Cost {
    val inputTokens = usage.input + usage.cacheRead + usage.cacheWrite
    var rates: ModelCostRates = model.cost
    var matchedThreshold = -1
    for (tier in model.cost.tiers) {
        if (inputTokens > tier.inputTokensAbove && tier.inputTokensAbove > matchedThreshold) {
            rates = tier
            matchedThreshold = tier.inputTokensAbove
        }
    }

    // Anthropic charges 2x base input for 1h cache writes.
    val longWrite = usage.cacheWrite1h
    val shortWrite = usage.cacheWrite - longWrite
    val cost = Cost(
        input = (rates.input / 1_000_000.0) * usage.input,
        output = (rates.output / 1_000_000.0) * usage.output,
        cacheRead = (rates.cacheRead / 1_000_000.0) * usage.cacheRead,
        cacheWrite = (rates.cacheWrite * shortWrite + rates.input * 2 * longWrite) / 1_000_000.0,
        totalFromProvider = false
    )
    return cost.copy(total = cost.input + cost.output + cost.cacheRead + cost.cacheWrite)
}

private val EXTENDED_THINKING_LEVELS = listOf(
    ModelThinkingLevel.OFF,
    ModelThinkingLevel.MINIMAL,
    ModelThinkingLevel.LOW,
    ModelThinkingLevel.MEDIUM,
    ModelThinkingLevel.HIGH,
    ModelThinkingLevel.XHIGH,
    ModelThinkingLevel.MAX
)

fun getSupportedThinkingLevels(model: Model): List<ModelThinkingLevel> {
    if (!model.reasoning) return listOf(ModelThinkingLevel.OFF)
    val map = model.thinkingLevelMap
    return EXTENDED_THINKING_LEVELS.filter { level ->
        val mapped = map?.forLevel(level)
        val specified = map?.isSpecified(level) == true
        // Explicit null means unsupported.
        if (specified && mapped == null) return@filter false
        // XHIGH/MAX require an explicit non-null mapping; OFF..HIGH default to supported.
        if (level == ModelThinkingLevel.XHIGH || level == ModelThinkingLevel.MAX) {
            return@filter specified && mapped != null
        }
        true
    }
}

/**
 * Clamps a requested thinking level to one the model supports, rounding up
 * first, then down.
 */
fun clampThinkingLevel(model: Model, level: ModelThinkingLevel): ModelThinkingLevel {
    val available = getSupportedThinkingLevels(model)
    if (available.contains(level)) return level

    val requestedIndex = EXTENDED_THINKING_LEVELS.indexOf(level)
    if (requestedIndex == -1) return available.firstOrNull() ?: ModelThinkingLevel.OFF

    for (i in requestedIndex until EXTENDED_THINKING_LEVELS.size) {
        if (available.contains(EXTENDED_THINKING_LEVELS[i])) return EXTENDED_THINKING_LEVELS[i]
    }
    for (i in requestedIndex - 1 downTo 0) {
        if (available.contains(EXTENDED_THINKING_LEVELS[i])) return EXTENDED_THINKING_LEVELS[i]
    }
    return available.firstOrNull() ?: ModelThinkingLevel.OFF
}

/**
 * pi's per-provider `filterModels` hook dispatch, which `getAvailable`
 * applies as `provider.filterModels?.(models, credential) ?? models`
 * (models.ts L538 at pin b8b873b98); GitHub Copilot alone defines the hook.
 * The dispatch lives in this free function instead of a `filterModels`
 * field on [CatalogProvider] because the catalog is generated data, while
 * the filter is runtime behavior tied to the credential shape.
 */
fun filterCatalogModels(provider: CatalogProvider, credential: Credential?): List<Model> =
    if (provider.id == GITHUB_COPILOT_PROVIDER_ID) {
        filterGitHubCopilotModels(provider.models, credential)
    } else {
        provider.models
    }
