package works.resolve.pathfinder.ai.auth

import kotlinx.serialization.json.JsonElement

/*
 * pi's `AbortSignal` parameters map to Kotlin structured concurrency: every
 * suspend function is cancellation-friendly, and flow implementations must
 * honor cancellation for blocking work.
 */

/**
 * Request auth for a single model request. If a value cannot be expressed as
 * `apiKey`, `headers`, or `baseUrl`, it is provider config, not auth. A null
 * header value suppresses a lower-level default header during merging.
 */
data class ModelAuth(
    val apiKey: String? = null,
    val headers: Map<String, String?> = emptyMap(),
    val baseUrl: String? = null
)

/**
 * Stored api-key credential. `env` holds provider-scoped environment/config
 * values such as Cloudflare account or gateway ids; `key` is null for
 * env-only credentials.
 */
data class ApiKeyCredential(val key: String? = null, val env: Map<String, String> = emptyMap()) :
    Credential {
    override val type: CredentialType = CredentialType.API_KEY

    override fun toString(): String = "ApiKeyCredential(key=<redacted>, env=${env.keys})"
}

/**
 * Stored canonical OAuth credential: `access`, `refresh`, `expires` (epoch
 * milliseconds) plus provider-specific extra fields, preserved verbatim in
 * [extras] so unknown JSON round trips safely. Pi models extras as an index
 * signature; the sealed type keeps them in an explicit map of raw JSON
 * elements.
 */
data class OAuthCredential(
    val access: String,
    val refresh: String,
    val expires: Long,
    val extras: Map<String, JsonElement> = emptyMap()
) : Credential {
    init {
        // Extra fields are written verbatim next to the canonical fields;
        // reserved names would corrupt the record on encode.
        val reserved = extras.keys intersect RESERVED_FIELDS
        require(reserved.isEmpty()) { "OAuth extra fields must not use reserved names: $reserved" }
    }

    override val type: CredentialType = CredentialType.OAUTH

    override fun toString(): String =
        "OAuthCredential(access=<redacted>, refresh=<redacted>, expires=$expires, extras=${extras.keys})"

    companion object {
        val RESERVED_FIELDS: Set<String> = setOf("type", "access", "refresh", "expires")
    }
}

/**
 * One type-tagged credential per provider — the shape of pi's `auth.json`.
 *
 * `toString` of every subtype redacts secret material; never log credentials.
 */
sealed interface Credential {
    val type: CredentialType
}

enum class CredentialType {
    API_KEY,
    OAUTH
}

/** Non-secret credential metadata for account/status enumeration. */
data class CredentialInfo(val providerId: String, val type: CredentialType)

/** Environment access for auth resolution; injectable for tests. */
interface AuthContext {
    suspend fun env(name: String): String?

    /** Check whether a file exists. Supports a leading `~`. */
    suspend fun fileExists(path: String): Boolean
}

data class AuthResult(
    val auth: ModelAuth,
    /** Provider-scoped environment/config values resolved from credentials and ambient context. */
    val env: Map<String, String> = emptyMap(),
    /** Human-readable label for status UI: "ANTHROPIC_API_KEY", "OAuth", "~/.aws/credentials". */
    val source: String? = null
)

data class AuthCheck(val source: String? = null, val type: AuthType)

enum class AuthType(val wire: String) {
    API_KEY("api_key"),
    OAUTH("oauth")
}

/**
 * Prompt shown to the user during login. pi's per-prompt abort signal is
 * expressed by cancelling the `prompt` coroutine (e.g. cancelling the child
 * scope when an out-of-band event wins).
 */
sealed interface AuthPrompt {
    data class Text(val message: String, val placeholder: String? = null) : AuthPrompt

    /** Secret input (never logged, never persisted outside the credential boundary). */
    data class Secret(val message: String, val placeholder: String? = null) : AuthPrompt

    data class Select(val message: String, val options: List<Option>) : AuthPrompt {
        data class Option(val id: String, val label: String, val description: String? = null)
    }

    /** Manual code entry, e.g. raced against an OAuth callback server. */
    data class ManualCode(val message: String, val placeholder: String? = null) : AuthPrompt
}

data class AuthInfoLink(val url: String, val label: String? = null)

sealed interface AuthEvent {
    data class Info(val message: String, val links: List<AuthInfoLink> = emptyList()) : AuthEvent

    data class AuthUrl(val url: String, val instructions: String? = null) : AuthEvent

    data class DeviceCode(
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int? = null,
        val expiresInSeconds: Int? = null
    ) : AuthEvent

    data class Progress(val message: String) : AuthEvent
}

/**
 * Login interaction callbacks serving both api-key and OAuth flows. `prompt`
 * returns the entered/selected string (`Select` returns the option id) and
 * throws on cancel. Cancelling the calling coroutine aborts the whole login
 * flow.
 */
interface AuthInteraction {
    suspend fun prompt(prompt: AuthPrompt): String

    suspend fun notify(event: AuthEvent)
}

/**
 * Api-key auth: stored key/provider env plus ambient sources (env vars, AWS
 * profiles, ADC files).
 */
interface ApiKeyAuth {
    /** Display name, e.g. "Anthropic API key". */
    val name: String

    /** Interactive setup (prompt for key/provider env). Absent = ambient-only. */
    val login: (suspend (interaction: AuthInteraction) -> ApiKeyCredential)?
        get() = null

    /**
     * Side-effect-free availability check. Use this when [resolve] may
     * execute commands or perform other request-time work. Missing means
     * availability is checked by resolving auth.
     */
    val check: (suspend (ctx: AuthContext, credential: ApiKeyCredential?) -> AuthCheck?)?
        get() = null

    /**
     * Resolve auth from the stored credential and/or ambient sources, merging
     * per field (`credential.key ?? env("...")`, `credential.env[name] ??
     * env("...")`). Null = not configured. Resolution is provider-scoped;
     * model-specific endpoint preparation happens after auth has been
     * resolved.
     */
    suspend fun resolve(ctx: AuthContext, credential: ApiKeyCredential?): AuthResult?
}

/**
 * OAuth auth. The `refresh`/`toAuth` split lets the orchestrator own the
 * locked refresh pattern: [refresh] produces a credential, [toAuth] derives
 * request auth from whatever credential ends up stored.
 */
interface OAuthAuth {
    /** Display name, e.g. "Anthropic (Claude Pro/Max)". */
    val name: String

    val isSubscription: Boolean
        get() = false

    /** Selector label for the OAuth login option, e.g. "Sign in with SuperGrok or X Premium". */
    val loginLabel: String? get() = null

    suspend fun login(interaction: AuthInteraction): OAuthCredential

    /**
     * Exchange the refresh token. Network call; throws on failure
     * (invalid_grant etc.). Run under the credential-store lock.
     */
    suspend fun refresh(credential: OAuthCredential): OAuthCredential

    /**
     * Side-effect-free derivation of request auth from a valid credential.
     * Covers per-credential baseUrl (GitHub Copilot).
     */
    suspend fun toAuth(credential: OAuthCredential): ModelAuth
}

/**
 * Provider auth. At least one of [apiKey]/[oauth] must be present: even
 * ambient-credential providers and keyless local servers provide [apiKey]
 * auth whose [ApiKeyAuth.resolve] reports whether the provider is
 * configured.
 */
interface ProviderAuth {
    val apiKey: ApiKeyAuth?
    val oauth: OAuthAuth?
}
