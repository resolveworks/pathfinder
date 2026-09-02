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
 * Interactive provider-login state machine (pi's `startProviderLogin` plus
 * its modal login dialog, reduced to a controller). The login coroutine runs
 * in [scope] (the ViewModel scope), so ViewModel teardown cancels it exactly
 * like pi's AbortSignal. One login runs at a time: events accumulate as
 * non-secret projections ([ProviderAuthFlow.events]); a suspended prompt is
 * exposed as the single [ProviderAuthFlow.pendingPrompt] and answered via
 * [submitPrompt]; prompt answers cross straight into the suspended login
 * coroutine and are never stored in UI state, persisted, or logged. No
 * credential is mutated unless the login completes (login persists only on
 * success). The [flow] state is the source of truth the ViewModel projects
 * into [ChatUiState.authFlow].
 */
internal class ProviderLoginController(
    private val scope: CoroutineScope,
    private val authService: ProviderAuthService,
    private val diagnostics: PathfinderDiagnostics,
    /**
     * Runs in [scope] after a successful login, with the flow state already
     * cleared (pi's `completeProviderAuthentication` successor step).
     */
    private val onLoginSucceeded: suspend () -> Unit,
    /**
     * Runs in [scope] when a login fails, with the flow state already
     * cleared; only the cause is handed over, never surfaced raw (the owner
     * maps it to a safe static error).
     */
    private val onLoginFailed: suspend (cause: Throwable) -> Unit,
) {
    /** The in-flight login coroutine, if any (cancelled by user cancel or scope teardown). */
    private var authJob: Job? = null

    /** Reply channel of the currently suspended login prompt, if any. */
    private var pendingPromptReply: CompletableDeferred<String>? = null

    private val _flow = MutableStateFlow<ProviderAuthFlow?>(null)

    /** The active login flow's UI projection, or null while no login is in flight. */
    val flow: StateFlow<ProviderAuthFlow?> = _flow.asStateFlow()

    /** True while any login is in flight or a prompt is pending (pi's one-dialog-at-a-time rule). */
    val busy: Boolean
        get() = _flow.value != null || authJob?.isActive == true

    /**
     * Starts the selected method's login flow. The caller validates the
     * provider/method pair first; concurrency is rejected here exactly like
     * pi's modal login dialog — one login at a time.
     */
    fun begin(providerId: String, method: AuthMethodInfo) {
        authJob = scope.launch {
            _flow.value = ProviderAuthFlow(providerId, method)
            try {
                diagnostics.authLogin(providerId, method.type.wire) {
                    authService.login(providerId, method.type, UiAuthInteraction())
                }
            } catch (e: CancellationException) {
                // User cancel or scope teardown: no credential was
                // mutated (login persists only on success); clear and stop.
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

    /**
     * Answers the pending login prompt. The answer crosses straight into
     * the suspended login coroutine; it is never stored in UI state, saved,
     * or logged. A no-op when no prompt is pending.
     */
    fun submitPrompt(answer: String) {
        pendingPromptReply?.complete(answer)
    }

    /**
     * Cancels the in-flight login (pi's dialog cancel): the login coroutine
     * and any pending prompt are cancelled, no credential is mutated, and
     * the flow state clears. A no-op when no flow is active.
     */
    fun cancel() {
        pendingPromptReply?.cancel(CancellationException("Login cancelled"))
        pendingPromptReply = null
        authJob?.cancel()
        // Belt-and-braces for a flow suspended outside a prompt: the login
        // coroutine's cancellation handler clears the state above.
        if (authJob?.isActive != true) {
            _flow.value = null
        }
    }

    /**
     * Bridges the ported [AuthInteraction] onto the flow state: `notify`
     * appends the non-secret event projection; `prompt` exposes one pending
     * prompt and suspends on a [CompletableDeferred] — cancellation (user
     * cancel, scope teardown) aborts the whole login, per pi's AbortSignal.
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
