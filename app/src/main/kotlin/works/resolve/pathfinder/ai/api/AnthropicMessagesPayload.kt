package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.ContentType
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.StreamOptions
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.anthropicCompatOf
import works.resolve.pathfinder.ai.utils.clampMaxTokensToContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Request construction for the Anthropic Messages API, ported from pi's
 * packages/ai/src/api/anthropic-messages.ts (buildParams, convertMessages,
 * convertTools, convertContentBlocks, normalizeToolCallId, cache-control
 * handling, Claude Code OAuth tool-name mapping, and the streamSimple
 * thinking helpers from simple-options.ts).
 *
 * Documented divergences from pi (kept narrow, per project rules):
 * - Deferred tool loading (`splitDeferredTools`, `tool_reference` blocks,
 *   `defer_loading`, and server-side fallbacks) is not ported: Pathfinder's core
 *   ToolResultMessage has no `addedToolNames` and Model has no
 *   `allowedFallbackModels`.
 * - `strict` tool JSON-schema sampling is not ported; pi's default is
 *   `supportsStrictTools: false`, so wire output is identical for defaults.
 * - pi's `metadata.user_id` option is not ported (no metadata option here).
 * - Thinking content stays a raw text/signature pair; pi additionally tracks
 *   a separate `cacheWrite1h` usage component (see AnthropicMessagesApi).
 */


/** pi's AnthropicEffort. */
enum class AnthropicEffort { LOW, MEDIUM, HIGH, XHIGH, MAX }

/** pi's AnthropicThinkingDisplay. */
enum class AnthropicThinkingDisplay { SUMMARIZED, OMITTED }

/** pi's AnthropicOptions.toolChoice. */
sealed interface AnthropicToolChoice {
    data object Auto : AnthropicToolChoice
    data object Any : AnthropicToolChoice
    data object None : AnthropicToolChoice
    data class Tool(val name: String) : AnthropicToolChoice
}

/**
 * Options for the Anthropic Messages adapter, ported from pi's
 * AnthropicOptions (StreamOptions base plus the Anthropic-specific fields).
 * `client` is not ported: the transport is injected at construction instead.
 */
data class AnthropicMessagesOptions(
    val apiKey: String? = null,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** pi's thinkingEnabled; null means "unspecified" (thinking omitted). */
    val thinkingEnabled: Boolean? = null,
    /** pi's thinkingBudgetTokens; default 1024 when enabled (older models). */
    val thinkingBudgetTokens: Int? = null,
    /** pi's effort: adaptive-thinking effort level. */
    val effort: AnthropicEffort? = null,
    /** pi's thinkingDisplay; defaults to SUMMARIZED when thinking is enabled. */
    val thinkingDisplay: AnthropicThinkingDisplay = AnthropicThinkingDisplay.SUMMARIZED,
    /** pi's interleavedThinking (default true). */
    val interleavedThinking: Boolean = true,
    val toolChoice: AnthropicToolChoice? = null,
    val cacheRetention: CacheRetention? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String?> = emptyMap(),
) {
    override fun toString(): String =
        "AnthropicMessagesOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
            ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
            ", thinkingEnabled=$thinkingEnabled, thinkingBudgetTokens=$thinkingBudgetTokens" +
            ", effort=$effort, thinkingDisplay=$thinkingDisplay" +
            ", interleavedThinking=$interleavedThinking, toolChoice=$toolChoice" +
            ", cacheRetention=$cacheRetention, timeoutMs=$timeoutMs, maxRetries=$maxRetries" +
            ", maxRetryDelayMs=$maxRetryDelayMs, env=${env.keys}, headers=${headers.keys})"
}

/**
 * Removes unpaired Unicode surrogates, ported verbatim from pi's
 * sanitizeSurrogates (packages/ai/src/utils/sanitize-unicode.ts).
 */
