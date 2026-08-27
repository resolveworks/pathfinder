package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.ChatTemplateKwargValue
import works.resolve.pathfinder.ai.core.ContentType
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.MaxTokensField
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.MessageRole
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.OpenAiCompletionsCompat
import works.resolve.pathfinder.ai.core.OpenAiCompletionsOptions
import works.resolve.pathfinder.ai.core.ThinkingFormat
import works.resolve.pathfinder.ai.core.ThinkingLevelMap
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.ToolChoice
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Request construction for OpenAI Chat Completions, ported from pi's
 * openai-completions.ts and reduced to the ZAI-relevant behavior: message
 * replay (system/user/assistant/tool-result), tool JSON Schema conversion,
 * ZAI thinking format, max_tokens field, tool_stream, and
 * stream_options.include_usage.
 */
object OpenAiCompletionsPayload {

    /** Reasoning delta fields some OpenAI-compatible servers use, in preference order. */
    val REASONING_FIELDS = listOf("reasoning_content", "reasoning", "reasoning_text")

    fun buildRequestBody(
        model: Model,
        context: Context,
        options: OpenAiCompletionsOptions,
        compat: OpenAiCompletionsCompat = model.compat,
    ): JsonObject {
        val body = mutableMapOf<String, JsonElement>()
        body["model"] = JsonPrimitive(model.id)
        body["messages"] = JsonArray(convertMessages(model, context, compat))
        body["stream"] = JsonPrimitive(true)

        if (compat.supportsUsageInStreaming) {
            body["stream_options"] = buildJsonObject { put("include_usage", true) }
        }
        if (compat.supportsStore) {
            body["store"] = JsonPrimitive(false)
        }

        options.maxTokens?.let { maxTokens ->
            val field = when (compat.maxTokensField) {
                MaxTokensField.MAX_TOKENS -> "max_tokens"
                MaxTokensField.MAX_COMPLETION_TOKENS -> "max_completion_tokens"
            }
            body[field] = JsonPrimitive(maxTokens)
        }
        options.temperature?.let { body["temperature"] = JsonPrimitive(it) }

        if (context.tools.isNotEmpty()) {
            body["tools"] = JsonArray(context.tools.map { convertTool(it) })
            if (compat.zaiToolStream) {
                body["tool_stream"] = JsonPrimitive(true)
            }
        } else if (hasToolHistory(context.messages)) {
            // Some proxies require the tools param when history has tool calls.
            body["tools"] = JsonArray(emptyList())
        }

        // pi's buildParams: `if (options?.toolChoice) params.tool_choice = options.toolChoice`
        // (openai-completions.ts:850-851), where toolChoice is the wire form
        // ChatCompletionToolChoiceOption: "auto"/"none"/"required" or
        // {type:"function", function:{name}}.
        mapToolChoice(options.toolChoice)?.let { body["tool_choice"] = it }

        applyThinking(model, options, compat)?.let { body.putAll(it) }

        return JsonObject(body)
    }

