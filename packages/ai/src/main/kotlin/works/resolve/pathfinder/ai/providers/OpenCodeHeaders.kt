package works.resolve.pathfinder.ai.providers

import kotlinx.coroutines.flow.Flow
import works.resolve.pathfinder.ai.AssistantMessageEvent
import works.resolve.pathfinder.ai.ChatApi
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.TranscriptContext
import works.resolve.pathfinder.ai.hasHeader

internal const val OPENCODE_SESSION_HEADER = "x-opencode-session"

/**
 * Adds OpenCode's required per-conversation routing header before API dispatch:
 * `x-opencode-session` from the session id, unless the caller already set that
 * header (case-insensitive).
 */
internal class OpenCodeSessionHeaderChatApi(private val delegate: ChatApi) : ChatApi {
    override fun streamSimple(
        model: Model,
        context: TranscriptContext,
        options: SimpleStreamOptions
    ): Flow<AssistantMessageEvent> {
        val sessionId = options.sessionId
        if (sessionId.isNullOrEmpty() || hasHeader(options.headers, OPENCODE_SESSION_HEADER)) {
            return delegate.streamSimple(model, context, options)
        }
        return delegate.streamSimple(
            model,
            context,
            options.copy(headers = options.headers + (OPENCODE_SESSION_HEADER to sessionId))
        )
    }
}
