// The file name mirrors pi's sdk.ts factory, not the result class it declares.
@file:Suppress("ktlint:standard:filename")

package works.resolve.pathfinder.codingagent.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
 * against the authenticated snapshot, and at startup the factory folds in
 * pi's main.ts buildSessionOptions scope precedence: when not continuing,
 * the saved default wins if it matches a scoped model, else the first
 * scoped model is used, and the chosen entry's pattern thinking level is
 * the initial thinking level.
 */
suspend fun createAgentSession(
    manager: SessionManager,
    settingsManager: SettingsManager,
    models: Models,
    streamFn: StreamFn,
    tools: List<AgentTool> = emptyList(),
    /** Working directory threaded to the session's system-prompt cwd line. */
    cwd: String = "",
    streamOptions: SimpleStreamOptions = SimpleStreamOptions(),
    /** Dispatcher for the session's prompt loop; see [AgentSession]. */
    loopDispatcher: CoroutineDispatcher = Dispatchers.Default
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
    var scopedThinkingLevel: ModelThinkingLevel? = null

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

    if (model == null && !hasExistingSession && scopedModels.isNotEmpty()) {
        // pi's main.ts buildSessionOptions prefers the saved default when it
        // matches a scoped model, else falls back to the first scoped model;
        // the chosen entry's explicit pattern thinking level wins at startup.
        val saved = settingsManager.getDefaultProvider()?.let { provider ->
            settingsManager.getDefaultModel()?.let { models.getModel(provider, it) }
        }
        val chosen =
            scopedModels.firstOrNull { saved != null && Models.modelsAreEqual(it.model, saved) }
                ?: scopedModels.first()
        model = chosen.model
        scopedThinkingLevel = chosen.thinkingLevel
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

    var thinkingLevel: ModelThinkingLevel? = scopedThinkingLevel
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
        cwd = cwd,
        models = models,
        loopDispatcher = loopDispatcher
    )
    return CreateAgentSessionResult(session = session, modelFallbackMessage = modelFallbackMessage)
}