internal fun sanitizeSurrogates(text: String): String {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate()) {
            if (i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                sb.append(c).append(text[i + 1])
                i += 2
            } else {
                i += 1
            }
        } else if (c.isLowSurrogate()) {
            // Keep only if preceded by a high surrogate; that case is consumed above.
            if (i > 0 && text[i - 1].isHighSurrogate()) {
                sb.append(c)
            }
            i += 1
        } else {
            sb.append(c)
            i += 1
        }
    }
    return sb.toString()
}

/** pi's resolveCacheRetention: explicit value, then PI_CACHE_RETENTION=long, else short. */
internal fun resolveCacheRetention(cacheRetention: CacheRetention?, env: Map<String, String>): CacheRetention {
    if (cacheRetention != null) return cacheRetention
    if (env["PI_CACHE_RETENTION"] == "long") return CacheRetention.LONG
    return CacheRetention.SHORT
}

/**
 * pi's getCacheControl: cache_control marker to attach, or null when
 * retention is "none". Long retention uses the 1h TTL when supported.
 */
internal fun getCacheControl(model: Model, options: AnthropicMessagesOptions): JsonObject? {
    val retention = resolveCacheRetention(options.cacheRetention, options.env)
    if (retention == CacheRetention.NONE) return null
    val ttl = retention == CacheRetention.LONG && anthropicCompatOf(model).supportsLongCacheRetention
    return buildJsonObject {
        put("type", "ephemeral")
        if (ttl) put("ttl", "1h")
    }
}

// --- Claude Code OAuth stealth naming (pi's claudeCodeTools table) ---------

internal const val CLAUDE_CODE_VERSION = "2.1.75"

// Claude Code 2.x tool names (canonical casing), pi's claudeCodeTools.
private val CLAUDE_CODE_TOOLS = listOf(
    "Read", "Write", "Edit", "Bash", "Grep", "Glob", "AskUserQuestion",
    "EnterPlanMode", "ExitPlanMode", "KillShell", "NotebookEdit", "Skill",
    "Task", "TaskOutput", "TodoWrite", "WebFetch", "WebSearch",
)

private val CC_TOOL_LOOKUP = CLAUDE_CODE_TOOLS.associateBy { it.lowercase() }

/** pi's toClaudeCodeName: CC canonical casing when it matches (case-insensitive). */
internal fun toClaudeCodeName(name: String): String = CC_TOOL_LOOKUP[name.lowercase()] ?: name

/** pi's fromClaudeCodeName: map a CC-cased name back onto a provided tool's real name. */
internal fun fromClaudeCodeName(name: String, tools: List<Tool>): String {
    if (tools.isNotEmpty()) {
        val lowerName = name.lowercase()
        val matched = tools.firstOrNull { it.name.lowercase() == lowerName }
        if (matched != null) return matched.name
    }
    return name
}

internal const val CLAUDE_CODE_IDENTITY = "You are Claude Code, Anthropic's official CLI for Claude."

/** pi's isOAuthToken. */
internal fun isOAuthToken(apiKey: String): Boolean = apiKey.contains("sk-ant-oat")

/**
 * pi's normalizeToolCallId: Anthropic requires IDs matching
 * ^[a-zA-Z0-9_-]+$ (max 64 chars).
 */
internal fun normalizeToolCallId(id: String): String =
    id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

// --- Content / message conversion ------------------------------------------

/**
 * pi's convertContentBlocks: text-only content collapses to a joined string;
 * image content becomes base64 source blocks with a placeholder text block
 * when no text is present.
 */
internal fun convertContentBlocks(content: List<Content>): Any {
    val hasImages = content.any { it.type == ContentType.IMAGE }
    if (!hasImages) {
        return sanitizeSurrogates(content.filterIsInstance<TextContent>().joinToString("\n") { it.text })
    }

    val blocks = content.map { block ->
        when (block) {
            is TextContent -> buildJsonObject {
                put("type", "text")
                put("text", sanitizeSurrogates(block.text))
            }
            is ImageContent -> buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", block.mimeType)
                    put("data", block.data)
                })
            }
            else -> null
        }
    }.filterNotNull().toMutableList()

    if (blocks.none { (it["type"] as? JsonPrimitive)?.content == "text" }) {
        blocks.add(
            0,
            buildJsonObject {
                put("type", "text")
                put("text", "(see attached image)")
            },
        )
    }
    return JsonArray(blocks)
}

