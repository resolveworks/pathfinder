package works.resolve.pathfinder.ai.auth.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class OAuthForegroundGateTest {

    private class ControllableGate : OAuthForegroundGate {
        val proceed = CompletableDeferred<Unit>()
        var awaited = 0

        override suspend fun awaitForeground() {
            awaited += 1
            proceed.await()
        }
    }

    private class RecordingClient(vararg responses: OAuthHttpResponse) : OAuthHttpClient {
        private val remaining = responses.toMutableList()
        val requests = mutableListOf<OAuthHttpRequest>()

        override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
            requests += request
            return remaining.removeAt(0)
        }
    }

    private fun request(url: String = "https://auth.example.test/token") = OAuthHttpRequest(
        method = "POST",
        url = url,
        body = ByteArray(0),
        timeoutMs = 30_000
    )

    @Test
    fun `http client defers execute while backgrounded and runs it on foreground`() = runBlocking {
        val gate = ControllableGate()
        val client = RecordingClient(OAuthHttpResponse(200, emptyMap(), ByteArray(0)))
        val gated = ForegroundGatedOAuthHttpClient(client, gate)

        val job = launch { gated.execute(request()) }
        withTimeout(100) {
            while (gate.awaited == 0) kotlinx.coroutines.delay(1)
        }
        assertTrue(
            client.requests.isEmpty(),
            "execute must not reach the network while backgrounded"
        )

        gate.proceed.complete(Unit)
        job.join()
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `http client executes immediately when already foregrounded`() = runBlocking {
        val gate = OAuthForegroundGate.NONE
        val client = RecordingClient(OAuthHttpResponse(200, emptyMap(), ByteArray(0)))
        val response = ForegroundGatedOAuthHttpClient(client, gate).execute(request())
        assertEquals(200, response.status)
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `cancelling the caller cancels a gated exchange`() = runBlocking {
        val never = OAuthForegroundGate { CompletableDeferred<Unit>().await() }
        val gated = ForegroundGatedOAuthHttpClient(RecordingClient(), never)
        val job = async { gated.execute(request()) }
        // Give the gate a chance to suspend, then cancel.
        kotlinx.coroutines.delay(10)
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        Unit
    }

    @Test
    fun `AppForegroundGate awaits until foregrounded`() = runBlocking {
        val gate = AppForegroundGate()
        gate.onAppBackgrounded()
        val job = launch { gate.awaitForeground() }
        assertTrue(job.isActive, "awaitForeground must suspend while backgrounded")
        gate.onAppForegrounded()
        job.join()
        Unit
    }

    @Test
    fun `loopback waitForResult blocks while backgrounded and resumes on foreground`() =
        runBlocking {
            val gate = AppForegroundGate()
            val handle = LoopbackOAuthServer<String>(
                port = 0,
                gate = gate,
                handler = { _, settle ->
                    settle("code")
                    LoopbackCallbackResponse(200, "ok")
                }
            ).start()!!
            try {
                // Simulate the browser landing the redirect while the app is
                // backgrounded: the server must keep serving, but the flow's
                // wait must not proceed.
                val connection = java.net.URL(
                    "http://127.0.0.1:${handle.port}/cb"
                ).openConnection() as java.net.HttpURLConnection
                try {
                    connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }

                gate.onAppBackgrounded()
                val waiter = async { handle.waitForResult() }
                kotlinx.coroutines.delay(50)
                assertTrue(waiter.isActive, "waitForResult must stay gated while backgrounded")

                gate.onAppForegrounded()
                assertEquals("code", waiter.await())
            } finally {
                handle.close()
            }
        }

    @Test
    fun `cancelling the login still cancels a gated loopback wait`() = runBlocking {
        val gate = AppForegroundGate()
        gate.onAppBackgrounded()
        val handle = LoopbackOAuthServer<String>(
            port = 0,
            gate = gate,
            handler = { _, _ -> LoopbackCallbackResponse(200, "ok") }
        ).start()!!
        try {
            val waiter = async { handle.waitForResult() }
            kotlinx.coroutines.delay(50)
            assertTrue(waiter.isActive)
            waiter.cancel()
            assertFailsWith<CancellationException> { waiter.await() }
            Unit
        } finally {
            handle.close()
        }
    }
}
