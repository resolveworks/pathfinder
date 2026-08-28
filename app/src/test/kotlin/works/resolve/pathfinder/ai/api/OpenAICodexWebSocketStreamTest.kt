package works.resolve.pathfinder.ai.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelCost
import works.resolve.pathfinder.ai.core.OpenAiResponsesCompat
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.Transport
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.testing.sse
import works.resolve.pathfinder.ai.transport.WebSocketCloseException
import works.resolve.pathfinder.ai.transport.WebSocketConnection
import works.resolve.pathfinder.ai.transport.WebSocketEvent
import works.resolve.pathfinder.ai.transport.WebSocketStreamingTransport
import java.io.IOException

/**
 * WebSocket-transport tests for [OpenAICodexResponsesApi], ported from pi's
 * openai-codex-stream.test.ts WebSocket suite (~:1227-2330): auto transport
 * with cached context, account scoping, one-shot sockets on cacheRetention
 * none, SSE fallback on connect timeout (sticky per session), one-shot
 * connection-limit reconnect, idle timeouts before/after the first event,
 * connection age limit, previous_response_not_found recovery, debug stats,
 * and abort closing the pooled socket.
 */
class OpenAICodexWebSocketStreamTest {

    private val model = Model(
        id = "gpt-5.1-codex",
        name = "GPT-5.1 Codex",
        api = "openai-codex-responses",
        provider = "openai-codex",
        baseUrl = "https://chatgpt.com/backend-api",
        reasoning = true,
        cost = ModelCost(input = 1.0, output = 2.0),
        contextWindow = 400_000,
        maxTokens = 128_000,
        responsesCompat = OpenAiResponsesCompat(supportsStrictMode = true),
    )

    /** pi's tests reset the module state between cases. */
    private suspend fun cleanSlate() {
        OpenAICodexWebSocketSessions.nowMs = System::currentTimeMillis
        OpenAICodexWebSocketSessions.resetForTest()
    }

