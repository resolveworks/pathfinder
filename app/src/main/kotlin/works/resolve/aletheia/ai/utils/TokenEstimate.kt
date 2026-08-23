package works.resolve.aletheia.ai.utils

import works.resolve.aletheia.ai.core.Content
import works.resolve.aletheia.ai.core.Context
import works.resolve.aletheia.ai.core.ContentType
import works.resolve.aletheia.ai.core.Message
import works.resolve.aletheia.ai.core.MessageRole
import works.resolve.aletheia.ai.core.Model
import works.resolve.aletheia.ai.core.StopReason
import works.resolve.aletheia.ai.core.Usage
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Rough context token estimation, ported from pi's
 * packages/ai/src/utils/estimate.ts. Uses ~4 chars per token and a fixed
 * 4800-char estimate per image. No tokenizer dependency, matching pi.
 */

private const val CHARS_PER_TOKEN = 4
private const val ESTIMATED_IMAGE_CHARS = 4800

/** Tokens always reserved for safety when clamping max output tokens. */
private const val CONTEXT_SAFETY_TOKENS = 4096
private const val MIN_MAX_TOKENS = 1

data class ContextUsageEstimate(
    /** Estimated total context tokens. */
    val tokens: Int,
    /** Tokens reported by the most recent applicable assistant usage block. */
    val usageTokens: Int,
    /** Estimated tokens after the most recent applicable assistant usage block. */
    val trailingTokens: Int,
    /** Index of the applicable message that provided usage, or null when none exists. */
    val lastUsageIndex: Int?,
)

/** Total context tokens for a usage block; prefers totalTokens, falls back to component sums. */
fun calculateContextTokens(usage: Usage): Int =
    usage.totalTokens.takeIf { it > 0 } ?: usage.input + usage.output + usage.cacheRead + usage.cacheWrite

fun estimateTextTokens(text: String): Int = ceil(text.length / CHARS_PER_TOKEN.toDouble()).toInt()

private fun estimateTextAndImageChars(content: List<Content>): Int =
    content.sumOf { block ->
        when (block.type) {
            ContentType.TEXT -> (block as works.resolve.aletheia.ai.core.TextContent).text.length
            ContentType.IMAGE -> ESTIMATED_IMAGE_CHARS
            else -> 0
        }
    }

fun estimateMessageTokens(message: Message): Int {
    return when (message.role) {
        MessageRole.USER, MessageRole.TOOL_RESULT ->
            ceil(estimateTextAndImageChars(message.contentList()) / CHARS_PER_TOKEN.toDouble()).toInt()

        MessageRole.ASSISTANT -> {
            var chars = 0
            for (block in message.contentList()) {
                when (block) {
                    is works.resolve.aletheia.ai.core.TextContent -> chars += block.text.length
                    is works.resolve.aletheia.ai.core.ThinkingContent -> chars += block.thinking.length
                    is works.resolve.aletheia.ai.core.ToolCall ->
                        // arguments is the raw JSON string; use it directly.
                        chars += block.name.length + block.arguments.length
                    else -> {}
                }
            }
            ceil(chars / CHARS_PER_TOKEN.toDouble()).toInt()
        }
    }
}

private fun Message.contentList(): List<Content> = when (this) {
    is works.resolve.aletheia.ai.core.UserMessage -> content
    is works.resolve.aletheia.ai.core.AssistantMessage -> content
    is works.resolve.aletheia.ai.core.ToolResultMessage -> content
}

/**
 * Finds the latest assistant message whose usage describes the current
 * prefix: its timestamp must not be older than any preceding message (a
 * newer inserted prefix message, e.g. a compaction summary, invalidates
 * earlier usage), and aborted/error responses are skipped.
 */
private fun getLastAssistantUsageInfo(messages: List<Message>): Pair<Usage, Int>? {
    var latestPrefixTimestamp = Long.MIN_VALUE
    var usageInfo: Pair<Usage, Int>? = null

    for (i in messages.indices) {
        val message = messages[i]
        if (message.role == MessageRole.ASSISTANT) {
            val assistant = message as works.resolve.aletheia.ai.core.AssistantMessage
            val usageAppliesToPrefix = assistant.timestamp >= latestPrefixTimestamp
            if (
                usageAppliesToPrefix &&
                assistant.stopReason != StopReason.ABORTED &&
                assistant.stopReason != StopReason.ERROR &&
                calculateContextTokens(assistant.usage) > 0
            ) {
                usageInfo = assistant.usage to i
            }
        }
        latestPrefixTimestamp = max(latestPrefixTimestamp, message.timestamp)
    }

    return usageInfo
}

private fun estimateMessages(messages: List<Message>): ContextUsageEstimate {
    val usageInfo = getLastAssistantUsageInfo(messages)
    if (usageInfo != null) {
        val (usage, index) = usageInfo
        val usageTokens = calculateContextTokens(usage)
        // When usage applies, it already covers the system prompt and tools.
        val trailingTokens = messages.drop(index + 1).sumOf { estimateMessageTokens(it) }
        return ContextUsageEstimate(usageTokens + trailingTokens, usageTokens, trailingTokens, index)
    }

    val tokens = messages.sumOf { estimateMessageTokens(it) }
    return ContextUsageEstimate(tokens, 0, tokens, null)
}

private fun estimateToolsTokens(context: Context): Int =
    if (context.tools.isEmpty()) 0 else estimateTextTokens(context.tools.toString())

fun estimateContextTokens(context: Context): ContextUsageEstimate {
    val estimate = estimateMessages(context.messages)

    // When usage applies, the system prompt and tools are part of the
    // reported prefix. pi also re-adds tools introduced after that point via
    // ToolResultMessage.addedToolNames, which our ToolResultMessage lacks;
    // trailing tool results' content is still estimated.
    if (estimate.lastUsageIndex != null) return estimate

    val prefixTokens = (context.systemPrompt?.let { estimateTextTokens(it) } ?: 0) + estimateToolsTokens(context)
    return ContextUsageEstimate(
        tokens = estimate.tokens + prefixTokens,
        usageTokens = estimate.usageTokens,
        trailingTokens = estimate.trailingTokens + prefixTokens,
        lastUsageIndex = estimate.lastUsageIndex,
    )
}

/**
 * Clamps a requested max output token limit to the room left in the model's
 * context window, keeping [CONTEXT_SAFETY_TOKENS] tokens of headroom and at
 * least [MIN_MAX_TOKENS] for the answer. A non-positive context window means
 * no clamping. Mirrors pi's clampMaxTokensToContext.
 */
fun clampMaxTokensToContext(model: Model, context: Context, maxTokens: Int): Int {
    if (model.contextWindow <= 0) return max(MIN_MAX_TOKENS, maxTokens)
    val available = model.contextWindow - estimateContextTokens(context).tokens - CONTEXT_SAFETY_TOKENS
    return min(maxTokens, max(MIN_MAX_TOKENS, available))
}
