package works.resolve.pathfinder.ai.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.ContentType
import works.resolve.pathfinder.ai.InputModality
import works.resolve.pathfinder.ai.MessageRole
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.ProviderStreamException
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ThinkingLevel
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.TranscriptContext
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.clampThinkingLevel
import works.resolve.pathfinder.ai.toModelThinkingLevel
import works.resolve.pathfinder.ai.toThinkingLevelOrNull
import works.resolve.pathfinder.ai.utils.arr
import works.resolve.pathfinder.ai.utils.collapseSystemMessages
import works.resolve.pathfinder.ai.utils.sanitizeSurrogates
import works.resolve.pathfinder.ai.utils.str
import works.resolve.pathfinder.ai.utils.strictBoolean
import works.resolve.pathfinder.ai.utils.withoutInitialSystemMessage

/**
 * Shared logic for the Google Generative AI adapter.
 *
 * Divergences from pi:
 * - Upstream delegates the wire protocol to the `@google/genai` SDK;
 *   Pathfinder has no such SDK, so the conversion here must produce the same
 *   GenerateContentRequest JSON the SDK emits for the fields pi sets.
 */
object GoogleShared {

    enum class GoogleApiThinkingLevel(val wire: String) {
        THINKING_LEVEL_UNSPECIFIED("THINKING_LEVEL_UNSPECIFIED"),
        MINIMAL("MINIMAL"),
        LOW("LOW"),
        MEDIUM("MEDIUM"),
        HIGH("HIGH")
    }

    /** A [ThinkingLevel] without the xhigh/max variants. */
    enum class ResolvedGoogleThinkingLevel { MINIMAL, LOW, MEDIUM, HIGH }

    private val GOOGLE_SDK_THINKING_LEVEL_MAP = mapOf(
        GoogleApiThinkingLevel.THINKING_LEVEL_UNSPECIFIED to
            GoogleApiThinkingLevel.THINKING_LEVEL_UNSPECIFIED,
        GoogleApiThinkingLevel.MINIMAL to GoogleApiThinkingLevel.MINIMAL,
        GoogleApiThinkingLevel.LOW to GoogleApiThinkingLevel.LOW,
        GoogleApiThinkingLevel.MEDIUM to GoogleApiThinkingLevel.MEDIUM,
        GoogleApiThinkingLevel.HIGH to GoogleApiThinkingLevel.HIGH
    )

    /** Resolve a supported pi level or model-specific Google mapping to a standard Google level. */
    fun resolveGoogleThinkingLevel(
        model: Model,
        level: ThinkingLevel
    ): ResolvedGoogleThinkingLevel {
        val mapped = model.thinkingLevelMap?.forLevel(level.toModelThinkingLevel())
        val resolvedLevel = mapped?.lowercase() ?: level.name.lowercase()
        return when (resolvedLevel) {
            "minimal" -> ResolvedGoogleThinkingLevel.MINIMAL

            "low" -> ResolvedGoogleThinkingLevel.LOW

            "medium" -> ResolvedGoogleThinkingLevel.MEDIUM

            "high" -> ResolvedGoogleThinkingLevel.HIGH

            else -> throw IllegalStateException(
                "Unsupported Google thinking level mapping for ${model.provider}/${model.id}: " +
                    "${level.name.lowercase()} -> $mapped"
            )
        }
    }

    /**
     * Whether this model uses Gemini's discrete `thinkingLevel` control instead of
     * the token-based `thinkingBudget` control. Supported levels come from the
     * model's `thinkingLevelMap`; this only selects the Google wire format.
     */
    fun usesGoogleThinkingLevel(model: Model): Boolean {
        val id = model.id.lowercase()
        return Regex("gemini-3(?:\\.\\d+)?-(?:pro|flash)").containsMatchIn(id) ||
            id == "gemini-flash-latest" ||
            id == "gemini-flash-lite-latest" ||
            Regex("gemma-?4").containsMatchIn(id)
    }

    fun toGoogleThinkingLevel(level: ResolvedGoogleThinkingLevel): GoogleApiThinkingLevel =
        when (level) {
            ResolvedGoogleThinkingLevel.MINIMAL -> GoogleApiThinkingLevel.MINIMAL
            ResolvedGoogleThinkingLevel.LOW -> GoogleApiThinkingLevel.LOW
            ResolvedGoogleThinkingLevel.MEDIUM -> GoogleApiThinkingLevel.MEDIUM
            ResolvedGoogleThinkingLevel.HIGH -> GoogleApiThinkingLevel.HIGH
        }

