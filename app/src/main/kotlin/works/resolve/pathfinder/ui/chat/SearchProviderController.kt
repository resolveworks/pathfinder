package works.resolve.pathfinder.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ai.providers.AuthPrompt
import works.resolve.pathfinder.codingagent.core.AgentSession
import works.resolve.pathfinder.tools.websearch.SearchProviderService
import works.resolve.pathfinder.tools.websearch.WebSearchTool

/**
 * The app's web-search feature: search-provider credentials, the active
 * provider selection, and the web_search tool's presence on sessions. Owns
 * the [ProviderOption] rows of the search-providers screen and the
 * active-configured fact every session's tool set follows. Runs in [scope];
 * user-facing failures surface through [onError] as static, secret-free
 * strings, absorbed degradations only log.
 */
internal class SearchProviderController(
    private val scope: CoroutineScope,
    private val service: SearchProviderService,
    private val tool: WebSearchTool,
    private val onError: (message: UiString, cause: Throwable?) -> Unit
) {
    /** Live search-provider surface. */
    data class State(
        val options: List<ProviderOption> = emptyList(),
        val activeProviderId: String = SearchProviderService.DEFAULT_PROVIDER_ID
    ) {
        val activeConfigured: Boolean
            get() = options.any { it.id == activeProviderId && it.configured }
    }

    private val _state = MutableStateFlow(State())

    /** The source of truth [ChatUiState.searchProviderOptions] mirrors. */
    val state: StateFlow<State> = _state.asStateFlow()

    /** Auth prompts for a search provider's credential form (empty for unknown providers). */
    fun authPrompts(providerId: String): List<AuthPrompt> = AUTH_PROMPTS[providerId].orEmpty()

    /**
     * Stores a web-search provider's API key. Blank input, an unknown
     * provider, or a storage failure surfaces a static, secret-free error
     * and changes nothing. Only a confirmed non-blank save of the active
     * provider enables web_search on the bound session for the next run.
     */
    fun saveCredential(providerId: String, apiKeyInput: String) {
        scope.launch {
            val key = apiKeyInput.trim()
            if (key.isEmpty()) {
                onError(UiString(R.string.error_search_credential_save), null)
                return@launch
            }
            try {
                service.saveApiKey(providerId, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(UiString(R.string.error_search_credential_save), e)
                return@launch
            }
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
                onError(UiString(R.string.error_search_credential_save), e)
                return@launch
            }
            refresh()
        }
    }

    /** Persists the active search provider; the next tool call dispatches to it. */
    fun setActive(providerId: String) {
        scope.launch {
            try {
                service.setActiveProvider(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(UiString(R.string.error_search_credential_save), e)
                return@launch
            }
            refresh()
        }
    }

    /**
     * Re-reads the active provider and search credentials and recomputes
     * [State]. A read failure degrades search to unconfigured/disabled with
     * a safe error — it never fails an otherwise-valid chat initialization.
     */
    suspend fun refresh() {
        try {
            val activeProviderId = service.activeProviderId()
            val options = service.providers
                .map { provider ->
                    ProviderOption(
                        id = provider.id,
                        name = provider.name,
                        configured = service.isConfigured(provider.id)
                    )
                }
                .sortedBy { it.name }
            _state.update { it.copy(options = options, activeProviderId = activeProviderId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("search_provider_status", e)
            onError(UiString(R.string.error_search_status), e)
            _state.update {
                it.copy(
                    options = service.providers
                        .map { provider ->
                            ProviderOption(provider.id, provider.name, configured = false)
                        }
                        .sortedBy { option -> option.name }
                )
            }
            return
        }
    }

    /**
     * Aligns one session's tool set with the active search provider:
     * web_search is appended (last) only while that provider is configured,
     * and its declared schema is rebuilt from the active provider's
     * native parameters; the order and activation of every other tool is
     * preserved exactly. Safe mid-stream: the agent snapshots its tool list
     * per run, so an in-flight run keeps its own snapshot and the change
     * lands on the next run. No `active_tools_change` session entry is
     * appended — this runtime-only toggle is never persisted.
     */
    fun applyTo(session: AgentSession) {
        val state = _state.value
        tool.applySchema(state.activeProviderId)
        val names = session.getActiveToolNames().filter { it != WebSearchTool.NAME } +
            listOfNotNull(WebSearchTool.NAME.takeIf { state.activeConfigured })
        session.setActiveToolsByName(names)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(SearchProviderController::class.java)

        /** Per-provider credential form (search providers offer one API key). */
        private val AUTH_PROMPTS: Map<String, List<AuthPrompt>> = mapOf(
            SearchProviderService.BRAVE_PROVIDER_ID to listOf(
                AuthPrompt("BRAVE_API_KEY", "Enter your Brave Search API key", secret = true)
            ),
            SearchProviderService.TAVILY_PROVIDER_ID to listOf(
                AuthPrompt("TAVILY_API_KEY", "Enter your Tavily API key", secret = true)
            )
        )
    }
}
