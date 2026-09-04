package works.resolve.pathfinder.codingagent.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.codingagent.core.session.Conversation
import works.resolve.pathfinder.codingagent.core.session.ModelChangeEntry

class AgentSessionModelSwitchTest {

    private val modelA = Model(
        id = "model-a",
        name = "A",
        api = "openai-completions",
        provider = "provider-a",
        baseUrl = "https://a.example.invalid"
    )

    private val modelB = Model(
        id = "model-b",
        name = "B",
        api = "openai-completions",
        provider = "provider-b",
        baseUrl = "https://b.example.invalid"
    )

    private fun assistant(model: Model, text: String) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = StopReason.STOP,
        timestamp = 42L
    )

    private fun okStream(model: Model): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Start(assistant(model, "")),
        AssistantMessageEvent.Done(StopReason.STOP, assistant(model, "ok-${model.id}"))
    )

    private fun provider(
        model: Model,
        auth: (
            suspend (
                String?,
                Map<String, String>
            ) -> ResolvedAuth?
        )? = { _, _ -> ResolvedAuth(apiKey = "k") }
    ) = Provider(
        id = model.provider,
        name = model.provider,
        baseUrl = model.baseUrl,
        authResolver = auth,
        models = listOf(model),
        apis = emptyMap() // no request ever flows through Models here; StreamFn is scripted
    )

    private fun models(vararg providers: Provider): Models = Models(providers.toList())

    @Test
    fun `setModel records a model_change child of the leaf and advances the leaf`() = runTest {
        val session = AgentSession(
            agent = Agent(model = modelA) { m, _, _ -> okStream(m) },
            models = models(provider(modelA), provider(modelB))
        )

        session.prompt("hi") // leaf: user entry -> assistant entry

        session.setModel(modelB)

        assertEquals(modelB, session.model)

        val entries = session.conversation.entries
        assertEquals(3, entries.size)
        val change = entries[2] as ModelChangeEntry
        assertEquals("model_change is a child of the previous leaf", entries[1].id, change.parentId)
        assertEquals(modelB.provider, change.provider)
        assertEquals(modelB.id, change.modelId)
        assertEquals(
            "the leaf advanced to the model_change",
            change.id,
            session.conversation.leafId
        )
    }

    @Test
    fun `the next prompt routes to the switched model and preserves the transcript`() = runTest {
        val streamedModels = CopyOnWriteArrayList<Model>()
        val session = AgentSession(
            agent = Agent(model = modelA) { m, _, _ ->
                streamedModels.add(m)
                okStream(m)
            },
            models = models(provider(modelA), provider(modelB))
        )

        session.prompt("first")
        session.setModel(modelB)
        session.prompt("second")

        assertEquals(listOf(modelA, modelB), streamedModels)

        val messages = session.state.value.messages
        assertEquals(4, messages.size)
        assertEquals(
            "first",
            ((messages[0] as UserMessage).content.single() as TextContent).text
        )
        assertEquals(modelA.provider, (messages[1] as AssistantMessage).provider)
        assertEquals(
            "second",
            ((messages[2] as UserMessage).content.single() as TextContent).text
        )
        assertEquals(modelB.provider, (messages[3] as AssistantMessage).provider)
    }

    @Test
    fun `setModel without configured auth throws and changes nothing`() = runTest {
        val session = AgentSession(
            agent = Agent(model = modelA) { m, _, _ -> okStream(m) },
            models = models(provider(modelA), provider(modelB, auth = { _, _ -> null }))
        )

        val error = runCatching { session.setModel(modelB) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(
            (error as IllegalStateException).message!!.contains("No API key for provider-b/model-b")
        )

        assertEquals(modelA, session.model)
        assertEquals(0, session.conversation.entries.size)
    }

    @Test
    fun `switching during an in-flight response appends model_change mid-run without disturbing the run`() =
        runTest {
            val streamedModels = CopyOnWriteArrayList<Model>()
            lateinit var session: AgentSession
            session = AgentSession(
                agent = Agent(model = modelA) { m, _, _ ->
                    streamedModels.add(m)
                    // StreamFn is not suspending, so the mid-run switch is
                    // initiated from the flow body.
                    flow {
                        session.setModel(modelB)
                        okStream(m).collect { emit(it) }
                    }
                },
                models = models(provider(modelA), provider(modelB))
            )

            session.prompt("hi")

            assertEquals(listOf(modelA), streamedModels)
            assertEquals(modelB, session.model)

            // pi appends the model_change under whatever the leaf is: the switch
            // fired before the assistant message_end, so the change hangs off the
            // user entry and the assistant response becomes its child.
            val entries = session.conversation.entries
            assertEquals(3, entries.size)
            val user = entries[0]
            val change = entries[1] as ModelChangeEntry
            assertEquals(user.id, change.parentId)
            assertEquals(change.id, entries[2].parentId)
            assertEquals(entries[2].id, session.conversation.leafId)

            assertEquals(2, session.state.value.messages.size)
        }

    @Test
    fun `a session without a models stack cannot switch`() = runTest {
        val session = AgentSession(agent = Agent(model = modelA) { m, _, _ -> okStream(m) })

        val error = runCatching { session.setModel(modelB) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(modelA, session.model)
    }

    @Test
    fun `activeMessages projection still works after a switch`() = runTest {
        // Compaction/session-context consumers rely on the tree remaining
        // well-formed after the model_change entry.
        val session = AgentSession(
            agent = Agent(model = modelA) { m, _, _ -> okStream(m) },
            models = models(provider(modelA), provider(modelB))
        )
        session.prompt("hi")
        session.setModel(modelB)

        val projected = session.conversation.activeMessages()
        assertEquals(session.state.value.messages, projected)
    }
}
