package works.resolve.pathfinder.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.telemetry.InMemoryTelemetryContext
import works.resolve.pathfinder.telemetry.SpanOptions
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.attr

class ModelStreamTelemetryTest {

    private val model = Model(
        id = "model",
        name = "Model",
        api = "telemetry-test",
        provider = "test",
        baseUrl = "https://example.test",
        reasoning = true,
        input = listOf(InputModality.TEXT),
        cost = ModelCost(input = 1.0, output = 2.0, cacheRead = 3.0, cacheWrite = 4.0),
        contextWindow = 1000,
        maxTokens = 100
    )

    private fun message(stopReason: StopReason = StopReason.STOP) = AssistantMessage(
        content = listOf(
            TextContent("text-secret"),
            ThinkingContent("thinking-secret"),
            ToolCall(
                "call-secret",
                "lookup",
                JsonObject(
                    mapOf("value" to kotlinx.serialization.json.JsonPrimitive("argument-secret"))
                )
            )
        ),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = Usage(
            input = 11,
            output = 22,
            cacheRead = 33,
            cacheWrite = 44,
            totalTokens = 110,
            cost = Cost(0.1, 0.2, 0.3, 0.4, 1.0)
        ),
        responseId = "response",
        stopReason = stopReason,
        timestamp = 0
    )

    private fun events(result: AssistantMessage): List<AssistantMessageEvent> {
        val toolCall = result.content[2] as ToolCall
        return listOf(
            AssistantMessageEvent.Start(result),
            AssistantMessageEvent.TextDelta(0, "text-secret"),
            AssistantMessageEvent.TextEnd(0, "text-secret", result),
            AssistantMessageEvent.ThinkingEnd(1, "thinking-secret", result),
            AssistantMessageEvent.ToolCallDelta(2, """{"value":"""),
            AssistantMessageEvent.ToolCallDelta(2, """"argument-secret"}"""),
            AssistantMessageEvent.ToolCallEnd(2, toolCall, result),
            if (result.stopReason == StopReason.ERROR || result.stopReason == StopReason.ABORTED) {
                AssistantMessageEvent.Error(result.stopReason, result)
            } else {
                AssistantMessageEvent.Done(result.stopReason, result)
            }
        )
    }

    private fun models(
        api: ChatApi,
        timeSource: kotlin.time.TimeSource = kotlin.time.TimeSource.Monotonic
    ): Models = Models(
        listOf(
            Provider(
                id = model.provider,
                name = model.provider,
                baseUrl = model.baseUrl,
                authResolver = { _, _ -> ResolvedAuth(apiKey = "key") },
                models = listOf(model),
                apiId = model.api,
                api = api
            )
        ),
        clock = FakeClock(1_000),
        timeSource = timeSource
    )

    private fun apiFor(result: AssistantMessage): ChatApi = object : ChatApi {
        override fun streamSimple(
            model: Model,
            context: TranscriptContext,
            options: SimpleStreamOptions
        ): Flow<AssistantMessageEvent> = flow {
            events(result).forEach { emit(it) }
        }
    }

    @Test
    fun `observes a model request without changing delivered events or results`() = runTest {
        val recorder = InMemoryTelemetryContext()
        val result = message()
        val expected = events(result)
        val models = models(apiFor(result))
        val received = recorder.startSpan(SpanOptions("caller")) { parent ->
            models.stream(
                model,
                Context(messages = emptyList()),
                SimpleStreamOptions(telemetry = parent, reasoning = ThinkingLevel.HIGH)
            ).toList()
        }
        assertEquals(expected, received)

        val (parent, span) = recorder.getSpans()
        assertEquals("caller", parent.name)
        assertEquals("pi.ai.request", span.name)
        assertEquals(parent.id, span.parentId)
        assertEquals(
            mapOf(
                "provider" to attr("test"),
                "api" to attr("telemetry-test"),
                "model" to attr("model"),
                "reasoning" to attr("high"),
                "stop" to attr("stop"),
                "usage.input" to attr(11),
                "usage.output" to attr(22),
                "usage.cache_read" to attr(33),
                "usage.cache_write" to attr(44),
                "cost.total" to attr(1.0),
                "response.id" to attr("response")
            ),
            span.attributes
        )
        assertEquals(
            listOf(
                "pi.ai.first_chunk",
                "pi.ai.block",
                "pi.ai.block",
                "pi.ai.block"
            ),
            span.events.map { it.name }
        )
        assertEquals(
            mapOf("type" to attr("text"), "index" to attr(0), "chars" to attr(11)),
            span.events[1].attributes
        )
        assertEquals(
            mapOf("type" to attr("thinking"), "index" to attr(1), "chars" to attr(15)),
            span.events[2].attributes
        )
        assertEquals(
            mapOf(
                "type" to attr("tool_call"),
                "index" to attr(2),
                "chars" to attr(27),
                "tool" to attr("lookup")
            ),
            span.events[3].attributes
        )
        assertTrue("text-secret" !in span.toString())
        assertTrue("thinking-secret" !in span.toString())
        assertTrue("argument-secret" !in span.toString())
        assertTrue("call-secret" !in span.toString())

        val measurements = recorder.getMetrics()
        assertEquals(7, measurements.size)
        assertEquals(
            listOf(11.0, 22.0, 33.0, 44.0, 1.0),
            measurements.filter { it.kind == "counter" }.map { it.value }
        )
        assertEquals(
            listOf("pi.ai.first_chunk.duration", "pi.ai.request.duration"),
            measurements.filter { it.kind == "histogram" }.map { it.name }
        )
    }

