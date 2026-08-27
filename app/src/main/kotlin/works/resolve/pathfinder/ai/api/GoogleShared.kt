package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.ContentType
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.MessageRole
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.Tool
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.UserMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared logic for the Google Generative AI adapter, ported from pi's
 * packages/ai/src/api/google-shared.ts (and the transformMessages pass it
 * runs from transform-messages.ts).
 *
 * Upstream delegates the wire protocol to the `@google/genai` SDK; Pathfinder
 * has no such SDK, so the conversion produces the same GenerateContentRequest
 * JSON the SDK emits for the fields pi sets:
 * - `contents` entries `{role: "user"|"model", parts: [...]}`,
 * - parts `{text}`, `{thought: true, text}`, `{inlineData}`, `{functionCall}`,
 *   `{functionResponse}`, each optionally carrying `thoughtSignature`,
 * - `tools: [{functionDeclarations: [...]}]` with `parametersJsonSchema`.
 *
 * Divergences from upstream (each narrow, see the citing KDoc):
 * - [Tool] has no `constrainedSampling`, so pi's
 *   `resolveJsonSchemaStrictSampling` is always undefined here: the
 *   `VALIDATED` function-calling mode never engages and `convertTools` never
 *   wraps/rewrites schemas for strictness (google-shared.ts /
 *   constrained-sampling.ts).
 * - [ThinkingContent] has no `redacted` flag, so the redacted-thinking branch
 *   of transform-messages.ts is omitted.
 * - `ToolCall.arguments` is a raw JSON string in the Kotlin core, not an
 *   object; parsing belongs to tool execution.
 */
object GoogleShared {

    /** pi's GoogleApiThinkingLevel: Google's ThinkingLevel enum wire values. */
    enum class GoogleApiThinkingLevel(val wire: String) {
        THINKING_LEVEL_UNSPECIFIED("THINKING_LEVEL_UNSPECIFIED"),
        MINIMAL("MINIMAL"),
        LOW("LOW"),
        MEDIUM("MEDIUM"),
        HIGH("HIGH"),
    }

    /** pi's ResolvedGoogleThinkingLevel: ThinkingLevel without xhigh/max. */
    enum class ResolvedGoogleThinkingLevel { MINIMAL, LOW, MEDIUM, HIGH }

    /** Gemini functionCallingConfig mode wire values (FunctionCallingConfigMode). */
    const val FUNCTION_CALLING_MODE_AUTO = "AUTO"
    const val FUNCTION_CALLING_MODE_NONE = "NONE"
    const val FUNCTION_CALLING_MODE_ANY = "ANY"
    const val FUNCTION_CALLING_MODE_VALIDATED = "VALIDATED"

    /**
     * Resolve a supported pi level or model-specific Google mapping to a
     * standard Google level. Port of google-shared.ts `resolveGoogleThinkingLevel`:
     * "off" maps to "high", a string `thinkingLevelMap` entry is lowercased,
     * and anything outside minimal/low/medium/high throws.
     */
    fun resolveGoogleThinkingLevel(
        model: Model,
        level: ModelThinkingLevel,
    ): ResolvedGoogleThinkingLevel {
        if (level == ModelThinkingLevel.OFF) return ResolvedGoogleThinkingLevel.HIGH

        val mapped = model.thinkingLevelMap?.forLevel(level)
        val resolvedLevel = mapped?.lowercase() ?: level.name.lowercase()
        return when (resolvedLevel) {
            "minimal" -> ResolvedGoogleThinkingLevel.MINIMAL
            "low" -> ResolvedGoogleThinkingLevel.LOW
            "medium" -> ResolvedGoogleThinkingLevel.MEDIUM
            "high" -> ResolvedGoogleThinkingLevel.HIGH
            else -> throw IllegalStateException(
                "Unsupported Google thinking level mapping for ${model.provider}/${model.id}: " +
                    "${level.name.lowercase()} -> $mapped",
            )
        }
    }

