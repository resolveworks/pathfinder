package works.resolve.aletheia.ai.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.AssistantMessageEvent
import works.resolve.aletheia.ai.core.CacheRetention
import works.resolve.aletheia.ai.core.Content
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.Cost
import works.resolve.aletheia.ai.core.ImageContent
import works.resolve.aletheia.ai.core.InputModality
import works.resolve.aletheia.ai.core.Message
import works.resolve.aletheia.ai.core.MessageRole
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.ModelThinkingLevel
import works.resolve.aletheia.ai.core.OpenAiResponsesCompat
import works.resolve.aletheia.ai.core.SessionAffinityFormat
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.ThinkingContent
import works.resolve.aletheia.ai.core.Tool
import works.resolve.aletheia.ai.core.ToolCall
import works.resolve.aletheia.ai.core.ToolResultMessage
import works.resolve.aletheia.ai.core.Usage
import works.resolve.aletheia.ai.core.UserMessage
import works.resolve.aletheia.ai.core.calculateCost
import works.resolve.aletheia.ai.core.hasHeader
import works.resolve.aletheia.ai.core.mergeHeaders

/**
 * Shared OpenAI Responses API machinery, ported from pi's
 * openai-responses-shared.ts (message/tool conversion, stream processing) and
 * the small utils it depends on (shortHash, sanitizeSurrogates,
 * openai-prompt-cache, transform-messages, deferred-tools).
 *
 * Divergences from pi (narrowest-boundary adaptations, documented per symbol):
 * - No external SDKs or new dependencies: the `openai` SDK's wire behavior
 *   is re-created by hand over Aletheia's transport.
 * - [ToolCall.arguments] stays Aletheia's raw JSON string rather than a parsed
 *   object; replay passes the string through and streaming accumulates raw
 *   deltas, so pi's parseStreamingJson partial parser is not needed here.
 * - Grammar constrained sampling (custom tools) is not ported: Aletheia's
 *   [Tool] has no constrainedSampling config, so grammar tool paths
 *   (custom_tool_call items, grammar input buffers, and the catalog's
 *   supportsOpenAIGrammarTools flag) are omitted; grammar tools replay as
 *   plain function calls.
 * - samplingParams / onPayload / onResponse request hooks are not ported.
 * - GitHub Copilot dynamic headers (buildCopilotDynamicHeaders) are not
 *   ported; only static model headers and the affinity headers are sent.
 */
object OpenAiResponsesShared {

    /** OpenAI Responses rejects max_output_tokens below 16 (pi issue #6265). */
    const val OPENAI_RESPONSES_MIN_OUTPUT_TOKENS = 16

    const val OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH = 64

    /** pi's OPENAI_TOOL_CALL_PROVIDERS / CODEX_TOOL_CALL_PROVIDERS. */
    val BASE_TOOL_CALL_PROVIDERS = setOf("openai", "openai-codex", "opencode")

    /** pi's AZURE_TOOL_CALL_PROVIDERS. */
    val AZURE_TOOL_CALL_PROVIDERS = BASE_TOOL_CALL_PROVIDERS + "azure-openai-responses"

    private val json = Json { ignoreUnknownKeys = true }

    // =========================================================================
    // Utilities
    // =========================================================================

    /** Fast deterministic hash to shorten long strings; faithful port of pi's shortHash (utils/hash.ts). */
    fun shortHash(str: String): String {
        var h1 = 0xdeadbeef.toInt()
        var h2 = 0x41c6ce57.toInt()
        for (ch in str) {
            val c = ch.code
            // Math.imul-equivalent constants: 2654435761=0x9E3779B1,
            // 1597334677=0x5F356495, 2246822507=0x85EBCA6B, 3266489909=0xC2B2AE35.
            h1 = ((h1 xor c) * 0x9E3779B1.toInt()).toInt()
            h2 = ((h2 xor c) * 0x5F356495.toInt()).toInt()
        }
        h1 = (h1 xor (h1 ushr 16)) * 0x85EBCA6B.toInt() xor ((h2 xor (h2 ushr 13)) * 0xC2B2AE35.toInt())
        h2 = (h2 xor (h2 ushr 16)) * 0x85EBCA6B.toInt() xor ((h1 xor (h1 ushr 13)) * 0xC2B2AE35.toInt())
        return (h2.toLong() and 0xFFFFFFFFL).toString(36) +
            (h1.toLong() and 0xFFFFFFFFL).toString(36)
    }

