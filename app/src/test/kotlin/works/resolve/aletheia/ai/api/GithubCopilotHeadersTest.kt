package works.resolve.aletheia.ai.api

import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.ImageContent
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.ModelCost
import works.resolve.aletheia.ai.core.OpenAiCompletionsOptions
import works.resolve.aletheia.ai.core.OpenAiResponsesCompat
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.ToolResultMessage
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.ai.testing.FakeTransport
import works.resolve.aletheia.ai.testing.sse
import works.resolve.aletheia.ai.utils.ProviderRetry
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the GitHub Copilot dynamic request headers port
 * (GithubCopilotHeaders.kt, mirroring pi's
 * packages/ai/src/api/github-copilot-headers.ts) and their wiring into the
 * three wire protocols, mirroring pi's
 * test/github-copilot-anthropic.test.ts header assertions and the call-site
 * behavior of openai-completions.ts / openai-responses.ts /
 * anthropic-messages.ts createClient.
 */
class GithubCopilotHeadersTest {

    // =========================================================================
    // Helper unit tests (pi's github-copilot-headers.ts semantics)
    // =========================================================================

    @Test
    fun `inferCopilotInitiator marks non-user last messages as agent`() {
        assertEquals(CopilotInitiator.USER, inferCopilotInitiator(emptyList()))
        assertEquals(
            CopilotInitiator.USER,
            inferCopilotInitiator(listOf(UserMessage.ofText("hi"))),
        )
        assertEquals(
            CopilotInitiator.AGENT,
            inferCopilotInitiator(
                listOf(
                    UserMessage.ofText("hi"),
                    AssistantMessage(
                        content = listOf(TextContent("hello")),
                        api = "openai-completions",
                        provider = "github-copilot",
                        model = "gpt-5",
                    ),
                ),
            ),
        )
        assertEquals(
            CopilotInitiator.AGENT,
            inferCopilotInitiator(
                listOf(
                    UserMessage.ofText("hi"),
                    ToolResultMessage("call_1", "tool", listOf(TextContent("result"))),
                ),
            ),
        )
    }

