package works.resolve.aletheia.agent

import works.resolve.aletheia.ai.core.Message
import works.resolve.aletheia.data.settings.ModelSettings

/**
 * Builds the [Agent] for a session from the persisted configuration.
 * Implementations construct the provider/models stack, resolve the API key
 * from the credential store, and validate the configuration by throwing
 * [IllegalArgumentException]; tests script a fake [Agent] with a fake
 * stream function.
 */
fun interface AgentFactory {
    fun create(settings: ModelSettings, sessionId: String, initialTranscript: List<Message>): Agent
}
