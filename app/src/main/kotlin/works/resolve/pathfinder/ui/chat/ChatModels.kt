package works.resolve.pathfinder.ui.chat

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import works.resolve.pathfinder.ai.AssistantMessage
import works.resolve.pathfinder.ai.Message
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.ToolCall
import works.resolve.pathfinder.ai.ToolResultMessage
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.codingagent.core.session.SessionInfo

/** Which conversation surface the chat root shows: the transcript or the session tree. */
enum class ConversationView {
    Chat,
    Tree
}

/**
 * One renderable transcript row: a compaction-cut marker, or a runtime
 * message with its stable entry id as the list key. Bodies render directly
 * from the runtime message (pi's components consume runtime messages the
 * same way).
 */
sealed interface TranscriptRow {
    val id: String

    data class Compacted(override val id: String) : TranscriptRow

    data class Chat(override val id: String, val message: Message) : TranscriptRow

    /**
     * One tool execution, like pi's per-call execution component: the row
     * lives on the call and [result] joins by call id once it commits —
     * null while the call runs, so the row never leaves the transcript in
     * between.
     */
    data class Tool(
        override val id: String,
        val call: ToolCall,
        val result: ToolResultMessage? = null
    ) : TranscriptRow
}

@Serializable
data object ChatNavKey : NavKey

@Serializable
data object SettingsNavKey : NavKey

@Serializable
data object ModelsNavKey : NavKey

@Serializable
data object DefaultModelNavKey : NavKey

@Serializable
data object DefaultThinkingNavKey : NavKey

@Serializable
data object ProvidersNavKey : NavKey

@Serializable
data object SearchProvidersNavKey : NavKey

@Serializable
data class SearchProviderAuthNavKey(val providerId: String) : NavKey

@Serializable
data class ProviderAuthNavKey(val providerId: String) : NavKey

/** The API-key credential form, pushed on top of a provider's method choice. */
@Serializable
data class ProviderApiKeyNavKey(val providerId: String) : NavKey

/** A provider's in-flight login; on the back stack only while its flow runs. */
@Serializable
data class ProviderLoginNavKey(val providerId: String) : NavKey

data class AutoRetryStatus(val attempt: Int, val maxAttempts: Int)

/** Outcome of the initial load of settings, credentials, and sessions. */
enum class ChatStatus {
    Loading,

    /** No valid provider/model/key configuration; the settings form is forced. */
    NeedsConfiguration,
    Ready,
    Failed
}

/** One row per catalog provider on the providers screen. */
data class ProviderOption(
    val id: String,
    val name: String,
    /** True iff a credential with a non-blank key is stored for this provider. */
    val configured: Boolean,
    /** Kind of the stored credential; null iff [configured] is false. */
    val authType: AuthType? = null
)

/** Display labels only: runtime models can carry request headers that must not enter UI state. */
data class ModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val name: String
) {
    val key: String get() = "$providerId/$modelId"
}

/**
 * An in-flight provider login: the chosen method, the ordered [AuthEvent]s
 * shown so far, and the pending prompt, if any. Contains only non-secret
 * event metadata; access/refresh tokens and manual codes never enter this
 * state.
 */
data class ProviderAuthFlow(
    val providerId: String,
    val method: AuthMethodInfo,
    val events: List<AuthEvent> = emptyList(),
    val pendingPrompt: AuthPrompt? = null
)

/** What the provider-auth screen shows first for a provider's method list. */
enum class ProviderAuthScreenMode {
    /** More than one method: choose account/subscription vs API key. */
    METHOD_CHOICE,

    /** Sole API-key method: show the credential form directly. */
    API_KEY_FORM,

    /** Sole OAuth method: offer an explicit account sign-in action. */
    START_OAUTH,

    /** No login method available (no catalog prompts, no registered flow). */
    NO_METHODS
}

internal fun providerAuthScreenMode(methods: List<AuthMethodInfo>): ProviderAuthScreenMode = when {
    methods.size > 1 -> ProviderAuthScreenMode.METHOD_CHOICE
    methods.size == 1 && methods[0].type == AuthType.API_KEY -> ProviderAuthScreenMode.API_KEY_FORM
    methods.size == 1 -> ProviderAuthScreenMode.START_OAUTH
    else -> ProviderAuthScreenMode.NO_METHODS
}

