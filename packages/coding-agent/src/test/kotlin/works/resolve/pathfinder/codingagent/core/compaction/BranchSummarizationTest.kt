package works.resolve.pathfinder.codingagent.core.compaction

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.Provider
import works.resolve.pathfinder.ai.ResolvedAuth
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.codingagent.core.BRANCH_SUMMARY_PREFIX
import works.resolve.pathfinder.codingagent.core.BRANCH_SUMMARY_SUFFIX
import works.resolve.pathfinder.codingagent.core.BranchSummaryEntry
import works.resolve.pathfinder.codingagent.core.COMPACTION_SUMMARY_PREFIX
import works.resolve.pathfinder.codingagent.core.CompactionEntry
import works.resolve.pathfinder.codingagent.core.MessageEntry
import works.resolve.pathfinder.codingagent.core.ModelChangeEntry
import works.resolve.pathfinder.codingagent.core.ReadonlySessionManager
import works.resolve.pathfinder.codingagent.core.SessionContext
import works.resolve.pathfinder.codingagent.core.SessionEntry
import works.resolve.pathfinder.codingagent.core.SessionTreeNode
import works.resolve.pathfinder.codingagent.core.buildContextEntries
import works.resolve.pathfinder.codingagent.core.buildSessionContext

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
            cost = Cost(0.0, 0.0, 0.0, 0.0, 0.0)
        ),
        stopReason = StopReason.STOP,
        timestamp = nextId.toLong()
    )

    private fun messageEntry(message: Message, parentId: String? = null) = MessageEntry(
        id = createId(),
        parentId = parentId,
        timestamp = nextId.toLong(),
        message = message
    )

    private fun branchSummaryEntry(
        summary: String,
        parentId: String?,
        fromId: String,
        details: JsonObject? = null
    ) = BranchSummaryEntry(
        id = createId(),
        parentId = parentId,
        timestamp = nextId.toLong(),
        fromId = fromId,
        summary = summary,
        details = details
    )

    @Test
    fun `collects nothing when there is no old leaf`() {
        val root = messageEntry(user("root"))
        val session = sessionOf(listOf(root), root.id)
        val result =
            collectEntriesForBranchSummary(session, oldLeafId = null, targetId = root.id)
        assertTrue(result.entries.isEmpty())
        assertNull(result.commonAncestorId)
    }

    @Test
    fun `collects the abandoned branch up to the deepest common ancestor`() {
        val root = messageEntry(user("root"))
        val a1 = messageEntry(user("a1"), root.id)
        val a2 = messageEntry(assistant("a2"), a1.id)
        val b1 = messageEntry(user("b1"), root.id)
        val session = sessionOf(listOf(root, a1, a2, b1), b1.id)

        val result =
            collectEntriesForBranchSummary(session, oldLeafId = a2.id, targetId = b1.id)

        assertEquals(root.id, result.commonAncestorId)
        assertEquals(listOf<SessionEntry>(a1, a2), result.entries)
    }

    @Test
    fun `navigating to an ancestor summarizes only the entries after it`() {
        val root = messageEntry(user("root"))
        val a1 = messageEntry(user("a1"), root.id)
        val a2 = messageEntry(assistant("a2"), a1.id)
        val session = sessionOf(listOf(root, a1, a2), a2.id)

        val result =
            collectEntriesForBranchSummary(session, oldLeafId = a2.id, targetId = a1.id)

        assertEquals(a1.id, result.commonAncestorId)
        assertEquals(listOf<SessionEntry>(a2), result.entries)
    }

    @Test
    fun `navigating to the current leaf collects nothing`() {
        val root = messageEntry(user("root"))
        val a1 = messageEntry(user("a1"), root.id)
        val session = sessionOf(listOf(root, a1), a1.id)

        val result =
            collectEntriesForBranchSummary(session, oldLeafId = a1.id, targetId = a1.id)

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
                firstKeptEntryId = root.id,
                tokensBefore = 10
            ),
            ModelChangeEntry(createId(), null, 0, provider = "p", modelId = "m")
        )

        val preparation = prepareBranchEntries(entries, tokenBudget = 0)

        assertEquals(4, preparation.messages.size)
        assertEquals(
            user("root").content,
            preparation.messages[0].let {
                (it as UserMessage).content
            }
        )
        val branch = preparation.messages[1] as UserMessage
        assertTrue(
            (branch.content[0] as TextContent).text.startsWith(
                BRANCH_SUMMARY_PREFIX.trimStart().substringBefore("<")
            )
        )
        assertTrue((branch.content[0] as TextContent).text.contains("explored elsewhere"))
        assertTrue((branch.content[0] as TextContent).text.endsWith(BRANCH_SUMMARY_SUFFIX))
        assertEquals(
            "answer",
            ((preparation.messages[2] as AssistantMessage).content[0] as TextContent).text
        )
        val compaction = preparation.messages[3] as UserMessage
        assertTrue((compaction.content[0] as TextContent).text.contains("compacted"))
        assertTrue(
            (compaction.content[0] as TextContent).text.startsWith(
                COMPACTION_SUMMARY_PREFIX.trimStart().substringBefore("<")
            )
        )
        assertEquals(preparation.messages.map(::estimateTokens).sum(), preparation.totalTokens)
    }

    @Test
    fun `preparation skips tool result messages`() {
        val root = messageEntry(user("root"))
        val toolResult = messageEntry(
            ToolResultMessage(
                toolCallId = "c1",
                toolName = "read",
                content = listOf(TextContent("ok")),
                isError = false
            ),
            root.id
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
        assertEquals(
            "tiny",
            ((preparation.messages[0] as UserMessage).content[0] as TextContent).text
        )
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
            putJsonArray("modifiedFiles") {
                add("b.txt")
                add("c.txt")
            }
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
            options: SimpleStreamOptions
        ): Flow<AssistantMessageEvent> = flow {
            seenContexts += context
            seenOptions += options
            val response = responses.removeFirstOrNull()
                ?: error("No faux completeSimple response queued")
            if (response.stopReason == StopReason.ERROR ||
                response.stopReason == StopReason.ABORTED
            ) {
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
            maxTokens = 8192
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
                        apis = mapOf("faux-api" to api)
                    )
                )
            ),
            model
        )
    }

    private fun fauxAssistantMessage(
        text: String,
        stopReason: StopReason = StopReason.STOP,
        errorMessage: String? = null
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
            cost = Cost(0.0, 0.0, 0.0, 0.0, 0.0)
        ),
        stopReason = stopReason,
        errorMessage = errorMessage
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
            GenerateBranchSummaryOptions(models = faux.models, model = faux.model, clock = clock)
        )

        val ok = assertIs<BranchSummaryCallResult.Ok>(result)
        assertEquals(
            "$BRANCH_SUMMARY_PREAMBLE## Goal\nship it",
            ok.value.summary
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
            GenerateBranchSummaryOptions(
                models = faux.models,
                model = faux.model,
                customInstructions = "focus on tests"
            )
        )
        assertTrue(
            promptText(
                faux.api.seenContexts[0]
            ).endsWith("$BRANCH_SUMMARY_PROMPT\n\nAdditional focus: focus on tests")
        )

        generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(
                models = faux.models,
                model = faux.model,
                customInstructions = "custom prompt",
                replaceInstructions = true
            )
        )
        assertTrue(
            promptText(faux.api.seenContexts[1]).endsWith("</conversation>\n\ncustom prompt")
        )
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
            messageEntry(user("tail"))
        )

        val result = generateBranchSummary(
            entries,
            GenerateBranchSummaryOptions(models = faux.models, model = faux.model)
        )

        val ok = assertIs<BranchSummaryCallResult.Ok>(result)
        assertEquals(listOf("read-only.txt"), ok.value.readFiles)
        assertEquals(listOf("also-read.txt"), ok.value.modifiedFiles)
        assertTrue(
            ok.value.summary.endsWith(
                "<read-files>\nread-only.txt\n</read-files>\n\n<modified-files>\nalso-read.txt\n</modified-files>"
            )
        )
    }

    @Test
    fun `returns placeholder without an llm call when nothing to summarize`() = runTest {
        val faux = createFaux()
        val result = generateBranchSummary(
            listOf(ModelChangeEntry(createId(), null, 0, provider = "p", modelId = "m")),
            GenerateBranchSummaryOptions(models = faux.models, model = faux.model)
        )
        val ok = assertIs<BranchSummaryCallResult.Ok>(result)
        assertEquals("No content to summarize", ok.value.summary)
        assertTrue(faux.api.seenContexts.isEmpty())
    }

    @Test
    fun `maps aborted and error stop reasons to branch summary errors`() = runTest {
        val faux = createFaux()
        val entries = listOf(messageEntry(user("m")))

        faux.api.responses +=
            fauxAssistantMessage("x", stopReason = StopReason.ABORTED, errorMessage = "cancelled")
        val aborted = assertIs<BranchSummaryCallResult.Err>(
            generateBranchSummary(
                entries,
                GenerateBranchSummaryOptions(models = faux.models, model = faux.model)
            )
        )
        assertEquals(BranchSummaryErrorCode.ABORTED, aborted.error.code)
        assertEquals("cancelled", aborted.error.message.orEmpty())

        faux.api.responses +=
            fauxAssistantMessage("x", stopReason = StopReason.ERROR, errorMessage = "boom")
        val failed = assertIs<BranchSummaryCallResult.Err>(
            generateBranchSummary(
                entries,
                GenerateBranchSummaryOptions(models = faux.models, model = faux.model)
            )
        )
        assertEquals(BranchSummaryErrorCode.SUMMARIZATION_FAILED, failed.error.code)
        assertTrue(failed.error.message.orEmpty().startsWith("Branch summary failed: boom"))
    }

    @Test
    fun `branch summary entries project a wrapped branch-summary context message`() {
        val root = messageEntry(user("root"))
        val summary = branchSummaryEntry("what we found", root.id, fromId = "gone")
        val tail = messageEntry(user("back home"), summary.id)

        val messages = buildSessionContext(listOf(root, summary, tail)).messages

        assertEquals(3, messages.size)
        val projected = messages[1] as UserMessage
        val text = (projected.content[0] as TextContent).text
        assertTrue(
            text.startsWith(
                "The following is a summary of a branch that this conversation came back from:"
            )
        )
        assertTrue(text.contains("what we found"))
        assertTrue(text.endsWith(BRANCH_SUMMARY_SUFFIX))
        assertEquals(summary.timestamp, projected.timestamp)
    }

    @Test
    fun `empty branch summary entries project nothing`() {
        val root = messageEntry(user("root"))
        val summary = branchSummaryEntry("", root.id, fromId = "gone")
        val messages = buildSessionContext(listOf(root, summary)).messages
        assertEquals(1, messages.size)
    }

    /** Read-only fake over fixed entries, for the pure collection walks. */
    private fun sessionOf(entries: List<SessionEntry>, leafId: String?): ReadonlySessionManager =
        object : ReadonlySessionManager {
            override fun getSessionId(): String = ""

            override fun getSessionFile(): File? = null

            override fun getEntries(): List<SessionEntry> = entries

            override fun getLeafId(): String? = leafId

            override fun getLeafEntry(): SessionEntry? = leafId?.let(::getEntry)

            override fun getEntry(id: String): SessionEntry? = entries.firstOrNull { it.id == id }

            override fun getBranch(fromId: String?): List<SessionEntry> {
                val byId = entries.associateBy { it.id }
                val path = ArrayList<SessionEntry>()
                var cursor = byId[fromId ?: leafId]
                while (cursor != null) {
                    path.add(cursor)
                    cursor = cursor.parentId?.let(byId::get)
                }
                path.reverse()
                return path
            }

            override fun buildContextEntries(): List<SessionEntry> =
                buildContextEntries(entries, leafId)

            override fun buildSessionContext(): SessionContext =
                buildSessionContext(entries, leafId)

            override fun getTree(): List<SessionTreeNode> = buildTree(entries)
        }

    /** pi's tree-selector.test.ts buildTree: nodes over a flat entry list. */
    private fun buildTree(entries: List<SessionEntry>): List<SessionTreeNode> {
        val byId = entries.associateBy { it.id }
        val children = HashMap<String, MutableList<SessionEntry>>()
        val roots = ArrayList<SessionEntry>()
        for (entry in entries) {
            val parent = entry.parentId?.let(byId::get)
            if (parent == null) {
                roots += entry
            } else {
                children.getOrPut(parent.id) { mutableListOf() } += entry
            }
        }
        fun nodeOf(entry: SessionEntry): SessionTreeNode =
            SessionTreeNode(entry, (children[entry.id] ?: emptyList()).map(::nodeOf))
        return roots.map(::nodeOf)
    }
}
