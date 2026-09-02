package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.ContentType
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.ProviderResponse
import works.resolve.pathfinder.ai.core.StreamOptions
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.anthropicCompatOf
import works.resolve.pathfinder.ai.utils.clampMaxTokensToContext
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.optionsToString
import works.resolve.pathfinder.ai.utils.redactedSecret
import works.resolve.pathfinder.telemetry.TelemetryContext
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.core.toModelThinkingLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class AnthropicEffort { LOW, MEDIUM, HIGH, XHIGH, MAX }

enum class AnthropicThinkingDisplay { SUMMARIZED, OMITTED }

sealed interface AnthropicToolChoice {
    data object Auto : AnthropicToolChoice
    data object Any : AnthropicToolChoice
    data object None : AnthropicToolChoice
    data class Tool(val name: String) : AnthropicToolChoice
}

/**
 * Options for the Anthropic Messages adapter. pi's `client` field is not
 * ported: the transport is injected at construction instead.
 */
data class AnthropicMessagesOptions(
    val apiKey: String? = null,
    val sessionId: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** Null means "unspecified" (thinking omitted). */
    val thinkingEnabled: Boolean? = null,
    /** Default 1024 when enabled (older models). */
    val thinkingBudgetTokens: Int? = null,
    /** Adaptive-thinking effort level. */
    val effort: AnthropicEffort? = null,
    val thinkingDisplay: AnthropicThinkingDisplay = AnthropicThinkingDisplay.SUMMARIZED,
    val interleavedThinking: Boolean = true,
    val toolChoice: AnthropicToolChoice? = null,
    val cacheRetention: CacheRetention? = null,
    val timeoutMs: Long? = null,
    val maxRetries: Int = 0,
    val maxRetryDelayMs: Long = StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String?> = emptyMap(),
    /**
     * Request hook that may return a replacement for the outgoing payload,
     * applied before serialization. It receives full message content —
     * installers must not log it. Never included in toString().
     */
    val onPayload: (suspend (payload: JsonObject, model: Model) -> JsonObject?)? = null,
    /** Invoked after the 2xx response headers arrive. Never included in toString(). */
    val onResponse: (suspend (response: ProviderResponse, model: Model) -> Unit)? = null,
    /**
     * Explicit parent telemetry context for this request. Dormant in this
     * port — carried for shape fidelity.
     */
    val telemetryContext: TelemetryContext? = null,
) {
    override fun toString(): String = optionsToString(
        "AnthropicMessagesOptions",
        "apiKey" to redactedSecret(apiKey),
        "sessionId" to sessionId,
        "temperature" to temperature,
        "maxTokens" to maxTokens,
        "thinkingEnabled" to thinkingEnabled,
        "thinkingBudgetTokens" to thinkingBudgetTokens,
        "effort" to effort,
        "thinkingDisplay" to thinkingDisplay,
        "interleavedThinking" to interleavedThinking,
        "toolChoice" to toolChoice,
        "cacheRetention" to cacheRetention,
        "timeoutMs" to timeoutMs,
        "maxRetries" to maxRetries,
        "maxRetryDelayMs" to maxRetryDelayMs,
        "env" to env.keys,
        "headers" to headers.keys,
        "onPayload" to (onPayload != null),
        "onResponse" to (onResponse != null),
        "telemetryContext" to (telemetryContext != null),
    )
}

internal fun resolveCacheRetention(cacheRetention: CacheRetention?, env: Map<String, String>): CacheRetention {
    if (cacheRetention != null) return cacheRetention
    if (env["PI_CACHE_RETENTION"] == "long") return CacheRetention.LONG
    return CacheRetention.SHORT
}

internal fun getCacheControl(model: Model, options: AnthropicMessagesOptions): JsonObject? {
    val retention = resolveCacheRetention(options.cacheRetention, options.env)
    if (retention == CacheRetention.NONE) return null
    val ttl = retention == CacheRetention.LONG && anthropicCompatOf(model).supportsLongCacheRetention
    return buildJsonObject {
        put("type", "ephemeral")
        if (ttl) put("ttl", "1h")
    }
}

