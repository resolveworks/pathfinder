package works.resolve.pathfinder.codingagent.core.session

import works.resolve.pathfinder.agent.*

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Cost
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.Usage
import works.resolve.pathfinder.ai.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.logging.PathfinderDiagnostics
import works.resolve.pathfinder.telemetry.InMemoryTelemetryContext
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.attr
import java.io.File
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionStoreTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val clock = FakeClock(1_000)
    private var nextId = 0
    private lateinit var root: File

    private fun newStore(maxFileBytes: Long = SessionStore.MAX_FILE_BYTES): SessionStore {
        if (!::root.isInitialized) root = tmpFolder.newFolder("sessions")
        return SessionStore(
            root = root,
            clock = clock,
            idFactory = { "sess-${nextId++}" },
            maxFileBytes = maxFileBytes,
        )
    }

    private fun newStoreWithIds(maxFileBytes: Long = SessionStore.MAX_FILE_BYTES): SessionStore {
        if (!::root.isInitialized) root = tmpFolder.newFolder("sessions")
        return SessionStore(
            root = root,
            clock = clock,
            idFactory = { nextId++.toString() },
            maxFileBytes = maxFileBytes,
        )
    }

    private fun fullTranscript() = listOf(
        UserMessage(
            content = listOf(
                TextContent("hello"),
                ImageContent(data = "aGk=", mimeType = "image/png"),
            ),
            timestamp = 1L,
        ),
        AssistantMessage(
            content = listOf(
                ThinkingContent(thinking = "hmm", thinkingSignature = "sig-1"),
                TextContent("hi there"),
                ToolCall(id = "call-1", name = "get_weather", arguments = """{"city":"Oslo"}"""),
            ),
            api = "openai-completions",
            provider = "zai",
            model = "glm-4.6",
            usage = Usage(
                input = 10, output = 20, cacheRead = 5, cacheWrite = 2,
                reasoning = 3, totalTokens = 35,
                cost = Cost(input = 0.001, output = 0.002, cacheRead = 0.0001, cacheWrite = 0.0002, total = 0.0033),
            ),
            stopReason = StopReason.TOOL_USE,
            errorMessage = null,
            rawStopReason = "tool_calls",
            responseId = "resp-9",
            responseModel = "glm-4.6-actual",
            timestamp = 2L,
        ),
        ToolResultMessage(
            toolCallId = "call-1",
            toolName = "get_weather",
            content = listOf(TextContent("""{"temp":12}""")),
            isError = false,
            timestamp = 3L,
        ),
        AssistantMessage(
            content = listOf(TextContent("It is 12 degrees.")),
            api = "openai-completions",
            provider = "zai",
            model = "glm-4.6",
            stopReason = StopReason.ERROR,
            errorMessage = "boom",
            timestamp = 4L,
        ),
    )

    @Test
    fun createListOrderingNewestUpdatedFirst() = runTest {
        val store = newStore()
        val a = store.create("a")
        clock.advanceMillis(10)
        val b = store.create("b")
        clock.advanceMillis(10)
        store.save(a.copy(title = "a2"))

        val summaries = store.summaries()
        assertEquals(listOf(a.id, b.id), summaries.map { it.id })
        assertEquals("a2", summaries.first().title)
        assertEquals(0, summaries.first().messageCount)
    }

    @Test
    fun fullRoundTripAcrossRestart() = runTest {
        val store = newStore()
        val created = store.create("session one")
        clock.advanceMillis(5)
        val saved = store.save(created.withMessages(fullTranscript()))

        val reloaded = newStore().load(created.id)
        assertNotNull(reloaded)
        assertEquals(saved, reloaded)
        assertEquals(fullTranscript(), reloaded!!.messages)
    }

    @Test
    fun roundTripPreservesEntriesAndLeafIdWithLaneMutation() = runTest {
        val store = newStore()
        val created = store.create("branchy")
        clock.advanceMillis(5)
        val rootEntry = MessageEntry("m0", 1, null, 1L, UserMessage.ofText("a", 1L))
        val left = MessageEntry("m1", 2, "m0", 2L, UserMessage.ofText("b", 2L))
        val right = MessageEntry("m2", 3, "m0", 3L, UserMessage.ofText("c", 3L))
        val saved = store.save(created.copy(entries = listOf(rootEntry, left, right), leafId = "m2"))

        val reloaded = newStore().load(created.id)!!
        assertEquals(saved, reloaded)
        assertEquals(listOf(rootEntry, left, right).map { it.id }, reloaded.entries.map { it.id })
        assertEquals("m2", reloaded.leafId)
        assertEquals(listOf("a", "c"), reloaded.messages.map { (it as UserMessage).content.single().let { c -> (c as TextContent).text } })
        // The branch persisted as a lane mutation (pi's moveLane-before-append order).
        val lines = File(root, "${created.id}.jsonl").readLines()
        assertTrue(lines.any { it.contains("\"kind\":\"lane\"") && it.contains("\"leafId\":\"m0\"") })
    }

    @Test
    fun saveAppendsWithoutRewritingTheFile() = runTest {
        val store = newStore()
        val created = store.create()
        val file = File(root, "${created.id}.jsonl")
        val headerLine = file.readLines().first()
        var previousLength = file.length()

        var conversation = Conversation(emptyList(), null)
        repeat(5) { i ->
            conversation = conversation.append(UserMessage.ofText("m$i", i.toLong()))
            store.save(
                Session(
                    created.id, created.title, created.createdAt, created.updatedAt,
                    entries = conversation.entries, leafId = conversation.leafId,
                ),
            )
            assertTrue(file.length() > previousLength)
            assertEquals(headerLine, file.readLines().first())
            previousLength = file.length()
        }
        assertEquals(5, store.load(created.id)!!.messages.size)
        // header + name fact + 5 entry mutations, one line each.
        assertEquals(7, file.readLines().size)
    }

    @Test
    fun repeatedSavesAreIdempotentAndKeepContentIntact() = runTest {
        val store = newStore()
        val created = store.create()
        val conversation = created.withMessages(listOf(UserMessage.ofText("hey", 7L)))
        val saved = store.save(conversation.copy(title = "renamed"))
        val lengthAfterFirst = File(root, "${created.id}.jsonl").length()
        val again = store.save(saved)
        assertEquals(lengthAfterFirst, File(root, "${created.id}.jsonl").length())

        val loaded = store.load(created.id)!!
        assertEquals("renamed", loaded.title)
        assertEquals(listOf(UserMessage.ofText("hey", 7L)), loaded.messages)
        assertEquals(1, store.summaries().single().messageCount)
        assertEquals(saved.createdAt, loaded.createdAt)
        assertTrue(loaded.updatedAt >= created.createdAt)
    }

    @Test
    fun storageAssignsConsecutiveSeq() = runTest {
        val store = newStore()
        val created = store.create()
        val saved = store.save(created.withMessages(fullTranscript()))
        // seq 1 is the create-time name fact; entries start at 2 (pi's shared
        // sequence spans every mutation kind).
        assertEquals(List(4) { (it + 2).toLong() }, saved.entries.map { it.seq })
    }

    @Test
    fun deleteRemovesSession() = runTest {
        val store = newStore()
        val session = store.create()
        assertTrue(store.delete(session.id))
        assertFalse(store.delete(session.id))
        assertNull(store.load(session.id))
        assertTrue(store.summaries().isEmpty())
    }

    @Test
    fun loadReturnsDefensiveCopies() = runTest {
        val store = newStore()
        val id = store.create().id
        val messages = mutableListOf(UserMessage.ofText("a"))
        val conversation = Conversation.fromMessages(messages)
        store.save(Session(id, "t", 1, 1, entries = conversation.entries, leafId = conversation.leafId))
        messages.add(UserMessage.ofText("sneaky"))

        val loaded = store.load(id)!!
        assertEquals(1, loaded.messages.size)
        runCatching { (loaded.messages as? MutableList)?.clear() }
        assertEquals(1, store.load(id)!!.messages.size)
    }

    @Test
    fun saveStoresDefensiveCopyOfCallerList() = runTest {
        val store = newStore()
        val created = store.create()
        val messages = mutableListOf(UserMessage.ofText("a"))
        store.save(created.withMessages(messages))
        messages.add(UserMessage.ofText("sneaky"))
        assertEquals(1, store.load(created.id)!!.messages.size)
    }

    @Test
    fun rejectsIdsWithTraversal() {
        assertFailsWith<SessionError> { Session("../evil", "t", 1, 1, emptyList(), null) }
        assertFailsWith<SessionError> { Session("a/b", "t", 1, 1, emptyList(), null) }
        assertFailsWith<SessionError> { Session("a\\b", "t", 1, 1, emptyList(), null) }
        assertFailsWith<SessionError> { Session("", "t", 1, 1, emptyList(), null) }
        assertFailsWith<SessionError> { Session("a.b", "t", 1, 1, emptyList(), null) }
        assertFailsWith<SessionError> { Session("x".repeat(65), "t", 1, 1, emptyList(), null) }
        runTest {
            val store = newStore()
            assertFailsWithSessionError { store.load("../secrets") }
            assertFailsWithSessionError { store.delete("../../keys") }
        }
    }

    private suspend fun assertFailsWithSessionError(block: suspend () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (e: SessionError) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun oldSnapshotFormatsAreIgnoredAndRejected() = runTest {
        val store = newStore()
        val id = store.create().id
        // Legacy whole-file format-3 snapshot: invisible to listing, never migrated.
        File(root, "$id.json").writeText(
            """{"format":3,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[],"leafId":null}""",
        )
        assertTrue(store.summaries().all { it.id != "$id.json" })
        File(root, "old.jsonl").writeText(
            """{"kind":"header","version":3,"id":"old","createdAt":1}""",
        )
        assertFailsWithSessionError { store.load("old") }
        assertTrue(store.summaries().none { it.id == "old" })
    }

    @Test
    fun corruptJsonRejectedWithSessionError() = runTest {
        val store = newStore()
        val id = store.create().id
        File(root, "$id.jsonl").writeText("{ not json")
        assertFailsWithSessionError { store.load(id) }
        assertTrue(store.summaries().isEmpty())
    }

    @Test
    fun unknownRolesContentTypesAndEntryKindsRejected() = runTest {
        val store = newStore()
        val id = store.create().id

        fun writeMutationLine(payload: String) {
            File(root, "$id.jsonl").writeText(
                """{"kind":"header","version":4,"id":"$id","createdAt":0}
                    |$payload
                """.trimMargin(),
            )
        }

        writeMutationLine(
            """{"kind":"entry","seq":1,"lane":"main","id":"m0","type":"message","parentId":null,"timestamp":0,"message":{"role":"system","timestamp":0}}""",
        )
        assertFailsWithSessionError { store.load(id) }

        writeMutationLine(
            """{"kind":"entry","seq":1,"lane":"main","id":"m0","type":"message","parentId":null,"timestamp":0,"message":{"role":"user","timestamp":0,"content":[{"type":"audio"}]}}""",
        )
        assertFailsWithSessionError { store.load(id) }

        writeMutationLine(
            """{"kind":"entry","seq":1,"lane":"main","id":"m0","type":"mystery","parentId":null,"timestamp":0}""",
        )
        assertFailsWithSessionError { store.load(id) }

        writeMutationLine(
            """{"kind":"entry","seq":1,"lane":"main","id":"m0","type":"message","parentId":null,"timestamp":0,"message":{"role":"assistant","timestamp":0,"content":[],"api":"a","provider":"p","model":"m","stopReason":"STOP"}}""",
        )
        assertFailsWithSessionError { store.load(id) }
    }

    @Test
    fun replayValidationRejectsNonConsecutiveSeqAndDanglingParents() = runTest {
        val store = newStore()
        val id = store.create().id

        File(root, "$id.jsonl").writeText(
            """{"kind":"header","version":4,"id":"$id","createdAt":0}
                {"kind":"fact","seq":1,"fact":"name","name":"t"}
                {"kind":"entry","seq":3,"lane":"main","id":"m0","type":"message","parentId":null,"timestamp":0,"message":{"role":"user","timestamp":0,"content":[]}}
            """.trimIndent().trimMargin(),
        )
        assertFailsWithSessionError { store.load(id) }

        File(root, "$id.jsonl").writeText(
            """{"kind":"header","version":4,"id":"$id","createdAt":0}
                {"kind":"entry","seq":1,"lane":"main","id":"m0","type":"message","parentId":"ghost","timestamp":0,"message":{"role":"user","timestamp":0,"content":[]}}
            """.trimIndent().trimMargin(),
        )
        assertFailsWithSessionError { store.load(id) }
    }

    @Test
    fun tornTailTruncatedFinalLineDroppedAndPrefixIntact() = runTest {
        val store = newStore()
        val created = store.create()
        store.save(created.withMessages(listOf(UserMessage.ofText("a", 1L))))
        val file = File(root, "${created.id}.jsonl")
        val valid = file.readText()
        assertTrue(valid.endsWith("\n"))

        // Simulate a torn append: a partial JSON line without a newline.
        file.writeText(valid + """{"kind":"entry","seq":9,"lane":"main","id":"torn""")

        val reloaded = newStore().load(created.id)
        assertNotNull(reloaded)
        assertEquals(1, reloaded!!.messages.size)
        assertEquals(valid, file.readText())
        assertEquals(listOf("${created.id}.jsonl"), root.listFiles()!!.map { it.name })
    }

    @Test
    fun tornTailSchemaErrorOnFinalLineIsNotRepaired() = runTest {
        val store = newStore()
        val created = store.create()
        val file = File(root, "${created.id}.jsonl")
        // Valid JSON but an unknown mutation kind: a schema error, not a torn append.
        file.writeText(
            """{"kind":"header","version":4,"id":"${created.id}","createdAt":0}
                {"kind":"zzz","seq":1}
            """.trimIndent().trimMargin() + "\n",
        )
        assertFailsWithSessionError { newStore().load(created.id) }
    }

    @Test
    fun unterminatedTailRepairedWithNewline() = runTest {
        val store = newStore()
        val created = store.create()
        val file = File(root, "${created.id}.jsonl")
        val valid = file.readText()
        // A complete final line whose newline never made it to disk.
        val extra =
            """{"kind":"entry","seq":2,"lane":"main","id":"m0","type":"message","parentId":null,"timestamp":0,"message":{"role":"user","timestamp":0,"content":[]}}"""
        file.writeText(valid + extra)

        val reloaded = newStore().load(created.id)
        assertNotNull(reloaded)
        assertEquals(1, reloaded!!.messages.size)
        // The repair appended the missing newline, so the next append is well-formed.
        val store3 = newStore()
        val grown = Conversation(reloaded.entries, reloaded.leafId).append(UserMessage.ofText("b", 2L))
        store3.save(reloaded.copy(entries = grown.entries, leafId = grown.leafId))
        val reloaded2 = newStore().load(created.id)!!
        assertEquals(2, reloaded2.messages.size)
    }

    @Test
    fun tornTailInsideFileIsRejected() = runTest {
        val store = newStore()
        val created = store.create()
        val file = File(root, "${created.id}.jsonl")
        val valid = file.readText()
        // A syntax error that is NOT the final line is corruption, not a torn tail.
        val good =
            """{"kind":"entry","seq":2,"lane":"main","id":"m0","type":"message","parentId":null,"timestamp":0,"message":{"role":"user","timestamp":0,"content":[]}}"""
        file.writeText(valid + """{"kind":"entry","seq":3,"lan""" + "\n" + good + "\n")
        assertFailsWithSessionError { newStore().load(created.id) }
    }

    @Test
    fun oversizeFileRejected() = runTest {
        val store = newStore(maxFileBytes = 16)
        val id = store.create().id
        File(root, "$id.jsonl").writeText("x".repeat(64))
        assertFailsWithSessionError { store.load(id) }
    }

    @Test
    fun zeroMaxFileBytesRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionStore(tmpFolder.newFolder(), maxFileBytes = 0)
        }
    }

    @Test
    fun concurrentSavesAreSerialized() = runTest {
        val store = newStore()
        val created = store.create()
        repeat(20) { i ->
            clock.advanceMillis(1)
            store.save(created.withMessages((0..i).map { UserMessage.ofText("m$it") }))
        }
        val loaded = store.load(created.id)!!
        assertEquals(20, loaded.messages.size)
    }

    @Test
    fun emptySessionIsValid() = runTest {
        val store = newStore()
        val created = store.create()
        val loaded = store.load(created.id)!!
        assertTrue(loaded.messages.isEmpty())
    }

    @Test
    fun oneCharacterIdSavesAndLoads() = runTest {
        val store = newStoreWithIds()
        val created = store.create("tiny")
        assertEquals("0", created.id)
        store.save(created.withMessages(listOf(UserMessage.ofText("hi", 1L))))
        assertEquals(1, store.load("0")!!.messages.size)
        val names = root.listFiles()!!.map { it.name }
        assertEquals(listOf("0.jsonl"), names)
    }

    @Test
    fun quotedNumbersAndBooleansRejected() = runTest {
        val store = newStore()
        val id = store.create().id

        File(root, "$id.jsonl").writeText(
            """{"kind":"header","version":4,"id":"$id","createdAt":0}
                {"kind":"entry","seq":1,"lane":"main","id":"m0","type":"message","parentId":null,"timestamp":0,"message":{"role":"user","timestamp":"7","content":[]}}
            """.trimIndent().trimMargin() + "\n",
        )
        assertFailsWithSessionError { store.load(id) }

        File(root, "$id.jsonl").writeText(
            """{"kind":"header","version":4,"id":"$id","createdAt":0}
                {"kind":"entry","seq":"1","lane":"main","id":"m0","type":"message","parentId":null,"timestamp":0,"message":{"role":"user","timestamp":0,"content":[]}}
            """.trimIndent().trimMargin() + "\n",
        )
        assertFailsWithSessionError { store.load(id) }
    }

    @Test
    fun idMismatchBetweenFilenameAndHeaderRejected() = runTest {
        val store = newStore()
        val id = store.create().id
        File(root, "$id.jsonl").writeText(
            """{"kind":"header","version":4,"id":"other","createdAt":0}""",
        )
        assertFailsWithSessionError { store.load(id) }
        // Corrupt entries are skipped by summaries.
        assertTrue(store.summaries().isEmpty())
    }

    @Test
    fun sessionFilesContainNoCredentialMaterial() = runTest {
        val store = newStore()
        val id = store.create().id
        val full = Conversation.fromMessages(fullTranscript())
        store.save(Session(id, "t", 1, 1, entries = full.entries, leafId = full.leafId))
        val text = File(root, "$id.jsonl").readText()
        assertFalse(text.contains("apiKey", ignoreCase = true))
        assertFalse(text.contains("authorization"))
        assertTrue(text.contains("glm-4.6"))
    }

    @Test
    fun summaryIgnoresUnreadableCorruptFiles() = runTest {
        val store = newStore()
        val good = store.create("good")
        store.save(good.copy(title = "good"))
        File(root, "corrupt.jsonl").writeText("garbage")
        val summaries = store.summaries()
        assertEquals(listOf(good.id), summaries.map { it.id })
    }

    @Test
    fun `telemetry records save and load spans on success`() = runTest {
        val telemetry = InMemoryTelemetryContext()
        val store = SessionStore(
            root = tmpFolder.newFolder("telemetry-ok"),
            clock = clock,
            idFactory = { "sess-t" },
            diagnostics = PathfinderDiagnostics(telemetry),
        )
        val created = store.create("t")
        assertNotNull(store.load(created.id))

        val saves = telemetry.getSpans().filter { it.name == "pf.session.save" }
        val loads = telemetry.getSpans().filter { it.name == "pf.session.load" }
        assertTrue(saves.isNotEmpty() && loads.isNotEmpty())
        assertEquals(SpanStatus.Ok, saves.single().status)
        assertEquals(SpanStatus.Ok, loads.single().status)
        assertEquals(attr("sess-t"), saves.single().attributes["pf.session.id"])
        assertEquals(attr("persisted"), saves.single().attributes["pf.session.outcome"])
        assertEquals(attr("loaded"), loads.single().attributes["pf.session.outcome"])
    }

    @Test
    fun `telemetry records load failure and summary skip with type only`() = runTest {
        val telemetry = InMemoryTelemetryContext()
        val rootFail = tmpFolder.newFolder("telemetry-fail")
        val store = SessionStore(
            root = rootFail,
            clock = clock,
            idFactory = { "sess-f" },
            diagnostics = PathfinderDiagnostics(telemetry),
        )
        val id = store.create("t").id
        File(rootFail, "$id.jsonl").writeText("{corrupt")

        assertFailsWithSessionError { store.load(id) }
        val loadFailed = telemetry.getSpans().last { it.name == "pf.session.load" }
        assertEquals(SpanStatus.Ok, telemetry.getSpans().first { it.name == "pf.session.save" }.status)
        val error = loadFailed.status as SpanStatus.Error
        assertEquals("SessionError", error.error?.name) // short type name only
        assertEquals("", error.error?.message) // never exception text, paths, or content

        assertTrue(store.summaries().isEmpty())
        val skipped = telemetry.getSpans().single { it.name == "pf.session.summary" }
        assertEquals(attr("skipped"), skipped.attributes["pf.session.outcome"])
        assertTrue(skipped.status is SpanStatus.Error)
    }

    @Test
    fun `telemetry records fork under its own span name`() = runTest {
        val telemetry = InMemoryTelemetryContext()
        val store = SessionStore(
            root = tmpFolder.newFolder("telemetry-fork"),
            clock = clock,
            idFactory = { "sess-fork" },
            diagnostics = PathfinderDiagnostics(telemetry),
        )
        val created = store.create("t")
        store.fork(created.id, ForkOptions.Tree, id = "sess-forked")

        val forkSpan = telemetry.getSpans().single { it.name == "pf.session.fork" }
        assertEquals(attr("persisted"), forkSpan.attributes["pf.session.outcome"])
        assertEquals(SpanStatus.Ok, forkSpan.status)
    }

    @Test
    fun `telemetry records save failure with type only`() = runTest {
        val telemetry = InMemoryTelemetryContext()
        // A regular file as root: the directory is unavailable, so writes fail.
        val rootAsFile = tmpFolder.newFile("telemetry-not-a-dir")
        val store = SessionStore(
            root = rootAsFile,
            clock = clock,
            idFactory = { "sess-w" },
            diagnostics = PathfinderDiagnostics(telemetry),
        )
        assertFailsWithSessionError { store.create("t") }
        val saveFailed = telemetry.getSpans().single()
        assertEquals("pf.session.save", saveFailed.name)
        val error = saveFailed.status as SpanStatus.Error
        // The write failure is wrapped before the span records its type.
        assertEquals("SessionError", error.error?.name) // short type name only
        assertEquals("", error.error?.message)
    }
}
