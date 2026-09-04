package works.resolve.pathfinder.ui.chat

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthPrompt
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.codingagent.core.session.LaneRecovery
import works.resolve.pathfinder.codingagent.core.session.SessionSummary

enum class ChatRole {
    User,
    Assistant,
    Tool
}

/** Text or thinking unit of a chat message body. */
sealed class ChatBlock {
    /** Plain text part; assistant text renders as markdown. */
    data class Text(val text: String) : ChatBlock()

    data class Thinking(val text: String) : ChatBlock()

    /** Name-only; raw JSON arguments never enter UI state. */
    data class ToolCall(val toolCallId: String, val name: String) : ChatBlock()
}

data class ChatMessage(
    /** Stable UI key; unique even when timestamps collide. */
    val id: String,
    val role: ChatRole,
    /** Body blocks in content order; consecutive thinking parts are pre-merged. */
    val blocks: List<ChatBlock>,
    /** User-facing failure text for error/aborted assistant messages. */
    val error: String? = null,
    /** Marker row for a compaction cut; renders as a divider, not message content. */
    val isCompactionMarker: Boolean = false,
    /** Tool-result payload; set only on [ChatRole.Tool] rows (empty blocks). */
    val toolResult: ChatToolResult? = null
)

/**
 * UI-safe projection of a committed tool result: tool name, error flag,
 * and the full text output. The structured `details` JSON and the raw
 * JSON arguments never enter UI state; [input] is the one parsed argument
 * the row title is built from (see `toolCallInput`).
 */
data class ChatToolResult(
    val toolCallId: String,
    val toolName: String,
    val isError: Boolean,
    val output: String? = null,
    /** Parsed call argument the row title is built from; null when the tool has none. */
    val input: String? = null
)

data class PendingToolExecution(
    val toolCallId: String,
    val toolName: String,
    /** Parsed call argument the row title is built from; null when the tool has none. */
    val input: String? = null
)

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
    val configured: Boolean
)

/** One row per model of a configured provider. */
data class ModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val name: String
)

data class ProviderAuthPrompt(val envKey: String, val message: String, val secret: Boolean)

enum class AuthPromptKind {
    TEXT,
    SECRET,
    SELECT,
    MANUAL_CODE
}

data class AuthPromptOption(val id: String, val label: String, val description: String? = null)

/**
 * A suspended login prompt awaiting a user answer: prompt metadata only —
 * the answer lives solely in the prompt reply.
 */
data class PendingAuthPrompt(
    val kind: AuthPromptKind,
    val message: String,
    val placeholder: String? = null,
    val options: List<AuthPromptOption> = emptyList()
)

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
    val pendingPrompt: PendingAuthPrompt? = null
)

internal fun projectAuthPrompt(prompt: AuthPrompt): PendingAuthPrompt = when (prompt) {
    is AuthPrompt.Text -> PendingAuthPrompt(
        AuthPromptKind.TEXT,
        prompt.message,
        prompt.placeholder
    )

    is AuthPrompt.Secret -> PendingAuthPrompt(
        AuthPromptKind.SECRET,
        prompt.message,
        prompt.placeholder
    )

    is AuthPrompt.Select -> PendingAuthPrompt(
        AuthPromptKind.SELECT,
        prompt.message,
        options = prompt.options.map { AuthPromptOption(it.id, it.label, it.description) }
    )

    is AuthPrompt.ManualCode -> PendingAuthPrompt(
        AuthPromptKind.MANUAL_CODE,
        prompt.message,
        prompt.placeholder
    )
}

/** What the provider-auth screen shows first for a provider's method list. */
enum class ProviderAuthScreenMode {
    /** More than one method: choose account/subscription vs API key. */
    METHOD_CHOICE,

    /** Sole API-key method: show the credential form directly. */
    API_KEY_FORM,

    /** Sole OAuth method: start the account login flow immediately. */
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

/** The live model of the bound [works.resolve.pathfinder.codingagent.core.AgentSession], or null while unbound. */
data class SelectedModel(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String
)

/**
 * Immutable projection of the chat screen state. Contains only UI-safe data:
 * no API keys (only per-provider [ProviderOption.configured] flags), no
 * provider-request options, and no AI-core message objects.
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
    /**
     * Model options narrowed to the configured scope in display order; all
     * model options when no (non-empty) scope is configured.
     */
    val scopedModelOptions: List<ModelOption> = emptyList(),
    val selectedModel: SelectedModel? = null,
    /**
     * Persisted startup default model, resolved through the catalog; null
     * when unset or no longer resolvable. Unlike [selectedModel], it never
     * follows the live session or the branch fold.
     */
    val defaultModel: SelectedModel? = null,
    /** The live thinking level of the bound session, or null when unbound. */
    val thinkingLevel: ModelThinkingLevel? = null,
    /** Thinking levels the current model supports; drives the thinking chip's visibility and the picker rows. */
    val availableThinkingLevels: List<ModelThinkingLevel> = emptyList(),
    /** The persisted default thinking level. */
    val defaultThinkingLevel: ModelThinkingLevel? = null,
    val activeSessionId: String? = null,
    val sessionSummaries: List<SessionSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val pendingTools: List<PendingToolExecution> = emptyList(),
    val streamingMessage: ChatMessage? = null,
    val draft: String = "",
    val isStreaming: Boolean = false,
    /** Transient auto-retry backoff status; null when not retrying. */
    val retryStatus: AutoRetryStatus? = null,
    val isCompacting: Boolean = false,
    val canSend: Boolean = false,
    /** Display-only; never affects the agent. */
    val showThinking: Boolean = false,
    /** Flattened tree rows of the active session's conversation (see TreeProjection.kt). */
    val treeRows: List<TreeRow> = emptyList(),
    /** In-memory tree-panel filter (never persisted). */
    val treeFilter: TreeFilter = TreeFilter.DEFAULT,
    /** The in-flight provider login flow, or null (see [ProviderAuthFlow]). */
    val authFlow: ProviderAuthFlow? = null,
    /**
     * Load-time lane recovery classification: [LaneRecovery.Suspended]
     * marks an interrupted operation — its operation_finished was never
     * persisted — as distinct from a finished one.
     */
    val laneRecovery: LaneRecovery = LaneRecovery.Idle,
    val error: String? = null
)
