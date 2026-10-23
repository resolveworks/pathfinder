package works.resolve.pathfinder.ai.utils

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The `User-Agent` sent to providers. Divergence from pi: the detailed form
 * keeps Pathfinder's existing `pathfinder (android <os.version>; <arch>)`
 * shape (the ai package is JDK-pure, so `android` is fixed and the release is
 * the JVM's `os.version`), and a version prefix appears only when
 * [ClientIdentity.version] is set. Pathfinder has no ai-package version
 * constant to default it to.
 */
sealed interface UserAgentMode {
    data object Short : UserAgentMode

    data object Detailed : UserAgentMode

    data class Custom(val custom: String) : UserAgentMode
}

/** Who requests are attributed to. [getAttributionHeaders] maps the fields to each provider's headers. */
data class ClientAttribution(
    /** App name: OpenRouter app title, NVIDIA invoke origin (first letter upper-cased), OpenCode client. */
    val name: String,
    /** App URL: OpenRouter `HTTP-Referer`. */
    val url: String,
    /** OpenRouter `X-OpenRouter-Categories`, comma-separated. */
    val categories: List<String>? = null
)

/**
 * Pathfinder's attribution. OpenRouter categories stay unset: its taxonomy has
 * no category for a native Android agent app, and `cli-agent` is specifically
 * a terminal-based coding assistant.
 */
val PATHFINDER_ATTRIBUTION: ClientAttribution = ClientAttribution(
    name = "pathfinder",
    url = "https://github.com/resolveworks/pathfinder"
)

/**
 * A merged attribution field. `null` in [ClientIdentity.attribution] skips
 * the field during merging (like undefined upstream); [Off] explicitly turns
 * attribution off, the Kotlin counterpart of upstream's explicit `null`.
 */
sealed interface ClientAttributionSetting {
    data object Off : ClientAttributionSetting

    data class On(val attribution: ClientAttribution) : ClientAttributionSetting
}

/**
 * How Pathfinder identifies the calling app to providers. Every field is
 * optional; absent fields use the ai layer's defaults: a detailed
 * `User-Agent` and no attribution headers. Plain data, so it can be persisted.
 */
data class ClientIdentity(
    /** Default: [UserAgentMode.Detailed]. */
    val userAgent: UserAgentMode? = null,
    /** Provider attribution headers. Null: none; [ClientAttributionSetting.Off] also turns off a merged identity's attribution. */
    val attribution: ClientAttributionSetting? = null,
    /** Version in the detailed `User-Agent`. Default: absent, keeping Pathfinder's unversioned shape. */
    val version: String? = null
)

/** The provider and endpoint a request goes to. */
interface ClientIdentityTarget {
    val provider: String
    val baseUrl: String
}

private const val NVIDIA_NIM_HOST = "integrate.api.nvidia.com"
private const val OPENCODE_HOST = "opencode.ai"

/** Merge identities field by field; later sources win and null fields are skipped. */
fun mergeClientIdentity(vararg sources: ClientIdentity?): ClientIdentity? {
    var merged: ClientIdentity? = null
    for (source in sources) {
        if (source == null) continue
        val current = merged ?: ClientIdentity()
        merged = current.copy(
            userAgent = source.userAgent ?: current.userAgent,
            attribution = source.attribution ?: current.attribution,
            version = source.version ?: current.version
        )
    }
    return merged
}

/** The `User-Agent` value of an identity. */
fun formatUserAgent(identity: ClientIdentity? = null): String {
    val mode = identity?.userAgent ?: UserAgentMode.Detailed
    return when (mode) {
        is UserAgentMode.Custom -> mode.custom

        UserAgentMode.Short -> "pathfinder"

        UserAgentMode.Detailed -> {
            val product = identity?.version?.let { "pathfinder/$it" } ?: "pathfinder"
            "$product (android ${System.getProperty(
                "os.version"
            )}; ${System.getProperty("os.arch")})"
        }
    }
}

private fun matchesHost(baseUrl: String, expectedHost: String): Boolean =
    baseUrl.toHttpUrlOrNull()?.host == expectedHost

private fun isOpenRouter(target: ClientIdentityTarget): Boolean =
    target.provider == "openrouter" || target.baseUrl.contains("openrouter.ai")

private fun isNvidiaNim(target: ClientIdentityTarget): Boolean =
    target.provider == "nvidia" || matchesHost(target.baseUrl, NVIDIA_NIM_HOST)

private fun isOpenCode(target: ClientIdentityTarget): Boolean = target.provider == "opencode" ||
    target.provider == "opencode-go" ||
    matchesHost(target.baseUrl, OPENCODE_HOST)

/** Provider attribution headers of an identity for a request target. Empty when attribution is off. */
fun getAttributionHeaders(
    target: ClientIdentityTarget,
    identity: ClientIdentity? = null
): Map<String, String> {
    val attribution = (identity?.attribution as? ClientAttributionSetting.On)?.attribution
        ?: return emptyMap()
    val name = attribution.name
    if (isOpenRouter(target)) {
        return buildMap {
            put("HTTP-Referer", attribution.url)
            put("X-OpenRouter-Title", name)
            if (!attribution.categories.isNullOrEmpty()) {
                put("X-OpenRouter-Categories", attribution.categories.joinToString(","))
            }
        }
    }
    // NVIDIA expects a capitalized origin, such as `Pathfinder`.
    if (isNvidiaNim(target)) {
        return mapOf("X-BILLING-INVOKE-ORIGIN" to name.replaceFirstChar { it.uppercase() })
    }
    if (isOpenCode(target)) return mapOf("x-opencode-client" to name)
    return emptyMap()
}

/**
 * OpenCode's per-conversation routing header for requests to OpenCode,
 * including custom providers pointing at it. Null without a session or for
 * other targets.
 */
fun getOpenCodeSessionHeaders(
    target: ClientIdentityTarget,
    sessionId: String?
): Map<String, String>? = sessionId?.takeIf { it.isNotEmpty() && isOpenCode(target) }
    ?.let { mapOf("x-opencode-session" to it) }

/**
 * Default headers an identity adds to a request: `User-Agent` and provider
 * attribution. APIs apply them below model, provider, and request headers, so
 * configured headers override them and null request headers remove them.
 */
fun getIdentityHeaders(
    target: ClientIdentityTarget,
    identity: ClientIdentity? = null
): Map<String, String> =
    mapOf("User-Agent" to formatUserAgent(identity)) + getAttributionHeaders(target, identity)
