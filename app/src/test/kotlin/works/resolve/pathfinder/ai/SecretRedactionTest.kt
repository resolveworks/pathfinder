package works.resolve.pathfinder.ai

import works.resolve.pathfinder.ai.api.AzureOpenAiResponsesOptions
import works.resolve.pathfinder.ai.api.OpenAiResponsesOptions
import works.resolve.pathfinder.ai.core.OpenAiCompletionsOptions
import works.resolve.pathfinder.ai.core.SimpleStreamOptions
import works.resolve.pathfinder.ai.core.StreamOptions
import works.resolve.pathfinder.ai.core.ThinkingLevel
import works.resolve.pathfinder.ai.models.ResolvedAuth
import works.resolve.pathfinder.ai.transport.TransportRequest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Secrets must never surface in toString(): a distinctive key value must not
 * occur in string output, while data-class copy/equality stays intact.
 */
class SecretRedactionTest {

    private val secret = "sk-SECRET-9f8e7d6c5b4a"

    @Test
    fun `StreamOptions toString omits api key`() {
        val options = StreamOptions(apiKey = secret, sessionId = "s1", temperature = 0.5)
        val rendered = options.toString()
        assertFalse(rendered.contains(secret))
        assertTrue(rendered.contains("<redacted>"))
        assertTrue(rendered.contains("sessionId=s1"))
        assertTrue(rendered.contains("temperature=0.5"))

        val none = StreamOptions(apiKey = null).toString()
        assertFalse(none.contains("redacted"))
        assertTrue(none.contains("apiKey=null"))
    }

    @Test
    fun `options toString output is exactly the redacted field list`() {
        // Byte-identical regression guards for the optionsToString-based
        // implementations: null apiKey renders "null", secrets render
        // "<redacted>", maps render as keys only, hooks as booleans.
        assertEquals(
            "StreamOptions(apiKey=<redacted>, sessionId=s1, temperature=0.5, maxTokens=null, " +
                "timeoutMs=null, maxRetries=0, maxRetryDelayMs=60000, telemetryContext=false)",
            StreamOptions(apiKey = secret, sessionId = "s1", temperature = 0.5).toString(),
        )
        assertEquals(
            "StreamOptions(apiKey=null, sessionId=null, temperature=null, maxTokens=null, " +
                "timeoutMs=null, maxRetries=0, maxRetryDelayMs=60000, telemetryContext=false)",
            StreamOptions().toString(),
        )

        assertEquals(
            "SimpleStreamOptions(apiKey=<redacted>, sessionId=s1, temperature=0.7, maxTokens=42, " +
                "reasoning=MINIMAL, toolChoice=Auto, cacheRetention=null, timeoutMs=null, maxRetries=1, " +
                "maxRetryDelayMs=60000, env=[PI_ENV_A], headers=[Authorization], " +
                "onPayload=true, onResponse=false, samplingParams=[top_k], transport=null, " +
                "websocketConnectTimeoutMs=null, telemetryContext=false)",
            SimpleStreamOptions(
                apiKey = secret,
                sessionId = "s1",
                temperature = 0.7,
                maxTokens = 42,
                reasoning = ThinkingLevel.MINIMAL,
                toolChoice = works.resolve.pathfinder.ai.core.SimpleToolChoice.Auto,
                maxRetries = 1,
                env = mapOf("PI_ENV_A" to secret),
                headers = mapOf("Authorization" to "Bearer $secret"),
                onPayload = { _, _ -> null },
                samplingParams = mapOf("top_k" to JsonPrimitive(1)),
            ).toString(),
        )
        assertEquals(
            "SimpleStreamOptions(apiKey=null, sessionId=null, temperature=null, maxTokens=null, " +
                "reasoning=null, toolChoice=null, cacheRetention=null, timeoutMs=null, maxRetries=0, " +
                "maxRetryDelayMs=60000, env=[], headers=[], onPayload=false, onResponse=false, " +
                "samplingParams=null, transport=null, websocketConnectTimeoutMs=null, telemetryContext=false)",
            SimpleStreamOptions().toString(),
        )

        assertEquals(
            "OpenAiCompletionsOptions(apiKey=<redacted>, sessionId=null, temperature=null, " +
                "maxTokens=null, reasoningEffort=null, toolChoice=null, cacheRetention=null, " +
                "timeoutMs=null, maxRetries=0, maxRetryDelayMs=60000, env=[PI_ENV_A], " +
                "headers=[Authorization], onPayload=false, onResponse=true, samplingParams=[top_k], telemetryContext=false)",
            OpenAiCompletionsOptions(
                apiKey = secret,
                env = mapOf("PI_ENV_A" to secret),
                headers = mapOf("Authorization" to "Bearer $secret"),
                onResponse = { _, _ -> },
                samplingParams = mapOf("top_k" to JsonPrimitive(1)),
            ).toString(),
        )

        assertEquals(
            "OpenAiResponsesOptions(apiKey=<redacted>, sessionId=null, temperature=null, " +
                "maxTokens=null, reasoningEffort=null, reasoningSummary=null, serviceTier=null, " +
                "toolChoice=null, cacheRetention=null, timeoutMs=null, maxRetries=0, " +
                "maxRetryDelayMs=60000, env=[PI_ENV_A], headers=[Authorization], " +
                "onPayload=false, onResponse=false, samplingParams=null, telemetryContext=false)",
            OpenAiResponsesOptions(
                apiKey = secret,
                env = mapOf("PI_ENV_A" to secret),
                headers = mapOf("Authorization" to "Bearer $secret"),
            ).toString(),
        )

        assertEquals(
            "AzureOpenAiResponsesOptions(apiKey=null, sessionId=null, temperature=null, " +
                "maxTokens=null, reasoningEffort=null, reasoningSummary=null, toolChoice=null, " +
                "azureApiVersion=v1, azureResourceName=null, azureBaseUrl=null, " +
                "azureDeploymentName=null, timeoutMs=null, maxRetries=0, maxRetryDelayMs=60000, " +
                "env=[PI_ENV_A], headers=[Authorization], onPayload=false, onResponse=false, " +
                "samplingParams=null, telemetryContext=false)",
            AzureOpenAiResponsesOptions(
                azureApiVersion = "v1",
                env = mapOf("PI_ENV_A" to secret),
                headers = mapOf("Authorization" to "Bearer $secret"),
            ).toString(),
        )
    }

