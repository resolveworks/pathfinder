package works.resolve.aletheia.ai.auth

import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.providers.CatalogProvider
import works.resolve.aletheia.ai.providers.ProviderCatalog
import works.resolve.aletheia.ai.providers.filterCatalogModels
import kotlinx.coroutines.CancellationException

/**
 * One selectable auth method for a provider, without secret material — the
 * UI-facing projection of a [ProviderAuth] pair (pi surfaces these from
 * provider definitions when building the login menu).
 */
data class AuthMethodInfo(
    val type: AuthType,
    /** Display/login label: the catalog label (API key) or the OAuth name/loginLabel. */
    val label: String,
    /** Whether access through this method is backed by a provider subscription. */
    val isSubscription: Boolean,
) {
    override fun toString(): String = "AuthMethodInfo(type=$type, label=$label, isSubscription=$isSubscription)"
}

/**
 * Stored-credential status for a provider: distinguishes a stored API-key
 * credential from a stored OAuth credential without exposing any values —
 * the same shape pi's `CredentialInfo` gives status UI.
 */
data class AuthStatus(
    val providerId: String,
    /** The stored credential's type, or null when nothing is stored. */
    val storedType: CredentialType?,
) {
    override fun toString(): String = "AuthStatus(providerId=$providerId, storedType=$storedType)"
}

/**
 * Provider-neutral login/logout orchestration over the catalog, the OAuth
 * registry, and the credential store — the port of pi `Models.login`/`
 * Models.logout` (packages/ai/src/models.ts) plus the method enumeration a
 * native login screen needs instead of pi's terminal menu.
 *
 * Semantics mirrored from pi:
 * - Unknown provider throws [ModelsError] with code PROVIDER
 *   (`Unknown provider: <id>`).
 * - A method without a login (`ProviderAuth.apiKey` absent, or no registered
 *   OAuth flow) throws [ModelsError] with code AUTH
 *   (`<provider> does not support <type> login`).
 * - Login runs the method's flow first; the credential is persisted only
 *   after success, as an unconditional replacement of whatever is stored
 *   (pi's `credentials.modify` returns the login credential regardless of
 *   the current entry — switching account↔key replaces the type).
 * - Storage failures wrap in [ModelsError] (code AUTH) with pi's messages
 *   (`Credential store modify failed for <id>` / `... delete failed ...`).
 * - Cancellation ([CancellationException]) always propagates unwrapped.
 *
 * Listing methods and reading status are side-effect free: no token refresh
 * and no network calls.
 */
