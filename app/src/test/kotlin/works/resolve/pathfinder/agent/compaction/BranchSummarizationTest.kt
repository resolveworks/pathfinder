package works.resolve.pathfinder.agent.compaction

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.api.ChatApi
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.AssistantMessageEvent
import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.Cost
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.models.Models
import works.resolve.pathfinder.ai.models.Provider
import works.resolve.pathfinder.ai.models.ResolvedAuth
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.data.sessions.BranchSummaryEntry
import works.resolve.pathfinder.data.sessions.CompactionEntry
import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.sessions.MessageEntry
import works.resolve.pathfinder.data.sessions.ModelChangeEntry
import works.resolve.pathfinder.data.sessions.SessionEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BranchSummarizationTest {

    private var nextId = 0

    private fun createId(): String = "entry-${nextId++}"

    private fun user(text: String) = UserMessage.ofText(text, timestamp = nextId.toLong())

    private fun assistant(text: String): AssistantMessage = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "anthropic-messages",
        provider = "anthropic",
        model = "claude-sonnet-4-5",
        usage = Usage(
            input = 1,
            output = 1,
            reasoning = 0,
            totalTokens = 2,
            cost = Cost(0.0, 0.0, 0.0, 0.0, 0.0),
        ),
        stopReason = StopReason.STOP,
        timestamp = nextId.toLong(),
    )

    private fun messageEntry(message: Message, parentId: String? = null, terminate: Boolean? = null) =
        MessageEntry(
            id = createId(),
            parentId = parentId,
            timestamp = nextId.toLong(),
            message = message,
            terminate = terminate,
        )

    private fun branchSummaryEntry(
        summary: String,
        parentId: String?,
        fromId: String,
        details: JsonObject? = null,
    ) = BranchSummaryEntry(
        id = createId(),
        parentId = parentId,
        timestamp = nextId.toLong(),
        fromId = fromId,
        summary = summary,
        details = details,
    )

    @Test
    fun `collects nothing when there is no old leaf`() {
        val root = messageEntry(user("root"))
        val conversation = Conversation(listOf(root), root.id)
        val result = collectEntriesForBranchSummary(conversation, oldLeafId = null, targetId = root.id)
        assertTrue(result.entries.isEmpty())
        assertNull(result.commonAncestorId)
    }

    @Test
    fun `collects the abandoned branch up to the deepest common ancestor`() {
        val root = messageEntry(user("root"))
        val a1 = messageEntry(user("a1"), root.id)
        val a2 = messageEntry(assistant("a2"), a1.id)
        val b1 = messageEntry(user("b1"), root.id)
        val conversation = Conversation(listOf(root, a1, a2, b1), b1.id)

        val result = collectEntriesForBranchSummary(conversation, oldLeafId = a2.id, targetId = b1.id)

        assertEquals(root.id, result.commonAncestorId)
        assertEquals(listOf<SessionEntry>(a1, a2), result.entries)
    }

    @Test
    fun `navigating to an ancestor summarizes only the entries after it`() {
        val root = messageEntry(user("root"))
        val a1 = messageEntry(user("a1"), root.id)
        val a2 = messageEntry(assistant("a2"), a1.id)
        val conversation = Conversation(listOf(root, a1, a2), a2.id)

        val result = collectEntriesForBranchSummary(conversation, oldLeafId = a2.id, targetId = a1.id)

        assertEquals(a1.id, result.commonAncestorId)
        assertEquals(listOf<SessionEntry>(a2), result.entries)
    }

    @Test
    fun `navigating to the current leaf collects nothing`() {
        val root = messageEntry(user("root"))
        val a1 = messageEntry(user("a1"), root.id)
        val conversation = Conversation(listOf(root, a1), a1.id)

        val result = collectEntriesForBranchSummary(conversation, oldLeafId = a1.id, targetId = a1.id)

        assertEquals(a1.id, result.commonAncestorId)
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun `preparation projects message branch_summary and compaction entries`() {
        val root = messageEntry(user("root"))
        val summary = branchSummaryEntry("explored elsewhere", root.id, fromId = "elsewhere")
        val entries = listOf(
            root,
            summary,
            messageEntry(assistant("answer"), summary.id),
            CompactionEntry(
                id = createId(),
                parentId = null,
                timestamp = 0,
                summary = "compacted",
                retainedTail = emptyList(),
                tokensBefore = 10,
            ),
            ModelChangeEntry(createId(), 0, null, 0, provider = "p", modelId = "m"),
        )

        val preparation = prepareBranchEntries(entries, tokenBudget = 0)

        assertEquals(4, preparation.messages.size)
        assertEquals(user("root").content, preparation.messages[0].let { (it as UserMessage).content })
        val branch = preparation.messages[1] as UserMessage
        assertTrue(
            (branch.content[0] as TextContent).text.startsWith(BRANCH_SUMMARY_PREFIX.trimStart().substringBefore("<")),
        )
        assertTrue((branch.content[0] as TextContent).text.contains("explored elsewhere"))
        assertTrue((branch.content[0] as TextContent).text.endsWith(BRANCH_SUMMARY_SUFFIX))
        assertEquals("answer", ((preparation.messages[2] as AssistantMessage).content[0] as TextContent).text)
        val compaction = preparation.messages[3] as UserMessage
        assertTrue((compaction.content[0] as TextContent).text.contains("compacted"))
        assertTrue((compaction.content[0] as TextContent).text.startsWith(COMPACTION_SUMMARY_PREFIX.trimStart().substringBefore("<")))
        assertEquals(preparation.messages.map(::estimateTokens).sum(), preparation.totalTokens)
    }

    @Test
    fun `preparation skips tool result messages`() {
        val root = messageEntry(user("root"))
        val toolResult = messageEntry(
            ToolResultMessage(toolCallId = "c1", toolName = "read", content = listOf(TextContent("ok")), isError = false),
            root.id,
        )
        val preparation = prepareBranchEntries(listOf(root, toolResult), tokenBudget = 0)
        assertEquals(1, preparation.messages.size)
    }

    @Test
    fun `token budget keeps the newest messages and stops`() {
        // ~100 tokens per message under estimateMessageTokens' char/4 heuristic.
        val big = "x".repeat(400)
        val e1 = messageEntry(user(big))
        val e2 = messageEntry(user(big), e1.id)
        val e3 = messageEntry(user("tiny"), e2.id)

        val preparation = prepareBranchEntries(listOf(e1, e2, e3), tokenBudget = 60)

        // Walking newest-first, only the tiny message fits the budget.
        assertEquals(1, preparation.messages.size)
        assertEquals("tiny", ((preparation.messages[0] as UserMessage).content[0] as TextContent).text)
    }

    @Test
    fun `branch summary over budget is kept while under ninety percent`() {
        val big = "x".repeat(400) // ~50 tokens
        val userEntry = messageEntry(user(big))
        val summary = branchSummaryEntry(big.padEnd(500, 'y'), userEntry.id, fromId = "x")

        // Budget 60: the branch summary (~125 tokens) exceeds it, but the
        // running total is 0 < 54 (90%), so it is kept and the walk stops —
        // the older user message is dropped.
        val preparation = prepareBranchEntries(listOf(userEntry, summary), tokenBudget = 60)

        assertEquals(1, preparation.messages.size)
        val projected = preparation.messages[0] as UserMessage
        assertTrue((projected.content[0] as TextContent).text.contains(BRANCH_SUMMARY_SUFFIX))
    }

    @Test
    fun `nested branch summary details carry into file operations`() {
        val details = buildJsonObject {
            putJsonArray("readFiles") { add("a.txt") }
            putJsonArray("modifiedFiles") { add("b.txt"); add("c.txt") }
        }
        val summary = branchSummaryEntry("s", parentId = null, fromId = "x", details = details)
        val preparation = prepareBranchEntries(listOf(summary), tokenBudget = 0)
        val (readFiles, modifiedFiles) = computeFileLists(preparation.fileOps)
        assertEquals(listOf("a.txt"), readFiles)
        assertEquals(listOf("b.txt", "c.txt"), modifiedFiles)
    }

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

    private var fauxCount = 0

    private class Faux(val api: FauxApi, val models: Models, val model: Model)

    private fun createFaux(): Faux {
        val api = FauxApi()
        val providerId = "faux-${++fauxCount}"
        val model = Model(
            id = "faux-model",
            name = "Faux",
            api = "faux-api",
            provider = providerId,
            baseUrl = "https://faux.test",
            reasoning = false,
            contextWindow = 200000,
            maxTokens = 8192,
        )
        return Faux(
            api,
            Models(
                listOf(
                    Provider(
                        providerId,
                        providerId,
                        "https://faux.test",
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
    ): AssistantMessage = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "faux-api",
        provider = "faux",
        model = "faux-model",
        usage = Usage(
            input = 10,
            output = 5,
            reasoning = 0,
            totalTokens = 15,
            cost = Cost(0.0, 0.0, 0.0, 0.0, 0.0),
        ),
        stopReason = stopReason,
        errorMessage = errorMessage,
    )

    private fun promptText(context: Context): String =
        ((context.messages[0] as UserMessage).content[0] as TextContent).text

    @Test
    fun `generates a branch summary with the fixed prompt format`() = runTest {
        val faux = createFaux()
        faux.api.responses += fauxAssistantMessage("## Goal\nship it")
        val entries = listOf(messageEntry(user("explore the widget")))
        val clock = FakeClock(1000)

        val result = generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(models = faux.models, model = faux.model, clock = clock),
        )

        val ok = assertIs<BranchSummaryCallResult.Ok>(result)
        assertEquals(
            "$BRANCH_SUMMARY_PREAMBLE## Goal\nship it",
            ok.value.summary,
        )
        assertEquals(15, ok.value.usage?.totalTokens)
        assertEquals(emptyList(), ok.value.readFiles)
        assertEquals(emptyList(), ok.value.modifiedFiles)

        assertEquals(SUMMARIZATION_SYSTEM_PROMPT, faux.api.seenContexts[0].systemPrompt)
        val expectedPrompt =
            "<conversation>\n[User]: explore the widget\n</conversation>\n\n$BRANCH_SUMMARY_PROMPT"
        assertEquals(expectedPrompt, promptText(faux.api.seenContexts[0]))
        assertEquals(2048, faux.api.seenOptions[0].maxTokens)
        assertEquals(1000, (faux.api.seenContexts[0].messages[0] as UserMessage).timestamp)
    }

    @Test
    fun `custom instructions append or replace the fixed prompt`() = runTest {
        val faux = createFaux()
        faux.api.responses += fauxAssistantMessage("s")
        faux.api.responses += fauxAssistantMessage("s2")
        val entries = listOf(messageEntry(user("m")))

        generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(models = faux.models, model = faux.model, customInstructions = "focus on tests"),
        )
        assertTrue(promptText(faux.api.seenContexts[0]).endsWith("$BRANCH_SUMMARY_PROMPT\n\nAdditional focus: focus on tests"))

        generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(
                models = faux.models,
                model = faux.model,
                customInstructions = "custom prompt",
                replaceInstructions = true,
            ),
        )
        assertTrue(promptText(faux.api.seenContexts[1]).endsWith("</conversation>\n\ncustom prompt"))
    }

    @Test
    fun `appends file operations from nested branch summary details`() = runTest {
        val faux = createFaux()
        faux.api.responses += fauxAssistantMessage("s")
        val details = buildJsonObject {
            putJsonArray("readFiles") { add("read-only.txt") }
            putJsonArray("modifiedFiles") { add("also-read.txt") }
        }
        val entries = listOf(
            branchSummaryEntry("inner", parentId = null, fromId = "x", details = details),
            messageEntry(user("tail")),
        )

        val result = generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(models = faux.models, model = faux.model),
        )

        val ok = assertIs<BranchSummaryCallResult.Ok>(result)
        assertEquals(listOf("read-only.txt"), ok.value.readFiles)
        assertEquals(listOf("also-read.txt"), ok.value.modifiedFiles)
        assertTrue(ok.value.summary.endsWith("<read-files>\nread-only.txt\n</read-files>\n\n<modified-files>\nalso-read.txt\n</modified-files>"))
    }

    @Test
    fun `returns placeholder without an llm call when nothing to summarize`() = runTest {
        val faux = createFaux()
        val result = generateBranchSummary(
            listOf(ModelChangeEntry(createId(), 0, null, 0, provider = "p", modelId = "m")),
            GenerateBranchSummaryOptions(models = faux.models, model = faux.model),
        )
        val ok = assertIs<BranchSummaryCallResult.Ok>(result)
        assertEquals("No content to summarize", ok.value.summary)
        assertTrue(faux.api.seenContexts.isEmpty())
    }

    @Test
    fun `maps aborted and error stop reasons to branch summary errors`() = runTest {
        val faux = createFaux()
        val entries = listOf(messageEntry(user("m")))

        faux.api.responses += fauxAssistantMessage("x", stopReason = StopReason.ABORTED, errorMessage = "cancelled")
        val aborted = assertIs<BranchSummaryCallResult.Err>(
            generateBranchSummary(entries, GenerateBranchSummaryOptions(models = faux.models, model = faux.model)),
        )
        assertEquals(BranchSummaryErrorCode.ABORTED, aborted.error.code)
        assertEquals("cancelled", aborted.error.message.orEmpty())

        faux.api.responses += fauxAssistantMessage("x", stopReason = StopReason.ERROR, errorMessage = "boom")
        val failed = assertIs<BranchSummaryCallResult.Err>(
            generateBranchSummary(entries, GenerateBranchSummaryOptions(models = faux.models, model = faux.model)),
        )
        assertEquals(BranchSummaryErrorCode.SUMMARIZATION_FAILED, failed.error.code)
        assertTrue(failed.error.message.orEmpty().startsWith("Branch summary failed: boom"))
    }

    @Test
    fun `branch summary entries project a wrapped branch-summary context message`() {
        val root = messageEntry(user("root"))
        val summary = branchSummaryEntry("what we found", root.id, fromId = "gone")
        val tail = messageEntry(user("back home"), summary.id)

        val messages = buildSessionContext(listOf(root, summary, tail))

        assertEquals(3, messages.size)
        val projected = messages[1] as UserMessage
        val text = (projected.content[0] as TextContent).text
        assertTrue(text.startsWith("The following is a summary of a branch that this conversation came back from:"))
        assertTrue(text.contains("what we found"))
        assertTrue(text.endsWith(BRANCH_SUMMARY_SUFFIX))
        assertEquals(summary.timestamp, projected.timestamp)
    }

    @Test
    fun `empty branch summary entries project nothing`() {
        val root = messageEntry(user("root"))
        val summary = branchSummaryEntry("", root.id, fromId = "gone")
        val messages = buildSessionContext(listOf(root, summary))
        assertEquals(1, messages.size)
    }

    @Test
    fun `deferred assistant messages drop from context`() {
        val root = messageEntry(user("root"))
        val deferred = messageEntry(assistant("partial").copy(stopReason = StopReason.DEFERRED), root.id)
        val final = messageEntry(assistant("done"), deferred.id)
        val toolResult = messageEntry(
            ToolResultMessage(toolCallId = "c", toolName = "t", content = listOf(TextContent("r")), isError = false),
            final.id,
        )

        val messages = buildSessionContext(listOf(root, deferred, final, toolResult))

        assertEquals(3, messages.size)
        assertEquals("root", ((messages[0] as UserMessage).content[0] as TextContent).text)
        assertEquals("done", ((messages[1] as AssistantMessage).content[0] as TextContent).text)
        assertEquals("r", ((messages[2] as ToolResultMessage).content[0] as TextContent).text)
    }

    @Test
    fun `terminated message entries still project into context`() {
        // The terminate flag marks session-terminal entries but does not
        // change context projection — pi has no terminate check here.
        val root = messageEntry(user("root"))
        val terminal = messageEntry(assistant("the end"), root.id, terminate = true)
        val messages = buildSessionContext(listOf(root, terminal))
        assertEquals(2, messages.size)
    }
}
