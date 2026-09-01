package works.resolve.pathfinder.ui.chat

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.agent.LaneRecovery
import works.resolve.pathfinder.data.sessions.SessionSummary

enum class ChatRole {
    User,
    Assistant,
}

/**
 * Ordered unit of a chat message body: text or model reasoning (thinking),
 * in the order the content was produced.
 */
sealed class ChatBlock {
    /** Plain (assistant: markdown) text part. */
    data class Text(val text: String) : ChatBlock()

    /** Model reasoning part; rendering is owned by a later chunk. */
    data class Thinking(val text: String) : ChatBlock()
}

data class ChatMessage(
    /** Stable UI key; unique even when timestamps collide. */
    val id: String,
    val role: ChatRole,
    /** Body blocks in content order; consecutive thinking parts are pre-merged. */
    val blocks: List<ChatBlock>,
    /** User-facing failure text for error/aborted assistant messages. */
    val error: String? = null,
    /**
     * Marker row for a compaction cut in the active path (pi's CompactionEntry):
     * renders as a minimal divider instead of message content.
     */
    val isCompactionMarker: Boolean = false,
)

/** Navigation 3 destination key: the conversation surface. */
@Serializable
data object ChatNavKey : NavKey

/** Navigation 3 destination key: the configuration form. */
@Serializable
data object SettingsNavKey : NavKey

/** Navigation 3 destination key: the scoped-models curator (pi's /scoped-models). */
@Serializable
data object ModelsNavKey : NavKey

/** Navigation 3 destination key: the provider credential list (pi's /login). */
@Serializable
data object ProvidersNavKey : NavKey

/** Navigation 3 destination key: the credential form of one provider. */
@Serializable
data class ProviderAuthNavKey(val providerId: String) : NavKey

/** Transient auto-retry status while the agent backs off before a retry (pi's auto_retry_start/end window). */
data class AutoRetryStatus(
    val attempt: Int,
    val maxAttempts: Int,
)

 /** Outcome of initial load of settings, credentials, and sessions. */
enum class ChatStatus {
    /** Initial load in progress. */
    Loading,
    /** No valid provider/model/key configuration; the settings form is forced. */
    NeedsConfiguration,
    /** Configured and ready to chat. */
    Ready,
    /** Initialization failed with a surfaced error. */
    Failed,
}

/** Row of the providers screen: one per catalog provider, with live auth status. */
data class ProviderOption(
    val id: String,
    val name: String,
    /** True iff a credential with a non-blank key is stored for this provider. */
    val configured: Boolean,
)

/** Row of the model scope curator / picker: one per model of a configured provider. */
data class ModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val name: String,
)

/** UI-safe projection of a catalog auth prompt: env slot, message, secret flag. */
data class ProviderAuthPrompt(
    val envKey: String,
    val message: String,
    val secret: Boolean,
)

/** Kind of an interactive auth prompt (projection of pi `AuthPrompt`). */
enum class AuthPromptKind {
    TEXT,
    SECRET,
    SELECT,
    MANUAL_CODE,
}

/** One selectable option of a [AuthPromptKind.SELECT] prompt (pi's option ids/labels). */
data class AuthPromptOption(
    val id: String,
    val label: String,
    val description: String? = null,
)

/**
 * A suspended login prompt awaiting a user answer: only prompt metadata —
 * never the answer, which lives solely in the suspended prompt reply.
 */
data class PendingAuthPrompt(
    val kind: AuthPromptKind,
    val message: String,
    val placeholder: String? = null,
    val options: List<AuthPromptOption> = emptyList(),
)

/**
 * An in-flight provider login (pi's login dialog): the chosen method plus
 * the ordered [AuthEvent]s shown so far and the single pending prompt, if
 * any. Contains only non-secret event metadata (messages, URLs, user
 * codes); access/refresh tokens and manual codes never enter this state.
 */
data class ProviderAuthFlow(
    val providerId: String,
    val method: AuthMethodInfo,
    val events: List<AuthEvent> = emptyList(),
    val pendingPrompt: PendingAuthPrompt? = null,
)

/** UI projection of a pi `AuthPrompt` — metadata only, never values. */
internal fun projectAuthPrompt(prompt: AuthPrompt): PendingAuthPrompt =
    when (prompt) {
        is AuthPrompt.Text -> PendingAuthPrompt(AuthPromptKind.TEXT, prompt.message, prompt.placeholder)
        is AuthPrompt.Secret -> PendingAuthPrompt(AuthPromptKind.SECRET, prompt.message, prompt.placeholder)
        is AuthPrompt.Select -> PendingAuthPrompt(
            AuthPromptKind.SELECT,
            prompt.message,
            options = prompt.options.map { AuthPromptOption(it.id, it.label, it.description) },
        )
        is AuthPrompt.ManualCode -> PendingAuthPrompt(AuthPromptKind.MANUAL_CODE, prompt.message, prompt.placeholder)
    }

/** What the provider-auth screen shows first for a provider's method list. */
enum class ProviderAuthScreenMode {
    /** More than one method: pick account/subscription vs API key (pi's auth-type selector). */
    METHOD_CHOICE,
    /** Sole API-key method: the existing all-fields form directly. */
    API_KEY_FORM,
    /** Sole OAuth method: start the account login flow immediately (pi's login dialog). */
    START_OAUTH,
    /** No login method available (neither catalog prompts nor a registered flow). */
    NO_METHODS,
}

