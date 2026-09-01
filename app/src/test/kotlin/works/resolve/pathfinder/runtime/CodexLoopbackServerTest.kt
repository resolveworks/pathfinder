package works.resolve.pathfinder.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real-socket round trips against [CodexLoopbackServer] on an ephemeral
 * loopback port: the happy path (pi's success page + the validated
 * redirect outcome), the skip-and-answer paths pi's server handles (foreign
 * route, state mismatch, missing code), the OAuth error redirect, and
 * lifecycle (bind conflict, cancellation).
 */
class CodexLoopbackServerTest {

    private companion object {
        const val STATE = "0123456789abcdef0123456789abcdef"
    }

    @Test
    fun servesSuccessPageAndReturnsValidatedCode() = runTest {
        val server = CodexLoopbackServer(STATE)
        try {
            server.bind(0)
            val redirect = async(Dispatchers.IO) { server.awaitRedirect() }
            val response = exchange(server.port, callbackRequest("code=abc&state=$STATE"))
            assertTrue(response.startsWith("HTTP/1.1 200"), response)
            assertTrue(response.contains("Authentication successful"), response)
            assertEquals(RedirectResult.Success("abc"), redirect.await())
        } finally {
            server.close()
        }
    }

    @Test
    fun skipsForeignPathsAndStillReturnsCallback() = runTest {
        val server = CodexLoopbackServer(STATE)
        try {
            server.bind(0)
            val redirect = async(Dispatchers.IO) { server.awaitRedirect() }
            val notFound = exchange(server.port, "GET /favicon.ico HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertTrue(notFound.startsWith("HTTP/1.1 404"), notFound)
            exchange(server.port, callbackRequest("code=abc&state=$STATE"))
            assertEquals(RedirectResult.Success("abc"), redirect.await())
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsStateMismatchAndStillWaits() = runTest {
        val server = CodexLoopbackServer(STATE)
        try {
            server.bind(0)
            val redirect = async(Dispatchers.IO) { server.awaitRedirect() }
            val mismatch = exchange(server.port, callbackRequest("code=abc&state=wrong"))
            assertTrue(mismatch.startsWith("HTTP/1.1 400"), mismatch)
            assertTrue(mismatch.contains("State mismatch."), mismatch)
            exchange(server.port, callbackRequest("code=abc&state=$STATE"))
            assertEquals(RedirectResult.Success("abc"), redirect.await())
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsMissingCodeAndStillWaits() = runTest {
        val server = CodexLoopbackServer(STATE)
        try {
            server.bind(0)
            val redirect = async(Dispatchers.IO) { server.awaitRedirect() }
            val missing = exchange(server.port, callbackRequest("state=$STATE"))
            assertTrue(missing.startsWith("HTTP/1.1 400"), missing)
            assertTrue(missing.contains("Missing authorization code."), missing)
            exchange(server.port, callbackRequest("code=abc&state=$STATE"))
            assertEquals(RedirectResult.Success("abc"), redirect.await())
        } finally {
            server.close()
        }
    }

    @Test
    fun returnsErrorResultForErrorRedirect() = runTest {
        val server = CodexLoopbackServer(STATE)
        try {
            server.bind(0)
            val redirect = async(Dispatchers.IO) { server.awaitRedirect() }
            val denied = exchange(
                server.port,
                callbackRequest("error=access_denied&error_description=User+canceled&state=$STATE"),
            )
            assertTrue(denied.startsWith("HTTP/1.1 400"), denied)
            assertTrue(denied.contains("Sign-in failed: User canceled."), denied)
            assertEquals(
                RedirectResult.ErrorResponse("access_denied", "User canceled"),
                redirect.await(),
            )
        } finally {
            server.close()
        }
    }

    @Test
    fun cancellationAbortsWaiting() = runTest {
        val server = CodexLoopbackServer(STATE)
        server.bind(0)
        val awaiter = launch(Dispatchers.IO) { server.awaitRedirect() }
        awaiter.cancelAndJoin()
        server.close()
    }

    @Test
    fun bindFailsWhenPortIsTaken() = runTest {
        val first = CodexLoopbackServer(STATE)
        try {
            first.bind(0)
            val second = CodexLoopbackServer(STATE)
            assertFailsWith<CodexOAuthException> { second.bind(first.port) }
            second.close()
        } finally {
            first.close()
        }
    }

    /** Sends one raw request and returns the full response. */
    private fun exchange(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.US_ASCII))
                flush()
            }
            socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }

    private fun callbackRequest(query: String): String =
        "GET /auth/callback?$query HTTP/1.1\r\nHost: localhost\r\n\r\n"
}
