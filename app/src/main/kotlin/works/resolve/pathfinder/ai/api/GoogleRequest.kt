package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.ThinkingLevel
import works.resolve.pathfinder.ai.core.clampThinkingLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Request construction for the Google Generative AI adapter: pi's
 * google-generative-ai.ts `buildParams` plus its model-class thinking
 * helpers (Gemini 3 / Gemma 4 / 2.5 budgets).
 *
 * Wire shape: where pi hands `config` to the `@google/genai` SDK, Pathfinder
 * writes the documented GenerateContentRequest REST shape directly —
 * `contents`, `systemInstruction` (string), `tools`, `toolConfig` at the top
 * level, and `temperature`/`maxOutputTokens`/`thinkingConfig` nested in
 * `generationConfig`.
 */
object GoogleRequest {

    /** pi's GoogleOptions.thinking. */
    data class GoogleThinking(
        val enabled: Boolean,
        /** -1 for dynamic, 0 to disable. */
        val budgetTokens: Int? = null,
        val level: GoogleShared.GoogleApiThinkingLevel? = null,
    )

    /** pi's GoogleOptions (StreamOptions plus toolChoice and thinking). */
    data class CommonOptions(
        val apiKey: String? = null,
        val sessionId: String? = null,
        val temperature: Double? = null,
        val maxTokens: Int? = null,
        val timeoutMs: Long? = null,
        val maxRetries: Int = 0,
        val maxRetryDelayMs: Long = works.resolve.pathfinder.ai.core.StreamOptions.DEFAULT_MAX_RETRY_DELAY_MS,
        val env: Map<String, String> = emptyMap(),
        val headers: Map<String, String?> = emptyMap(),
        /** "auto" | "none" | "any". */
        val toolChoice: String? = null,
        val thinking: GoogleThinking? = null,
    ) {
        override fun toString(): String =
            "CommonOptions(apiKey=" + (apiKey?.let { "<redacted>" } ?: "null") +
                ", sessionId=$sessionId, temperature=$temperature, maxTokens=$maxTokens" +
                ", timeoutMs=$timeoutMs, maxRetries=$maxRetries, maxRetryDelayMs=$maxRetryDelayMs" +
                ", env=${env.keys}, headers=${headers.keys}, toolChoice=$toolChoice," +
                " thinking=${thinking?.enabled})"
    }

    /** pi's buildParams, on the REST wire. */
    fun buildGenerateContentRequest(
        model: Model,
        context: Context,
        options: CommonOptions,
        gemmaSupported: Boolean,
    ): JsonObject {
        val supportsStrictMode = GoogleShared.supportsGoogleStrictToolSampling(model.id)
        val functionCallingMode = if (context.tools.isNotEmpty()) {
            GoogleShared.resolveGoogleFunctionCallingMode(context.tools, options.toolChoice, supportsStrictMode)
        } else {
            null
        }

        val request = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        request["contents"] = GoogleShared.convertMessages(model, context)
        if (!context.systemPrompt.isNullOrEmpty()) {
            request["systemInstruction"] = JsonPrimitive(
                OpenAiCompletionsPayload.sanitizeSurrogates(context.systemPrompt),
            )
        }
        if (context.tools.isNotEmpty()) {
            request["tools"] = GoogleShared.convertTools(context.tools, false, supportsStrictMode)!!
        }
        if (functionCallingMode != null) {
            request["toolConfig"] = buildJsonObject {
                put(
                    "functionCallingConfig",
                    buildJsonObject { put("mode", functionCallingMode) },
                )
            }
        }

        val generationConfig = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        options.temperature?.let { generationConfig["temperature"] = JsonPrimitive(it) }
        options.maxTokens?.let { generationConfig["maxOutputTokens"] = JsonPrimitive(it) }

        val thinking = options.thinking
        val thinkingConfig: JsonObject? = when {
            thinking != null && thinking.enabled && model.reasoning -> buildJsonObject {
                put("includeThoughts", true)
                thinking.level?.let { put("thinkingLevel", it.wire) }
                    ?: thinking.budgetTokens?.let { put("thinkingBudget", it) }
            }

            model.reasoning && thinking != null && !thinking.enabled ->
                getDisabledThinkingConfig(model, gemmaSupported)

            else -> null
        }
        thinkingConfig?.let { generationConfig["thinkingConfig"] = it }
        if (generationConfig.isNotEmpty()) {
            request["generationConfig"] = JsonObject(generationConfig)
        }

        return JsonObject(request)
    }

    private fun isGemma4Model(modelId: String): Boolean =
        Regex("gemma-?4").containsMatchIn(modelId.lowercase())

    private fun isGemini3ProModel(modelId: String): Boolean =
        Regex("gemini-3(?:\\.\\d+)?-pro").containsMatchIn(modelId.lowercase())

