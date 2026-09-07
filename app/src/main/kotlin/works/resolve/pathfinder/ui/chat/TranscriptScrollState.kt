package works.resolve.pathfinder.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
internal fun rememberTranscriptScrollState(uiState: ChatUiState): TranscriptScrollState {
    // Start at the sentinel on the first measure, not at the top followed by
    // an effect-driven jump. Restored reader positions still take precedence.
    val initialBottomIndex = remember {
        uiState.messages.count(TranscriptRow::hasRenderableContent) +
            if (uiState.streamingMessage != null) 1 else 0
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialBottomIndex)
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
    // Observe measured geometry, not token counts or composition timing:
    // thinking, markdown reflow, tools and viewport resizing all count.
    // A derived boolean (not the layoutInfo object itself, which is a fresh
    // instance on every measure) breaks the follow feedback loop: the flow
    // only re-fires when content growth actually makes the end scrollable
    // again, not on the layout churn caused by our own scrollToItem.
    val needsFollow by remember(state) {
        derivedStateOf {
            state.following && !state.listState.isScrollInProgress &&
                state.listState.canScrollForward
        }
    }
    LaunchedEffect(state, needsFollow) {
        if (!needsFollow) return@LaunchedEffect
        // The final item is a small sentinel, not a potentially taller-
        // than-screen message. LazyColumn clamps this to the actual end.
        state.listState.scrollToItem(state.listState.layoutInfo.totalItemsCount - 1)
    }
}
