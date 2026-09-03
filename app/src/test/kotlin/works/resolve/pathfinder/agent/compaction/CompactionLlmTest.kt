package works.resolve.pathfinder.agent.compaction

import works.resolve.pathfinder.ai.core.ChatApi
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.CacheRetention
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Cost
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingLevel
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.models.Models
import works.resolve.pathfinder.ai.models.Provider
import works.resolve.pathfinder.ai.models.ResolvedAuth
import works.resolve.pathfinder.ai.utils.Retry
import works.resolve.pathfinder.ai.utils.RetryCallbacks
import works.resolve.pathfinder.ai.utils.RetryPolicy
import works.resolve.pathfinder.agent.utils.addUsage
import works.resolve.pathfinder.data.sessions.CompactionEntry
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.SessionEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

class CompactionLlmTest {

    private var nextId = 0

    private fun createId(): String = "entry-${nextId++}"

    private fun createMockUsage(input: Int, output: Int, cacheRead: Int = 0, cacheWrite: Int = 0) = Usage(
        input = input,
        output = output,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite,
        totalTokens = input + output + cacheRead + cacheWrite,
    )

    private fun createUserMessage(text: String): UserMessage = UserMessage.ofText(text)

    private fun createAssistantMessage(
        text: String,
        usage: Usage = createMockUsage(100, 50),
    ): AssistantMessage = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "anthropic-messages",
        provider = "anthropic",
        model = "claude-sonnet-4-5",
        usage = usage,
        stopReason = StopReason.STOP,
    )

    private fun createMessageEntry(message: works.resolve.pathfinder.ai.core.Message, parentId: String? = null) =
        MessageEntry(id = createId(), parentId = parentId, timestamp = nextId.toLong(), message = message)

    private fun createCompactionEntry(
        summary: String,
        parentId: String? = null,
        retainedTail: List<works.resolve.pathfinder.ai.core.Message> = emptyList(),
        details: CompactionDetails? = null,
    ): CompactionEntry = CompactionEntry(
        id = createId(),
        parentId = parentId,
        timestamp = nextId.toLong(),
        summary = summary,
        tokensBefore = 1234,
        retainedTail = retainedTail,
        details = details,
    )

    private class FauxApi : ChatApi {
        val seenContexts = mutableListOf<Context>()
        val seenOptions = mutableListOf<SimpleStreamOptions>()
        val responses = ArrayDeque<AssistantMessage>()

        override fun streamSimple(
            model: Model,
            context: Context,
            options: SimpleStreamOptions,
        ): Flow<AssistantMessageEvent> = flow {
            seenContexts += context
            seenOptions += options
            val response = responses.removeFirstOrNull()
                ?: error("No faux completeSimple response queued")
            if (response.stopReason == StopReason.ERROR || response.stopReason == StopReason.ABORTED) {
                emit(AssistantMessageEvent.Error(response.stopReason, response))
            } else {
                emit(AssistantMessageEvent.Done(response.stopReason, response))
            }
        }
    }

    private class Faux(
        val api: FauxApi,
        val models: Models,
        val model: Model,
    ) {
        fun enqueue(vararg responses: AssistantMessage) {
            api.responses.addAll(responses)
        }
    }

    private var fauxCount = 0

    /** Unique provider id per fake so coexisting fakes route correctly. */
    private fun createFauxModel(reasoning: Boolean, maxTokens: Int = 8192): Faux {
        val api = FauxApi()
        val providerId = "faux-${++fauxCount}"
        val model = Model(
            id = if (reasoning) "reasoning-model" else "non-reasoning-model",
            name = "Faux",
            api = "faux-api",
            provider = providerId,
            baseUrl = "https://faux.test",
            reasoning = reasoning,
            contextWindow = 200000,
            maxTokens = maxTokens,
        )
        return Faux(
            api,
            Models(
                listOf(
                    Provider(
                        providerId,
                        providerId,
                        "https://faux.test",
                        // Ambient credential so these tests don't exercise stored-credential resolution.
                        authResolver = { _, _ -> ResolvedAuth(apiKey = "faux-key") },
                        models = listOf(model),
                        apis = mapOf("faux-api" to api),
                    ),
                ),
            ),
            model,
        )
    }

    private fun fauxAssistantMessage(
        text: String,
        stopReason: StopReason = StopReason.STOP,
        errorMessage: String? = null,
        usage: Usage = createMockUsage(100, 50),
    ): AssistantMessage = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "faux-api",
        provider = "faux",
        model = "faux-model",
        usage = usage,
        stopReason = stopReason,
        errorMessage = errorMessage,
    )

    @Test
    fun `builds session context with a compaction entry`() {
        val u1 = createMessageEntry(createUserMessage("1"))
        val a1 = createMessageEntry(createAssistantMessage("a"), u1.id)
        val u2 = createMessageEntry(createUserMessage("2"), a1.id)
        val a2 = createMessageEntry(createAssistantMessage("b"), u2.id)
        val compaction = createCompactionEntry("Summary of 1,a,2,b", a2.id, listOf(createUserMessage("2"), createAssistantMessage("b")))
        val u3 = createMessageEntry(createUserMessage("3"), compaction.id)
        val a3 = createMessageEntry(createAssistantMessage("c"), u3.id)
        val loaded = buildSessionContext(listOf<SessionEntry>(u1, a1, u2, a2, compaction, u3, a3))
        assertEquals(5, loaded.size)
        assertEquals(COMPACTION_SUMMARY_PREFIX + "Summary of 1,a,2,b" + COMPACTION_SUMMARY_SUFFIX, (loaded[0] as UserMessage).content[0].let { (it as TextContent).text })
        assertEquals(
            listOf(
                works.resolve.pathfinder.ai.core.MessageRole.USER,
                works.resolve.pathfinder.ai.core.MessageRole.USER,
                works.resolve.pathfinder.ai.core.MessageRole.ASSISTANT,
                works.resolve.pathfinder.ai.core.MessageRole.USER,
                works.resolve.pathfinder.ai.core.MessageRole.ASSISTANT,
            ),
            loaded.map { it.role },
        )
    }

    private fun preparationValue(result: CompactionResult<CompactionPreparation?>): CompactionPreparation? =
        when (result) {
            is CompactionResult.Ok -> result.value
            is CompactionResult.Err -> error(result.error.message ?: "compaction failed")
        }

    @Test
    fun `prepares compaction using the latest compaction summary as previousSummary`() {
        val u1 = createMessageEntry(createUserMessage("user msg 1"))
        val a1 = createMessageEntry(createAssistantMessage("assistant msg 1"), u1.id)
        val u2 = createMessageEntry(createUserMessage("user msg 2"), a1.id)
        val a2 = createMessageEntry(createAssistantMessage("assistant msg 2", createMockUsage(5000, 1000)), u2.id)
        val compaction1 = createCompactionEntry("First summary", a2.id)
        val u3 = createMessageEntry(createUserMessage("user msg 3"), compaction1.id)
        val a3 = createMessageEntry(createAssistantMessage("assistant msg 3", createMockUsage(8000, 2000)), u3.id)
        val pathEntries = listOf<SessionEntry>(u1, a1, u2, a2, compaction1, u3, a3)
        val preparation = preparationValue(prepareCompaction(pathEntries, DEFAULT_COMPACTION_SETTINGS))!!
        assertEquals("First summary", preparation.previousSummary)
        assertTrue(preparation.retainedTail.isNotEmpty())
        assertEquals(estimateContextTokens(buildSessionContext(pathEntries)).tokens, preparation.tokensBefore)
    }

    @Test
    fun `carries a previous compaction's retained tail into the next preparation`() {
        val retainedUser = createUserMessage("retained user")
        val retainedAssistant = createAssistantMessage("retained assistant")
        val compaction = createCompactionEntry("previous summary", null, listOf(retainedUser, retainedAssistant))
        val user = createMessageEntry(createUserMessage("new user"), compaction.id)
        val assistant = createMessageEntry(createAssistantMessage("new assistant"), user.id)

        val preparation = preparationValue(
            prepareCompaction(
                listOf(compaction, user, assistant),
                CompactionSettings(enabled = true, reserveTokens = 100, keepRecentTokens = 1),
            ),
        )!!
        assertEquals("previous summary", preparation.previousSummary)
        assertEquals(
            listOf(retainedUser, retainedAssistant, user.message, assistant.message),
            preparation.messagesToSummarize + preparation.turnPrefixMessages + preparation.retainedTail,
        )
    }

    @Test
    fun `prepares split-turn compaction with prior file-operation details`() {
        val u1 = createMessageEntry(createUserMessage("user msg 1"))
        val assistantMessage = createAssistantMessage("assistant msg 1").copy(
            content = listOf(ToolCall(id = "tool-1", name = "write", arguments = """{"path":"written.ts"}""")),
        )
        val a1 = createMessageEntry(assistantMessage, u1.id)
        val compaction1 = createCompactionEntry(
            "First summary",
            a1.id,
            details = CompactionDetails(readFiles = listOf("old-read.ts"), modifiedFiles = listOf("old-edit.ts", "written.ts")),
        )
        val u2 = createMessageEntry(createUserMessage("large turn"), compaction1.id)
        val a2 = createMessageEntry(createAssistantMessage("large assistant message"), u2.id)
        val preparation = preparationValue(
            prepareCompaction(
                listOf(u1, a1, compaction1, u2, a2),
                CompactionSettings(enabled = true, reserveTokens = 100, keepRecentTokens = 1),
            ),
        )!!

        assertEquals("First summary", preparation.previousSummary)
        assertTrue(preparation.isSplitTurn)
        assertEquals(listOf(works.resolve.pathfinder.ai.core.MessageRole.USER), preparation.turnPrefixMessages.map { it.role })
        assertTrue("old-read.ts" in preparation.fileOps.read)
        assertTrue("old-edit.ts" in preparation.fileOps.edited)
        assertTrue("written.ts" in preparation.fileOps.edited)
    }

    @Test
    fun `does not prepare compaction when there is nothing valid to compact`() {
        val compaction = createCompactionEntry("already compacted")
        assertNull(preparationValue(prepareCompaction(listOf<SessionEntry>(compaction), DEFAULT_COMPACTION_SETTINGS)))
        assertNull(preparationValue(prepareCompaction(emptyList(), DEFAULT_COMPACTION_SETTINGS)))
    }

    private fun firstPrompt(faux: Faux): String {
        val message = faux.api.seenContexts.first().messages.single() as UserMessage
        return (message.content.single() as TextContent).text
    }

    @Test
    fun `passes reasoning through generateSummary only for reasoning models with thinking enabled`() = runTest {
        val messages = listOf(createUserMessage("Summarize this."))

        val reasoning = createFauxModel(reasoning = true)
        reasoning.enqueue(fauxAssistantMessage("## Goal\nTest summary"))
        generateSummary(messages, reasoning.models, reasoning.model, 2000, thinkingLevel = ModelThinkingLevel.MEDIUM, clock = FakeClock())
        assertEquals(ThinkingLevel.MEDIUM, reasoning.api.seenOptions[0].reasoning)

        val off = createFauxModel(reasoning = true)
        off.enqueue(fauxAssistantMessage("## Goal\nTest summary"))
        generateSummary(messages, off.models, off.model, 2000, thinkingLevel = ModelThinkingLevel.OFF, clock = FakeClock())
        assertNull(off.api.seenOptions[0].reasoning)

        val nonReasoning = createFauxModel(reasoning = false)
        nonReasoning.enqueue(fauxAssistantMessage("## Goal\nTest summary"))
        generateSummary(messages, nonReasoning.models, nonReasoning.model, 2000, thinkingLevel = ModelThinkingLevel.MEDIUM, clock = FakeClock())
        assertNull(nonReasoning.api.seenOptions[0].reasoning)
    }

    @Test
    fun `includes previous summaries and custom instructions in generateSummary prompts`() = runTest {
        val messages = listOf(createUserMessage("Summarize this."))
        val faux = createFauxModel(reasoning = false)
        faux.enqueue(fauxAssistantMessage("## Goal\nTest summary"))

        val summary = when (
            val result = generateSummaryWithUsage(
                messages, faux.models, faux.model, 2000,
                customInstructions = "focus", previousSummary = "old summary",
                clock = FakeClock(),
            )
        ) {
            is CompactionResult.Ok -> result.value
            is CompactionResult.Err -> error(result.error.message ?: "compaction failed")
        }

        assertTrue("Test summary" in summary.text)
        assertTrue(summary.usage.input > 0)
        assertTrue(summary.usage.output > 0)
        assertEquals(
            summary.usage.input + summary.usage.output + summary.usage.cacheRead + summary.usage.cacheWrite,
            summary.usage.totalTokens,
        )
        val prompt = firstPrompt(faux)
        assertTrue("<previous-summary>\nold summary\n</previous-summary>" in prompt)
        assertTrue("Additional focus: focus" in prompt)
        assertTrue(prompt.startsWith("<conversation>\n[User]: Summarize this.\n</conversation>\n\n"))
    }

    @Test
    fun `preserves the string result from generateSummary`() = runTest {
        val messages = listOf(createUserMessage("Summarize this."))
        val faux = createFauxModel(reasoning = false)
        faux.enqueue(fauxAssistantMessage("## Goal\nTest summary"))

        assertEquals(
            "## Goal\nTest summary",
            when (val result = generateSummary(messages, faux.models, faux.model, 2000, clock = FakeClock())) {
                is CompactionResult.Ok -> result.value
                is CompactionResult.Err -> error(result.error.message ?: "compaction failed")
            },
        )
    }

    @Test
    fun `returns error results for failed or aborted summary generations`() = runTest {
        val messages = listOf(createUserMessage("Summarize this."))

        val errorFaux = createFauxModel(reasoning = false)
        errorFaux.enqueue(fauxAssistantMessage("", stopReason = StopReason.ERROR, errorMessage = "boom"))
        when (val errorResult = generateSummary(messages, errorFaux.models, errorFaux.model, 2000, clock = FakeClock())) {
            is CompactionResult.Err -> {
                assertEquals(CompactionErrorCode.SUMMARIZATION_FAILED, errorResult.error.code)
                assertEquals("Summarization failed: boom", errorResult.error.message)
            }
            is CompactionResult.Ok -> error("expected error")
        }

        val abortedFaux = createFauxModel(reasoning = false)
        abortedFaux.enqueue(fauxAssistantMessage("", stopReason = StopReason.ABORTED, errorMessage = "stopped"))
        when (val abortedResult = generateSummary(messages, abortedFaux.models, abortedFaux.model, 2000, clock = FakeClock())) {
            is CompactionResult.Err -> {
                assertEquals(CompactionErrorCode.ABORTED, abortedResult.error.code)
                assertEquals("stopped", abortedResult.error.message)
            }
            is CompactionResult.Ok -> error("expected error")
        }
    }

    @Test
    fun `combines usage across all reported fields`() {
        val first = Usage(
            input = 10, output = 5, cacheRead = 3, cacheWrite = 2, cacheWrite1h = 1, reasoning = 7,
            totalTokens = 20, cost = Cost(input = 1.0, output = 2.0, cacheRead = 3.0, cacheWrite = 4.0, total = 10.0),
        )
        val second = Usage(
            input = 1, output = 2, cacheRead = 3, cacheWrite = 4, cacheWrite1h = 0, reasoning = 3,
            totalTokens = 10, cost = Cost(input = 0.5, output = 0.5, cacheRead = 0.5, cacheWrite = 0.5, total = 2.0),
        )
        val combined = addUsage(first, second)
        assertEquals(11, combined.input)
        assertEquals(7, combined.output)
        assertEquals(6, combined.cacheRead)
        assertEquals(6, combined.cacheWrite)
        assertEquals(1, combined.cacheWrite1h)
        assertEquals(10, combined.reasoning)
        assertEquals(30, combined.totalTokens)
        assertEquals(12.0, combined.cost.total)
    }

    @Test
    fun `completeSimpleWithRetries retries transient errors, isolates requests, and reports callbacks`() = runTest {
        val faux = createFauxModel(reasoning = false)
        faux.enqueue(
            fauxAssistantMessage("", stopReason = StopReason.ERROR, errorMessage = "503 service unavailable"),
            fauxAssistantMessage("recovered"),
        )
        val sleeps = mutableListOf<Long>()
        val scheduled = mutableListOf<Int>()
        var attemptStarted = 0
        var finished: Triple<Boolean, Int, String?>? = null

        val response = completeSimpleWithRetries(
            faux.models,
            faux.model,
            Context(messages = emptyList()),
            SimpleStreamOptions(),
            retry = RetryPolicy(enabled = true, maxRetries = 2, baseDelayMs = 5),
            callbacks = RetryCallbacks(
                onRetryScheduled = { attempt, _, _, _ -> scheduled += attempt },
                onRetryAttemptStart = { attemptStarted++ },
                onRetryFinished = { success, attempt, error -> finished = Triple(success, attempt, error) },
            ),
            retryRunner = Retry(sleep = { sleeps += it }),
        )

        assertEquals("recovered", (response.content.single() as TextContent).text)
        assertEquals(listOf(5L), sleeps)
        assertEquals(listOf(1), scheduled)
        assertEquals(1, attemptStarted)
        assertEquals(Triple(true, 1, null), finished)
        // The sessionId is drawn once per completeSimpleWithRetries invocation:
        // retries share it, separate calls don't.
        assertEquals(
            listOf(CacheRetention.NONE, CacheRetention.NONE),
            faux.api.seenOptions.map { it.cacheRetention },
        )
        assertEquals(faux.api.seenOptions[0].sessionId, faux.api.seenOptions[1].sessionId)
        faux.enqueue(fauxAssistantMessage("again"))
        completeSimpleWithRetries(
            faux.models,
            faux.model,
            Context(messages = emptyList()),
            SimpleStreamOptions(),
            retryRunner = Retry(sleep = {}),
        )
        assertNotEquals(faux.api.seenOptions[0].sessionId, faux.api.seenOptions[2].sessionId)
    }

    @Test
    fun `completeSimpleWithRetries returns the final error after exhausting retries`() = runTest {
        val faux = createFauxModel(reasoning = false)
        repeat(3) {
            faux.enqueue(fauxAssistantMessage("", stopReason = StopReason.ERROR, errorMessage = "503 service unavailable"))
        }
        var finished: Triple<Boolean, Int, String?>? = null

        val response = completeSimpleWithRetries(
            faux.models,
            faux.model,
            Context(messages = emptyList()),
            SimpleStreamOptions(),
            retry = RetryPolicy(enabled = true, maxRetries = 1, baseDelayMs = 1),
            callbacks = RetryCallbacks(
                onRetryFinished = { success, attempt, error -> finished = Triple(success, attempt, error) },
            ),
            retryRunner = Retry(sleep = {}),
        )

        assertEquals(StopReason.ERROR, response.stopReason)
        assertEquals("503 service unavailable", response.errorMessage)
        assertEquals(2, faux.api.seenOptions.size)
        assertEquals(Triple(false, 1, "503 service unavailable"), finished)
    }

    private fun preparation(
        messagesToSummarize: List<works.resolve.pathfinder.ai.core.Message>,
        turnPrefixMessages: List<works.resolve.pathfinder.ai.core.Message>,
        isSplitTurn: Boolean,
        tokensBefore: Int = 100,
        settings: CompactionSettings = CompactionSettings(enabled = true, reserveTokens = 2000, keepRecentTokens = 20),
    ) = CompactionPreparation(
        messagesToSummarize = messagesToSummarize,
        turnPrefixMessages = turnPrefixMessages,
        retainedTail = messagesToSummarize,
        isSplitTurn = isSplitTurn,
        tokensBefore = tokensBefore,
        previousSummary = null,
        fileOps = createFileOps(),
        settings = settings,
    )

    private fun compactValue(result: CompactionResult<CompactResult>): CompactResult = when (result) {
        is CompactionResult.Ok -> result.value
        is CompactionResult.Err -> error(result.error.message ?: "compaction failed")
    }

    @Test
    fun `clamps compaction summary maxTokens to the model output cap`() = runTest {
        val messages = listOf<works.resolve.pathfinder.ai.core.Message>(createUserMessage("Summarize this."))
        val faux = createFauxModel(reasoning = false, maxTokens = 128000)
        faux.enqueue(
            fauxAssistantMessage("## Goal\nTest summary"),
            fauxAssistantMessage("## Original Request\nTest summary"),
        )

        compact(
            preparation(messages, messages, isSplitTurn = true, tokensBefore = 600000, settings = CompactionSettings(enabled = true, reserveTokens = 500000, keepRecentTokens = 20000)),
            faux.models,
            faux.model,
            clock = FakeClock(),
        )

        assertEquals(listOf(128000, 128000), faux.api.seenOptions.map { it.maxTokens })
        assertEquals(listOf(CacheRetention.NONE, CacheRetention.NONE), faux.api.seenOptions.map { it.cacheRetention })
        val sessionIds = faux.api.seenOptions.map { it.sessionId }
        assertNotEquals(sessionIds[0], sessionIds[1])
    }

    @Test
    fun `returns compaction error results without throwing`() = runTest {
        val messages = listOf<works.resolve.pathfinder.ai.core.Message>(createUserMessage("Summarize this."))
        val faux = createFauxModel(reasoning = false)
        faux.enqueue(fauxAssistantMessage("", stopReason = StopReason.ERROR, errorMessage = "history failed"))

        when (val result = compact(preparation(messages, emptyList(), isSplitTurn = false), faux.models, faux.model, clock = FakeClock())) {
            is CompactionResult.Err -> {
                assertEquals(CompactionErrorCode.SUMMARIZATION_FAILED, result.error.code)
                assertEquals("Summarization failed: history failed", result.error.message)
            }
            is CompactionResult.Ok -> error("expected error")
        }
    }

    @Test
    fun `combines usage for split-turn compaction summaries`() = runTest {
        val messages = listOf<works.resolve.pathfinder.ai.core.Message>(createUserMessage("Summarize this."))
        val faux = createFauxModel(reasoning = false)
        faux.enqueue(
            fauxAssistantMessage("history summary", usage = createMockUsage(1, 2, 3, 4)),
            fauxAssistantMessage("turn prefix summary", usage = createMockUsage(5, 6, 7, 8)),
        )

        val result = compactValue(compact(preparation(messages, messages, isSplitTurn = true), faux.models, faux.model, clock = FakeClock()))

        assertEquals(createMockUsage(6, 8, 10, 12), result.usage)
    }

    @Test
    fun `passes reasoning through turn-prefix summaries when enabled`() = runTest {
        val messages = listOf<works.resolve.pathfinder.ai.core.Message>(createUserMessage("Summarize this."))
        val faux = createFauxModel(reasoning = true)
        faux.enqueue(fauxAssistantMessage("## Original Request\nTest summary"))

        compact(preparation(emptyList(), messages, isSplitTurn = true), faux.models, faux.model, thinkingLevel = ModelThinkingLevel.HIGH, clock = FakeClock())

        assertEquals(ThinkingLevel.HIGH, faux.api.seenOptions[0].reasoning)
    }

    @Test
    fun `returns turn-prefix compaction errors without throwing`() = runTest {
        val messages = listOf<works.resolve.pathfinder.ai.core.Message>(createUserMessage("Summarize this."))
        val prep = preparation(emptyList(), messages, isSplitTurn = true)

        val faux = createFauxModel(reasoning = false)
        faux.enqueue(fauxAssistantMessage("", stopReason = StopReason.ERROR, errorMessage = "prefix failed"))
        when (val result = compact(prep, faux.models, faux.model, clock = FakeClock())) {
            is CompactionResult.Err -> {
                assertEquals(CompactionErrorCode.SUMMARIZATION_FAILED, result.error.code)
                assertEquals("Turn prefix summarization failed: prefix failed", result.error.message)
            }
            is CompactionResult.Ok -> error("expected error")
        }

        val abortedFaux = createFauxModel(reasoning = false)
        abortedFaux.enqueue(fauxAssistantMessage("", stopReason = StopReason.ABORTED, errorMessage = "prefix stopped"))
        when (val result = compact(prep, abortedFaux.models, abortedFaux.model, clock = FakeClock())) {
            is CompactionResult.Err -> {
                assertEquals(CompactionErrorCode.ABORTED, result.error.code)
                assertEquals("prefix stopped", result.error.message)
            }
            is CompactionResult.Ok -> error("expected error")
        }
    }

    @Test
    fun `returns a compaction result with file details`() = runTest {
        val u1 = createMessageEntry(createUserMessage("read a file"))
        val assistantMessage = createAssistantMessage("calling tool", createMockUsage(1000, 200)).copy(
            content = listOf(ToolCall(id = "tool-1", name = "read", arguments = """{"path":"src/index.ts"}""")),
        )
        val a1 = createMessageEntry(assistantMessage, u1.id)
        val u2 = createMessageEntry(createUserMessage("continue"), a1.id)
        val a2 = createMessageEntry(createAssistantMessage("done", createMockUsage(4000, 500)), u2.id)
        val prep = preparationValue(
            prepareCompaction(
                listOf<SessionEntry>(u1, a1, u2, a2),
                CompactionSettings(enabled = true, reserveTokens = 2000, keepRecentTokens = 1),
            ),
        )!!
        val faux = createFauxModel(reasoning = false)
        faux.enqueue(
            fauxAssistantMessage("## Goal\nTest summary"),
            fauxAssistantMessage("## Original Request\nTest summary"),
        )
        val result = compactValue(compact(prep, faux.models, faux.model, clock = FakeClock()))
        assertTrue(result.summary.isNotEmpty())
        assertTrue((result.usage?.totalTokens ?: 0) > 0)
        assertTrue(result.retainedTail.isNotEmpty())
        assertEquals(CompactionDetails(readFiles = listOf("src/index.ts"), modifiedFiles = emptyList()), result.details)
        assertTrue("<read-files>\nsrc/index.ts\n</read-files>" in result.summary)
    }
}
