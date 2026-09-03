package works.resolve.pathfinder.ai.utils

private const val CLOUDFLARE_ACCOUNT_ID = "CLOUDFLARE_ACCOUNT_ID"
private const val CLOUDFLARE_GATEWAY_ID = "CLOUDFLARE_GATEWAY_ID"

/**
 * Materializes the Cloudflare account/gateway placeholders of an AI-Gateway
 * base URL (the URL constants in pi `api/cloudflare.ts`) from the resolved
 * provider env. Missing env values leave the placeholders intact, exactly
 * as in pi.
 *
 * This is pi's `resolveCloudflareModel` (`providers/cloudflare-stream.ts`),
 * which upstream applies by wrapping every Cloudflare provider's streams
 * (`cloudflareStreams`). Cloudflare providers are deliberately unported
 * here, so the substitution is applied inline at the adapter call site
 * instead — a model on the OpenAI-completions API may point at an AI
 * Gateway URL. Pi's `utils/provider-env.ts` (`getProviderEnvValue`) is not
 * ported at all (no ambient env on Android); this file has no upstream
 * twin.
 */
fun resolveCloudflareBaseUrl(baseUrl: String, env: Map<String, String>): String =
    baseUrl
        .replace("{$CLOUDFLARE_ACCOUNT_ID}", env[CLOUDFLARE_ACCOUNT_ID] ?: "{$CLOUDFLARE_ACCOUNT_ID}")
        .replace("{$CLOUDFLARE_GATEWAY_ID}", env[CLOUDFLARE_GATEWAY_ID] ?: "{$CLOUDFLARE_GATEWAY_ID}")
