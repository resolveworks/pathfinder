package works.resolve.pathfinder.runtime

import works.resolve.pathfinder.codingagent.core.CreateAgentSessionResult
import works.resolve.pathfinder.codingagent.core.SessionManager
import works.resolve.pathfinder.data.settings.ModelSettings

/**
 * Builds the [works.resolve.pathfinder.codingagent.core.AgentSession] for a
 * session via the core createAgentSession factory, which owns model
 * resolution, restoration, and seeding. [settings] seeds the per-agent
 * snapshot settings manager.
 */
fun interface AgentFactory {
    suspend fun create(
        settings: ModelSettings,
        sessionManager: SessionManager
    ): CreateAgentSessionResult
}
