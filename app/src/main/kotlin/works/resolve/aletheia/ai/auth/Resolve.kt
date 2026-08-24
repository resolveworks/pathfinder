package works.resolve.aletheia.ai.auth

/**
 * Ported from pi `packages/ai/src/auth/resolve.ts`: auth resolution shared by
 * model collections. A stored credential owns the provider: ambient/env is
 * consulted only when nothing is stored. No silent env fallback after a
 * failed refresh or for a credential type without a matching handler.
 *
 * Cancellation: pi races every operation against an `AbortSignal`; here the
 * suspend call's structured cancellation plays that role, so every await
 * point (including the refresh timeout) is cancellation-friendly.
 */

/** Pi `ModelsErrorCode` (auth-adjacent subset kept for future orchestrators). */
enum class ModelsErrorCode {
    MODEL_SOURCE,
    MODEL_VALIDATION,
    PROVIDER,
    STREAM,
    AUTH,
    OAUTH,
}

/** Pi `ModelsError`: callers surface `message` only, so the underlying reason stays in it. */
class ModelsError(
    val code: ModelsErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(withCauseDetail(message, cause), cause) {
    constructor(code: ModelsErrorCode, message: String) : this(code, message, null)

    companion object {
        /** Pi `withCauseDetail`: keep the underlying reason in the message. */
        private fun withCauseDetail(message: String, cause: Throwable?): String {
            val detail = cause?.message?.trim().orEmpty()
            if (detail.isEmpty() || message.contains(detail)) return message
            return "$message: $detail"
        }
    }
}

/** Pi `AuthResolutionOverrides`. */
data class AuthResolutionOverrides(
    val apiKey: String? = null,
    val env: Map<String, String> = emptyMap(),
    /** Require this much remaining OAuth-token validity; defaults to five minutes. */
    val minOAuthValidityMs: Long? = null,
)

suspend fun resolveProviderAuth(
    provider: AuthProviderRef,
    credentials: CredentialStore,
    authContext: AuthContext,
    overrides: AuthResolutionOverrides? = null,
): AuthResult? {
    val requestAuthContext =
        if (overrides?.env?.isNotEmpty() == true) overlayEnvAuthContext(authContext, overrides.env) else authContext

    val apiKeyAuth = provider.auth.apiKey
    val overrideKey = overrides?.apiKey
    if (overrides != null && overrideKey != null && apiKeyAuth != null) {
        return resolveApiKey(
            requestAuthContext,
            apiKeyAuth,
            provider.id,
            ApiKeyCredential(key = overrideKey, env = overrides.env),
        )
    }

    val stored = readCredential(credentials, provider.id)
    if (stored != null) {
        val oauthAuth = provider.auth.oauth
        if (stored is OAuthCredential && oauthAuth != null) {
            return resolveStoredOAuth(
                credentials,
                provider.id,
                oauthAuth,
                stored,
                overrides?.minOAuthValidityMs,
            )
        }
        if (stored is ApiKeyCredential && apiKeyAuth != null) {
            val credential =
                if (overrides?.env?.isNotEmpty() == true) {
                    stored.copy(env = stored.env + overrides.env)
                } else {
                    stored
                }
            return resolveApiKey(requestAuthContext, apiKeyAuth, provider.id, credential)
        }
        return null
    }

    // Ambient (env vars, AWS profiles, ADC files).
    return apiKeyAuth
        ?.let { resolveApiKey(requestAuthContext, it, provider.id, null) }
}

/** Minimal provider reference (pi resolves over `{ id, auth }`). */
interface AuthProviderRef {
    val id: String
    val auth: ProviderAuth
}

private fun overlayEnvAuthContext(base: AuthContext, env: Map<String, String>): AuthContext =
    object : AuthContext {
        override suspend fun env(name: String): String? = env[name] ?: base.env(name)

        override suspend fun fileExists(path: String): Boolean = base.fileExists(path)
    }

private const val DEFAULT_OAUTH_MINIMUM_VALIDITY_MS = 5 * 60 * 1000L
private const val DEFAULT_OAUTH_REFRESH_TIMEOUT_MS = 15_000L

/**
 * OAuth resolution with double-checked locking (pi `resolveStoredOAuth`):
 * tokens with less than five minutes remaining lock, re-check expiry under
 * the lock, refresh once globally, and persist the rotated credential before
 * release.
 *
 * Port note: pi bounds the refresh network call with a 15s `AbortSignal`
 * timeout; here the caller's cancellation applies. A withTimeout wrapper can
 * be added when the refresh implementations are ported.
 */
private suspend fun resolveStoredOAuth(
    credentials: CredentialStore,
    providerId: String,
    oauth: OAuthAuth,
    stored: OAuthCredential,
    minOAuthValidityMs: Long?,
): AuthResult? {
    val minimumValidityMs = maxOf(DEFAULT_OAUTH_MINIMUM_VALIDITY_MS, minOAuthValidityMs ?: 0)
    fun expiresSoon(credential: OAuthCredential): Boolean =
        System.currentTimeMillis() + minimumValidityMs >= credential.expires
    var credential = stored

    if (expiresSoon(credential)) {
        // Optimistic check said expired; the authoritative check runs under the lock.
        val post = try {
            credentials.modify(providerId) { current ->
                val currentOAuth = current as? OAuthCredential ?: return@modify null // logged out meanwhile
                if (!expiresSoon(currentOAuth)) return@modify null // another request refreshed
                try {
                    oauth.refresh(currentOAuth)
                } catch (error: Throwable) {
                    throw ModelsError(ModelsErrorCode.OAUTH, "OAuth refresh failed for $providerId", error)
                }
            }
        } catch (error: ModelsError) {
            throw error
        } catch (error: Throwable) {
            throw ModelsError(ModelsErrorCode.AUTH, "Credential store modify failed for $providerId", error)
        }
        if (post !is OAuthCredential) return null // logged out meanwhile
        credential = post
        // The normal five-minute window triggers a refresh but does not impose a
        // provider contract. Explicit callers do require the requested minimum
        // after the refresh.
        if (minOAuthValidityMs != null && expiresSoon(credential)) {
            throw ModelsError(ModelsErrorCode.OAUTH, "OAuth refresh returned a token that expires too soon for $providerId")
        }
    }

    return try {
        AuthResult(auth = oauth.toAuth(credential), source = "OAuth")
    } catch (error: Throwable) {
        throw ModelsError(ModelsErrorCode.OAUTH, "OAuth auth derivation failed for $providerId", error)
    }
}

private suspend fun resolveApiKey(
    authContext: AuthContext,
    apiKey: ApiKeyAuth,
    providerId: String,
    credential: ApiKeyCredential?,
): AuthResult? =
    try {
        apiKey.resolve(authContext, credential)
    } catch (error: Throwable) {
        throw ModelsError(ModelsErrorCode.AUTH, "API key auth failed for provider $providerId", error)
    }

private suspend fun readCredential(
    credentials: CredentialStore,
    providerId: String,
): Credential? =
    try {
        credentials.read(providerId)
    } catch (error: Throwable) {
        throw ModelsError(ModelsErrorCode.AUTH, "Credential store read failed for $providerId", error)
    }
