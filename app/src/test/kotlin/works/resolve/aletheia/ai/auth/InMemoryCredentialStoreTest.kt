package works.resolve.aletheia.ai.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/** Ports the semantics of pi's `InMemoryCredentialStore` tests + contract. */
class InMemoryCredentialStoreTest {

    @Test
    fun `read returns null for missing entries`() = runTest {
        val store = InMemoryCredentialStore()
        assertNull(store.read("openai"))
    }

    @Test
    fun `modify writes and returns the post-write credential`() = runTest {
        val store = InMemoryCredentialStore()
        val written = ApiKeyCredential(key = "sk")
        assertEquals(written, store.modify("openai") { written })
        assertEquals(written, store.read("openai"))
    }

    @Test
    fun `modify returning null leaves the entry unchanged`() = runTest {
        val store = InMemoryCredentialStore()
        val written = ApiKeyCredential(key = "sk")
        store.modify("openai") { written }
        assertEquals(written, store.modify("openai") { null })
        assertEquals(written, store.read("openai"))
    }

    @Test
    fun `modify sees the current credential`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { ApiKeyCredential(key = "first") }
        store.modify("openai") { current -> (current as ApiKeyCredential).copy(key = "second") }
        assertEquals(ApiKeyCredential(key = "second"), store.read("openai"))
    }

    @Test
    fun `modify exceptions propagate without changing the entry`() = runTest {
        val store = InMemoryCredentialStore()
        val written = ApiKeyCredential(key = "sk")
        store.modify("openai") { written }
        assertFailsWith<IllegalStateException> {
            store.modify("openai") { error("boom") }
        }
        assertEquals(written, store.read("openai"))
    }

    @Test
    fun `writes are serialized per provider`() = runTest {
        val store = InMemoryCredentialStore()
        val updates = List(32) { i ->
            async {
                store.modify("openai") { current ->
                    OAuthCredential(
                        access = "a$i",
                        refresh = "r",
                        expires = current?.let { (it as OAuthCredential).expires + 1 } ?: 0L,
                    )
                }
            }
        }.awaitAll()
        // Every update saw exactly its predecessor's write (serialized RMW).
        assertEquals((0L..31L).toList(), updates.map { (it as OAuthCredential).expires })
    }

    @Test
    fun `different providers are not serialized against each other`() = runTest {
        val store = InMemoryCredentialStore()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val blocked = async { store.modify("openai") { gate.await(); ApiKeyCredential("k") } }
        // A different provider can write while `openai`'s modify is in flight.
        store.modify("anthropic") { ApiKeyCredential("other") }
        assertEquals(ApiKeyCredential("other"), store.read("anthropic"))
        gate.complete(Unit)
        blocked.await()
    }

    @Test
    fun `delete removes the entry`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { ApiKeyCredential("k") }
        store.delete("openai")
        assertNull(store.read("openai"))
    }

    @Test
    fun `concurrent delete and modify on the same provider stay serialized`() = runTest {
        val store = InMemoryCredentialStore()
        val jobs = (1..50).map { i ->
            async {
                if (i % 2 == 0) {
                    store.modify("openai") { current ->
                        OAuthCredential(
                            access = "a$i",
                            refresh = "r",
                            expires = current?.let { (it as OAuthCredential).expires + 1 } ?: 0L,
                        )
                    }
                } else {
                    store.delete("openai")
                    null
                }
            }
        }.awaitAll()
        // No lost updates within a single modify chain, no exceptions, and the
        // final state is one of the serialized outcomes (an OAuth credential
        // left by the last modify, or absent after the last delete+no-write).
        val final = store.read("openai")
        if (final is OAuthCredential) {
            assertTrue(final.expires in 0..25) // 25 modify operations at most
        }
    }

    @Test
    fun `delete then modify never loses the mutual exclusion mutex`() = runTest {
        val store = InMemoryCredentialStore()
        // Regression for removing the per-provider mutex inside delete: a
        // queued waiter must never observe a different (new) mutex than a
        // later caller. With the lock retained, every modify is still ordered.
        val first = async {
            store.modify("openai") {
                delay(10)
                ApiKeyCredential("first")
            }
        }
        delay(1) // let `first` take the lock
        val queued = async { store.modify("openai") { ApiKeyCredential("queued") } }
        store.delete("openai")
        store.modify("openai") { ApiKeyCredential("after-delete") }
        first.await()
        queued.await()
        assertEquals(ApiKeyCredential("after-delete"), store.read("openai"))
    }

    @Test
    fun `delete of missing entry is a no-op`() = runTest {
        val store = InMemoryCredentialStore()
        store.delete("openai")
        assertNull(store.read("openai"))
    }

    @Test
    fun `list exposes metadata without secrets`() = runTest {
        val store = InMemoryCredentialStore()
        store.modify("openai") { ApiKeyCredential(key = "sk-SECRET") }
        store.modify("anthropic") { OAuthCredential(access = "SECRET", refresh = "SECRET", expires = 1L) }
        val list = store.list()
        assertEquals(
            listOf(
                CredentialInfo("anthropic", CredentialType.OAUTH),
                CredentialInfo("openai", CredentialType.API_KEY),
            ),
            list.sortedBy { it.providerId },
        )
        assertTrue(list.none { it.toString().contains("SECRET") })
    }
}
