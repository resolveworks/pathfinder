package works.resolve.pathfinder.ui.chat

import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthInteraction
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthPrompt as AuthInteractionPrompt
import works.resolve.pathfinder.ai.auth.ProviderAuthService
import works.resolve.pathfinder.logging.PathfinderDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Interactive provider-login state machine. The login coroutine runs in
 * [scope], so ViewModel teardown cancels it exactly like pi's AbortSignal.
 * One login runs at a time: events accumulate as non-secret projections
 * ([ProviderAuthFlow.events]) and a suspended prompt is exposed as the
 * single [ProviderAuthFlow.pendingPrompt], answered via [submitPrompt].
 * Prompt answers cross straight into the suspended login coroutine — never
 * stored in UI state, persisted, or logged. No credential is mutated unless
 * the login completes (login persists only on success).
 */
internal class ProviderLoginController(
    private val scope: CoroutineScope,
    private val authService: ProviderAuthService,
    private val diagnostics: PathfinderDiagnostics,
    /** Runs in [scope] with the flow state already cleared. */
    private val onLoginSucceeded: suspend () -> Unit,
    /**
     * Runs in [scope] with the flow state already cleared; the raw cause is
     * never surfaced — the owner maps it to a safe static error.
     */
    private val onLoginFailed: suspend (cause: Throwable) -> Unit,
) {
    private var authJob: Job? = null
    private var pendingPromptReply: CompletableDeferred<String>? = null

    private val _flow = MutableStateFlow<ProviderAuthFlow?>(null)

    /** The active login flow, or null while no login is in flight. */
    val flow: StateFlow<ProviderAuthFlow?> = _flow.asStateFlow()

    /** True while a login is in flight or a prompt is pending; gates concurrent logins (pi's one-dialog-at-a-time rule). */
    val busy: Boolean
        get() = _flow.value != null || authJob?.isActive == true

    /** Starts the login flow; the caller validates the provider/method pair and rejects concurrent logins ([busy]). */
    fun begin(providerId: String, method: AuthMethodInfo) {
        authJob = scope.launch {
            _flow.value = ProviderAuthFlow(providerId, method)
            try {
                diagnostics.authLogin(providerId, method.type.wire) {
                    authService.login(providerId, method.type, UiAuthInteraction())
                }
            } catch (e: CancellationException) {
                // User cancel or scope teardown aborts the login — not a
                // failure: clear and stop without the failure path.
                _flow.value = null
                return@launch
            } catch (e: Exception) {
                _flow.value = null
                onLoginFailed(e)
                return@launch
            }
            _flow.value = null
            onLoginSucceeded()
        }
    }

    /** Answers the pending login prompt; a no-op when no prompt is pending. */
    fun submitPrompt(answer: String) {
        pendingPromptReply?.complete(answer)
    }

    /**
     * Cancels the in-flight login and any pending prompt (pi's dialog
     * cancel); a no-op when no flow is active.
     */
    fun cancel() {
        pendingPromptReply?.cancel(CancellationException("Login cancelled"))
        pendingPromptReply = null
        authJob?.cancel()
        // Belt-and-braces: the cancelled coroutine's own handling clears the
        // state as well.
        if (authJob?.isActive != true) {
            _flow.value = null
        }
    }

    /**
     * Bridges [AuthInteraction] onto the flow state: `notify` appends the
     * non-secret event projection; `prompt` suspends on a
     * [CompletableDeferred] — cancellation (user cancel, scope teardown)
     * aborts the whole login, per pi's AbortSignal.
     */
    private inner class UiAuthInteraction : AuthInteraction {
        override suspend fun prompt(prompt: AuthInteractionPrompt): String {
            val reply = CompletableDeferred<String>()
            pendingPromptReply = reply
            _flow.value = _flow.value?.copy(pendingPrompt = projectAuthPrompt(prompt))
            try {
                return reply.await()
            } finally {
                pendingPromptReply = null
                _flow.value = _flow.value?.copy(pendingPrompt = null)
            }
        }

        override suspend fun notify(event: AuthEvent) {
            val current = _flow.value ?: return
            _flow.value = current.copy(events = current.events + event)
        }
    }
}
