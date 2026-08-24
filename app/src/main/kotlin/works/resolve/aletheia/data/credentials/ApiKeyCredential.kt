package works.resolve.aletheia.data.credentials

/**
 * Pi's credential shape (minus the `type` tag): an API key plus optional
 * provider environment values (e.g. Cloudflare account/gateway ids) that are
 * substituted into provider base URLs at resolve time.
 *
 * `toString` never includes the key.
 */
class ApiKeyCredential(
    val key: String,
    val env: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean =
        other is ApiKeyCredential && other.key == key && other.env == env

    override fun hashCode(): Int = 31 * key.hashCode() + env.hashCode()

    override fun toString(): String =
        "ApiKeyCredential(key=<redacted>, env=${env.keys})"
}
