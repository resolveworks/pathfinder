package works.resolve.pathfinder.ui.chat

import works.resolve.pathfinder.ai.Model
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.api.ChatApiRegistry
import works.resolve.pathfinder.ai.clampThinkingLevel
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.codingagent.core.session.Conversation
import works.resolve.pathfinder.codingagent.core.session.SessionManager
import works.resolve.pathfinder.codingagent.core.session.ThinkingLevelEntry
import works.resolve.pathfinder.data.settings.ModelSettings

private val DEFAULT_THINKING_LEVEL = ModelThinkingLevel.MEDIUM

/**
 * pi's sdk.ts session-init: resolves the startup model and seeds the
 * session's configuration entries through the manager. [seedSessionConfiguration]
 * resolves the model ([initialModelSettings] for a fresh session — the
 * active branch's folded model_change when it carries messages, via
 * [settingsSeededFromFold]); a fresh session additionally gets a
 * model_change entry recording the resolved model; a session without a
 * thinking_level_change entry on its active path gets the stored default
 * level, else "medium", clamped to the effective model — so both restore
 * on resume. Because any opened session contains an assistant message,
 * the fresh-session branch can only run for created sessions; the seeds
 * buffer harmlessly there until the first assistant commit writes the
 * file.
 */
internal suspend fun seedSessionConfiguration(
    manager: SessionManager,
    settings: ModelSettings,
    modelOptions: List<ModelOption>,
    modelResolver: (providerId: String, modelId: String) -> Model,
    catalog: ProviderCatalog
): ModelSettings {
    val conversation = manager.conversation
    val hasExistingSession = conversation.activeMessages().isNotEmpty()
    val base = initialModelSettings(settings, modelOptions, isContinuing = hasExistingSession)
    val seeded = settingsSeededFromFold(base, conversation, catalog)
    if (!hasExistingSession && seeded.providerId.isNotBlank() && seeded.modelId.isNotBlank()) {
        manager.appendModelChange(seeded.providerId, seeded.modelId)
    }
    if (conversation.activeEntries().none { it is ThinkingLevelEntry } &&
        seeded.providerId.isNotBlank() && seeded.modelId.isNotBlank()
    ) {
        // Clamped before storing. An unresolvable model fails agent
        // creation anyway, so the tree stays unseeded rather than
        // gaining a second error path.
        val seededLevel = try {
            val model = modelResolver(seeded.providerId, seeded.modelId)
            clampThinkingLevel(
                model,
                settings.defaultThinkingLevel ?: DEFAULT_THINKING_LEVEL
            )
        } catch (e: Exception) {
            null
        }
        if (seededLevel != null) {
            manager.appendThinkingLevelChange(seededLevel.wire)
        }
    }
    return seeded
}

/**
 * pi's findInitialModel order (model-resolver.ts), minus the CLI step: a
 * fresh session takes the first available scoped model, else the saved
 * default while the credential-filtered options still admit it, else the
 * first available model; a continuing session skips the scope step (its
 * branch fold, when present, wins in [settingsSeededFromFold]).
 * Availability is [ChatUiState.modelOptions].
 */
internal fun initialModelSettings(
    settings: ModelSettings,
    modelOptions: List<ModelOption>,
    isContinuing: Boolean
): ModelSettings {
    if (modelOptions.isEmpty()) return settings
    if (!isContinuing) {
        val scoped = settings.enabledModels.orEmpty().firstNotNullOfOrNull { ref ->
            modelOptions.firstOrNull { option ->
                "${option.providerId}/${option.modelId}".equals(ref, ignoreCase = true)
            }
        }
        if (scoped != null) {
            return settings.copy(providerId = scoped.providerId, modelId = scoped.modelId)
        }
    }
    val defaultAvailable = modelOptions.any {
        it.providerId == settings.providerId && it.modelId == settings.modelId
    }
    if (defaultAvailable) return settings
    val first = modelOptions.first()
    return settings.copy(providerId = first.providerId, modelId = first.modelId)
}

/**
 * Seeds the provider/model from the conversation's configuration fold: a
 * branch that recorded a different model runs on that model, overriding
 * the global defaults. Divergence from pi: only pairs the generated
 * catalog supports are applied; anything else falls back to [settings].
 */
internal fun settingsSeededFromFold(
    settings: ModelSettings,
    conversation: Conversation,
    catalog: ProviderCatalog
): ModelSettings {
    val model = conversation.effectiveConfiguration().model ?: return settings
    if (model.provider == settings.providerId &&
        model.modelId == settings.modelId
    ) {
        return settings
    }
    val catalogModel = catalog.getProvider(model.provider)?.model(model.modelId)
        ?: return settings
    return if (ChatApiRegistry.isSupported(catalogModel.api)) {
        settings.copy(providerId = model.provider, modelId = model.modelId)
    } else {
        settings
    }
}
