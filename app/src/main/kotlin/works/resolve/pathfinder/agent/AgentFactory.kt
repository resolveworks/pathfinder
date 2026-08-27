package works.resolve.pathfinder.agent

import works.resolve.pathfinder.data.sessions.Conversation
import works.resolve.pathfinder.data.settings.ModelSettings

/**
 * Builds the [AgentSession] for a session from the persisted configuration.
 * Implementations construct the provider/models stack, resolve the API key
 * from the credential store, adopt the session's conversation tree, and
 * validate the configuration by throwing [IllegalArgumentException]; tests
 * script a fake session with a fake stream function.
 */
fun interface AgentFactory {
    fun create(settings: ModelSettings, sessionId: String, conversation: Conversation): AgentSession
}
