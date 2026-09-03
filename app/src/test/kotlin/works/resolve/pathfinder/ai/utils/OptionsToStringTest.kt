package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals

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
            "onPayload" to (null != null),
        )
        assertEquals("StreamOptions(apiKey=<redacted>, sessionId=abc, env=[A, B], onPayload=false)", s)
    }
}
