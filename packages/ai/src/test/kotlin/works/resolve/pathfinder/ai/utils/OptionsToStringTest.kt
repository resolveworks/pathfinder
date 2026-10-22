package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel

class OptionsToStringTest {

    @Test
    fun secretsRenderAsNullOrRedacted() {
        assertEquals("null", redactedSecret(null))
        assertEquals("<redacted>", redactedSecret("sk-secret"))
        assertEquals("<redacted>", redactedSecret(""))
    }

    @Test
    fun joinsAlreadyRedactedFields() {
        val s = optionsToString(
            "StreamOptions",
            "apiKey" to redactedSecret("k"),
            "sessionId" to "abc",
            "env" to mapOf("A" to "1", "B" to "2").keys,
            "onPayload" to (null != null)
        )
        assertEquals(
            "StreamOptions(apiKey=<redacted>, sessionId=abc, env=[A, B], onPayload=false)",
            s
        )
    }

    @Test
    fun `sampling params merge model, thinking level, then request keys`() {
        val model = Model(
            id = "m",
            name = "M",
            api = "openai-completions",
            provider = "p",
            baseUrl = "https://example.test",
            reasoning = true,
            samplingParams = mapOf(
                "temperature" to JsonPrimitive(1),
                "top_p" to JsonPrimitive(0.95)
            ),
            samplingParamsByThinkingLevel = mapOf(
                ModelThinkingLevel.HIGH to mapOf(
                    "temperature" to JsonPrimitive(0.8),
                    "top_k" to JsonPrimitive(64)
                )
            )
        )
        assertEquals(
            mapOf(
                "temperature" to JsonPrimitive(0.8),
                "top_p" to JsonPrimitive(0.5),
                "top_k" to JsonPrimitive(64)
            ),
            resolveSamplingParams(
                model,
                ModelThinkingLevel.HIGH,
                mapOf("top_p" to JsonPrimitive(0.5))
            )
        )
    }

    @Test
    fun `sampling params use the clamped effective thinking level`() {
        val model = Model(
            id = "m",
            name = "M",
            api = "openai-completions",
            provider = "p",
            baseUrl = "https://example.test",
            reasoning = true,
            thinkingLevelMap = works.resolve.pathfinder.ai.ThinkingLevelMap.of(
                ModelThinkingLevel.LOW to null,
                ModelThinkingLevel.MEDIUM to null
            ),
            samplingParamsByThinkingLevel = mapOf(
                ModelThinkingLevel.HIGH to mapOf("temperature" to JsonPrimitive(0.8))
            )
        )
        assertEquals(
            mapOf("temperature" to JsonPrimitive(0.8)),
            resolveSamplingParams(model, ModelThinkingLevel.LOW, null)
        )
    }

    @Test
    fun `sampling params can come from the off level and are null when absent`() {
        val withOff = Model(
            id = "m",
            name = "M",
            api = "openai-completions",
            provider = "p",
            baseUrl = "https://example.test",
            samplingParamsByThinkingLevel = mapOf(
                ModelThinkingLevel.OFF to mapOf("temperature" to JsonPrimitive(0.7))
            )
        )
        assertEquals(
            mapOf("temperature" to JsonPrimitive(0.7)),
            resolveSamplingParams(withOff, ModelThinkingLevel.OFF, null)
        )

        val empty = withOff.copy(samplingParamsByThinkingLevel = null)
        assertNull(resolveSamplingParams(empty, ModelThinkingLevel.OFF, null))
    }
}