    @Test
    fun `StreamOptions keeps copy and equality`() {
        val options = StreamOptions(apiKey = secret, maxTokens = 42)
        assertEquals(options, options.copy())
        assertEquals(StreamOptions(apiKey = "other", maxTokens = 42), options.copy(apiKey = "other"))
        assertEquals(42, options.copy(temperature = 1.0).maxTokens)
    }

    @Test
    fun `SimpleStreamOptions toString omits api key and header values`() {
        val options = SimpleStreamOptions(
            apiKey = secret,
            temperature = 0.7,
            headers = mapOf("cf-aig-authorization" to "Bearer $secret"),
        )
        assertFalse(options.toString().contains(secret))
        assertTrue(options.toString().contains("<redacted>"))
        assertTrue(options.toString().contains("cf-aig-authorization"))
        assertFalse(SimpleStreamOptions(apiKey = null).toString().contains("redacted"))
        assertEquals(options, options.copy())
    }

    @Test
    fun `OpenAiCompletionsOptions toString omits api key and header values`() {
        val options = OpenAiCompletionsOptions(
            apiKey = secret,
            maxTokens = 100,
            headers = mapOf("cf-aig-authorization" to "Bearer $secret"),
        )
        assertFalse(options.toString().contains(secret))
        assertTrue(options.toString().contains("<redacted>"))
        assertTrue(options.toString().contains("cf-aig-authorization"))
        assertFalse(OpenAiCompletionsOptions(apiKey = null).toString().contains("redacted"))
        assertEquals(options, options.copy())
    }

    @Test
    fun `ResolvedAuth toString omits api key and header values`() {
        val auth = ResolvedAuth(
            apiKey = secret,
            env = mapOf("CLOUDFLARE_ACCOUNT_ID" to "acct"),
            headers = mapOf(
                "cf-aig-authorization" to "Bearer $secret",
                "Authorization" to null,
            ),
        )
        val rendered = auth.toString()
        assertFalse(rendered.contains(secret))
        assertTrue(rendered.contains("<redacted>"))
        assertTrue(rendered.contains("cf-aig-authorization"))
        assertFalse(rendered.contains("Bearer"))

        val noKey = ResolvedAuth(headers = mapOf("cf-aig-authorization" to "Bearer x")).toString()
        assertFalse(noKey.contains("redacted"))
        assertTrue(noKey.contains("apiKey=null"))
    }

    @Test
    fun `TransportRequest toString omits bearer token`() {
        val request = TransportRequest(
            url = "https://api.example.com/v1/chat/completions",
            bearerToken = secret,
            headers = mapOf("Accept" to "text/event-stream"),
            body = "{}".toByteArray(),
        )
        val rendered = request.toString()
        assertFalse(rendered.contains(secret))
        assertTrue(rendered.contains("<redacted>"))
        assertTrue(rendered.contains("url=https://api.example.com/v1/chat/completions"))

        val noAuth = request.copy(bearerToken = null).toString()
        assertFalse(noAuth.contains("redacted"))
        assertTrue(noAuth.contains("bearerToken=null"))
    }

    @Test
    fun `TransportRequest keeps copy and equality`() {
        val request = TransportRequest(
            url = "https://api.example.com",
            bearerToken = secret,
            headers = emptyMap(),
            body = "ping".toByteArray(),
        )
        assertEquals(request, request.copy())
        assertEquals(
            TransportRequest("https://api.example.com", "other", body = "ping".toByteArray()),
            request.copy(bearerToken = "other"),
        )
    }
}
