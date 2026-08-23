package works.resolve.aletheia.ai.providers

import works.resolve.aletheia.ai.api.ChatApi
import works.resolve.aletheia.ai.models.Provider
import works.resolve.aletheia.ai.transport.HttpStreamingTransport
import works.resolve.aletheia.ai.api.OpenAiCompletionsApi
import works.resolve.aletheia.ai.utils.ProviderRetry

/**
 * The global Z.AI provider (api.z.ai coding plan endpoint), ported from pi's
 * providers/zai.ts: identity, base URL, env-style API-key auth, the coding
 * plan model catalog, and the reusable openai-completions API.
 */
object ZaiProvider {

    const val NAME = "Z.AI"
    const val API_KEY_ENV_VAR = "ZAI_API_KEY"

    /** Normalizes a base-URL override: trimmed, with all trailing slashes dropped. */
    internal fun normalizeBaseUrl(url: String): String {
        val effective = url.trim().trimEnd('/')
        if (effective.isNullOrEmpty()) {
            throw IllegalArgumentException("baseUrl must not be blank")
        }
        return effective
    }

    fun create(
        transport: HttpStreamingTransport,
        retry: ProviderRetry = ProviderRetry(),
        apiKeyResolver: (suspend () -> String?)? = null,
        baseUrl: String = ZaiModels.BASE_URL,
    ): Provider {
        val effectiveBaseUrl = normalizeBaseUrl(baseUrl)
        return Provider(
            id = ZaiModels.PROVIDER_ID,
            name = NAME,
            baseUrl = effectiveBaseUrl,
            apiKeyResolver = apiKeyResolver,
            models = if (effectiveBaseUrl == ZaiModels.BASE_URL) {
                ZaiModels.ALL
            } else {
                ZaiModels.ALL.map { it.copy(baseUrl = effectiveBaseUrl) }
            },
            api = OpenAiCompletionsApi(transport, retry) as ChatApi,
        )
    }
}
