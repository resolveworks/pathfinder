package com.aletheia.ai.providers

import com.aletheia.ai.api.ChatApi
import com.aletheia.ai.models.Provider
import com.aletheia.ai.transport.HttpStreamingTransport
import com.aletheia.ai.api.OpenAiCompletionsApi
import com.aletheia.ai.utils.ProviderRetry

/**
 * The global Z.AI provider (api.z.ai coding plan endpoint), ported from pi's
 * providers/zai.ts: identity, base URL, env-style API-key auth, the coding
 * plan model catalog, and the reusable openai-completions API.
 */
object ZaiProvider {

    const val NAME = "Z.AI"
    const val API_KEY_ENV_VAR = "ZAI_API_KEY"

    fun create(
        transport: HttpStreamingTransport,
        retry: ProviderRetry = ProviderRetry(),
        apiKeyResolver: (suspend () -> String?)? = null,
    ): Provider = Provider(
        id = ZaiModels.PROVIDER_ID,
        name = NAME,
        baseUrl = ZaiModels.BASE_URL,
        apiKeyResolver = apiKeyResolver,
        models = ZaiModels.ALL,
        api = OpenAiCompletionsApi(transport, retry) as ChatApi,
    )
}
