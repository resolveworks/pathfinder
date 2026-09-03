package works.resolve.pathfinder.codingagent.core

import works.resolve.pathfinder.agent.*

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.codingagent.core.session.CompactionEntry
import works.resolve.pathfinder.codingagent.core.session.LaneRecord
import works.resolve.pathfinder.codingagent.core.session.OperationIntent
import works.resolve.pathfinder.codingagent.core.session.OperationOutcome
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOperationRecordsTest {

    private class RecordingSink : OperationLifecycleRecorder {
        val records = CopyOnWriteArrayList<LaneRecord>()
        val operations = mutableListOf<String>()
        override suspend fun append(record: LaneRecord) {
            records.add(record)
        }

        override fun appendBestEffort(record: LaneRecord) {
            records.add(record)
        }
    }

    private val model = Model(
        id = "glm-4.6",
        name = "GLM",
        api = "openai-completions",
        provider = "zai",
        baseUrl = "https://example.invalid",
        contextWindow = 200_000,
        maxTokens = 8_192,
    )

    private fun assistant(
        text: String = "hello",
        stopReason: StopReason = StopReason.STOP,
        usage: Usage = Usage(),
    ) = AssistantMessage(
        content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = stopReason,
        usage = usage,
        timestamp = 42L,
    )

    private class FauxApi : ChatApi {
        val responses = ArrayDeque<AssistantMessage>()
        var failSummaries = false

        override fun streamSimple(model: Model, context: Context, options: SimpleStreamOptions): Flow<AssistantMessageEvent> =
            flow {
                val response = responses.removeFirstOrNull() ?: error("No faux summary response queued")
                if (response.stopReason == StopReason.ERROR) {
                    emit(AssistantMessageEvent.Error(response.stopReason, response))
                } else {
                    emit(AssistantMessageEvent.Done(response.stopReason, response))
                }
            }
    }

    private fun fauxModels(api: FauxApi): Models = Models(
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

    private fun session(
        sink: RecordingSink,
        streamFn: StreamFn,
        models: Models? = null,
    ): AgentSession = AgentSession(
        agent = Agent(model = model, streamFn = streamFn),
        models = models,
    ).apply { operationRecorder = sink }

    @Test
    fun `successful prompt records started and completed with the leaf as source`() = runTest {
        val sink = RecordingSink()
        val streams = ArrayDeque<Flow<AssistantMessageEvent>>()
        val agent = session(sink, StreamFn { _, _, _ -> streams.removeFirst() })
        streams.add(
            flowOf(
                AssistantMessageEvent.Start(assistant("")),
                AssistantMessageEvent.Done(StopReason.STOP, assistant("done")),
            ),
        )
        agent.prompt("hi")

        assertEquals(2, sink.records.size)
        val started = sink.records[0] as LaneRecord.OperationStartedRecord
        assertEquals(OperationIntent.Kind.RUN, started.intent.kind)
        assertEquals(null, started.sourceLeafId)
        val finished = sink.records[1] as LaneRecord.OperationFinishedRecord
        assertEquals(started.id, finished.runId)
        assertEquals(OperationOutcome.COMPLETED, finished.outcome)

        streams.add(
            flowOf(
                AssistantMessageEvent.Start(assistant("")),
                AssistantMessageEvent.Done(StopReason.STOP, assistant("done2")),
            ),
        )
        agent.prompt("again")
        val second = sink.records[2] as LaneRecord.OperationStartedRecord
        assertEquals(agent.conversation.entries[1].id, second.sourceLeafId)
    }

    @Test
    fun `abort records abort_requested before aborted finish`() = runTest {
        val sink = RecordingSink()
        val streams = ArrayDeque<Flow<AssistantMessageEvent>>()
        val agent = session(sink, StreamFn { _, _, _ -> streams.removeFirst() })
        streams.add(
            flow {
                emit(AssistantMessageEvent.Start(assistant("")))
                awaitCancellation()
            },
        )
        val job = launch { agent.prompt("hi") }
        withTimeout(5_000) { while (!agent.state.value.isStreaming) yield() }
        agent.abort()
        job.cancelAndJoin()

        val kinds = sink.records.map { it::class.simpleName }
        assertEquals(
            listOf("OperationStartedRecord", "AbortRequestedRecord", "OperationFinishedRecord"),
            kinds,
        )
        val started = sink.records[0] as LaneRecord.OperationStartedRecord
        assertEquals(started.id, (sink.records[1] as LaneRecord.AbortRequestedRecord).runId)
        val finished = sink.records[2] as LaneRecord.OperationFinishedRecord
        assertEquals(OperationOutcome.ABORTED, finished.outcome)
    }

    @Test
    fun `threshold compaction records run finish, compaction operation, and completion`() = runTest {
        val sink = RecordingSink()
        val api = FauxApi()
        api.responses.add(assistant("SUMMARY", usage = Usage(input = 100, output = 50, totalTokens = 150)))
        val streams = ArrayDeque<Flow<AssistantMessageEvent>>()
        val agent = session(
            sink,
            StreamFn { _, _, _ -> streams.removeFirst() },
            models = fauxModels(api),
        )
        streams.add(
            flowOf(
                AssistantMessageEvent.Start(assistant("")),
                AssistantMessageEvent.Done(
                    StopReason.STOP,
                    assistant("long", usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)),
                ),
            ),
        )
        agent.prompt("hi")

        assertEquals(4, sink.records.size)
        val runStarted = sink.records[0] as LaneRecord.OperationStartedRecord
        assertEquals(OperationIntent.Kind.RUN, runStarted.intent.kind)
        assertEquals(runStarted.id, (sink.records[1] as LaneRecord.OperationFinishedRecord).runId)
        assertEquals(OperationOutcome.COMPLETED, (sink.records[1] as LaneRecord.OperationFinishedRecord).outcome)

        val compactionStarted = sink.records[2] as LaneRecord.OperationStartedRecord
        assertEquals(OperationIntent.Kind.COMPACTION, compactionStarted.intent.kind)
        val resultEntryId = compactionStarted.intent.payload["resultEntryId"]!!.toString().trim('"')
        val compactionEntry = agent.conversation.entries.last() as CompactionEntry
        assertEquals(resultEntryId, compactionEntry.id)
        assertEquals(compactionEntry.parentId, compactionStarted.sourceLeafId)

        val compactionFinished = sink.records[3] as LaneRecord.OperationFinishedRecord
        assertEquals(compactionStarted.id, compactionFinished.runId)
        assertEquals(OperationOutcome.COMPLETED, compactionFinished.outcome)
    }

    @Test
    fun `failed summarization finishes the compaction operation as failed`() = runTest {
        val sink = RecordingSink()
        val api = FauxApi()
        api.responses.add(assistant("boom", stopReason = StopReason.ERROR))
        val streams = ArrayDeque<Flow<AssistantMessageEvent>>()
        val agent = session(
            sink,
            StreamFn { _, _, _ -> streams.removeFirst() },
            models = fauxModels(api),
        )
        streams.add(
            flowOf(
                AssistantMessageEvent.Start(assistant("")),
                AssistantMessageEvent.Done(
                    StopReason.STOP,
                    assistant("long", usage = Usage(input = 190_000, output = 10, totalTokens = 190_010)),
                ),
            ),
        )
        agent.prompt("hi")

        val compactionFinished = sink.records.last() as LaneRecord.OperationFinishedRecord
        assertEquals(OperationOutcome.FAILED, compactionFinished.outcome)
        val error = compactionFinished.error
        assertNotNull(error)
        assertTrue(error!!.code.isNotBlank())
        assertTrue(error.message.isNotEmpty())
        assertTrue(agent.conversation.entries.none { it is CompactionEntry })
    }
}
