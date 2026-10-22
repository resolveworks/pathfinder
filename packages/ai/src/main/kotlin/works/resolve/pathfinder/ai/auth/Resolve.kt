package works.resolve.pathfinder.ai.auth

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import works.resolve.pathfinder.ai.utils.trimJsWhitespace

/**
 * A stored credential owns the provider: ambient/env is consulted only when
 * nothing is stored. No silent env fallback after a failed refresh or for a
 * credential type without a matching handler.
 *
 * Cancellation: caller cancellation ([CancellationException]) always
 * propagates unwrapped; only genuine failures are wrapped in [ModelsError].
 */

enum class ModelsErrorCode {
    MODEL_SOURCE,
    MODEL_VALIDATION,
    PROVIDER,
    STREAM,
    AUTH,
    OAUTH
}

/** Callers surface `message` only, so the underlying reason stays in it. */
class ModelsError(val code: ModelsErrorCode, message: String, cause: Throwable? = null) :
    Exception(withCauseDetail(message, cause), cause) {
    constructor(code: ModelsErrorCode, message: String) : this(code, message, null)

    companion object {
        private fun withCauseDetail(message: String, cause: Throwable?): String {
            val detail = cause?.message?.let(::trimJsWhitespace).orEmpty()
            if (detail.isEmpty() || message.contains(detail)) return message
            return "$message: $detail"
        }
    }
}

data class AuthResolutionOverrides(
    val apiKey: String? = null,
    val env: Map<String, String> = emptyMap(),
    val minOAuthValidityMs: Long? = null
)

