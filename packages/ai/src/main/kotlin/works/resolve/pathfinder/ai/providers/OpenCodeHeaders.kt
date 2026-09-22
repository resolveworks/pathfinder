package works.resolve.pathfinder.ai.providers

import kotlinx.coroutines.flow.Flow
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.TranscriptContext

internal const val OPENCODE_SESSION_HEADER = "x-opencode-session"

private fun hasHeaderKey(headers: Map<String, String?>, name: String): Boolean =
    headers.keys.any { it.lowercase() == name }

/**
 * Adds OpenCode's required per-conversation routing header before API dispatch:
 * `x-opencode-session` from the session id, unless the caller already set that
 * header (case-insensitive presence — a key explicitly mapped to null still
 * suppresses adding it, unlike the non-blank [works.resolve.pathfinder.ai.hasHeader]
 * semantics the API layer uses).
 */
internal class OpenCodeSessionHeaderChatApi(private val delegate: ChatApi) : ChatApi {
    override fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions
    ): Flow<AssistantMessageEvent> {
        val sessionId = options.sessionId
        if (sessionId.isNullOrEmpty() || hasHeaderKey(options.headers, OPENCODE_SESSION_HEADER)) {
            return delegate.streamSimple(model, context, options)
        }
        return delegate.streamSimple(
            model,
            context,
            options.copy(headers = options.headers + (OPENCODE_SESSION_HEADER to sessionId))
        )
    }
}
