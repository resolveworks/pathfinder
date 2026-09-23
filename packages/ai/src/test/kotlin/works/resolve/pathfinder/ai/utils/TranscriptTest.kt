package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import works.resolve.pathfinder.ai.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.Tool

class TranscriptTest {

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun tool(
        parameters: String = """{"type": "object"}""",
        constrainedSampling: ConstrainedSamplingConfig? = null
    ) = Tool(
        name = "read",
        description = "Reads a file",
        parameters = parse(parameters),
        constrainedSampling = constrainedSampling
    )

    @Test
    fun declarationsEqualComparesTheSerializedInterface() {
        assertTrue(declarationsEqual(tool(), tool()))
        // Separately parsed parameters compare equal: both sides serialize.
        assertTrue(
            declarationsEqual(
                tool(
                    parameters =
                        """{"type": "object", "properties": {"path": {"type": "string"}}}"""
                ),
                tool(
                    parameters =
                        """{"type": "object", "properties": {"path": {"type": "string"}}}"""
                )
            )
        )
        assertFalse(declarationsEqual(tool(), tool().copy(name = "write")))
        assertFalse(declarationsEqual(tool(), tool().copy(description = "Writes a file")))
        assertFalse(declarationsEqual(tool(), tool(parameters = """{"type": "string"}""")))
        // pi: constrainedSampling participates in the declaration once defined.
        assertFalse(
            declarationsEqual(
                tool(),
                tool(constrainedSampling = ConstrainedSamplingConfig.Disabled)
            )
        )
        assertTrue(
            declarationsEqual(
                tool(constrainedSampling = ConstrainedSamplingConfig.Disabled),
                tool(constrainedSampling = ConstrainedSamplingConfig.Disabled)
            )
        )
    }

    @Test
    fun declarationsEqualComparesSerializedKeyOrderLikeJsonStringify() {
        // JSON.stringify preserves insertion order, so a re-declaration whose
        // schema keys are ordered differently is a changed declaration — the
        // serialized compare must not collapse that the way kotlinx `==`
        // (Map.equals) would.
        val a = tool(parameters = """{"type": "object", "properties": {}}""")
        val b = tool(parameters = """{"properties": {}, "type": "object"}""")
        assertFalse(declarationsEqual(a, b))
        // Sanity: kotlinx `==` (Map.equals) treats those parameter objects as
        // equal — the serialized compare is deliberately stricter than that.
        assertTrue(
            a.parameters as JsonObject ==
                Json.parseToJsonElement("""{"properties": {}, "type": "object"}""").jsonObject
        )
    }
}