    @Test
    fun `reports provider error status with a bounded message`() = runTest {
        val recorder = InMemoryTelemetryContext()
        val result = message(StopReason.ERROR).copy(
            errorMessage =
                "x".repeat(500) + "discarded-tail"
        )
        val models = models(apiFor(result))
        val received = recorder.startSpan(SpanOptions("caller")) { parent ->
            models.completeSimple(
                model,
                Context(messages = emptyList()),
                SimpleStreamOptions(telemetry = parent)
            )
        }
        assertEquals("x".repeat(500) + "discarded-tail", received.errorMessage)
        val span = recorder.getSpans()[1]
        assertEquals(
            SpanStatus.Error(
                works.resolve.pathfinder.telemetry.TelemetryError(
                    "provider_error",
                    "x".repeat(500)
                )
            ),
            span.status
        )
        assertTrue("discarded-tail" !in span.toString())
    }

    @Test
    fun `aborted status stays ok without error attributes or events`() = runTest {
        val recorder = InMemoryTelemetryContext()
        val result = message(StopReason.ABORTED).copy(errorMessage = "error-secret")
        val observed = recorder.startSpan(SpanOptions("caller")) { parent ->
            models(apiFor(result)).completeSimple(
                model,
                Context(messages = emptyList()),
                SimpleStreamOptions(telemetry = parent)
            )
        }
        assertEquals(result, observed)
        val span = recorder.getSpans()[1]
        assertEquals(SpanStatus.Ok, span.status)
        assertEquals(attr("aborted"), span.attributes["stop"])
        assertTrue("error-secret" !in span.toString())
    }

    @Test
    fun `setup failures are observed as provider errors`() = runTest {
        val recorder = InMemoryTelemetryContext()
        val unknown = model.copy(provider = "missing")
        val models = models(apiFor(message()))
        val result = recorder.startSpan(SpanOptions("caller")) { parent ->
            models.completeSimple(
                unknown,
                Context(messages = emptyList()),
                SimpleStreamOptions(telemetry = parent)
            )
        }
        assertEquals(StopReason.ERROR, result.stopReason)
        assertEquals("Unknown provider: missing", result.errorMessage)
        val span = recorder.getSpans()[1]
        assertEquals(
            SpanStatus.Error(
                works.resolve.pathfinder.telemetry.TelemetryError(
                    "provider_error",
                    "Unknown provider: missing"
                )
            ),
            span.status
        )
        assertEquals(listOf("pi.ai.first_chunk"), span.events.map { it.name })
    }

    @Test
    fun `final message carries monotonic duration from stream start`() = runTest {
        val time = TestTimeSource()
        val result = message().copy(timestamp = 1_000)
        val api = object : ChatApi {
            override fun streamSimple(
                model: Model,
                context: TranscriptContext,
                options: SimpleStreamOptions
            ): Flow<AssistantMessageEvent> = flow {
                emit(AssistantMessageEvent.Start(result))
                time += 20.milliseconds
                emit(AssistantMessageEvent.Done(StopReason.STOP, result))
            }
        }
        val events = models(api, time).stream(
            model,
            Context(messages = emptyList()),
            SimpleStreamOptions()
        ).toList()
        assertEquals(20L, (events.last() as AssistantMessageEvent.Done).message.durationMs)
    }

    @Test
    fun `existing duration wins and messages that started earlier stay untimed`() = runTest {
        val time = TestTimeSource()
        val preset = message().copy(timestamp = 1_000, durationMs = 1234)
        val events = models(apiFor(preset), time).stream(
            model,
            Context(messages = emptyList()),
            SimpleStreamOptions()
        ).toList()
        assertEquals(1234L, (events.last() as AssistantMessageEvent.Done).message.durationMs)

        val old = message().copy(timestamp = 0)
        val oldEvents = models(apiFor(old), time).stream(
            model,
            Context(messages = emptyList()),
            SimpleStreamOptions()
        ).toList()
        assertNull((oldEvents.last() as AssistantMessageEvent.Done).message.durationMs)
    }
}
