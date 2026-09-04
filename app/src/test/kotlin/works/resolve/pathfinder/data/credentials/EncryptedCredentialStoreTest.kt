package works.resolve.pathfinder.data.credentials

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.auth.ApiKeyCredential
import works.resolve.pathfinder.ai.auth.CredentialType
import works.resolve.pathfinder.ai.auth.OAuthCredential
import works.resolve.pathfinder.logging.PathfinderDiagnostics
import works.resolve.pathfinder.telemetry.InMemoryTelemetryContext
import works.resolve.pathfinder.telemetry.SpanStatus
import works.resolve.pathfinder.telemetry.attr

/**
 * The Android Keystore path is a thin production wiring of the same pure
 * (dir + cipher function) constructor these tests exercise.
 */
class EncryptedCredentialStoreTest {

    private fun newStore(dir: File): EncryptedCredentialStore = EncryptedCredentialStore(
        dir = dir,
        encrypt = { bytes -> bytes.map { (it + 1).toByte() }.toByteArray() },
        decrypt = { bytes -> bytes.map { (it - 1).toByte() }.toByteArray() }
    )

    private fun writeRaw(dir: File, providerId: String, raw: String) {
        File(dir, "$providerId.bin").writeBytes(
            raw.toByteArray().map {
                (it + 1).toByte()
            }.toByteArray()
        )
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
        store.modify("anthropic") {
            OAuthCredential(
                access = "a",
                refresh = "r",
                expires = 7L,
                extras = mapOf("x" to kotlinx.serialization.json.JsonPrimitive(1))
            )
        }
        assertEquals(ApiKeyCredential(key = "sk", env = mapOf("A" to "1")), store.read("openai"))
        val oauth = store.read("anthropic") as OAuthCredential
        assertEquals(7L, oauth.expires)
        assertTrue("x" in oauth.extras)
    }

    @Test
    fun `legacy bare-key entry is rejected`() = runTest {
        val dir = createTempDirectory()
        writeRaw(dir, "openai", "sk-legacy")
        val store = newStore(dir)
        assertFailsWith<CredentialFormatException> { store.read("openai") }
    }

