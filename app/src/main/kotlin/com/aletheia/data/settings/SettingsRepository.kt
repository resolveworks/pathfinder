package com.aletheia.data.settings

import kotlinx.coroutines.flow.Flow

/** Persistence boundary for model settings. */
interface SettingsRepository {

    val settings: Flow<ModelSettings>

    suspend fun setProviderId(providerId: String)

    suspend fun setModelId(modelId: String)

    suspend fun setBaseUrl(baseUrl: String?)

    suspend fun setActiveSessionId(sessionId: String?)
}
