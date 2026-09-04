package works.resolve.pathfinder.ai.utils

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.ContentType
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.MessageRole
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.StopReason
import works.resolve.pathfinder.ai.Usage

/**
 * Rough context token estimation: ~4 chars per token and a fixed 4800-char
 * estimate per image; no tokenizer dependency.
 */

private const val CHARS_PER_TOKEN = 4
private const val ESTIMATED_IMAGE_CHARS = 4800

/** Tokens always reserved for safety when clamping max output tokens. */
private const val CONTEXT_SAFETY_TOKENS = 4096
private const val MIN_MAX_TOKENS = 1

data class ContextUsageEstimate(
    val tokens: Int,
    val usageTokens: Int,
    val trailingTokens: Int,
    val lastUsageIndex: Int?
)

fun calculateContextTokens(usage: Usage): Int = usage.totalTokens.takeIf { it > 0 }
    ?: usage.input + usage.output + usage.cacheRead + usage.cacheWrite

fun estimateTextTokens(text: String): Int = ceil(text.length / CHARS_PER_TOKEN.toDouble()).toInt()

private fun estimateTextAndImageChars(content: List<Content>): Int = content.sumOf { block ->
    when (block.type) {
        ContentType.TEXT -> (block as works.resolve.pathfinder.ai.TextContent).text.length
        ContentType.IMAGE -> ESTIMATED_IMAGE_CHARS
        else -> 0
    }
}

fun estimateMessageTokens(message: Message): Int = when (message.role) {
    MessageRole.USER, MessageRole.TOOL_RESULT ->
        ceil(
            estimateTextAndImageChars(message.contentList()) / CHARS_PER_TOKEN.toDouble()
        ).toInt()

    MessageRole.ASSISTANT -> {
        var chars = 0
        for (block in message.contentList()) {
            when (block) {
                is works.resolve.pathfinder.ai.TextContent -> chars += block.text.length

                is works.resolve.pathfinder.ai.ThinkingContent -> chars += block.thinking.length

                is works.resolve.pathfinder.ai.ToolCall ->
                    chars += block.name.length + block.arguments.length

                else -> {}
            }
        }
        ceil(chars / CHARS_PER_TOKEN.toDouble()).toInt()
    }
}

private fun Message.contentList(): List<Content> = when (this) {
    is works.resolve.pathfinder.ai.UserMessage -> content
    is works.resolve.pathfinder.ai.AssistantMessage -> content
    is works.resolve.pathfinder.ai.ToolResultMessage -> content
}

/**
 * Finds the latest assistant message whose usage describes the current
 * prefix: its timestamp must not be older than any preceding message (a
 * newer inserted prefix message, e.g. a compaction summary, invalidates
 * earlier usage).
 */
private fun getLastAssistantUsageInfo(messages: List<Message>): Pair<Usage, Int>? {
    var latestPrefixTimestamp = Long.MIN_VALUE
    var usageInfo: Pair<Usage, Int>? = null

    for (i in messages.indices) {
        val message = messages[i]
        if (message.role == MessageRole.ASSISTANT) {
            val assistant = message as works.resolve.pathfinder.ai.AssistantMessage
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
        return ContextUsageEstimate(
            usageTokens + trailingTokens,
            usageTokens,
            trailingTokens,
            index
        )
    }

    val tokens = messages.sumOf { estimateMessageTokens(it) }
    return ContextUsageEstimate(tokens, 0, tokens, null)
}

private fun estimateToolsTokens(context: Context): Int = estimateToolsTokens(context.tools)

private fun estimateToolsTokens(tools: List<works.resolve.pathfinder.ai.Tool>): Int =
    if (tools.isEmpty()) 0 else estimateTextTokens(tools.toString())

fun estimateContextTokens(context: Context): ContextUsageEstimate {
    val estimate = estimateMessages(context.messages)

    if (estimate.lastUsageIndex != null) {
        // Tools introduced after the usage point aren't covered by its
        // snapshot; re-add them via trailing ToolResultMessage.addedToolNames.
        val addedNames = context.messages
            .drop(estimate.lastUsageIndex + 1)
            .filterIsInstance<works.resolve.pathfinder.ai.ToolResultMessage>()
            .flatMap { it.addedToolNames }
            .toSet()
        val addedToolTokens = estimateToolsTokens(context.tools.filter { it.name in addedNames })
        return ContextUsageEstimate(
            tokens = estimate.tokens + addedToolTokens,
            usageTokens = estimate.usageTokens,
            trailingTokens = estimate.trailingTokens + addedToolTokens,
            lastUsageIndex = estimate.lastUsageIndex
        )
    }

    val prefixTokens =
        (context.systemPrompt?.let { estimateTextTokens(it) } ?: 0) + estimateToolsTokens(context)
    return ContextUsageEstimate(
        tokens = estimate.tokens + prefixTokens,
        usageTokens = estimate.usageTokens,
        trailingTokens = estimate.trailingTokens + prefixTokens,
        lastUsageIndex = estimate.lastUsageIndex
    )
}

/**
 * Clamps a requested max output token limit to the room left in the model's
 * context window, keeping [CONTEXT_SAFETY_TOKENS] tokens of headroom and at
 * least [MIN_MAX_TOKENS] for the answer.
 */
fun clampMaxTokensToContext(model: Model, context: Context, maxTokens: Int): Int {
    if (model.contextWindow <= 0) return max(MIN_MAX_TOKENS, maxTokens)
    val available =
        model.contextWindow - estimateContextTokens(context).tokens - CONTEXT_SAFETY_TOKENS
    return min(maxTokens, max(MIN_MAX_TOKENS, available))
}