// Stealth mode: on OAuth requests, tool names mimic Claude Code's exactly.
internal const val CLAUDE_CODE_VERSION = "2.1.75"

private val CLAUDE_CODE_TOOLS = listOf(
    "Read", "Write", "Edit", "Bash", "Grep", "Glob", "AskUserQuestion",
    "EnterPlanMode", "ExitPlanMode", "KillShell", "NotebookEdit", "Skill",
    "Task", "TaskOutput", "TodoWrite", "WebFetch", "WebSearch",
)

private val CC_TOOL_LOOKUP = CLAUDE_CODE_TOOLS.associateBy { it.lowercase() }

internal fun toClaudeCodeName(name: String): String = CC_TOOL_LOOKUP[name.lowercase()] ?: name

/** Maps a Claude Code-cased name back onto the matching tool's real name. */
internal fun fromClaudeCodeName(name: String, tools: List<Tool>): String {
    if (tools.isNotEmpty()) {
        val lowerName = name.lowercase()
        val matched = tools.firstOrNull { it.name.lowercase() == lowerName }
        if (matched != null) return matched.name
    }
    return name
}

internal const val CLAUDE_CODE_IDENTITY = "You are Claude Code, Anthropic's official CLI for Claude."

internal fun isOAuthToken(apiKey: String): Boolean = apiKey.contains("sk-ant-oat")