    @Test
    fun `hasCopilotVisionInput counts images in user messages and tool results only`() {
        val image = ImageContent(data = "aGk=", mimeType = "image/png")
        assertFalse(hasCopilotVisionInput(emptyList()))
        assertFalse(hasCopilotVisionInput(listOf(UserMessage.ofText("hi"))))
        assertTrue(
            hasCopilotVisionInput(listOf(UserMessage(listOf(TextContent("look"), image)))),
        )
        assertTrue(
            hasCopilotVisionInput(
                listOf(
                    UserMessage.ofText("hi"),
                    ToolResultMessage("call_1", "tool", listOf(image)),
                ),
            ),
        )
        // Assistant images do not trigger Copilot-Vision-Request.
        assertFalse(
            hasCopilotVisionInput(
                listOf(
                    AssistantMessage(
                        content = listOf(image),
                        api = "openai-completions",
                        provider = "github-copilot",
                        model = "gpt-5",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `buildCopilotDynamicHeaders sends base headers and vision only with images`() {
        val messages = listOf(UserMessage.ofText("hi"))
        assertEquals(
            mapOf(
                "X-Initiator" to "user",
                "Openai-Intent" to "conversation-edits",
            ),
            buildCopilotDynamicHeaders(messages, hasImages = false),
        )
        assertEquals(
            mapOf(
                "X-Initiator" to "user",
                "Openai-Intent" to "conversation-edits",
                "Copilot-Vision-Request" to "true",
            ),
            buildCopilotDynamicHeaders(messages, hasImages = true),
        )
    }

    // =========================================================================
    // Shared request fixtures
    // =========================================================================

    private fun copilotModel(
        api: String,
        headers: Map<String, String> = mapOf("Copilot-Integration-Id" to "vscode"),
    ) = Model(
        id = "gpt-5",
        name = "GPT-5",
        api = api,
        provider = "github-copilot",
        baseUrl = "https://api.individual.githubcopilot.com",
        headers = headers,
        contextWindow = 400_000,
        maxTokens = 128_000,
        responsesCompat = if (api == "openai-responses") OpenAiResponsesCompat() else null,
    )

    private val image = ImageContent(data = "aGk=", mimeType = "image/png")

    private fun userContext() = Context(
        messages = listOf(UserMessage(listOf(TextContent("hello")))),
    )

    private fun toolInitiatedContext() = Context(
        messages = listOf(
            UserMessage.ofText("hi"),
            AssistantMessage(
                content = listOf(TextContent("calling")),
                api = "openai-completions",
                provider = "github-copilot",
                model = "gpt-5",
            ),
            ToolResultMessage("call_1", "tool", listOf(TextContent("result"))),
        ),
    )

    private fun visionContext() = Context(
        messages = listOf(UserMessage(listOf(TextContent("look"), image))),
    )

    private fun retry() = ProviderRetry(sleep = {}, nowMs = { 0L }, random = { 0.0 })

    /** Header lookups are case-insensitive at HTTP layer; assert the sent casing. */
    private fun sent(request: works.resolve.aletheia.ai.transport.TransportRequest): Map<String, String> =
        request.headers

    private fun jsonBody(request: works.resolve.aletheia.ai.transport.TransportRequest) =
        Json.parseToJsonElement(request.body.decodeToString()).jsonObject

    // =========================================================================
    // openai-completions wiring (pi's openai-completions.ts createClient)
    // =========================================================================

    @Test
    fun `completions sends dynamic headers for user and tool initiation and vision`() = runTest {
        val copilot = copilotModel("openai-completions")

        fun completions(transport: FakeTransport) = OpenAiCompletionsApi(transport, retry())

        val userTransport = FakeTransport()
        userTransport.enqueueResponse(
            sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"),
        )
        completions(userTransport).stream(copilot, userContext(), OpenAiCompletionsOptions(apiKey = "tok"))
            .take(1).toList()
        val userHeaders = sent(userTransport.requests.single())
        assertEquals("user", userHeaders["X-Initiator"])
        assertEquals("conversation-edits", userHeaders["Openai-Intent"])
        assertNull(userHeaders["Copilot-Vision-Request"])
        assertEquals("gpt-5", jsonBody(userTransport.requests.single())["model"]!!.jsonPrimitive.content)

        val toolTransport = FakeTransport()
        toolTransport.enqueueResponse(
            sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"),
        )
        completions(toolTransport).stream(copilot, toolInitiatedContext(), OpenAiCompletionsOptions(apiKey = "tok"))
            .take(1).toList()
        val toolHeaders = sent(toolTransport.requests.single())
        assertEquals("agent", toolHeaders["X-Initiator"])

        val visionTransport = FakeTransport()
        visionTransport.enqueueResponse(
            sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"),
        )
        completions(visionTransport).stream(copilot, visionContext(), OpenAiCompletionsOptions(apiKey = "tok"))
            .take(1).toList()
        assertEquals("true", sent(visionTransport.requests.single())["Copilot-Vision-Request"])
    }

    @Test
    fun `completions dynamic headers override model headers but options headers win`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"),
        )
        OpenAiCompletionsApi(transport, retry()).stream(
            copilotModel(
                "openai-completions",
                headers = mapOf("X-Initiator" to "stale", "Copilot-Integration-Id" to "vscode"),
            ),
            userContext(),
            OpenAiCompletionsOptions(
                apiKey = "tok",
                headers = mapOf("Openai-Intent" to "custom-intent"),
            ),
        ).take(1).toList()
        val headers = sent(transport.requests.single())
        // Dynamic headers override the model's static headers...
        assertEquals("user", headers["X-Initiator"])
        // ...but explicit request headers override the dynamic ones.
        assertEquals("custom-intent", headers["Openai-Intent"])
    }

    @Test
    fun `completions sends no copilot headers for other providers`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            sse("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]"),
        )
        OpenAiCompletionsApi(transport, retry()).stream(
            copilotModel("openai-completions").copy(provider = "zai"),
            userContext(),
            OpenAiCompletionsOptions(apiKey = "tok"),
        ).take(1).toList()
        val headers = sent(transport.requests.single())
        assertNull(headers["X-Initiator"])
        assertNull(headers["Openai-Intent"])
    }

    // =========================================================================
    // openai-responses wiring (pi's openai-responses.ts createClient)
    // =========================================================================

    private fun responsesChunk() = listOf(
        """{"type":"response.output_item.added","output_index":0,
            "item":{"type":"message","id":"msg_1","role":"assistant","status":"in_progress"}}""",
        """{"type":"response.output_text.delta","output_index":0,"delta":"ok"}""",
        """{"type":"response.output_item.done","output_index":0,
            "item":{"type":"message","id":"msg_1","role":"assistant","status":"completed",
                "content":[{"type":"output_text","text":"ok","annotations":[]}]}}""",
        """{"type":"response.completed","response":{"id":"resp_1","status":"completed",
            "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}""",
        "[DONE]",
    )

    @Test
    fun `responses sends dynamic headers for user and tool initiation and vision`() = runTest {
        val copilot = copilotModel("openai-responses")

        fun responses(transport: FakeTransport) = OpenAiResponsesApi(transport, retry())

        val userTransport = FakeTransport()
        userTransport.enqueueResponse(sse(*responsesChunk().toTypedArray()))
        responses(userTransport).stream(copilot, userContext(), OpenAiResponsesOptions(apiKey = "tok")).take(1).toList()
        val userHeaders = sent(userTransport.requests.single())
        assertEquals("user", userHeaders["X-Initiator"])
        assertEquals("conversation-edits", userHeaders["Openai-Intent"])
        assertNull(userHeaders["Copilot-Vision-Request"])
        assertEquals("gpt-5", jsonBody(userTransport.requests.single())["model"]!!.jsonPrimitive.content)

        val toolTransport = FakeTransport()
        toolTransport.enqueueResponse(sse(*responsesChunk().toTypedArray()))
        responses(toolTransport).stream(copilot, toolInitiatedContext(), OpenAiResponsesOptions(apiKey = "tok"))
            .take(1).toList()
        assertEquals("agent", sent(toolTransport.requests.single())["X-Initiator"])

        val visionTransport = FakeTransport()
        visionTransport.enqueueResponse(sse(*responsesChunk().toTypedArray()))
        responses(visionTransport).stream(copilot, visionContext(), OpenAiResponsesOptions(apiKey = "tok"))
            .take(1).toList()
        assertEquals("true", sent(visionTransport.requests.single())["Copilot-Vision-Request"])
    }

    @Test
    fun `responses dynamic headers override model headers but options headers win`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(sse(*responsesChunk().toTypedArray()))
        OpenAiResponsesApi(transport, retry()).stream(
            copilotModel(
                "openai-responses",
                headers = mapOf("X-Initiator" to "stale"),
            ),
            userContext(),
            OpenAiResponsesOptions(
                apiKey = "tok",
                headers = mapOf("Openai-Intent" to "custom-intent"),
            ),
        ).take(1).toList()
        val headers = sent(transport.requests.single())
        assertEquals("user", headers["X-Initiator"])
        assertEquals("custom-intent", headers["Openai-Intent"])
    }

