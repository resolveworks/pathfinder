package works.resolve.pathfinder.ui.chat

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import works.resolve.pathfinder.runtime.ProviderAuthKind
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

    /** Model reasoning part; shown behind the show-thinking preference. */
    data class Thinking(val text: String) : ChatBlock()
}

data class ChatMessage(
    /** Stable UI key; unique even when timestamps collide. */
    val id: String,
    val role: ChatRole,
    /** Body blocks in content order; consecutive reasoning parts are pre-merged. */
    val blocks: List<ChatBlock>,
)

/** Navigation 3 destination key: the conversation surface. */
@Serializable
data object ChatNavKey : NavKey

/** Navigation 3 destination key: the configuration form. */
@Serializable
data object SettingsNavKey : NavKey

/** Navigation 3 destination key: the model configuration form. */
@Serializable
data object ModelSettingsNavKey : NavKey

/** Navigation 3 destination key: the provider credential list. */
@Serializable
data object ProvidersNavKey : NavKey

/** Navigation 3 destination key: the credential form of one provider. */
@Serializable
data class ProviderAuthNavKey(val providerId: String) : NavKey

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

/** Row of the providers screen: one per provider, with live credential status. */
data class ProviderOption(
    val id: String,
    val name: String,
    /** Projected descriptor auth kind: drives the credential form's shape. */
    val authKind: ProviderAuthKind,
    /** True iff a credential is stored for this provider. */
    val configured: Boolean,
)

/**
 * Live ChatGPT sign-in, null when idle. UI-safe projection only: no tokens,
 * no PKCE verifier, no redirect URLs with parameters.
 */
sealed interface CodexSignInState {
    /**
     * Device-code flow: the user enters [userCode] at [verificationUri]; the
     * sign-in polls until approved (or fails with [error]).
     */
    data class Device(
        val userCode: String,
        val verificationUri: String,
        val error: String? = null,
    ) : CodexSignInState

    /**
     * Browser flow: [authorizeUrl] is opened in the user's default browser
     * (sharing the browser's login session) while a loopback listener waits
     * for the redirect. The exchange phase ([completing]) renders inline;
     * [error] shows a retryable failure. Success renders nothing here: the
     * browser shows the listener's success page and the stored credential
     * pops the form via the credential-success epoch.
     */
    data class Browser(
        val authorizeUrl: String,
        val completing: Boolean = false,
        val error: String? = null,
    ) : CodexSignInState
}

/** Row of the model picker: one per model of a configured provider. */
data class ModelOption(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val name: String,
)

/** The committed provider+model selection (from settings), projected for display. */
data class SelectedModel(
    val providerId: String,
    val providerName: String,
    val modelId: String,
    val modelName: String,
)

/**
 * Immutable projection of the chat screen state. Contains only UI-safe data:
 * no API keys (only per-provider [ProviderOption.configured] flags) and no
 * Koog message objects.
 *
 * Navigation is signaled from this state rather than commanded: an
 * unconfigured app sets [startKey] to [ProvidersNavKey] when no provider
 * credential exists (fresh install), or directly to [ModelSettingsNavKey]
 * when one already exists (restoration), and the UI layer honors this by
 * rebuilding its Nav3 back stack to exactly that root (a dead end: back
 * cannot leave it). After a successful credential save that does not
 * complete configuration, [startKey] moves to [ModelSettingsNavKey] with a
 * [navigationEpoch] bump so configured models are immediately selectable;
 * every success that should return the user to the chat instead sets
 * [startKey] to [ChatNavKey] and bumps the monotonic [navigationEpoch],
 * which the UI layer reads as a reset-to-[startKey] signal, atomically with
 * the rest of the state. A successful provider-credential save additionally
 * bumps the monotonic [credentialSuccessEpoch]; the UI layer reacts by
 * popping one credential form off the stack when one is still on top.
 * The invariant `NeedsConfiguration implies startKey == ProvidersNavKey ||
 * startKey == ModelSettingsNavKey` holds by construction.
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
    /** Every provider with live credential status; name-sorted (providers screen). */
    val providerOptions: List<ProviderOption> = emptyList(),
    /** Models of configured providers only; provider-name-then-model-name sorted. */
    val modelOptions: List<ModelOption> = emptyList(),
    /** The committed selection projected from settings, or null when unset/invalid. */
    val selectedModel: SelectedModel? = null,
    /** True iff the selection is descriptor-valid AND a key exists for its provider. */
    val configured: Boolean = false,
    val activeSessionId: String? = null,
    val sessionSummaries: List<SessionSummary> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),
    val streamingMessage: ChatMessage? = null,
    val draft: String = "",
    val isStreaming: Boolean = false,
    val canSend: Boolean = false,
    /** Display-only flag: whether to show the model's reasoning (never affects the runtime). */
    val showThinking: Boolean = false,
    /** Flattened tree rows of the active session's conversation (see TreeProjection.kt). */
    val treeRows: List<TreeRow> = emptyList(),
    /** In-memory tree-panel filter (never persisted). */
    val treeFilter: TreeFilter = TreeFilter.DEFAULT,
    /** Live ChatGPT sign-in (browser or device code), null when idle. */
    val codexSignIn: CodexSignInState? = null,
    val error: String? = null,
)
