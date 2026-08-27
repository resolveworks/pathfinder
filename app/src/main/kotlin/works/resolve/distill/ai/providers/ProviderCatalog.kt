package works.resolve.distill.ai.providers

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import works.resolve.distill.ai.api.ChatApiRegistry
import works.resolve.distill.ai.core.AnthropicMessagesCompat
import works.resolve.distill.ai.core.ChatTemplateKwargValue
import works.resolve.distill.ai.core.InputModality
import works.resolve.distill.ai.core.MaxTokensField
import works.resolve.distill.ai.core.Model
import works.resolve.distill.ai.core.ModelCost
import works.resolve.distill.ai.core.ModelThinkingLevel
import works.resolve.distill.ai.core.OpenAiCompletionsCompat
import works.resolve.distill.ai.core.OpenAiResponsesCompat
import works.resolve.distill.ai.core.SessionAffinityFormat
import works.resolve.distill.ai.core.ThinkingFormat
import works.resolve.distill.ai.core.ThinkingLevelMap
import works.resolve.distill.ai.auth.ModelAuth
import works.resolve.distill.ai.models.Provider
import works.resolve.distill.ai.models.ResolvedAuth
import works.resolve.distill.ai.transport.HttpStreamingTransport
import works.resolve.distill.ai.utils.ProviderRetry

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

/** OAuth capability metadata (pi's lazyOAuth blocks): declarative only —
 * no flow implementation ships. [loginLabel] defaults to the name in pi. */
data class ProviderOAuth(
    val name: String,
    val loginLabel: String? = null,
    val isSubscription: Boolean = false,
)

/** Provider auth metadata: a label, OAuth capability for providers that
 * offer account login, and the prompts that fill its env slots. */
data class ProviderAuth(
    val label: String? = null,
    val oauth: ProviderOAuth? = null,
    val prompts: List<AuthPrompt> = emptyList(),
)

/**
 * A provider as described by the generated models-catalog asset: identity,
 * base URL, auth prompts, optional bearer-header override (Cloudflare AI
 * Gateway's `cf-aig-authorization`, resolved into a request header), and its
 * model list. Pure data; the transport/API pair is injected when a runtime
 * [Provider] is built.
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

    /** The distinct model API ids this provider's models use (pi's provider
     * api-map keys), regardless of whether a Kotlin implementation exists. */
    val apis: Set<String> get() = models.mapTo(mutableSetOf()) { it.api }

    /**
     * Auth prompts still missing values given a candidate credential: the
     * first prompt maps to the API key ([key]); every later prompt maps to
     * its [env] slot. All catalog auth prompts are required (pi's Cloudflare
     * auth resolution returns unconfigured unless every value exists), so an
     * empty list means the credential is complete. Values are never echoed —
     * only the prompt metadata is.
     */
    fun missingAuthPrompts(key: String?, env: Map<String, String>): List<AuthPrompt> {
        val missing = auth.prompts.mapIndexedNotNull { index, prompt ->
            val value = if (index == 0) key else env[prompt.envKey]
            if (value.isNullOrBlank()) prompt else null
        }.toMutableList()
        // API-key completeness is false for providers with no key prompts
        // (OAuth-only providers such as openai-codex). OAuth configuration is
        // evaluated separately by ProviderAuthService and the auth registry.
        if (auth.prompts.isEmpty() && key.isNullOrBlank()) {
            missing += AuthPrompt("API_KEY", "API key")
        }
        return missing
    }

    /** True iff every auth prompt has a nonblank value ([missingAuthPrompts] is empty). */
    fun isCredentialComplete(key: String?, env: Map<String, String>): Boolean =
        missingAuthPrompts(key, env).isEmpty()

    /**
     * Resolves a complete credential's key into the auth-layer request shape
     * (pi's cloudflare-auth.ts): ordinary providers resolve to a normal
     * apiKey; a bearer-header provider (Cloudflare AI Gateway) resolves to a
     * `Bearer <key>` request header on its named header with the default
     * Authorization/x-api-key paths removed and no apiKey.
     */
    fun toModelAuth(key: String, env: Map<String, String>): ModelAuth {
        val bearerHeaderName = bearerHeaderName
            ?: return ModelAuth(apiKey = key)
        return ModelAuth(
            headers = mapOf(
                bearerHeaderName to "Bearer $key",
                "Authorization" to null,
                "x-api-key" to null,
            ),
        )
    }

    /** [toModelAuth] projected onto the models-layer [ResolvedAuth] shape. */
    fun toResolvedAuth(key: String, env: Map<String, String>): ResolvedAuth {
        val auth = toModelAuth(key, env)
        return ResolvedAuth(apiKey = auth.apiKey, env = env, headers = auth.headers, baseUrl = auth.baseUrl)
    }

    /**
     * Builds the runtime provider for this catalog entry, wiring an API
     * implementation per model API id (pi's `Record<ApiId, Api>` provider
     * shape) and the auth resolver. APIs without a Kotlin implementation
     * are simply absent from the map, so catalog parsing and provider
     * listing work and streaming one of those models fails with a clear
     * unsupported-API error from Models.stream. Base-URL overrides are not
     * stamped here: callers create their effective model once via
     * `Model.copy(baseUrl = ...)` and stream that model (pi's requestModel).
     */
    fun toRuntimeProvider(
        transport: HttpStreamingTransport,
        retry: ProviderRetry = ProviderRetry(),
        authResolver: (suspend (apiKey: String?, env: Map<String, String>) -> ResolvedAuth?)? = null,
    ): Provider =
        Provider(
            id = id,
            name = name,
            baseUrl = baseUrl,
            authResolver = authResolver,
            models = models,
            apis = apis.mapNotNull { apiId ->
                ChatApiRegistry.create(apiId, transport, retry)?.let { apiId to it }
            }.toMap(),
        )
}

