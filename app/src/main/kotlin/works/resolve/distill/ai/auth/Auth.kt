package works.resolve.distill.ai.auth

/**
 * Provider-neutral auth contracts ported from pi
 * `packages/ai/src/auth/types.ts`. No provider OAuth flow implementations
 * live here; orchestration (a later port of pi's `Models` auth layer) uses
 * these types.
 *
 * Cancellation: pi's `AbortSignal` parameters map to Kotlin structured
 * concurrency — every suspend function is cancellation-friendly, and flow
 * implementations must honor cancellation for blocking work.
 */

/**
 * Request auth for a single model request (pi `ModelAuth`). If a value cannot
 * be expressed as `apiKey`, `headers`, or `baseUrl`, it is provider config,
 * not auth. Header values are nullable (pi `ProviderHeaders`): null
 * suppresses a lower-level default header during merging.
 */
data class ModelAuth(
    val apiKey: String? = null,
    val headers: Map<String, String?> = emptyMap(),
    val baseUrl: String? = null,
)

/** Auth method tag (pi `AuthType`). */
enum class AuthType {
    API_KEY,
    OAUTH,
}

/** Result of resolving auth for a model (pi `AuthResult`). */
data class AuthResult(
    val auth: ModelAuth,
    /** Provider-scoped environment/config values resolved from credentials and ambient context. */
    val env: Map<String, String> = emptyMap(),
    /** Human-readable label for status UI: "ANTHROPIC_API_KEY", "OAuth", "~/.aws/credentials". */
    val source: String? = null,
)

/** Side-effect-free availability check result (pi `AuthCheck`). */
data class AuthCheck(
    val source: String? = null,
    val type: AuthType,
)

/** Environment access for auth resolution (pi `AuthContext`). Injectable for tests. */
interface AuthContext {
    suspend fun env(name: String): String?

    /** Check whether a file exists. Supports a leading `~`. */
    suspend fun fileExists(path: String): Boolean
}

/** Link attached to an info event (pi `AuthInfoLink`). */
data class AuthInfoLink(
    val url: String,
    val label: String? = null,
)

/**
 * Prompt shown to the user during login (pi `AuthPrompt`). Pi attaches an
 * out-of-band abort signal per prompt; in Kotlin, per-prompt cancellation is
 * expressed by cancelling the `prompt` coroutine (e.g. launching it in a
 * child scope and cancelling that child when an out-of-band event wins).
 */
sealed interface AuthPrompt {
    /** Text input. */
    data class Text(val message: String, val placeholder: String? = null) : AuthPrompt

    /** Secret input (never logged, never persisted outside the credential boundary). */
    data class Secret(val message: String, val placeholder: String? = null) : AuthPrompt

    /** Option selection; [prompt] returns the chosen option id. */
    data class Select(
        val message: String,
        val options: List<Option>,
    ) : AuthPrompt {
        data class Option(val id: String, val label: String, val description: String? = null)
    }

    /** Manual code entry, e.g. raced against an OAuth callback server. */
    data class ManualCode(val message: String, val placeholder: String? = null) : AuthPrompt
}

/** Progress/instruction events emitted during login (pi `AuthEvent`). */
sealed interface AuthEvent {
    data class Info(val message: String, val links: List<AuthInfoLink> = emptyList()) : AuthEvent

    data class AuthUrl(val url: String, val instructions: String? = null) : AuthEvent

    data class DeviceCode(
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int? = null,
        val expiresInSeconds: Int? = null,
    ) : AuthEvent

    data class Progress(val message: String) : AuthEvent
}

/**
 * Login interaction callbacks serving both api-key and OAuth flows
 * (pi `AuthInteraction`). `prompt` returns the entered/selected string
 * (`Select` returns the option id) and throws on cancel. Cancelling the
 * calling coroutine aborts the whole login flow.
 */
interface AuthInteraction {
    suspend fun prompt(prompt: AuthPrompt): String

    suspend fun notify(event: AuthEvent)
}

/**
 * Api-key auth (pi `ApiKeyAuth`): stored key/provider env plus ambient
 * sources (env vars, AWS profiles, ADC files). Ambient-only providers omit
 * [login].
 */
interface ApiKeyAuth {
    /** Display name, e.g. "Anthropic API key". */
    val name: String

    /** Interactive setup (prompt for key/provider env). Absent = ambient-only. */
    val login: (suspend (interaction: AuthInteraction) -> ApiKeyCredential)?
        get() = null

    /**
     * Optional side-effect-free availability check. Use this when [resolve]
     * may execute commands or perform other request-time work. Missing means
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
 * OAuth auth (pi `OAuthAuth`). The `refresh`/`toAuth` split lets the
 * orchestrator own the locked refresh pattern: [refresh] produces a
 * credential, [toAuth] derives request auth from whatever credential ends up
 * stored.
 */
interface OAuthAuth {
    /** Display name, e.g. "Anthropic (Claude Pro/Max)". */
    val name: String

    /** Whether access through this auth method is backed by a provider subscription. */
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
 * Provider auth (pi `ProviderAuth`). At least one of [apiKey]/[oauth] must be
 * present: even ambient-credential providers and keyless local servers
 * provide [apiKey] auth whose [ApiKeyAuth.resolve] reports whether the
 * provider is configured.
 */
interface ProviderAuth {
    val apiKey: ApiKeyAuth?
    val oauth: OAuthAuth?
}