    fun toGoogleSdkThinkingLevel(level: GoogleApiThinkingLevel): GoogleApiThinkingLevel =
        GOOGLE_SDK_THINKING_LEVEL_MAP.getValue(level)

    fun getDisabledGoogleThinkingConfig(model: Model): JsonObject {
        if (!usesGoogleThinkingLevel(model)) return buildJsonObject { put("thinkingBudget", 0) }

        val fallback = clampThinkingLevel(model, ModelThinkingLevel.OFF)
        if (fallback == ModelThinkingLevel.OFF) return buildJsonObject { put("thinkingBudget", 0) }

        val resolvedLevel = resolveGoogleThinkingLevel(model, fallback.toThinkingLevelOrNull()!!)
        val apiLevel = toGoogleThinkingLevel(resolvedLevel)
        return buildJsonObject { put("thinkingLevel", toGoogleSdkThinkingLevel(apiLevel).wire) }
    }
    const val FUNCTION_CALLING_MODE_AUTO = "AUTO"
    const val FUNCTION_CALLING_MODE_NONE = "NONE"
    const val FUNCTION_CALLING_MODE_ANY = "ANY"
    const val FUNCTION_CALLING_MODE_VALIDATED = "VALIDATED"

    /**
     * Whether a Gemini part is thinking content: `thought: true` is the
     * definitive marker; `thoughtSignature` can appear on any part type and
     * does not make the part thinking.
     * See https://ai.google.dev/gemini-api/docs/thought-signatures
     */
    fun isThinkingPart(part: JsonObject): Boolean = part.strictBoolean("thought") == true

    /**
     * Keeps the last non-empty signature within a streamed block; signatures
     * are never merged or moved across parts.
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
    private fun resolveThoughtSignature(
        isSameProviderAndModel: Boolean,
        signature: String?
    ): String? =
        if (isSameProviderAndModel && isValidThoughtSignature(signature)) signature else null

    private fun getGeminiMajorVersion(modelId: String): Int? =
        Regex("^gemini(?:-live)?-(\\d+)").find(modelId.lowercase())
            ?.groupValues?.get(1)?.toIntOrNull()

    /** Models via Google APIs that require explicit tool call IDs in function calls/responses. */
    fun requiresToolCallId(modelId: String): Boolean {
        val geminiMajorVersion = getGeminiMajorVersion(modelId)
        return modelId.startsWith("claude-") ||
            modelId.startsWith("gpt-oss-") ||
            (geminiMajorVersion != null && geminiMajorVersion >= 3)
    }

