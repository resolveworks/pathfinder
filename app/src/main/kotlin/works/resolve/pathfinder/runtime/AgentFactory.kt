package works.resolve.pathfinder.runtime

import works.resolve.pathfinder.codingagent.core.CreateAgentSessionResult
import works.resolve.pathfinder.codingagent.core.SessionManager

/**
 * Builds the [works.resolve.pathfinder.codingagent.core.AgentSession] for a
 * session via the core createAgentSession factory, which owns model
 * resolution, restoration, and seeding; runtime settings come from the
 * process-wide shared settings manager wired into the factory.
 */
fun interface AgentFactory {
    suspend fun create(sessionManager: SessionManager): CreateAgentSessionResult
}
