package works.resolve.pathfinder.codingagent.core

import works.resolve.pathfinder.agent.*

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingLevel
import works.resolve.pathfinder.ai.ThinkingLevelMap
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.codingagent.core.session.Conversation
import works.resolve.pathfinder.codingagent.core.session.ThinkingLevelEntry
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSessionThinkingTest {

    private val reasoningModel = Model(
        id = "model-a",
        name = "A",
        api = "openai-completions",
        provider = "provider-a",
        baseUrl = "https://a.example.invalid",
        reasoning = true,
    )

    /** pi's xhigh/max thinkingLevelMap shape (explicit null = unsupported). */
    private val extendedModel = reasoningModel.copy(
        id = "model-extended",
        thinkingLevelMap = ThinkingLevelMap.of(
            ModelThinkingLevel.OFF to null,
            ModelThinkingLevel.MINIMAL to null,
            ModelThinkingLevel.LOW to "low",
            ModelThinkingLevel.MEDIUM to null,
            ModelThinkingLevel.HIGH to "high",
            ModelThinkingLevel.XHIGH to null,
            ModelThinkingLevel.MAX to "max",
        ),
    )

    private val plainModel = reasoningModel.copy(id = "model-plain", reasoning = false)

    private fun assistant(model: Model, text: String) = AssistantMessage(
        content = listOf(TextContent(text)),
        api = model.api,
        provider = model.provider,
        model = model.id,
        stopReason = StopReason.STOP,
        timestamp = 42L,
    )

    private fun okStream(model: Model): Flow<AssistantMessageEvent> = flowOf(
        AssistantMessageEvent.Start(assistant(model, "")),
        AssistantMessageEvent.Done(StopReason.STOP, assistant(model, "ok")),
    )

    private fun provider(model: Model): Provider = Provider(
        id = model.provider,
        name = model.provider,
        baseUrl = model.baseUrl,
        authResolver = { _, _ -> ResolvedAuth(apiKey = "k") },
        models = listOf(model),
        apis = emptyMap(), // StreamFn is scripted; no request flows through Models
    )

    private fun session(
        model: Model,
        conversation: Conversation = Conversation(emptyList(), null),
        streamFn: (Model, works.resolve.pathfinder.ai.Context, SimpleStreamOptions) -> Flow<AssistantMessageEvent> =
            { m, _, _ -> okStream(m) },
    ): AgentSession = AgentSession(
        agent = Agent(model = model, streamFn = StreamFn(streamFn)),
        conversation = conversation,
        models = Models(listOf(provider(model))),
    )

    @Test
    fun `setThinkingLevel clamps to a non-reasoning model's off`() = runTest {
        val s = session(plainModel)

        s.setThinkingLevel(ModelThinkingLevel.HIGH)

        assertEquals(ModelThinkingLevel.OFF, s.thinkingLevel)
        assertEquals("no thinking_level_change when the clamped level is unchanged", 0, s.conversation.entries.size)
    }

    @Test
    fun `setThinkingLevel appends thinking_level_change only when the level changes`() = runTest {
        val s = session(reasoningModel)

        s.setThinkingLevel(ModelThinkingLevel.MEDIUM)
        s.setThinkingLevel(ModelThinkingLevel.MEDIUM)

        assertEquals(ModelThinkingLevel.MEDIUM, s.thinkingLevel)
        assertEquals(1, s.conversation.entries.size)
        val entry = s.conversation.entries.single() as ThinkingLevelEntry
        assertEquals("medium", entry.thinkingLevel)
        assertNull("the entry is a root when the leaf is unset", entry.parentId)
        assertEquals(entry.id, s.conversation.leafId)

        s.setThinkingLevel(ModelThinkingLevel.HIGH)
        val second = s.conversation.entries[1] as ThinkingLevelEntry
        assertEquals("high", second.thinkingLevel)
        assertEquals("the second entry chains under the first", entry.id, second.parentId)
        assertEquals(second.id, s.conversation.leafId)
    }

    /** Clamping rounds up to the nearest supported level first, then down. */
    @Test
    fun `unavailable levels clamp up to the nearest supported level`() = runTest {
        val s = session(extendedModel)

        s.setThinkingLevel(ModelThinkingLevel.MEDIUM)
        assertEquals(ModelThinkingLevel.HIGH, s.thinkingLevel)

        s.setThinkingLevel(ModelThinkingLevel.OFF)
        assertEquals(ModelThinkingLevel.LOW, s.thinkingLevel)
        assertEquals(
            "each actual change appends its clamped level",
            listOf("high", "low"),
            s.conversation.entries.filterIsInstance<ThinkingLevelEntry>().map { it.thinkingLevel },
        )
    }

    @Test
    fun `the model's thinkingLevelMap shapes the available levels`() {
        val s = session(reasoningModel)
        assertEquals(
            listOf(
                ModelThinkingLevel.OFF,
                ModelThinkingLevel.MINIMAL,
                ModelThinkingLevel.LOW,
                ModelThinkingLevel.MEDIUM,
                ModelThinkingLevel.HIGH,
            ),
            works.resolve.pathfinder.ai.getSupportedThinkingLevels(s.model),
        )

        val extended = session(extendedModel)
        assertEquals(
            listOf(ModelThinkingLevel.LOW, ModelThinkingLevel.HIGH, ModelThinkingLevel.MAX),
            works.resolve.pathfinder.ai.getSupportedThinkingLevels(extended.model),
        )
    }

    /** A branch without a thinking entry folds "off", which itself clamps:
     *  the extended map marks off unsupported, so it rounds up to low. */
    @Test
    fun `init seeds the level from the branch fold clamped to the model`() {
        val folded = session(
            extendedModel,
            Conversation(emptyList(), null)
                .appendThinkingLevelChange("medium"),
        )
        assertEquals("medium clamps up to the map's high", ModelThinkingLevel.HIGH, folded.thinkingLevel)

        val withoutEntry = session(extendedModel, Conversation(emptyList(), null))
        assertEquals("the off fold clamps up to the map's low", ModelThinkingLevel.LOW, withoutEntry.thinkingLevel)

        val plainFold = session(reasoningModel, Conversation(emptyList(), null))
        assertEquals(ModelThinkingLevel.OFF, plainFold.thinkingLevel)
    }

    @Test
    fun `the per-run reasoning follows the run-start level and off sends none`() = runTest {
        val requestReasoning = CopyOnWriteArrayList<ThinkingLevel?>()
        lateinit var s: AgentSession
        s = session(reasoningModel) { m, _, options ->
            flow {
                // The UI equivalent: a level pick while the response streams.
                s.setThinkingLevel(ModelThinkingLevel.HIGH)
                requestReasoning.add(options.reasoning)
                okStream(m).collect { emit(it) }
            }
        }

        s.setThinkingLevel(ModelThinkingLevel.MEDIUM)
        s.prompt("first")
        s.prompt("second")

        assertEquals(listOf(ThinkingLevel.MEDIUM, ThinkingLevel.HIGH), requestReasoning)

        s.setThinkingLevel(ModelThinkingLevel.OFF)
        s.prompt("third")
        assertEquals(listOf(ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, null), requestReasoning)
    }
}
