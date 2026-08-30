package works.resolve.pathfinder.data.credentials

import works.resolve.pathfinder.diagnostics.DiagnosticEntry
import works.resolve.pathfinder.diagnostics.DiagnosticEvent
import works.resolve.pathfinder.diagnostics.Diagnostics
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import java.io.File

/**
 * [EncryptedCredentialStore] semantics tested on the JVM through the pure
 * (dir + cipher function) constructor; the Android Keystore path is a thin
 * production wiring of the same functions.
 */
class EncryptedCredentialStoreTest {

    private val entries = mutableListOf<DiagnosticEntry>()

    @kotlin.test.BeforeTest
    fun setUp() {
        Diagnostics.install { entries += it }
    }

    @kotlin.test.AfterTest
    fun tearDown() {
        Diagnostics.install(null)
    }

    private fun newStore(dir: File): EncryptedCredentialStore =
        EncryptedCredentialStore(
            dir = dir,
            encrypt = { bytes -> bytes.map { (it + 1).toByte() }.toByteArray() },
            decrypt = { bytes -> bytes.map { (it - 1).toByte() }.toByteArray() },
        )

    private fun writeRaw(dir: File, providerId: String, raw: String) {
        File(dir, "$providerId.bin").writeBytes(raw.toByteArray().map { (it + 1).toByte() }.toByteArray())
    }

    @Test
    fun `read returns null when nothing is stored`() = runTest {
        val store = newStore(createTempDirectory())
        assertNull(store.read("openai"))
    }

    @Test
    fun `set persists and round trips`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        store.set("openai", Credential.ApiKey("sk"))
        assertEquals(Credential.ApiKey("sk"), store.read("openai"))
    }

    @Test
    fun `oauth credential round trips`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        val credential = Credential.ChatGptOAuth("acc", "ref", 1_700_000_000_000, "acct")
        store.set("openaicodex", credential)
        assertEquals(credential, store.read("openaicodex"))
        assertEquals(listOf("openaicodex"), store.list())
    }

    @Test
    fun `set replaces the stored credential wholesale`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        store.set("openai", Credential.ApiKey("old"))
        store.set("openai", Credential.ApiKey("new"))
        assertEquals(Credential.ApiKey("new"), store.read("openai"))
    }

    @Test
    fun `legacy bare-key entry is rejected`() = runTest {
        val dir = createTempDirectory()
        writeRaw(dir, "openai", "sk-legacy")
        val store = newStore(dir)
        assertFailsWith<CredentialFormatException> { store.read("openai") }
    }

    @Test
    fun `legacy oauth entry is rejected`() = runTest {
        val dir = createTempDirectory()
        writeRaw(dir, "anthropic", """{"type":"oauth","access":"a","refresh":"r","expires":7}""")
        val store = newStore(dir)
        assertFailsWith<CredentialFormatException> { store.read("anthropic") }
    }

    @Test
    fun `list reports configured provider ids without secrets`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        store.set("openai", Credential.ApiKey("sk-SECRET"))
        store.set("zai", Credential.ApiKey("other-SECRET"))
        assertEquals(listOf("openai", "zai"), store.list())
    }

    @Test
    fun `delete removes the persisted entry`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        store.set("openai", Credential.ApiKey("sk"))
        store.delete("openai")
        assertNull(store.read("openai"))
        assertTrue(!File(dir, "openai.bin").exists())
    }

    @Test
    fun `list rejects corrupt or malformed entries instead of skipping them`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        store.set("openai", Credential.ApiKey("sk"))
        writeRaw(dir, "zai", "{bad}") // object-looking malformed record
        assertFailsWith<CredentialFormatException> { store.list() }

        // A decrypt failure (storage-level) also rejects.
        val failing = EncryptedCredentialStore(
            dir = dir,
            encrypt = { it },
            decrypt = { error("keystore failure") },
        )
        assertFailsWith<IllegalStateException> { failing.list() }
        assertFailsWith<IllegalStateException> { failing.read("openai") }
    }

    @Test
    fun `invalid provider ids are rejected`() = runTest {
        val store = newStore(createTempDirectory())
        assertFailsWith<IllegalArgumentException> { store.read("../evil") }
        assertFailsWith<IllegalArgumentException> { store.set("", Credential.ApiKey("k")) }
    }

    @Test
    fun `decrypt failure throws and records one sanitized read failure`() = runTest {
        val dir = createTempDirectory()
        writeRaw(dir, "openai", "{\"type\":\"api_key\",\"key\":\"sk\"}")
        val store = EncryptedCredentialStore(
            dir = dir,
            encrypt = { it },
            decrypt = { throw IllegalStateException("keystore failure SECRET-MARKER") },
        )
        assertFailsWith<IllegalStateException> { store.read("openai") }
        assertEquals(listOf(DiagnosticEvent.CREDENTIAL_READ_FAILED), entries.map { it.event })
        val message = entries.single().message()
        assertTrue(SECRET_MARKER !in message, message)
    }

    @Test
    fun `undecodable stored credential records decode rejection`() = runTest {
        val dir = createTempDirectory()
        writeRaw(dir, "openai", "{bad json}")
        val store = newStore(dir)
        assertFailsWith<CredentialFormatException> { store.read("openai") }
        assertEquals(listOf(DiagnosticEvent.CREDENTIAL_DECODE_REJECTED), entries.map { it.event })
    }

    @Test
    fun `list over one corrupt file records exactly one decode rejection`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        store.set("openai", Credential.ApiKey("sk"))
        writeRaw(dir, "zai", "{bad}")
        assertFailsWith<CredentialFormatException> { store.list() }
        assertEquals(listOf(DiagnosticEvent.CREDENTIAL_DECODE_REJECTED), entries.map { it.event })
    }

    @Test
    fun `encrypt failure records write failure and rethrows`() = runTest {
        val store = EncryptedCredentialStore(
            dir = createTempDirectory(),
            encrypt = { throw IllegalStateException("encrypt failed SECRET-MARKER") },
            decrypt = { it },
        )
        assertFailsWith<IllegalStateException> { store.set("openai", Credential.ApiKey("sk")) }
        assertEquals(listOf(DiagnosticEvent.CREDENTIAL_WRITE_FAILED), entries.map { it.event })
        val message = entries.single().message()
        assertTrue(SECRET_MARKER !in message, message)
    }

    @Test
    fun `happy path records no diagnostic entries`() = runTest {
        val store = newStore(createTempDirectory())
        store.set("openai", Credential.ApiKey("sk"))
        assertEquals(Credential.ApiKey("sk"), store.read("openai"))
        assertEquals(listOf("openai"), store.list())
        assertTrue(entries.isEmpty(), entries.toString())
    }

    private companion object {
        const val SECRET_MARKER = "SECRET-MARKER"
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("credstore").toFile().apply { deleteOnExit() }
}