    fun isGemini3FlashModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        return Regex("gemini-3(?:\\.\\d+)?-flash").containsMatchIn(id) ||
            id == "gemini-flash-latest" || id == "gemini-flash-lite-latest"
    }

    /**
     * pi's getDisabledThinkingConfig: Gemini 3.1 Pro cannot disable thinking
     * and Gemini 3 Flash/Flash-Lite (and Gemma 4, where in scope) do not
     * support full thinking-off either, so use the lowest supported
     * thinkingLevel without includeThoughts; Gemini 2.x disables via
     * thinkingBudget = 0.
     */
    private fun getDisabledThinkingConfig(model: Model, gemmaSupported: Boolean): JsonObject = when {
        isGemini3ProModel(model.id) ->
            buildJsonObject { put("thinkingLevel", GoogleShared.GoogleApiThinkingLevel.LOW.wire) }

        isGemini3FlashModel(model.id) ->
            buildJsonObject { put("thinkingLevel", GoogleShared.GoogleApiThinkingLevel.MINIMAL.wire) }

        gemmaSupported && isGemma4Model(model.id) ->
            buildJsonObject { put("thinkingLevel", GoogleShared.GoogleApiThinkingLevel.MINIMAL.wire) }

        else -> buildJsonObject { put("thinkingBudget", 0) }
    }

    /**
     * pi's streamSimple thinking resolution: the provider-neutral reasoning
     * level becomes a Gemini 3 `thinkingLevel` or a Gemini 2.5
     * `thinkingBudget`. Returns `thinking { enabled: false }` when reasoning
     * is absent, exactly like upstream.
     */
    fun thinkingForSimpleStream(
        model: Model,
        reasoning: ThinkingLevel?,
        budgets: Map<ThinkingLevel, Int>,
        gemmaSupported: Boolean,
    ): GoogleThinking {
        if (reasoning == null) return GoogleThinking(enabled = false)

        val clamped = clampThinkingLevel(model, reasoning.toModelThinkingLevel())
        val resolvedLevel = GoogleShared.resolveGoogleThinkingLevel(model, clamped)

        val useLevels = isGemini3ProModel(model.id) ||
            isGemini3FlashModel(model.id) ||
            (gemmaSupported && isGemma4Model(model.id))
        if (useLevels) {
            return GoogleThinking(enabled = true, level = getThinkingLevel(resolvedLevel, model, gemmaSupported))
        }
        return GoogleThinking(enabled = true, budgetTokens = getGoogleBudget(model, resolvedLevel, budgets))
    }

    private fun ThinkingLevel.toModelThinkingLevel(): ModelThinkingLevel =
        ModelThinkingLevel.valueOf(name)

    /** pi's getThinkingLevel. */
    private fun getThinkingLevel(
        effort: GoogleShared.ResolvedGoogleThinkingLevel,
        model: Model,
        gemmaSupported: Boolean,
    ): GoogleShared.GoogleApiThinkingLevel {
        if (isGemini3ProModel(model.id)) {
            return when (effort) {
                GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL,
                GoogleShared.ResolvedGoogleThinkingLevel.LOW,
                -> GoogleShared.GoogleApiThinkingLevel.LOW

                else -> GoogleShared.GoogleApiThinkingLevel.HIGH
            }
        }
        if (gemmaSupported && isGemma4Model(model.id)) {
            return when (effort) {
                GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL,
                GoogleShared.ResolvedGoogleThinkingLevel.LOW,
                -> GoogleShared.GoogleApiThinkingLevel.MINIMAL

                else -> GoogleShared.GoogleApiThinkingLevel.HIGH
            }
        }
        return when (effort) {
            GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL -> GoogleShared.GoogleApiThinkingLevel.MINIMAL
            GoogleShared.ResolvedGoogleThinkingLevel.LOW -> GoogleShared.GoogleApiThinkingLevel.LOW
            GoogleShared.ResolvedGoogleThinkingLevel.MEDIUM -> GoogleShared.GoogleApiThinkingLevel.MEDIUM
            GoogleShared.ResolvedGoogleThinkingLevel.HIGH -> GoogleShared.GoogleApiThinkingLevel.HIGH
        }
    }

    /** pi's getGoogleBudget: model-specific default budgets, -1 (dynamic) otherwise. */
    private fun getGoogleBudget(
        model: Model,
        level: GoogleShared.ResolvedGoogleThinkingLevel,
        customBudgets: Map<ThinkingLevel, Int>,
    ): Int {
        val asThinkingLevel = ThinkingLevel.valueOf(level.name)
        customBudgets[asThinkingLevel]?.let { return it }

        val defaults = when {
            model.id.contains("2.5-pro") -> mapOf(
                GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL to 128,
                GoogleShared.ResolvedGoogleThinkingLevel.LOW to 2048,
                GoogleShared.ResolvedGoogleThinkingLevel.MEDIUM to 8192,
                GoogleShared.ResolvedGoogleThinkingLevel.HIGH to 32768,
            )

            model.id.contains("2.5-flash-lite") -> mapOf(
                GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL to 512,
                GoogleShared.ResolvedGoogleThinkingLevel.LOW to 2048,
                GoogleShared.ResolvedGoogleThinkingLevel.MEDIUM to 8192,
                GoogleShared.ResolvedGoogleThinkingLevel.HIGH to 24576,
            )

            model.id.contains("2.5-flash") -> mapOf(
                GoogleShared.ResolvedGoogleThinkingLevel.MINIMAL to 128,
                GoogleShared.ResolvedGoogleThinkingLevel.LOW to 2048,
                GoogleShared.ResolvedGoogleThinkingLevel.MEDIUM to 8192,
                GoogleShared.ResolvedGoogleThinkingLevel.HIGH to 24576,
            )

            else -> return -1
        }
        return defaults.getValue(level)
    }

    /**
     * User-Agent default pi sends via getPiUserAgent(); Pathfinder identifies
     * itself (divergence: the Android client is not pi).
     */
    const val USER_AGENT = "pathfinder (Android)"
}
