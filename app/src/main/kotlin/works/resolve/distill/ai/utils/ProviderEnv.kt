package works.resolve.distill.ai.utils

private const val CLOUDFLARE_ACCOUNT_ID = "CLOUDFLARE_ACCOUNT_ID"
private const val CLOUDFLARE_GATEWAY_ID = "CLOUDFLARE_GATEWAY_ID"

/**
 * Replaces `{CLOUDFLARE_ACCOUNT_ID}` / `{CLOUDFLARE_GATEWAY_ID}` placeholders
 * in a provider base URL with values from the credential's provider env,
 * mirroring pi's resolveCloudflareModel (cloudflare-stream.ts). Missing or
 * empty env values leave the placeholder intact, exactly like pi.
 */
fun resolveCloudflareBaseUrl(baseUrl: String, env: Map<String, String>): String =
    baseUrl
        .replace("{$CLOUDFLARE_ACCOUNT_ID}", env[CLOUDFLARE_ACCOUNT_ID] ?: "{$CLOUDFLARE_ACCOUNT_ID}")
        .replace("{$CLOUDFLARE_GATEWAY_ID}", env[CLOUDFLARE_GATEWAY_ID] ?: "{$CLOUDFLARE_GATEWAY_ID}")