/**
 * The parsed models-catalog asset: every static provider pi knows about,
 * with all of each provider's model APIs (only some of which have runtime
 * implementations; see ChatApiRegistry). Parsing is lenient about unknown
 * object fields and ignores compat fields the runtime does not model yet,
 * but fails fast on unknown enum values — the asset ships inside the APK,
 * so a mismatch is a build bug, not a runtime condition to paper over.
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
 * Normalizes a base URL: trimmed, with all trailing slashes dropped.
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
    val oauth: OAuthDto? = null,
    val prompts: List<PromptDto> = emptyList(),
) {
    fun toDomain() = ProviderAuth(
        label = label,
        oauth = oauth?.toDomain(),
        prompts = prompts.map { it.toDomain() },
    )
}

@Serializable
private data class OAuthDto(
    val name: String,
    val loginLabel: String? = null,
    val isSubscription: Boolean = false,
) {
    fun toDomain() = ProviderOAuth(name, loginLabel, isSubscription)
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
        anthropicCompat = compat.toAnthropicDomain(),
        responsesCompat = if (api in RESPONSES_FAMILY_APIS) compat.toResponsesDomain("${owner.id}/$id") else null,
        headers = headers,
    )

    private companion object {
        /** pi models carry OpenAIResponsesCompat only for the Responses family. */
        val RESPONSES_FAMILY_APIS = setOf(
            "openai-responses",
            "openai-codex-responses",
            "azure-openai-responses",
        )
    }
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
    // Anthropic Messages compat (pi's AnthropicMessagesCompat): consumed by
    // the anthropic-messages adapter via Model.anthropicCompat.
    val supportsEagerToolInputStreaming: Boolean? = null,
    val supportsLongCacheRetention: Boolean? = null,
    val sendSessionAffinityHeaders: Boolean? = null,
    val supportsCacheControlOnTools: Boolean? = null,
    val supportsTemperature: Boolean? = null,
    val allowEmptySignature: Boolean? = null,
    val supportsStrictTools: Boolean? = null,
    val forceAdaptiveThinking: Boolean? = null,
    // OpenAI Responses-family compat (pi's OpenAIResponsesCompat), consumed
    // via Model.responsesCompat.
    val supportsStrictMode: Boolean? = null,
    val sessionAffinityFormat: String? = null,
    val supportsAdditionalTools: Boolean? = null,
    val supportsToolSearch: Boolean? = null,
    val supportsExplicitPromptCacheMode: Boolean? = null,
    // Not yet modeled by the runtime: supportsStrictMode,
    // requiresReasoningContentOnAssistantMessages, deferredToolsMode,
    // cacheControlFormat — ignored via ignoreUnknownKeys.
    //
    // Deliberate narrow omission: allowedFallbackModels (server-side
    // `fallbacks` models) is present in the asset but not ported in this
    // chunk; callers must continue to omit the `fallbacks` request field.
    // supportsToolReferences is likewise not yet modeled.
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

    /** pi's getCompat (openai-responses) defaults apply per field when absent. */
    fun toResponsesDomain(where: String) = OpenAiResponsesCompat(
        supportsDeveloperRole = supportsDeveloperRole ?: true,
        sessionAffinityFormat = sessionAffinityFormat?.let { parseSessionAffinityFormat(it, where) },
        supportsLongCacheRetention = supportsLongCacheRetention ?: true,
        supportsStrictMode = supportsStrictMode ?: false,
        supportsAdditionalTools = supportsAdditionalTools ?: false,
        supportsToolSearch = supportsToolSearch ?: false,
        supportsExplicitPromptCacheMode = supportsExplicitPromptCacheMode ?: false,
    )

    /** pi's getAnthropicCompat defaults apply per field when absent. */
    fun toAnthropicDomain() = AnthropicMessagesCompat(
        supportsEagerToolInputStreaming = supportsEagerToolInputStreaming ?: true,
        supportsLongCacheRetention = supportsLongCacheRetention ?: true,
        sendSessionAffinityHeaders = sendSessionAffinityHeaders ?: false,
        supportsCacheControlOnTools = supportsCacheControlOnTools ?: true,
        supportsTemperature = supportsTemperature ?: true,
        allowEmptySignature = allowEmptySignature ?: false,
        supportsStrictTools = supportsStrictTools ?: false,
        forceAdaptiveThinking = forceAdaptiveThinking,
    )
}

private fun parseSessionAffinityFormat(value: String, where: String): SessionAffinityFormat = when (value) {
    "openai" -> SessionAffinityFormat.OPENAI
    "openai-nosession" -> SessionAffinityFormat.OPENAI_NOSESSION
    "openrouter" -> SessionAffinityFormat.OPENROUTER
    else -> throw IllegalArgumentException("Unknown session affinity format '$value' for $where")
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
