package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.CacheControlFormat
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.ChatTemplateKwargValue
import works.resolve.pathfinder.ai.core.ContentType
import works.resolve.pathfinder.ai.core.DeferredToolsMode
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
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import works.resolve.pathfinder.ai.utils.shortHash
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.strOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

object OpenAiCompletionsPayload {

    /** Reasoning delta fields some OpenAI-compatible servers use, in preference order. */
    val REASONING_FIELDS = listOf("reasoning_content", "reasoning", "reasoning_text")

    fun buildRequestBody(
        model: Model,
        context: Context,
        options: OpenAiCompletionsOptions,
        compat: OpenAiCompletionsCompat = model.compat,
        cacheRetention: CacheRetention = OpenAiResponsesShared.resolveCacheRetention(
            options.cacheRetention,
            options.env,
        ),
    ): JsonObject {
        val body = mutableMapOf<String, JsonElement>()
        body["model"] = JsonPrimitive(model.id)
        val messages = convertMessages(model, context, compat).toMutableList()
        val cacheControl = getCompatCacheControl(compat, cacheRetention)
        body["stream"] = JsonPrimitive(true)

        val sendPromptCacheKey =
            (model.baseUrl.contains("api.openai.com") && cacheRetention != CacheRetention.NONE) ||
                (cacheRetention == CacheRetention.LONG && compat.supportsLongCacheRetention)
        if (sendPromptCacheKey) {
            OpenAiResponsesShared.clampOpenAIPromptCacheKey(options.sessionId)?.let {
                body["prompt_cache_key"] = JsonPrimitive(it)
            }
        }
        if (cacheRetention == CacheRetention.LONG && compat.supportsLongCacheRetention) {
            body["prompt_cache_retention"] = JsonPrimitive("24h")
        }

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

        val tools: MutableList<JsonObject>? = if (context.tools.isNotEmpty()) {
            // deferredToolsMode "kimi": tools already loaded via the bare-tools
            // system message are excluded from the standard tools param.
            val deferredToolNames =
                if (compat.deferredToolsMode == DeferredToolsMode.KIMI) getDeferredToolNames(context.messages) else emptySet()
            val activeTools = context.tools.filter { it.name !in deferredToolNames }
            if (activeTools.isNotEmpty()) {
                activeTools.map { convertTool(it, compat) }.toMutableList().also {
                    if (compat.zaiToolStream) {
                        body["tool_stream"] = JsonPrimitive(true)
                    }
                }
            } else if (hasToolHistory(context.messages)) {
                // Some proxies require the tools param when history has tool calls.
                mutableListOf()
            } else {
                null
            }
        } else if (hasToolHistory(context.messages)) {
            // Some proxies require the tools param when history has tool calls.
            mutableListOf()
        } else {
            null
        }
        if (cacheControl != null) {
            applyAnthropicCacheControl(messages, tools, cacheControl)
        }
        body["messages"] = JsonArray(messages.toList())
        tools?.let { body["tools"] = JsonArray(it.toList()) }

        mapToolChoice(options.toolChoice)?.let { body["tool_choice"] = it }

        applyThinking(model, options, compat)?.let { body.putAll(it) }

        options.samplingParams?.let { body.putAll(it) }

        return JsonObject(body)
    }

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