    /** Removes unpaired UTF-16 surrogates, pi's sanitizeSurrogates. */
    fun sanitizeSurrogates(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i].code
            when {
                c in 0xD800..0xDBFF -> {
                    val next = if (i + 1 < text.length) text[i + 1].code else -1
                    if (next in 0xDC00..0xDFFF) {
                        sb.append(text[i]).append(text[i + 1])
                        i++
                    } // else: drop unpaired high surrogate
                }
                c in 0xDC00..0xDFFF -> {
                    // Low surrogate kept only when the previous unit was a high
                    // surrogate (already appended with its pair).
                    val prev = if (i > 0) text[i - 1].code else -1
                    if (prev in 0xD800..0xDBFF) sb.append(text[i])
                }
                else -> sb.append(text[i])
            }
            i++
        }
        return sb.toString()
    }

    /** Pi's clampOpenAIPromptCacheKey: truncate to 64 Unicode code points. */
    fun clampOpenAIPromptCacheKey(key: String?): String? {
        if (key == null) return null
        val chars = key.codePoints().toArray()
        if (chars.size <= OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH) return key
        return String(chars, 0, OPENAI_PROMPT_CACHE_KEY_MAX_LENGTH)
    }

    /** Pi's TextSignatureV1 encoding: `{"v":1,"id":...,"phase":...?}`. */
    fun encodeTextSignatureV1(id: String, phase: String?): String = buildJsonObject {
        put("v", 1)
        put("id", id)
        if (phase != null) put("phase", phase)
    }.toString()

    /** Pi's parseTextSignature: JSON v1 signature or legacy plain string. */
    fun parseTextSignature(signature: String?): Pair<String, String?>? {
        if (signature == null) return null
        if (signature.startsWith("{")) {
            try {
                val parsed = json.parseToJsonElement(signature) as? JsonObject
                if (parsed != null) {
                    // pi requires the numeric JSON `v: 1` (parsed.v === 1).
                    val v = parsed["v"] as? JsonPrimitive
                    val id = parsed["id"]?.textOrNull()
                    if (v != null && !v.isString && v.content.toIntOrNull() == 1 && id != null) {
                        val phase = parsed["phase"]?.textOrNull()
                            ?.takeIf { it == "commentary" || it == "final_answer" }
                        return id to phase
                    }
                }
            } catch (_: Exception) {
                // Fall through to legacy plain-string handling.
            }
        }
        return signature to null
    }

    // =========================================================================
    // transform-messages (reduced port of transform-messages.ts)
    // =========================================================================

    private const val NON_VISION_USER_IMAGE_PLACEHOLDER = "(image omitted: model does not support images)"
    private const val NON_VISION_TOOL_IMAGE_PLACEHOLDER = "(tool image omitted: model does not support images)"

    /**
     * Pi's transformMessages, reduced as documented on this object: image
     * downgrade for non-vision models, thinking/text replay rules, foreign
     * tool-call id normalization, skipping errored/aborted assistant turns,
     * and synthetic tool results for orphaned tool calls.
     */
    fun transformMessages(
        messages: List<Message>,
        model: Model,
        normalizeToolCallId: ((id: String, source: AssistantMessage) -> String)? = null,
    ): List<Message> {
        val toolCallIdMap = mutableMapOf<String, String>()
        val supportsImages = model.input.contains(InputModality.IMAGE)

        fun downgradeImages(content: List<Content>, placeholder: String): List<Content> =
            buildList {
                var previousWasPlaceholder = false
                for (block in content) {
                    if (block is ImageContent) {
                        if (!previousWasPlaceholder) add(TextContent(placeholder))
                        previousWasPlaceholder = true
                        continue
                    }
                    add(block)
                    previousWasPlaceholder = block is TextContent && block.text == placeholder
                }
            }

        val transformed = messages.map { msg ->
            when (msg) {
                is UserMessage ->
                    if (supportsImages) msg
                    else msg.copy(content = downgradeImages(msg.content, NON_VISION_USER_IMAGE_PLACEHOLDER))
                is ToolResultMessage -> {
                    val withId = toolCallIdMap[msg.toolCallId]?.let { msg.copy(toolCallId = it) } ?: msg
                    if (supportsImages) {
                        withId
                    } else {
                        withId.copy(content = downgradeImages(withId.content, NON_VISION_TOOL_IMAGE_PLACEHOLDER))
                    }
                }
                is AssistantMessage -> {
                    val isSameModel = msg.provider == model.provider && msg.api == model.api && msg.model == model.id
                    val content = msg.content.flatMap { block ->
                        when (block) {
                            is ThinkingContent -> when {
                                // Redacted thinking is opaque provider data and is only
                                // valid when replayed to the same model.
                                block.redacted -> if (isSameModel) listOf(block) else emptyList()
                                // Same model: keep thinking with signatures (needed for
                                // replay) even with empty text (encrypted reasoning).
                                isSameModel && block.thinkingSignature != null -> listOf(block)
                                block.thinking.isBlank() -> emptyList()
                                isSameModel -> listOf(block)
                                else -> listOf(TextContent(block.thinking))
                            }
                            is TextContent ->
                                if (isSameModel) listOf(block) else listOf(TextContent(block.text))
                            is ToolCall -> {
                                var normalized = if (!isSameModel && block.thoughtSignature != null) {
                                    block.copy(thoughtSignature = null)
                                } else {
                                    block
                                }
                                if (!isSameModel && normalizeToolCallId != null) {
                                    val normalizedId = normalizeToolCallId(block.id, msg)
                                    if (normalizedId != block.id) {
                                        toolCallIdMap[block.id] = normalizedId
                                        normalized = normalized.copy(id = normalizedId)
                                    }
                                }
                                listOf(normalized)
                            }
                            else -> listOf(block)
                        }
                    }
                    msg.copy(content = content)
                }
            }
        }

        // Second pass: synthetic empty tool results for orphaned tool calls.
        val result = mutableListOf<Message>()
        var pendingToolCalls = emptyList<ToolCall>()
        var existingToolResultIds = mutableSetOf<String>()
        fun insertSyntheticToolResults() {
            for (tc in pendingToolCalls) {
                if (tc.id !in existingToolResultIds) {
                    result.add(
                        ToolResultMessage(
                            toolCallId = tc.id,
                            toolName = tc.name,
                            content = listOf(TextContent("No result provided")),
                            isError = true,
                        ),
                    )
                }
            }
            pendingToolCalls = emptyList()
            existingToolResultIds = mutableSetOf()
        }

        for (msg in transformed) {
            when (msg) {
                is AssistantMessage -> {
                    insertSyntheticToolResults()
                    // Skip errored/aborted assistant messages entirely (pi:
                    // incomplete turns that must not be replayed).
                    if (msg.stopReason == StopReason.ERROR || msg.stopReason == StopReason.ABORTED) continue
                    val toolCalls = msg.content.filterIsInstance<ToolCall>()
                    if (toolCalls.isNotEmpty()) {
                        pendingToolCalls = toolCalls
                        existingToolResultIds = mutableSetOf()
                    }
                    result.add(msg)
                }
                is ToolResultMessage -> {
                    existingToolResultIds.add(msg.toolCallId)
                    result.add(msg)
                }
                else -> {
                    insertSyntheticToolResults()
                    result.add(msg)
                }
            }
        }
        insertSyntheticToolResults()
        return result
    }

    // =========================================================================
    // Message conversion (convertResponsesMessages)
    // =========================================================================

    data class ConvertResponsesToolsOptions(
        /** pi's strict option; null mirrors pi's `strict: null` (omit the field decision to the default). */
        val strict: Boolean? = false,
        val supportsStrictMode: Boolean = true,
        val deferLoading: Boolean = false,
    )

    enum class DeferredToolsMode { ADDITIONAL_TOOLS, TOOL_SEARCH }

    data class ConvertResponsesMessagesOptions(
        val includeSystemPrompt: Boolean = true,
        val deferredTools: Map<String, Tool> = emptyMap(),
        val deferredToolsMode: DeferredToolsMode? = null,
        val toolOptions: ConvertResponsesToolsOptions = ConvertResponsesToolsOptions(),
    )

    fun convertResponsesMessages(
        model: Model,
        context: Context,
        allowedToolCallProviders: Set<String>,
        options: ConvertResponsesMessagesOptions = ConvertResponsesMessagesOptions(),
    ): List<JsonObject> {
        val messages = mutableListOf<JsonObject>()
        val loadedToolNames = mutableSetOf<String>()

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
                val callId = id.substringBefore("|")
                val itemId = id.substringAfter("|")
                val normalizedCallId = normalizeIdPart(callId)
                val isForeignToolCall = source.provider != model.provider || source.api != model.api
                var normalizedItemId =
                    if (isForeignToolCall) buildForeignResponsesItemId(itemId) else normalizeIdPart(itemId)
                // OpenAI Responses API requires item ids to start with "fc".
                if (!normalizedItemId.startsWith("fc_")) {
                    normalizedItemId = normalizeIdPart("fc_$normalizedItemId")
                }
                "$normalizedCallId|$normalizedItemId"
            }
        }

        val transformedMessages = transformMessages(context.messages, model, normalizeToolCallId)

        if (options.includeSystemPrompt && context.systemPrompt != null) {
            val role =
                if (model.reasoning && model.responsesCompat?.supportsDeveloperRole != false) "developer" else "system"
            messages.add(
                buildJsonObject {
                    put("role", role)
                    put("content", sanitizeSurrogates(context.systemPrompt))
                },
            )
        }

        transformedMessages.forEachIndexed { msgIndex, msg ->
            when (msg) {
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
                            },
                        )
                    }
                }
                is AssistantMessage -> {
                    val output = mutableListOf<JsonObject>()
                    val isSameProviderAndApi = msg.provider == model.provider && msg.api == model.api
                    val isSameModel = isSameProviderAndApi && msg.model == model.id
                    val isDifferentModel = isSameProviderAndApi && msg.model != model.id
                    var textBlockIndex = 0

                    for (block in msg.content) {
                        when (block) {
                            is ThinkingContent -> if (block.thinkingSignature != null) {
                                // The signature is the serialized reasoning item.
                                output.add(json.parseToJsonElement(block.thinkingSignature!!) as JsonObject)
                            }
                            is TextContent -> {
                                val parsedSignature = parseTextSignature(block.textSignature)
                                val fallbackMessageId =
                                    if (textBlockIndex == 0) "msg_pi_$msgIndex"
                                    else "msg_pi_${msgIndex}_$textBlockIndex"
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
                                                    },
                                                ),
                                            ),
                                        )
                                        put("status", "completed")
                                        put("id", msgId)
                                        parsedSignature?.second?.let { put("phase", it) }
                                    },
                                )
                            }
                            is ToolCall -> {
                                val callId = block.id.substringBefore("|")
                                val itemIdRaw = block.id.substringAfter("|", "")
                                var itemId: String? = itemIdRaw.takeIf { block.id.contains("|") }
                                // For different-model messages drop fc_ ids to avoid
                                // pairing validation; non-fc_* ids (e.g. custom-tool
                                // ctc_*) are dropped because function_call ids must
                                // be fc_*.
                                if ((isDifferentModel && itemId?.startsWith("fc_") == true) ||
                                    itemId?.startsWith("fc_") != true
                                ) {
                                    itemId = null
                                }
                                val canReplayNamespace =
                                    isSameModel || options.deferredTools[block.name] != null
                                output.add(
                                    buildJsonObject {
                                        put("type", "function_call")
                                        itemId?.let { put("id", it) }
                                        put("call_id", callId)
                                        put("name", block.name)
                                        // Aletheia stores arguments as the raw JSON string.
                                        put("arguments", block.arguments)
                                        if (canReplayNamespace && block.namespace != null) {
                                            put("namespace", block.namespace)
                                        }
                                    },
                                )
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
                            put("type", "function_call_output")
                            put("call_id", callId)
                            put("output", converted)
                        },
                    )

                    val deferredTools = mutableListOf<Tool>()
                    for (name in msg.addedToolNames) {
                        val tool = options.deferredTools[name] ?: continue
                        if (!loadedToolNames.add(name)) continue
                        deferredTools.add(tool)
                    }
                    if (deferredTools.isNotEmpty() &&
                        options.deferredToolsMode == DeferredToolsMode.ADDITIONAL_TOOLS
                    ) {
                        messages.add(
                            buildJsonObject {
                                put("type", "additional_tools")
                                put("role", "developer")
                                put("tools", JsonArray(convertResponsesTools(deferredTools, options.toolOptions)))
                            },
                        )
                    } else if (deferredTools.isNotEmpty() &&
                        options.deferredToolsMode == DeferredToolsMode.TOOL_SEARCH
                    ) {
                        val names = deferredTools.map { it.name }
                        val searchCallId =
                            "pi_tool_load_${shortHash("${msg.toolCallId}:${names.joinToString(",")}")}"
                        messages.add(
                            buildJsonObject {
                                put("type", "tool_search_call")
                                put("call_id", searchCallId)
                                put("execution", "client")
                                put("status", "completed")
                                put(
                                    "arguments",
                                    buildJsonObject {
                                        put("query", names.joinToString(" "))
                                        put("limit", names.size)
                                    },
                                )
                            },
                        )
                        messages.add(
                            buildJsonObject {
                                put("type", "tool_search_output")
                                put("call_id", searchCallId)
                                put("execution", "client")
                                put("status", "completed")
                                put(
                                    "tools",
                                    JsonArray(
                                        convertResponsesTools(
                                            deferredTools,
                                            options.toolOptions.copy(deferLoading = true),
                                        ),
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
        return messages
    }

    /** Pi's convertToolResultOutput: joined text, or input_text/input_image parts. */
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
                },
            )
        }
        for (image in images) {
            output.add(
                buildJsonObject {
                    put("type", "input_image")
                    put("detail", "auto")
                    put("image_url", "data:${image.mimeType};base64,${image.data}")
                },
            )
        }
        return JsonArray(output)
    }

    // =========================================================================
    // Tool conversion
    // =========================================================================

    /**
     * Pi's convertResponsesTools, reduced: no grammar (custom) tools and no
     * strict-schema transformation (Aletheia tools carry no
     * constrainedSampling config, so strict schemas are never forced).
     */
    fun convertResponsesTools(
        tools: List<Tool>,
        options: ConvertResponsesToolsOptions = ConvertResponsesToolsOptions(),
    ): List<JsonObject> = tools.map { tool ->
        buildJsonObject {
            put("type", "function")
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", tool.parameters)
            if (options.deferLoading) put("defer_loading", true)
            if (options.supportsStrictMode) put("strict", options.strict ?: false)
        }
    }

    // =========================================================================
    // Stream processing (processResponsesStream)
    // =========================================================================

    /** Options threading pi's OpenAIResponsesStreamOptions + codex tier resolution. */
    data class StreamProcessingOptions(
        val serviceTier: String? = null,
        val resolveServiceTier: ((responseTier: String?, requestTier: String?) -> String?)? = null,
        val applyServiceTierPricing: ((usage: Usage, serviceTier: String?) -> Usage)? = null,
    )

    /**
     * Mutable content block holders; snapshots render fresh immutable [Content]
     * values so partials never share state (mirrors the existing completions
     * StreamingState contract).
     */
    private sealed interface Block {
        val index: Int

        class Thinking(override val index: Int) : Block {
            var thinking: String = ""
            var thinkingSignature: String? = null
        }

        class Text(override val index: Int) : Block {
            var text: String = ""
            var textSignature: String? = null
        }

        class Tool(override val index: Int) : Block {
            var id: String = ""
            var name: String = ""
            var arguments: StringBuilder = StringBuilder()
            var namespace: String? = null
        }
    }

    /**
     * Event-ordered stream state machine ported from pi's
     * processResponsesStream: slots keyed by output_index, reasoning/text/
     * toolcall block events with partial snapshots, Azure
     * reasoning.encrypted_content backfill from the terminal response, usage/
     * cost accounting, stop-reason mapping, and provider error events.
     */
    class ResponsesStreamState(
        private val model: Model,
        private val timestampMs: Long,
        private val options: StreamProcessingOptions = StreamProcessingOptions(),
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
            is Block.Thinking -> ThinkingContent(block.thinking, block.thinkingSignature)
            is Block.Text -> TextContent(block.text, block.textSignature)
            is Block.Tool -> ToolCall(
                id = block.id,
                name = block.name,
                arguments = block.arguments.toString(),
                namespace = block.namespace,
            )
        }

        private fun partial(): AssistantMessage = AssistantMessage(
            content = blocks.map(::render),
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = usage,
            stopReason = stopReason,
            errorMessage = errorMessage,
            rawStopReason = rawStopReason,
            responseId = responseId,
            timestamp = timestampMs,
        )

        /** Processes one complete stream event object; returns events to emit. */
        fun onEvent(event: JsonObject): List<AssistantMessageEvent> = when (event["type"].textOrNull()) {
            "response.created" -> {
                event.respObj("response")?.get("id").textOrNull()?.let { responseId = it }
                emptyList()
            }
            "response.output_item.added" -> {
                val outputIndex = event.respIntOrNull("output_index") ?: return emptyList()
                val item = event.respObj("item") ?: return emptyList()
                createSlot(outputIndex, item)
            }
            "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                val slot = getSlot<Block.Thinking>(event) ?: return emptyList()
                val delta = event["delta"].textOrNull() ?: return emptyList()
                slot.thinking += delta
                listOf(AssistantMessageEvent.ThinkingDelta(slot.index, delta, partial()))
            }
            "response.reasoning_summary_part.done" -> {
                val slot = getSlot<Block.Thinking>(event) ?: return emptyList()
                slot.thinking += "\n\n"
                listOf(AssistantMessageEvent.ThinkingDelta(slot.index, "\n\n", partial()))
            }
            "response.output_text.delta", "response.refusal.delta" -> {
                val slot = getSlot<Block.Text>(event) ?: return emptyList()
                val delta = event["delta"].textOrNull() ?: return emptyList()
                slot.text += delta
                listOf(AssistantMessageEvent.TextDelta(slot.index, delta, partial()))
            }
            "response.function_call_arguments.delta" -> {
                val slot = getSlot<Block.Tool>(event) ?: return emptyList()
                val delta = event["delta"].textOrNull() ?: return emptyList()
                slot.arguments.append(delta)
                listOf(AssistantMessageEvent.ToolCallDelta(slot.index, delta, partial()))
            }
            "response.function_call_arguments.done" -> {
                // Aletheia keeps raw argument strings: the done event's complete
                // arguments replace the accumulated buffer and any tail beyond the
                // streamed prefix is emitted as one final delta.
                val slot = getSlot<Block.Tool>(event) ?: return emptyList()
                val arguments = event["arguments"].textOrNull() ?: return emptyList()
                val previous = slot.arguments.toString()
                slot.arguments = StringBuilder(arguments)
                if (!arguments.startsWith(previous)) return emptyList()
                val delta = arguments.substring(previous.length)
                if (delta.isEmpty()) return emptyList()
                listOf(AssistantMessageEvent.ToolCallDelta(slot.index, delta, partial()))
            }
            "response.output_item.done" -> onOutputItemDone(event)
            "response.completed", "response.incomplete" -> {
                finalizeResponse(event.respObj("response"))
                emptyList()
            }
            "error" -> throw ProviderStreamException(
                "Error Code ${event["code"].textOrNull()}: ${event["message"].textOrNull()}"
                    .ifBlank { "Unknown error" },
            )
            "response.failed" -> {
                sawTerminalResponseEvent = true
                rawStopReason = event.respObj("response")?.get("status").textOrNull()
                val error = event.respObj("response")?.respObj("error")
                val details = event.respObj("response")?.respObj("incomplete_details")
                    ?.get("reason").textOrNull()
                val message = when {
                    error != null -> "${error["code"].textOrNull() ?: "unknown"}: " +
                        (error["message"].textOrNull() ?: "no message")
                    details != null -> "incomplete: $details"
                    else -> "Unknown error (no error details in response)"
                }
                throw ProviderStreamException(message)
            }
            else -> emptyList()
        }

        private inline fun <reified T : Block> getSlot(event: JsonObject): T? =
            slots[event.respIntOrNull("output_index") ?: return null] as? T

        private fun createSlot(outputIndex: Int, item: JsonObject): List<AssistantMessageEvent> {
            val block: Block = when (item["type"].textOrNull()) {
                "reasoning" -> Block.Thinking(blocks.size)
                "message" -> {
                    applyMessagePhaseStopReason(item)
                    Block.Text(blocks.size)
                }
                "function_call" -> Block.Tool(blocks.size).also { tool ->
                    tool.id = "${item["call_id"].textOrNull()}|${item["id"].textOrNull()}"
                    tool.name = item["name"].textOrNull() ?: ""
                    item["arguments"].textOrNull()?.let { tool.arguments.append(it) }
                    tool.namespace = item["namespace"].textOrNull()
                }
                // custom_tool_call omitted: grammar tools are not ported.
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
            if (item["type"].textOrNull() == "message" && item["phase"].textOrNull() == "final_answer") {
                stopReason = StopReason.STOP
            }
        }

        private fun onOutputItemDone(event: JsonObject): List<AssistantMessageEvent> {
            val item = event.respObj("item") ?: return emptyList()
            val outputIndex = event.respIntOrNull("output_index") ?: return emptyList()
            applyMessagePhaseStopReason(item)
            // pi's getOrCreateSlot: Azure and others may send output_item.done
            // without a preceding output_item.added for the same output_index.
            var events: List<AssistantMessageEvent> = emptyList()
            val slot = slots[outputIndex] ?: run {
                events = createSlot(outputIndex, item)
                slots[outputIndex]
            } ?: return emptyList()
            return when {
                item["type"].textOrNull() == "reasoning" && slot is Block.Thinking -> {
                    val summaryText = (item["summary"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonObject)?.get("text").textOrNull() }
                        ?.joinToString("\n\n").orEmpty()
                    val contentText = (item["content"] as? JsonArray)
                        ?.mapNotNull { c -> (c as? JsonObject)?.get("text").textOrNull() }
                        ?.joinToString("\n\n").orEmpty()
                    slot.thinking = summaryText.ifEmpty { contentText.ifEmpty { slot.thinking } }
                    slot.thinkingSignature = item.toString()
                    item["id"].textOrNull()?.let { reasoningBlocksById[it] = slot }
                    slots.remove(outputIndex)
                    return events + AssistantMessageEvent.ThinkingEnd(slot.index, slot.thinking, partial())
                }
                item["type"].textOrNull() == "message" && slot is Block.Text -> {
                    slot.text = (item["content"] as? JsonArray)
                        ?.mapNotNull { c ->
                            val obj = c as? JsonObject ?: return@mapNotNull null
                            obj["text"].textOrNull() ?: obj["refusal"].textOrNull()
                        }
                        ?.joinToString("").orEmpty()
                    slot.textSignature = encodeTextSignatureV1(
                        item["id"].textOrNull() ?: "",
                        item["phase"].textOrNull(),
                    )
                    slots.remove(outputIndex)
                    return events + AssistantMessageEvent.TextEnd(slot.index, slot.text, partial())
                }
                item["type"].textOrNull() == "function_call" && slot is Block.Tool -> {
                    // Finalize with the item's complete arguments; the streamed
                    // buffer is only a scratch replay of partial parsing.
                    val arguments = item["arguments"].textOrNull()
                    if (!arguments.isNullOrBlank()) slot.arguments = StringBuilder(arguments)
                    item["namespace"].textOrNull()?.let { slot.namespace = it }
                    slots.remove(outputIndex)
                    return events + listOf(
                        AssistantMessageEvent.ToolCallEnd(slot.index, render(slot) as ToolCall, partial()),
                    )
                }
                else -> emptyList()
            }
        }

        /**
         * Azure can omit reasoning.encrypted_content from output_item.done and
         * provide it only in the terminal response's output; backfill the
         * persisted reasoning signature so store:false replay stays stateless
         * (pi issue #6409).
         */
        private fun backfillReasoningSignatures(responseOutput: JsonArray?) {
            for (element in responseOutput ?: return) {
                val item = element as? JsonObject ?: continue
                if (item["type"].textOrNull() != "reasoning") continue
                val encrypted = item["encrypted_content"].textOrNull() ?: continue
                val block = item["id"].textOrNull()?.let { reasoningBlocksById[it] } ?: continue
                val stored = block.thinkingSignature ?: continue
                val storedItem = try {
                    json.parseToJsonElement(stored) as? JsonObject
                } catch (_: Exception) {
                    null
                } ?: continue
                if (storedItem["encrypted_content"] != null) continue
                block.thinkingSignature = buildJsonObject {
                    storedItem.forEach { (k, v) -> put(k, v) }
                    put("encrypted_content", encrypted)
                }.toString()
            }
        }

        private fun finalizeResponse(response: JsonObject?) {
            sawTerminalResponseEvent = true
            backfillReasoningSignatures(response?.get("output") as? JsonArray)
            response?.get("id").textOrNull()?.let { responseId = it }
            response?.respObj("usage")?.let { rawUsage ->
                val inputDetails = rawUsage.respObj("input_tokens_details")
                val cachedTokens = inputDetails?.respIntOrNull("cached_tokens") ?: 0
                val cacheWriteTokens = inputDetails?.respIntOrNull("cache_write_tokens") ?: 0
                val input = maxOf(0, (rawUsage.respIntOrNull("input_tokens") ?: 0) - cachedTokens - cacheWriteTokens)
                // OpenAI includes cached and cache-write tokens in input_tokens.
                var computed = Usage(
                    input = input,
                    output = rawUsage.respIntOrNull("output_tokens") ?: 0,
                    cacheRead = cachedTokens,
                    cacheWrite = cacheWriteTokens,
                    reasoning = rawUsage.respObj("output_tokens_details")?.respIntOrNull("reasoning_tokens") ?: 0,
                    totalTokens = rawUsage.respIntOrNull("total_tokens") ?: 0,
                )
                computed = computed.copy(cost = calculateCost(model, computed))
                options.applyServiceTierPricing?.let { apply ->
                    val serviceTier = options.resolveServiceTier?.invoke(
                        response["service_tier"].textOrNull(),
                        options.serviceTier,
                    ) ?: (response["service_tier"].textOrNull() ?: options.serviceTier)
                    computed = apply(computed, serviceTier)
                }
                usage = computed
            }
            // Map status to stop reason; incomplete keeps the provider's specific
            // reason so truncation and content filtering stay distinct.
            val status = response?.get("status").textOrNull()
            val incompleteReason = (response?.get("incomplete_details") as? JsonObject)?.get("reason").textOrNull()
            rawStopReason = if (incompleteReason != null) "$status.$incompleteReason" else status
            val mapped = mapStopReason(status, incompleteReason)
            stopReason = mapped.first
            errorMessage = mapped.second
            if (blocks.any { it is Block.Tool } && stopReason == StopReason.STOP) {
                stopReason = StopReason.TOOL_USE
            }
        }

        /** Pi: the stream must end with a terminal response event. */
        fun assertTerminalEvent() {
            if (!sawTerminalResponseEvent) {
                throw ProviderStreamException("OpenAI Responses stream ended before a terminal response event")
            }
        }
    }

    /** Pi's mapStopReason for response statuses. */
    fun mapStopReason(status: String?, incompleteReason: String?): Pair<StopReason, String?> = when (status) {
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

    // =========================================================================
    // Service tier pricing (openai-responses.ts / openai-codex-responses.ts)
    // =========================================================================

    fun getServiceTierCostMultiplier(modelId: String, serviceTier: String?): Double = when (serviceTier) {
        "flex" -> 0.5
        "priority" -> if (modelId == "gpt-5.5") 2.5 else 2.0
        else -> 1.0
    }

    /** Pi's applyServiceTierPricing, expressed over immutable [Usage] values. */
    fun applyServiceTierPricing(usage: Usage, serviceTier: String?, modelId: String): Usage {
        val multiplier = getServiceTierCostMultiplier(modelId, serviceTier)
        val cost = usage.cost
        if (multiplier == 1.0) return usage
        val scaled = Cost(
            input = cost.input * multiplier,
            output = cost.output * multiplier,
            cacheRead = cost.cacheRead * multiplier,
            cacheWrite = cost.cacheWrite * multiplier,
        )
        return usage.copy(
            cost = scaled.copy(total = scaled.input + scaled.output + scaled.cacheRead + scaled.cacheWrite),
        )
    }

    // =========================================================================
    // Compat resolution (openai-responses.ts getCompat)
    // =========================================================================

    fun detectSessionAffinityFormat(model: Model): SessionAffinityFormat =
        if (model.provider == "openrouter" || model.baseUrl.contains("openrouter.ai")) {
            SessionAffinityFormat.OPENROUTER
        } else {
            SessionAffinityFormat.OPENAI
        }

    fun getCompat(model: Model): ResolvedResponsesCompat {
        val compat = model.responsesCompat
        return ResolvedResponsesCompat(
            supportsDeveloperRole = compat?.supportsDeveloperRole ?: true,
            sessionAffinityFormat = compat?.sessionAffinityFormat ?: detectSessionAffinityFormat(model),
            supportsLongCacheRetention = compat?.supportsLongCacheRetention ?: true,
            supportsStrictMode = compat?.supportsStrictMode ?: false,
            supportsAdditionalTools = compat?.supportsAdditionalTools ?: false,
            supportsToolSearch = compat?.supportsToolSearch ?: false,
            supportsExplicitPromptCacheMode = compat?.supportsExplicitPromptCacheMode ?: false,
        )
    }

    data class ResolvedResponsesCompat(
        val supportsDeveloperRole: Boolean,
        val sessionAffinityFormat: SessionAffinityFormat,
        val supportsLongCacheRetention: Boolean,
        val supportsStrictMode: Boolean,
        val supportsAdditionalTools: Boolean,
        val supportsToolSearch: Boolean,
        val supportsExplicitPromptCacheMode: Boolean,
    )

    /** Pi's resolveCacheRetention: explicit > PI_CACHE_RETENTION=long > short. */
    fun resolveCacheRetention(cacheRetention: CacheRetention?, env: Map<String, String>): CacheRetention = when {
        cacheRetention != null -> cacheRetention
        env["PI_CACHE_RETENTION"] == "long" -> CacheRetention.LONG
        else -> CacheRetention.SHORT
    }

    /** Pi's getPromptCacheRetention. */
    fun getPromptCacheRetention(compat: ResolvedResponsesCompat, cacheRetention: CacheRetention): String? =
        if (cacheRetention == CacheRetention.LONG && compat.supportsLongCacheRetention) "24h" else null

    /** Pi's getClientApiKey: header auth stands in for a key. */
    fun getClientApiKey(provider: String, apiKey: String?, headers: Map<String, String?>): String {
        if (apiKey != null) return apiKey
        if (hasHeader(headers, "authorization") || hasHeader(headers, "cf-aig-authorization")) return "unused"
        throw IllegalStateException("No API key for provider: $provider")
    }

    /** Session-affinity headers, pi's openai-responses createClient. */
    fun sessionAffinityHeaders(sessionId: String?, compat: ResolvedResponsesCompat): Map<String, String> {
        if (sessionId == null) return emptyMap()
        return when (compat.sessionAffinityFormat) {
            SessionAffinityFormat.OPENROUTER -> mapOf("x-session-id" to sessionId)
            // "openai-nosession" sends only x-client-request-id (pi types.ts).
            SessionAffinityFormat.OPENAI_NOSESSION -> mapOf("x-client-request-id" to sessionId)
            SessionAffinityFormat.OPENAI -> mapOf(
                "session_id" to sessionId,
                "x-client-request-id" to sessionId,
            )
        }
    }

    /** Merge order shared by the Responses clients: model, affinity, request headers. */
    fun mergeClientHeaders(
        modelHeaders: Map<String, String>,
        sessionId: String?,
        compat: ResolvedResponsesCompat,
        optionsHeaders: Map<String, String?>,
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String?>()
        merged.putAll(modelHeaders)
        merged.putAll(sessionAffinityHeaders(sessionId, compat))
        // Options headers merge last so they can override defaults; null removes.
        merged.putAll(mergeHeaders(merged, optionsHeaders))
        return merged.filterValues { it != null }.mapValues { it.value!! }
    }

    /**
     * Pi's reasoning-effort resolution shared by the buildParams variants:
     * map through thinkingLevelMap when specified, else pass the level name
     * through, else the caller's default.
     */
    fun resolveReasoningEffort(model: Model, requested: ModelThinkingLevel?, defaultEffort: String): String =
        requested?.let { level ->
            model.thinkingLevelMap?.takeIf { it.isSpecified(level) }?.forLevel(level)
                ?: level.name.lowercase()
        } ?: defaultEffort
}

internal fun JsonElement?.textOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull()

internal fun JsonPrimitive?.contentOrNull(): String? = if (this != null && isString) content else null

internal fun JsonObject.respObj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.respIntOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()?.toInt()
