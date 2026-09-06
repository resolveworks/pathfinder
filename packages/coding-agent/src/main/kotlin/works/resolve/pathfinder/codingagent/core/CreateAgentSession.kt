// The file name mirrors pi's sdk.ts factory, not the result class it declares.
@file:Suppress("ktlint:standard:filename")

package works.resolve.pathfinder.codingagent.core

import works.resolve.pathfinder.agent.Agent
import works.resolve.pathfinder.agent.AgentTool
import works.resolve.pathfinder.agent.StreamFn
import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.Models
import works.resolve.pathfinder.ai.SimpleStreamOptions
import works.resolve.pathfinder.ai.clampThinkingLevel
import works.resolve.pathfinder.ai.modelThinkingLevelFromWire

/** Result of [createAgentSession]. */
data class CreateAgentSessionResult(
    val session: AgentSession,
    /** Warning when the session's saved model could not be restored. */
    val modelFallbackMessage: String?
)

/**
 * pi's createAgentSession (core/sdk.ts), ported as the single owner of
 * startup ordering: branch fold, model restore/fallback via
 * [findInitialModel], thinking-level resolution and clamping, agent
 * construction, transcript restoration, and configuration seeding for new
 * sessions — [AgentSession] receives an already-restored agent.
 *
 * Divergences: pi wires the stream function and resource loading inside
 * the factory; here the app owns transports, so the [StreamFn] (plus agent
 * tools and stream options) is passed in. And pi tolerates a session with
 * no resolvable model (Agent's model is optional); Pathfinder's [Agent]
 * requires one, so an empty resolution throws instead. The resource
 * loader, extensions, and convertToLlm wrapper are out of scope (AGENTS.md).
 *
 * Scoped models are resolved from the settings' enabledModels patterns
 * against the authenticated snapshot, so the session's scope-append guard
 * operates on the resolved scope as in pi.
 */
suspend fun createAgentSession(
    manager: SessionManager,
    settingsManager: SettingsManager,
    models: Models,
    streamFn: StreamFn,
    tools: List<AgentTool> = emptyList(),
    streamOptions: SimpleStreamOptions = SimpleStreamOptions()
): CreateAgentSessionResult {
    val existingSession = manager.buildSessionContext()
    val hasExistingSession = existingSession.messages.isNotEmpty()
    val hasThinkingEntry = manager.getBranch().any { it is ThinkingLevelEntry }

    val scopePatterns = settingsManager.getEnabledModels()
    val scopedModels = if (scopePatterns.isNullOrEmpty()) {
        emptyList()
    } else {
        resolveModelScope(scopePatterns, models.getAvailableSnapshot()).scopedModels
    }

    var model: Model? = null
    var modelFallbackMessage: String? = null

    if (hasExistingSession && existingSession.model != null) {
        val restored =
            models.getModel(existingSession.model.provider, existingSession.model.modelId)
        if (restored != null && models.checkAuth(restored.provider)) {
            model = restored
        }
        if (model == null) {
            modelFallbackMessage =
                "Could not restore model ${existingSession.model.provider}/${existingSession.model.modelId}"
        }
    }

    if (model == null) {
        val result = findInitialModel(
            scopedModels = scopedModels,
            isContinuing = hasExistingSession,
            defaultProvider = settingsManager.getDefaultProvider(),
            defaultModelId = settingsManager.getDefaultModel(),
            defaultThinkingLevel = settingsManager.getDefaultThinkingLevel(),
            modelThinkingLevels = settingsManager.getAllModelThinkingLevels(),
            models = models
        )
        model = result.model
        // findInitialModel's thinking level is ignored here, as in pi's
        // sdk.ts: the resolution below re-derives it from the session fold
        // and settings.
        if (model == null) {
            throw IllegalStateException(formatNoModelsAvailableMessage())
        }
        if (modelFallbackMessage != null) {
            modelFallbackMessage += ". Using ${model.provider}/${model.id}"
        }
    }

    var thinkingLevel: ModelThinkingLevel? = null
    if (hasExistingSession) {
        // Divergence: pi casts the folded string; an invalid fold falls
        // back to the default level rather than failing.
        val folded =
            if (hasThinkingEntry) {
                modelThinkingLevelFromWire(
                    existingSession.thinkingLevel
                )
            } else {
                null
            }
        thinkingLevel =
            folded ?: settingsManager.getDefaultThinkingLevel() ?: DEFAULT_THINKING_LEVEL
    }
    if (thinkingLevel == null) {
        thinkingLevel = settingsManager.getModelThinkingLevel(model.provider, model.id)
    }
    if (thinkingLevel == null) {
        thinkingLevel = settingsManager.getDefaultThinkingLevel() ?: DEFAULT_THINKING_LEVEL
    }
    thinkingLevel = clampThinkingLevel(model, thinkingLevel)

    val agent =
        Agent(model = model, streamOptions = streamOptions, tools = tools, streamFn = streamFn)
    // pi passes the resolved level in the Agent's initialState; the Agent
    // constructor has no level parameter, so it is applied right after.
    agent.setThinkingLevel(thinkingLevel)

    if (hasExistingSession) {
        agent.replaceTranscript(existingSession.messages)
        if (!hasThinkingEntry) {
            manager.appendThinkingLevelChange(thinkingLevel.wire)
        }
    } else {
        manager.appendModelChange(model.provider, model.id)
        manager.appendThinkingLevelChange(thinkingLevel.wire)
    }

    val session = AgentSession(
        agent = agent,
        manager = manager,
        settingsManager = settingsManager,
        scopedModels = scopedModels,
        tools = tools,
        models = models
    )
    return CreateAgentSessionResult(session = session, modelFallbackMessage = modelFallbackMessage)
}