class ProviderAuthService(
    private val catalog: ProviderCatalog,
    private val registry: CatalogAuthRegistry,
    private val credentials: CredentialStore,
) {
    private fun requireProvider(providerId: String): CatalogProvider =
        catalog.getProvider(providerId)
            ?: throw ModelsError(ModelsErrorCode.PROVIDER, "Unknown provider: $providerId")

    /**
     * The provider's available auth methods with their [AuthType], label,
     * and subscription flag: API key iff the catalog carries key prompts
     * (pi leaves `apiKey` unset for OAuth-only providers), OAuth iff a flow
     * is registered. Never touches stored credentials or the network.
     */
    fun authMethods(providerId: String): List<AuthMethodInfo> {
        val provider = requireProvider(providerId)
        val methods = mutableListOf<AuthMethodInfo>()
        if (provider.auth.prompts.isNotEmpty()) {
            methods += AuthMethodInfo(AuthType.API_KEY, apiKeyLabel(provider), isSubscription = false)
        }
        registry.oauthAuth(provider)?.let { oauth ->
            methods += AuthMethodInfo(
                AuthType.OAUTH,
                oauth.loginLabel ?: oauth.name,
                oauth.isSubscription,
            )
        }
        return methods
    }

    /**
     * Stored-credential status without exposing values. Unknown providers
     * throw like pi's provider-scoped operations; a read failure wraps in
     * [ModelsError] (code AUTH, pi's resolution error message).
     */
    suspend fun authStatus(providerId: String): AuthStatus {
        requireProvider(providerId)
        val credential = try {
            credentials.read(providerId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ModelsError(
                ModelsErrorCode.AUTH,
                "Failed to read stored credential for provider '$providerId'",
                error,
            )
        }
        return AuthStatus(providerId, credential?.type)
    }

    /**
     * Side-effect-free configured check (pi's `getProviderAuthStatus`
     * configured flag, per this catalog/registry pair): an API-key credential
     * is configured iff every catalog prompt has a value; an OAuth
     * credential is configured iff a flow is registered (pi: a stored
     * credential without a matching handler resolves as unconfigured). No
     * token refresh and no network calls.
     */
    suspend fun isConfigured(providerId: String): Boolean {
        val provider = requireProvider(providerId)
        val credential = try {
            credentials.read(providerId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ModelsError(
                ModelsErrorCode.AUTH,
                "Failed to read stored credential for provider '$providerId'",
                error,
            )
        }
        return when (credential) {
            is ApiKeyCredential -> provider.isCredentialComplete(credential.key, credential.env)
            is OAuthCredential -> registry.oauthAuth(provider) != null
            null -> false
        }
    }

    /**
     * The provider's credential-filtered static models: pi's
     * `Models.getAvailable` per-provider slice
     * (`provider.filterModels?.(models, credential) ?? models`), reduced to
     * one provider. Reads the stored credential through [CredentialStore] and
     * applies the provider's filter (GitHub Copilot's `availableModelIds`;
     * see [works.resolve.aletheia.ai.providers.filterCatalogModels]) — static
     * catalog only, never dynamic discovery. Exposes model metadata only,
     * never credential values; a read failure throws [ModelsError] like
     * [isConfigured]. Unlike pi's getAvailable, the configured gate is not
     * applied here: callers compose it with their own per-provider
     * configured flags (the same aggregate rule pi applies).
     */
    suspend fun availableModels(providerId: String): List<Model> {
        val provider = requireProvider(providerId)
        val credential = try {
            credentials.read(providerId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ModelsError(
                ModelsErrorCode.AUTH,
                "Failed to read stored credential for provider '$providerId'",
                error,
            )
        }
        return filterCatalogModels(provider, credential)
    }

    /**
     * Run the selected method's login and persist its credential (pi's
     * `Models.login`). The stored credential is replaced atomically — only
     * after a successful login — via [CredentialStore.modify]. Returns the
     * non-secret [AuthStatus] of the newly stored credential.
     */
    suspend fun login(providerId: String, type: AuthType, interaction: AuthInteraction): AuthStatus {
        val provider = requireProvider(providerId)
        val credential = try {
            when (type) {
                AuthType.API_KEY -> runApiKeyLogin(provider, interaction)
                AuthType.OAUTH -> runOAuthLogin(provider, interaction)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: ModelsError) {
            throw error
        } catch (error: Exception) {
            throw ModelsError(ModelsErrorCode.AUTH, "Login failed for provider '${provider.id}'", error)
        }
        try {
            credentials.modify(providerId) { credential }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ModelsError(
                ModelsErrorCode.AUTH,
                "Credential store modify failed for $providerId",
                error,
            )
        }
        return AuthStatus(providerId, credential.type)
    }

    /** Remove the stored credential (pi's `Models.logout`). */
    suspend fun logout(providerId: String) {
        requireProvider(providerId)
        try {
            credentials.delete(providerId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw ModelsError(
                ModelsErrorCode.AUTH,
                "Credential store delete failed for $providerId",
                error,
            )
        }
    }

    private suspend fun runApiKeyLogin(
        provider: CatalogProvider,
        interaction: AuthInteraction,
    ): Credential {
        val auth = CatalogProviderAuth(provider, registry).apiKey
        val login = auth?.login
            ?: throw ModelsError(
                ModelsErrorCode.AUTH,
                "${provider.name} does not support ${AuthType.API_KEY} login",
            )
        return login(interaction)
    }

    private suspend fun runOAuthLogin(
        provider: CatalogProvider,
        interaction: AuthInteraction,
    ): Credential {
        val oauth = registry.oauthAuth(provider)
            ?: throw ModelsError(
                ModelsErrorCode.AUTH,
                "${provider.name} does not support ${AuthType.OAUTH} login",
            )
        return oauth.login(interaction)
    }

    private fun apiKeyLabel(provider: CatalogProvider): String =
        provider.auth.label ?: "${provider.name} API key"
}