/** Pi's `startProviderLogin` routing, reduced to a pure screen-mode decision. */
internal fun providerAuthScreenMode(methods: List<AuthMethodInfo>): ProviderAuthScreenMode = when {
    methods.size > 1 -> ProviderAuthScreenMode.METHOD_CHOICE
    methods.size == 1 && methods[0].type == AuthType.API_KEY -> ProviderAuthScreenMode.API_KEY_FORM
    methods.size == 1 -> ProviderAuthScreenMode.START_OAUTH
    else -> ProviderAuthScreenMode.NO_METHODS
}

/**
 * The live model projection (pi's "what runs"): the model of the bound
 * [works.resolve.pathfinder.agent.AgentSession], or null while unbound.
 */
data class SelectedModel(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
)

/**
 * Immutable projection of the chat screen state. Contains only UI-safe data:
 * no API keys (only per-provider [ProviderOption.configured] flags), no provider-request options, and no AI-core
 * message objects.
 *
 * Navigation is signaled from this state rather than commanded: an
 * unconfigured app (no configured provider at all) pins
 * [ChatUiState.startKey] to [ProvidersNavKey] (first-run step: pick a
 * provider and sign in). Every intent that should return the user to the
 * chat (adopting a session, a credential save completing configuration)
 * sets [ChatUiState.startKey] to [ChatNavKey] and bumps
 * [ChatUiState.navigationEpoch] atomically with the rest of the state.
 * The UI layer owns the Nav3 back stack and resets it to
 * [ChatUiState.startKey] whenever either field changes, so the forced
 * first-run step is a single-entry dead end until configuration completes.
 * A successful provider-credential save additionally bumps the monotonic
 * [ChatUiState.credentialSuccessEpoch]; the UI pops exactly one
 * [ProviderAuthNavKey] entry when this changes while one is on top. A failed
 * or incomplete save leaves it unchanged so the form and its typed inputs
 * stay intact for correction.
 */
data class ChatUiState(
    val status: ChatStatus = ChatStatus.Loading,
    /** Root of the Nav3 back stack; the stack must contain exactly this after a reset. */
    val startKey: NavKey = ChatNavKey,
    /** Monotonic reset signal: any change tells the UI to rebuild the stack to [startKey]. */
    val navigationEpoch: Long = 0,
    /**
     * Monotonic success signal for provider-credential saves: incremented
     * only after a credential has been successfully persisted (never on a
     * validation or storage failure). The UI pops exactly one
     * [ProviderAuthNavKey] entry when this changes while such an entry is
     * still on top of the stack; single-entry roots are never popped.
     */
    val credentialSuccessEpoch: Long = 0,
    /** Every catalog provider with live auth status; name-sorted (providers screen). */
    val providerOptions: List<ProviderOption> = emptyList(),
    /**
     * Models of configured providers only; provider-name-then-model-name
     * sorted (pi's getAvailable: the credential-filtered set). The scope
     * curator's universe (pi's /scoped-models list).
     */
    val modelOptions: List<ModelOption> = emptyList(),
    /**
     * Mirror of the stored `enabledModels` scope (pi's setting; null = no
     * curated scope = everything checked). An empty list is preserved as
     * written and behaves as no scope downstream, exactly like pi's
     * `!enabledModels?.length`.
     */
    val enabledModels: List<String>? = null,
    /**
     * The picker's Scoped view (pi's selector scoped list): the model
     * options narrowed to the configured scope in display order, or all
     * model options when no (non-empty) scope is configured.
     */
    val scopedModelOptions: List<ModelOption> = emptyList(),
    /** The live model of the bound session, or null when unbound/unknown. */
    val selectedModel: SelectedModel? = null,
    val activeSessionId: String? = null,
    val sessionSummaries: List<SessionSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val streamingMessage: ChatMessage? = null,
    val draft: String = "",
    val isStreaming: Boolean = false,
    /** Transient auto-retry backoff status; null when not retrying. */
    val retryStatus: AutoRetryStatus? = null,
    /** True between compaction_start and compaction_end (pi's compaction in-progress window). */
    val isCompacting: Boolean = false,
    val canSend: Boolean = false,
    /** Display-only flag: whether to show the model's reasoning (never affects the agent). */
    val showThinking: Boolean = false,
    /** Flattened tree rows of the active session's conversation (see TreeProjection.kt). */
    val treeRows: List<TreeRow> = emptyList(),
    /** In-memory tree-panel filter (never persisted). */
    val treeFilter: TreeFilter = TreeFilter.DEFAULT,
    /** The in-flight provider login flow, or null (see [ProviderAuthFlow]; never secrets). */
    val authFlow: ProviderAuthFlow? = null,
    /**
     * Load-time lane recovery classification (pi's findOpenOperations
     * limit-2 contract, reduced by the lane-state reducer):
     * [LaneRecovery.Suspended] marks an interrupted operation — its
     * operation_finished never persisted — distinguishing an interrupted
     * run from a finished one.
     */
    val laneRecovery: LaneRecovery = LaneRecovery.Idle,
    val error: String? = null,
)
