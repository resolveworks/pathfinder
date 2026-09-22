package works.resolve.pathfinder.ai.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.ProviderStreamException
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.SystemMessage
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.TranscriptContext
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.calculateCost
import works.resolve.pathfinder.ai.utils.getSystemMessageText
import works.resolve.pathfinder.ai.utils.int
import works.resolve.pathfinder.ai.utils.lenientJson
import works.resolve.pathfinder.ai.utils.obj
import works.resolve.pathfinder.ai.utils.parseStreamingJson
import works.resolve.pathfinder.ai.utils.renderSystemMessageUpdate
import works.resolve.pathfinder.ai.utils.resolveTranscript
import works.resolve.pathfinder.ai.utils.resolveTranscriptTools
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import works.resolve.pathfinder.ai.utils.shortHash
import works.resolve.pathfinder.ai.utils.strictInt
import works.resolve.pathfinder.ai.utils.string
import works.resolve.pathfinder.ai.utils.stringOrNull

/**
 * Shared OpenAI Responses API machinery: message/tool conversion and stream
 * processing.
 *
 * Divergences from pi:
 * - The `openai` SDK's wire behavior is re-created by hand over Pathfinder's
 *   transport.
 * - Grammar constrained sampling maps grammar tools to OpenAI `custom` tools
 *   (`format: {type:"grammar", syntax, definition}`), replayed as
 *   `custom_tool_call` items and streamed through [GrammarToolInputJsonBuffer]
 *   input deltas.
 */
object OpenAiResponsesShared {

    val BASE_TOOL_CALL_PROVIDERS = setOf("openai", "openai-codex", "opencode")

    /** `{"v":1,"id":...,"phase":...?}`; a null id is omitted because
     * JSON.stringify drops undefined (malformed events). */
    fun encodeTextSignatureV1(id: String?, phase: String?): String = buildJsonObject {
        put("v", 1)
        if (id != null) put("id", id)
        if (phase != null) put("phase", phase)
    }.toString()

    fun parseTextSignature(signature: String?): Pair<String, String?>? {
        if (signature == null) return null
        if (signature.startsWith("{")) {
            try {
                val parsed = lenientJson.parseToJsonElement(signature) as? JsonObject
                if (parsed != null) {
                    val id = parsed.string("id")
                    if (parsed.strictInt("v") == 1 && id != null) {
                        val phase = parsed.string("phase")
                            ?.takeIf { it == "commentary" || it == "final_answer" }
                        return id to phase
                    }
                }
            } catch (_: Exception) {
            }
        }
        return signature to null
    }

    data class ConvertResponsesToolsOptions(
        /** Null sends `"strict": null` on the wire (pi assigns the resolved
         * value unconditionally when the model supports strict mode). */
        val strict: Boolean? = false,
        val supportsStrictMode: Boolean = true,
        val supportsOpenAIGrammarTools: Boolean = false,
        val toolSearchResult: Boolean = false
    )

    data class ConvertResponsesMessagesOptions(
        val includeSystemPrompt: Boolean = true,
        val grammarToolInputProperties: Map<String, String> = emptyMap(),
        /**
         * Whether later system messages are sent in place; otherwise they are folded into the
         * leading prompt.
         */
        val supportsMidConvoSystemMessages: Boolean = false,
        val supportsAdditionalTools: Boolean = false,
        val supportsToolSearch: Boolean = false,
        val toolOptions: ConvertResponsesToolsOptions = ConvertResponsesToolsOptions()
    )

