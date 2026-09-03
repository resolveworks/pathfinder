package works.resolve.pathfinder.agent

import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.settings.ModelSettings

/**
 * Builds the [AgentSession] for a session. Implementations validate the
 * configuration eagerly, throwing [IllegalArgumentException] on an
 * unsupported provider or model, so a bad configuration fails before any
 * agent state exists.
 */
fun interface AgentFactory {
    fun create(settings: ModelSettings, sessionId: String, conversation: Conversation): AgentSession
}
