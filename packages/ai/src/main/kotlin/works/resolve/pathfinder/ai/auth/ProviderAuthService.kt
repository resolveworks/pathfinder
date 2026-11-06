package works.resolve.pathfinder.ai.auth

import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.providers.CatalogProvider
import works.resolve.pathfinder.ai.providers.ProviderCatalog

/** One selectable auth method for a provider; never carries secret material. */
data class AuthMethodInfo(val type: AuthType, val label: String, val isSubscription: Boolean) {
    override fun toString(): String =
        "AuthMethodInfo(type=$type, label=$label, isSubscription=$isSubscription)"
}

/** Stored-credential status for a provider; never exposes credential values. */
data class AuthStatus(val providerId: String, val storedType: CredentialType?) {
    override fun toString(): String = "AuthStatus(providerId=$providerId, storedType=$storedType)"
}

/**
 * App-facing provider-auth facade over [Models]. [authMethods] is the
 * UI-only projection of the generated catalog (where pi renders a terminal
 * menu); every credential operation delegates to the models runtime, so
 * login/logout and resolution share one credential store and one auth
 * semantics. Cancellation always propagates unwrapped.
 */
class ProviderAuthService(private val catalog: ProviderCatalog, private val models: Models) {
    private fun requireProvider(providerId: String): CatalogProvider =
        catalog.getProvider(providerId)
            ?: throw ModelsError(ModelsErrorCode.PROVIDER, "Unknown provider: $providerId")

    fun authMethods(providerId: String): List<AuthMethodInfo> {
        val provider = requireProvider(providerId)
        val methods = mutableListOf<AuthMethodInfo>()
        if (provider.auth.prompts.isNotEmpty()) {
            methods +=
                AuthMethodInfo(AuthType.API_KEY, apiKeyLabel(provider), isSubscription = false)
        }
        models.getProvider(providerId)?.auth?.oauth?.let { oauth ->
            methods += AuthMethodInfo(
                AuthType.OAUTH,
                oauth.loginLabel ?: oauth.name,
                oauth.isSubscription
            )
        }
        return methods
    }

    /** Stored/resolved credential type, without resolving or refreshing OAuth. */
    suspend fun authStatus(providerId: String): AuthStatus {
        requireProvider(providerId)
        val type = when (models.checkAuth(providerId)?.type) {
            AuthType.API_KEY -> CredentialType.API_KEY
            AuthType.OAUTH -> CredentialType.OAUTH
            null -> null
        }
        return AuthStatus(providerId, type)
    }

    /** Present stored credential, without resolving or refreshing it. */
    suspend fun isConfigured(providerId: String): Boolean {
        requireProvider(providerId)
        return models.hasConfiguredAuth(providerId)
    }

    /** The provider's authenticated, credential-filtered models (pi's getAvailable). */
    suspend fun availableModels(providerId: String): List<Model> {
        requireProvider(providerId)
        return models.getAvailable(providerId)
    }

    suspend fun login(
        providerId: String,
        type: AuthType,
        interaction: AuthInteraction,
        options: LoginOptions? = null
    ): AuthStatus {
        requireProvider(providerId)
        val credential = models.login(providerId, type, interaction, options)
        return AuthStatus(providerId, credential.type)
    }

    suspend fun logout(providerId: String) {
        requireProvider(providerId)
        models.logout(providerId)
    }

    /** Await in-flight credential writes before the app process may die. */
    suspend fun settleCredentialWrites() {
        models.settleCredentialWrites()
    }

    private fun apiKeyLabel(provider: CatalogProvider): String =
        provider.auth.label ?: "${provider.name} API key"
}
