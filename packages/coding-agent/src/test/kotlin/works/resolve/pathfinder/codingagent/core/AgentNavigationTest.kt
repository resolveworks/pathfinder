package works.resolve.pathfinder.codingagent.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.codingagent.core.RetrySettings
import works.resolve.pathfinder.codingagent.core.compaction.BranchSummaryError
import works.resolve.pathfinder.codingagent.core.compaction.buildSessionContext
import works.resolve.pathfinder.codingagent.core.compaction.createBranchSummaryMessage
import works.resolve.pathfinder.codingagent.core.session.BranchSummaryEntry
import works.resolve.pathfinder.codingagent.core.session.MessageEntry
import works.resolve.pathfinder.codingagent.core.session.SessionManager

class AgentNavigationTest {

    private val model = Model(
        id = "glm-4.6",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid"
    )

    private val clock = FakeClock()

    private class FauxApi : ChatApi {
        val responses = ArrayDeque<AssistantMessage>()
        override fun streamSimple(
            model: Model,
            context: Context,
            options: SimpleStreamOptions
        ): Flow<AssistantMessageEvent> = flow {
            val response = responses.removeFirstOrNull() ?: error("No faux summary response queued")
            emit(AssistantMessageEvent.Done(response.stopReason, response))
        }
    }

    private suspend fun newManager(): SessionManager {
        var sessions = 0
        var entries = 0
        return SessionManager.create(
            dir = createTempDirectory("nav-test").toFile(),
            clock = clock,
            idFactory = { "sess-${sessions++}" },
            ioDispatcher = Dispatchers.Unconfined,
            entryIdFactory = { "e${entries++}" }
        )
    }

    /** user("hello") ← assistant("branch A") ← assistant("branch B"), leaf on B. */
    private suspend fun forkedSession(): Pair<SessionManager, String> {
        val manager = newManager()
        manager.appendMessage(UserMessage.ofText("hello"))
        clock.advanceMillis(1)
        manager.appendMessage(assistant("branch A"))
        val branchA = manager.leafId!!
        clock.advanceMillis(1)
        manager.appendMessage(assistant("branch B"))
        return manager to branchA
    }

