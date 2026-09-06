package works.resolve.pathfinder.runtime

import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.codingagent.core.AgentSession
import works.resolve.pathfinder.codingagent.core.SessionManager
import works.resolve.pathfinder.data.settings.ModelSettings

/**
 * Builds the [AgentSession] for a session. Implementations validate the
 * configuration eagerly, throwing [IllegalArgumentException] on an
 * unsupported provider or model, so a bad configuration fails before any
 * agent state exists. [defaultThinkingLevel] supplies the app-owned
 * thinking-level default the session consults on model switches.
 */
fun interface AgentFactory {
    fun create(
        settings: ModelSettings,
        sessionManager: SessionManager,
        defaultThinkingLevel: () -> ModelThinkingLevel?
    ): AgentSession
}