    fun convertResponsesMessages(
        model: Model,
        context: TranscriptContext,
        allowedToolCallProviders: Set<String>,
        options: ConvertResponsesMessagesOptions = ConvertResponsesMessagesOptions()
    ): List<JsonObject> {
        val normalizedContext = resolveTranscript(context, options.supportsMidConvoSystemMessages)
        val messages = mutableListOf<JsonObject>()

        fun normalizeIdPart(part: String): String {
            val sanitized = part.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val normalized = if (sanitized.length > 64) sanitized.substring(0, 64) else sanitized
            return normalized.trimEnd('_')
        }

        fun buildForeignResponsesItemId(itemId: String): String {
            val normalized = "fc_${shortHash(itemId)}"
            return if (normalized.length > 64) normalized.substring(0, 64) else normalized
        }

        val normalizeToolCallId: (String, AssistantMessage) -> String = { id, source ->
            if (model.provider !in allowedToolCallProviders || !id.contains("|")) {
                normalizeIdPart(id)
            } else {
                // Destructuring mirrors pi's `const [callId, itemId] = id.split("|")`:
                // only the second segment is the item id, never the whole tail.
                val (callId, itemId) = id.split("|")
                val normalizedCallId = normalizeIdPart(callId)
                val isForeignToolCall = source.provider != model.provider || source.api != model.api
                var normalizedItemId =
                    if (isForeignToolCall) {
                        buildForeignResponsesItemId(
                            itemId
                        )
                    } else {
                        normalizeIdPart(itemId)
                    }
                // OpenAI Responses API requires item ids to start with "fc".
                if (!normalizedItemId.startsWith("fc_")) {
                    normalizedItemId = normalizeIdPart("fc_$normalizedItemId")
                }
                "$normalizedCallId|$normalizedItemId"
            }
        }

        val transformedMessages =
            transformMessages(normalizedContext.messages, model, normalizeToolCallId)
        val transcriptTools = resolveTranscriptTools(
            normalizedContext.messages,
            options.supportsAdditionalTools || options.supportsToolSearch
        )

        fun appendSystemToolAdditions(message: SystemMessage, seed: String) {
            val tools = if (transcriptTools.anchorsAdditions) {
                message.toolsAdded.orEmpty()
            } else {
                emptyList()
            }
            if (tools.isEmpty()) return
            if (options.supportsAdditionalTools) {
                messages.add(
                    buildJsonObject {
                        put("type", "additional_tools")
                        put("role", "developer")
                        put("tools", JsonArray(convertResponsesTools(tools, options.toolOptions)))
                    }
                )
                return
            }
            if (!options.supportsToolSearch) return
            val names = tools.map { it.name }
            val callId = "pi_tool_load_${shortHash("$seed:${names.joinToString(",")}")}"
            messages.add(
                buildJsonObject {
                    put("type", "tool_search_call")
                    put("call_id", callId)
                    put("execution", "client")
                    put("status", "completed")
                    put(
                        "arguments",
                        buildJsonObject {
                            put("query", names.joinToString(" "))
                            put("limit", names.size)
                        }
                    )
                }
            )
            messages.add(
                buildJsonObject {
                    put("type", "tool_search_output")
                    put("call_id", callId)
                    put("execution", "client")
                    put("status", "completed")
                    put(
                        "tools",
                        JsonArray(
                            convertResponsesTools(
                                tools,
                                options.toolOptions.copy(toolSearchResult = true)
                            )
                        )
                    )
                }
            )
        }

        val includeInitialSystemMessage = options.includeSystemPrompt
        val instructionRole =
            if (model.reasoning && model.responsesCompat?.supportsDeveloperRole != false) {
                "developer"
            } else {
                "system"
            }
        val initialSystemMessage = transformedMessages.firstOrNull() as? SystemMessage

        transformedMessages.forEachIndexed { index, msg ->
            val isLeadingSystemMessage = index == 0 && msg is SystemMessage
            // The leading system message is not counted as a conversation message.
            val msgIndex = if (initialSystemMessage != null) index - 1 else index
            when (msg) {
                is SystemMessage -> {
                    if (!isLeadingSystemMessage) appendSystemToolAdditions(msg, "system:$msgIndex")
                    if (!isLeadingSystemMessage || includeInitialSystemMessage) {
                        val text =
                            if (isLeadingSystemMessage) {
                                getSystemMessageText(msg)
                            } else {
                                renderSystemMessageUpdate(msg)
                            }
                        if (text.isNotEmpty()) {
                            messages.add(
                                buildJsonObject {
                                    put("role", instructionRole)
                                    put("content", sanitizeSurrogates(text))
                                }
                            )
                        }
                    }
                }

                is UserMessage -> {
                    val content = msg.content.mapNotNull { item ->
                        when (item) {
                            is TextContent -> buildJsonObject {
                                put("type", "input_text")
                                put("text", sanitizeSurrogates(item.text))
                            }

                            is ImageContent -> buildJsonObject {
                                put("type", "input_image")
                                put("detail", "auto")
                                put("image_url", "data:${item.mimeType};base64,${item.data}")
                            }

                            else -> null
                        }
                    }
                    if (content.isNotEmpty()) {
                        messages.add(
                            buildJsonObject {
                                put("role", "user")
                                put("content", JsonArray(content))
                            }
                        )
                    }
                }

                is AssistantMessage -> {
                    val output = mutableListOf<JsonObject>()
                    val isSameProviderAndApi =
                        msg.provider == model.provider && msg.api == model.api
                    val isSameModel = isSameProviderAndApi && msg.model == model.id
                    val isDifferentModel = isSameProviderAndApi && msg.model != model.id
                    var textBlockIndex = 0

                    for (block in msg.content) {
                        when (block) {
                            is ThinkingContent -> if (block.thinkingSignature != null) {
                                // The signature is the serialized reasoning item.
                                output.add(
                                    lenientJson.parseToJsonElement(
                                        block.thinkingSignature!!
                                    ) as JsonObject
                                )
                            }

                            is TextContent -> {
                                val parsedSignature = parseTextSignature(block.textSignature)
                                val fallbackMessageId =
                                    if (textBlockIndex == 0) {
                                        "msg_pi_$msgIndex"
                                    } else {
                                        "msg_pi_${msgIndex}_$textBlockIndex"
                                    }
                                textBlockIndex++
                                // OpenAI requires ids of at most 64 characters.
                                var msgId = parsedSignature?.first
                                if (msgId == null) {
                                    msgId = fallbackMessageId
                                } else if (msgId.length > 64) {
                                    msgId = "msg_${shortHash(msgId)}"
                                }
                                output.add(
                                    buildJsonObject {
                                        put("type", "message")
                                        put("role", "assistant")
                                        put(
                                            "content",
                                            JsonArray(
                                                listOf(
                                                    buildJsonObject {
                                                        put("type", "output_text")
                                                        put("text", sanitizeSurrogates(block.text))
                                                        put("annotations", JsonArray(emptyList()))
                                                    }
                                                )
                                            )
                                        )
                                        put("status", "completed")
                                        put("id", msgId)
                                        parsedSignature?.second?.let { put("phase", it) }
                                    }
                                )
                            }

                            is ToolCall -> {
                                // `getOrNull(1)` mirrors pi's destructured
                                // `split("|")`, where a missing segment is
                                // undefined rather than a string.
                                val idParts = block.id.split("|")
                                val callId = idParts[0]
                                var itemId: String? = idParts.getOrNull(1)
                                val customInputProperty =
                                    options.grammarToolInputProperties[block.name]
                                // For different-model messages drop fc_ ids to avoid
                                // pairing validation. When replaying a custom-tool call
                                // its ctc_* id is kept; only function_call replay needs
                                // fc_* item ids.
                                if ((isDifferentModel && itemId?.startsWith("fc_") == true) ||
                                    (
                                        customInputProperty == null &&
                                            itemId?.startsWith("fc_") != true
                                        )
                                ) {
                                    itemId = null
                                }
                                val canReplayNamespace = isSameModel
                                if (customInputProperty != null) {
                                    output.add(
                                        buildJsonObject {
                                            put("type", "custom_tool_call")
                                            itemId?.let { put("id", it) }
                                            put("call_id", callId)
                                            put("name", block.name)
                                            put(
                                                "input",
                                                sanitizeSurrogates(
                                                    getGrammarToolInput(
                                                        block.name,
                                                        block.arguments,
                                                        customInputProperty
                                                    )
                                                )
                                            )
                                            if (canReplayNamespace && block.namespace != null) {
                                                put("namespace", block.namespace)
                                            }
                                        }
                                    )
                                } else {
                                    output.add(
                                        buildJsonObject {
                                            put("type", "function_call")
                                            itemId?.let { put("id", it) }
                                            put("call_id", callId)
                                            put("name", block.name)
                                            put(
                                                "arguments",
                                                JsonPrimitive(block.arguments.toString())
                                            )
                                            if (canReplayNamespace && block.namespace != null) {
                                                put("namespace", block.namespace)
                                            }
                                        }
                                    )
                                }
                            }

                            else -> {}
                        }
                    }
                    messages.addAll(output)
                }

                is ToolResultMessage -> {
                    val callId = msg.toolCallId.substringBefore("|")
                    val converted = convertToolResultOutput(model, msg.content)
                    messages.add(
                        buildJsonObject {
                            put(
                                "type",
                                if (options.grammarToolInputProperties.containsKey(msg.toolName)) {
                                    "custom_tool_call_output"
                                } else {
                                    "function_call_output"
                                }
                            )
                            put("call_id", callId)
                            put("output", converted)
                        }
                    )
                }
            }
        }
        return messages
    }

