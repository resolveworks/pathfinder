package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import works.resolve.pathfinder.ai.ConstrainedSamplingConfig
import works.resolve.pathfinder.ai.SystemMessage
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

    @Test
    fun resolveTranscriptToolsFoldsUpToTheLastNonAdditiveChange() {
        fun named(name: String, description: String = "$name tool") = Tool(
            name = name,
            description = description,
            parameters = parse("""{"type":"object"}""")
        )
        val base = named("base_tool")
        val drop = named("drop_tool")
        val late = named("late_tool")
        val messages = listOf(
            SystemMessage(content = emptyList(), toolsAdded = listOf(base, drop), timestamp = 0),
            SystemMessage(
                content = emptyList(),
                toolsRemoved = listOf(works.resolve.pathfinder.ai.ToolReference("drop_tool")),
                timestamp = 1
            ),
            SystemMessage(content = emptyList(), toolsAdded = listOf(drop), timestamp = 2),
            SystemMessage(
                content = emptyList(),
                toolsAdded = listOf(named("late_tool", "x")),
                timestamp = 3
            ),
            SystemMessage(
                content = emptyList(),
                toolsAdded = listOf(named("late_tool", "y")),
                timestamp = 4
            )
        )

        assertEquals(
            TranscriptTools(requestTools = listOf(base), foldedSystemMessages = 2),
            resolveTranscriptTools(messages.take(2), TranscriptToolSupport.ADDITIONS)
        )
        assertEquals(
            TranscriptTools(requestTools = listOf(base, drop), foldedSystemMessages = 3),
            resolveTranscriptTools(messages.take(4), TranscriptToolSupport.ADDITIONS)
        )
        assertEquals(
            TranscriptTools(
                requestTools = listOf(base, drop, late.copy(description = "y")),
                foldedSystemMessages = 5
            ),
            resolveTranscriptTools(messages, TranscriptToolSupport.ADDITIONS)
        )
        assertEquals(
            TranscriptTools(emptyList(), 0),
            resolveTranscriptTools(messages, TranscriptToolSupport.CHANGES)
        )
        assertEquals(
            TranscriptTools(listOf(base, drop, late.copy(description = "y")), 5),
            resolveTranscriptTools(messages, TranscriptToolSupport.NONE)
        )
    }
}
