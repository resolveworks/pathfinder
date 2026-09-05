package works.resolve.pathfinder.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

internal class TranscriptScrollState(
    val listState: LazyListState,
    initialFollowing: Boolean = true
) {
    var following by mutableStateOf(initialFollowing)
        private set

    // Layout growth is not user intent. In particular, canScrollForward becomes
    // true when a pinned message grows, without the reader having scrolled away.
    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y > 0 && listState.canScrollBackward) following = false
            return Offset.Zero
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            // Fling deltas participate too; programmatic scrollToItem does not
            // dispatch nested scroll. Horizontal code-block scrolling is ignored.
            if ((consumed.y < 0 || available.y < 0) && !listState.canScrollForward) {
                following = true
            }
            return Offset.Zero
        }
    }

    fun followBottom() {
        following = true
    }
}

@Composable
internal fun rememberTranscriptScrollState(): TranscriptScrollState {
    val listState = rememberLazyListState()
    return rememberSaveable(
        listState,
        saver = Saver(
            save = { it.following },
            restore = { TranscriptScrollState(listState, it) }
        )
    ) { TranscriptScrollState(listState) }
}

@Composable
internal fun FollowTranscriptBottom(state: TranscriptScrollState) {
    LaunchedEffect(state) {
        snapshotFlow {
            // Observe measured geometry, not token counts or composition timing:
            // thinking, markdown reflow, tools and viewport resizing all count.
            if (state.following && !state.listState.isScrollInProgress &&
                state.listState.canScrollForward
            ) {
                state.listState.layoutInfo
            } else {
                null
            }
        }.collect { layout ->
            if (layout != null && state.following && !state.listState.isScrollInProgress) {
                // The final item is a small sentinel, not a potentially taller-
                // than-screen message. LazyColumn clamps this to the actual end.
                state.listState.scrollToItem(layout.totalItemsCount - 1)
            }
        }
    }
}