    // =========================================================================
    // anthropic-messages wiring (pi's anthropic-messages.ts createClient)
    // =========================================================================

    private fun anthropicChunk() = listOf(
        null to """{"type":"message_start","message":{"id":"msg_test",
            "usage":{"input_tokens":10,"output_tokens":0}}}""",
        null to """{"type":"message_delta","delta":{"stop_reason":"end_turn"},
            "usage":{"output_tokens":5}}}""",
        null to """{"type":"message_stop"}""",
    )

    @Test
    fun `anthropic sends dynamic headers for user and tool initiation and vision`() = runTest {
        val copilot = copilotModel("anthropic-messages")

        fun anthropic(transport: FakeTransport) = AnthropicMessagesApi(transport, retry())

        val userTransport = FakeTransport()
        userTransport.enqueueNamedResponse(anthropicChunk())
        anthropic(userTransport).stream(copilot, userContext(), AnthropicMessagesOptions(apiKey = "tok"))
            .take(1).toList()
        val userHeaders = sent(userTransport.requests.single())
        assertEquals("user", userHeaders["X-Initiator"])
        assertEquals("conversation-edits", userHeaders["Openai-Intent"])
        assertNull(userHeaders["Copilot-Vision-Request"])
        assertEquals("gpt-5", jsonBody(userTransport.requests.single())["model"]!!.jsonPrimitive.content)

        val toolTransport = FakeTransport()
        toolTransport.enqueueNamedResponse(anthropicChunk())
        anthropic(toolTransport).stream(copilot, toolInitiatedContext(), AnthropicMessagesOptions(apiKey = "tok"))
            .take(1).toList()
        assertEquals("agent", sent(toolTransport.requests.single())["X-Initiator"])

        val visionTransport = FakeTransport()
        visionTransport.enqueueNamedResponse(anthropicChunk())
        anthropic(visionTransport).stream(copilot, visionContext(), AnthropicMessagesOptions(apiKey = "tok"))
            .take(1).toList()
        assertEquals("true", sent(visionTransport.requests.single())["Copilot-Vision-Request"])
    }

    @Test
    fun `anthropic dynamic headers override model headers but options headers win`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(anthropicChunk())
        AnthropicMessagesApi(transport, retry()).stream(
            copilotModel(
                "anthropic-messages",
                headers = mapOf("X-Initiator" to "stale", "User-Agent" to "GitHubCopilotChat/1.0"),
            ),
            userContext(),
            AnthropicMessagesOptions(
                apiKey = "tok",
                headers = mapOf("Openai-Intent" to "custom-intent"),
            ),
        ).take(1).toList()
        val headers = sent(transport.requests.single())
        assertEquals("user", headers["X-Initiator"])
        assertEquals("custom-intent", headers["Openai-Intent"])
        // Model static headers still flow through.
        assertEquals("GitHubCopilotChat/1.0", headers["User-Agent"])
    }
}
