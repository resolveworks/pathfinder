package works.resolve.aletheia.data.sessions

import works.resolve.aletheia.ai.core.AssistantMessage
import works.resolve.aletheia.ai.core.Cost
import works.resolve.aletheia.ai.core.ImageContent
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.TextContent
import works.resolve.aletheia.ai.core.ThinkingContent
import works.resolve.aletheia.ai.core.ToolCall
import works.resolve.aletheia.ai.core.ToolResultMessage
import works.resolve.aletheia.ai.core.Usage
import works.resolve.aletheia.ai.core.UserMessage
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

    private var now = 1_000L
    private var nextId = 0
    private lateinit var root: File

    private fun newStore(maxFileBytes: Long = SessionStore.MAX_FILE_BYTES): SessionStore {
        if (!::root.isInitialized) root = tmpFolder.newFolder("sessions")
        return SessionStore(
            root = root,
            clock = { now },
            idFactory = { "sess-${nextId++}" },
            maxFileBytes = maxFileBytes,
        )
    }

    private fun newStoreWithIds(maxFileBytes: Long = SessionStore.MAX_FILE_BYTES): SessionStore {
        if (!::root.isInitialized) root = tmpFolder.newFolder("sessions")
        return SessionStore(
            root = root,
            clock = { now },
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
        now += 10
        val b = store.create("b")
        now += 10
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
        now += 5
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
        now += 5
        val root = MessageEntry("m0", null, 1L, UserMessage.ofText("a", 1L))
        val left = MessageEntry("m1", "m0", 2L, UserMessage.ofText("b", 2L))
        val right = MessageEntry("m2", "m0", 3L, UserMessage.ofText("c", 3L))
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
        now += 100
        val saved = store.save(created.withMessages(listOf(UserMessage.ofText("hey", 7L))).copy(title = "renamed"))
        assertEquals(now, saved.updatedAt)
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
            """{"format":1,"id":"$id","title":"t","createdAt":1,"updatedAt":1,
               "messages":[{"role":"system","timestamp":0}]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        File(root, "$id.json").writeText(
            """{"format":1,"id":"$id","title":"t","createdAt":1,"updatedAt":1,
               "messages":[{"role":"user","timestamp":0,"content":[{"type":"audio"}]}]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Unknown format version.
        File(root, "$id.json").writeText(
            """{"format":99,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"messages":[]}"""
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
            now += 1
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
            """{"format":1,"id":"$id","title":"t","createdAt":1,"updatedAt":1,
               "messages":[{"role":"assistant","timestamp":0,"content":[],"api":"a","provider":"p","model":"m",
               "stopReason":"STOP"}]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Missing usage cost component.
        File(root, "$id.json").writeText(
            """{"format":1,"id":"$id","title":"t","createdAt":1,"updatedAt":1,
               "messages":[{"role":"assistant","timestamp":0,"content":[],"api":"a","provider":"p","model":"m",
               "stopReason":"STOP","usage":{"input":1,"output":1,"cacheRead":0,"cacheWrite":0,"reasoning":0,"totalTokens":2,
               "cost":{"input":0.0,"output":0.0,"cacheRead":0.0,"cacheWrite":0.0}}}]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Missing message timestamp.
        File(root, "$id.json").writeText(
            """{"format":1,"id":"$id","title":"t","createdAt":1,"updatedAt":1,
               "messages":[{"role":"user","content":[]}]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Missing tool-result isError.
        File(root, "$id.json").writeText(
            """{"format":1,"id":"$id","title":"t","createdAt":1,"updatedAt":1,
               "messages":[{"role":"toolResult","timestamp":0,"toolCallId":"c","toolName":"n","content":[]}]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }
    }

    @Test
    fun quotedNumbersAndBooleansRejected() = runTest {
        val store = newStore()
        val id = store.create().id

        // Quoted top-level format / timestamps.
        File(root, "$id.json").writeText(
            """{"format":"1","id":"$id","title":"t","createdAt":1,"updatedAt":1,"messages":[]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }
        File(root, "$id.json").writeText(
            """{"format":1,"id":"$id","title":"t","createdAt":"1","updatedAt":1,"messages":[]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Quoted message timestamp.
        File(root, "$id.json").writeText(
            """{"format":1,"id":"$id","title":"t","createdAt":1,"updatedAt":1,
               "messages":[{"role":"user","timestamp":"7","content":[]}]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Quoted usage number.
        File(root, "$id.json").writeText(
            """{"format":1,"id":"$id","title":"t","createdAt":1,"updatedAt":1,
               "messages":[{"role":"assistant","timestamp":0,"content":[],"api":"a","provider":"p","model":"m",
               "stopReason":"STOP","usage":{"input":"1","output":1,"cacheRead":0,"cacheWrite":0,"reasoning":0,"totalTokens":2,
               "cost":{"input":0.0,"output":0.0,"cacheRead":0.0,"cacheWrite":0.0,"total":0.0}}}]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Quoted isError.
        File(root, "$id.json").writeText(
            """{"format":1,"id":"$id","title":"t","createdAt":1,"updatedAt":1,
               "messages":[{"role":"toolResult","timestamp":0,"toolCallId":"c","toolName":"n","content":[],"isError":"false"}]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }
    }

    @Test
    fun idMismatchBetweenFilenameAndContentRejected() = runTest {
        val store = newStore()
        val id = store.create().id
        File(root, "$id.json").writeText(
            """{"format":1,"id":"other","title":"t","createdAt":1,"updatedAt":1,"messages":[]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }
        // Corrupt entries are skipped by summaries.
        assertTrue(store.summaries().isEmpty())
    }

}