    private fun convertToolResultOutput(model: Model, content: List<Content>): JsonElement {
        val texts = content.filterIsInstance<TextContent>().map { it.text }
        val images = content.filterIsInstance<ImageContent>()
        val textResult = texts.joinToString("\n")
        val hasText = textResult.isNotEmpty()

        if (images.isEmpty() || !model.input.contains(InputModality.IMAGE)) {
            val text = when {
                hasText -> textResult
                images.isNotEmpty() -> "(see attached image)"
                else -> "(no tool output)"
            }
            return JsonPrimitive(sanitizeSurrogates(text))
        }

        val output = mutableListOf<JsonElement>()
        if (hasText) {
            output.add(
                buildJsonObject {
                    put("type", "input_text")
                    put("text", sanitizeSurrogates(textResult))
                }
            )
        }
        for (image in images) {
            output.add(
                buildJsonObject {
                    put("type", "input_image")
                    put("detail", "auto")
                    put("image_url", "data:${image.mimeType};base64,${image.data}")
                }
            )
        }
        return JsonArray(output)
    }

    fun convertResponsesTools(
        tools: List<Tool>,
        options: ConvertResponsesToolsOptions = ConvertResponsesToolsOptions()
    ): List<JsonObject> = tools.map { tool ->
        val grammar = resolveGrammarConstrainedSampling(tool, options.supportsOpenAIGrammarTools)
        if (grammar != null) {
            buildJsonObject {
                put("type", "custom")
                put("name", tool.name)
                put("description", tool.description)
                put(
                    "format",
                    buildJsonObject {
                        put("type", "grammar")
                        put(
                            "syntax",
                            if (grammar.format ==
                                GrammarConstrainedFormat.LARK
                            ) {
                                "lark"
                            } else {
                                "regex"
                            }
                        )
                        put("definition", grammar.definition)
                    }
                )
                if (options.toolSearchResult) put("defer_loading", true)
            }
        } else {
            val constrainedStrict =
                resolveJsonSchemaStrictSampling(tool, options.supportsStrictMode)
            val strict = constrainedStrict ?: options.strict
            buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", getJsonSchemaToolParameters(tool, strict == true))
                if (options.toolSearchResult) put("defer_loading", true)
                if (options.supportsStrictMode) put("strict", strict)
            }
        }
    }

    data class StreamProcessingOptions(
        val serviceTier: String? = null,
        val grammarToolInputProperties: Map<String, String> = emptyMap(),
        val resolveServiceTier: ((responseTier: String?, requestTier: String?) -> String?)? = null,
        val applyServiceTierPricing: ((usage: Usage, serviceTier: String?) -> Usage)? = null
    )

    /**
     * Snapshots render immutable [Content] values, cached per block and
     * reused while unchanged (sharing instances across snapshots is safe);
     * a boundary snapshot re-renders only the blocks mutated since the
     * previous one. Deltas append into per-block builders and carry no
     * snapshot.
     */
    private sealed interface Block {
        val index: Int

        /** Last rendered content; null when stale. */
        var built: Content?

        class Thinking(override val index: Int) : Block {
            val thinking = StringBuilder()
            var thinkingSignature: String? = null
            override var built: Content? = null
        }

        class Text(override val index: Int) : Block {
            val text = StringBuilder()
            var textSignature: String? = null
            override var built: Content? = null
        }

        class Tool(override val index: Int) : Block {
            var id: String = ""
            var name: String = ""

            /** Parsed arguments; kept current with every streamed fragment. */
            var arguments: JsonObject = JsonObject(emptyMap())

            /** Scratch buffer of streamed fragments; null for custom tool calls. */
            var partialJson: String? = ""
            var namespace: String? = null
            var customInput: CustomToolInput? = null
            override var built: Content? = null
        }
    }

    /** Grammar input property and JSON buffer for `custom_tool_call` items. */
    private class CustomToolInput(
        val property: String,
        val jsonBuffer: GrammarToolInputJsonBuffer = GrammarToolInputJsonBuffer()
    )

    /** Last accepted custom-tool input, stored as the sole arguments property. */
    private fun getCustomToolCallInput(slot: Block.Tool): String {
        val customInput = slot.customInput ?: return ""
        val value = slot.arguments[customInput.property]
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: ""
    }

    private fun appendCustomToolCallInput(
        slot: Block.Tool,
        nextInput: String,
        close: Boolean
    ): String? {
        val customInput = slot.customInput ?: return null
        val delta =
            appendGrammarToolInputJsonDelta(
                customInput.jsonBuffer,
                customInput.property,
                nextInput,
                close
            )
        slot.arguments = buildJsonObject { put(customInput.property, nextInput) }
        return delta
    }

    /** Event-ordered stream state machine; adapters feed events to [onEvent]. */
    class ResponsesStreamState(
        private val model: Model,
        private val timestampMs: Long,
        private val options: StreamProcessingOptions = StreamProcessingOptions()
    ) {
        private val blocks = mutableListOf<Block>()
        private val slots = mutableMapOf<Int, Block>() // output_index -> live block
        private val reasoningBlocksById = mutableMapOf<String, Block.Thinking>()

        var responseId: String? = null
            private set
        var usage = Usage()
            private set
        var stopReason = StopReason.PENDING
            private set
        var errorMessage: String? = null
            private set
        var rawStopReason: String? = null
            private set
        var sawTerminalResponseEvent = false
            private set

        /** Snapshot exposed to the adapters' Start/Done/Error events. */
        internal fun partialSnapshot(): AssistantMessage = partial()

        private fun render(block: Block): Content = when (block) {
            is Block.Thinking -> ThinkingContent(block.thinking.toString(), block.thinkingSignature)

            is Block.Text -> TextContent(block.text.toString(), block.textSignature)

            is Block.Tool -> ToolCall(
                id = block.id,
                name = block.name,
                arguments = block.arguments,
                namespace = block.namespace
            )
        }

        private fun renderCached(block: Block): Content =
            block.built ?: render(block).also { block.built = it }

        private fun partial(): AssistantMessage = AssistantMessage(
            content = blocks.map(::renderCached),
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = usage,
            stopReason = stopReason,
            errorMessage = errorMessage,
            rawStopReason = rawStopReason,
            responseId = responseId,
            timestamp = timestampMs
        )

        fun onEvent(event: JsonObject): List<AssistantMessageEvent> = when (event.string("type")) {
            "response.created" -> {
                event.obj("response").string("id")?.let { responseId = it }
                emptyList()
            }

            "response.output_item.added" -> {
                val outputIndex = event.int("output_index") ?: return emptyList()
                val item = event.obj("item") ?: return emptyList()
                createSlot(outputIndex, item)
            }

            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                val slot = getSlot<Block.Thinking>(event) ?: return emptyList()
                val delta = event.string("delta") ?: return emptyList()
                slot.thinking.append(delta)
                slot.built = null
                listOf(AssistantMessageEvent.ThinkingDelta(slot.index, delta))
            }

            "response.reasoning_summary_part.done" -> {
                val slot = getSlot<Block.Thinking>(event) ?: return emptyList()
                slot.thinking.append("\n\n")
                slot.built = null
                listOf(AssistantMessageEvent.ThinkingDelta(slot.index, "\n\n"))
            }

            "response.output_text.delta", "response.refusal.delta" -> {
                val slot = getSlot<Block.Text>(event) ?: return emptyList()
                val delta = event.string("delta") ?: return emptyList()
                slot.text.append(delta)
                slot.built = null
                listOf(AssistantMessageEvent.TextDelta(slot.index, delta))
            }

            "response.function_call_arguments.delta" -> {
                val slot = getSlot<Block.Tool>(event) ?: return emptyList()
                val delta = event.string("delta") ?: return emptyList()
                val partialJson = slot.partialJson ?: return emptyList()
                val next = partialJson + delta
                slot.partialJson = next
                slot.arguments = parseStreamingJson(next)
                slot.built = null
                listOf(AssistantMessageEvent.ToolCallDelta(slot.index, delta))
            }

            "response.function_call_arguments.done" -> {
                // The complete arguments replace the buffer; any tail beyond the
                // streamed prefix is emitted as one final delta.
                val slot = getSlot<Block.Tool>(event) ?: return emptyList()
                val previous = slot.partialJson ?: return emptyList()
                val arguments = event.string("arguments") ?: return emptyList()
                slot.partialJson = arguments
                slot.arguments = parseStreamingJson(arguments)
                slot.built = null
                if (!arguments.startsWith(previous)) return emptyList()
                val delta = arguments.substring(previous.length)
                if (delta.isEmpty()) return emptyList()
                listOf(AssistantMessageEvent.ToolCallDelta(slot.index, delta))
            }

            "response.custom_tool_call_input.delta" -> {
                val slot = getSlot<Block.Tool>(event) ?: return emptyList()
                if (slot.customInput == null) return emptyList()
                val delta = event.string("delta") ?: return emptyList()
                val out =
                    appendCustomToolCallInput(slot, getCustomToolCallInput(slot) + delta, false)
                        ?: return emptyList()
                slot.built = null
                listOf(AssistantMessageEvent.ToolCallDelta(slot.index, out))
            }

            "response.custom_tool_call_input.done" -> {
                val slot = getSlot<Block.Tool>(event) ?: return emptyList()
                if (slot.customInput == null) return emptyList()
                val input = event.string("input") ?: return emptyList()
                val out = appendCustomToolCallInput(slot, input, true) ?: return emptyList()
                slot.built = null
                listOf(AssistantMessageEvent.ToolCallDelta(slot.index, out))
            }

            "response.output_item.done" -> onOutputItemDone(event)

            "response.completed", "response.incomplete" -> {
                finalizeResponse(event.obj("response"))
                emptyList()
            }

            "error" -> throw ProviderStreamException(
                "Error Code ${event.string("code")}: ${event.string("message")}"
                    .ifBlank { "Unknown error" }
            )

            "response.failed" -> {
                sawTerminalResponseEvent = true
                rawStopReason = event.obj("response").string("status")
                val error = event.obj("response")?.obj("error")
                val details = event.obj("response")?.obj("incomplete_details")
                    .string("reason")
                val message = when {
                    error != null -> "${error.string("code") ?: "unknown"}: " +
                        (error.string("message") ?: "no message")

                    details != null -> "incomplete: $details"

                    else -> "Unknown error (no error details in response)"
                }
                throw ProviderStreamException(message)
            }

            else -> emptyList()
        }

        private inline fun <reified T : Block> getSlot(event: JsonObject): T? =
            slots[event.int("output_index") ?: return null] as? T

        private fun createSlot(outputIndex: Int, item: JsonObject): List<AssistantMessageEvent> {
            val block: Block = when (item.string("type")) {
                "reasoning" -> Block.Thinking(blocks.size)

                "message" -> {
                    applyMessagePhaseStopReason(item)
                    Block.Text(blocks.size)
                }

                "function_call" -> Block.Tool(blocks.size).also { tool ->
                    tool.id = "${item.string("call_id")}|${item.string("id")}"
                    tool.name = item.string("name") ?: ""
                    tool.partialJson = item.string("arguments").orEmpty()
                    tool.namespace = item.string("namespace")
                }

                "custom_tool_call" -> {
                    val name = item.string("name") ?: ""
                    val inputProperty = options.grammarToolInputProperties[name] ?: "input"
                    val input = item.string("input") ?: ""
                    Block.Tool(blocks.size).also { tool ->
                        tool.id = "${item.string("call_id")}|${item.string("id")}"
                        tool.name = name
                        tool.arguments = buildJsonObject { put(inputProperty, input) }
                        tool.namespace = item.string("namespace")
                        tool.customInput = CustomToolInput(inputProperty)
                    }
                }

                else -> return emptyList()
            }
            blocks.add(block)
            slots[outputIndex] = block
            val start = when (block) {
                is Block.Thinking -> AssistantMessageEvent.ThinkingStart(block.index, partial())
                is Block.Text -> AssistantMessageEvent.TextStart(block.index, partial())
                is Block.Tool -> AssistantMessageEvent.ToolCallStart(block.index, partial())
            }
            return listOf(start)
        }

        private fun applyMessagePhaseStopReason(item: JsonObject) {
            if (item.string("type") == "message" && item.string("phase") == "final_answer") {
                stopReason = StopReason.STOP
            }
        }

        private fun onOutputItemDone(event: JsonObject): List<AssistantMessageEvent> {
            val item = event.obj("item") ?: return emptyList()
            val outputIndex = event.int("output_index") ?: return emptyList()
            applyMessagePhaseStopReason(item)
            // Azure and others may send output_item.done without a preceding
            // output_item.added for the same output_index.
            var events: List<AssistantMessageEvent> = emptyList()
            val slot = slots[outputIndex] ?: run {
                events = createSlot(outputIndex, item)
                slots[outputIndex]
            } ?: return emptyList()
            return when {
                item.string("type") == "reasoning" && slot is Block.Thinking -> {
                    val summaryText = (item["summary"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonObject).string("text") }
                        ?.joinToString("\n\n").orEmpty()
                    val contentText = (item["content"] as? JsonArray)
                        ?.mapNotNull { c -> (c as? JsonObject).string("text") }
                        ?.joinToString("\n\n").orEmpty()
                    val thinking = summaryText.ifEmpty {
                        contentText.ifEmpty { slot.thinking.toString() }
                    }
                    slot.thinking.clear()
                    slot.thinking.append(thinking)
                    slot.thinkingSignature = item.toString()
                    slot.built = null
                    item.string("id")?.let { reasoningBlocksById[it] = slot }
                    slots.remove(outputIndex)
                    return events +
                        AssistantMessageEvent.ThinkingEnd(slot.index, thinking, partial())
                }

                item.string("type") == "message" && slot is Block.Text -> {
                    val text = (item["content"] as? JsonArray)
                        ?.mapNotNull { c ->
                            val obj = c as? JsonObject ?: return@mapNotNull null
                            obj.string("text") ?: obj.string("refusal")
                        }
                        ?.joinToString("").orEmpty()
                    slot.text.clear()
                    slot.text.append(text)
                    slot.textSignature = encodeTextSignatureV1(
                        item.string("id"),
                        item.string("phase")
                    )
                    slot.built = null
                    slots.remove(outputIndex)
                    return events + AssistantMessageEvent.TextEnd(slot.index, text, partial())
                }

                item.string("type") == "function_call" && slot is Block.Tool &&
                    slot.customInput == null -> {
                    slot.arguments = parseStreamingJson(
                        item.string("arguments")?.takeIf { it.isNotEmpty() }
                            ?: slot.partialJson?.takeIf { it.isNotEmpty() }
                            ?: "{}"
                    )
                    item.string("namespace")?.let { slot.namespace = it }
                    // The scratch buffer is stripped so replay only carries
                    // parsed arguments.
                    slot.partialJson = null
                    slot.built = null
                    slots.remove(outputIndex)
                    return events + listOf(
                        AssistantMessageEvent.ToolCallEnd(
                            slot.index,
                            render(slot) as ToolCall,
                            partial()
                        )
                    )
                }

                item.string("type") == "custom_tool_call" && slot is Block.Tool &&
                    slot.customInput != null -> {
                    val input = item.string("input") ?: getCustomToolCallInput(slot)
                    appendCustomToolCallInput(slot, input, true)?.let {
                        events += AssistantMessageEvent.ToolCallDelta(slot.index, it)
                    }
                    item.string("namespace")?.let { slot.namespace = it }
                    slot.customInput = null
                    slot.built = null
                    slots.remove(outputIndex)
                    return events + listOf(
                        AssistantMessageEvent.ToolCallEnd(
                            slot.index,
                            render(slot) as ToolCall,
                            partial()
                        )
                    )
                }

                else -> emptyList()
            }
        }

        /**
         * Azure can omit reasoning.encrypted_content from output_item.done and
         * provide it only in the terminal response's output; backfill the
         * persisted signature so store:false replay stays stateless.
         */
        private fun backfillReasoningSignatures(responseOutput: JsonArray?) {
            for (element in responseOutput ?: return) {
                val item = element as? JsonObject ?: continue
                if (item.string("type") != "reasoning") continue
                val encrypted = item.string("encrypted_content") ?: continue
                val block = item.string("id")?.let { reasoningBlocksById[it] } ?: continue
                val stored = block.thinkingSignature ?: continue
                val storedItem = try {
                    lenientJson.parseToJsonElement(stored) as? JsonObject
                } catch (_: Exception) {
                    null
                } ?: continue
                if (storedItem["encrypted_content"] != null) continue
                block.thinkingSignature = buildJsonObject {
                    storedItem.forEach { (k, v) -> put(k, v) }
                    put("encrypted_content", encrypted)
                }.toString()
                block.built = null
            }
        }

        private fun finalizeResponse(response: JsonObject?) {
            sawTerminalResponseEvent = true
            backfillReasoningSignatures(response?.get("output") as? JsonArray)
            response.string("id")?.let { responseId = it }
            response?.obj("usage")?.let { rawUsage ->
                val inputDetails = rawUsage.obj("input_tokens_details")
                val cachedTokens = inputDetails?.int("cached_tokens") ?: 0
                val cacheWriteTokens = inputDetails?.int("cache_write_tokens") ?: 0
                val input = maxOf(
                    0,
                    (rawUsage.int("input_tokens") ?: 0) - cachedTokens - cacheWriteTokens
                )
                // OpenAI includes cached and cache-write tokens in input_tokens.
                var computed = Usage(
                    input = input,
                    output = rawUsage.int("output_tokens") ?: 0,
                    cacheRead = cachedTokens,
                    cacheWrite = cacheWriteTokens,
                    reasoning = rawUsage.obj("output_tokens_details")?.int("reasoning_tokens") ?: 0,
                    totalTokens = rawUsage.int("total_tokens") ?: 0
                )
                computed = computed.copy(cost = calculateCost(model, computed))
                options.applyServiceTierPricing?.let { apply ->
                    val serviceTier = options.resolveServiceTier?.invoke(
                        response.string("service_tier"),
                        options.serviceTier
                    ) ?: (response.string("service_tier") ?: options.serviceTier)
                    computed = apply(computed, serviceTier)
                }
                usage = computed
            }
            // The incomplete reason stays in rawStopReason so truncation and
            // content filtering remain distinct.
            val status = response.string("status")
            val incompleteReason = (
                response?.get(
                    "incomplete_details"
                ) as? JsonObject
                ).string("reason")
            rawStopReason = if (incompleteReason != null) "$status.$incompleteReason" else status
            val mapped = mapStopReason(status, incompleteReason)
            stopReason = mapped.first
            errorMessage = mapped.second
            if (blocks.any { it is Block.Tool } && stopReason == StopReason.STOP) {
                stopReason = StopReason.TOOL_USE
            }
        }

        /** The stream must end with a terminal response event. */
        fun assertTerminalEvent() {
            if (!sawTerminalResponseEvent) {
                throw ProviderStreamException(
                    "OpenAI Responses stream ended before a terminal response event"
                )
            }
        }
    }

    fun mapStopReason(status: String?, incompleteReason: String?): Pair<StopReason, String?> =
        when (status) {
            null -> StopReason.STOP to null

            "completed" -> StopReason.STOP to null

            "incomplete" ->
                if (incompleteReason == "max_output_tokens") {
                    StopReason.LENGTH to null
                } else {
                    StopReason.ERROR to (
                        incompleteReason?.let { "Response incomplete: $it" }
                            ?: "Response incomplete without a provider reason"
                        )
                }

            "failed", "cancelled" -> StopReason.ERROR to null

            // These two are wonky upstream too; treated as stop.
            "in_progress", "queued" -> StopReason.STOP to null

            else -> throw ProviderStreamException("Unhandled stop reason: $status")
        }

    fun resolveReasoningEffort(
        model: Model,
        requested: ModelThinkingLevel?,
        defaultEffort: String
    ): String = requested?.let { level ->
        model.thinkingLevelMap?.takeIf { it.isSpecified(level) }?.forLevel(level)
            ?: level.name.lowercase()
    } ?: defaultEffort
}
