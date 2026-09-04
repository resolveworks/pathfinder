package works.resolve.pathfinder.ai.api

import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Context
import works.resolve.pathfinder.ai.ImageContent
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.MessageRole
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.UserMessage

enum class CopilotInitiator(val wire: String) {
    USER("user"),
    AGENT("agent")
}

/**
 * Copilot expects X-Initiator to indicate whether the request is
 * user-initiated or agent-initiated (e.g. follow-up after assistant/tool
 * messages).
 */
fun inferCopilotInitiator(messages: List<Message>): CopilotInitiator {
    val last = messages.lastOrNull()
    return if (last != null && last.role != MessageRole.USER) {
        CopilotInitiator.AGENT
    } else {
        CopilotInitiator.USER
    }
}

/** Copilot requires Copilot-Vision-Request when the request carries images. */
fun hasCopilotVisionInput(messages: List<Message>): Boolean = messages.any { message ->
    when (message) {
        is UserMessage -> message.content.any { it is ImageContent }
        is ToolResultMessage -> message.content.any { it is ImageContent }
        is AssistantMessage -> false
    }
}

/**
 * Always X-Initiator and Openai-Intent, plus Copilot-Vision-Request: "true"
 * when [hasImages]. The unconventional casing (Openai-Intent) is intentional.
 */
fun buildCopilotDynamicHeaders(messages: List<Message>, hasImages: Boolean): Map<String, String> =
    buildMap {
        put("X-Initiator", inferCopilotInitiator(messages).wire)
        put("Openai-Intent", "conversation-edits")
        if (hasImages) {
            put("Copilot-Vision-Request", "true")
        }
    }

/** Applies only for provider "github-copilot"; other providers get no extra headers. */
fun copilotDynamicHeadersFor(model: Model, context: Context): Map<String, String> =
    if (model.provider == "github-copilot") {
        buildCopilotDynamicHeaders(
            messages = context.messages,
            hasImages = hasCopilotVisionInput(context.messages)
        )
    } else {
        emptyMap()
    }
