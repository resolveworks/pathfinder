package works.resolve.pathfinder.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import works.resolve.pathfinder.codingagent.core.session.SessionInfo

/**
 * The drawer's session search. Filters the drawer's refreshed session
 * summaries synchronously; there is no separate corpus, so results always
 * track the latest summary state (divergence from pi's snapshot-at-scan).
 */
internal class SessionSearchController {
    /** Search surface mirrored into [ChatUiState]. */
    data class State(
        val query: String = "",
        /** RELEVANCE matches pi's effective default under a query (its "threaded" mode degrades to relevance). */
        val sort: SessionSearchSort = SessionSearchSort.RELEVANCE,
        val results: List<SessionInfo> = emptyList()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var summaries: List<SessionInfo> = emptyList()

    /** Updates the search query: blank clears results, non-blank filters the current summaries. */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList()) }
            return
        }
        applyFilter()
    }

    /** Switches the sort and re-filters when a query is active. */
    fun setSort(sort: SessionSearchSort) {
        _state.update { it.copy(sort = sort) }
        if (_state.value.query.isNotBlank()) {
            applyFilter()
        }
    }

    /** Pushes the latest drawer summaries; re-filters when a query is active. */
    fun onSummariesChanged(updated: List<SessionInfo>) {
        summaries = updated
        if (_state.value.query.isNotBlank()) {
            applyFilter()
        }
    }

    private fun applyFilter() {
        val state = _state.value
        if (state.query.isBlank()) return
        _state.update {
            it.copy(
                results = filterAndSortSessions(
                    summaries,
                    state.query,
                    state.sort
                )
            )
        }
    }
}
