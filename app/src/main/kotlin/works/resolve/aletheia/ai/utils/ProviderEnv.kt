package works.resolve.aletheia.ai.utils

/**
 * Replaces `{NAME}` placeholders in a provider base URL with values from the
 * credential's provider env (mirroring pi's resolveCloudflareModel). Unknown
 * placeholders are left intact.
 */
fun substituteEnvPlaceholders(baseUrl: String, env: Map<String, String>): String {
    var result = baseUrl
    for ((name, value) in env) {
        result = result.replace("{$name}", value)
    }
    return result
}

/**
 * Returns the first `{NAME}` placeholder still present after substitution, or
 * null when none remain. Used as a defensive request-layer guard: an
 * unresolved placeholder means the credential is incomplete (e.g. missing
 * Cloudflare account/gateway ids), and the request must fail with a clear
 * error instead of hitting transport with a malformed URL.
 */
fun findUnresolvedPlaceholder(url: String): String? =
    PLACEHOLDER.find(url)?.value

private val PLACEHOLDER = Regex("\\{[A-Za-z_][A-Za-z0-9_]*}")
