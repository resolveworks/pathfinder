package works.resolve.pathfinder.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool

/**
 * Pins the details-to-UI parse behind the structured web_search renderer:
 * matching entries project, everything else (other tools, errors,
 * malformed or empty shapes) falls back to the text renderer via null.
 */
class ChatProjectionSearchResultsTest {
    private fun result(
        toolName: String = BraveWebSearchTool.NAME,
        details: kotlinx.serialization.json.JsonElement? = null,
        isError: Boolean = false
    ): ToolResultMessage = ToolResultMessage(
        toolCallId = "call-1",
        toolName = toolName,
        content = listOf(TextContent("markdown")),
        details = details,
        isError = isError,
        timestamp = 1L
    )

    @Test
    fun `details results project with description`() {
        val details = buildJsonObject {
            put(
                "results",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("title", "First")
                            put("url", "https://a.example")
                            put("description", "Desc one")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("title", "")
                            put("url", "https://b.example")
                        }
                    )
                }
            )
        }
        assertEquals(
            listOf(
                ChatSearchResult(
                    title = "First",
                    url = "https://a.example",
                    description = "Desc one"
                ),
                ChatSearchResult(title = "", url = "https://b.example")
            ),
            toolResultSearchResults(result(details = details))
        )
    }

    @Test
    fun `empty description stays null like the markdown path skips it`() {
        val details = buildJsonObject {
            put(
                "results",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("title", "T")
                            put("url", "https://a.example")
                            put("description", "")
                        }
                    )
                }
            )
        }
        assertEquals(
            listOf(ChatSearchResult(title = "T", url = "https://a.example")),
            toolResultSearchResults(result(details = details))
        )
    }

    @Test
    fun `non-results shapes fall back to text rendering`() {
        assertNull(toolResultSearchResults(result(details = null)))
        assertNull(
            toolResultSearchResults(
                result(
                    details = buildJsonObject {
                        put("results", "no")
                    }
                )
            )
        )
        assertNull(
            toolResultSearchResults(
                result(
                    details = buildJsonObject {
                        put("results", buildJsonArray {})
                    }
                )
            )
        )
        // Non-object entries are skipped; none left means no structured render.
        assertNull(
            toolResultSearchResults(
                result(
                    details = buildJsonObject {
                        put("results", buildJsonArray { add(JsonPrimitive("x")) })
                    }
                )
            )
        )
    }

    @Test
    fun `other tools and error results never project structured entries`() {
        val details = buildJsonObject {
            put(
                "results",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("title", "T")
                            put("url", "https://a.example")
                        }
                    )
                }
            )
        }
        assertNull(toolResultSearchResults(result(toolName = "web_fetch", details = details)))
        assertNull(toolResultSearchResults(result(details = details, isError = true)))
    }
}
