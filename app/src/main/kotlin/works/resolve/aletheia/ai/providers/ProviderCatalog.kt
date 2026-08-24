package works.resolve.aletheia.ai.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import works.resolve.aletheia.ai.api.ChatApi
import works.resolve.aletheia.ai.api.OpenAiCompletionsApi
import works.resolve.aletheia.ai.core.ChatTemplateKwargValue
import works.resolve.aletheia.ai.core.InputModality
import works.resolve.aletheia.ai.core.MaxTokensField
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.ModelCost
import works.resolve.aletheia.ai.core.ModelThinkingLevel
import works.resolve.aletheia.ai.core.OpenAiCompletionsCompat
import works.resolve.aletheia.ai.core.ThinkingFormat
import works.resolve.aletheia.ai.core.ThinkingLevelMap
import works.resolve.aletheia.ai.models.Provider
import works.resolve.aletheia.ai.transport.HttpStreamingTransport
import works.resolve.aletheia.ai.utils.ProviderRetry

/**
 * One credential prompt from the catalog, mirroring pi's auth prompt
 * definitions: which env slot the value fills, the user-facing message, and
 * whether the input is a secret.
 */
data class AuthPrompt(
    val envKey: String,
    val message: String,
    val secret: Boolean = true,
)

/** Provider auth metadata: a label plus the prompts that fill its env slots. */
data class ProviderAuth(
    val label: String? = null,
    val prompts: List<AuthPrompt> = emptyList(),
)

/**
 * A provider as described by the generated models-catalog asset: identity,
 * base URL, auth prompts, optional bearer-header override (Cloudflare AI
 * Gateway's `cf-aig-authorization`), and its model list. Pure data; the
 * transport/API pair is injected when a runtime [Provider] is built.
 */
class CatalogProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val bearerHeaderName: String? = null,
    val auth: ProviderAuth = ProviderAuth(),
    val models: List<Model>,
) {
    fun model(id: String): Model? = models.firstOrNull { it.id == id }

    /**
     * Auth prompts still missing values given a candidate credential: the
     * first prompt maps to the API key ([key]); every later prompt maps to
     * its [env] slot. All catalog auth prompts are required (pi's Cloudflare
     * auth resolution returns unconfigured unless every value exists), so an
     * empty list means the credential is complete. Values are never echoed —
     * only the prompt metadata is.
     */
    fun missingAuthPrompts(key: String?, env: Map<String, String>): List<AuthPrompt> =
        auth.prompts.mapIndexedNotNull { index, prompt ->
            val value = if (index == 0) key else env[prompt.envKey]
            if (value.isNullOrBlank()) prompt else null
        }

    /** True iff every auth prompt has a nonblank value ([missingAuthPrompts] is empty). */
    fun isCredentialComplete(key: String?, env: Map<String, String>): Boolean =
        missingAuthPrompts(key, env).isEmpty()

    /**
     * Builds the runtime provider for this catalog entry. A [baseUrl]
     * override (normalized) is stamped onto every model, mirroring what the
     * old ZaiProvider.create did for base-URL overrides.
     */
    fun toRuntimeProvider(
        transport: HttpStreamingTransport,
        retry: ProviderRetry = ProviderRetry(),
        apiKeyResolver: (suspend () -> String?)? = null,
        baseUrl: String = this.baseUrl,
    ): Provider {
        val effectiveBaseUrl = normalizeBaseUrl(baseUrl)
        return Provider(
            id = id,
            name = name,
            baseUrl = effectiveBaseUrl,
            apiKeyResolver = apiKeyResolver,
            models = if (effectiveBaseUrl == this.baseUrl) {
                models
            } else {
                models.map { it.copy(baseUrl = effectiveBaseUrl) }
            },
            api = OpenAiCompletionsApi(transport, retry) as ChatApi,
        )
    }
}

/**
 * The parsed models-catalog asset: every openai-completions provider pi
 * knows about. Parsing is lenient about unknown object fields and ignores
 * compat flags the runtime does not model yet, but fails fast on unknown
 * enum values — the asset ships inside the APK, so a mismatch is a build
 * bug, not a runtime condition to paper over.
 */
