package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.TextContent

class RetryTest {

    private fun fauxAssistantMessage(
        text: String = "",
        stopReason: StopReason = StopReason.STOP,
        errorMessage: String? = null
    ): AssistantMessage = AssistantMessage(
        content = listOf(TextContent(text)),
        api = "faux",
        provider = "faux",
        model = "faux",
        stopReason = stopReason,
        errorMessage = errorMessage
    )

    private val openAIExplicitRetryMessage =
        "An error occurred while processing your request. You can retry your request, or contact us through our help center at help.openai.com if the error persists. Please include the request ID req_******** in your message."
    private val bedrockExplicitRetryMessage =
        """{"message":"The system encountered an unexpected error during processing. Try your request again."}"""
    private val nvidiaNIMResourceExhaustedMessage =
        "ResourceExhausted: Worker local total request limit reached (288/48)"
    private val bunFetchSocketClosedMessage =
        "The socket connection was closed unexpectedly. For more information, pass `verbose: true` in the second argument to fetch()"
    private val openAIResponsesEarlyEofMessage =
        "OpenAI Responses stream ended before a terminal response event"
    private val wrappedDnsLookupError =
        "The pending stream has been canceled (caused by: getaddrinfo ENOTFOUND bedrock-runtime.us-east-1.amazonaws.com)"

    private val disabled = RetryPolicy(enabled = false, maxRetries = 3, baseDelayMs = 0)
    private val enabled = RetryPolicy(enabled = true, maxRetries = 3, baseDelayMs = 0)

    @Test
    fun `matches explicit provider retry guidance`() {
        val retry = Retry()
        assertTrue(
            retry.isRetryableAssistantError(
                fauxAssistantMessage(
                    stopReason = StopReason.ERROR,
                    errorMessage = openAIExplicitRetryMessage
                )
            )
        )
        assertTrue(
            retry.isRetryableAssistantError(
                fauxAssistantMessage(
                    stopReason = StopReason.ERROR,
                    errorMessage = bedrockExplicitRetryMessage
                )
            )
        )
        assertTrue(
            retry.isRetryableAssistantError(
                fauxAssistantMessage(
                    stopReason = StopReason.ERROR,
                    errorMessage = nvidiaNIMResourceExhaustedMessage
                )
            )
        )
    }

    @Test
    fun `matches Bun fetch socket drop wording`() {
        assertTrue(
            Retry().isRetryableAssistantError(
                fauxAssistantMessage(
                    stopReason = StopReason.ERROR,
                    errorMessage = bunFetchSocketClosedMessage
                )
            )
        )
    }

    @Test
    fun `matches upstream request buffer exhaustion wording`() {
        assertTrue(
            Retry().isRetryableAssistantError(
                fauxAssistantMessage(
                    stopReason = StopReason.ERROR,
                    errorMessage = "Error: exceeded request buffer limit while retrying upstream"
                )
            )
        )
    }

    @Test
    fun `matches DNS transport failure wording`() {
        val retry = Retry()
        for (errorMessage in listOf(
            wrappedDnsLookupError,
            "connect ENOTFOUND api.example.com",
            "EAI_AGAIN api.example.com",
            "getaddrinfo failed for api.example.com"
        )) {
            assertTrue(
                retry.isRetryableAssistantError(
                    fauxAssistantMessage(stopReason = StopReason.ERROR, errorMessage = errorMessage)
                )
            )
        }
    }

    @Test
    fun `matches HTTP2 pending stream cancellation`() {
        for (errorMessage in listOf(
            "The pending stream has been canceled",
            "The pending stream has been canceled (caused by: socket closed)"
        )) {
            // Regression for #10379.
            assertTrue(
                Retry().isRetryableAssistantError(
                    fauxAssistantMessage(stopReason = StopReason.ERROR, errorMessage = errorMessage)
                )
            )
        }
    }

    @Test
    fun `matches service unavailable wording`() {
        for (errorMessage in listOf(
            // Regression for #10965 (provider unknown, no status code in the message).
            "The service is temporarily unavailable. Please retry later.",
            // Bedrock ServiceUnavailableException as formatted by formatBedrockError.
            "Service unavailable: The service is temporarily unavailable."
        )) {
            assertTrue(
                Retry().isRetryableAssistantError(
                    fauxAssistantMessage(stopReason = StopReason.ERROR, errorMessage = errorMessage)
                )
            )
        }
    }

    @Test
    fun `matches node-fetch request failure wording`() {
        for (errorMessage in listOf(
            // Regression for #10996: node-fetch errors from the google-vertex OAuth token refresh.
            "request to https://oauth2.googleapis.com/token failed, reason:",
            "request to https://oauth2.googleapis.com/token failed, reason: connect ECONNREFUSED 127.0.0.1:9"
        )) {
            assertTrue(
                Retry().isRetryableAssistantError(
                    fauxAssistantMessage(stopReason = StopReason.ERROR, errorMessage = errorMessage)
                )
            )
        }
    }

