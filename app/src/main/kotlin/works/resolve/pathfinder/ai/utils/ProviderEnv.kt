package works.resolve.pathfinder.ai.utils

private const val CLOUDFLARE_ACCOUNT_ID = "CLOUDFLARE_ACCOUNT_ID"
private const val CLOUDFLARE_GATEWAY_ID = "CLOUDFLARE_GATEWAY_ID"

/** Missing env values leave the placeholders intact, exactly as in pi. */
fun resolveCloudflareBaseUrl(baseUrl: String, env: Map<String, String>): String =
    baseUrl
        .replace("{$CLOUDFLARE_ACCOUNT_ID}", env[CLOUDFLARE_ACCOUNT_ID] ?: "{$CLOUDFLARE_ACCOUNT_ID}")
        .replace("{$CLOUDFLARE_GATEWAY_ID}", env[CLOUDFLARE_GATEWAY_ID] ?: "{$CLOUDFLARE_GATEWAY_ID}")