/** Anthropic tool-use ids must match ^[a-zA-Z0-9_-]+$ (max 64 chars). */
internal fun normalizeToolCallId(id: String): String =
    id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)

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

    if (blocks.none { it.str("type") == "text" }) {
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

    // Prompt caching: the marker on the last user message caches the
    // conversation history.
    if (cacheControl != null && params.isNotEmpty()) {
        val lastMessage = params.last()
        if (lastMessage.str("role") == "user") {
            val content = lastMessage["content"]
            val contentValue = when {
                content is JsonArray && content.isNotEmpty() -> {
                    val lastBlock = content.last() as? JsonObject
                    val type = lastBlock.str("type")
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

private fun parseOrEmptyObject(arguments: String): JsonObject {
    if (arguments.isBlank()) return JsonObject(emptyMap())
    return try {
        val parsed = lenientJson.parseToJsonElement(arguments)
        parsed as? JsonObject ?: JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }
}

internal fun convertTools(
    tools: List<Tool>,
    isOAuthToken: Boolean,
    supportsEagerToolInputStreaming: Boolean,
    supportsStrictTools: Boolean,
    cacheControl: JsonObject?,
): List<JsonObject> = tools.mapIndexed { index, tool ->
    val strict = resolveJsonSchemaStrictSampling(tool, supportsStrictTools)
    val parameters = getJsonSchemaToolParameters(tool, strict)
    val schema = parameters as? JsonObject ?: JsonObject(emptyMap())
    val legacyInputSchema = buildJsonObject {
        put("type", "object")
        put("properties", schema["properties"] ?: JsonObject(emptyMap()))
        put("required", schema["required"] ?: JsonArray(emptyList()))
    }
    // Legacy type/properties/required override the schema's own keys.
    val inputSchema = if (strict == true) {
        val merged = LinkedHashMap(schema)
        merged.putAll(legacyInputSchema)
        JsonObject(merged)
    } else {
        legacyInputSchema
    }
    buildJsonObject {
        put("name", if (isOAuthToken) toClaudeCodeName(tool.name) else tool.name)
        put("description", tool.description)
        if (supportsEagerToolInputStreaming) put("eager_input_streaming", true)
        if (strict == true) put("strict", true)
        put("input_schema", inputSchema)
        if (cacheControl != null && index == tools.size - 1) put("cache_control", cacheControl)
    }
}

internal fun buildRequestBody(
    model: Model,
    context: Context,
    isOAuthToken: Boolean,
    options: AnthropicMessagesOptions,
): JsonObject {
    val cacheControl = getCacheControl(model, options)
    val compat = anthropicCompatOf(model)
    val transformed = transformMessages(context.messages, model) { id, _ -> normalizeToolCallId(id) }

    val body = mutableMapOf<String, JsonElement>()
    body["model"] = JsonPrimitive(model.id)
    body["messages"] = JsonArray(
        convertMessages(transformed, isOAuthToken, cacheControl, compat.allowEmptySignature),
    )
    body["max_tokens"] = JsonPrimitive(options.maxTokens ?: model.maxTokens)
    body["stream"] = JsonPrimitive(true)

    // OAuth requests prepend the Claude Code identity to the system prompt.
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
                compat.supportsStrictTools,
                if (compat.supportsCacheControlOnTools) cacheControl else null,
            ),
        )
    }

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

    // Fallbacks carry model ids only; provider/cost are local metadata.
    // Omitted when empty: Anthropic rejects the field without permitted
    // fallback targets.
    val allowedFallbackModels = compat.allowedFallbackModels
    if (allowedFallbackModels.isNotEmpty()) {
        body["fallbacks"] = JsonArray(
            allowedFallbackModels.map { buildJsonObject { put("model", it.model) } },
        )
    }

    return JsonObject(body)
}

/** An explicit null OFF entry in [Model.thinkingLevelMap] means off is unsupported. */
internal fun thinkingOffExplicitlyUnsupported(model: Model): Boolean {
    val map = model.thinkingLevelMap ?: return false
    return map.isSpecified(ModelThinkingLevel.OFF) && map.forLevel(ModelThinkingLevel.OFF) == null
}

internal const val MIN_ANSWER_TOKENS = 1024

internal val DEFAULT_THINKING_BUDGETS = mapOf(
    works.resolve.pathfinder.ai.core.ThinkingLevel.MINIMAL to 1024,
    works.resolve.pathfinder.ai.core.ThinkingLevel.LOW to 2048,
    works.resolve.pathfinder.ai.core.ThinkingLevel.MEDIUM to 8192,
    works.resolve.pathfinder.ai.core.ThinkingLevel.HIGH to 16384,
)

/** Xhigh/max have no budget entries, so they clamp to high before lookup. */
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

internal fun mapThinkingLevelToEffort(
    model: Model,
    level: works.resolve.pathfinder.ai.core.ThinkingLevel?,
): AnthropicEffort {
    // Core ThinkingLevel has no OFF case, so toModelThinkingLevel is total here.
    val mapped = level?.let { model.thinkingLevelMap?.forLevel(it.toModelThinkingLevel()) }
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

/**
 * Required maps to [AnthropicToolChoice.Any]: the Anthropic protocol has no
 * "required" (a tool must be called), matching the Any/Required collapse in
 * the other adapters.
 */
internal fun mapToolChoice(choice: works.resolve.pathfinder.ai.core.ToolChoice?): AnthropicToolChoice? =
    when (choice) {
        works.resolve.pathfinder.ai.core.ToolChoice.Auto -> AnthropicToolChoice.Auto
        works.resolve.pathfinder.ai.core.ToolChoice.None -> AnthropicToolChoice.None
        works.resolve.pathfinder.ai.core.ToolChoice.Any,
        works.resolve.pathfinder.ai.core.ToolChoice.Required,
        -> AnthropicToolChoice.Any
        is works.resolve.pathfinder.ai.core.ToolChoice.Function -> AnthropicToolChoice.Tool(choice.name)
        null -> null
    }

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
        cacheRetention = options.cacheRetention,
        timeoutMs = options.timeoutMs,
        maxRetries = options.maxRetries,
        maxRetryDelayMs = options.maxRetryDelayMs,
        env = options.env,
        headers = options.headers,
        onPayload = options.onPayload,
        onResponse = options.onResponse,
        telemetryContext = options.telemetryContext,
    )
