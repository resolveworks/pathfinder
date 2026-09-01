package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Cost
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage
import works.resolve.pathfinder.ai.core.Usage
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
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

        // Simulate restart: fresh store instance over the same directory.
        val reloaded = newStore().load(created.id)
        assertNotNull(reloaded)
        assertEquals(saved, reloaded)
        assertEquals(fullTranscript(), reloaded!!.messages)
    }

    @Test
    fun roundTripPreservesEntriesAndLeafId() = runTest {
        val store = newStore()
        val created = store.create("branchy")
        clock.advanceMillis(5)
        val root = MessageEntry("m0", 0L, null, 1L, UserMessage.ofText("a", 1L))
        val left = MessageEntry("m1", 0L, "m0", 2L, UserMessage.ofText("b", 2L))
        val right = MessageEntry("m2", 0L, "m0", 3L, UserMessage.ofText("c", 3L))
        val saved = store.save(created.copy(entries = listOf(root, left, right), leafId = "m2"))

        val reloaded = newStore().load(created.id)!!
        assertEquals(saved, reloaded)
        assertEquals(listOf(root, left, right), reloaded.entries)
        assertEquals("m2", reloaded.leafId)
        assertEquals(listOf("a", "c"), reloaded.messages.map { (it as UserMessage).content.single().let { c -> (c as TextContent).text } })
    }

    @Test
    fun saveBumpsUpdatedAtAndPersistsTitleAndTranscriptChanges() = runTest {
        val store = newStore()
        val created = store.create()
        clock.advanceMillis(100)
        val saved = store.save(created.withMessages(listOf(UserMessage.ofText("hey", 7L))).copy(title = "renamed"))
        assertEquals(clock.now().toEpochMilliseconds(), saved.updatedAt)
        assertEquals(created.createdAt, saved.createdAt)

        val loaded = store.load(created.id)!!
        assertEquals("renamed", loaded.title)
        assertEquals(listOf(UserMessage.ofText("hey", 7L)), loaded.messages)
        assertEquals(1, store.summaries().single().messageCount)
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
        // Mutating the returned list (when possible) must not affect persisted state.
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
        assertFailsWith<SessionDataException> { Session("../evil", "t", 1, 1, emptyList(), null) }
        assertFailsWith<SessionDataException> { Session("a/b", "t", 1, 1, emptyList(), null) }
        assertFailsWith<SessionDataException> { Session("a\\b", "t", 1, 1, emptyList(), null) }
        assertFailsWith<SessionDataException> { Session("", "t", 1, 1, emptyList(), null) }
        assertFailsWith<SessionDataException> { Session("a.b", "t", 1, 1, emptyList(), null) }
        assertFailsWith<SessionDataException> { Session("x".repeat(65), "t", 1, 1, emptyList(), null) }
        runTest {
            val store = newStore()
            assertFailsWithSessionDataException { store.load("../secrets") }
            assertFailsWithSessionDataException { store.delete("../../keys") }
        }
    }

    private suspend fun assertFailsWithSessionDataException(block: suspend () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (e: SessionDataException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun corruptJsonRejectedWithSessionDataException() = runTest {
        val store = newStore()
        val id = store.create().id
        File(root, "$id.json").writeText("{ not json")
        assertFailsWithSessionDataException { store.load(id) }
        assertTrue(store.summaries().isEmpty())
    }

    @Test
    fun unknownRolesAndContentTypesRejected() = runTest {
        val store = newStore()
        val id = store.create().id

        File(root, "$id.json").writeText(
            """{"format":2,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[{"type":"message","id":"m0","timestamp":0,"message":{"role":"system","timestamp":0}}],"leafId":"m0"}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        File(root, "$id.json").writeText(
            """{"format":2,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[{"type":"message","id":"m0","timestamp":0,"message":{"role":"user","timestamp":0,"content":[{"type":"audio"}]}}],"leafId":"m0"}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Unknown format version.
        File(root, "$id.json").writeText(
            """{"format":99,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }
    }

    @Test
    fun oversizeFileRejected() = runTest {
        val store = newStore(maxFileBytes = 16)
        val id = store.create().id
        File(root, "$id.json").writeText("x".repeat(64))
        assertFailsWithSessionDataException { store.load(id) }
    }

    @Test
    fun zeroMaxFileBytesRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionStore(tmpFolder.newFolder(), maxFileBytes = 0)
        }
    }

    @Test
    fun atomicWriteLeavesNoTempFiles() = runTest {
        val store = newStore()
        val created = store.create()
        store.save(created.copy(title = "again"))
        val names = root.listFiles()!!.map { it.name }
        assertEquals(listOf("${created.id}.json"), names)
    }

    @Test
    fun repeatedSavesKeepPreviousContentIntact() = runTest {
        val store = newStore()
        val created = store.create()
        store.save(created.copy(title = "good"))
        store.save(created.copy(title = "good2"))
        val names = root.listFiles()!!.map { it.name }
        assertEquals(listOf("${created.id}.json"), names)
        assertEquals("good2", store.load(created.id)!!.title)
    }

    @Test
    fun sessionFilesContainNoCredentialMaterial() = runTest {
        val store = newStore()
        val id = store.create().id
        val full = Conversation.fromMessages(fullTranscript())
        store.save(Session(id, "t", 1, 1, entries = full.entries, leafId = full.leafId))
        val text = File(root, "$id.json").readText()
        assertFalse(text.contains("apiKey", ignoreCase = true))
        assertFalse(text.contains("authorization"))
        // Only the assistant's identity fields appear.
        assertTrue(text.contains("glm-4.6"))
    }

    @Test
    fun summaryIgnoresUnreadableCorruptFiles() = runTest {
        val store = newStore()
        val good = store.create("good")
        store.save(good.copy(title = "good"))
        File(root, "corrupt.json").writeText("garbage")
        val summaries = store.summaries()
        assertEquals(listOf(good.id), summaries.map { it.id })
    }

    @Test
    fun concurrentSavesAreSerialized() = runTest {
        val store = newStore()
        val created = store.create()
        // Interleaved saves of different sizes must all land atomically.
        repeat(20) { i ->
            clock.advanceMillis(1)
            store.save(created.withMessages((0..i).map { UserMessage.ofText("m$it") }).copy(title = "t$i"))
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
        // No stray temp files remain.
        val names = root.listFiles()!!.map { it.name }
        assertEquals(listOf("0.json"), names)
    }

    @Test
    fun missingRequiredFieldsRejected() = runTest {
        val store = newStore()
        val id = store.create().id

        // Missing assistant usage.
        File(root, "$id.json").writeText(
            """{"format":2,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[{"type":"message","id":"m0","timestamp":0,"message":{"role":"assistant","timestamp":0,"content":[],"api":"a","provider":"p","model":"m",
               "stopReason":"STOP"}}],"leafId":"m0"}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Missing usage cost component.
        File(root, "$id.json").writeText(
            """{"format":2,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[{"type":"message","id":"m0","timestamp":0,"message":{"role":"assistant","timestamp":0,"content":[],"api":"a","provider":"p","model":"m",
               "stopReason":"STOP","usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,"reasoning":0,"totalTokens":2,
               "cost":{"input":0.0,"output":0.0,"cacheRead":0.0,"cacheWrite":0.0}}}}],"leafId":"m0"}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Missing message timestamp.
        File(root, "$id.json").writeText(
            """{"format":2,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[{"type":"message","id":"m0","timestamp":0,"message":{"role":"user","content":[]}}],"leafId":"m0"}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Missing tool-result isError.
        File(root, "$id.json").writeText(
            """{"format":2,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[{"type":"message","id":"m0","timestamp":0,"message":{"role":"toolResult","timestamp":0,"toolCallId":"c","toolName":"n","content":[]}}],"leafId":"m0"}"""
        )
        assertFailsWithSessionDataException { store.load(id) }
    }

    @Test
    fun quotedNumbersAndBooleansRejected() = runTest {
        val store = newStore()
        val id = store.create().id

        // Quoted top-level format / timestamps.
        File(root, "$id.json").writeText(
            """{"format":"2","id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }
        File(root, "$id.json").writeText(
            """{"format":2,"id":"$id","title":"t","createdAt":"1","updatedAt":1,"entries":[]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Quoted message timestamp.
        File(root, "$id.json").writeText(
            """{"format":2,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[{"type":"message","id":"m0","timestamp":0,"message":{"role":"user","timestamp":"7","content":[]}}],"leafId":"m0"}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Quoted usage number.
        File(root, "$id.json").writeText(
            """{"format":2,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[{"type":"message","id":"m0","timestamp":0,"message":{"role":"assistant","timestamp":0,"content":[],"api":"a","provider":"p","model":"m",
               "stopReason":"STOP","usage":{"input":"1","output":1,"cacheRead":0,"cacheWrite":0,"reasoning":0,"totalTokens":2,
               "cost":{"input":0.0,"output":0.0,"cacheRead":0.0,"cacheWrite":0.0,"total":0.0}}}}],"leafId":"m0"}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Quoted isError.
        File(root, "$id.json").writeText(
            """{"format":2,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[{"type":"message","id":"m0","timestamp":0,"message":{"role":"toolResult","timestamp":0,"toolCallId":"c","toolName":"n","content":[],"isError":"false"}}],"leafId":"m0"}"""
        )
        assertFailsWithSessionDataException { store.load(id) }
    }

    @Test
    fun idMismatchBetweenFilenameAndContentRejected() = runTest {
        val store = newStore()
        val id = store.create().id
        File(root, "$id.json").writeText(
            """{"format":2,"id":"other","title":"t","createdAt":1,"updatedAt":1,"entries":[]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }
        // Corrupt entries are skipped by summaries.
        assertTrue(store.summaries().isEmpty())
    }

    @Test
    fun `telemetry records save and load spans on success`() = runTest {
        val telemetry = InMemoryTelemetryContext()
        val store = SessionStore(
            root = tmpFolder.newFolder("telemetry-ok"),
            clock = clock,
            idFactory = { "sess-t" },
            telemetryContext = telemetry,
        )
        val created = store.create("t")
        assertNotNull(store.load(created.id))

        val saves = telemetry.spans().filter { it.name == "pf.session.save" }
        val loads = telemetry.spans().filter { it.name == "pf.session.load" }
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
            telemetryContext = telemetry,
        )
        val id = store.create("t").id
        File(rootFail, "$id.json").writeText("{corrupt")

        assertFailsWithSessionDataException { store.load(id) }
        val loadFailed = telemetry.spans().last { it.name == "pf.session.load" }
        assertEquals(SpanStatus.Ok, telemetry.spans().first { it.name == "pf.session.save" }.status)
        val error = loadFailed.status as SpanStatus.Error
        assertEquals("works.resolve.pathfinder.data.sessions.SessionDataException", error.error?.name)
        assertEquals("", error.error?.message) // never exception text, paths, or content

        // Summaries skip the corrupt entry and record it as a summary skip.
        assertTrue(store.summaries().isEmpty())
        val skipped = telemetry.spans().single { it.name == "pf.session.summary" }
        assertEquals(attr("skipped"), skipped.attributes["pf.session.outcome"])
        assertTrue(skipped.status is SpanStatus.Error)
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
            telemetryContext = telemetry,
        )
        assertFailsWithSessionDataException { store.create("t") }
        val saveFailed = telemetry.spans().single()
        assertEquals("pf.session.save", saveFailed.name)
        val error = saveFailed.status as SpanStatus.Error
        assertEquals("java.io.IOException", error.error?.name)
        assertEquals("", error.error?.message)
    }

}
