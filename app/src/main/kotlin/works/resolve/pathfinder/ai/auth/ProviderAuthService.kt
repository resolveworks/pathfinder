package works.resolve.pathfinder.ai.auth

import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.providers.filterCatalogModels
import kotlinx.coroutines.CancellationException

/** One selectable auth method for a provider; never carries secret material. */
data class AuthMethodInfo(
    val type: AuthType,
    val label: String,
    val isSubscription: Boolean,
) {
    override fun toString(): String = "AuthMethodInfo(type=$type, label=$label, isSubscription=$isSubscription)"
}

/** Stored-credential status for a provider; never exposes credential values. */
data class AuthStatus(
    val providerId: String,
    val storedType: CredentialType?,
) {
    override fun toString(): String = "AuthStatus(providerId=$providerId, storedType=$storedType)"
}

/**
 * Provider-neutral login/logout orchestration over the catalog, the OAuth
 * registry, and the credential store; [authMethods] enumerates the methods a
 * native login screen offers where pi renders a terminal menu.
 *
 * [CancellationException] always propagates unwrapped. Listing methods and
 * reading status are side-effect free: no token refresh and no network
 * calls.
 */
class ProviderAuthService(
    private val catalog: ProviderCatalog,
    private val registry: CatalogAuthRegistry,
    private val credentials: CredentialStore,
) {
    private fun requireProvider(providerId: String): CatalogProvider =
        catalog.getProvider(providerId)
            ?: throw ModelsError(ModelsErrorCode.PROVIDER, "Unknown provider: $providerId")

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
     * The provider's credential-filtered models from the static catalog —
     * never dynamic discovery, and never credential values. Unlike pi's
     * getAvailable, the configured gate is not applied here; callers compose
     * it with their own per-provider configured flags.
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
     * The credential is persisted only after a successful login, replacing
     * whatever was stored. Login diagnostics (`pf.auth.login`) live at the
     * Android caller boundary, not here — pi's login takes no telemetry
     * context.
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
