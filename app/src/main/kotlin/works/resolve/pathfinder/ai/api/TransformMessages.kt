package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.core.AssistantMessage
import works.resolve.pathfinder.ai.core.Content
import works.resolve.pathfinder.ai.core.ImageContent
import works.resolve.pathfinder.ai.core.Message
import works.resolve.pathfinder.ai.core.MessageRole
import works.resolve.pathfinder.ai.core.Model
import works.resolve.pathfinder.ai.core.TextContent
import works.resolve.pathfinder.ai.core.ThinkingContent
import works.resolve.pathfinder.ai.core.ToolCall
import works.resolve.pathfinder.ai.core.ToolResultMessage

/**
 * Message normalization for provider replay, ported from pi's
 * packages/ai/src/api/transform-messages.ts.
 *
 * - Downgrades images to placeholders for non-vision models (deduplicated).
 * - Keeps same-model thinking blocks (with signatures) for replay, drops
 *   redacted thinking cross-model, and converts other thinking to plain text
 *   (pi's cross-provider handoff: `<thinking>` tagging belongs to callers that
 *   want it; pi's anthropic adapter sends the bare text).
 * - Strips provider-specific tool thought signatures cross-provider and
 *   normalizes tool call IDs via [normalizeToolCallId] (pi's callback shape
 *   is `(id, model, source)`; the model is already a parameter here, so the
 *   callback receives the source assistant message).
 * - Skips errored/aborted assistant messages entirely.
 * - Inserts synthetic error tool results for orphaned tool calls.
 */

private const val NON_VISION_USER_IMAGE_PLACEHOLDER = "(image omitted: model does not support images)"
private const val NON_VISION_TOOL_IMAGE_PLACEHOLDER = "(tool image omitted: model does not support images)"

private fun replaceImagesWithPlaceholder(content: List<Content>, placeholder: String): List<Content> {
    val result = mutableListOf<Content>()
    var previousWasPlaceholder = false
    for (block in content) {
        if (block is ImageContent) {
            if (!previousWasPlaceholder) result.add(TextContent(placeholder))
            previousWasPlaceholder = true
            continue
        }
        result.add(block)
        previousWasPlaceholder = block is TextContent && block.text == placeholder
    }
    return result
}

private fun downgradeUnsupportedImages(messages: List<Message>, model: Model): List<Message> =
    if (model.input.contains(works.resolve.pathfinder.ai.core.InputModality.IMAGE)) {
        messages
    } else {
        messages.map { msg ->
            when (msg.role) {
                MessageRole.USER -> {
                    val user = msg as works.resolve.pathfinder.ai.core.UserMessage
                    user.copy(content = replaceImagesWithPlaceholder(user.content, NON_VISION_USER_IMAGE_PLACEHOLDER))
                }
                MessageRole.TOOL_RESULT -> {
                    val toolResult = msg as ToolResultMessage
                    toolResult.copy(
                        content = replaceImagesWithPlaceholder(toolResult.content, NON_VISION_TOOL_IMAGE_PLACEHOLDER),
                    )
                }
                else -> msg
            }
        }
    }

/** pi's transformMessages; see the file KDoc for the preserved behavior. * thinkingSignature truthiness: an empty-string signature falls through to
 * the blank-drop path, exactly like pi's `if (block.thinkingSignature)`.
 *
 * This is the single port of pi's transformMessages; every adapter
 * (Anthropic, Google, Mistral, OpenAI Completions, OpenAI Responses)
 * runs this pass with its provider-specific [normalizeToolCallId].
 */
