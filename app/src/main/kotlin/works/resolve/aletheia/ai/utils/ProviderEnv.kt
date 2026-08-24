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
