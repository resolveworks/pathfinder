package works.resolve.pathfinder.tools.websearch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.utils.MAX_PROVIDER_ERROR_BODY_CHARS
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.trimJsWhitespace
import works.resolve.pathfinder.ai.utils.truncateErrorText

/**
 * The web_search agent tool: dispatches each call to the active search
 * provider's [SearchExecutor]. Pathfinder-owned app tooling (pi has no
 * upstream web search): the parameter surface, result payload, and wording
 * are app decisions. The declared schema reflects the active executor's
 * native parameters — refreshed at apply time via [applySchema] and per call
 * when resolving, so a mid-session provider switch takes effect on the
 * next tool call without rebuilding the session.
 */
class WebSearchTool(
    executors: List<SearchExecutor>,
    initialProviderId: String,
    private val activeProviderId: suspend () -> String,
    private val credentialFor: suspend (providerId: String) -> String?
) : AgentTool {

    private val executorsById: Map<String, SearchExecutor> =
        executors.associateBy { it.providerId }

    @Volatile
    private var currentDefinition: Tool = definitionFor(executorFor(initialProviderId))

    override val definition: Tool get() = currentDefinition

    override val label: String = "Web Search"

    override val promptSnippet: String = "Search the web for information on a topic"

    override val promptGuidelines: List<String> = listOf(
        "Use web_search when the user asks you to look up current information, facts, or content from the web."
    )

    /**
     * Re-declares the schema from [providerId]'s executor. Called when the
     * active provider changes so the model never sees parameters the
     * provider does not honor.
     *
     * @throws IllegalArgumentException for an unknown provider id.
     */
    fun applySchema(providerId: String) {
        val executor = executorFor(providerId)
        currentDefinition = definitionFor(executor)
    }

    override suspend fun execute(
        toolCallId: String,
        arguments: JsonObject,
        onUpdate: (AgentToolResult) -> Unit
    ): AgentToolResult {
        val query = arguments.str("query")
            ?: throw IllegalArgumentException("web_search: missing required argument 'query'")

        val executor = executorFor(activeProviderId()).also {
            currentDefinition = definitionFor(it)
        }

        val apiKey = credentialFor(executor.providerId)
        if (apiKey == null || apiKey.isBlank()) {
            return AgentToolResult(
                content = listOf(TextContent(executor.missingKeyMessage)),
                details = EMPTY_DETAILS
            )
        }

        // Values for exactly the provider's declared optional parameters,
        // in that provider's native naming; anything else is invalid state.
        val declared = executor.parameters.mapTo(HashSet()) { it.name }
        arguments.keys.firstOrNull { it != "query" && it !in declared }?.let {
            throw IllegalArgumentException(
                "web_search: unexpected argument '$it' for provider '${executor.providerId}'"
            )
        }
        val options = executor.parameters.mapNotNull { param ->
            arguments.str(param.name)?.let { param.name to it }
        }

        val results = try {
            executor.search(query, options.toMap(), apiKey)
        } catch (e: SearchRequestException) {
            val reason =
                truncateErrorText(trimJsWhitespace(e.body), MAX_PROVIDER_ERROR_BODY_CHARS)
                    .ifEmpty { e.reasonPhrase }
            return AgentToolResult(
                content = listOf(TextContent("Search failed (${e.code}): $reason")),
                details = EMPTY_DETAILS
            )
        }

        if (results.isEmpty()) {
            return AgentToolResult(
                content = listOf(TextContent("No results found for \"$query\".")),
                details = buildJsonObject { put("resultCount", 0) }
            )
        }

        // web_fetch's block shape — title, source, body — so a result page
        // spans many lines and the transcript's generic preview cap collapses
        // and expands search rows exactly like fetch rows.
        val blocks = results.map { result ->
            buildString {
                append("## ").appendLine(result.title)
                appendLine()
                append("Source: ").append(result.url)
                result.snippet?.let {
                    appendLine()
                    appendLine()
                    append(it)
                }
            }
        }

        return AgentToolResult(
            content = listOf(TextContent(blocks.joinToString("\n\n"))),
            details = buildJsonObject { put("resultCount", results.size) }
        )
    }

    private fun executorFor(providerId: String): SearchExecutor = executorsById[providerId]
        ?: throw IllegalArgumentException("Unknown search provider: $providerId")

    private fun definitionFor(executor: SearchExecutor): Tool = Tool(
        name = NAME,
        description = "Search the web with ${executor.providerName} and return the top " +
            "results as markdown.",
        parameters = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put(
                        "query",
                        buildJsonObject {
                            put("type", "string")
                            put("description", "Search query")
                        }
                    )
                    executor.parameters.forEach { param ->
                        put(
                            param.name,
                            buildJsonObject {
                                put("type", param.type)
                                param.enumValues?.let { values ->
                                    put("enum", JsonArray(values.map { JsonPrimitive(it) }))
                                }
                                put("description", param.description)
                            }
                        )
                    }
                }
            )
            put("required", JsonArray(listOf(JsonPrimitive("query"))))
        }
    )

    companion object {
        const val NAME = "web_search"

        private val EMPTY_DETAILS: JsonObject = JsonObject(emptyMap())
    }
}