    private fun assistant(text: String) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = StopReason.STOP,
        usage = Usage()
    )

    private fun summaryResponse(text: String) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = StopReason.STOP
    )

    @Test
    fun `navigation with summarize appends a branch summary on the target`() = runTest {
        val api = FauxApi().apply { responses.add(summaryResponse("## Goal\nexplore")) }
        val models = Models(
            listOf(
                Provider(
                    model.provider,
                    model.provider,
                    "https://faux.test",
                    authResolver = { _, _ -> ResolvedAuth(apiKey = "faux-key") },
                    models = listOf(model),
                    apis = mapOf(model.api to api)
                )
            )
        )
        val (manager, branchA) = forkedSession()
        val oldLeaf = manager.leafId
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            sessionManager = manager,
            models = models,
            retrySettings = RetrySettings(enabled = false)
        )

        val result = session.navigateTree(
            branchA,
            AgentSession.NavigateTreeOptions(summarize = true)
        )

        assertNull(result.editorText)
        assertTrue(!result.cancelled)
        val summary = result.summaryEntry
        assertNotNull(summary)

        assertEquals(branchA, summary!!.parentId)
        assertEquals(oldLeaf, summary.fromId)
        assertTrue(summary.summary.contains("## Goal"))
        assertEquals(summary.id, session.conversation.leafId)

        val context = buildSessionContext(session.conversation.activeEntries())
        val projected =
            createBranchSummaryMessage(summary.summary, summary.fromId, summary.timestamp)
        val projectedContent = (projected as UserMessage).content
        assertTrue(
            context.any { msg ->
                msg is UserMessage && msg.content == projectedContent
            }
        )
        assertTrue(
            context.none { msg ->
                msg is AssistantMessage &&
                    msg.content.any { c -> c is TextContent && c.text == "branch B" }
            }
        )
    }

    @Test
    fun `navigation without summarize moves the leaf`() = runTest {
        val (manager, branchA) = forkedSession()
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            sessionManager = manager,
            retrySettings = RetrySettings(enabled = false)
        )

        val result = session.navigateTree(branchA)

        assertNull(result.summaryEntry)
        assertEquals(branchA, session.conversation.leafId)
    }

    @Test
    fun `navigating to the current leaf is a no-op`() = runTest {
        val (manager, _) = forkedSession()
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            sessionManager = manager
        )

        val result = session.navigateTree(manager.leafId!!)
        assertTrue(!result.cancelled)
        assertEquals(3, session.conversation.entries.size)
    }

    @Test
    fun `user-message leaf target re-edits instead of the no-op`() = runTest {
        // A run that never committed an assistant entry leaves its user
        // message as the leaf; navigating to it must re-edit uniformly.
        val manager = newManager()
        manager.appendMessage(UserMessage.ofText("hello"))
        val userEntryId = manager.leafId!!
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            sessionManager = manager
        )

        val result = session.navigateTree(userEntryId)

        assertEquals("hello", result.editorText)
        assertNull(session.conversation.leafId)
    }

    @Test
    fun `user-message target re-edits and returns the editor text`() = runTest {
        val (manager, _) = forkedSession()
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            sessionManager = manager
        )

        val userEntryId = manager.entries.first { it.parentId == null }.id
        val result = session.navigateTree(userEntryId)

        assertEquals("hello", result.editorText)
        assertNull(session.conversation.leafId)
    }

    @Test
    fun `summarize without a provider stack is rejected`() = runTest {
        val (manager, branchA) = forkedSession()
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            sessionManager = manager
        )
        try {
            session.navigateTree(branchA, AgentSession.NavigateTreeOptions(summarize = true))
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("No model available for summarization", e.message)
        }
    }

    @Test
    fun `failing summarization leaves the tree untouched`() = runTest {
        val api = FauxApi().apply {
            responses.add(
                AssistantMessage(
                    content = emptyList(),
                    api = model.api,
                    provider = model.provider,
                    model = model.id,
                    stopReason = StopReason.ERROR,
                    errorMessage = "provider down"
                )
            )
        }
        val models = Models(
            listOf(
                Provider(
                    model.provider,
                    model.provider,
                    "https://faux.test",
                    authResolver = { _, _ -> ResolvedAuth(apiKey = "faux-key") },
                    models = listOf(model),
                    apis = mapOf(model.api to api)
                )
            )
        )
        val (manager, branchA) = forkedSession()
        val oldLeaf = manager.leafId
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            sessionManager = manager,
            models = models,
            retrySettings = RetrySettings(enabled = false)
        )

        try {
            session.navigateTree(branchA, AgentSession.NavigateTreeOptions(summarize = true))
            throw AssertionError("expected failure")
        } catch (e: BranchSummaryError) {
            assertEquals("provider down", e.message?.substringAfter("Branch summary failed: "))
        }
        assertTrue(session.conversation.entries.none { it is BranchSummaryEntry })
        assertEquals(oldLeaf, session.conversation.leafId)
    }

    @Test
    fun `send and instant abort commits a complete resumable session file`() = runTest {
        // Flagship scenario: an aborted first turn still persists an
        // assistant message (empty content, stopReason "aborted"), which is
        // exactly the no-assistant guard's flush trigger.
        val dir = createTempDirectory("nav-test").toFile()
        val manager = SessionManager.create(dir, clock, ioDispatcher = Dispatchers.Unconfined)
        val streams = ArrayDeque<Flow<AssistantMessageEvent>>()
        val session = AgentSession(
            agent = Agent(
                model = model,
                streamOptions = SimpleStreamOptions(),
                streamFn = StreamFn { _, _, _ -> streams.removeFirst() }
            ),
            sessionManager = manager
        )

        // Instant abort: the committed empty-content assistant message with
        // stopReason "aborted" is the no-assistant guard's flush trigger.
        streams.add(
            flow {
                emit(
                    AssistantMessageEvent.Done(
                        StopReason.ABORTED,
                        AssistantMessage(
                            content = emptyList(),
                            api = model.api,
                            provider = model.provider,
                            model = model.id,
                            stopReason = StopReason.ABORTED,
                            usage = Usage()
                        )
                    )
                )
            }
        )
        session.prompt("hello")

        val file = dir.listFiles { f: File -> f.name.endsWith(".jsonl") }!!.single()
        val reopened = SessionManager.open(file, clock, ioDispatcher = Dispatchers.Unconfined)
        assertEquals(manager.sessionId, reopened.sessionId)
        val aborted = reopened.entries.last()
        val abortedMessage = (aborted as MessageEntry).message as AssistantMessage
        assertEquals(StopReason.ABORTED, abortedMessage.stopReason)
        assertTrue(abortedMessage.content.isEmpty())
        assertEquals(reopened.leafId, aborted.id)
    }
}
