package works.resolve.pathfinder.ai.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import works.resolve.pathfinder.ai.AnthropicMessagesCompat
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelCost
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.transport.TransportRequest
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.normalizeContext

class AnthropicMessageThreadsTest {

    private val model = Model(
        id = "claude-sonnet-4-5",
        name = "Claude Sonnet 4.5",
        api = "anthropic-messages",
        provider = "anthropic",
        baseUrl = "https://api.anthropic.com",
        reasoning = true,
        input = listOf(InputModality.TEXT),
        cost = ModelCost(input = 3.0, output = 15.0, cacheRead = 0.3, cacheWrite = 3.75),
        contextWindow = 200_000,
        maxTokens = 64_000,
        anthropicCompat = AnthropicMessagesCompat(supportsMessageThreads = true)
    )

    private val options = AnthropicMessagesOptions(apiKey = "test-key", sessionId = "session-1")

    private val system = SystemMessage(
        content = listOf(TextContent("Be brief.")),
        toolsAdded = listOf(
            Tool("echo", "Echo text.", Json.parseToJsonElement("""{"type":"object"}"""))
        )
    )

    private fun api(transport: FakeTransport) = AnthropicMessagesApi(
        transport,
        ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 }),
        clock = FakeClock(1_770_000_000_000L)
    )

    private fun context(vararg messages: Message) =
        normalizeContext(Context(messages = messages.toList()))

    private fun textStream(id: String, vararg deltas: String) = listOf(
        "message_start" to
            """{"type":"message_start","message":{"id":"$id","model":"claude-sonnet-4-5","usage":{"input_tokens":12,"output_tokens":0}}}""",
        "content_block_start" to
            """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        *deltas.map {
            "content_block_delta" to
                """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$it"}}"""
        }.toTypedArray(),
        "content_block_stop" to """{"type":"content_block_stop","index":0}""",
        "message_delta" to
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""",
        "message_stop" to """{"type":"message_stop"}"""
    )

    private fun body(request: TransportRequest) =
        Json.parseToJsonElement(request.body.decodeToString()).jsonObject

    private fun messages(request: TransportRequest): JsonArray =
        body(request)["messages"]!!.jsonArray

    private fun threadType(request: TransportRequest): String? =
        body(request)["thread"]?.jsonObject?.get("type")?.jsonPrimitive?.content

    private fun betas(request: TransportRequest): List<String> =
        request.headers["anthropic-beta"]?.split(",") ?: emptyList()

    @Test
    fun `creates a thread then continues with only the new suffix`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("msg_1", "Hel", "lo"))
        val client = api(transport)
        val first = client.stream(
            model,
            context(system, UserMessage.ofText("hi")),
            options
        ).toList()
        val firstDone = assertIs<AssistantMessageEvent.Done>(first.last())

        assertEquals("create", threadType(transport.requests[0]))
        assertTrue(MESSAGE_THREADS_BETA in betas(transport.requests[0]))
        assertEquals(1, messages(transport.requests[0]).size)

        transport.enqueueNamedResponse(textStream("msg_2", "again"))
        val second = client.stream(
            model,
            context(
                system,
                UserMessage.ofText("hi"),
                firstDone.message,
                UserMessage.ofText("again")
            ),
            options
        ).toList()

        val request = transport.requests[1]
        val requestBody = body(request)
        assertEquals("continue", threadType(request))
        assertEquals(
            "msg_1",
            requestBody["thread"]!!.jsonObject["previous_message_id"]!!.jsonPrimitive.content
        )
        assertFalse(requestBody.containsKey("system"))
        assertFalse(requestBody.containsKey("tools"))
        assertEquals(1, messages(request).size)
        assertEquals(
            "again",
            messages(request)[0].jsonObject["content"]!!.jsonArray[0]
                .jsonObject["text"]!!.jsonPrimitive.content
        )

        assertIs<AssistantMessageEvent.Start>(second.first())
        val secondDone = assertIs<AssistantMessageEvent.Done>(second.last())
        assertEquals(StopReason.STOP, secondDone.reason)
        assertEquals(12, secondDone.message.usage.input)
        assertEquals(5, secondDone.message.usage.output)
        assertEquals(17, secondDone.message.usage.totalTokens)
        assertEquals("again", assertIs<TextContent>(secondDone.message.content[0]).text)
    }

    @Test
    fun `a changed prefix starts a new thread`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("msg_1", "hello"))
        val client = api(transport)
        val first = client.stream(
            model,
            context(system, UserMessage.ofText("hi")),
            options
        ).toList()
        val firstDone = assertIs<AssistantMessageEvent.Done>(first.last())

        transport.enqueueNamedResponse(textStream("msg_2", "other"))
        client.stream(
            model,
            context(
                system,
                UserMessage.ofText("different"),
                firstDone.message,
                UserMessage.ofText("again")
            ),
            options
        ).toList()

        val request = transport.requests[1]
        assertEquals("create", threadType(request))
        assertEquals(3, messages(request).size)
    }

    @Test
    fun `thread unsupported falls back once and turns threads off`() = runTest {
        val transport = FakeTransport()
        transport.enqueueError(
            400,
            """{"type":"error","error":{"type":"invalid_request_error","message":"thread: Extra inputs are not permitted","details":{"error_code":"thread_unsupported_request"}}}"""
        )
        transport.enqueueNamedResponse(textStream("msg_1", "ok"))
        val client = api(transport)
        val events = client.stream(
            model,
            context(system, UserMessage.ofText("hi")),
            options
        ).toList()
        val done = assertIs<AssistantMessageEvent.Done>(events.last())

        assertEquals(2, transport.requests.size)
        assertEquals("create", threadType(transport.requests[0]))
        assertFalse(body(transport.requests[1]).containsKey("thread"))
        assertTrue(MESSAGE_THREADS_BETA in betas(transport.requests[1]))
        val diagnostic = done.message.diagnostics.single {
            it.type == "anthropic_thread_fallback"
        }
        assertEquals(
            "thread_unsupported_request",
            diagnostic.details!!["reason"]!!.jsonPrimitive.content
        )

        transport.enqueueNamedResponse(textStream("msg_2", "next"))
        client.stream(
            model,
            context(
                system,
                UserMessage.ofText("hi"),
                done.message,
                UserMessage.ofText("next")
            ),
            options
        ).toList()

        // "off" still sends the threads beta, which the accepted resend used.
        assertFalse(body(transport.requests[2]).containsKey("thread"))
        assertTrue(MESSAGE_THREADS_BETA in betas(transport.requests[2]))
    }

    @Test
    fun `a thread rejection that a new thread fixes resends in full`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("msg_1", "hello"))
        val client = api(transport)
        val first = client.stream(
            model,
            context(system, UserMessage.ofText("hi")),
            options
        ).toList()
        val firstDone = assertIs<AssistantMessageEvent.Done>(first.last())

        transport.enqueueError(
            400,
            """{"type":"error","error":{"type":"invalid_request_error","message":"thread fingerprint mismatch","details":{"error_code":"thread_fingerprint_mismatch"}}}"""
        )
        transport.enqueueNamedResponse(textStream("msg_2", "again"))
        val second = client.stream(
            model,
            context(
                system,
                UserMessage.ofText("hi"),
                firstDone.message,
                UserMessage.ofText("again")
            ),
            options
        ).toList()

        assertEquals(3, transport.requests.size)
        assertEquals("continue", threadType(transport.requests[1]))
        assertEquals("create", threadType(transport.requests[2]))
        assertEquals(3, messages(transport.requests[2]).size)
        val done = assertIs<AssistantMessageEvent.Done>(second.last())
        assertEquals(
            "thread_fingerprint_mismatch",
            done.message.diagnostics.single {
                it.type == "anthropic_thread_fallback"
            }.details!!["reason"]!!.jsonPrimitive.content
        )

        // The replaced thread continues from the reply the resend produced.
        transport.enqueueNamedResponse(textStream("msg_3", "third"))
        client.stream(
            model,
            context(
                system,
                UserMessage.ofText("hi"),
                firstDone.message,
                UserMessage.ofText("again"),
                done.message,
                UserMessage.ofText("third")
            ),
            options
        ).toList()
        assertEquals(
            "msg_2",
            body(transport.requests[3])["thread"]!!
                .jsonObject["previous_message_id"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `disableContinuation neither continues nor records thread state`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("msg_1", "hello"))
        val client = api(transport)
        val first = client.stream(
            model,
            context(system, UserMessage.ofText("hi")),
            options
        ).toList()
        val firstDone = assertIs<AssistantMessageEvent.Done>(first.last())

        transport.enqueueNamedResponse(textStream("msg_2", "replay"))
        val second = client.stream(
            model,
            context(
                system,
                UserMessage.ofText("hi"),
                firstDone.message,
                UserMessage.ofText("again")
            ),
            options.copy(disableContinuation = true)
        ).toList()
        val secondDone = assertIs<AssistantMessageEvent.Done>(second.last())
        assertFalse(body(transport.requests[1]).containsKey("thread"))

        // The replay did not replace msg_1, so the next request continues it.
        transport.enqueueNamedResponse(textStream("msg_3", "third"))
        client.stream(
            model,
            context(
                system,
                UserMessage.ofText("hi"),
                firstDone.message,
                UserMessage.ofText("again"),
                secondDone.message,
                UserMessage.ofText("third")
            ),
            options
        ).toList()
        assertEquals("continue", threadType(transport.requests[2]))
        assertEquals(
            "msg_1",
            body(transport.requests[2])["thread"]!!
                .jsonObject["previous_message_id"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `streamSimple forwards disableContinuation`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("msg_1", "hello"))
        val client = api(transport)
        val first = client.stream(
            model,
            context(system, UserMessage.ofText("hi")),
            options
        ).toList()
        val firstDone = assertIs<AssistantMessageEvent.Done>(first.last())

        transport.enqueueNamedResponse(textStream("msg_2", "replay"))
        client.streamSimple(
            model,
            context(
                system,
                UserMessage.ofText("hi"),
                firstDone.message,
                UserMessage.ofText("again")
            ),
            SimpleStreamOptions(
                apiKey = "test-key",
                sessionId = "session-1",
                disableContinuation = true
            )
        ).toList()

        assertFalse(body(transport.requests[1]).containsKey("thread"))
    }

    @Test
    fun `models without thread support send no thread or threads beta`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(textStream("msg_1", "hello"))
        val client = api(transport)
        client.stream(
            model.copy(
                provider = "openrouter",
                baseUrl = "https://openrouter.ai/api/v1",
                anthropicCompat = AnthropicMessagesCompat(supportsMessageThreads = false)
            ),
            context(system, UserMessage.ofText("hi")),
            options
        ).toList()

        assertFalse(body(transport.requests[0]).containsKey("thread"))
        assertFalse(MESSAGE_THREADS_BETA in betas(transport.requests[0]))
    }

    @Test
    fun `the generated catalog enables threads only on Anthropic's own endpoint`() {
        val file = File("src/main/assets/models-catalog.json")
        val catalog = ProviderCatalog.parse(file.readText())
        assertTrue(
            catalog.getModel("anthropic", "claude-sonnet-4-5")!!
                .anthropicCompat.supportsMessageThreads
        )
        assertFalse(
            catalog.getModel("github-copilot", "claude-haiku-4.5")!!
                .anthropicCompat.supportsMessageThreads
        )
        assertFalse(
            catalog.getModel("openrouter", "~anthropic/claude-sonnet-latest")!!
                .anthropicCompat.supportsMessageThreads
        )
    }
}