    /**
     * Serializes the core ToolChoice to the Chat Completions `tool_choice`
     * wire form (pi's ChatCompletionToolChoiceOption pass-through,
     * openai-completions.ts:850-851). Null means the field is omitted.
     */
    private fun mapToolChoice(choice: ToolChoice?): JsonElement? = when (choice) {
        null -> null
        ToolChoice.Auto -> JsonPrimitive("auto")
        ToolChoice.None -> JsonPrimitive("none")
        ToolChoice.Any, ToolChoice.Required -> JsonPrimitive("required")
        is ToolChoice.Function -> buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject { put("name", choice.name) })
        }
    }

    /** Returns the extra thinking-related params to merge into the request body. */
    private fun applyThinking(
        model: Model,
        options: OpenAiCompletionsOptions,
        compat: OpenAiCompletionsCompat,
    ): Map<String, JsonElement>? {
        if (!model.reasoning) return null
        // Direct OFF never enables reasoning; it is equivalent to no effort.
        val effort = options.reasoningEffort?.takeIf { it != ModelThinkingLevel.OFF }
        val map = model.thinkingLevelMap

        /** Unspecified passes the level through; explicit null omits the field. */
        fun mappedEffort(level: ModelThinkingLevel): String? {
            if (map == null) return level.name.lowercase()
            return if (map.isSpecified(level)) map.forLevel(level) else level.name.lowercase()
        }

        /** Pi's `map?.off !== null`: false only for an explicit null OFF entry. */
        val explicitNullOff = map?.isSpecified(ModelThinkingLevel.OFF) == true &&
            map.forLevel(ModelThinkingLevel.OFF) == null

        fun effortParam(): Pair<String, JsonElement>? =
            mappedEffort(effort!!)?.let { "reasoning_effort" to JsonPrimitive(it) }

        when (compat.thinkingFormat) {
            ThinkingFormat.ZAI -> {
                val thinking = if (effort != null) {
                    buildJsonObject {
                        put("type", "enabled")
                        put("clear_thinking", false)
                    }
                } else {
                    buildJsonObject { put("type", "disabled") }
                }
                val params = mutableListOf<Pair<String, JsonElement>>("thinking" to thinking)
                if (effort != null && compat.supportsReasoningEffort) {
                    effortParam()?.let { params.add(it) }
                }
                return params.toMap()
            }

            ThinkingFormat.QWEN -> {
                val params = mutableListOf<Pair<String, JsonElement>>("enable_thinking" to JsonPrimitive(effort != null))
                if (effort != null && compat.supportsReasoningEffort) {
                    effortParam()?.let { params.add(it) }
                }
                return params.toMap()
            }

            ThinkingFormat.DEEPSEEK -> {
                val params = mutableListOf<Pair<String, JsonElement>>()
                val thinking = if (effort != null) {
                    "enabled"
                } else if (!explicitNullOff) {
                    "disabled"
                } else {
                    null
                }
                thinking?.let { params.add("thinking" to buildJsonObject { put("type", it) }) }
                if (effort != null && compat.supportsReasoningEffort) {
                    effortParam()?.let { params.add(it) }
                }
                return params.toMap()
            }

            ThinkingFormat.OPENROUTER -> {
                val offEffort = map?.takeIf { it.isSpecified(ModelThinkingLevel.OFF) }
                    ?.forLevel(ModelThinkingLevel.OFF)
                val effortValue = when {
                    effort != null -> mappedEffort(effort)
                    !explicitNullOff -> offEffort ?: "none"
                    else -> null
                }
                return effortValue?.let {
                    mapOf("reasoning" to buildJsonObject { put("effort", it) })
                }
            }

            ThinkingFormat.TOGETHER -> {
                val params = mutableListOf<Pair<String, JsonElement>>(
                    "reasoning" to buildJsonObject { put("enabled", effort != null) },
                )
                if (effort != null && compat.supportsReasoningEffort) {
                    effortParam()?.let { params.add(it) }
                }
                return params.toMap()
            }

            ThinkingFormat.ANT_LING -> {
                if (effort == null) return null
                // Pi requires an explicitly mapped string; no level-name fallback.
                val mapped = map?.takeIf { it.isSpecified(effort) }?.forLevel(effort)
                return mapped?.let { mapOf("reasoning" to buildJsonObject { put("effort", it) }) }
            }

            ThinkingFormat.BASETEN -> {
                val params = mutableListOf<Pair<String, JsonElement>>()
                buildChatTemplateValues(compat, effort, map)?.let {
                    params.add("chat_template_args" to it)
                }
                if (compat.supportsReasoningEffort) {
                    // Pi maps the OFF entry when effort is null; no fallback then.
                    val value = if (effort != null) {
                        mappedEffort(effort)
                    } else {
                        map?.takeIf { it.isSpecified(ModelThinkingLevel.OFF) }
                            ?.forLevel(ModelThinkingLevel.OFF)
                    }
                    value?.let { params.add("reasoning_effort" to JsonPrimitive(it)) }
                }
                return params.toMap()
            }

            ThinkingFormat.OPENAI -> {
                // OpenAI-style reasoning_effort.
                if (!compat.supportsReasoningEffort) return null
                if (effort != null) {
                    return effortParam()?.let { mapOf(it) }
                }
                // Only an explicitly mapped non-null off value is sent.
                val off = if (map?.isSpecified(ModelThinkingLevel.OFF) == true) {
                    map.forLevel(ModelThinkingLevel.OFF)
                } else {
                    null
                }
                return off?.let { mapOf("reasoning_effort" to JsonPrimitive(it)) }
            }
        }
    }

    /** Resolves compat.chatTemplateArgs into wire values; null when empty. */
    private fun buildChatTemplateValues(
        compat: OpenAiCompletionsCompat,
        effort: ModelThinkingLevel?,
        map: ThinkingLevelMap?,
    ): JsonObject? {
        fun resolve(value: ChatTemplateKwargValue): JsonElement? = when (value) {
            is ChatTemplateKwargValue.Scalar -> value.value
            is ChatTemplateKwargValue.Ref -> {
                if (effort == null && value.omitWhenOff) return@resolve null
                when (value.varName) {
                    "thinking.enabled" -> JsonPrimitive(effort != null)
                    "thinking.budget" -> null // thinking budgets unsupported here
                    "thinking.effort" -> {
                        if (effort != null) {
                            // Explicit null mapping omits; unspecified falls back to the level name.
                            val mapped = if (map?.isSpecified(effort) == true) {
                                map.forLevel(effort)
                            } else {
                                effort.name.lowercase()
                            }
                            mapped?.let { JsonPrimitive(it) }
                        } else {
                            // No effort: only an explicitly mapped OFF string is sent.
                            map?.takeIf { it.isSpecified(ModelThinkingLevel.OFF) }
                                ?.forLevel(ModelThinkingLevel.OFF)
                                ?.let { JsonPrimitive(it) as JsonElement }
                        }
                    }
                    else -> null
                }
            }
        }

        val resolved = compat.chatTemplateArgs.mapNotNull { (k, v) ->
            resolve(v)?.let { k to it }
        }
        return resolved.toMap().takeIf { it.isNotEmpty() }?.let { JsonObject(it) }
    }

    fun convertMessages(
        model: Model,
        context: Context,
        compat: OpenAiCompletionsCompat = model.compat,
    ): List<JsonObject> {
        val params = mutableListOf<JsonObject>()

        if (!context.systemPrompt.isNullOrEmpty()) {
            val role = if (model.reasoning && compat.supportsDeveloperRole) "developer" else "system"
            params.add(
                buildJsonObject {
                    put("role", role)
                    put("content", sanitizeSurrogates(context.systemPrompt))
                },
            )
        }

        val messages = context.messages
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            when (msg.role) {
                MessageRole.USER ->
                    convertUserMessage(msg as works.resolve.pathfinder.ai.core.UserMessage)
                        ?.let { params.add(it) }

                MessageRole.ASSISTANT -> convertAssistantMessage(msg as works.resolve.pathfinder.ai.core.AssistantMessage, compat)
                    ?.let { params.add(it) }

                MessageRole.TOOL_RESULT -> {
                    var j = i
                    val imageParts = mutableListOf<JsonElement>()
                    val supportsImage = model.input.contains(InputModality.IMAGE)
                    while (j < messages.size && messages[j].role == MessageRole.TOOL_RESULT) {
                        val toolMsg = messages[j] as works.resolve.pathfinder.ai.core.ToolResultMessage
                        val textResult = toolMsg.content
                            .filter { it.type == ContentType.TEXT }
                            .joinToString("\n") { (it as works.resolve.pathfinder.ai.core.TextContent).text }
                        val hasImages = toolMsg.content.any { it.type == ContentType.IMAGE }
                        val toolResultText = when {
                            textResult.isNotEmpty() -> textResult
                            hasImages -> "(see attached image)"
                            else -> "(no tool output)"
                        }
                        val toolMessage = mutableMapOf<String, JsonElement>(
                            "role" to JsonPrimitive("tool"),
                            "content" to JsonPrimitive(sanitizeSurrogates(toolResultText)),
                            "tool_call_id" to JsonPrimitive(toolMsg.toolCallId),
                        )
                        if (compat.requiresToolResultName && toolMsg.toolName.isNotEmpty()) {
                            toolMessage["name"] = JsonPrimitive(toolMsg.toolName)
                        }
                        params.add(JsonObject(toolMessage))
                        if (supportsImage) {
                            toolMsg.content
                                .filter { it.type == ContentType.IMAGE }
                                .map { it as works.resolve.pathfinder.ai.core.ImageContent }
                                .forEach { imageParts.add(imagePart(it)) }
                        }
                        j++
                    }
                    i = j - 1
                    if (imageParts.isNotEmpty()) {
                        params.add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(buildJsonObject { put("type", "text"); put("text", "Attached image(s) from tool result:") })
                                        imageParts.forEach { add(it) }
                                    },
                                )
                            },
                        )
                    }
                }
            }
            i++
        }
        return params
    }

    private fun convertUserMessage(msg: works.resolve.pathfinder.ai.core.UserMessage): JsonObject? {
        if (msg.content.isEmpty()) return null
        return buildJsonObject {
            put("role", "user")
            val text = sanitizeSurrogates(
                msg.content.filter { it.type == ContentType.TEXT }
                    .joinToString("") { (it as works.resolve.pathfinder.ai.core.TextContent).text },
            )
            val images = msg.content.filter { it.type == ContentType.IMAGE }
            if (images.isEmpty()) {
                put("content", text)
            } else {
                put(
                    "content",
                    buildJsonArray {
                        if (text.isNotEmpty()) {
                            add(buildJsonObject { put("type", "text"); put("text", text) })
                        }
                        images.forEach { add(imagePart(it as works.resolve.pathfinder.ai.core.ImageContent)) }
                    },
                )
            }
        }
    }

    private fun imagePart(image: works.resolve.pathfinder.ai.core.ImageContent): JsonObject =
        buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", "data:${image.mimeType};base64,${image.data}") })
        }

    /** Returns null for assistant messages with no content and no tool calls. */
    private fun convertAssistantMessage(
        msg: works.resolve.pathfinder.ai.core.AssistantMessage,
        compat: OpenAiCompletionsCompat,
    ): JsonObject? {
        val assistant = mutableMapOf<String, JsonElement>()

        // Keep only assistant text blocks with non-whitespace content.
        val text = sanitizeSurrogates(
            msg.content.filter { it.type == ContentType.TEXT }
                .map { (it as works.resolve.pathfinder.ai.core.TextContent).text }
                .filter { it.isNotBlank() }
                .joinToString(""),
        )

        val thinkingBlocks = msg.content.filter { it.type == ContentType.THINKING }
            .map { it as works.resolve.pathfinder.ai.core.ThinkingContent }
        val nonEmptyThinking = thinkingBlocks.filter { it.thinking.isNotBlank() }

        if (compat.requiresThinkingAsText && nonEmptyThinking.isNotEmpty()) {
            val thinkingText = sanitizeSurrogates(nonEmptyThinking.joinToString("\n\n") { it.thinking })
            val parts = buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", thinkingText) })
                if (text.isNotEmpty()) add(buildJsonObject { put("type", "text"); put("text", text) })
            }
            assistant["content"] = parts
        } else if (text.isNotEmpty()) {
            // Plain string content is the standard format; block arrays make
            // some models mirror the structure literally.
            assistant["content"] = JsonPrimitive(text)
        } else {
            // Some providers reject null content; absent content is only valid
            // alongside tool calls.
        }

        // Replay reasoning when the provider stored a wire-field signature.
        if (!compat.requiresThinkingAsText && nonEmptyThinking.isNotEmpty()) {
            val signature = nonEmptyThinking.first().thinkingSignature
            if (signature != null && signature in REASONING_FIELDS) {
                // Exact pi parity: the raw reasoning field is replayed unsanitized
                // (only requiresThinkingAsText output is sanitized).
                assistant[signature] = JsonPrimitive(nonEmptyThinking.joinToString("\n") { it.thinking })
            }
        }

        val toolCalls = msg.content.filter { it.type == ContentType.TOOL_CALL }
        if (toolCalls.isNotEmpty()) {
            assistant["tool_calls"] = JsonArray(
                toolCalls.map { call ->
                    call as works.resolve.pathfinder.ai.core.ToolCall
                    buildJsonObject {
                        put("id", call.id)
                        put("type", "function")
                        put(
                            "function",
                            buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.arguments)
                            },
                        )
                    }
                },
            )
        }

        val hasContent = (assistant["content"] as? JsonPrimitive)?.content?.isNotEmpty() == true ||
            assistant["content"] is JsonArray
        if (!hasContent && !assistant.containsKey("tool_calls")) {
            return null
        }
        return JsonObject(mapOf("role" to JsonPrimitive("assistant")) + assistant)
    }

    private fun convertTool(tool: Tool): JsonObject =
        buildJsonObject {
            put("type", "function")
            put(
                "function",
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameters)
                    // Explicit strict:false for plain tools on strict-capable
                    // providers (ZAI is strict-capable).
                    put("strict", false)
                },
            )
        }

    private fun hasToolHistory(messages: List<Message>): Boolean = messages.any { msg ->
        msg.role == MessageRole.TOOL_RESULT ||
            (msg as? works.resolve.pathfinder.ai.core.AssistantMessage)?.content?.any { it.type == ContentType.TOOL_CALL } == true
    }

    /**
     * Removes unpaired UTF-16 surrogates, which many providers reject during JSON
     * parsing. Valid surrogate pairs (emoji, astral text) are preserved. Mirrors pi's
     * sanitizeSurrogates.
     */
    internal fun sanitizeSurrogates(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isHighSurrogate() -> {
                    val next = if (i + 1 < text.length) text[i + 1] else ' '
                    if (next.isLowSurrogate()) {
                        sb.append(c).append(next)
                        i++
                    }
                    // else: drop unpaired high surrogate
                }
                c.isLowSurrogate() -> Unit // drop unpaired low surrogate
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
