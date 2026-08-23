package com.aletheia.agent

import com.aletheia.ai.core.Message
import com.aletheia.data.settings.ModelSettings

/**
 * Builds the [Agent] for a session from the persisted configuration.
 * Implementations construct the provider/models stack, resolve the API key
 * from the credential store, and normalize/validate the base URL; tests
 * script a fake [Agent] with a fake stream function.
 *
 * [settings.baseUrl] is already trimmed by the ViewModel; implementations
 * validate it (e.g. reject blank) by throwing [IllegalArgumentException].
 */
fun interface AgentFactory {
    fun create(settings: ModelSettings, sessionId: String, initialTranscript: List<Message>): Agent
}