/**
 * Immutable projection of the chat screen state. Contains no credentials or
 * secrets (only per-provider [ProviderOption.configured] flags and no
 * provider-request options); transcript rows carry the runtime messages
 * themselves, projected as-is.
 *
 * Navigation is signaled from this state rather than commanded: the UI owns
 * the Nav3 back stack and resets it to [startKey] whenever [startKey] or
 * [navigationEpoch] changes, so the forced first-run provider step is a
 * single-entry dead end until configuration completes.
 */
data class ChatUiState(
    val status: ChatStatus = ChatStatus.Loading,
    /** Root of the Nav3 back stack; the stack must contain exactly this after a reset. */
    val startKey: NavKey = ChatNavKey,
    /** Monotonic reset signal: any change tells the UI to rebuild the stack to [startKey]. */
    val navigationEpoch: Long = 0,
    /**
     * Monotonic success signal for provider-credential saves: incremented
     * only after a credential has been successfully persisted, never on a
     * validation or storage failure. The UI pops exactly one
     * [ProviderAuthNavKey] entry when this changes while such an entry is
     * on top of the stack; single-entry roots are never popped.
     */
    val credentialSuccessEpoch: Long = 0,
    /** [credentialSuccessEpoch] counterpart for [SearchProviderAuthNavKey] entries (same pop contract). */
    val searchCredentialSuccessEpoch: Long = 0,
    /** All catalog providers with live auth status, name-sorted. */
    val providerOptions: List<ProviderOption> = emptyList(),
    /** All catalog search providers with live auth status, name-sorted. */
    val searchProviderOptions: List<ProviderOption> = emptyList(),
    /**
     * Models of configured providers only, sorted by provider then model
     * name; the scope curator's universe.
     */
    val modelOptions: List<ModelOption> = emptyList(),
    /**
     * Mirror of the stored `enabledModels` scope; null = no curated scope
     * (everything checked). An empty list is preserved as written and
     * behaves as no scope downstream.
     */
    val enabledModels: List<String>? = null,
    /** The bound session's live model, independent of the startup default. */
    val selectedModel: ModelOption? = null,
    /**
     * Persisted startup default model, resolved through the catalog; null
     * when unset or no longer resolvable. Unlike [selectedModel], it never
     * follows the live session or the branch fold.
     */
    val defaultModel: ModelOption? = null,
    /** The live thinking level of the bound session, or null when unbound. */
    val thinkingLevel: ModelThinkingLevel? = null,
    /** Thinking levels the current model supports; drives the thinking chip's visibility and the picker rows. */
    val availableThinkingLevels: List<ModelThinkingLevel> = emptyList(),
    /** The persisted default thinking level. */
    val defaultThinkingLevel: ModelThinkingLevel? = null,
    val activeSessionId: String? = null,
    val sessionSummaries: List<SessionInfo> = emptyList(),
    /** Drawer search over the current session summaries. */
    val sessionSearchQuery: String = "",
    /** RELEVANCE matches pi's effective default under a query (its "threaded" mode degrades to relevance). */
    val sessionSearchSort: SessionSearchSort = SessionSearchSort.RELEVANCE,
    val sessionSearchResults: List<SessionInfo> = emptyList(),
    val messages: List<TranscriptRow> = emptyList(),
    /** In-flight partial; role-generic in pi, assistant-only here (non-assistant partials render nothing). */
    val streamingMessage: AssistantMessage? = null,
    val draft: String = "",
    val isStreaming: Boolean = false,
    /** Transient auto-retry backoff status; null when not retrying. */
    val retryStatus: AutoRetryStatus? = null,
    val isCompacting: Boolean = false,
    /** Display-only; never affects the agent. */
    val showThinking: Boolean = false,
    /** Flattened tree rows of the active session's conversation (see TreeProjection.kt). */
    val treeRows: List<TreeRow> = emptyList(),
    /** In-memory tree-panel filter (never persisted). */
    val treeFilter: TreeFilter = TreeFilter.DEFAULT,
    /** The in-flight provider login flow, or null (see [ProviderAuthFlow]). */
    val authFlow: ProviderAuthFlow? = null,
    /**
     * Transient ViewModel-sourced failure shown as a snackbar. Agent-run
     * errors are never mirrored here: they render as transcript rows (pi's
     * contract) and persist with the session.
     */
    val error: String? = null
) {
    val canSend: Boolean
        get() = status == ChatStatus.Ready && !isStreaming && draft.isNotBlank()

    val scopedModelOptions: List<ModelOption>
        get() {
            if (enabledModels.isNullOrEmpty()) return modelOptions
            val enabled = enabledModels.mapTo(mutableSetOf()) { it.lowercase() }
            return modelOptions.filter { it.key.lowercase() in enabled }
        }
}
