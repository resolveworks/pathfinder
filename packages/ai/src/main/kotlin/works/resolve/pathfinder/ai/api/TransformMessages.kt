package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Content
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.MessageRole
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.TextContent
import works.resolve.pathfinder.ai.ThinkingContent
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage

/**
 * Normalizes a replayed history into a shape the target provider accepts:
 * non-vision image downgrade, cross-model thinking handling, tool call ID
 * normalization, synthetic error results for orphaned tool calls.
 *
 * Cross-model thinking degrades to bare text without `<thinking>` tagging;
 * tagging belongs to callers that want it (mirrors pi's handoff behavior).
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
    if (model.input.contains(works.resolve.pathfinder.ai.InputModality.IMAGE)) {
        messages
    } else {
        messages.map { msg ->
            when (msg.role) {
                MessageRole.USER -> {
                    val user = msg as works.resolve.pathfinder.ai.UserMessage
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

/**
 * The shared replay pre-pass: every provider adapter runs this with its own
 * [normalizeToolCallId] before building its payload.
 *
 * Divergence from pi's callback shape `(id, model, source)`: the model is
 * already a parameter here, so the callback receives the source assistant
 * message instead.
 *
 * [ThinkingContent.thinkingSignature] truthiness mirrors pi: an empty-string
 * signature counts as absent and falls through to the blank-drop path.
 *
 * Divergence from pi (lax-message-content.test.ts): upstream normalizes
 * null/missing message `content` from untyped callers to an empty array
 * before transforming (pi issues #6259, #6276). Kotlin's non-null
 * `Message.content: List<Content>` makes that laxness unrepresentable
 * in-domain, and the session codec rejects missing content instead of
 * repairing it, so no equivalent normalization exists here.
 */
internal fun transformMessages(
    messages: List<Message>,
    model: Model,
    normalizeToolCallId: ((id: String, source: AssistantMessage) -> String)? = null,
): List<Message> {
    val toolCallIdMap = mutableMapOf<String, String>()
    val imageAwareMessages = downgradeUnsupportedImages(messages, model)

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
                        // Cross-model text is recreated without textSignature: the
                        // signature is opaque replay data only meaningful for the same
                        // provider/model (upstream recreates `{type:"text", text}`).
                        is TextContent ->
                            if (isSameModel) {
                                listOf(block)
                            } else {
                                listOf(TextContent(block.text))
                            }
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
                if (assistantMsg.stopReason == works.resolve.pathfinder.ai.StopReason.ERROR ||
                    assistantMsg.stopReason == works.resolve.pathfinder.ai.StopReason.ABORTED
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
