package works.resolve.pathfinder.ai.auth.oauth

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform gate that suspends OAuth network work while the app is
 * backgrounded.
 *
 * Deliberate Android-only divergence from pi (documented per AGENTS.md): pi
 * runs on desktop terminals where the process is never network-restricted,
 * so `packages/ai` has no foreground concept. On Pathfinder's targets
 * (GrapheneOS / latest Android), a browser Custom Tab or another foreground
 * app can stop Pathfinder's activity, and modern Android then revokes the
 * UID's network access mid-flow — killing loopback-flow token exchanges and
 * device-code polls with it. The gate defers exactly that network work until
 * the app is foregrounded again; cancellation semantics are untouched
 * (suspending on the gate is fully cancellable, so leaving the sign-in screen
 * still aborts in-flight logins with pi's "Login cancelled" path).
 *
 * The gate is optional plumbing at the port seams: it defaults to [NONE]
 * (pi behavior — never wait), and the ported flow logic never branches on
 * it.
 */
fun interface OAuthForegroundGate {
    /** Suspends until the app is foregrounded; returns immediately when it already is. Cancellation-friendly. */
    suspend fun awaitForeground()

    companion object {
        /** Pi parity: no gating. */
        val NONE: OAuthForegroundGate = OAuthForegroundGate {}
    }
}

/**
 * [OAuthHttpClient] decorator that defers every exchange until the app is
 * foregrounded — the single seam that gates token exchanges, device-code
 * polling, and refreshes for all flows uniformly, without touching the
 * ported flow logic. Production wiring installs it around
 * [UrlConnectionOAuthHttpClient]; tests pass the raw client for pi parity.
 */
internal class ForegroundGatedOAuthHttpClient(
    private val delegate: OAuthHttpClient,
    private val gate: OAuthForegroundGate,
) : OAuthHttpClient {
    override suspend fun execute(request: OAuthHttpRequest): OAuthHttpResponse {
        gate.awaitForeground()
        return delegate.execute(request)
    }
}

/**
 * The production [OAuthForegroundGate]: one process-wide foreground flag
 * driven by the foreground activity's `onResume`/`onPause` through the
 * ViewModel (MainActivity → ChatViewModel.onAppForegrounded/onAppBackgrounded
 * → here). Created in [works.resolve.pathfinder.PathfinderApplication] as
 * part of the manual composition root.
 */
class AppForegroundGate : OAuthForegroundGate {
    private val _foreground = MutableStateFlow(true)

    /** True while the app's activity is resumed; exposed read-only for UI/projection use. */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    fun onAppForegrounded() {
        _foreground.value = true
    }

    fun onAppBackgrounded() {
        _foreground.value = false
    }

    override suspend fun awaitForeground() {
        _foreground.first { it }
    }
}
