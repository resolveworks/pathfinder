package works.resolve.pathfinder.ai.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.StopReason
import works.resolve.pathfinder.ai.core.Usage

/**
 * Port of pi's `packages/ai/test/overflow.test.ts` (vitest) for the overflow
 * classification ported from `packages/ai/src/utils/overflow.ts`.
 */
class OverflowTest {
    private fun createErrorMessage(errorMessage: String): AssistantMessage = AssistantMessage(
        content = emptyList(),
        api = "openai-completions",
        provider = "ollama",
        model = "qwen3.5:35b",
        usage = Usage(),
        stopReason = StopReason.ERROR,
        errorMessage = errorMessage,
    )

    private fun createLengthStopMessage(
        input: Int,
        cacheRead: Int,
        output: Int,
        cacheWrite: Int = 0,
        api: String = "openai-completions",
        provider: String = "test-provider",
        model: String = "test-model",
    ): AssistantMessage = AssistantMessage(
        content = emptyList(),
        api = api,
        provider = provider,
        model = model,
        usage = Usage(
            input = input,
            output = output,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            totalTokens = input + cacheRead + cacheWrite + output,
        ),
        stopReason = StopReason.LENGTH,
    )

    @Test
    fun `detects explicit Ollama prompt-too-long errors`() {
        val message = createErrorMessage("400 `prompt too long; exceeded max context length by 100918 tokens`")
        assertTrue(isContextOverflow(message, 32768))
    }

    @Test
    fun `detects Together AI context length errors`() {
        val message = createErrorMessage(
            "400 The input (516368 tokens) is longer than the model's context length (262144 tokens).",
        )
        assertTrue(isContextOverflow(message, 262144))
    }

    @Test
    fun `detects LiteLLM-wrapped OpenAI maximum context length errors`() {
        val message = createErrorMessage(
            "Error: 503 litellm.ServiceUnavailableError: litellm.MidStreamFallbackError: " +
                "litellm.APIConnectionError: APIConnectionError: OpenAIException - Requested token count " +
                "exceeds the model's maximum context length of 131072 tokens.",
        )
        assertTrue(isContextOverflow(message, 131072))
    }

    @Test
    fun `detects OpenAI-compatible parenthesized maximum context length errors`() {
        val message = createErrorMessage(
            "Error: 400 Input length (265330) exceeds model's maximum context length (262144).",
        )
        assertTrue(isContextOverflow(message, 262144))
    }

    @Test
    fun `detects OpenRouter Poolside maximum allowed input length errors`() {
        val message = createErrorMessage(
            "Provider returned error: Input length 131393 exceeds the maximum allowed input length of 131040 tokens.",
        )
        assertTrue(isContextOverflow(message, 131072))
    }

    @Test
    fun `detects DS4 configured context size errors`() {
        val message = createErrorMessage(
            "400 Prompt has 256468 tokens, but the configured context size is 256000 tokens",
        )
        assertTrue(isContextOverflow(message, 256000))

        val commaMessage = createErrorMessage(
            "Prompt has 5,958,968 tokens, but the configured context size is 256,000 tokens",
        )
        assertTrue(isContextOverflow(commaMessage, 256000))
    }

    @Test
    fun `does not treat generic non-overflow Ollama errors as overflow`() {
        val message = createErrorMessage("500 `model runner crashed unexpectedly`")
        assertFalse(isContextOverflow(message, 32768))
    }

    @Test
    fun `does not treat Bedrock throttling Too many tokens as overflow`() {
        // Bedrock returns this for HTTP 429 rate limiting, NOT context overflow.
        // formatBedrockError uses a human-readable prefix for ThrottlingException.
        val message = createErrorMessage("Throttling error: Too many tokens, please wait before trying again.")
        assertFalse(isContextOverflow(message, 200000))
    }

    @Test
    fun `does not treat Bedrock service unavailable as overflow`() {
        val message = createErrorMessage("Service unavailable: The service is temporarily unavailable.")
        assertFalse(isContextOverflow(message, 200000))
    }

    @Test
    fun `does not treat generic rate limit errors as overflow`() {
        val message = createErrorMessage("Rate limit exceeded, please retry after 30 seconds.")
        assertFalse(isContextOverflow(message, 200000))
    }

    @Test
    fun `does not treat HTTP 429 style errors as overflow`() {
        val message = createErrorMessage("Too many requests. Please slow down.")
        assertFalse(isContextOverflow(message, 200000))
    }

    @Test
    fun `detects Xiaomi-style overflow - length stop with zero output and filled context`() {
        val message = createLengthStopMessage(
            input = 58,
            cacheRead = 1048512,
            output = 0,
            provider = "xiaomi",
            model = "mimo-v2.5-pro",
        )
        assertTrue(isContextOverflow(message, 1048576))
    }

    @Test
    fun `treats a length stop below the desired output limit as recoverable`() {
        val message = createLengthStopMessage(
            input = 3,
            cacheRead = 253584,
            cacheWrite = 25554,
            output = 16,
            api = "openai-responses",
            provider = "openai",
            model = "gpt-5.6-sol",
        )
        assertTrue(isRecoverableLength(message, 128000))
    }

    @Test
    fun `does not recover a length stop that reached the desired output limit`() {
        val message = createLengthStopMessage(input = 4062, cacheRead = 0, output = 1024)
        assertFalse(isRecoverableLength(message, 1024))
    }

    @Test
    fun `treats zero-output length stops as recoverable without context metadata`() {
        val message = createLengthStopMessage(input = 100, cacheRead = 0, output = 0)
        assertTrue(isRecoverableLength(message, 128000))
    }

    @Test
    fun `does not treat normal length stops with output as context overflow`() {
        val message = createLengthStopMessage(input = 1000, cacheRead = 0, output = 4096)
        assertFalse(isContextOverflow(message, 200000))
    }

    @Test
    fun `does not treat zero-output length stops far below context as context overflow`() {
        val message = createLengthStopMessage(input = 100, cacheRead = 0, output = 0)
        assertFalse(isContextOverflow(message, 200000))
    }

    @Test
    fun `getOverflowPatterns returns a copy of the pattern list`() {
        val patterns = getOverflowPatterns()
        assertEquals(patterns, getOverflowPatterns())
    }
}