private fun textBlock(text: String, cacheControl: JsonObject? = null): JsonObject = buildJsonObject {
    put("type", "text")
    put("text", sanitizeSurrogates(text))
    if (cacheControl != null) put("cache_control", cacheControl)
}

private fun imageBlock(block: ImageContent): JsonObject = buildJsonObject {
    put("type", "image")
    put("source", buildJsonObject {
        put("type", "base64")
        put("media_type", block.mimeType)
        put("data", block.data)
    })
}

/**
 * pi's convertMessages: transforms [messages] (already passed through
 * [transformMessages]) into Anthropic MessageParam objects.
 *
 * - Empty user text content is dropped; image-only user content keeps images.
 * - Assistant thinking is replayed with signatures; redacted thinking passes
 *   the opaque payload back as redacted_thinking; missing signatures degrade
 *   to text (or an empty-signature block for allowEmptySignature models).
 * - Consecutive toolResult messages are grouped into one user message.
 * - cache_control lands on the final block of the last user message.
 */
internal fun convertMessages(
    transformedMessages: List<Message>,
    isOAuthToken: Boolean,
    cacheControl: JsonObject?,
    allowEmptySignature: Boolean,
): List<JsonObject> {
    val params = mutableListOf<JsonObject>()

    var i = 0
    while (i < transformedMessages.size) {
        val msg = transformedMessages[i]
        when (msg.role) {
            works.resolve.pathfinder.ai.core.MessageRole.USER -> {
                val userContent = (msg as works.resolve.pathfinder.ai.core.UserMessage).content
                val blocks = userContent.mapNotNull { block ->
                    when (block) {
                        is TextContent ->
                            if (block.text.trim().isNotEmpty()) textBlock(block.text) else null
                        is ImageContent -> imageBlock(block)
                        else -> null
                    }
                }
                if (blocks.isNotEmpty()) {
                    params.add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", JsonArray(blocks))
                        },
                    )
                }
            }
            works.resolve.pathfinder.ai.core.MessageRole.ASSISTANT -> {
                val assistant = msg as works.resolve.pathfinder.ai.core.AssistantMessage
                val blocks = mutableListOf<JsonObject>()
                for (block in assistant.content) {
                    when (block) {
                        is TextContent -> if (block.text.trim().isNotEmpty()) blocks.add(textBlock(block.text))
                        is ThinkingContent -> {
                            // Redacted thinking: pass the opaque payload back.
                            if (block.redacted) {
                                blocks.add(
                                    buildJsonObject {
                                        put("type", "redacted_thinking")
                                        put("data", block.thinkingSignature ?: "")
                                    },
                                )
                                continue
                            }
                            val signature = block.thinkingSignature
                            val hasSignature = !signature.isNullOrEmpty() && signature.trim().isNotEmpty()
                            if (block.thinking.trim().isEmpty() && !hasSignature) continue
                            if (!hasSignature) {
                                blocks.add(
                                    if (allowEmptySignature) {
                                        buildJsonObject {
                                            put("type", "thinking")
                                            put("thinking", sanitizeSurrogates(block.thinking))
                                            put("signature", "")
                                        }
                                    } else {
                                        textBlock(block.thinking)
                                    },
                                )
                            } else {
                                blocks.add(
                                    buildJsonObject {
                                        put("type", "thinking")
                                        put("thinking", sanitizeSurrogates(block.thinking))
                                        put("signature", signature)
                                    },
                                )
                            }
                        }
                        is ToolCall -> blocks.add(
                            buildJsonObject {
                                put("type", "tool_use")
                                put("id", block.id)
                                put("name", if (isOAuthToken) toClaudeCodeName(block.name) else block.name)
                                put(
                                    "input",
                                    parseOrEmptyObject(block.arguments),
                                )
                            },
                        )
                        else -> {}
                    }
                }
                if (blocks.isNotEmpty()) {
                    params.add(
                        buildJsonObject {
                            put("role", "assistant")
                            put("content", JsonArray(blocks))
                        },
                    )
                }
            }
            works.resolve.pathfinder.ai.core.MessageRole.TOOL_RESULT -> {
                // Collect all consecutive toolResult messages.
                val toolResults = mutableListOf<JsonObject>()
                var j = i
                while (j < transformedMessages.size &&
                    transformedMessages[j].role == works.resolve.pathfinder.ai.core.MessageRole.TOOL_RESULT
                ) {
                    val toolResult = transformedMessages[j] as ToolResultMessage
                    val convertedContent = convertContentBlocks(toolResult.content)
                    toolResults.add(
                        buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", toolResult.toolCallId)
                            put(
                                "content",
                                if (convertedContent is JsonArray) convertedContent else JsonPrimitive(convertedContent.toString()),
                            )
                            put("is_error", toolResult.isError)
                        },
                    )
                    j++
                }
                i = j - 1
                params.add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", JsonArray(toolResults))
                    },
                )
            }
        }
        i++
    }

    // Add cache_control to the last user message to cache conversation history.
    if (cacheControl != null && params.isNotEmpty()) {
        val lastMessage = params.last()
        if ((lastMessage["role"] as? JsonPrimitive)?.content == "user") {
            val content = lastMessage["content"]
            val contentValue = when {
                content is JsonArray && content.isNotEmpty() -> {
                    val lastBlock = content.last() as? JsonObject
                    val type = (lastBlock?.get("type") as? JsonPrimitive)?.content
                    if (lastBlock != null && (type == "text" || type == "image" || type == "tool_result")) {
                        JsonArray(content.dropLast(1) + lastBlock.toMutableMap().apply {
                            put("cache_control", cacheControl)
                        }.let { JsonObject(it) })
                    } else {
                        content
                    }
                }
                content is JsonPrimitive -> JsonArray(
                    listOf(textBlock(content.content, cacheControl)),
                )
                else -> content ?: JsonNull
            }
            params[params.size - 1] = buildJsonObject {
                put("role", "user")
                put("content", contentValue)
            }
        }
    }

    return params
}

