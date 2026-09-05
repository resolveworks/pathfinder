package works.resolve.pathfinder.ui.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ui.theme.PathfinderTheme

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = Application::class)
class TranscriptScrollTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private var uiState by mutableStateOf(
        ChatUiState(showThinking = true)
    )
    private lateinit var scrollState: TranscriptScrollState

    private fun text(vararg chunks: String) = chunks.joinToString("\n\n")

    private fun lines(count: Int, prefix: String = "line") =
        (1..count).joinToString("\n") { "$prefix $it" }

    private fun userMessage(id: String, body: String) =
        ChatMessage(id = id, role = ChatRole.User, blocks = listOf(ChatBlock.Text(body)))

    private fun assistantMessage(id: String, body: String) =
        ChatMessage(id = id, role = ChatRole.Assistant, blocks = listOf(ChatBlock.Text(body)))

    private val viewportHeight = mutableStateOf(400.dp)

    private fun setContent() {
        compose.setContent {
            PathfinderTheme {
                Box {
                    ConversationContent(
                        uiState = uiState,
                        scrollState = rememberTranscriptScrollState().also {
                            if (!::scrollState.isInitialized) scrollState = it
                        },
                        modifier = Modifier.size(320.dp, viewportHeight.value)
                    )
                }
            }
        }
        compose.runOnIdle {}
    }

    private fun launch(vararg messages: ChatMessage, streaming: ChatMessage? = null) {
        uiState = uiState.copy(messages = messages.toList(), streamingMessage = streaming)
        setContent()
    }

    @Test
    fun viewportShrinkWhilePinnedStillFollowsBottom() {
        uiState = uiState.copy(
            messages = listOf(userMessage("u1", text("hello"))),
            streamingMessage = ChatMessage(
                id = "s1",
                role = ChatRole.Assistant,
                blocks = listOf(ChatBlock.Text(lines(40, "stream")))
            )
        )
        setContent()
        waitUntilAtBottom()

        compose.runOnIdle { viewportHeight.value = 250.dp }
        waitUntilAtBottom()
        org.junit.Assert.assertTrue(scrollState.following)
    }

    private val listState get() = scrollState.listState

    private fun waitUntilAtBottom(timeout: Long = 10_000) {
        compose.waitUntil(timeoutMillis = timeout) {
            !listState.canScrollForward && listState.layoutInfo.visibleItemsInfo.isNotEmpty()
        }
    }

    private fun waitUntil(timeout: Long = 10_000, condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis = timeout, condition = condition)
    }

    @Test
    fun growingStreamingTextBeyondViewportStaysAtBottom() {
        val seed = mutableListOf(
            userMessage("u1", text("hello")),
            assistantMessage("a1", lines(30))
        )
        uiState = uiState.copy(
            messages = seed.toList(),
            streamingMessage = ChatMessage(
                id = "s1",
                role = ChatRole.Assistant,
                blocks = listOf(ChatBlock.Text(lines(10, "stream")))
            )
        )
        setContent()
        waitUntilAtBottom()
        org.junit.Assert.assertTrue(scrollState.following)

        repeat(3) { step ->
            compose.runOnIdle {
                uiState = uiState.copy(
                    streamingMessage = uiState.streamingMessage?.copy(
                        blocks = listOf(ChatBlock.Text(lines(10 + 15 * (step + 1), "stream")))
                    )
                )
            }
            waitUntilAtBottom()
            org.junit.Assert.assertTrue(scrollState.following)
        }
    }

    @Test
    fun growingThinkingBeyondViewportStaysAtBottom() {
        uiState = uiState.copy(
            messages = listOf(userMessage("u1", text("hello"))),
            streamingMessage = ChatMessage(
                id = "s1",
                role = ChatRole.Assistant,
                blocks = listOf(ChatBlock.Thinking(lines(10, "think")))
            )
        )
        setContent()
        waitUntilAtBottom()
        org.junit.Assert.assertTrue(scrollState.following)

        repeat(2) { step ->
            compose.runOnIdle {
                uiState = uiState.copy(
                    streamingMessage = uiState.streamingMessage?.copy(
                        blocks = listOf(
                            ChatBlock.Thinking(lines(10 + 20 * (step + 1), "think"))
                        )
                    )
                )
            }
            waitUntilAtBottom()
        }
    }

    /** Slow drag toward the top (finger moving down): nested-scroll detach intent. */
    private fun dragDown(px: Float = 250f) {
        compose.onNode(hasScrollAction()).performTouchInput {
            val start = center
            down(start)
            val steps = 10
            advanceEventTime(100)
            repeat(steps) { i ->
                moveTo(start + Offset(0f, px * (i + 1) / steps))
                advanceEventTime(50)
            }
            up()
        }
    }

    private fun dragUp(px: Float = 250f) {
        compose.onNode(hasScrollAction()).performTouchInput {
            val start = center
            down(start)
            val steps = 10
            advanceEventTime(100)
            repeat(steps) { i ->
                moveTo(start + Offset(0f, -px * (i + 1) / steps))
                advanceEventTime(50)
            }
            up()
        }
    }

    @Test
    fun swipeDownDetachesAndAppendingPreservesScrollAnchor() {
        uiState = uiState.copy(
            messages = listOf(
                userMessage("u1", text("hello")),
                assistantMessage("a1", lines(30))
            ),
            streamingMessage = ChatMessage(
                id = "s1",
                role = ChatRole.Assistant,
                blocks = listOf(ChatBlock.Text(lines(30, "stream")))
            )
        )
        setContent()
        waitUntilAtBottom()

        dragDown()
        compose.runOnIdle {}
        waitUntil({ !scrollState.following })
        org.junit.Assert.assertTrue(listState.canScrollForward)

        val layout = listState.layoutInfo
        val anchorIndex = listState.firstVisibleItemIndex
        val anchorOffset = listState.firstVisibleItemScrollOffset
        val anchorKey = layout.visibleItemsInfo.first { it.index == anchorIndex }.key

        compose.runOnIdle {
            uiState = uiState.copy(
                streamingMessage = uiState.streamingMessage?.copy(
                    blocks = listOf(ChatBlock.Text(lines(60, "stream")))
                )
            )
        }
        compose.runOnIdle {}
        waitUntil({
            val li = listState.layoutInfo
            li.visibleItemsInfo.firstOrNull { it.index == anchorIndex }?.key == anchorKey
        })
        org.junit.Assert.assertFalse(scrollState.following)
        org.junit.Assert.assertEquals(anchorIndex, listState.firstVisibleItemIndex)
        org.junit.Assert.assertEquals(anchorOffset, listState.firstVisibleItemScrollOffset)
    }

    @Test
    fun swipeBackToBottomResumesFollowing() {
        uiState = uiState.copy(
            messages = listOf(assistantMessage("a1", lines(60))),
            streamingMessage = ChatMessage(
                id = "s1",
                role = ChatRole.Assistant,
                blocks = listOf(ChatBlock.Text(lines(30, "stream")))
            )
        )
        setContent()
        waitUntilAtBottom()

        dragDown()
        waitUntil({ !scrollState.following })

        var guard = 0
        while (listState.canScrollForward && guard++ < 20) {
            dragUp()
            compose.runOnIdle {}
        }
        waitUntil({ !listState.canScrollForward })
        waitUntil({ scrollState.following })

        compose.runOnIdle {
            uiState = uiState.copy(
                streamingMessage = uiState.streamingMessage?.copy(
                    blocks = listOf(ChatBlock.Text(lines(60, "stream")))
                )
            )
        }
        waitUntilAtBottom()
    }

    @Test
    fun followBottomButtonResumesFollowing() {
        uiState = uiState.copy(
            messages = listOf(assistantMessage("a1", lines(60))),
            streamingMessage = ChatMessage(
                id = "s1",
                role = ChatRole.Assistant,
                blocks = listOf(ChatBlock.Text(lines(30, "stream")))
            )
        )
        setContent()
        waitUntilAtBottom()

        dragDown()
        waitUntil({ !scrollState.following })

        val description =
            compose.activity.getString(R.string.action_scroll_to_bottom)
        compose.onNodeWithContentDescription(description).assertExists()
            .performClick()

        waitUntilAtBottom()
        waitUntil({ scrollState.following })
    }

    @Test
    fun committedStreamingMessageKeepsTranscriptAtBottom() {
        uiState = uiState.copy(
            messages = listOf(userMessage("u1", text("hello"))),
            streamingMessage = ChatMessage(
                id = "s1",
                role = ChatRole.Assistant,
                blocks = listOf(ChatBlock.Text(lines(30, "stream")))
            )
        )
        setContent()
        waitUntilAtBottom()

        compose.runOnIdle {
            uiState = uiState.copy(
                messages = uiState.messages + (uiState.streamingMessage ?: error("missing")),
                streamingMessage = null
            )
        }
        waitUntilAtBottom()

        compose.runOnIdle {
            uiState = uiState.copy(
                messages = uiState.messages + userMessage("u2", text("again")),
                streamingMessage = ChatMessage(
                    id = "s2",
                    role = ChatRole.Assistant,
                    blocks = listOf(ChatBlock.Text(lines(30, "stream2")))
                )
            )
        }
        waitUntilAtBottom()
        org.junit.Assert.assertTrue(scrollState.following)
    }
}
