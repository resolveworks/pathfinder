package works.resolve.pathfinder.codingagent.core

import works.resolve.pathfinder.agent.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SystemPromptTest {

    private class FakeTool(
        val name: String,
        override val promptSnippet: String? = null,
        override val promptGuidelines: List<String> = emptyList(),
    ) : AgentTool {
        override val definition = Tool(name, "$name tool", JsonPrimitive("object"))
        override val label = name

        override fun validateArguments(arguments: JsonObject) = arguments

        override suspend fun execute(
            toolCallId: String,
            arguments: JsonObject,
            onUpdate: AgentToolUpdateCallback,
        ) = AgentToolResult(content = listOf(TextContent("ok")))
    }

    @Test
    fun `null for empty active tools`() {
        assertNull(buildSystemPrompt(emptyList()))
    }

    @Test
    fun `tools without snippets render none and only always-on guidelines`() {
        val prompt = buildSystemPrompt(listOf(FakeTool("web_search")))
        assertEquals(
            "Available tools:\n" +
                "(none)\n" +
                "\n" +
                "Guidelines:\n" +
                "- Be concise in your responses\n" +
                "- Show file paths clearly when working with files",
            prompt,
        )
    }

    @Test
    fun `mixed tools list only snippet-bearing tools`() {
        val prompt = buildSystemPrompt(
            listOf(
                FakeTool("bash", promptSnippet = "Run shell commands"),
                FakeTool("hidden"),
                FakeTool("read", promptSnippet = "Read file contents"),
            ),
        )
        assertEquals(
            "Available tools:\n" +
                "- bash: Run shell commands\n" +
                "- read: Read file contents\n" +
                "\n" +
                "Guidelines:\n" +
                "- Be concise in your responses\n" +
                "- Show file paths clearly when working with files",
            prompt,
        )
    }

    @Test
    fun `tool guidelines come first in tool order and are deduped keeping first occurrence`() {
        val prompt = buildSystemPrompt(
            listOf(
                FakeTool(
                    "web_search",
                    promptSnippet = "Search the web",
                    promptGuidelines = listOf("Cite sources", "  Cite sources  ", "", "   "),
                ),
                FakeTool("web_fetch", promptSnippet = "Fetch a URL", promptGuidelines = listOf("Cite sources", "Prefer web_fetch over guessing URLs")),
            ),
        )
        assertEquals(
            "Available tools:\n" +
                "- web_search: Search the web\n" +
                "- web_fetch: Fetch a URL\n" +
                "\n" +
                "Guidelines:\n" +
                "- Cite sources\n" +
                "- Prefer web_fetch over guessing URLs\n" +
                "- Be concise in your responses\n" +
                "- Show file paths clearly when working with files",
            prompt,
        )
    }

    @Test
    fun `always-on guidelines dedupe against tool guidelines`() {
        val prompt = buildSystemPrompt(
            listOf(FakeTool("echo", promptGuidelines = listOf("Be concise in your responses"))),
        )
        assertEquals(
            "Available tools:\n" +
                "(none)\n" +
                "\n" +
                "Guidelines:\n" +
                "- Be concise in your responses\n" +
                "- Show file paths clearly when working with files",
            prompt,
        )
    }

    @Test
    fun `whitespace-only snippet is treated as absent`() {
        val prompt = buildSystemPrompt(
            listOf(
                FakeTool("bash", promptSnippet = "   "),
                FakeTool("read", promptSnippet = "Read file contents"),
            ),
        )
        assertEquals(
            "Available tools:\n" +
                "- read: Read file contents\n" +
                "\n" +
                "Guidelines:\n" +
                "- Be concise in your responses\n" +
                "- Show file paths clearly when working with files",
            prompt,
        )
    }

    @Test
    fun `multi-line snippet with whitespace runs is collapsed to one trimmed line`() {
        val prompt = buildSystemPrompt(
            listOf(FakeTool("web_search", promptSnippet = "Search\n  the   web\r\nfor facts  ")),
        )
        assertEquals(
            "Available tools:\n" +
                "- web_search: Search the web for facts\n" +
                "\n" +
                "Guidelines:\n" +
                "- Be concise in your responses\n" +
                "- Show file paths clearly when working with files",
            prompt,
        )
    }
}
