package works.resolve.aletheia.ai.api

import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.ContentType
import works.resolve.aletheia.ai.core.InputModality
import works.resolve.aletheia.ai.core.MaxTokensField
import works.resolve.aletheia.ai.core.Message
import works.resolve.aletheia.ai.core.MessageRole
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.ModelThinkingLevel
import works.resolve.aletheia.ai.core.OpenAiCompletionsCompat
import works.resolve.aletheia.ai.core.OpenAiCompletionsOptions
import works.resolve.aletheia.ai.core.ThinkingFormat
import works.resolve.aletheia.ai.core.Tool
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

        applyThinking(model, options, compat)?.let { (thinking, effort) ->
            body.putAll(thinking)
            if (effort != null) {
                body["reasoning_effort"] = JsonPrimitive(effort)
            }
        }

        return JsonObject(body)
    }

    /** Returns extra params (e.g. `thinking`) and the resolved reasoning_effort. */
    private fun applyThinking(
        model: Model,
        options: OpenAiCompletionsOptions,
        compat: OpenAiCompletionsCompat,
    ): Pair<Map<String, JsonElement>, String?>? {
        if (!model.reasoning) return null
        // Direct OFF never enables reasoning; it is equivalent to no effort.
        val effort = options.reasoningEffort?.takeIf { it != ModelThinkingLevel.OFF }

        /** pi semantics: unspecified passes the level through, explicit null omits the field. */
        fun mappedEffort(level: ModelThinkingLevel): String? {
            val map = model.thinkingLevelMap ?: return level.name.lowercase()
            return if (map.isSpecified(level)) map.forLevel(level) else level.name.lowercase()
        }

        if (compat.thinkingFormat == ThinkingFormat.ZAI) {
            val thinking = if (effort != null) {
                buildJsonObject {
                    put("type", "enabled")
                    put("clear_thinking", false)
                }
            } else {
                buildJsonObject { put("type", "disabled") }
            }
            if (effort == null || !compat.supportsReasoningEffort) {
                return mapOf("thinking" to thinking) to null
            }
            return mapOf("thinking" to thinking) to mappedEffort(effort)
        }

        // OpenAI-style reasoning_effort.
        if (compat.supportsReasoningEffort) {
            if (effort != null) {
                return emptyMap<String, JsonElement>() to mappedEffort(effort)
            }
            // Only an explicitly mapped non-null off value is sent.
            val map = model.thinkingLevelMap
            val off = if (map?.isSpecified(ModelThinkingLevel.OFF) == true) {
                map.forLevel(ModelThinkingLevel.OFF)
            } else {
                null
            }
            if (off != null) {
                return emptyMap<String, JsonElement>() to off
            }
        }
        return null
    }

    fun convertMessages(
        model: Model,
        context: Context,
        compat: OpenAiCompletionsCompat = model.compat,
    ): List<JsonObject> {
        val params = mutableListOf<JsonObject>()

        if (context.systemPrompt != null) {
            val role = if (model.reasoning && compat.supportsDeveloperRole) "developer" else "system"
            params.add(buildJsonObject { put("role", role); put("content", context.systemPrompt) })
        }

        val messages = context.messages
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            when (msg.role) {
                MessageRole.USER -> params.add(convertUserMessage(msg as works.resolve.aletheia.ai.core.UserMessage))

                MessageRole.ASSISTANT -> convertAssistantMessage(msg as works.resolve.aletheia.ai.core.AssistantMessage, compat)
                    ?.let { params.add(it) }

                MessageRole.TOOL_RESULT -> {
                    var j = i
                    val imageParts = mutableListOf<JsonElement>()
                    val supportsImage = model.input.contains(InputModality.IMAGE)
                    while (j < messages.size && messages[j].role == MessageRole.TOOL_RESULT) {
                        val toolMsg = messages[j] as works.resolve.aletheia.ai.core.ToolResultMessage
                        val textResult = toolMsg.content
                            .filter { it.type == ContentType.TEXT }
                            .joinToString("\n") { (it as works.resolve.aletheia.ai.core.TextContent).text }
                        val hasImages = toolMsg.content.any { it.type == ContentType.IMAGE }
                        val toolResultText = when {
                            textResult.isNotEmpty() -> textResult
                            hasImages -> "(see attached image)"
                            else -> "(no tool output)"
                        }
                        val toolMessage = mutableMapOf<String, JsonElement>(
                            "role" to JsonPrimitive("tool"),
                            "content" to JsonPrimitive(toolResultText),
                            "tool_call_id" to JsonPrimitive(toolMsg.toolCallId),
                        )
                        if (compat.requiresToolResultName && toolMsg.toolName.isNotEmpty()) {
                            toolMessage["name"] = JsonPrimitive(toolMsg.toolName)
                        }
                        params.add(JsonObject(toolMessage))
                        if (supportsImage) {
                            toolMsg.content
                                .filter { it.type == ContentType.IMAGE }
                                .map { it as works.resolve.aletheia.ai.core.ImageContent }
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

    private fun convertUserMessage(msg: works.resolve.aletheia.ai.core.UserMessage): JsonObject =
        buildJsonObject {
            put("role", "user")
            val text = msg.content.filter { it.type == ContentType.TEXT }
                .joinToString("") { (it as works.resolve.aletheia.ai.core.TextContent).text }
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
                        images.forEach { add(imagePart(it as works.resolve.aletheia.ai.core.ImageContent)) }
                    },
                )
            }
        }

    private fun imagePart(image: works.resolve.aletheia.ai.core.ImageContent): JsonObject =
        buildJsonObject {
            put("type", "image_url")
            put("image_url", buildJsonObject { put("url", "data:${image.mimeType};base64,${image.data}") })
        }

    /** Returns null for assistant messages with no content and no tool calls. */
    private fun convertAssistantMessage(
        msg: works.resolve.aletheia.ai.core.AssistantMessage,
        compat: OpenAiCompletionsCompat,
    ): JsonObject? {
        val assistant = mutableMapOf<String, JsonElement>()

        val text = msg.content.filter { it.type == ContentType.TEXT }
            .joinToString("") { (it as works.resolve.aletheia.ai.core.TextContent).text }

        val thinkingBlocks = msg.content.filter { it.type == ContentType.THINKING }
            .map { it as works.resolve.aletheia.ai.core.ThinkingContent }
        val nonEmptyThinking = thinkingBlocks.filter { it.thinking.isNotBlank() }

        if (compat.requiresThinkingAsText && nonEmptyThinking.isNotEmpty()) {
            val thinkingText = nonEmptyThinking.joinToString("\n\n") { it.thinking }
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
                assistant[signature] = JsonPrimitive(nonEmptyThinking.joinToString("\n") { it.thinking })
            }
        }

        val toolCalls = msg.content.filter { it.type == ContentType.TOOL_CALL }
        if (toolCalls.isNotEmpty()) {
            assistant["tool_calls"] = JsonArray(
                toolCalls.map { call ->
                    call as works.resolve.aletheia.ai.core.ToolCall
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
                },
            )
        }

    private fun hasToolHistory(messages: List<Message>): Boolean = messages.any { msg ->
        msg.role == MessageRole.TOOL_RESULT ||
            (msg as? works.resolve.aletheia.ai.core.AssistantMessage)?.content?.any { it.type == ContentType.TOOL_CALL } == true
    }
}
