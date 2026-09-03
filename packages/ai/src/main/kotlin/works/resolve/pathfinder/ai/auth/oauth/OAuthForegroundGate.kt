package works.resolve.pathfinder.ai.auth.oauth

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Platform gate that suspends OAuth network work while the app is
 * backgrounded. Deliberate Android-only divergence: pi's desktop processes
 * are never network-restricted, but Android can stop Pathfinder's activity
 * while a browser Custom Tab or another app is foregrounded and then revoke
 * the UID's network access mid-flow — killing loopback-flow token exchanges
 * and device-code polls. The gate defers exactly that network work until the
 * app is foregrounded again. The ported flow logic never branches on the
 * gate; it is applied only at the network seams.
 */
fun interface OAuthForegroundGate {
    /** Fully cancellable — cancelling while gated still takes pi's "Login cancelled" path. */
    suspend fun awaitForeground()

    companion object {
        /** Pi parity: no gating. */
        val NONE: OAuthForegroundGate = OAuthForegroundGate {}
    }
}

/**
 * The single seam that gates token exchanges, device-code polling, and
 * refreshes uniformly for all flows, without branching the ported flow
 * logic.
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

class AppForegroundGate : OAuthForegroundGate {
    private val _foreground = MutableStateFlow(true)

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