class ProviderCatalog(val providers: List<CatalogProvider>) {

    fun getProvider(id: String): CatalogProvider? = providers.firstOrNull { it.id == id }

    fun getModel(providerId: String, modelId: String): Model? =
        getProvider(providerId)?.model(modelId)

    companion object {
        fun parse(text: String): ProviderCatalog = try {
            ProviderCatalog(
                json.decodeFromString<CatalogDto>(text).providers.map { it.toDomain() },
            )
        } catch (error: SerializationException) {
            throw IllegalArgumentException("Malformed model catalog: ${error.message}", error)
        }

        private val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Normalizes a base-URL override: trimmed, with all trailing slashes dropped.
 * Reuses the logic previously owned by ZaiProvider.
 */
internal fun normalizeBaseUrl(url: String): String {
    val effective = url.trim().trimEnd('/')
    if (effective.isEmpty()) {
        throw IllegalArgumentException("baseUrl must not be blank")
    }
    return effective
}

// ---- lenient serialization DTOs (asset shape) ----

@Serializable
private data class CatalogDto(
    val generatedAt: String? = null,
    val providers: List<ProviderDto> = emptyList(),
)

@Serializable
private data class ProviderDto(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val auth: AuthDto? = null,
    val bearerHeaderName: String? = null,
    val models: List<ModelDto> = emptyList(),
) {
    fun toDomain(): CatalogProvider = CatalogProvider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        bearerHeaderName = bearerHeaderName,
        auth = auth?.toDomain() ?: ProviderAuth(),
        models = models.map { it.toDomain(this) },
    )
}

@Serializable
private data class AuthDto(
    val label: String? = null,
    val prompts: List<PromptDto> = emptyList(),
) {
    fun toDomain() = ProviderAuth(label = label, prompts = prompts.map { it.toDomain() })
}

@Serializable
private data class PromptDto(
    val envKey: String,
    val message: String = "",
    val secret: Boolean = true,
) {
    fun toDomain() = AuthPrompt(envKey = envKey, message = message, secret = secret)
}

@Serializable
private data class ModelDto(
    val id: String,
    val name: String,
    val api: String = "openai-completions",
    val provider: String = "",
    val baseUrl: String = "",
    val reasoning: Boolean = false,
    /** Nullable map values keep the absent-vs-explicit-null distinction. */
    val thinkingLevelMap: Map<String, String?>? = null,
    val input: List<String> = listOf("text"),
    val cost: CostDto = CostDto(),
    val contextWindow: Int = 4096,
    val maxTokens: Int = 4096,
    val compat: CompatDto = CompatDto(),
    val headers: Map<String, String> = emptyMap(),
) {
    fun toDomain(owner: ProviderDto): Model = Model(
        id = id,
        name = name,
        api = api,
        provider = provider.ifEmpty { owner.id },
        baseUrl = baseUrl.ifEmpty { owner.baseUrl },
        reasoning = reasoning,
        thinkingLevelMap = thinkingLevelMap?.let { parseThinkingLevelMap(it, "${owner.id}/$id") },
        input = input.map { parseInputModality(it, "${owner.id}/$id") },
        cost = cost.toDomain(),
        contextWindow = contextWindow,
        maxTokens = maxTokens,
        compat = compat.toDomain("${owner.id}/$id"),
        headers = headers,
    )
}

@Serializable
private data class CostDto(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cacheRead: Double = 0.0,
    val cacheWrite: Double = 0.0,
) {
    fun toDomain() = ModelCost(input, output, cacheRead, cacheWrite)
}

@Serializable
private data class CompatDto(
    val supportsStore: Boolean? = null,
    val supportsDeveloperRole: Boolean? = null,
    val supportsReasoningEffort: Boolean? = null,
    val supportsUsageInStreaming: Boolean? = null,
    val supportsFinishReason: Boolean? = null,
    val maxTokensField: String? = null,
    val requiresToolResultName: Boolean? = null,
    val requiresThinkingAsText: Boolean? = null,
    val thinkingFormat: String? = null,
    val zaiToolStream: Boolean? = null,
    val chatTemplateArgs: Map<String, kotlinx.serialization.json.JsonElement>? = null,
    // Not yet modeled by the runtime: supportsStrictMode,
    // supportsLongCacheRetention, sendSessionAffinityHeaders,
    // requiresReasoningContentOnAssistantMessages, deferredToolsMode,
    // cacheControlFormat — ignored via ignoreUnknownKeys.
) {
    fun toDomain(where: String) = OpenAiCompletionsCompat(
        supportsStore = supportsStore ?: true,
        supportsDeveloperRole = supportsDeveloperRole ?: true,
        supportsReasoningEffort = supportsReasoningEffort ?: true,
        supportsUsageInStreaming = supportsUsageInStreaming ?: true,
        supportsFinishReason = supportsFinishReason ?: true,
        maxTokensField = maxTokensField?.let { parseMaxTokensField(it, where) }
            ?: MaxTokensField.MAX_COMPLETION_TOKENS,
        requiresToolResultName = requiresToolResultName ?: false,
        requiresThinkingAsText = requiresThinkingAsText ?: false,
        thinkingFormat = thinkingFormat?.let { parseThinkingFormat(it, where) } ?: ThinkingFormat.OPENAI,
        zaiToolStream = zaiToolStream ?: false,
        chatTemplateArgs = chatTemplateArgs
            ?.mapValues { (_, value) -> parseChatTemplateKwarg(value, "${where}.chatTemplateArgs") }
            ?: emptyMap(),
    )
}

private fun parseInputModality(value: String, where: String): InputModality = when (value) {
    "text" -> InputModality.TEXT
    "image" -> InputModality.IMAGE
    else -> throw IllegalArgumentException("Unknown input modality '$value' for $where")
}

private fun parseThinkingFormat(value: String, where: String): ThinkingFormat = when (value) {
    "openai" -> ThinkingFormat.OPENAI
    "zai" -> ThinkingFormat.ZAI
    "qwen" -> ThinkingFormat.QWEN
    "deepseek" -> ThinkingFormat.DEEPSEEK
    "openrouter" -> ThinkingFormat.OPENROUTER
    "together" -> ThinkingFormat.TOGETHER
    "ant-ling" -> ThinkingFormat.ANT_LING
    "baseten" -> ThinkingFormat.BASETEN
    else -> throw IllegalArgumentException("Unknown thinkingFormat '$value' for $where")
}

private fun parseMaxTokensField(value: String, where: String): MaxTokensField = when (value) {
    "max_tokens" -> MaxTokensField.MAX_TOKENS
    "max_completion_tokens" -> MaxTokensField.MAX_COMPLETION_TOKENS
    else -> throw IllegalArgumentException("Unknown maxTokensField '$value' for $where")
}

private fun parseThinkingLevel(
    value: String,
    where: String,
): ModelThinkingLevel = try {
    ModelThinkingLevel.valueOf(value.uppercase())
} catch (_: IllegalArgumentException) {
    throw IllegalArgumentException("Unknown thinking level '$value' for $where")
}

private fun parseThinkingLevelMap(raw: Map<String, String?>, where: String): ThinkingLevelMap {
    val pairs = raw.entries
        // Unknown level keys are ignored leniently (fields, not enum values).
        .mapNotNull { (key, value) ->
            val level = runCatching { parseThinkingLevel(key, where) }.getOrNull()
                ?: return@mapNotNull null
            level to value
        }
        .toTypedArray()
    return ThinkingLevelMap.of(*pairs)
}

private fun parseChatTemplateKwarg(
    value: kotlinx.serialization.json.JsonElement,
    where: String,
): ChatTemplateKwargValue {
    if (value is JsonObject && value.containsKey("\$var")) {
        val varName = (value["\$var"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (varName == null) {
            throw IllegalArgumentException("Malformed \$var reference for $where")
        }
        val omitWhenOff = (value["omitWhenOff"] as? JsonPrimitive)?.booleanOrNull ?: false
        return ChatTemplateKwargValue.Ref(varName = varName, omitWhenOff = omitWhenOff)
    }
    return ChatTemplateKwargValue.Scalar(value)
}
