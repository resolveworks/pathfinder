package works.resolve.pathfinder.ai.utils

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.StopReason

/**
 * Regex patterns matching the error messages providers return when the input
 * exceeds the model's context window. Provider-specific examples:
 *
 * - Anthropic: "prompt is too long: 213462 tokens > 200000 maximum"
 * - Anthropic: "413 {\"error\":{\"type\":\"request_too_large\",...}}"
 * - OpenAI: "Your input exceeds the context window of this model"
 * - OpenAI/LiteLLM: "Requested token count exceeds the model's maximum context length of 131072 tokens"
 * - Google: "The input token count (1196265) exceeds the maximum number of tokens allowed (1048575)"
 * - Together AI: "The input (X tokens) is longer than the model's context length (Y tokens)."
 * - z.ai: does NOT error, accepts overflow silently — handled via usage.input > contextWindow
 * - Xiaomi MiMo: truncates input to fill the context window exactly, then returns finish_reason
 *   "length" with output=0 — detected via stopReason LENGTH + zero output + filled context
 */
private val OVERFLOW_PATTERNS = listOf(
    Regex("prompt is too long", RegexOption.IGNORE_CASE), // Anthropic token overflow
    // Anthropic request byte-size overflow (HTTP 413)
    Regex("request_too_large", RegexOption.IGNORE_CASE),
    Regex("input is too long for requested model", RegexOption.IGNORE_CASE), // Amazon Bedrock
    // OpenAI (Completions & Responses API)
    Regex("exceeds the context window", RegexOption.IGNORE_CASE),
    Regex(
        "exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))",
        RegexOption.IGNORE_CASE
    ), // OpenAI-compatible proxies (LiteLLM)
    Regex("input token count.*exceeds the maximum", RegexOption.IGNORE_CASE), // Google (Gemini)
    Regex("maximum prompt length is \\d+", RegexOption.IGNORE_CASE), // xAI (Grok)
    Regex("reduce the length of the messages", RegexOption.IGNORE_CASE), // Groq
    // OpenRouter (most backends)
    Regex("maximum context length is \\d+ tokens", RegexOption.IGNORE_CASE),
    Regex(
        "exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?",
        RegexOption.IGNORE_CASE
    ), // OpenRouter/Poolside
    Regex(
        "input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)",
        RegexOption.IGNORE_CASE
    ), // Together AI
    Regex("exceeds the limit of \\d+", RegexOption.IGNORE_CASE), // GitHub Copilot
    Regex("exceeds the available context size", RegexOption.IGNORE_CASE), // llama.cpp server
    Regex("greater than the context length", RegexOption.IGNORE_CASE), // LM Studio
    Regex("context window exceeds limit", RegexOption.IGNORE_CASE), // MiniMax
    Regex("exceeded model token limit", RegexOption.IGNORE_CASE), // Kimi For Coding
    // Mistral
    Regex("too large for model with \\d+ maximum context length", RegexOption.IGNORE_CASE),
    Regex(
        "prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?",
        RegexOption.IGNORE_CASE
    ), // DS4 server
    // z.ai non-standard finish_reason surfaced as error text
    Regex("model_context_window_exceeded", RegexOption.IGNORE_CASE),
    // Ollama explicit overflow error
    Regex("prompt too long; exceeded (?:max )?context length", RegexOption.IGNORE_CASE),
    // DashScope / Qwen Token Plan
    Regex("range of input length should be", RegexOption.IGNORE_CASE),
    Regex("context[_ ]length[_ ]exceeded", RegexOption.IGNORE_CASE), // Generic fallback
    Regex("too many tokens", RegexOption.IGNORE_CASE), // Generic fallback
    Regex("token limit exceeded", RegexOption.IGNORE_CASE), // Generic fallback
    // Cerebras: 400/413 with no body
    Regex("^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)", RegexOption.IGNORE_CASE)
)

/**
 * Error messages matching any of these are excluded from overflow detection
 * even if they also match an overflow pattern: Bedrock formats throttling
 * errors as "ThrottlingException: Too many tokens, please wait before trying
 * again." which would match the "too many tokens" overflow pattern without
 * this exclusion.
 */
private val NON_OVERFLOW_PATTERNS = listOf(
    // AWS Bedrock non-overflow errors (formatBedrockError prefixes)
    Regex("^(Throttling error|Service unavailable):", RegexOption.IGNORE_CASE),
    Regex("rate limit", RegexOption.IGNORE_CASE), // Generic rate limiting
    Regex("too many requests", RegexOption.IGNORE_CASE) // Generic HTTP 429 style
)

/**
 * Check if an assistant message represents a context overflow error. Three
 * cases:
 *
 * 1. Error-based overflow: most providers return stopReason ERROR with a
 *    specific error message pattern.
 * 2. Silent overflow: some providers (z.ai) accept overflow requests and
 *    return successfully; detected via usage.input exceeding the context window.
 * 3. Length-stop overflow: Xiaomi MiMo can return LENGTH with zero output when
 *    the input fills the context window.
 *
 * Cases 2 and 3 need a non-zero [contextWindow]; silent truncation (some
 * Ollama deployments) remains undetectable because the expected token count
 * is unknown.
 *
 * @param contextWindow optional context window size enabling cases 2 and 3; a
 *   null or zero value disables them, mirroring pi's JS truthiness check
 *   `if (contextWindow && ...)`.
 */
fun isContextOverflow(message: AssistantMessage, contextWindow: Int? = null): Boolean {
    if (message.stopReason == StopReason.ERROR && message.errorMessage != null) {
        val errorMessage = message.errorMessage!!
        val isNonOverflow = NON_OVERFLOW_PATTERNS.any { it.containsMatchIn(errorMessage) }
        if (!isNonOverflow && OVERFLOW_PATTERNS.any { it.containsMatchIn(errorMessage) }) {
            return true
        }
    }

    if (contextWindow != null && contextWindow > 0 && message.stopReason == StopReason.STOP) {
        val inputTokens = message.usage.input + message.usage.cacheRead
        if (inputTokens > contextWindow) {
            return true
        }
    }

    if (contextWindow != null && contextWindow > 0 &&
        message.stopReason == StopReason.LENGTH && message.usage.output == 0
    ) {
        val inputTokens = message.usage.input + message.usage.cacheRead
        if (inputTokens >= contextWindow * 0.99) {
            return true
        }
    }

    return false
}

/**
 * Check whether a length stop ended below the caller or model's intended
 * output limit. Such responses may be caused by context pressure or
 * provider-side truncation, so callers can make one bounded compact-and-retry
 * attempt. [desiredMaxOutput] must be the original limit before any
 * context-based clamping.
 */
fun isRecoverableLength(message: AssistantMessage, desiredMaxOutput: Int): Boolean =
    message.stopReason == StopReason.LENGTH && desiredMaxOutput > 0 &&
        message.usage.output < desiredMaxOutput

internal fun getOverflowPatterns(): List<Regex> = OVERFLOW_PATTERNS.toList()
