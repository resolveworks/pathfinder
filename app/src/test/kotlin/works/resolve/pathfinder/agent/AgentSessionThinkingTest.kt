package works.resolve.pathfinder.agent

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingLevel
import works.resolve.pathfinder.ai.core.ThinkingLevelMap
import works.resolve.pathfinder.ai.models.Models
import works.resolve.pathfinder.ai.models.Provider
import works.resolve.pathfinder.ai.models.ResolvedAuth
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.sessions.ThinkingLevelEntry
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

/**
 * AgentSession.setThinkingLevel tests, ported from pi's
 * test/suite/agent-session-model-extension.test.ts ("clamps thinking levels
 * to model capabilities", "only persists ... when requested", level-cycling
 * order) and the sdk.ts session-load fold: clamping to the model's supported
 * levels, the thinking_level_change tree entry with pi's branch ordering
 * (appended only when the level actually changes), init seeding from the
 * branch fold, and the per-run request `reasoning` snapshot
 * (pi agent.ts:450 createLoopConfig).
 *
 * Exclusions (documented on [AgentSession.setThinkingLevel]): pi's
 * per-model thinking overrides, cycleThinkingLevel, and `persist` (the app
 * layer owns settings).
 */
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
        streamFn: (Model, works.resolve.pathfinder.ai.core.Context, SimpleStreamOptions) -> Flow<AssistantMessageEvent> =
            { m, _, _ -> okStream(m) },
    ): AgentSession = AgentSession(
        agent = Agent(model = model, streamFn = StreamFn(streamFn)),
        conversation = conversation,
        models = Models(listOf(provider(model))),
    )

    /**
     * pi "clamps thinking levels to model capabilities": a non-reasoning
     * model's only level is off, so any request clamps there — and since the
     * clamped level equals the current one, no entry is appended.
     */
    @Test
    fun `setThinkingLevel clamps to a non-reasoning model's off`() = runTest {
        val s = session(plainModel)

        s.setThinkingLevel(ModelThinkingLevel.HIGH)

        assertEquals(ModelThinkingLevel.OFF, s.thinkingLevel)
        assertEquals("no thinking_level_change when the clamped level is unchanged", 0, s.conversation.entries.size)
    }

    /**
     * pi agent-session.ts:1797-1810: the agent state is assigned the clamped
     * level unconditionally, but the thinking_level_change entry lands only
     * when the level actually changes.
     */
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

    /**
     * pi setThinkingLevel's clamp (agent-session.ts:1795): unavailable levels
     * clamp via clampThinkingLevel — round up first, then down. The extended
     * map supports only low/high/max, so medium rounds up to high and off
     * rounds up to low.
     */
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

    /**
     * pi getAvailableThinkingLevels order (agent-session.ts:1837-1841 over
     * getSupportedThinkingLevels): canonical order with explicit xhigh/max
     * mappings, and explicit-null mappings excluded.
     */
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
            works.resolve.pathfinder.ai.core.getSupportedThinkingLevels(s.model),
        )

        val extended = session(extendedModel)
        assertEquals(
            listOf(ModelThinkingLevel.LOW, ModelThinkingLevel.HIGH, ModelThinkingLevel.MAX),
            works.resolve.pathfinder.ai.core.getSupportedThinkingLevels(extended.model),
        )
    }

    /**
     * pi sdk.ts:229-253: the agent's initial thinking level is the branch's
     * thinking_level_change fold, clamped to the model; a branch without a
     * thinking entry folds "off" (the app layer seeds the default entry
     * before adoption) — and the fold clamps like any requested level, so
     * "off" on a model whose map disables it rounds up (pi's clamp:
     * up first, then down).
     */
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

    /**
     * pi agent.ts:450 (createLoopConfig): the run's request carries the
     * run-start thinking level as its reasoning option; a level switched
     * mid-run applies to the next prompt, and off sends no reasoning.
     */
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

        // First run started at medium (switched mid-run to high); the second
        // run started at high.
        assertEquals(listOf(ThinkingLevel.MEDIUM, ThinkingLevel.HIGH), requestReasoning)

        // Off maps to no reasoning parameter (pi: undefined).
        s.setThinkingLevel(ModelThinkingLevel.OFF)
        s.prompt("third")
        assertEquals(listOf(ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, null), requestReasoning)
    }
}
