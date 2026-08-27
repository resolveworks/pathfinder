package works.resolve.distill.ai.api

import works.resolve.distill.ai.core.AssistantMessage
import works.resolve.distill.ai.core.Context
import works.resolve.distill.ai.core.ImageContent
import works.resolve.distill.ai.core.Message
import works.resolve.distill.ai.core.MessageRole
import works.resolve.distill.ai.core.ToolResultMessage
import works.resolve.distill.ai.core.UserMessage
import works.resolve.distill.ai.core.Model

/**
 * GitHub Copilot dynamic request headers, ported from pi's
 * packages/ai/src/api/github-copilot-headers.ts (inferCopilotInitiator,
 * hasCopilotVisionInput, buildCopilotDynamicHeaders).
 *
 * Call-site behavior is ported from the three wire protocols that activate
 * them — pi's openai-completions.ts createClient, openai-responses.ts
 * createClient, and anthropic-messages.ts createClient — where they apply
 * only for provider "github-copilot", after the model's static headers and
 * before the request options headers (so explicit request headers win).
 */

/** Pi's "user" | "agent" initiator discriminator for X-Initiator. */
enum class CopilotInitiator(val headerValue: String) {
    USER("user"),
    AGENT("agent"),
}

/**
 * pi's inferCopilotInitiator: Copilot expects X-Initiator to indicate whether
 * the request is user-initiated or agent-initiated (e.g. follow-up after
 * assistant/tool messages). Any last message whose role is not "user" marks
 * the request agent-initiated.
 */
fun inferCopilotInitiator(messages: List<Message>): CopilotInitiator {
    val last = messages.lastOrNull()
    return if (last != null && last.role != MessageRole.USER) {
        CopilotInitiator.AGENT
    } else {
        CopilotInitiator.USER
    }
}

/**
 * pi's hasCopilotVisionInput: Copilot requires Copilot-Vision-Request when
 * sending images. Only user messages and tool results count; images in other
 * roles do not.
 */
fun hasCopilotVisionInput(messages: List<Message>): Boolean =
    messages.any { message ->
        when (message) {
            is UserMessage -> message.content.any { it is ImageContent }
            is ToolResultMessage -> message.content.any { it is ImageContent }
            is AssistantMessage -> false
        }
    }

/**
 * pi's buildCopilotDynamicHeaders: always X-Initiator and Openai-Intent, plus
 * Copilot-Vision-Request: "true" when [hasImages]. Header-name casing matches
 * pi exactly.
 */
fun buildCopilotDynamicHeaders(
    messages: List<Message>,
    hasImages: Boolean,
): Map<String, String> = buildMap {
    put("X-Initiator", inferCopilotInitiator(messages).headerValue)
    put("Openai-Intent", "conversation-edits")
    if (hasImages) {
        put("Copilot-Vision-Request", "true")
    }
}

/**
 * The adapters' shared call-site behavior: dynamic Copilot headers only for
 * provider "github-copilot" (pi's per-protocol activation predicate), computed
 * from the request context's messages; other providers get no extra headers.
 */
fun copilotDynamicHeadersFor(model: Model, context: Context): Map<String, String> =
    if (model.provider == "github-copilot") {
        buildCopilotDynamicHeaders(
            messages = context.messages,
            hasImages = hasCopilotVisionInput(context.messages),
        )
    } else {
        emptyMap()
    }
