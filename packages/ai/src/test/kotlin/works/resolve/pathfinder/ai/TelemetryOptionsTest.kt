package works.resolve.pathfinder.ai

import works.resolve.pathfinder.ai.api.AzureOpenAiResponsesOptions
import works.resolve.pathfinder.ai.api.OpenAiCompletionsOptions
import works.resolve.pathfinder.ai.api.AnthropicMessagesOptions
import works.resolve.pathfinder.ai.api.GoogleGenerativeAiApi
import works.resolve.pathfinder.ai.api.MistralOptions
import works.resolve.pathfinder.ai.api.OpenAICodexResponsesOptions
import works.resolve.pathfinder.ai.api.OpenAiResponsesOptions
import works.resolve.pathfinder.ai.api.buildAzureOpenAiResponsesOptions
import works.resolve.pathfinder.ai.api.buildBaseOptions
import works.resolve.pathfinder.ai.api.buildGoogleOptions
import works.resolve.pathfinder.ai.api.buildMistralOptions
import works.resolve.pathfinder.ai.api.buildOpenAICodexResponsesOptions
import works.resolve.pathfinder.ai.api.buildOpenAiResponsesOptions
import works.resolve.pathfinder.ai.api.toMistralOptions
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.api.OpenAiCompletionsApi
import works.resolve.pathfinder.telemetry.InMemoryTelemetryContext
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/** telemetryContext is dormant: no adapter reads it, and none may emit spans. */
class TelemetryOptionsTest {

    private val telemetry = InMemoryTelemetryContext()
    private val context = Context(messages = emptyList())

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
        maxTokens = 100,
    )

    @Test
    fun `telemetryContext is inherited by every request option surface`() {
        val stream = StreamOptions(telemetryContext = telemetry)
        assertSame(telemetry, stream.telemetryContext)
        val simple = SimpleStreamOptions(telemetryContext = telemetry)
        assertSame(telemetry, simple.telemetryContext)
    }

    @Test
    fun `telemetryContext defaults to null on every request option surface`() {
        assertNull(StreamOptions().telemetryContext)
        assertNull(SimpleStreamOptions().telemetryContext)
        assertNull(OpenAiCompletionsOptions().telemetryContext)
        assertNull(OpenAiResponsesOptions().telemetryContext)
        assertNull(AzureOpenAiResponsesOptions().telemetryContext)
        assertNull(OpenAICodexResponsesOptions().telemetryContext)
        assertNull(AnthropicMessagesOptions().telemetryContext)
        assertNull(GoogleGenerativeAiApi.GoogleOptions().telemetryContext)
        assertNull(MistralOptions().telemetryContext)
    }

    @Test
    fun `telemetryContext survives every simple to base conversion by identity`() {
        val options = SimpleStreamOptions(telemetryContext = telemetry)
        assertSame(telemetry, options.toStreamOptions(null).telemetryContext)
        assertSame(telemetry, buildBaseOptions(model, context, options).telemetryContext)
        assertSame(telemetry, buildOpenAiResponsesOptions(model, context, options, null).telemetryContext)
        assertSame(telemetry, buildAzureOpenAiResponsesOptions(model, context, options, null).telemetryContext)
        assertSame(telemetry, buildOpenAICodexResponsesOptions(model, context, options, null).telemetryContext)
        assertSame(telemetry, buildGoogleOptions(model, context, options).telemetryContext)
        assertSame(telemetry, buildMistralOptions(model, context, options).telemetryContext)
    }

    @Test
    fun `telemetryContext survives the mistral manual completions conversion by identity`() {
        val options = SimpleStreamOptions(telemetryContext = telemetry).toStreamOptions(null)
        assertSame(telemetry, toMistralOptions(model, options).telemetryContext)
    }

    @Test
    fun `toString renders telemetryContext presence only, never the object`() {
        val rendered = listOf(
            StreamOptions(telemetryContext = telemetry).toString(),
            SimpleStreamOptions(telemetryContext = telemetry).toString(),
            OpenAiCompletionsOptions(telemetryContext = telemetry).toString(),
            OpenAiResponsesOptions(telemetryContext = telemetry).toString(),
            AzureOpenAiResponsesOptions(telemetryContext = telemetry).toString(),
            OpenAICodexResponsesOptions(telemetryContext = telemetry).toString(),
            AnthropicMessagesOptions(telemetryContext = telemetry).toString(),
            GoogleGenerativeAiApi.GoogleOptions(telemetryContext = telemetry).toString(),
            MistralOptions(telemetryContext = telemetry).toString(),
        )
        for (text in rendered) {
            assertTrue("telemetryContext=true" in text, text)
            assertFalse("InMemoryTelemetryContext" in text, text)
        }
        // Same presence-only convention as onPayload/onResponse.
        assertTrue("telemetryContext=false" in SimpleStreamOptions().toString())
    }

    @Test
    fun `streaming emits zero spans even with a telemetryContext present`() = runTest {
        val transport = FakeTransport()
        transport.enqueueResponse(
            listOf(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                "[DONE]",
            ),
        )
        val events = OpenAiCompletionsApi(transport, ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 }))
            .streamSimple(
                model,
                Context(messages = listOf(UserMessage.ofText("hi"))),
                SimpleStreamOptions(apiKey = "k", telemetryContext = telemetry),
            )
            .toList()
        assertEquals(works.resolve.pathfinder.ai.StopReason.STOP, events.last().partial.stopReason)
        assertEquals(0, telemetry.getSpans().size)
    }
}