internal fun transformMessages(
    messages: List<Message>,
    model: Model,
    normalizeToolCallId: ((id: String, source: AssistantMessage) -> String)? = null,
): List<Message> {
    val toolCallIdMap = mutableMapOf<String, String>()
    val imageAwareMessages = downgradeUnsupportedImages(messages, model)

    // First pass: image downgrade, thinking blocks, tool call ID normalization.
    val transformed = imageAwareMessages.map { msg ->
        when (msg.role) {
            MessageRole.USER -> msg
            MessageRole.TOOL_RESULT -> {
                val toolResult = msg as ToolResultMessage
                val normalizedId = toolCallIdMap[toolResult.toolCallId]
                if (normalizedId != null && normalizedId != toolResult.toolCallId) {
                    toolResult.copy(toolCallId = normalizedId)
                } else {
                    toolResult
                }
            }
            MessageRole.ASSISTANT -> {
                val assistantMsg = msg as AssistantMessage
                val isSameModel =
                    assistantMsg.provider == model.provider &&
                        assistantMsg.api == model.api &&
                        assistantMsg.model == model.id

                val transformedContent = assistantMsg.content.flatMap { block ->
                    when (block) {
                        is ThinkingContent -> {
                            // Redacted thinking is opaque encrypted content, only valid
                            // for the same model; drop it cross-model.
                            if (block.redacted) {
                                if (isSameModel) listOf(block) else emptyList()
                            } else if (isSameModel && !block.thinkingSignature.isNullOrEmpty()) {
                                listOf(block)
                            } else if (block.thinking.isBlank()) {
                                emptyList()
                            } else if (isSameModel) {
                                listOf(block)
                            } else {
                                listOf(TextContent(block.thinking))
                            }
                        }
                        is TextContent -> listOf(block)
                        is ToolCall -> {
                            var normalized = if (!isSameModel && block.thoughtSignature != null) {
                                block.copy(thoughtSignature = null)
                            } else {
                                block
                            }
                            if (!isSameModel && normalizeToolCallId != null) {
                                val normalizedId = normalizeToolCallId(block.id, assistantMsg)
                                if (normalizedId != block.id) {
                                    toolCallIdMap[block.id] = normalizedId
                                    normalized = normalized.copy(id = normalizedId)
                                }
                            }
                            listOf(normalized)
                        }
                        else -> listOf(block)
                    }
                }
                assistantMsg.copy(content = transformedContent)
            }
        }
    }

    // Second pass: skip error/aborted assistant turns and synthesize tool
    // results for orphaned tool calls.
    val result = mutableListOf<Message>()
    var pendingToolCalls = mutableListOf<ToolCall>()
    var existingToolResultIds = mutableSetOf<String>()

    fun insertSyntheticToolResults(now: Long) {
        for (tc in pendingToolCalls) {
            if (tc.id !in existingToolResultIds) {
                result.add(
                    ToolResultMessage(
                        toolCallId = tc.id,
                        toolName = tc.name,
                        content = listOf(TextContent("No result provided")),
                        isError = true,
                        timestamp = now,
                    ),
                )
            }
        }
        pendingToolCalls = mutableListOf()
        existingToolResultIds = mutableSetOf()
    }

    for (msg in transformed) {
        when (msg.role) {
            MessageRole.ASSISTANT -> {
                insertSyntheticToolResults(msg.timestamp)
                val assistantMsg = msg as AssistantMessage
                if (assistantMsg.stopReason == works.resolve.pathfinder.ai.core.StopReason.ERROR ||
                    assistantMsg.stopReason == works.resolve.pathfinder.ai.core.StopReason.ABORTED
                ) {
                    continue
                }
                val toolCalls = assistantMsg.content.filterIsInstance<ToolCall>()
                if (toolCalls.isNotEmpty()) {
                    pendingToolCalls = toolCalls.toMutableList()
                    existingToolResultIds = mutableSetOf()
                }
                result.add(msg)
            }
            MessageRole.TOOL_RESULT -> {
                existingToolResultIds.add((msg as ToolResultMessage).toolCallId)
                result.add(msg)
            }
            MessageRole.USER -> {
                insertSyntheticToolResults(msg.timestamp)
                result.add(msg)
            }
        }
    }
    insertSyntheticToolResults(transformed.lastOrNull()?.timestamp ?: 0L)

    return result
}
