package works.resolve.pathfinder.codingagent.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.AgentEvent
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.AgentToolResult
import works.resolve.pathfinder.agent.AgentToolUpdateCallback
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.Tool
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FauxProvider
import works.resolve.pathfinder.ai.testing.FauxResponseFactory
import works.resolve.pathfinder.codingagent.core.compaction.CompactionSettings

class AgentSessionCompactionTest {

    private fun assistant(text: String, stopReason: StopReason = StopReason.STOP) =
        AssistantMessage(
            content = if (text.isEmpty()) emptyList() else listOf(TextContent(text)),
            api = "faux",
            provider = "faux",
            model = "faux-1",
            stopReason = stopReason,
            timestamp = System.currentTimeMillis()
        )

    @Test
    fun `compacts after a tool result before the next assistant request in the same run`() =
        runTest {
            val model = Model(
                id = "faux-1",
                name = "Faux Model",
                api = "faux",
                provider = "faux",
                baseUrl = "http://localhost:0",
                contextWindow = 2_600,
                maxTokens = 100
            )
            val faux = FauxProvider(model)
            val toolResult = "large-tool-result:" + "x".repeat(6_800)
            val largeTool = object : AgentTool {
                override val definition = Tool(
                    "large_result",
                    "Returns enough content to cross the compaction threshold",
                    buildJsonObject {}
                )
                override val label = "Large result"

                override fun validateArguments(arguments: JsonObject) = arguments

                override suspend fun execute(
                    toolCallId: String,
                    arguments: JsonObject,
                    onUpdate: AgentToolUpdateCallback
                ) = AgentToolResult(content = listOf(TextContent(toolResult)))
            }
            val session = AgentSession(
                agent = Agent(
                    model = model,
                    systemPrompt = "You are a test assistant.",
                    tools = listOf(largeTool),
                    streamFn = StreamFn(faux.models::stream)
                ),
                manager = SessionManager.create(
                    createTempDirectory("compaction-test").toFile(),
                    ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
                ),
                settingsManager = SettingsManager.inMemory(
                    Settings(
                        retry = RetrySettings(enabled = false),
                        compaction = CompactionSettings(
                            enabled = true,
                            reserveTokens = 400,
                            keepRecentTokens = 1_750
                        )
                    )
                ),
                models = faux.models
            )
            var resumedRequest = emptyList<Message>()
            val order = CopyOnWriteArrayList<String>()
            faux.setResponses(
                assistant("old-history:" + "a".repeat(800)),
                assistant("recent-history:" + "b".repeat(800)),
                assistant("", stopReason = StopReason.TOOL_USE).copy(
                    content = listOf(ToolCall("tool-1", "large_result", "{}"))
                ),
                // Extensions are out of scope, so the history and split-turn
                // summaries supplied by upstream's hook are provider responses here.
                assistant("compacted history"),
                assistant("compacted turn")
            )
            faux.appendResponse(
                FauxResponseFactory { context, _, _, _ ->
                    order.add("provider")
                    resumedRequest = context.messages
                    assistant("finished after compaction")
                }
            )

            val events = mutableListOf<AgentEvent>()
            val collector = launch {
                session.events.collect { event ->
                    events.add(event)
                    if (event is AgentEvent.CompactionStart) order.add("compaction")
                }
            }
            yield()
            session.prompt("seed old history")
            session.prompt("seed recent history")
            val agentStartsBefore = events.count { it is AgentEvent.AgentStart }
            session.prompt("run the large tool")
            collector.cancelAndJoin()

            assertEquals(listOf("compaction", "provider"), order)
            assertEquals(
                agentStartsBefore + 1,
                events.count { it is AgentEvent.AgentStart }
            )
            assertEquals(
                AgentEvent.CompactionReason.THRESHOLD,
                events.filterIsInstance<AgentEvent.CompactionStart>().last().reason
            )
            assertTrue(resumedRequest.toString().contains("compacted history"))
            assertTrue(resumedRequest.toString().contains("large-tool-result"))
            val last = session.state.value.messages.last() as AssistantMessage
            assertEquals("finished after compaction", (last.content.single() as TextContent).text)
        }

    @Test
    fun `should trigger manual compaction via compact()`() = runTest {
        val faux = FauxProvider()
        faux.setResponses(
            assistant("4"),
            assistant("6"),
            // The retained cut requires both history and split-turn summaries.
            assistant("SUMMARY"),
            assistant("SUMMARY")
        )
        val session = AgentSession(
            agent = Agent(faux.model, streamFn = StreamFn(faux.models::stream)),
            manager = SessionManager.create(
                createTempDirectory("compaction-test").toFile(),
                ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            ),
            settingsManager = SettingsManager.inMemory(
                Settings(
                    retry = RetrySettings(enabled = false),
                    compaction = CompactionSettings(
                        enabled = true,
                        reserveTokens = 16_384,
                        keepRecentTokens = 1
                    )
                )
            ),
            models = faux.models
        )

        session.prompt("What is 2+2? Reply with just the number.")
        session.prompt("What is 3+3? Reply with just the number.")
        val result = session.compact()

        assertTrue(result.summary.isNotEmpty())
        assertTrue(result.tokensBefore > 0)
        val messages = session.state.value.messages
        assertTrue(messages.isNotEmpty())
        val firstMessage = messages.first() as UserMessage
        assertTrue((firstMessage.content.single() as TextContent).text.contains(result.summary))
    }

    @Test
    fun `should emit compaction events during manual compaction`() = runTest {
        val faux = FauxProvider()
        faux.setResponses(assistant("hello"), assistant("SUMMARY"))
        val session = AgentSession(
            agent = Agent(faux.model, streamFn = StreamFn(faux.models::stream)),
            manager = SessionManager.create(
                createTempDirectory("compaction-test").toFile(),
                ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined
            ),
            settingsManager = SettingsManager.inMemory(
                Settings(
                    retry = RetrySettings(enabled = false),
                    compaction = CompactionSettings(
                        enabled = true,
                        reserveTokens = 16_384,
                        keepRecentTokens = 1
                    )
                )
            ),
            models = faux.models
        )

        val events = mutableListOf<AgentEvent>()
        val collector = launch { session.events.collect(events::add) }
        yield()
        session.prompt("Say hello")
        session.compact()
        collector.cancelAndJoin()

        val compactionEvents = events.filter {
            it is AgentEvent.CompactionStart || it is AgentEvent.CompactionEnd
        }
        assertEquals(2, compactionEvents.size)
        val start = compactionEvents[0] as AgentEvent.CompactionStart
        assertEquals(AgentEvent.CompactionReason.MANUAL, start.reason)
        val end = compactionEvents[1] as AgentEvent.CompactionEnd
        assertEquals(AgentEvent.CompactionReason.MANUAL, end.reason)
        assertFalse(end.aborted)
        assertFalse(end.willRetry)
        assertTrue(events.any { it is AgentEvent.MessageEnd })
    }
}