/** Parses raw tool arguments to a JSON object; blank/malformed values become `{}`. */
private fun parseOrEmptyObject(arguments: String): JsonObject {
    if (arguments.isBlank()) return JsonObject(emptyMap())
    return try {
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(arguments)
        parsed as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}

/**
 * pi's convertTools (without strict sampling and deferred loading): legacy
 * input_schema shape, optional eager_input_streaming, and cache_control on
 * the last immediate tool.
 */
internal fun convertTools(
    tools: List<Tool>,
    isOAuthToken: Boolean,
    supportsEagerToolInputStreaming: Boolean,
    cacheControl: JsonObject?,
): List<JsonObject> = tools.mapIndexed { index, tool ->
    val parameters = tool.parameters as? JsonObject ?: JsonObject(emptyMap())
    buildJsonObject {
        put("name", if (isOAuthToken) toClaudeCodeName(tool.name) else tool.name)
        put("description", tool.description)
        if (supportsEagerToolInputStreaming) put("eager_input_streaming", true)
        put(
            "input_schema",
            buildJsonObject {
                put("type", "object")
                put("properties", parameters["properties"] ?: JsonObject(emptyMap()))
                put("required", parameters["required"] ?: JsonArray(emptyList()))
            },
        )
        if (cacheControl != null && index == tools.size - 1) put("cache_control", cacheControl)
    }
}

/**
 * pi's buildParams: assembles the streaming Messages API request body.
 */
internal fun buildRequestBody(
    model: Model,
    context: Context,
    isOAuthToken: Boolean,
    options: AnthropicMessagesOptions,
): JsonObject {
    val cacheControl = getCacheControl(model, options)
    val compat = anthropicCompatOf(model)
    val transformed = transformMessages(context.messages, model, ::normalizeToolCallId)

    val body = mutableMapOf<String, JsonElement>()
    body["model"] = JsonPrimitive(model.id)
    body["messages"] = JsonArray(
        convertMessages(transformed, isOAuthToken, cacheControl, compat.allowEmptySignature),
    )
    body["max_tokens"] = JsonPrimitive(options.maxTokens ?: model.maxTokens)
    body["stream"] = JsonPrimitive(true)

    // System prompt: OAuth requests prepend the Claude Code identity.
    val systemBlocks = mutableListOf<JsonObject>()
    if (isOAuthToken) {
        systemBlocks.add(textBlock(CLAUDE_CODE_IDENTITY, cacheControl))
        if (context.systemPrompt != null) {
            systemBlocks.add(textBlock(context.systemPrompt, cacheControl))
        }
    } else if (context.systemPrompt != null) {
        systemBlocks.add(textBlock(context.systemPrompt, cacheControl))
    }
    if (systemBlocks.isNotEmpty()) body["system"] = JsonArray(systemBlocks)

    // Temperature is incompatible with extended thinking.
    if (options.temperature != null && options.thinkingEnabled != true && compat.supportsTemperature) {
        body["temperature"] = JsonPrimitive(options.temperature)
    }

    if (context.tools.isNotEmpty()) {
        body["tools"] = JsonArray(
            convertTools(
                context.tools,
                isOAuthToken,
                compat.supportsEagerToolInputStreaming,
                if (compat.supportsCacheControlOnTools) cacheControl else null,
            ),
        )
    }

    // Thinking mode: adaptive, budget-based, or explicitly disabled.
    if (model.reasoning) {
        if (options.thinkingEnabled == true) {
            val display = options.thinkingDisplay
            if (compat.forceAdaptiveThinking == true) {
                body["thinking"] = buildJsonObject {
                    put("type", "adaptive")
                    put("display", display.name.lowercase())
                }
                options.effort?.let {
                    body["output_config"] = buildJsonObject {
                        put("effort", it.name.lowercase())
                    }
                }
            } else {
                body["thinking"] = buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", options.thinkingBudgetTokens?.takeIf { it != 0 } ?: 1024)
                    put("display", display.name.lowercase())
                }
            }
        } else if (options.thinkingEnabled == false && !thinkingOffExplicitlyUnsupported(model)) {
            body["thinking"] = buildJsonObject { put("type", "disabled") }
        }
    }

    when (val choice = options.toolChoice) {
        AnthropicToolChoice.Auto -> body["tool_choice"] = buildJsonObject { put("type", "auto") }
        AnthropicToolChoice.Any -> body["tool_choice"] = buildJsonObject { put("type", "any") }
        AnthropicToolChoice.None -> body["tool_choice"] = buildJsonObject { put("type", "none") }
        is AnthropicToolChoice.Tool -> body["tool_choice"] = buildJsonObject {
            put("type", "tool")
            put("name", choice.name)
        }
        null -> {}
    }

    return JsonObject(body)
}