    private fun jwt(accountId: String): String {
        val encode: (String) -> String = { text ->
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        }
        val payload = """{"https://api.openai.com/auth":{"chatgpt_account_id":"$accountId"}}"""
        return "${encode("""{"alg":"none"}""")}.${encode(payload)}.sig"
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeWebSocketConnection : WebSocketConnection {
        override val events = Channel<WebSocketEvent>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()

        @Volatile
        private var open = true

        /** Scripted per-frame server reaction. */
        var onSend: ((String) -> Unit)? = null

        override fun send(text: String) {
            sent.add(text)
            onSend?.invoke(text)
        }

        override fun close(code: Int, reason: String) {
            open = false
        }

        override val isOpen: Boolean get() = open

        fun server(obj: JsonObject) {
            events.trySend(WebSocketEvent.Message(obj.toString()))
        }

        fun serverAll(objs: List<JsonObject>) = objs.forEach(::server)

        fun fail(message: String) {
            events.trySend(WebSocketEvent.Failure(message))
            events.close()
        }

        fun closedByServer(code: Int? = 1000, reason: String? = "done") {
            val codeText = code?.let { " $it" } ?: ""
            events.trySend(
                WebSocketEvent.Closed(code, reason, true, "WebSocket closed$codeText ${reason ?: ""}".trim()),
            )
            events.close()
        }
    }

    private class FakeWebSocketTransport : WebSocketStreamingTransport {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val connections = mutableListOf<FakeWebSocketConnection>()

        /** Thrown from connect (e.g. "WebSocket connect timeout after 50ms"). */
        var connectError: Exception? = null

        /** Scriptable hook fired when a connection is created. */
        var onConnect: ((FakeWebSocketConnection) -> Unit)? = null

        /** When set, connect returns this connection instead of a fresh one. */
        var connectStub: FakeWebSocketConnection? = null

        override suspend fun connect(
            url: String,
            headers: Map<String, String>,
            connectTimeoutMs: Long,
        ): WebSocketConnection {
            requests.add(url to headers)
            connectError?.let { throw it }
            val connection = connectStub ?: FakeWebSocketConnection()
            if (connectStub == null) connections.add(connection)
            onConnect?.invoke(connection)
            return connection
        }
    }

    private fun api(
        http: FakeTransport,
        ws: FakeWebSocketTransport?,
    ) = OpenAICodexResponsesApi(http, webSocketTransport = ws)

    // ------------------------------------------------------------------
    // Server event fixtures (pi's buildSSEPayload / mock shapes)
    // ------------------------------------------------------------------

    private fun textEvents(responseId: String, text: String = "Hello", endTurn: Boolean? = false) = listOf(
        buildJsonObject {
            put("type", "response.created")
            putJsonObject("response") { put("id", responseId) }
        },
        buildJsonObject {
            put("type", "response.output_item.added")
            put("output_index", 0)
            putJsonObject("item") {
                put("type", "message")
                put("id", "msg_1")
                put("role", "assistant")
                put("status", "in_progress")
            }
        },
        buildJsonObject {
            put("type", "response.output_text.delta")
            put("output_index", 0)
            put("delta", text)
        },
        buildJsonObject {
            put("type", "response.output_item.done")
            put("output_index", 0)
            putJsonObject("item") {
                put("type", "message")
                put("id", "msg_1")
                put("role", "assistant")
                put("status", "completed")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
            }
        },
        buildJsonObject {
            put("type", "response.completed")
            putJsonObject("response") {
                put("id", responseId)
                put("status", "completed")
                endTurn?.let { put("end_turn", it) }
                putJsonObject("usage") {
                    put("input_tokens", 5)
                    put("output_tokens", 3)
                    put("total_tokens", 8)
                }
            }
        },
    )

    private fun completedOnly(responseId: String) = listOf(
        buildJsonObject {
            put("type", "response.completed")
            putJsonObject("response") {
                put("id", responseId)
                put("status", "completed")
                putJsonObject("usage") {
                    put("input_tokens", 5)
                    put("output_tokens", 3)
                    put("total_tokens", 8)
                }
            }
        },
    )

    private fun errorEvent(code: String, message: String) = buildJsonObject {
        put("type", "error")
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    private fun frameOf(connection: FakeWebSocketConnection, index: Int = 0): JsonObject =
        responsesJson.parseToJsonElement(connection.sent[index]).jsonObject

    private fun messageOf(events: List<AssistantMessageEvent>): AssistantMessage =
        when (val last = events.last()) {
            is AssistantMessageEvent.Done -> last.message
            is AssistantMessageEvent.Error -> last.error
            else -> error("expected a terminal event, got $last")
        }


    private fun sseChunks(text: String = "Hello") = listOf(
        """{"type":"response.output_item.added","output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress"}}""",
        """{"type":"response.output_text.delta","output_index":0,"delta":"$text"}""",
        """{"type":"response.done","response":{"id":"resp_sse","status":"completed","usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}""",
        "[DONE]",
    )

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `auto transport uses websocket with cached context`() = runTest {
        cleanSlate()
        val ws = FakeWebSocketTransport()
        val http = FakeTransport()
        ws.onConnect = { it.onSend = { _ -> it.serverAll(textEvents("resp_1")) } }
        val api = api(http, ws)

        val events = api.stream(
            model,
            Context(systemPrompt = "You are a helpful assistant.", messages = listOf(UserMessage.ofText("Say hello"))),
            OpenAICodexResponsesOptions(
                apiKey = jwt("acc_test"),
                sessionId = "session-auto",
                transport = Transport.AUTO,
            ),
        ).toList()

        val done = assertIs<AssistantMessageEvent.Done>(events.last())
        assertEquals(false, done.message.endTurn)
        val connection = ws.connections.single()
        val frame = frameOf(connection)
        assertEquals("response.create", frame["type"]!!.toString().trim('"'))
        assertNull(frame["previous_response_id"])
        assertEquals("gpt-5.1-codex", frame["model"]!!.toString().trim('"'))
        val (url, headers) = ws.requests.single()
        assertEquals("wss://chatgpt.com/backend-api/codex/responses", url)
        assertEquals("session-auto", headers["session-id"])
        assertEquals("session-auto", headers["x-client-request-id"])
        assertEquals("responses_websockets=2026-02-06", headers["OpenAI-Beta"])
        assertEquals("acc_test", headers["chatgpt-account-id"])
        assertTrue(http.requests.isEmpty())
        val stats = getOpenAICodexWebSocketDebugStats("session-auto")!!
        assertEquals(1, stats.cachedContextRequests)
        assertEquals(1, stats.fullContextRequests)
    }

    @Test
    fun `second request sends previous_response_id and the input delta only`() = runTest {
        cleanSlate()
        val ws = FakeWebSocketTransport()
        val http = FakeTransport()
        val api = api(http, ws)

        val firstContext = Context(
            systemPrompt = "You are a helpful assistant.",
            messages = listOf(UserMessage.ofText("Say hello")),
        )
        var responseCount = 0
        ws.onConnect = { connection ->
            connection.onSend = { _ ->
                responseCount++
                connection.serverAll(textEvents("resp_$responseCount"))
            }
        }

        val firstEvents = api.stream(
            model,
            firstContext,
            OpenAICodexResponsesOptions(apiKey = jwt("a"), sessionId = "session-1", transport = Transport.WEBSOCKET_CACHED),
        ).toList()
        val first = messageOf(firstEvents)
        assertEquals(StopReason.STOP, first.stopReason)

        val secondContext = Context(
            systemPrompt = "You are a helpful assistant.",
            messages = firstContext.messages + first + UserMessage.ofText("Now finish"),
        )
        val secondEvents = api.stream(
            model,
            secondContext,
            OpenAICodexResponsesOptions(apiKey = jwt("a"), sessionId = "session-1", transport = Transport.WEBSOCKET_CACHED),
        ).toList()
        val second = messageOf(secondEvents)
        assertEquals(StopReason.STOP, second.stopReason)

        assertEquals(1, ws.connections.size)
        val connection = ws.connections[0]
        assertEquals(2, connection.sent.size)
        val firstFrame = frameOf(connection, 0)
        assertNull(firstFrame["previous_response_id"])
        val secondFrame = frameOf(connection, 1)
        assertEquals("resp_1", secondFrame["previous_response_id"]!!.toString().trim('"'))
        assertEquals(
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject { put("type", "input_text"); put("text", "Now finish") })
                            },
                        )
                    },
                )
            },
            secondFrame["input"],
        )
        val stats = getOpenAICodexWebSocketDebugStats("session-1")!!
        assertEquals(2, stats.requests)
        assertEquals(1, stats.connectionsCreated)
        assertEquals(1, stats.connectionsReused)
        assertEquals(2, stats.cachedContextRequests)
        assertEquals(0, stats.storeTrueRequests)
        assertEquals(1, stats.fullContextRequests)
        assertEquals(1, stats.deltaRequests)
        assertEquals(1, stats.lastDeltaInputItems)
        assertEquals("resp_1", stats.lastPreviousResponseId)
    }

    @Test
    fun `cached websockets are scoped to the authenticated account`() = runTest {
        cleanSlate()
        val ws = FakeWebSocketTransport()
        val http = FakeTransport()
        val api = api(http, ws)
        var responseId = 0
        ws.onConnect = { connection ->
            connection.onSend = { _ ->
                responseId++
                connection.serverAll(completedOnly("resp_$responseId"))
            }
        }
        val context = Context(systemPrompt = "", messages = emptyList())

        suspend fun runOnce(key: String) {
            api.stream(
                model,
                context,
                OpenAICodexResponsesOptions(
                    apiKey = key,
                    sessionId = "shared-session",
                    transport = Transport.WEBSOCKET_CACHED,
                ),
            ).toList()
        }
        runOnce(jwt("account-a"))
        runOnce(jwt("account-b"))
        runOnce(jwt("account-a"))

        // account-b must not reuse account-a's socket, but the third request
        // reuses the pooled account-a connection.
        assertEquals(2, ws.connections.size)
        assertEquals(
            listOf("account-a", "account-b"),
            ws.requests.map { it.second["chatgpt-account-id"]!! },
        )
        assertEquals(
            listOf("Bearer ${jwt("account-a")}", "Bearer ${jwt("account-b")}"),
            ws.requests.map { it.second["Authorization"]!! },
        )
        assertTrue(http.requests.isEmpty())
        val stats = getOpenAICodexWebSocketDebugStats("shared-session")!!
        assertEquals(2, stats.connectionsCreated)
        assertEquals(1, stats.connectionsReused)
    }

    @Test
    fun `closes one-shot websockets when cacheRetention is none`() = runTest {
        cleanSlate()
        val ws = FakeWebSocketTransport()
        val http = FakeTransport()
        val api = api(http, ws)
        ws.onConnect = { it.onSend = { _ -> it.serverAll(completedOnly("resp_${ws.connections.size}")) } }

        val options = OpenAICodexResponsesOptions(
            apiKey = jwt("acc_test"),
            cacheRetention = CacheRetention.NONE,
            sessionId = "one-off-summary",
            transport = Transport.AUTO,
        )
        val context = Context(systemPrompt = "You are a helpful assistant.", messages = listOf(UserMessage.ofText("Say hello")))
        api.stream(model, context, options).toList()
        api.stream(model, context, options).toList()

        assertEquals(2, ws.connections.size)
        ws.connections.forEach { assertFalse(it.isOpen) }
        assertEquals(2, ws.connections.sumOf { it.sent.size })
        ws.connections.forEach { assertNull(frameOf(it)["prompt_cache_key"]) }
        assertNull(getOpenAICodexWebSocketDebugStats("one-off-summary"))
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `falls back to sse when websocket connect times out and the session stays sticky`() = runTest {
        cleanSlate()
        val ws = FakeWebSocketTransport()
        ws.connectError = IOException("WebSocket connect timeout after 50ms")
        val http = FakeTransport()
        http.enqueueResponse(sse(*sseChunks().toTypedArray()))
        http.enqueueResponse(sse(*sseChunks().toTypedArray()))
        val api = api(http, ws)
        val context = Context(systemPrompt = "You are a helpful assistant.", messages = listOf(UserMessage.ofText("Say hello")))

        val events = api.stream(
            model,
            context,
            OpenAICodexResponsesOptions(
                apiKey = jwt("acc_test"),
                sessionId = "ws-connect-timeout",
                transport = Transport.AUTO,
                timeoutMs = 300_000,
                websocketConnectTimeoutMs = 50,
            ),
        ).toList()
        val result = messageOf(events)
        assertTrue(result.content.any { it is TextContent && it.text == "Hello" })
        assertEquals(1, http.requests.size)
        val stats = getOpenAICodexWebSocketDebugStats("ws-connect-timeout")!!
        assertEquals(1, stats.websocketFailures)
        assertEquals(1, stats.sseFallbacks)
        assertEquals(true, stats.websocketFallbackActive)
        assertEquals("WebSocket connect timeout after 50ms", stats.lastWebSocketError)

        // The session is sticky: the second request skips the WS path entirely.
        api.stream(
            model,
            context,
            OpenAICodexResponsesOptions(
                apiKey = jwt("acc_test"),
                sessionId = "ws-connect-timeout",
                transport = Transport.AUTO,
            ),
        ).toList()
        assertEquals(2, http.requests.size)
        assertEquals(1, ws.requests.size)
        assertEquals(2, getOpenAICodexWebSocketDebugStats("ws-connect-timeout")!!.sseFallbacks)
    }

    @Test
    fun `reconnects once when the connection limit is reached before output starts`() = runTest {
        cleanSlate()
        val ws = FakeWebSocketTransport()
        val http = FakeTransport()
        val api = api(http, ws)
        ws.onConnect = { connection ->
            connection.onSend = { _ ->
                if (ws.connections.indexOf(connection) == 0) {
                    connection.server(errorEvent("websocket_connection_limit_reached", "Connection limit reached"))
                } else {
                    connection.serverAll(completedOnly("resp_1"))
                }
            }
        }

        val events = api.stream(
            model,
            Context(systemPrompt = "", messages = emptyList()),
            OpenAICodexResponsesOptions(apiKey = jwt("acc_test")),
        ).toList()
        assertEquals(StopReason.STOP, messageOf(events).stopReason)
        assertEquals(2, ws.connections.size)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `falls back to sse when idle before the first event`() = runTest {
        cleanSlate()
        val ws = FakeWebSocketTransport()
        val http = FakeTransport()
        http.enqueueResponse(sse(*sseChunks().toTypedArray()))
        val api = api(http, ws)
        val context = Context(systemPrompt = "You are a helpful assistant.", messages = listOf(UserMessage.ofText("Say hello")))

        val events = api.stream(
            model,
            context,
            OpenAICodexResponsesOptions(
                apiKey = jwt("acc_test"),
                sessionId = "ws-idle-before-start",
                transport = Transport.AUTO,
                timeoutMs = 50,
            ),
        ).toList()
        val result = messageOf(events)
        assertTrue(result.content.any { it is TextContent && it.text == "Hello" })
        assertEquals(1, http.requests.size)
        val stats = getOpenAICodexWebSocketDebugStats("ws-idle-before-start")!!
        assertEquals(1, stats.websocketFailures)
        assertEquals(1, stats.sseFallbacks)
        assertEquals(true, stats.websocketFallbackActive)
    }

    @Test
    fun `errors when idle after the stream started`() = runTest {
        cleanSlate()
        val ws = FakeWebSocketTransport()
        val http = FakeTransport()
        val api = api(http, ws)
        ws.onConnect = { connection ->
            connection.onSend = { _ ->
                connection.server(
                    buildJsonObject {
                        put("type", "response.output_item.added")
                        put("output_index", 0)
                        putJsonObject("item") {
                            put("type", "message")
                            put("id", "msg_1")
                            put("role", "assistant")
                            put("status", "in_progress")
                        }
                    },
                )
                // then nothing: idle timeout fires
            }
        }

        val events = api.stream(
            model,
            Context(systemPrompt = "You are a helpful assistant.", messages = listOf(UserMessage.ofText("Say hello"))),
            OpenAICodexResponsesOptions(apiKey = jwt("acc_test"), transport = Transport.AUTO, timeoutMs = 50),
        ).toList()
        val result = messageOf(events)
        assertEquals(StopReason.ERROR, result.stopReason)
        assertEquals("WebSocket idle timeout after 50ms", result.errorMessage)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `opens a fresh connection past the backend connection age limit`() = runTest {
        cleanSlate()
        val ws = FakeWebSocketTransport()
        val http = FakeTransport()
        val api = api(http, ws)
        val startAt = 1_000_000L
        OpenAICodexWebSocketSessions.nowMs = { startAt }
        var responseCount = 0
        ws.onConnect = { connection ->
            connection.onSend = { _ ->
                responseCount++
                connection.serverAll(completedOnly("resp_$responseCount"))
            }
        }
        val firstContext = Context(
            systemPrompt = "You are a helpful assistant.",
            messages = listOf(UserMessage.ofText("Say hello")),
        )
        api.stream(
            model,
            firstContext,
            OpenAICodexResponsesOptions(apiKey = jwt("acc_test"), sessionId = "aged-ws-session", transport = Transport.WEBSOCKET_CACHED),
        ).toList()
        OpenAICodexWebSocketSessions.nowMs = { startAt + 56 * 60 * 1000 }
        val secondContext = Context(
            systemPrompt = "You are a helpful assistant.",
            messages = firstContext.messages + UserMessage.ofText("Now finish"),
        )
        api.stream(
            model,
            secondContext,
            OpenAICodexResponsesOptions(apiKey = jwt("acc_test"), sessionId = "aged-ws-session", transport = Transport.WEBSOCKET_CACHED),
        ).toList()

        assertEquals(2, ws.connections.size)
        assertFalse(ws.connections[0].isOpen)
        val stats = getOpenAICodexWebSocketDebugStats("aged-ws-session")!!
        assertEquals(2, stats.connectionsCreated)
        assertEquals(0, stats.connectionsReused)
    }

    @Test
    fun `previous_response_not_found is retried once with the full body`() = runTest {
        cleanSlate()
        val ws = FakeWebSocketTransport()
        val http = FakeTransport()
        val api = api(http, ws)
        val context = Context(
            systemPrompt = "You are a helpful assistant.",
            messages = listOf(UserMessage.ofText("Say hello")),
        )
        var responseCount = 0
        ws.onConnect = { connection ->
            connection.onSend = { _ ->
                responseCount++
                when (responseCount) {
                    1 -> connection.serverAll(textEvents("resp_1"))
                    // Delta request on the pooled socket: the server-side
                    // continuation was dropped.
                    2 -> connection.server(errorEvent("previous_response_not_found", "Previous response with id 'resp_1' not found."))
                    else -> connection.serverAll(textEvents("resp_2", "Recovered"))
                }
            }
        }

        val first = api.stream(
            model,
            context,
            OpenAICodexResponsesOptions(apiKey = jwt("acc_test"), sessionId = "missing", transport = Transport.WEBSOCKET_CACHED),
        ).toList()
        assertEquals(StopReason.STOP, messageOf(first).stopReason)

        val secondContext = Context(
            systemPrompt = "You are a helpful assistant.",
            messages = context.messages + messageOf(first) + UserMessage.ofText("Now finish"),
        )
        val second = api.stream(
            model,
            secondContext,
            OpenAICodexResponsesOptions(apiKey = jwt("acc_test"), sessionId = "missing", transport = Transport.WEBSOCKET_CACHED),
        ).toList()
        assertEquals(StopReason.STOP, messageOf(second).stopReason)
        assertEquals(1, second.count { it is AssistantMessageEvent.Start })

        // Connection 1 carried the full body and the failed delta; the retry
        // happened on a fresh connection with a full body (no prev id).
        assertEquals(2, ws.connections.size)
        assertEquals(2, ws.connections[0].sent.size)
        assertEquals(1, ws.connections[1].sent.size)
        assertEquals("resp_1", frameOf(ws.connections[0], 1)["previous_response_id"]!!.toString().trim('"'))
        assertNull(frameOf(ws.connections[1], 0)["previous_response_id"])
        val retryInput = frameOf(ws.connections[1], 0)["input"] as JsonArray
        assertEquals(3, retryInput.size)
        assertTrue(http.requests.isEmpty())
        val stats = getOpenAICodexWebSocketDebugStats("missing")!!
        assertEquals(3, stats.requests)
        assertEquals(2, stats.connectionsCreated)
        assertEquals(1, stats.connectionsReused)
        assertEquals(2, stats.fullContextRequests)
        assertEquals(1, stats.deltaRequests)
        assertEquals(0, stats.websocketFailures)
        assertEquals(0, stats.sseFallbacks)
    }

    @Test
    fun `abort closes the pooled socket`() = runTest {
        cleanSlate()
        val ws = FakeWebSocketTransport()
        val http = FakeTransport()
        val connection = FakeWebSocketConnection()
        connection.onSend = { _ ->
            // One non-terminal event, then stall.
            connection.server(
                buildJsonObject {
                    put("type", "response.output_item.added")
                    put("output_index", 0)
                    putJsonObject("item") {
                        put("type", "message")
                        put("id", "msg_1")
                        put("role", "assistant")
                        put("status", "in_progress")
                    }
                },
            )
        }
        ws.connectStub = connection
        val api = OpenAICodexResponsesApi(http, webSocketTransport = ws)

        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            api.stream(
                model,
                Context(systemPrompt = "You are a helpful assistant.", messages = listOf(UserMessage.ofText("Say hello"))),
                OpenAICodexResponsesOptions(apiKey = jwt("acc_test"), sessionId = "abort-session", transport = Transport.AUTO),
            ).toList()
        }
        while (connection.sent.isEmpty()) yield()
        job.cancelAndJoin()
        assertFalse(connection.isOpen)
    }

    @Test
    fun `close before completion is a transport error`() = runTest {
        cleanSlate()
        val ws = FakeWebSocketTransport()
        val http = FakeTransport()
        http.enqueueResponse(sse(*sseChunks().toTypedArray()))
        val api = api(http, ws)
        ws.onConnect = { connection ->
            connection.onSend = { _ ->
                connection.closedByServer(code = 1011, reason = "boom")
            }
        }
        val events = api.stream(
            model,
            Context(systemPrompt = "You are a helpful assistant.", messages = listOf(UserMessage.ofText("Say hello"))),
            OpenAICodexResponsesOptions(apiKey = jwt("acc_test"), transport = Transport.AUTO),
        ).toList()
        // Closed before anything started: transport failure -> SSE fallback.
        assertEquals(1, http.requests.size)
        assertTrue(events.last() is AssistantMessageEvent.Done)
    }

    @Test
    fun `null webSocketTransport throws a transport error and falls back to sse`() = runTest {
        cleanSlate()
        val http = FakeTransport()
        http.enqueueResponse(sse(*sseChunks().toTypedArray()))
        val api = OpenAICodexResponsesApi(http, webSocketTransport = null)
        val events = api.stream(
            model,
            Context(systemPrompt = "You are a helpful assistant.", messages = listOf(UserMessage.ofText("Say hello"))),
            OpenAICodexResponsesOptions(apiKey = jwt("acc_test"), sessionId = "no-ws"),
        ).toList()
        assertTrue(events.last() is AssistantMessageEvent.Done)
        val stats = getOpenAICodexWebSocketDebugStats("no-ws")!!
        assertEquals("WebSocket transport is not available in this runtime", stats.lastWebSocketError)
    }

    @Test
    fun `resolveCodexWebSocketUrl maps schemes`() {
        assertEquals(
            "wss://chatgpt.com/backend-api/codex/responses",
            resolveCodexWebSocketUrl("https://chatgpt.com/backend-api"),
        )
        assertEquals(
            "ws://localhost:8080/codex/responses",
            resolveCodexWebSocketUrl("http://localhost:8080"),
        )
    }

    @Test
    fun `websocket headers carry the beta flag and drop sse-only headers`() {
        val headers = buildCodexWebSocketHeaders(
            modelHeaders = emptyMap(),
            optionsHeaders = mapOf("accept" to "text/event-stream", "content-type" to "application/json"),
            accountId = "acc",
            token = "tok",
            requestId = "req-1",
        )
        assertEquals("responses_websockets=2026-02-06", headers["OpenAI-Beta"])
        assertNull(headers["accept"])
        assertNull(headers["content-type"])
        assertEquals("req-1", headers["x-client-request-id"])
        assertEquals("req-1", headers["session-id"])
        assertEquals("Bearer tok", headers["Authorization"])
        assertEquals("acc", headers["chatgpt-account-id"])
    }
}
