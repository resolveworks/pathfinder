package works.resolve.aletheia.data.credentials

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import works.resolve.aletheia.ai.auth.ApiKeyCredential
import works.resolve.aletheia.ai.auth.CredentialType
import works.resolve.aletheia.ai.auth.OAuthCredential
import java.io.File

/**
 * [EncryptedCredentialStore] semantics tested on the JVM through the pure
 * (dir + cipher function) constructor; the Android Keystore path is a thin
 * production wiring of the same functions.
 */
class EncryptedCredentialStoreTest {

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
    fun `modify persists and round trips both credential types`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        store.modify("openai") { ApiKeyCredential(key = "sk", env = mapOf("A" to "1")) }
        store.modify("anthropic") { OAuthCredential(access = "a", refresh = "r", expires = 7L, extras = mapOf("x" to kotlinx.serialization.json.JsonPrimitive(1))) }
        assertEquals(ApiKeyCredential(key = "sk", env = mapOf("A" to "1")), store.read("openai"))
        val oauth = store.read("anthropic") as OAuthCredential
        assertEquals(7L, oauth.expires)
        assertTrue("x" in oauth.extras)
    }

    @Test
    fun `legacy bare-key entry migrates and rewrites in tagged shape`() = runTest {
        val dir = createTempDirectory()
        writeRaw(dir, "openai", "sk-legacy")
        val store = newStore(dir)
        assertEquals(ApiKeyCredential("sk-legacy"), store.read("openai"))
        // A later write upgrades the record to the tagged codec shape.
        store.modify("openai") { current -> current as ApiKeyCredential }
        val onDisk = File(dir, "openai.bin").readBytes().map { (it - 1).toByte() }.toByteArray().decodeToString()
        assertTrue(onDisk.contains("\"type\":\"api_key\""))
    }

    @Test
    fun `legacy key-env entry migrates`() = runTest {
        val dir = createTempDirectory()
        writeRaw(dir, "cloudflare", """{"key":"k","env":{"CLOUDFLARE_ACCOUNT_ID":"acc"}}""")
        val store = newStore(dir)
        assertEquals(ApiKeyCredential("k", mapOf("CLOUDFLARE_ACCOUNT_ID" to "acc")), store.read("cloudflare"))
    }

    @Test
    fun `list reports metadata for both types without secrets`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        store.modify("openai") { ApiKeyCredential(key = "sk-SECRET") }
        store.modify("zai") { OAuthCredential(access = "SECRET", refresh = "SECRET", expires = 1L) }
        val list = store.list()
        assertEquals(2, list.size)
        assertEquals(CredentialType.API_KEY, list.first { it.providerId == "openai" }.type)
        assertEquals(CredentialType.OAUTH, list.first { it.providerId == "zai" }.type)
        assertTrue(list.none { it.toString().contains("SECRET") })
    }

    @Test
    fun `modify is serialized per provider`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        val updates = List(16) { i ->
            async {
                store.modify("openai") { current ->
                    OAuthCredential(access = "a$i", refresh = "r", expires = current?.let { (it as OAuthCredential).expires + 1 } ?: 0L)
                }
            }
        }.awaitAll()
        assertEquals((0L..15L).toList(), updates.map { (it as OAuthCredential).expires })
    }

    @Test
    fun `modify failure leaves stored credential intact`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        store.modify("openai") { ApiKeyCredential("sk") }
        assertFailsWith<IllegalStateException> { store.modify("openai") { error("boom") } }
        assertEquals(ApiKeyCredential("sk"), store.read("openai"))
    }

    @Test
    fun `delete removes the persisted entry`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        store.modify("openai") { ApiKeyCredential("sk") }
        store.delete("openai")
        assertNull(store.read("openai"))
        assertTrue(!File(dir, "openai.bin").exists())
    }

    @Test
    fun `list rejects corrupt or malformed entries instead of skipping them`() = runTest {
        val dir = createTempDirectory()
        val store = newStore(dir)
        store.modify("openai") { ApiKeyCredential("sk") }
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
        assertFailsWith<IllegalArgumentException> { store.modify("") { ApiKeyCredential("k") } }
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("credstore").toFile().apply { deleteOnExit() }
}