/** pi's `model.thinkingLevelMap?.off !== null` check: an explicit null entry disables off. */
internal fun thinkingOffExplicitlyUnsupported(model: Model): Boolean {
    val map = model.thinkingLevelMap ?: return false
    return map.isSpecified(ModelThinkingLevel.OFF) && map.forLevel(ModelThinkingLevel.OFF) == null
}

// --- streamSimple helpers (pi's simple-options.ts) --------------------------

/** pi's MIN_ANSWER_TOKENS. */
internal const val MIN_ANSWER_TOKENS = 1024

/** pi's DEFAULT_THINKING_BUDGETS. */
internal val DEFAULT_THINKING_BUDGETS = mapOf(
    works.resolve.pathfinder.ai.core.ThinkingLevel.MINIMAL to 1024,
    works.resolve.pathfinder.ai.core.ThinkingLevel.LOW to 2048,
    works.resolve.pathfinder.ai.core.ThinkingLevel.MEDIUM to 8192,
    works.resolve.pathfinder.ai.core.ThinkingLevel.HIGH to 16384,
)

/** pi's clampReasoning: xhigh/max clamp to high before budget lookups. */
internal fun clampReasoning(
    level: works.resolve.pathfinder.ai.core.ThinkingLevel,
): works.resolve.pathfinder.ai.core.ThinkingLevel =
    if (level == works.resolve.pathfinder.ai.core.ThinkingLevel.XHIGH ||
        level == works.resolve.pathfinder.ai.core.ThinkingLevel.MAX
    ) {
        works.resolve.pathfinder.ai.core.ThinkingLevel.HIGH
    } else {
        level
    }

