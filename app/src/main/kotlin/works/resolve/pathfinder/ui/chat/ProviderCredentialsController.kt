package works.resolve.pathfinder.ui.chat

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthPrompt as AuthInteractionPrompt
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.ai.auth.CredentialType
import works.resolve.pathfinder.ai.auth.ModelsError
import works.resolve.pathfinder.ai.auth.ProviderAuthService
import works.resolve.pathfinder.ai.providers.AuthPrompt
import works.resolve.pathfinder.ai.providers.ProviderCatalog

/**
 * LLM-provider credential ownership: the API-key form save/remove and the
 * credential-derived provider/model surfaces. Runs in [scope]; user-facing
 * failures surface through [onError] as static, secret-free strings,
 * absorbed degradations only log. OAuth/account logins live in
 * [ProviderLoginController]; [isLoginBusy] keeps a key save from racing one.
 */
internal class ProviderCredentialsController(
    private val scope: CoroutineScope,
    private val app: Application,
    private val catalog: ProviderCatalog,
    private val authService: ProviderAuthService,
    /** True while an interactive login is in flight (the login controller's busy flag); a key save must not race it. */
    private val isLoginBusy: () -> Boolean,
    /** Shared post-login success path in the ViewModel (refreshes, then completes first-run configuration). */
    private val onCredentialStored: suspend () -> Unit,
    private val onError: (message: String, cause: Throwable?) -> Unit
) {
    /** Live credential-derived provider and model surfaces. */
    data class State(
        val providerOptions: List<ProviderOption> = emptyList(),
        val modelOptions: List<ModelOption> = emptyList()
    )

    private val _state = MutableStateFlow(State())

    /** The source of truth [ChatUiState.providerOptions]/[ChatUiState.modelOptions] mirror. */
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Credential-filtered catalog models behind [State.modelOptions],
     * refreshed by [refresh]; model-scope resolution runs against this
     * snapshot (pi's getAvailableSnapshot analog).
     */
    var availableModels: List<Model> = emptyList()
        private set

    /**
     * Saves a fresh credential for [providerId]: every prompt's input is its
     * value and a complete save replaces the stored credential wholesale —
     * values are never merged with what was stored. Blank/missing required
     * values are rejected with an error naming the missing prompts.
     *
     * Not busy-rejected: the agent resolves the credential once per request,
     * so changing it mid-stream only affects the next request.
     */
    fun saveCredential(providerId: String, apiKeyInput: String, envInputs: Map<String, String>) {
        scope.launch {
            val provider = catalog.getProvider(providerId) ?: run {
                onError(app.getString(R.string.error_unknown_provider), null)
                return@launch
            }
            // A key save must not race an in-flight account login.
            if (isLoginBusy()) {
                onError(app.getString(R.string.error_auth_in_progress), null)
                return@launch
            }
            // The first auth prompt is the API key; every other prompt fills
            // its env slot. An incomplete credential is rejected rather than
            // persisted.
            val newKey = apiKeyInput.trim()
            val env = buildMap<String, String> {
                provider.auth.prompts.drop(1).forEach { prompt ->
                    val value = envInputs[prompt.envKey]?.trim()
                    if (!value.isNullOrEmpty()) put(prompt.envKey, value)
                }
            }
            val missing = provider.missingAuthPrompts(newKey.ifEmpty { null }, env)
            if (newKey.isEmpty() || missing.isNotEmpty()) {
                onError(missingCredentialError(missing), null)
                return@launch
            }
            // The form's values answer the catalog's own prompts (in order)
            // through an in-memory interaction; the answers live only here,
            // never in UI state.
            val answers = buildList {
                provider.auth.prompts.forEachIndexed { index, prompt ->
                    add(if (index == 0) newKey else env[prompt.envKey].orEmpty())
                }
            }
            try {
                authService.login(providerId, AuthType.API_KEY, FormAuthInteraction(answers))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(app.getString(R.string.error_credential_save), e)
                return@launch
            }
            onCredentialStored()
        }
    }

    /**
     * Forgets the credential for [providerId]. Never tears down sessions or
     * the agent (credentials are read per request); only the derived status
     * surfaces are refreshed.
     */
    fun removeCredential(providerId: String) {
        scope.launch {
            try {
                authService.logout(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(app.getString(R.string.error_credential_save), e)
                return@launch
            }
            refresh()
        }
    }

    /**
     * Recomputes every credential-derived surface (provider rows, model
     * options). [ChatUiState.selectedModel] is not derived here: it follows
     * the bound agent's state, the same source the next prompt uses.
     */
    suspend fun refresh() {
        val providerOptions = try {
            catalog.providers
                .map { provider ->
                    val configured = authService.isConfigured(provider.id)
                    // The stored kind labels the sign-out action ("Log out"
                    // vs "Forget provider"); read only when configured.
                    val authType = if (configured) {
                        authService.authStatus(provider.id).storedType
                    } else {
                        null
                    }
                    ProviderOption(
                        id = provider.id,
                        name = provider.name,
                        configured = configured,
                        authType = when (authType) {
                            CredentialType.API_KEY -> AuthType.API_KEY
                            CredentialType.OAUTH -> AuthType.OAUTH
                            null -> null
                        }
                    )
                }
                .sortedBy { it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "provider_status", e)
            onError(app.getString(R.string.error_credential_save), e)
            return
        }
        val configuredIds = providerOptions.filter { it.configured }.map { it.id }.toSet()
        // Only models from configured providers, limited to each provider's
        // credential-filtered set; kept as real Model instances (the scope
        // resolution snapshot) and projected into picker options.
        val available = catalog.providers
            .filter { it.id in configuredIds }
            .flatMap { provider ->
                try {
                    authService.availableModels(provider.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "available_models", e)
                    onError(app.getString(R.string.error_credential_save), e)
                    return
                }
            }
        availableModels = available
        val providerNames = catalog.providers.associate { it.id to it.name }
        val modelOptions = available
            .map { model ->
                ModelOption(
                    providerId = model.provider,
                    providerName = providerNames.getValue(model.provider),
                    modelId = model.id,
                    name = model.name
                )
            }
            .sortedWith(compareBy({ it.providerName }, { it.name }))
        _state.update {
            it.copy(
                providerOptions = providerOptions,
                modelOptions = modelOptions
            )
        }
    }

    /**
     * Auth prompts for a provider's credential form, in catalog order: the
     * first prompt is the API key (secret); later prompts fill env slots.
     * Catalog data as-is — only envKey/message/secret exist on it.
     */
    fun providerAuthPrompts(providerId: String): List<AuthPrompt> =
        catalog.getProvider(providerId)?.auth?.prompts.orEmpty()

    /**
     * The provider's selectable auth methods, or an empty list for an
     * unknown provider. Never touches credentials.
     */
    fun providerAuthMethods(providerId: String): List<AuthMethodInfo> = try {
        authService.authMethods(providerId)
    } catch (e: ModelsError) {
        emptyList()
    }

    /** In-memory [AuthInteraction] answering fixed form values in order. */
    private class FormAuthInteraction(answers: List<String>) : AuthInteraction {
        private val remaining = ArrayDeque(answers)

        override suspend fun prompt(prompt: AuthInteractionPrompt): String = remaining.removeFirst()

        override suspend fun notify(event: AuthEvent) {}
    }

    /** Actionable, secret-free message naming the still-missing auth prompts. */
    private fun missingCredentialError(missing: List<AuthPrompt>): String = app.getString(
        R.string.error_missing_credentials,
        missing.joinToString(", ") { prompt -> prompt.message.ifEmpty { prompt.envKey } }
    )

    private companion object {
        private const val TAG = "Pathfinder"
    }
}
