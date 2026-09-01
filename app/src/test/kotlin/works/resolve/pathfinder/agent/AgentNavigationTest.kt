package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.api.ChatApi
import works.resolve.pathfinder.ai.models.Models
import works.resolve.pathfinder.ai.models.Provider
import works.resolve.pathfinder.ai.models.ResolvedAuth
import works.resolve.pathfinder.agent.compaction.BranchSummaryError
import works.resolve.pathfinder.agent.compaction.buildSessionContext
import works.resolve.pathfinder.agent.compaction.createBranchSummaryMessage
import works.resolve.pathfinder.data.sessions.BranchSummaryEntry
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.sessions.LaneRecord
import kotlinx.serialization.json.jsonPrimitive
import works.resolve.pathfinder.data.sessions.OperationOutcome
import works.resolve.pathfinder.data.settings.RetrySettings
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tree navigation with branch summarization (pi agent-session.ts
 * navigateTree ~3092; audit P1-4): the durable navigation operation record
 * (started intent + finished outcome) and the appended
 * [BranchSummaryEntry] on the target branch, projected into context.
 */
class AgentNavigationTest {

    private val model = Model(
        id = "glm-4.6",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid",
    )

    /** Deterministic recorder capturing records in append order. */
    private class RecordingSink : OperationLifecycleRecorder {
        val records = CopyOnWriteArrayList<LaneRecord>()
        override suspend fun append(record: LaneRecord) {
            records.add(record)
        }
        override fun appendBestEffort(record: LaneRecord) {
            records.add(record)
        }
    }

    /** Fake summarization ChatApi serving one queued terminal response. */
    private class FauxApi : ChatApi {
        val responses = ArrayDeque<AssistantMessage>()
        override fun streamSimple(
            model: Model,
            context: Context,
            options: SimpleStreamOptions,
        ): Flow<AssistantMessageEvent> = flow {
            val response = responses.removeFirstOrNull() ?: error("No faux summary response queued")
            emit(AssistantMessageEvent.Done(response.stopReason, response))
        }
    }

    /** A forked conversation: root user, assistant A (leaf), sibling assistant B. */
    private fun forkedConversation(): Pair<Conversation, String> {
        var conversation = Conversation(emptyList(), null)
        conversation = conversation.append(works.resolve.pathfinder.ai.core.UserMessage.ofText("hello"))
        val userEntry = conversation.entries.last()
        conversation = conversation.append(
            AssistantMessage(
                content = listOf(TextContent("branch A")),
                api = model.api,
                provider = model.provider,
                model = model.id,
                stopReason = StopReason.STOP,
                usage = Usage(),
            ),
        )
        val branchA = conversation.leafId!!
        conversation = conversation.append(
            AssistantMessage(
                content = listOf(TextContent("branch B")),
                api = model.api,
                provider = model.provider,
                model = model.id,
                stopReason = StopReason.STOP,
                usage = Usage(),
            ),
        )
        return conversation to branchA
    }