internal fun thinkingBudgetForLevel(
    level: works.resolve.pathfinder.ai.core.ThinkingLevel,
    customBudgets: Map<works.resolve.pathfinder.ai.core.ThinkingLevel, Int> = emptyMap(),
): Int {
    val budgets = DEFAULT_THINKING_BUDGETS + customBudgets
    return budgets[clampReasoning(level)]!!
}

/** pi's adjustMaxTokensForThinking: fit a thinking budget under the response ceiling. */
internal fun adjustMaxTokensForThinking(
    baseMaxTokens: Int?,
    modelMaxTokens: Int,
    reasoningLevel: works.resolve.pathfinder.ai.core.ThinkingLevel,
    customBudgets: Map<works.resolve.pathfinder.ai.core.ThinkingLevel, Int> = emptyMap(),
): Pair<Int, Int> {
    var thinkingBudget = thinkingBudgetForLevel(reasoningLevel, customBudgets)
    val maxTokens = if (baseMaxTokens == null) {
        modelMaxTokens
    } else {
        minOf(baseMaxTokens + thinkingBudget, modelMaxTokens)
    }
    if (maxTokens <= thinkingBudget) {
        thinkingBudget = minOf(thinkingBudget, maxOf(0, maxTokens - MIN_ANSWER_TOKENS))
    }
    return maxTokens to thinkingBudget
}

/**
 * pi's mapThinkingLevelToEffort: model thinkingLevelMap wins, then the level
 * default mapping (minimal/low -> low, medium -> medium, else high).
 */
internal fun mapThinkingLevelToEffort(
    model: Model,
    level: works.resolve.pathfinder.ai.core.ThinkingLevel?,
): AnthropicEffort {
    val mapped = level?.let { model.thinkingLevelMap?.forLevel(ModelThinkingLevel.valueOf(it.name)) }
    if (mapped is String) {
        return try {
            AnthropicEffort.valueOf(mapped.uppercase())
        } catch (_: IllegalArgumentException) {
            AnthropicEffort.HIGH
        }
    }
    return when (level) {
        works.resolve.pathfinder.ai.core.ThinkingLevel.MINIMAL,
        works.resolve.pathfinder.ai.core.ThinkingLevel.LOW,
        -> AnthropicEffort.LOW
        works.resolve.pathfinder.ai.core.ThinkingLevel.MEDIUM -> AnthropicEffort.MEDIUM
        else -> AnthropicEffort.HIGH
    }
}

/** pi's buildBaseOptions (reduced): common clamped defaults for streamSimple. */
internal fun buildBaseOptions(
    model: Model,
    context: Context,
    options: works.resolve.pathfinder.ai.core.SimpleStreamOptions,
): AnthropicMessagesOptions =
    AnthropicMessagesOptions(
        apiKey = options.apiKey,
        sessionId = options.sessionId,
        temperature = options.temperature,
        maxTokens = clampMaxTokensToContext(model, context, options.maxTokens ?: model.maxTokens),
        timeoutMs = options.timeoutMs,
        maxRetries = options.maxRetries,
        maxRetryDelayMs = options.maxRetryDelayMs,
        env = options.env,
        headers = options.headers,
    )