    /**
     * Gemini 3+ supports images nested in `functionResponse.parts`; Gemini < 3
     * needs a separate user image turn.
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

    /** Converts internal messages to Gemini `Content[]` wire JSON via [transformMessages]. */
    fun convertMessages(model: Model, context: TranscriptContext): JsonArray {
        // Gemini has no mid-conversation system messages; the leading prompt is sent as
        // systemInstruction.
        val conversation = withoutInitialSystemMessage(collapseSystemMessages(context).messages)
        val contents = mutableListOf<JsonObject>()
        val normalizeToolCallId = { id: String, _: AssistantMessage ->
            if (!requiresToolCallId(model.id)) {
                id
            } else {
                id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
            }
        }

        for (msg in transformMessages(conversation, model, normalizeToolCallId)) {
            when (msg.role) {
                MessageRole.USER -> convertUserMessage(msg as UserMessage, model)?.let {
                    contents.add(it)
                }

                MessageRole.ASSISTANT -> {
                    val assistant = msg as AssistantMessage
                    val parts = mutableListOf<JsonObject>()
                    val isSameProviderAndModel =
                        assistant.provider == model.provider && assistant.model == model.id

                    for (block in assistant.content) {
                        when (block.type) {
                            ContentType.TEXT -> {
                                block as TextContent
                                val thoughtSignature =
                                    resolveThoughtSignature(
                                        isSameProviderAndModel,
                                        block.textSignature
                                    )
                                // Skip empty text blocks — unless they carry a thought
                                // signature. Gemini attaches the signature to a part whose
                                // visible text is empty and requires it echoed back; dropping
                                // it breaks the reasoning chain.
                                if (block.text.isBlank() && thoughtSignature == null) continue
                                parts.add(
                                    buildJsonObject {
                                        put("text", sanitizeSurrogates(block.text))
                                        thoughtSignature?.let { put("thoughtSignature", it) }
                                    }
                                )
                            }

                            ContentType.THINKING -> {
                                block as ThinkingContent
                                if (isSameProviderAndModel) {
                                    val thoughtSignature =
                                        resolveThoughtSignature(true, block.thinkingSignature)
                                    // Same empty-block rule as text blocks.
                                    if (block.thinking.isBlank() &&
                                        thoughtSignature == null
                                    ) {
                                        continue
                                    }
                                    parts.add(
                                        buildJsonObject {
                                            put("thought", true)
                                            put("text", sanitizeSurrogates(block.thinking))
                                            thoughtSignature?.let { put("thoughtSignature", it) }
                                        }
                                    )
                                } else {
                                    // Cross-provider/model: the signature is unusable; convert
                                    // to plain text (no tags to avoid the model mimicking them).
                                    if (block.thinking.isBlank()) continue
                                    parts.add(
                                        buildJsonObject {
                                            put("text", sanitizeSurrogates(block.thinking))
                                        }
                                    )
                                }
                            }

                            ContentType.TOOL_CALL -> {
                                block as ToolCall
                                val thoughtSignature =
                                    resolveThoughtSignature(
                                        isSameProviderAndModel,
                                        block.thoughtSignature
                                    )
                                parts.add(
                                    buildJsonObject {
                                        put(
                                            "functionCall",
                                            buildJsonObject {
                                                put("name", block.name)
                                                put("args", block.arguments)
                                                if (requiresToolCallId(
                                                        model.id
                                                    )
                                                ) {
                                                    put("id", block.id)
                                                }
                                            }
                                        )
                                        thoughtSignature?.let { put("thoughtSignature", it) }
                                    }
                                )
                            }

                            // assistant images are not replayed upstream either
                            ContentType.IMAGE -> Unit
                        }
                    }

                    if (parts.isNotEmpty()) {
                        contents.add(
                            buildJsonObject {
                                put("role", "model")
                                put("parts", JsonArray(parts))
                            }
                        )
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
                                .map { it as works.resolve.pathfinder.ai.ImageContent }
                        } else {
                            emptyList()
                        }

                    val hasImages = imageContent.isNotEmpty()
                    val modelSupportsMultimodalFunctionResponse =
                        supportsMultimodalFunctionResponse(model.id)

                    // Use "output" for success, "error" for errors, per SDK docs.
                    val responseValue = when {
                        textResult.isNotEmpty() -> sanitizeSurrogates(textResult)
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
                                }
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
                                            responseValue
                                        )
                                    }
                                )
                                if (hasImages && modelSupportsMultimodalFunctionResponse) {
                                    put("parts", JsonArray(imageParts))
                                }
                                if (includeId) put("id", toolMsg.toolCallId)
                            }
                        )
                    }

                    // The Cloud Code Assist API requires all function responses in a
                    // single user turn: merge into a preceding function-response turn.
                    val lastContent = contents.lastOrNull()
                    val lastParts = lastContent?.arr("parts")
                    if (lastContent?.str("role") == "user" &&
                        lastParts != null &&
                        lastParts.filterIsInstance<JsonObject>().any {
                            it.containsKey("functionResponse")
                        }
                    ) {
                        contents[contents.size - 1] = buildJsonObject {
                            put("role", "user")
                            put("parts", JsonArray(lastParts + functionResponsePart))
                        }
                    } else {
                        contents.add(
                            buildJsonObject {
                                put("role", "user")
                                put("parts", JsonArray(listOf(functionResponsePart)))
                            }
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
                                        listOf(
                                            buildJsonObject {
                                                put("text", "Tool result image:")
                                            }
                                        ) +
                                            imageParts
                                    )
                                )
                            }
                        )
                    }
                }

                MessageRole.SYSTEM -> Unit
            }
        }

        return JsonArray(contents)
    }

    private fun convertUserMessage(msg: UserMessage, model: Model): JsonObject? {
        // transformMessages already downgraded unsupported images to placeholders.
        val parts = msg.content.mapNotNull { item ->
            when (item.type) {
                ContentType.TEXT -> buildJsonObject {
                    put("text", sanitizeSurrogates((item as TextContent).text))
                }

                ContentType.IMAGE -> {
                    item as works.resolve.pathfinder.ai.ImageContent
                    if (!model.input.contains(InputModality.IMAGE)) return@mapNotNull null
                    buildJsonObject {
                        put(
                            "inlineData",
                            buildJsonObject {
                                put("mimeType", item.mimeType)
                                put("data", item.data)
                            }
                        )
                    }
                }

                else -> null
            }
        }
        if (parts.isEmpty()) return null
        return buildJsonObject {
            put("role", "user")
            put("parts", JsonArray(parts))
        }
    }

    // JSON Schema meta-declarations stripped when using legacy OpenAPI `parameters`.
    private val JSON_SCHEMA_META_DECLARATIONS = setOf(
        "${'$'}schema",
        "${'$'}id",
        "${'$'}anchor",
        "${'$'}dynamicAnchor",
        "${'$'}vocabulary",
        "${'$'}comment",
        "${'$'}defs",
        "definitions" // pre-draft-2019-09 equivalent of $defs
    )

    internal fun sanitizeForOpenApi(schema: JsonElement): JsonElement = when (schema) {
        !is JsonObject -> schema

        else -> JsonObject(
            schema.entries
                .filter { it.key !in JSON_SCHEMA_META_DECLARATIONS }
                .associate { it.key to sanitizeForOpenApi(it.value) }
        )
    }

    /**
     * Convert tools to Gemini function declarations. By default uses
     * `parametersJsonSchema` (full JSON Schema); `useParameters` selects the
     * legacy OpenAPI `parameters` field instead (needed for Cloud Code Assist
     * with Claude), running the schema through [sanitizeForOpenApi].
     */
    fun convertTools(
        tools: List<Tool>,
        useParameters: Boolean = false,
        supportsStrictMode: Boolean = true
    ): JsonArray? {
        if (tools.isEmpty()) return null
        return JsonArray(
            listOf(
                buildJsonObject {
                    put(
                        "functionDeclarations",
                        JsonArray(
                            tools.map { tool ->
                                val strict =
                                    resolveJsonSchemaStrictSampling(tool, supportsStrictMode)
                                val parameters = getJsonSchemaToolParameters(tool, strict)
                                buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    if (useParameters) {
                                        put("parameters", sanitizeForOpenApi(parameters))
                                    } else {
                                        put("parametersJsonSchema", parameters)
                                    }
                                }
                            }
                        )
                    )
                }
            )
        )
    }

    /** Map tool choice string to a Gemini functionCallingConfig mode. */
    fun mapToolChoice(choice: String): String = when (choice) {
        "auto" -> FUNCTION_CALLING_MODE_AUTO
        "none" -> FUNCTION_CALLING_MODE_NONE
        "any" -> FUNCTION_CALLING_MODE_ANY
        else -> FUNCTION_CALLING_MODE_AUTO
    }

    /**
     * Resolves the function calling mode: explicit `none`/`any` choices win;
     * otherwise [FUNCTION_CALLING_MODE_VALIDATED] engages when any tool uses
     * strict JSON-schema sampling.
     */
    fun resolveGoogleFunctionCallingMode(
        tools: List<Tool>,
        toolChoice: String?,
        supportsStrictMode: Boolean
    ): String? {
        val useStrictMode = tools.any {
            resolveJsonSchemaStrictSampling(it, supportsStrictMode) ==
                true
        }
        if (toolChoice == "none" || toolChoice == "any") {
            return mapToolChoice(toolChoice)
        }
        if (useStrictMode) {
            return FUNCTION_CALLING_MODE_VALIDATED
        }
        return toolChoice?.let { mapToolChoice(it) }
    }

    /**
     * Maps a Gemini `FinishReason` wire string to a [StopReason]. STOP and
     * MAX_TOKENS are the success reasons; the SDK's remaining enumerated
     * reasons are provider errors reported at stream end; any other value
     * throws mid-stream, like pi's exhaustive switch over the SDK enum.
     */
    fun mapStopReason(reason: String): StopReason = when (reason) {
        "STOP" -> StopReason.STOP

        "MAX_TOKENS" -> StopReason.LENGTH

        "FINISH_REASON_UNSPECIFIED",
        "SAFETY",
        "RECITATION",
        "LANGUAGE",
        "OTHER",
        "BLOCKLIST",
        "PROHIBITED_CONTENT",
        "SPII",
        "MALFORMED_FUNCTION_CALL",
        "IMAGE_SAFETY",
        "UNEXPECTED_TOOL_CALL",
        "TOO_MANY_TOOL_CALLS",
        "IMAGE_PROHIBITED_CONTENT",
        "NO_IMAGE",
        "IMAGE_RECITATION",
        "IMAGE_OTHER" -> StopReason.ERROR

        else -> throw ProviderStreamException("Unhandled stop reason: $reason")
    }
}