    /**
     * Whether a streamed Gemini part is thinking content. Port of
     * google-shared.ts `isThinkingPart`: `thought: true` is the definitive
     * marker; `thoughtSignature` can appear on any part type and does not make
     * the part thinking. See https://ai.google.dev/gemini-api/docs/thought-signatures
     */
    fun isThinkingPart(part: JsonObject): Boolean = (part["thought"] as? JsonPrimitive)?.content == "true"

    /**
     * Retain thought signatures during streaming. Port of google-shared.ts
     * `retainThoughtSignature`: keep the last non-empty signature within the
     * same streamed block; never merge signatures across distinct parts.
     */
    fun retainThoughtSignature(existing: String?, incoming: String?): String? =
        if (!incoming.isNullOrEmpty()) incoming else existing

    // Thought signatures must be base64 for Google APIs (TYPE_BYTES).
    private val base64SignaturePattern = Regex("^[A-Za-z0-9+/]+={0,2}$")

    private fun isValidThoughtSignature(signature: String?): Boolean {
        if (signature.isNullOrEmpty()) return false
        if (signature.length % 4 != 0) return false
        return base64SignaturePattern.matches(signature)
    }

    /** Only keep signatures from the same provider/model and with valid base64. */
    private fun resolveThoughtSignature(isSameProviderAndModel: Boolean, signature: String?): String? =
        if (isSameProviderAndModel && isValidThoughtSignature(signature)) signature else null

    private fun getGeminiMajorVersion(modelId: String): Int? =
        Regex("^gemini(?:-live)?-(\\d+)").find(modelId.lowercase())
            ?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Models via Google APIs that require explicit tool call IDs in function
     * calls/responses. Port of google-shared.ts `requiresToolCallId`.
     */
    fun requiresToolCallId(modelId: String): Boolean {
        val geminiMajorVersion = getGeminiMajorVersion(modelId)
        return modelId.startsWith("claude-") ||
            modelId.startsWith("gpt-oss-") ||
            (geminiMajorVersion != null && geminiMajorVersion >= 3)
    }

    /**
     * Gemini 3+ models support multimodal function responses (images nested in
     * `functionResponse.parts`); other models need a separate user image turn.
     * Port of google-shared.ts `supportsMultimodalFunctionResponse`.
     */
    fun supportsMultimodalFunctionResponse(modelId: String): Boolean {
        val geminiMajorVersion = getGeminiMajorVersion(modelId)
        return geminiMajorVersion?.let { it >= 3 } ?: true
    }

    /** Gemini 3+ enforces required function parameters in validated tool-calling modes. */
    fun supportsGoogleStrictToolSampling(modelId: String): Boolean {
        val majorVersion = getGeminiMajorVersion(modelId)
        return majorVersion != null && majorVersion >= 3
    }

