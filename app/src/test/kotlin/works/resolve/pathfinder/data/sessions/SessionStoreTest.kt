package works.resolve.pathfinder.data.sessions

import works.resolve.pathfinder.testing.FakeClock
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

    private fun conversationOf(vararg messages: ai.koog.prompt.message.Message): Conversation {
        var conversation = Conversation(emptyList(), null)
        for (message in messages) conversation = conversation.append(message)
        return conversation
    }

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
        val transcript = conversationOf(
            userMessage("hello", 1L),
            assistantMessage(
                reasoningPart("hmm"),
                textPart("hi there"),
                toolCallPart("call-1", "get_weather", """{"city":"Oslo"}"""),
                epochMs = 2L,
            ),
            assistantMessage(textPart("It is 12 degrees."), epochMs = 4L),
        )
        val saved = store.save(created.copy(entries = transcript.entries, leafId = transcript.leafId))

        // Simulate restart: fresh store instance over the same directory.
        val reloaded = newStore().load(created.id)
        assertNotNull(reloaded)
        assertEquals(saved, reloaded)
        assertEquals(transcript.activeMessages(), reloaded!!.messages)
    }

    @Test
    fun roundTripPreservesEntriesAndLeafId() = runTest {
        val store = newStore()
        val created = store.create("branchy")
        clock.advanceMillis(5)
        val root = MessageEntry("m0", null, 1L, userMessage("a", 1L))
        val left = MessageEntry("m1", "m0", 2L, userMessage("b", 2L))
        val right = MessageEntry("m2", "m0", 3L, userMessage("c", 3L))
        val saved = store.save(created.copy(entries = listOf(root, left, right), leafId = "m2"))

        val reloaded = newStore().load(created.id)!!
        assertEquals(saved, reloaded)
        assertEquals(listOf(root, left, right), reloaded.entries)
        assertEquals("m2", reloaded.leafId)
        assertEquals(listOf("a", "c"), reloaded.messages.map { (it as ai.koog.prompt.message.Message.User).textContent() })
    }

    @Test
    fun saveBumpsUpdatedAtAndPersistsTitleAndTranscriptChanges() = runTest {
        val store = newStore()
        val created = store.create()
        clock.advanceMillis(100)
        val transcript = conversationOf(userMessage("hey", 7L))
        val saved = store.save(created.copy(entries = transcript.entries, leafId = transcript.leafId).copy(title = "renamed"))
        assertEquals(clock.now().toEpochMilliseconds(), saved.updatedAt)
        assertEquals(created.createdAt, saved.createdAt)

        val loaded = store.load(created.id)!!
        assertEquals("renamed", loaded.title)
        assertEquals(listOf(userMessage("hey", 7L)), loaded.messages)
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
    fun unknownFormatVersionsAndEntryKindsRejected() = runTest {
        val store = newStore()
        val id = store.create().id

        // Old (pre-Koog) format version.
        File(root, "$id.json").writeText(
            """{"format":2,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Unknown future version.
        File(root, "$id.json").writeText(
            """{"format":99,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }

        // Unknown entry kind.
        File(root, "$id.json").writeText(
            """{"format":3,"id":"$id","title":"t","createdAt":1,"updatedAt":1,"entries":[{"kind":"compaction","id":"m0","timestamp":0}],"leafId":"m0"}"""
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
        val full = conversationOf(userMessage("hello"), assistantMessage(textPart("hi"), epochMs = 2L))
        store.save(Session(id, "t", 1, 1, entries = full.entries, leafId = full.leafId))
        val text = File(root, "$id.json").readText()
        assertFalse(text.contains("apiKey", ignoreCase = true))
        assertFalse(text.contains("authorization"))
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
        val transcript = conversationOf(userMessage("hi", 1L))
        store.save(created.copy(entries = transcript.entries, leafId = transcript.leafId))
        assertEquals(1, store.load("0")!!.messages.size)
        // No stray temp files remain.
        val names = root.listFiles()!!.map { it.name }
        assertEquals(listOf("0.json"), names)
    }

    @Test
    fun idMismatchBetweenFilenameAndContentRejected() = runTest {
        val store = newStore()
        val id = store.create().id
        File(root, "$id.json").writeText(
            """{"format":3,"id":"other","title":"t","createdAt":1,"updatedAt":1,"entries":[]}"""
        )
        assertFailsWithSessionDataException { store.load(id) }
        // Corrupt entries are skipped by summaries.
        assertTrue(store.summaries().isEmpty())
    }
}