    @Test
    fun `matches gateway upstream failure wording`() {
        for (errorMessage in listOf(
            // Regression for #10920: gateway upstream failures sent as `response.failed` error codes.
            "unknown: Unable to forward upstream stream",
            "upstream_error: Upstream request failed",
            "upstream_stream_read_error: Upstream response stream was interrupted",
            "Upstream HTTP/2 stream failed"
        )) {
            assertTrue(
                Retry().isRetryableAssistantError(
                    fauxAssistantMessage(stopReason = StopReason.ERROR, errorMessage = errorMessage)
                )
            )
        }
    }

    @Test
    fun `keeps the ChatGPT subscription usage limit non-retryable`() {
        val errorMessage =
            """OpenAI API error (429): {"code":"subscription_sharing_usage_limit_exceeded","message":"Usage limit reached."}"""
        assertFalse(
            Retry().isRetryableAssistantError(
                fauxAssistantMessage(stopReason = StopReason.ERROR, errorMessage = errorMessage)
            )
        )
    }

    @Test
    fun `keeps the ChatGPT Business workspace usage limit non-retryable`() {
        val errorMessage =
            """OpenAI API error (429): {"code":"subscription_sharing_workplace_individual_usage_limit_exceeded","message":"Usage limit reached."}"""
        assertFalse(
            Retry().isRetryableAssistantError(
                fauxAssistantMessage(stopReason = StopReason.ERROR, errorMessage = errorMessage)
            )
        )
    }

    @Test
    fun `retries temporary ChatGPT subscription errors`() {
        for (errorMessage in listOf(
            "subscription_sharing_usage_unavailable: Usage cannot be checked.",
            "subscription_sharing_user_unavailable: User cannot be loaded."
        )) {
            assertTrue(
                Retry().isRetryableAssistantError(
                    fauxAssistantMessage(stopReason = StopReason.ERROR, errorMessage = errorMessage)
                )
            )
        }
    }

    @Test
    fun `does not match status codes inside ids or longer numbers`() {
        for (errorMessage in listOf(
            // Regression for #11057: status codes inside ids or other numbers are not status codes.
            """401 {"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"},"request_id":"req_011CU503xyz"}""",
            "400 image exceeds 5 MB maximum: 5316784 bytes > 5242880 bytes"
        )) {
            assertFalse(
                Retry().isRetryableAssistantError(
                    fauxAssistantMessage(stopReason = StopReason.ERROR, errorMessage = errorMessage)
                )
            )
        }
    }

    @Test
    fun `matches OpenAI Responses streams that end before terminal events`() {
        assertTrue(
            Retry().isRetryableAssistantError(
                fauxAssistantMessage(
                    stopReason = StopReason.ERROR,
                    errorMessage = openAIResponsesEarlyEofMessage
                )
            )
        )
    }

    @Test
    fun `keeps provider limit errors non-retryable`() {
        assertFalse(
            Retry().isRetryableAssistantError(
                fauxAssistantMessage(
                    stopReason = StopReason.ERROR,
                    errorMessage = "429 quota exceeded"
                )
            )
        )
    }

    @Test
    fun `classifies assistant error messages`() {
        val retry = Retry()
        assertTrue(
            retry.isRetryableAssistantError(
                fauxAssistantMessage(
                    stopReason = StopReason.ERROR,
                    errorMessage = "overloaded_error"
                )
            )
        )
        assertTrue(
            retry.isRetryableAssistantError(
                fauxAssistantMessage(
                    stopReason = StopReason.ERROR,
                    errorMessage = "524 status code (no body)"
                )
            )
        )
        assertFalse(retry.isRetryableAssistantError(fauxAssistantMessage("not an error")))
    }

    @Test
    fun `returns a successful response immediately without retrying`() = runTest {
        var calls = 0
        val res = Retry().retryAssistantCall({
            calls++
            fauxAssistantMessage("ok")
        }, enabled)
        assertEquals(listOf(TextContent("ok")), res.content)
        assertEquals(1, calls)
    }

    @Test
    fun `does not retry an aborted message`() = runTest {
        var calls = 0
        var scheduled = 0
        val res = Retry().retryAssistantCall(
            {
                calls++
                fauxAssistantMessage(stopReason = StopReason.ABORTED)
            },
            enabled,
            RetryCallbacks(onRetryScheduled = { _, _, _, _ -> scheduled++ })
        )
        assertEquals(StopReason.ABORTED, res.stopReason)
        assertEquals(1, calls)
        assertEquals(0, scheduled)
    }

    @Test
    fun `does not retry a non-retryable error quota billing`() = runTest {
        var calls = 0
        var scheduled = 0
        var finished = 0
        val res = Retry().retryAssistantCall(
            {
                calls++
                fauxAssistantMessage(
                    stopReason = StopReason.ERROR,
                    errorMessage = "insufficient_quota"
                )
            },
            enabled,
            RetryCallbacks(
                onRetryScheduled = { _, _, _, _ -> scheduled++ },
                onRetryFinished = { _, _, _ -> finished++ }
            )
        )
        assertEquals(StopReason.ERROR, res.stopReason)
        assertEquals(1, calls)
        assertEquals(0, scheduled)
        assertEquals(0, finished)
    }