        /** True only for an explicit null OFF entry in the level map. */
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
                // Requires an explicitly mapped string; no level-name fallback.
                val mapped = map?.takeIf { it.isSpecified(effort) }?.forLevel(effort)
                return mapped?.let { mapOf("reasoning" to buildJsonObject { put("effort", it) }) }
            }

            ThinkingFormat.BASETEN -> {
                val params = mutableListOf<Pair<String, JsonElement>>()
                buildChatTemplateValues(compat, effort, map)?.let {
                    params.add("chat_template_args" to it)
                }
                if (compat.supportsReasoningEffort) {
                    // With null effort, only the mapped OFF entry is sent; no fallback.
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

    internal data class OpenAiCompatCacheControl(val type: String, val ttl: String?) {
        fun toJson(): JsonObject = buildJsonObject {
            put("type", type)
            ttl?.let { put("ttl", it) }
        }
    }

    internal fun getCompatCacheControl(
        compat: OpenAiCompletionsCompat,
        cacheRetention: CacheRetention,
    ): OpenAiCompatCacheControl? {
        if (compat.cacheControlFormat != CacheControlFormat.ANTHROPIC || cacheRetention == CacheRetention.NONE) {
            return null
        }
        val ttl = if (cacheRetention == CacheRetention.LONG && compat.supportsLongCacheRetention) "1h" else null
        return OpenAiCompatCacheControl(type = "ephemeral", ttl = ttl)
    }

    /**
     * pi mutates the converted payload objects in place; the immutable JSON
     * values are rebuilt here instead.
     */
    internal fun applyAnthropicCacheControl(
        messages: MutableList<JsonObject>,
        tools: MutableList<JsonObject>?,
        cacheControl: OpenAiCompatCacheControl,
    ) {
        addCacheControlToSystemPrompt(messages, cacheControl)
        addCacheControlToLastTool(tools, cacheControl)
        addCacheControlToLastConversationMessage(messages, cacheControl)
    }

    /** Only the first system/developer message is considered; no fallback when it has no markable text. */
    private fun addCacheControlToSystemPrompt(
        messages: MutableList<JsonObject>,
        cacheControl: OpenAiCompatCacheControl,
    ) {
        val index = messages.indexOfFirst { message ->
            message.str("role") == "system" || message.str("role") == "developer"
        }
        if (index >= 0) {
            addCacheControlToTextContent(messages, index, cacheControl)
        }
    }

    private fun addCacheControlToLastConversationMessage(
        messages: MutableList<JsonObject>,
        cacheControl: OpenAiCompatCacheControl,
    ) {
        for (i in messages.indices.reversed()) {
            val role = messages[i].str("role")
            if (role == "user" || role == "assistant" || role == "tool") {
                if (addCacheControlToTextContent(messages, i, cacheControl)) {
                    return
                }
            }
        }
    }

    private fun addCacheControlToLastTool(tools: MutableList<JsonObject>?, cacheControl: OpenAiCompatCacheControl) {
        if (tools.isNullOrEmpty()) return
        tools[tools.size - 1] = JsonObject(tools.last() + ("cache_control" to cacheControl.toJson()))
    }

    /**
     * Anthropic cache_control attaches to text content parts, not messages:
     * string content is rewritten to a single text part, array content is
     * marked on its last text part, and empty/absent text is not markable.
     */
    private fun addCacheControlToTextContent(
        messages: MutableList<JsonObject>,
        index: Int,
        cacheControl: OpenAiCompatCacheControl,
    ): Boolean {
        val message = messages[index]
        return when (val content = message["content"] ?: return false) {
            is JsonPrimitive -> {
                val text = content.contentOrNull
                if (text == null || text.isEmpty()) return false
                messages[index] = JsonObject(
                    message + (
                        "content" to JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                    put("cache_control", cacheControl.toJson())
                                },
                            ),
                        )
                        ),
                )
                true
            }

            is JsonArray -> {
                for (j in content.indices.reversed()) {
                    val part = content[j]
                    if (part is JsonObject && part.str("type") == "text") {
                        val newContent = content.toMutableList().also {
                            it[j] = JsonObject(part + ("cache_control" to cacheControl.toJson()))
                        }
                        messages[index] = JsonObject(message + ("content" to JsonArray(newContent)))
                        return true
                    }
                }
                false
            }

            else -> false
        }
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

        val messages = transformMessages(context.messages, model) { id, _ -> normalizeToolCallId(id, model.provider) }
        val deferredToolNames = mutableSetOf<String>()
        var i = 0
        while (i < messages.size) {
            val msg = messages[i]
            when (msg.role) {
                MessageRole.USER ->
                    convertUserMessage(msg as works.resolve.pathfinder.ai.core.UserMessage)
                        ?.let { params.add(it) }

                MessageRole.ASSISTANT ->
                    convertAssistantMessage(model, msg as works.resolve.pathfinder.ai.core.AssistantMessage, compat)
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
                        // deferredToolsMode "kimi": tool results mark the
                        // tools they loaded; those are re-announced as a bare
                        // `tools` system message after the group.
                        if (compat.deferredToolsMode == DeferredToolsMode.KIMI) {
                            deferredToolNames.addAll(toolMsg.addedToolNames)
                        }
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
                    if (deferredToolNames.isNotEmpty()) {
                        // Kimi accepts a system message with a bare `tools`
                        // array and no content.
                        val deferredTools = getToolsByName(context.tools, deferredToolNames)
                        if (deferredTools.isNotEmpty()) {
                            params.add(
                                buildJsonObject {
                                    put("role", "system")
                                    put("tools", JsonArray(deferredTools.map { convertTool(it, compat) }))
                                },
                            )
                        }
                    }
                }
            }
            i++
        }
        return params
    }

    /**
     * Splits pipe-separated ids coming from Responses-style providers
     * (`{call_id}|{item_id}`), where item ids can be 400+ chars of special
     * chars, and recombines them as `{callId}_{itemId}` so multiple tool
     * calls sharing a call_id stay unique. Results longer than 40 chars (the
     * OpenAI limit) are truncated with a hash suffix. Plain ids are truncated
     * to 40 chars only for provider "openai".
     */