suspend fun resolveProviderAuth(
    provider: AuthProviderRef,
    credentials: CredentialStore,
    authContext: AuthContext,
    overrides: AuthResolutionOverrides? = null,
    clock: Clock = Clock.System
): AuthResult? {
    val requestAuthContext =
        if (overrides?.env?.isNotEmpty() ==
            true
        ) {
            overlayEnvAuthContext(authContext, overrides.env)
        } else {
            authContext
        }

    val apiKeyAuth = provider.auth.apiKey
    val overrideKey = overrides?.apiKey
    if (overrides != null && overrideKey != null && apiKeyAuth != null) {
        return resolveApiKey(
            requestAuthContext,
            apiKeyAuth,
            provider.id,
            ApiKeyCredential(key = overrideKey, env = overrides.env)
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
                clock
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

interface AuthProviderRef {
    val id: String
    val auth: ProviderAuth
}

private fun overlayEnvAuthContext(base: AuthContext, env: Map<String, String>): AuthContext =
    object : AuthContext {
        // An empty override falls through to the ambient value instead of
        // suppressing it.
        override suspend fun env(name: String): String? =
            env[name]?.takeIf { it.isNotEmpty() } ?: base.env(name)

        override suspend fun fileExists(path: String): Boolean = base.fileExists(path)
    }

private const val DEFAULT_OAUTH_MINIMUM_VALIDITY_MS = 5 * 60 * 1000L
private const val DEFAULT_OAUTH_REFRESH_TIMEOUT_MS = 15_000L

/**
 * Refresh a stored OAuth credential under the credential-store lock and persist
 * the result before the lock is released. [needsRefresh] is re-checked under
 * the lock, so concurrent callers refresh only once.
 *
 * Caller cancellation stops only the wait for the lock. Once a refresh starts,
 * the provider may already have rotated the refresh token, so the refresh and
 * its persistence ignore caller cancellation and are bounded only by the
 * refresh timeout; otherwise a cancelled caller could discard the only valid
 * refresh token. The caller still returns promptly on cancellation.
 *
 * Returns the stored OAuth credential after the operation, or null when the
 * provider no longer has an OAuth credential.
 */
internal suspend fun refreshStoredOAuthCredential(
    credentials: CredentialStore,
    providerId: String,
    oauth: OAuthAuth,
    needsRefresh: (OAuthCredential) -> Boolean
): OAuthCredential? {
    val callerContext = currentCoroutineContext()
    val refreshStarted = AtomicBoolean(false)
    // Detached from the caller's Job: caller cancellation must not cancel a
    // refresh that has already started.
    val scope = CoroutineScope(callerContext + Job())
    val deferred = scope.async {
        credentials.modify(providerId) { current ->
            // logged out meanwhile
            val currentOAuth = current as? OAuthCredential ?: return@modify null
            if (!needsRefresh(currentOAuth)) {
                return@modify null // another request refreshed
            }
            refreshStarted.set(true)
            val refreshed = try {
                withTimeoutOrNull(DEFAULT_OAUTH_REFRESH_TIMEOUT_MS) {
                    oauth.refresh(currentOAuth)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw ModelsError(
                    ModelsErrorCode.OAUTH,
                    "OAuth refresh failed for $providerId",
                    error
                )
            } ?: throw ModelsError(
                // pi's refresh signal is AbortSignal.any([caller signal,
                // timeout]): its timeout surfaces as a TimeoutError cause
                // under the same "OAuth refresh failed" message as any other
                // refresh failure.
                ModelsErrorCode.OAUTH,
                "OAuth refresh failed for $providerId",
                TimeoutException("Timed out waiting for ${DEFAULT_OAUTH_REFRESH_TIMEOUT_MS} ms")
            )
            refreshed
        }
    }

    try {
        val post = deferred.await()
        return post as? OAuthCredential
    } catch (error: ModelsError) {
        throw error
    } catch (error: CancellationException) {
        // Cancellation before the refresh starts releases the lock wait;
        // after it starts the detached job completes and persists.
        if (!refreshStarted.get()) deferred.cancel()
        throw error
    } catch (error: Throwable) {
        throw ModelsError(
            ModelsErrorCode.AUTH,
            "Credential store modify failed for $providerId",
            error
        )
    }
}

/**
 * OAuth resolution with double-checked locking: tokens with less than five
 * minutes remaining are refreshed through [refreshStoredOAuthCredential].
 */
private suspend fun resolveStoredOAuth(
    credentials: CredentialStore,
    providerId: String,
    oauth: OAuthAuth,
    stored: OAuthCredential,
    minOAuthValidityMs: Long?,
    clock: Clock = Clock.System
): AuthResult? {
    val minimumValidityMs = maxOf(DEFAULT_OAUTH_MINIMUM_VALIDITY_MS, minOAuthValidityMs ?: 0)
    fun expiresSoon(credential: OAuthCredential): Boolean =
        clock.now().toEpochMilliseconds() + minimumValidityMs >= credential.expires
    var credential = stored

    if (expiresSoon(credential)) {
        // Optimistic check said expired; the authoritative check runs under the lock.
        val post = refreshStoredOAuthCredential(
            credentials,
            providerId,
            oauth,
            ::expiresSoon
        )
        if (post == null) return null // logged out meanwhile
        credential = post
        // The normal five-minute window triggers a refresh but does not impose a
        // provider contract. Explicit callers do require the requested minimum
        // after the refresh.
        if (minOAuthValidityMs != null && expiresSoon(credential)) {
            throw ModelsError(
                ModelsErrorCode.OAUTH,
                "OAuth refresh returned a token that expires too soon for $providerId"
            )
        }
    }

    return try {
        AuthResult(auth = oauth.toAuth(credential), source = "OAuth")
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw ModelsError(
            ModelsErrorCode.OAUTH,
            "OAuth auth derivation failed for $providerId",
            error
        )
    }
}

private suspend fun resolveApiKey(
    authContext: AuthContext,
    apiKey: ApiKeyAuth,
    providerId: String,
    credential: ApiKeyCredential?
): AuthResult? = try {
    apiKey.resolve(authContext, credential)
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    throw ModelsError(
        ModelsErrorCode.AUTH,
        "API key auth failed for provider $providerId",
        error
    )
}

private suspend fun readCredential(credentials: CredentialStore, providerId: String): Credential? =
    try {
        credentials.read(providerId)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw ModelsError(
            ModelsErrorCode.AUTH,
            "Credential store read failed for $providerId",
            error
        )
    }