    @Test
    fun `retries a transient error up to maxRetries then returns the final error`() = runTest {
        var calls = 0
        var scheduled = 0
        var finished: Triple<Boolean, Int, String?>? = null
        val res = Retry().retryAssistantCall(
            {
                calls++
                fauxAssistantMessage(stopReason = StopReason.ERROR, errorMessage = "terminated")
            },
            enabled,
            RetryCallbacks(
                onRetryScheduled = { _, _, _, _ -> scheduled++ },
                onRetryFinished = { success, attempt, finalError ->
                    finished =
                        Triple(success, attempt, finalError)
                }
            )
        )
        assertEquals(StopReason.ERROR, res.stopReason)
        assertEquals(4, calls) // 1 initial + 3 retries
        assertEquals(3, scheduled)
        assertEquals(Triple(false, 3, "terminated"), finished)
    }

    @Test
    fun `stops retrying once a call succeeds`() = runTest {
        var n = 0
        var finished: Triple<Boolean, Int, String?>? = null
        val res = Retry().retryAssistantCall(
            {
                n++
                if (n <
                    3
                ) {
                    fauxAssistantMessage(
                        stopReason = StopReason.ERROR,
                        errorMessage = "terminated"
                    )
                } else {
                    fauxAssistantMessage("recovered")
                }
            },
            enabled,
            RetryCallbacks(onRetryFinished = { success, attempt, finalError ->
                finished =
                    Triple(success, attempt, finalError)
            })
        )
        assertEquals(listOf(TextContent("recovered")), res.content)
        assertEquals(3, n)
        assertEquals(Triple(true, 2, null), finished)
    }

    @Test
    fun `reports an aborted retried call as unsuccessful`() = runTest {
        var n = 0
        var finished: Triple<Boolean, Int, String?>? = null
        val res = Retry().retryAssistantCall(
            {
                n++
                if (n ==
                    1
                ) {
                    fauxAssistantMessage(
                        stopReason = StopReason.ERROR,
                        errorMessage = "terminated"
                    )
                } else {
                    fauxAssistantMessage(stopReason = StopReason.ABORTED)
                }
            },
            enabled,
            RetryCallbacks(onRetryFinished = { success, attempt, finalError ->
                finished =
                    Triple(success, attempt, finalError)
            })
        )
        assertEquals(StopReason.ABORTED, res.stopReason)
        assertEquals(2, n)
        assertEquals(Triple(false, 1, null), finished)
    }

    @Test
    fun `does not retry when policy is disabled`() = runTest {
        var calls = 0
        var scheduled = 0
        var finished = 0
        val res = Retry().retryAssistantCall(
            {
                calls++
                fauxAssistantMessage(stopReason = StopReason.ERROR, errorMessage = "terminated")
            },
            disabled,
            RetryCallbacks(
                onRetryScheduled = { _, _, _, _ -> scheduled++ },
                onRetryFinished = { _, _, _ -> finished++ }
            )
        )
        assertEquals(StopReason.ERROR, res.stopReason)
        assertEquals(1, calls)
        assertEquals(0, scheduled)
        assertEquals(0, finished)
    }

    @Test
    fun `emits onRetryAttemptStart after backoff before each retried call`() = runTest {
        val events = mutableListOf<String>()
        var n = 0
        val res = Retry(sleep = { events.add("sleep") }).retryAssistantCall(
            {
                events.add("produce:$n")
                n++
                if (n <
                    3
                ) {
                    fauxAssistantMessage(
                        stopReason = StopReason.ERROR,
                        errorMessage = "terminated"
                    )
                } else {
                    fauxAssistantMessage("recovered")
                }
            },
            enabled,
            RetryCallbacks(
                onRetryScheduled = { attempt, _, _, _ -> events.add("retry:$attempt") },
                onRetryAttemptStart = { events.add("attempt-start") }
            )
        )
        assertEquals(listOf(TextContent("recovered")), res.content)
        assertEquals(
            listOf(
                "produce:0",
                "retry:1", "sleep", "attempt-start",
                "produce:1",
                "retry:2", "sleep", "attempt-start",
                "produce:2"
            ),
            events
        )
    }

    @Test
    fun `aborts backoff sleep via cancellation, returns an aborted message, and emits onRetryFinished false`() =
        runTest {
            var calls = 0
            var finished: Triple<Boolean, Int, String?>? = null
            val res = Retry(sleep = { throw CancellationException("aborted") }).retryAssistantCall(
                {
                    calls++
                    fauxAssistantMessage(stopReason = StopReason.ERROR, errorMessage = "terminated")
                },
                RetryPolicy(enabled = true, maxRetries = 5, baseDelayMs = 10_000),
                RetryCallbacks(onRetryFinished = { success, attempt, finalError ->
                    finished =
                        Triple(success, attempt, finalError)
                })
            )
            assertEquals(StopReason.ABORTED, res.stopReason)
            assertNull(res.errorMessage)
            assertEquals(1, calls)
            assertEquals(Triple(false, 1, "terminated"), finished)
        }
}