private fun normalizeToolCallId(id: String, provider: String): String {
    if ("|" in id) {
        val separatorIndex = id.indexOf("|")
        val callId = id.substring(0, separatorIndex).replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val itemId = id.substring(separatorIndex + 1).replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val combinedId = if (itemId.isNotEmpty()) "${callId}_${itemId}" else callId
        if (combinedId.length <= 40) return combinedId
        val hash = shortHash(id).take(8)
        val prefix = callId.take(maxOf(1, 40 - hash.length - 1))
        return "${prefix}_${hash}"
    }

    if (provider == "openai") return if (id.length > 40) id.take(40) else id
    return id
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
        model: Model,
        msg: works.resolve.pathfinder.ai.core.AssistantMessage,
        compat: OpenAiCompletionsCompat,
    ): JsonObject? {
        val assistant = mutableMapOf<String, JsonElement>()

        val text = sanitizeSurrogates(
            msg.content.filter { it.type == ContentType.TEXT }
                .map { (it as works.resolve.pathfinder.ai.core.TextContent).text }
                .filter { it.isNotBlank() }
                .joinToString(""),
        )

        val thinkingBlocks = msg.content.filter { it.type == ContentType.THINKING }
            .map { it as works.resolve.pathfinder.ai.core.ThinkingContent }
        val nonEmptyThinking = thinkingBlocks.filter { it.thinking.isNotBlank() }
        val toolCalls = msg.content.filter { it.type == ContentType.TOOL_CALL }
            .map { it as works.resolve.pathfinder.ai.core.ToolCall }

        val signedReasoningDetails = thinkingBlocks.firstNotNullOfOrNull {
            parseOpenAIReasoningDetails(it.thinkingSignature)
        }
        val legacyReasoningDetails = toolCalls.mapNotNull {
            parseLegacyEncryptedReasoningDetail(it.thoughtSignature)
        }
        val preservedReasoningDetails: JsonElement? =
            signedReasoningDetails
                ?: legacyReasoningDetails.takeIf { it.isNotEmpty() }?.let { JsonArray(it) }

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

        // The raw reasoning field is replayed only when no structured
        // reasoning_details were preserved.
        if (preservedReasoningDetails == null &&
            !compat.requiresThinkingAsText && nonEmptyThinking.isNotEmpty()
        ) {
            // opencode-go accepts "reasoning_content", not the stored
            // "reasoning" signature field.
            var signature = nonEmptyThinking.first().thinkingSignature
            if (model.provider == "opencode-go" && signature == "reasoning") {
                signature = "reasoning_content"
            }
            if (signature != null && signature in REASONING_FIELDS) {
                // Replayed unsanitized, for exact parity with pi; only
                // requiresThinkingAsText output is sanitized.
                assistant[signature] = JsonPrimitive(nonEmptyThinking.joinToString("\n") { it.thinking })
            }
        }

        if (toolCalls.isNotEmpty()) {
            assistant["tool_calls"] = JsonArray(
                toolCalls.map { call ->
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

        preservedReasoningDetails?.let { assistant["reasoning_details"] = it }

        // DeepSeek-style endpoints reject replayed assistant messages without
        // reasoning_content when the model reasons; send an empty string.
        if (compat.requiresReasoningContentOnAssistantMessages &&
            model.reasoning &&
            !assistant.containsKey("reasoning_content")
        ) {
            assistant["reasoning_content"] = JsonPrimitive("")
        }

        // Content here is always either a primitive string we set above or a
        // block array, never JSON null, so the lenient read is equivalent.
        val hasContent = assistant["content"].strOrNull()?.isNotEmpty() == true ||
            assistant["content"] is JsonArray
        if (!hasContent && !assistant.containsKey("tool_calls")) {
            return null
        }
        return JsonObject(mapOf("role" to JsonPrimitive("assistant")) + assistant)
    }

    /**
     * pi's supportsStrictMode is tri-state (`!== false`, undefined means
     * supported); [OpenAiCompletionsCompat.supportsStrictMode] is a non-null
     * Boolean defaulting to true, so it is passed through directly.
     */
    private fun convertTool(tool: Tool, compat: OpenAiCompletionsCompat): JsonObject {
        val strict = resolveJsonSchemaStrictSampling(tool, compat.supportsStrictMode)
        return buildJsonObject {
            put("type", "function")
            put(
                "function",
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", getJsonSchemaToolParameters(tool, strict))
                    // Some providers reject unknown fields.
                    if (compat.supportsStrictMode) put("strict", strict ?: false)
                },
            )
        }
    }

    private fun getDeferredToolNames(messages: List<Message>): Set<String> =
        messages.flatMap { (it as? works.resolve.pathfinder.ai.core.ToolResultMessage)?.addedToolNames.orEmpty() }
            .toSet()

    private fun getToolsByName(tools: List<Tool>, names: Collection<String>): List<Tool> {
        val byName = tools.associateBy { it.name }
        return names.mapNotNull { byName[it] }
    }

    private fun hasToolHistory(messages: List<Message>): Boolean = messages.any { msg ->
        msg.role == MessageRole.TOOL_RESULT ||
            (msg as? works.resolve.pathfinder.ai.core.AssistantMessage)?.content?.any { it.type == ContentType.TOOL_CALL } == true
    }
}
