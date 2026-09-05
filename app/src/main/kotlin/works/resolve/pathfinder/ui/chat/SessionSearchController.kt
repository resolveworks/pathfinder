package works.resolve.pathfinder.ui.chat

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import works.resolve.pathfinder.codingagent.core.session.SessionInfo
import works.resolve.pathfinder.data.sessions.SessionSource

/**
 * The drawer's session search. Holds the searchable-text corpus only while
 * a query is active (memory bound; pi holds it only while the selector is
 * open) and filters the drawer's session summaries against it.
 * Snapshot-at-activation: list churn reuses the corpus, never rescans, and
 * sessions absent from the corpus drop out under a query (pi: unscanned
 * sessions don't appear in selector results). A scan failure degrades to
 * an empty corpus — results stay empty, no error surfaced.
 */
internal class SessionSearchController(
    private val scope: CoroutineScope,
    private val sessionSource: SessionSource
) {
    /** Search surface mirrored into [ChatUiState]; the corpus itself never enters UI state. */
    data class State(
        val query: String = "",
        /** RELEVANCE matches pi's effective default under a query (its "threaded" mode degrades to relevance). */
        val sort: SessionSearchSort = SessionSearchSort.RELEVANCE,
        val results: List<SessionInfo> = emptyList(),
        /** True while the one-time corpus scan runs after the query activates. */
        val isScanning: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var corpus: Map<String, String>? = null
    private var scanJob: Job? = null
    private var summaries: List<SessionInfo> = emptyList()

    /**
     * Updates the search query: blank drops the corpus and results;
     * non-blank filters synchronously against the loaded corpus or triggers
     * the single scan that loads it.
     */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        if (query.isBlank()) {
            scanJob?.cancel()
            scanJob = null
            corpus = null
            _state.update { it.copy(results = emptyList(), isScanning = false) }
            return
        }
        if (corpus != null) {
            applyFilter()
            return
        }
        if (scanJob?.isActive == true) return
        _state.update { it.copy(isScanning = true) }
        scanJob = scope.launch {
            val scanned = try {
                sessionSource.list().associate { info ->
                    info.id to "${info.id} ${info.allMessagesText}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "session_search", e)
                emptyMap()
            }
            corpus = if (_state.value.query.isBlank()) null else scanned
            _state.update { it.copy(isScanning = false) }
            applyFilter()
        }
    }

    /** Switches the sort and re-filters when a query is active against a loaded corpus. */
    fun setSort(sort: SessionSearchSort) {
        _state.update { it.copy(sort = sort) }
        if (_state.value.query.isNotBlank() && corpus != null) {
            applyFilter()
        }
    }

    /** Pushes the latest drawer summaries; re-filters when a query is active against a loaded corpus. */
    fun onSummariesChanged(updated: List<SessionInfo>) {
        summaries = updated
        if (corpus != null && _state.value.query.isNotBlank()) {
            applyFilter()
        }
    }

    private fun applyFilter() {
        val loaded = corpus ?: return
        val state = _state.value
        if (state.query.isBlank()) return
        val entries = summaries.map { summary ->
            SessionSearchEntry(summary.id, summary.modified, loaded[summary.id].orEmpty())
        }
        val matched = filterAndSortSessions(entries, state.query, state.sort)
        val byId = summaries.associateBy { it.id }
        _state.update {
            it.copy(results = matched.mapNotNull { entry -> byId[entry.id] })
        }
    }

    private companion object {
        private const val TAG = "Pathfinder"
    }
}