    private fun summaryResponse(text: String) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = StopReason.STOP,
    )

    @Test
    fun `navigation with summarize appends the record pair and a branch summary on the target`() = runTest {
        val api = FauxApi().apply { responses.add(summaryResponse("## Goal\nexplore")) }
        val models = Models(
            listOf(
                Provider(
                    model.provider,
                    model.provider,
                    "https://faux.test",
                    authResolver = { _, _ -> ResolvedAuth(apiKey = "faux-key") },
                    models = listOf(model),
                    apis = mapOf(model.api to api),
                ),
            ),
        )
        val (forked, branchA) = forkedConversation()
        val sink = RecordingSink()
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            conversation = forked,
            models = models,
            retrySettings = RetrySettings(enabled = false),
        )
        session.operationRecorder = sink

        val result = session.navigateTree(branchA, AgentSession.NavigateTreeOptions(summarize = true))

        assertNull(result.editorText)
        assertTrue(!result.cancelled)
        val summary = result.summaryEntry
        assertNotNull(summary)

        // Records: operation_started (navigation intent naming target,
        // summarize flag, and the pre-minted summaryEntryId) then
        // operation_finished completed.
        val start = sink.records.filterIsInstance<LaneRecord.OperationStartedRecord>().single()
        assertEquals("navigation", start.intent.payload["kind"]!!.jsonPrimitive.content)
        assertEquals(branchA, start.intent.payload["targetId"]!!.jsonPrimitive.content)
        assertEquals(true, start.intent.payload["summarize"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(summary!!.id, start.intent.payload["summaryEntryId"]!!.jsonPrimitive.content)
        val finish = sink.records.filterIsInstance<LaneRecord.OperationFinishedRecord>().single()
        assertEquals(start.id, finish.runId)
        assertEquals(OperationOutcome.COMPLETED, finish.outcome)

        // The summary entry sits on the TARGET branch (parent = the target)
        // and records the abandoned leaf as fromId (pi's branchWithSummary).
        assertEquals(branchA, summary.parentId)
        assertEquals(forked.leafId, summary.fromId)
        assertTrue(summary.summary.contains("## Goal"))
        assertEquals(summary.id, session.conversation.leafId)

        // The summary projects into context on the target branch.
        val context = buildSessionContext(session.conversation.activeEntries())
        val projected = createBranchSummaryMessage(summary.summary, summary.fromId, summary.timestamp)
        val projectedContent = (projected as works.resolve.pathfinder.ai.core.UserMessage).content
        assertTrue(context.any { msg -> msg is works.resolve.pathfinder.ai.core.UserMessage && msg.content == projectedContent })
        // The abandoned branch's message is gone from the active path.
        assertTrue(context.none { msg -> msg is AssistantMessage && msg.content.any { c -> c is TextContent && c.text == "branch B" } })
    }

    @Test
    fun `navigation without summarize moves the leaf and records a completed navigation`() = runTest {
        val (forked, branchA) = forkedConversation()
        val sink = RecordingSink()
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            conversation = forked,
            retrySettings = RetrySettings(enabled = false),
        )
        session.operationRecorder = sink

        val result = session.navigateTree(branchA)

        assertNull(result.summaryEntry)
        assertEquals(branchA, session.conversation.leafId)
        val start = sink.records.filterIsInstance<LaneRecord.OperationStartedRecord>().single()
        assertEquals(false, start.intent.payload["summarize"]!!.jsonPrimitive.content.toBoolean())
        assertNull(start.intent.payload["summaryEntryId"])
        assertEquals(
            OperationOutcome.COMPLETED,
            sink.records.filterIsInstance<LaneRecord.OperationFinishedRecord>().single().outcome,
        )
    }

    @Test
    fun `navigating to the current leaf is a recordless no-op`() = runTest {
        val (forked, _) = forkedConversation()
        val sink = RecordingSink()
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            conversation = forked,
        )
        session.operationRecorder = sink

        val result = session.navigateTree(forked.leafId!!)
        assertTrue(!result.cancelled)
        assertTrue(sink.records.isEmpty())
    }

    @Test
    fun `user-message target re-edits and returns the editor text`() = runTest {
        val (forked, _) = forkedConversation()
        val sink = RecordingSink()
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            conversation = forked,
        )
        session.operationRecorder = sink

        val userEntryId = forked.entries.first { it.parentId == null }.id
        val result = session.navigateTree(userEntryId)

        assertEquals("hello", result.editorText)
        assertNull(session.conversation.leafId)
        assertEquals(
            OperationOutcome.COMPLETED,
            sink.records.filterIsInstance<LaneRecord.OperationFinishedRecord>().single().outcome,
        )
    }

    @Test
    fun `summarize without a provider stack is rejected`() = runTest {
        val (forked, branchA) = forkedConversation()
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            conversation = forked,
        )
        try {
            session.navigateTree(branchA, AgentSession.NavigateTreeOptions(summarize = true))
            throw AssertionError("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("No model available for summarization", e.message)
        }
    }

    @Test
    fun `failing summarization finishes the operation failed`() = runTest {
        val api = FauxApi().apply {
            responses.add(
                AssistantMessage(
                    content = emptyList(),
                    api = model.api,
                    provider = model.provider,
                    model = model.id,
                    stopReason = StopReason.ERROR,
                    errorMessage = "provider down",
                ),
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
                    apis = mapOf(model.api to api),
                ),
            ),
        )
        val (forked, branchA) = forkedConversation()
        val sink = RecordingSink()
        val session = AgentSession(
            agent = Agent(model = model, streamFn = StreamFn { _, _, _ -> flow { } }),
            conversation = forked,
            models = models,
            retrySettings = RetrySettings(enabled = false),
        )
        session.operationRecorder = sink

        try {
            session.navigateTree(branchA, AgentSession.NavigateTreeOptions(summarize = true))
            throw AssertionError("expected failure")
        } catch (e: BranchSummaryError) {
            assertEquals("provider down", e.message?.substringAfter("Branch summary failed: "))
        }
        assertEquals(
            OperationOutcome.FAILED,
            sink.records.filterIsInstance<LaneRecord.OperationFinishedRecord>().single().outcome,
        )
        // The tree is untouched: no summary entry, leaf unchanged.
        assertTrue(session.conversation.entries.none { it is BranchSummaryEntry })
        assertEquals(forked.leafId, session.conversation.leafId)
    }
}