    @Test
    fun `legacy untagged key-env entry is rejected`() = runTest {
        val dir = createTempDirectory()
        writeRaw(dir, "cloudflare", """{"key":"k","env":{"CLOUDFLARE_ACCOUNT_ID":"acc"}}""")
        val store = newStore(dir)
        assertFailsWith<CredentialFormatException> { store.read("cloudflare") }
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
                    OAuthCredential(
                        access = "a$i",
                        refresh = "r",
                        expires =
                            current?.let { (it as OAuthCredential).expires + 1 } ?: 0L
                    )
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

        val failing = EncryptedCredentialStore(
            dir = dir,
            encrypt = { it },
            decrypt = { error("keystore failure") }
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

    @Test
    fun `telemetry records write read and decode spans on success`() = runTest {
        val telemetry = InMemoryTelemetryContext()
        val store = EncryptedCredentialStore(
            dir = createTempDirectory(),
            encrypt = { bytes -> bytes.map { (it + 1).toByte() }.toByteArray() },
            decrypt = { bytes -> bytes.map { (it - 1).toByte() }.toByteArray() },
            diagnostics = PathfinderDiagnostics(telemetry)
        )
        store.modify("openai") { ApiKeyCredential("sk") }
        assertEquals(ApiKeyCredential("sk"), store.read("openai"))

        val byName = telemetry.getSpans().associateBy { it.name }
        assertEquals(
            setOf("pf.credentials.write", "pf.credentials.read", "pf.credentials.decode"),
            byName.keys
        )
        assertEquals(
            attr("openai"),
            byName.getValue("pf.credentials.write").attributes["pf.credentials.provider"]
        )
        assertEquals(
            attr("persisted"),
            byName.getValue("pf.credentials.write").attributes["pf.credentials.outcome"]
        )
        assertEquals(
            attr("decrypted"),
            byName.getValue("pf.credentials.read").attributes["pf.credentials.outcome"]
        )
        assertEquals(SpanStatus.Ok, byName.getValue("pf.credentials.write").status)
        assertEquals(SpanStatus.Ok, byName.getValue("pf.credentials.read").status)
        assertEquals(SpanStatus.Ok, byName.getValue("pf.credentials.decode").status)
    }

    @Test
    fun `telemetry records absent read and type-only failures`() = runTest {
        val telemetry = InMemoryTelemetryContext()
        val dir = createTempDirectory()
        val failing = EncryptedCredentialStore(
            dir = dir,
            encrypt = { error("keystore failure") },
            decrypt = { error("keystore failure") },
            diagnostics = PathfinderDiagnostics(telemetry)
        )

        assertNull(failing.read("openai"))
        val absent = telemetry.getSpans().single()
        assertEquals("pf.credentials.read", absent.name)
        assertEquals(attr("absent"), absent.attributes["pf.credentials.outcome"])

        writeRaw(dir, "zai", "{}")
        assertFailsWith<IllegalStateException> { failing.read("zai") }
        val readFailed = telemetry.getSpans().last()
        assertEquals("pf.credentials.read", readFailed.name)
        val readError = readFailed.status as SpanStatus.Error
        assertEquals("IllegalStateException", readError.error?.name)
        assertEquals("", readError.error?.message)
    }

    @Test
    fun `telemetry records decode rejection and write failure with type only`() = runTest {
        val telemetry = InMemoryTelemetryContext()
        val dir = createTempDirectory()
        val store = EncryptedCredentialStore(
            dir = dir,
            encrypt = { bytes -> bytes.map { (it + 1).toByte() }.toByteArray() },
            decrypt = { bytes -> bytes.map { (it - 1).toByte() }.toByteArray() },
            diagnostics = PathfinderDiagnostics(telemetry)
        )

        writeRaw(dir, "openai", "sk-legacy")
        assertFailsWith<CredentialFormatException> { store.read("openai") }
        val decodeFailed = telemetry.getSpans().last()
        assertEquals("pf.credentials.decode", decodeFailed.name)
        val decodeError = decodeFailed.status as SpanStatus.Error
        assertEquals("CredentialFormatException", decodeError.error?.name)
        assertEquals("", decodeError.error?.message)

        val failingWrite = EncryptedCredentialStore(
            dir = dir,
            encrypt = { error("keystore failure") },
            decrypt = { bytes -> bytes.map { (it - 1).toByte() }.toByteArray() },
            diagnostics = PathfinderDiagnostics(telemetry)
        )
        assertFailsWith<IllegalStateException> {
            failingWrite.modify("zai") { ApiKeyCredential("k") }
        }
        val writeFailed = telemetry.getSpans().last()
        assertEquals("pf.credentials.write", writeFailed.name)
        assertEquals("IllegalStateException", (writeFailed.status as SpanStatus.Error).error?.name)
    }

    @Test
    fun `telemetry records delete outcome`() = runTest {
        val telemetry = InMemoryTelemetryContext()
        val store = EncryptedCredentialStore(
            dir = createTempDirectory(),
            encrypt = { bytes -> bytes.map { (it + 1).toByte() }.toByteArray() },
            decrypt = { bytes -> bytes.map { (it - 1).toByte() }.toByteArray() },
            diagnostics = PathfinderDiagnostics(telemetry)
        )
        store.delete("openai")
        store.modify("openai") { ApiKeyCredential("sk") }
        store.delete("openai")

        val deletes = telemetry.getSpans().filter { it.name == "pf.credentials.delete" }
        assertEquals(2, deletes.size)
        assertEquals(attr("absent"), deletes[0].attributes["pf.credentials.outcome"])
        assertEquals(attr("deleted"), deletes[1].attributes["pf.credentials.outcome"])
        assertEquals(SpanStatus.Ok, deletes[0].status)
    }

    @Test
    fun `cancelled read and write settle ok, never as error spans`() = runTest {
        val telemetry = InMemoryTelemetryContext()
        val cancelled = CancellationException("scope cancelled")
        val dir = createTempDirectory()
        val store = EncryptedCredentialStore(
            dir = dir,
            encrypt = { throw cancelled },
            decrypt = { throw cancelled },
            diagnostics = PathfinderDiagnostics(telemetry)
        )
        writeRaw(dir, "openai", "{}")

        assertFailsWith<CancellationException> { store.read("openai") }
        assertFailsWith<CancellationException> { store.modify("zai") { ApiKeyCredential("k") } }

        val reads = telemetry.getSpans().filter { it.name == "pf.credentials.read" }
        val write = telemetry.getSpans().single { it.name == "pf.credentials.write" }
        assertTrue(reads.isNotEmpty())
        reads.forEach { assertEquals(SpanStatus.Ok, it.status) } // cancellation is not a failure
        assertEquals(SpanStatus.Ok, write.status)
    }

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("credstore").toFile().apply {
            deleteOnExit()
        }
}
