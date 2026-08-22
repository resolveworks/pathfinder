package com.aletheia.agent

import java.io.Closeable
import kotlinx.coroutines.flow.Flow

data class AgentConfig(
    val providerId: String,
    val modelId: String,
)

/** Application-facing contract for the JavaScript agent runtime. */
interface AgentClient : Closeable {
    val events: Flow<AgentEvent>

    /** Loads the JavaScript bundle and initializes its model. May only be called once. */
    suspend fun start(config: AgentConfig)

    /** Resolves after pi has completed responding to the prompt. */
    suspend fun prompt(text: String)

    suspend fun abort()
}
