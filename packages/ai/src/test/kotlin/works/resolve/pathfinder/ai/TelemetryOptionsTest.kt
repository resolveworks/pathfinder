package works.resolve.pathfinder.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.api.AnthropicMessagesOptions
import works.resolve.pathfinder.ai.api.AzureOpenAiResponsesOptions
import works.resolve.pathfinder.ai.api.GoogleGenerativeAiApi
import works.resolve.pathfinder.ai.api.MistralOptions
import works.resolve.pathfinder.ai.api.OpenAICodexResponsesOptions
import works.resolve.pathfinder.ai.api.OpenAiCompletionsApi
import works.resolve.pathfinder.ai.api.OpenAiCompletionsOptions
import works.resolve.pathfinder.ai.api.OpenAiResponsesOptions
import works.resolve.pathfinder.ai.api.buildAzureOpenAiResponsesOptions
import works.resolve.pathfinder.ai.api.buildBaseOptions
import works.resolve.pathfinder.ai.api.buildGoogleOptions
import works.resolve.pathfinder.ai.api.buildMistralOptions
import works.resolve.pathfinder.ai.api.buildOpenAICodexResponsesOptions
import works.resolve.pathfinder.ai.api.buildOpenAiResponsesOptions
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.normalizeContext
import works.resolve.pathfinder.telemetry.InMemoryTelemetryContext
import works.resolve.pathfinder.telemetry.NOOP_TELEMETRY_CONTEXT
import works.resolve.pathfinder.telemetry.SpanOptions

/** The parent telemetry span reaches every request option surface. */
class TelemetryOptionsTest {

    private val telemetry = NOOP_TELEMETRY_CONTEXT
    private val context = normalizeContext(Context(messages = emptyList()))

    private val model = Model(
        id = "model",
        name = "Model",
        api = "telemetry-test",
        provider = "telemetry-provider",
        baseUrl = "https://example.test",
        reasoning = false,
        input = listOf(InputModality.TEXT),
        cost = ModelCost(),
        contextWindow = 1000,
        maxTokens = 100
    )

    @Test
    fun `telemetry is inherited by the core stream option surfaces`() {
        assertSame(telemetry, StreamOptions(telemetry = telemetry).telemetry)
        assertSame(telemetry, SimpleStreamOptions(telemetry = telemetry).telemetry)
    }

    @Test
    fun `telemetry defaults to null on every request option surface`() {
        assertNull(StreamOptions().telemetry)
        assertNull(SimpleStreamOptions().telemetry)
        assertNull(OpenAiCompletionsOptions().telemetryContext)
        assertNull(OpenAiResponsesOptions().telemetryContext)
        assertNull(AzureOpenAiResponsesOptions().telemetryContext)
        assertNull(OpenAICodexResponsesOptions().telemetryContext)
        assertNull(AnthropicMessagesOptions().telemetryContext)
        assertNull(GoogleGenerativeAiApi.GoogleOptions().telemetryContext)
        assertNull(MistralOptions().telemetryContext)
    }

    @Test
    fun `telemetry survives every simple to base conversion by identity`() {
        val options = SimpleStreamOptions(telemetry = telemetry)
        assertSame(telemetry, options.toStreamOptions(null).telemetryContext)
        assertSame(telemetry, buildBaseOptions(model, context, options).telemetryContext)
        assertSame(
            telemetry,
            buildOpenAiResponsesOptions(model, context, options, null).telemetryContext
        )
        assertSame(
            telemetry,
            buildAzureOpenAiResponsesOptions(model, context, options, null).telemetryContext
        )
        assertSame(
            telemetry,
            buildOpenAICodexResponsesOptions(model, context, options, null).telemetryContext
        )
        assertSame(telemetry, buildGoogleOptions(model, context, options).telemetryContext)
        assertSame(telemetry, buildMistralOptions(model, context, options).telemetryContext)
    }

    @Test
    fun `toString renders telemetry presence only, never the object`() {
        val core = listOf(
            StreamOptions(telemetry = telemetry).toString(),
            SimpleStreamOptions(telemetry = telemetry).toString()
        )
        for (text in core) {
            assertTrue("telemetry=true" in text, text)
            assertFalse("InMemoryTelemetryContext" in text, text)
        }
        val provider = listOf(
            OpenAiCompletionsOptions(telemetryContext = telemetry).toString(),
            OpenAiResponsesOptions(telemetryContext = telemetry).toString(),
            AzureOpenAiResponsesOptions(telemetryContext = telemetry).toString(),
            OpenAICodexResponsesOptions(telemetryContext = telemetry).toString(),
            AnthropicMessagesOptions(telemetryContext = telemetry).toString(),
            GoogleGenerativeAiApi.GoogleOptions(telemetryContext = telemetry).toString(),
            MistralOptions(telemetryContext = telemetry).toString()
        )
        for (text in provider) {
            assertTrue("telemetryContext=true" in text, text)
            assertFalse("InMemoryTelemetryContext" in text, text)
        }
        // Same presence-only convention as onPayload/onResponse.
        assertTrue("telemetry=false" in SimpleStreamOptions().toString())
    }

    @Test
    fun `direct adapter streaming emits zero spans even with a telemetry span present`() = runTest {
        val recorder = InMemoryTelemetryContext()
        val transport = FakeTransport()
        transport.enqueueResponse(
            listOf(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "[DONE]"
            )
        )
        val api = OpenAiCompletionsApi(
            transport,
            ProviderRetry(sleep = {
            }, clock = FakeClock(0L), random = { 0.0 })
        )
        val events = recorder.startSpan(SpanOptions("test-parent")) { parent ->
            api.streamSimple(
                model,
                normalizeContext(Context(messages = listOf(UserMessage.ofText("hi")))),
                SimpleStreamOptions(apiKey = "k", telemetry = parent)
            ).toList()
        }
        assertEquals(
            works.resolve.pathfinder.ai.StopReason.STOP,
            (events.last() as AssistantMessageEvent.Done).reason
        )
        assertEquals(listOf("test-parent"), recorder.getSpans().map { it.name })
    }
}
