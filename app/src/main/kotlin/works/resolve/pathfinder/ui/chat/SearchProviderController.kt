package works.resolve.pathfinder.ui.chat

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import works.resolve.pathfinder.codingagent.core.AgentSession
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool
import works.resolve.pathfinder.tools.websearch.SearchProviderService

/**
 * The app's web-search feature: search-provider credentials (Brave only,
 * Scry parity) and the web_search tool's presence on sessions. Owns the
 * [ProviderOption] rows of the search-providers screen, the success epoch
 * its credential form pops on, and the Brave-configured fact every
 * session's tool set follows. Runs in [scope]; user-facing failures
 * surface through [onError] as static, secret-free strings, absorbed
 * degradations only log.
 */
internal class SearchProviderController(
    private val scope: CoroutineScope,
    private val service: SearchProviderService,
    private val onError: (message: String, cause: Throwable?) -> Unit
) {
    /** Live search-provider surface: option rows, the Brave flag, and the credential form's success epoch. */
    data class State(
        val options: List<ProviderOption> = emptyList(),
        val braveConfigured: Boolean = false,
        /**
         * Incremented only after a credential has been successfully
         * persisted, never on a validation or storage failure.
         */
        val successEpoch: Long = 0
    )

    private val _state = MutableStateFlow(State())

    /** The source of truth [ChatUiState.searchProviderOptions] and [ChatUiState.searchCredentialSuccessEpoch] mirror. */
    val state: StateFlow<State> = _state.asStateFlow()

    /** UI-safe auth prompts for a search provider's credential form (only Brave is supported). */
    fun authPrompts(providerId: String): List<ProviderAuthPrompt> =
        if (providerId == SearchProviderService.BRAVE_PROVIDER_ID) {
            listOf(ProviderAuthPrompt(BRAVE_API_KEY_PROMPT, KEY_PROMPT_MESSAGE, secret = true))
        } else {
            emptyList()
        }

    /**
     * Stores a web-search provider's API key. Blank input, an unknown
     * provider, or a storage failure surfaces a static, secret-free error
     * and changes nothing. Only a confirmed non-blank save bumps
     * [State.successEpoch] and enables web_search on the bound session for
     * the next run.
     */
    fun saveCredential(providerId: String, apiKeyInput: String) {
        scope.launch {
            if (providerId != SearchProviderService.BRAVE_PROVIDER_ID) {
                onError(ERROR_CREDENTIAL_SAVE, null)
                return@launch
            }
            val key = apiKeyInput.trim()
            if (key.isEmpty()) {
                onError(ERROR_CREDENTIAL_SAVE, null)
                return@launch
            }
            try {
                service.saveApiKey(providerId, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(ERROR_CREDENTIAL_SAVE, e)
                return@launch
            }
            _state.update { it.copy(successEpoch = it.successEpoch + 1) }
            refresh()
        }
    }

    /** Deletes a search provider's stored key; a failure surfaces a safe error and changes nothing. */
    fun removeCredential(providerId: String) {
        scope.launch {
            try {
                service.remove(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(ERROR_CREDENTIAL_SAVE, e)
                return@launch
            }
            refresh()
        }
    }

    /**
     * Re-reads search credentials and recomputes [State]. A read failure
     * degrades search to unconfigured/disabled with a safe error — it never
     * fails an otherwise-valid chat initialization.
     */
    suspend fun refresh() {
        val options = try {
            service.providers
                .map { provider ->
                    ProviderOption(
                        id = provider.id,
                        name = provider.name,
                        configured = service.isConfigured(provider.id)
                    )
                }
                .sortedBy { it.name }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "search_provider_status", e)
            onError(ERROR_STATUS, e)
            _state.update {
                it.copy(
                    braveConfigured = false,
                    options = service.providers
                        .map { provider ->
                            ProviderOption(provider.id, provider.name, configured = false)
                        }
                        .sortedBy { option -> option.name }
                )
            }
            return
        }
        val braveConfigured =
            options.firstOrNull { it.id == SearchProviderService.BRAVE_PROVIDER_ID }?.configured ==
                true
        _state.update { it.copy(options = options, braveConfigured = braveConfigured) }
    }

    /**
     * Aligns one session's tool set with the current search credential:
     * web_search is appended (last) only while Brave is configured; the
     * order and activation of every other tool is preserved exactly. Safe
     * mid-stream: the agent snapshots its tool list per run, so an
     * in-flight run keeps its own snapshot and the change lands on the
     * next run. No `active_tools_change` session entry is appended — this
     * runtime-only toggle is never persisted.
     */
    fun applyTo(session: AgentSession) {
        val names = session.getActiveToolNames().filter { it != BraveWebSearchTool.NAME } +
            listOfNotNull(BraveWebSearchTool.NAME.takeIf { _state.value.braveConfigured })
        session.setActiveToolsByName(names)
    }

    private companion object {
        private const val TAG = "Pathfinder"

        private const val ERROR_CREDENTIAL_SAVE = "Could not store the search API key"
        private const val ERROR_STATUS = "Could not read the search provider status"

        /** Kept as the prompt's stable id. */
        private const val BRAVE_API_KEY_PROMPT = "BRAVE_API_KEY"

        /** Clear, secret-free message for the Brave key prompt. */
        private const val KEY_PROMPT_MESSAGE = "Enter your Brave Search API key"
    }
}