    /**
     * Convert internal messages to Gemini Content[] wire JSON. Port of
     * google-shared.ts `convertMessages` (including the `transformMessages`
     * pre-pass with pi's tool-call-id normalization).
     */
    fun convertMessages(model: Model, context: Context): JsonArray {
        val contents = mutableListOf<JsonObject>()
        val normalizeToolCallId = { id: String ->
            if (!requiresToolCallId(model.id)) id
            else id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
        }

        for (msg in transformMessages(context.messages, model, normalizeToolCallId)) {
            when (msg.role) {
                MessageRole.USER -> convertUserMessage(msg as UserMessage, model)?.let { contents.add(it) }

                MessageRole.ASSISTANT -> {
                    val assistant = msg as AssistantMessage
                    val parts = mutableListOf<JsonObject>()
                    // Only keep thinking blocks from the same provider and model.
                    val isSameProviderAndModel =
                        assistant.provider == model.provider && assistant.model == model.id

                    for (block in assistant.content) {
                        when (block.type) {
                            ContentType.TEXT -> {
                                block as TextContent
                                val thoughtSignature =
                                    resolveThoughtSignature(isSameProviderAndModel, block.textSignature)
                                // Skip empty text blocks — unless they carry a thought
                                // signature. Gemini can attach the signature to a part whose
                                // visible text is empty and requires it echoed back; dropping it
                                // breaks the reasoning chain (test/google-shared-signed-empty-blocks).
                                if (block.text.isBlank() && thoughtSignature == null) continue
                                parts.add(
                                    buildJsonObject {
                                        put("text", OpenAiCompletionsPayload.sanitizeSurrogates(block.text))
                                        thoughtSignature?.let { put("thoughtSignature", it) }
                                    },
                                )
                            }

                            ContentType.THINKING -> {
                                block as ThinkingContent
                                if (isSameProviderAndModel) {
                                    val thoughtSignature = resolveThoughtSignature(true, block.thinkingSignature)
                                    // Same empty-block rule as text blocks.
                                    if (block.thinking.isBlank() && thoughtSignature == null) continue
                                    parts.add(
                                        buildJsonObject {
                                            put("thought", true)
                                            put("text", OpenAiCompletionsPayload.sanitizeSurrogates(block.thinking))
                                            thoughtSignature?.let { put("thoughtSignature", it) }
                                        },
                                    )
                                } else {
                                    // Cross-provider/model: the signature is unusable; convert
                                    // to plain text (no tags to avoid the model mimicking them).
                                    if (block.thinking.isBlank()) continue
                                    parts.add(
                                        buildJsonObject {
                                            put("text", OpenAiCompletionsPayload.sanitizeSurrogates(block.thinking))
                                        },
                                    )
                                }
                            }

                            ContentType.TOOL_CALL -> {
                                block as ToolCall
                                val thoughtSignature =
                                    resolveThoughtSignature(isSameProviderAndModel, block.thoughtSignature)
                                parts.add(
                                    buildJsonObject {
                                        put(
                                            "functionCall",
                                            buildJsonObject {
                                                put("name", block.name)
                                                put("args", parseArgsOrEmpty(block.arguments))
                                                if (requiresToolCallId(model.id)) put("id", block.id)
                                            },
                                        )
                                        thoughtSignature?.let { put("thoughtSignature", it) }
                                    },
                                )
                            }

                            ContentType.IMAGE -> Unit // assistant images are not replayed upstream either
                        }
                    }

                    if (parts.isNotEmpty()) {
                        contents.add(buildJsonObject { put("role", "model"); put("parts", JsonArray(parts)) })
                    }
                }

                MessageRole.TOOL_RESULT -> {
                    val toolMsg = msg as ToolResultMessage
                    val textResult = toolMsg.content
                        .filter { it.type == ContentType.TEXT }
                        .map { (it as TextContent).text }
                        .joinToString("\n")
                    val imageContent =
                        if (model.input.contains(InputModality.IMAGE)) {
                            toolMsg.content.filter { it.type == ContentType.IMAGE }
                                .map { it as works.resolve.pathfinder.ai.core.ImageContent }
                        } else {
                            emptyList()
                        }

                    val hasImages = imageContent.isNotEmpty()
                    val modelSupportsMultimodalFunctionResponse = supportsMultimodalFunctionResponse(model.id)

                    // Use "output" for success, "error" for errors, per SDK docs.
                    val responseValue = when {
                        textResult.isNotEmpty() -> OpenAiCompletionsPayload.sanitizeSurrogates(textResult)
                        hasImages -> "(see attached image)"
                        else -> ""
                    }

                    val imageParts = imageContent.map { imageBlock ->
                        buildJsonObject {
                            put(
                                "inlineData",
                                buildJsonObject {
                                    put("mimeType", imageBlock.mimeType)
                                    put("data", imageBlock.data)
                                },
                            )
                        }
                    }

                    val includeId = requiresToolCallId(model.id)
                    val functionResponsePart = buildJsonObject {
                        put(
                            "functionResponse",
                            buildJsonObject {
                                put("name", toolMsg.toolName)
                                put(
                                    "response",
                                    buildJsonObject {
                                        put(
                                            if (toolMsg.isError) "error" else "output",
                                            responseValue,
                                        )
                                    },
                                )
                                if (hasImages && modelSupportsMultimodalFunctionResponse) {
                                    put("parts", JsonArray(imageParts))
                                }
                                if (includeId) put("id", toolMsg.toolCallId)
                            },
                        )
                    }

                    // The Cloud Code Assist API requires all function responses in a
                    // single user turn: merge into a preceding function-response turn.
                    val lastContent = contents.lastOrNull()
                    val lastParts = (lastContent?.get("parts") as? JsonArray)
                    if (lastContent?.get("role")?.let { (it as? JsonPrimitive)?.content } == "user" &&
                        lastParts != null && lastParts.any { (it as? JsonObject)?.containsKey("functionResponse") == true }
                    ) {
                        contents[contents.size - 1] = buildJsonObject {
                            put("role", "user")
                            put("parts", JsonArray(lastParts + functionResponsePart))
                        }
                    } else {
                        contents.add(
                            buildJsonObject { put("role", "user"); put("parts", JsonArray(listOf(functionResponsePart))) },
                        )
                    }

                    // Gemini < 3: images go in a separate user message.
                    if (hasImages && !modelSupportsMultimodalFunctionResponse) {
                        contents.add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "parts",
                                    JsonArray(
                                        listOf(buildJsonObject { put("text", "Tool result image:") }) + imageParts,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }

        return JsonArray(contents)
    }

    private fun convertUserMessage(msg: UserMessage, model: Model): JsonObject? {
        // transformMessages already downgraded unsupported images to placeholders.
        val parts = msg.content.mapNotNull { item ->
            when (item.type) {
                ContentType.TEXT -> buildJsonObject {
                    put("text", OpenAiCompletionsPayload.sanitizeSurrogates((item as TextContent).text))
                }

                ContentType.IMAGE -> {
                    item as works.resolve.pathfinder.ai.core.ImageContent
                    if (!model.input.contains(InputModality.IMAGE)) return@mapNotNull null
                    buildJsonObject {
                        put(
                            "inlineData",
                            buildJsonObject {
                                put("mimeType", item.mimeType)
                                put("data", item.data)
                            },
                        )
                    }
                }

                else -> null
            }
        }
        if (parts.isEmpty()) return null
        return buildJsonObject { put("role", "user"); put("parts", JsonArray(parts)) }
    }

    private fun parseArgsOrEmpty(raw: String): JsonElement = try {
        val parsed = Json.parseToJsonElement(raw)
        if (parsed is JsonObject) parsed else JsonObject(emptyMap())
    } catch (_: Exception) {
        JsonObject(emptyMap())
    }

    /** JSON parser shared by argument replay and stream decoding. */
    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    // JSON Schema meta-declarations stripped when using legacy OpenAPI `parameters`.
    private val JSON_SCHEMA_META_DECLARATIONS = setOf(
        "${'$'}schema", "${'$'}id", "${'$'}anchor", "${'$'}dynamicAnchor", "${'$'}vocabulary",
        "${'$'}comment", "${'$'}defs",
        "definitions", // pre-draft-2019-09 equivalent of $defs
    )

    /** Strip meta-declarations from a schema obj. Port of google-shared.ts `sanitizeForOpenApi`. */
    internal fun sanitizeForOpenApi(schema: JsonElement): JsonElement = when (schema) {
        !is JsonObject -> schema
        else -> JsonObject(
            schema.entries
                .filter { it.key !in JSON_SCHEMA_META_DECLARATIONS }
                .associate { it.key to sanitizeForOpenApi(it.value) },
        )
    }

    /**
     * Convert tools to Gemini function declarations format. Port of
     * google-shared.ts `convertTools`. By default uses `parametersJsonSchema`
     * (full JSON Schema); `useParameters` selects the legacy OpenAPI
     * `parameters` field (needed for Cloud Code Assist with Claude).
     *
     * Divergence: pi's `resolveJsonSchemaStrictSampling` (constrained-sampling.ts)
     * has no equivalent here because [Tool] has no `constrainedSampling`, so
     * strict schema wrapping is never applied and the caller's
     * `supportsStrictMode` only affects mode resolution.
     */
    fun convertTools(
        tools: List<Tool>,
        useParameters: Boolean = false,
        @Suppress("UNUSED_PARAMETER") supportsStrictMode: Boolean = true,
    ): JsonArray? {
        if (tools.isEmpty()) return null
        return JsonArray(
            listOf(
                buildJsonObject {
                    put(
                        "functionDeclarations",
                        JsonArray(
                            tools.map { tool ->
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    if (useParameters) {
                                        put("parameters", sanitizeForOpenApi(tool.parameters))
                                    } else {
                                        put("parametersJsonSchema", tool.parameters)
                                    }
                                }
                            },
                        ),
                    )
                },
            ),
        )
    }

    /** Map tool choice string to a Gemini functionCallingConfig mode. Port of `mapToolChoice`. */
    fun mapToolChoice(choice: String): String = when (choice) {
        "auto" -> FUNCTION_CALLING_MODE_AUTO
        "none" -> FUNCTION_CALLING_MODE_NONE
        "any" -> FUNCTION_CALLING_MODE_ANY
        else -> FUNCTION_CALLING_MODE_AUTO
    }

    /**
     * Resolve the function calling mode. Port of google-shared.ts
     * `resolveGoogleFunctionCallingMode`, minus the strict-sampling branch:
     * VALIDATED is only reachable for tools that opt into constrained JSON
     * schema sampling, which the Kotlin core does not model, so it is never
     * returned here.
     */
    fun resolveGoogleFunctionCallingMode(
        tools: List<Tool>,
        toolChoice: String?,
        @Suppress("UNUSED_PARAMETER") supportsStrictMode: Boolean,
    ): String? = when (toolChoice) {
        "none" -> FUNCTION_CALLING_MODE_NONE
        "any" -> FUNCTION_CALLING_MODE_ANY
        else -> toolChoice?.let { mapToolChoice(it) }
    }

    /**
     * Map a Gemini FinishReason (its enum's wire string) to our StopReason.
     * Port of google-shared.ts `mapStopReason`/`mapStopReasonString`: STOP →
     * stop, MAX_TOKENS → length, everything else → error.
     */
    fun mapStopReason(reason: String): StopReason = when (reason) {
        "STOP" -> StopReason.STOP
        "MAX_TOKENS" -> StopReason.LENGTH
        else -> StopReason.ERROR
    }

    // ------------------------------------------------------------------------
    // transform-messages.ts port
    // ------------------------------------------------------------------------

    private const val NON_VISION_USER_IMAGE_PLACEHOLDER = "(image omitted: model does not support images)"
    private const val NON_VISION_TOOL_IMAGE_PLACEHOLDER = "(tool image omitted: model does not support images)"

    /**
     * Normalize messages for replay. Port of transform-messages.ts
     * `transformMessages`: image downgrade for non-vision models, thinking
     * block conversion for cross-model replay, tool-call ID normalization,
     * dropping errored/aborted assistant turns, and synthetic tool results for
     * orphaned tool calls.
     *
     * Divergence: pi's ThinkingContent `redacted` flag has no Kotlin
     * counterpart, so the redacted-thinking branch is omitted.
     */
    fun transformMessages(
        messages: List<Message>,
        model: Model,
        normalizeToolCallId: ((id: String) -> String)? = null,
    ): List<Message> {
        val toolCallIdMap = mutableMapOf<String, String>()
        val supportsImages = model.input.contains(InputModality.IMAGE)

        val transformed = messages.map { msg ->
            when (msg.role) {
                MessageRole.USER -> {
                    if (supportsImages) return@map msg
                    msg as UserMessage
                    msg.copy(content = replaceImagesWithPlaceholders(msg.content, NON_VISION_USER_IMAGE_PLACEHOLDER))
                }

                MessageRole.TOOL_RESULT -> {
                    val tool = msg as ToolResultMessage
                    val normalizedId = toolCallIdMap[tool.toolCallId]
                    val content = if (supportsImages) {
                        tool.content
                    } else {
                        replaceImagesWithPlaceholders(tool.content, NON_VISION_TOOL_IMAGE_PLACEHOLDER)
                    }
                    if (normalizedId != null && normalizedId != tool.toolCallId) {
                        tool.copy(toolCallId = normalizedId, content = content)
                    } else {
                        tool.copy(content = content)
                    }
                }

                MessageRole.ASSISTANT -> {
                    val assistant = msg as AssistantMessage
                    val isSameModel =
                        assistant.provider == model.provider &&
                            assistant.api == model.api &&
                            assistant.model == model.id

                    val newContent = assistant.content.flatMap { block ->
                        when (block.type) {
                            ContentType.THINKING -> {
                                block as ThinkingContent
                                // For the same model, keep thinking blocks with
                                // signatures (needed for replay) even if empty.
                                if (isSameModel && block.thinkingSignature != null) return@flatMap listOf(block)
                                if (block.thinking.isBlank()) return@flatMap emptyList()
                                if (isSameModel) return@flatMap listOf(block)
                                listOf(TextContent(block.thinking))
                            }

                            ContentType.TEXT ->
                                listOf(block as TextContent)

                            ContentType.TOOL_CALL -> {
                                var call = block as ToolCall
                                if (!isSameModel && call.thoughtSignature != null) {
                                    call = call.copy(thoughtSignature = null)
                                }
                                if (!isSameModel && normalizeToolCallId != null) {
                                    val normalizedId = normalizeToolCallId(call.id)
                                    if (normalizedId != call.id) {
                                        toolCallIdMap[call.id] = normalizedId
                                        call = call.copy(id = normalizedId)
                                    }
                                }
                                listOf(call)
                            }

                            else -> listOf(block)
                        }
                    }
                    assistant.copy(content = newContent)
                }
            }
        }

        // Second pass: synthesize tool results for orphaned tool calls and drop
        // errored/aborted assistant turns.
        val result = mutableListOf<Message>()
        var pendingToolCalls = mutableListOf<ToolCall>()
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
            if (pendingToolCalls.isNotEmpty()) {
                pendingToolCalls = mutableListOf()
                existingToolResultIds = mutableSetOf()
            }
        }

        for (msg in transformed) {
            when (msg.role) {
                MessageRole.ASSISTANT -> {
                    insertSyntheticToolResults()
                    val assistant = msg as AssistantMessage
                    // Skip errored/aborted assistant messages entirely; replaying
                    // incomplete turns can cause API errors.
                    if (assistant.stopReason == StopReason.ERROR || assistant.stopReason == StopReason.ABORTED) {
                        continue
                    }
                    val toolCalls = assistant.content.filterIsInstance<ToolCall>()
                    if (toolCalls.isNotEmpty()) {
                        pendingToolCalls = toolCalls.toMutableList()
                        existingToolResultIds = mutableSetOf()
                    }
                    result.add(msg)
                }

                MessageRole.TOOL_RESULT -> {
                    existingToolResultIds.add((msg as ToolResultMessage).toolCallId)
                    result.add(msg)
                }

                MessageRole.USER -> {
                    insertSyntheticToolResults()
                    result.add(msg)
                }
            }
        }
        insertSyntheticToolResults()
        return result
    }

    private fun replaceImagesWithPlaceholders(content: List<Content>, placeholder: String): List<Content> {
        val result = mutableListOf<Content>()
        var previousWasPlaceholder = false
        for (block in content) {
            if (block.type == ContentType.IMAGE) {
                if (!previousWasPlaceholder) result.add(TextContent(placeholder))
                previousWasPlaceholder = true
                continue
            }
            result.add(block)
            previousWasPlaceholder = block is TextContent && block.text == placeholder
        }
        return result
    }
}

private typealias Json = kotlinx.serialization.json.Json

private fun JsonElement?.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
