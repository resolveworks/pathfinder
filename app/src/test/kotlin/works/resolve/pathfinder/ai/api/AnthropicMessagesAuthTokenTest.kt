package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.Context
import works.resolve.pathfinder.ai.core.InputModality
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.ModelCost
import works.resolve.pathfinder.ai.core.UserMessage
import works.resolve.pathfinder.ai.testing.FakeClock
import works.resolve.pathfinder.ai.testing.FakeTransport
import works.resolve.pathfinder.ai.utils.ProviderRetry
import works.resolve.pathfinder.ai.utils.getPiUserAgent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ports the request-shaping half of pi's anthropic-auth-token.test.ts.
 *
 * Upstream's ambient env-resolution cases (ANTHROPIC_AUTH_TOKEN resolving to
 * a bearer Authorization header, ANTHROPIC_OAUTH_TOKEN preserving OAuth-mode
 * shaping, authContext threading) are deliberately not ported: Pathfinder
 * reduces pi's ambient ANTHROPIC_AUTH_TOKEN / ANTHROPIC_OAUTH_TOKEN env
 * paths to ANTHROPIC_API_KEY (KDoc divergence on AnthropicMessagesApi). The
 * OAuth-token request shaping itself is covered here and in the stream
 * suite's `oauth token uses bearer auth and claude code headers`.
 */
class AnthropicMessagesAuthTokenTest {

    private val claude = Model(
        id = "claude-test",
        name = "Claude Test",
        api = "anthropic-messages",
        provider = "anthropic",
        baseUrl = "https://api.anthropic.com",
        reasoning = false,
        input = listOf(InputModality.TEXT),
        cost = ModelCost(input = 0.0, output = 0.0, cacheRead = 0.0, cacheWrite = 0.0),
        contextWindow = 100_000,
        maxTokens = 4096,
    )

    private val context = Context(
        systemPrompt = "System prompt.",
        messages = listOf(UserMessage.ofText("Hello")),
    )

    private fun api(transport: FakeTransport) = AnthropicMessagesApi(
        transport,
        ProviderRetry(sleep = {}, clock = FakeClock(0L), random = { 0.0 }),
        clock = FakeClock(1_770_000_000_000L),
    )

    private fun okStream(): List<Pair<String?, String>> = listOf(
        "message_start" to
            """{"type":"message_start","message":{"id":"msg_test","usage":{"input_tokens":1,"output_tokens":0}}}""",
        "message_delta" to
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    @Test
    fun `authorization headers are used without oauth-mode request shaping`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(okStream())
        api(transport)
            .stream(claude, context, AnthropicMessagesOptions(headers = mapOf("authorization" to "Bearer gateway-token")))
            .toList()

        val request = transport.requests.single()
        assertNull(request.bearerToken)
        assertTrue(request.headers.keys.none { it.equals("x-api-key", ignoreCase = true) })
        assertEquals("Bearer gateway-token", request.headers["authorization"])
        // Authorization-header auth is not OAuth: no claude-code/oauth betas.
        assertFalse((request.headers["anthropic-beta"] ?: "").contains("oauth-2025-04-20"))
        val body = Json.parseToJsonElement(request.body.decodeToString()).jsonObject
        // The system prompt is sent as-is, without the Claude Code identity block.
        val system = body["system"]!!.jsonArray
        assertEquals(1, system.size)
        assertEquals("System prompt.", system[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sends pi user agent by default for anthropic messages requests`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(okStream())
        api(transport).stream(claude, context, AnthropicMessagesOptions(apiKey = "anthropic-key")).toList()

        // Divergence: the product token is `pathfinder (...)`, not pi's
        // `pi (...)` — see getPiUserAgent().
        assertEquals(getPiUserAgent(), transport.requests.single().headers["User-Agent"])
    }

    @Test
    fun `explicit user agent header overrides the default`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(okStream())
        val kimiCoding = claude.copy(
            id = "kimi-for-coding",
            name = "Kimi For Coding",
            provider = "kimi-coding",
            baseUrl = "https://api.kimi.com/coding",
        )
        api(transport)
            .stream(
                kimiCoding,
                context,
                AnthropicMessagesOptions(apiKey = "kimi-key", headers = mapOf("User-Agent" to "custom-client")),
            )
            .toList()

        assertEquals("custom-client", transport.requests.single().headers["User-Agent"])
    }

    @Test
    fun `explicit authorization header overrides model headers`() = runTest {
        val transport = FakeTransport()
        transport.enqueueNamedResponse(okStream())
        val withModelAuth = claude.copy(headers = mapOf("Authorization" to "Bearer model-token"))
        api(transport)
            .stream(
                withModelAuth,
                context,
                AnthropicMessagesOptions(
                    apiKey = "k",
                    headers = mapOf("authorization" to "Bearer explicit-token"),
                ),
            )
            .toList()

        val request = transport.requests.single()
        assertEquals(1, request.headers.keys.count { it.equals("authorization", ignoreCase = true) })
        assertEquals("Bearer explicit-token", request.headers["authorization"])
        // pi's ambient auth-token collapses to the Authorization header; the
        // apiKey pathfinder uses instead stays on x-api-key alongside it.
        assertEquals("k", request.headers["x-api-key"])
    }
}
