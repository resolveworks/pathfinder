package works.resolve.pathfinder

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import works.resolve.pathfinder.agent.NativeAgentFactory
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.transport.OkHttpTransport
import works.resolve.pathfinder.ai.transport.OkHttpWebSocketTransport
import works.resolve.pathfinder.ai.auth.CatalogAuthRegistry
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.ai.auth.ProductionCatalogAuthRegistry
import works.resolve.pathfinder.ai.auth.ProviderAuthService
import works.resolve.pathfinder.ai.auth.oauth.AppForegroundGate
import works.resolve.pathfinder.data.credentials.EncryptedCredentialStore
import works.resolve.pathfinder.data.credentials.KeystoreAeadCipher
import works.resolve.pathfinder.data.sessions.SessionStore
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.logging.LogcatTelemetryContext
import works.resolve.pathfinder.logging.PathfinderDiagnostics
import works.resolve.pathfinder.ui.chat.ChatViewModel
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Application-level manual dependency graph. Everything is process-wide
 * single-instance and created lazily on first use; there is no DI framework
 * and no shared mutable coroutine scope (the single Preferences DataStore
 * owns its own scope per the documented factory contract).
 *
 * The graph is deliberately flat and conventional:
 *
 * - one shared [OkHttpClient]/[OkHttpTransport] for all provider requests,
 *   plus an [OkHttpWebSocketTransport] on the same client for the Codex
 *   WebSocket transport family;
 * - [CredentialStore] (pi's credential contract) on the Android-Keystore-backed
 *   [KeystoreAeadCipher];
 * - [SettingsRepository] on a single Preferences DataStore file;
 * - [SessionStore] rooted under app-private `filesDir/sessions`;
 * - the generated multi-provider model catalog, parsed once from assets;
 * - [NativeAgentFactory] wiring the native runtime to any catalog provider.
 */
class PathfinderApplication : Application() {

    /**
     * The app's single diagnostics facade over the Logcat telemetry backend
     * (spans rendered as structured Logcat lines): the one owner of the
     * `pf.*` span vocabulary and sanitization policy.
     */
    val diagnostics: PathfinderDiagnostics by lazy { PathfinderDiagnostics(LogcatTelemetryContext()) }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    val transport: OkHttpTransport by lazy {
        OkHttpTransport(client = okHttpClient)
    }

    /**
     * WebSocket seam for the Codex adapter, sharing the HTTP client's
     * connection pool and timeouts. Wired here, Codex requests default to
     * pi's `"auto"` transport: WebSocket-first with per-session SSE fallback
     * and cached context over the pooled connection.
     */
    val webSocketTransport: OkHttpWebSocketTransport by lazy {
        OkHttpWebSocketTransport(client = okHttpClient)
    }

    val credentials: CredentialStore by lazy {
        EncryptedCredentialStore(this, KeystoreAeadCipher(), diagnostics = diagnostics)
    }

    /**
     * Process-wide app foreground state, fed from MainActivity's
     * onResume/onPause through the ChatViewModel; the OAuth registry gates
     * loopback waits and all OAuth network work on it (see AppForegroundGate).
     */
    val appForegroundGate: AppForegroundGate by lazy { AppForegroundGate() }

    /** Concrete OAuth flows shared by login UI and runtime auth resolution. */
    val authRegistry: CatalogAuthRegistry by lazy { ProductionCatalogAuthRegistry(appForegroundGate) }

    /** Login/logout orchestration over the catalog, registry, and credential store. */
    val authService: ProviderAuthService by lazy {
        ProviderAuthService(
            catalog = modelCatalog,
            registry = authRegistry,
            credentials = credentials,
        )
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsDataStore)
    }

    val sessionStore: SessionStore by lazy {
        SessionStore(File(filesDir, SESSIONS_DIRECTORY), diagnostics = diagnostics)
    }

    /** Generated model catalog, parsed once from the bundled asset. */
    val modelCatalog: ProviderCatalog by lazy {
        assets.open("models-catalog.json").bufferedReader().use { it.readText() }
            .let(ProviderCatalog.Companion::parse)
    }

    val agentFactory: NativeAgentFactory by lazy {
        NativeAgentFactory(
            credentials = credentials,
            catalog = modelCatalog,
            transport = transport,
            webSocketTransport = webSocketTransport,
            authRegistry = authRegistry,
            // Production tool registry is intentionally empty for now; pi's
            // tool surface lands with the tool-execution change.
            tools = emptyList(),
        )
    }

    /** Conventional creation point for the chat controller. */
    val chatViewModelFactory = viewModelFactory {
        initializer {
            ChatViewModel(
                settingsRepository = settingsRepository,
                catalog = modelCatalog,
                authService = authService,
                sessionStore = sessionStore,
                agentFactory = agentFactory,
                diagnostics = diagnostics,
                appForegroundGate = appForegroundGate,
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
    }

    private companion object {
        const val SESSIONS_DIRECTORY = "sessions"
        const val CONNECT_TIMEOUT_SECONDS = 30L
    }
}

/**
 * Single Preferences DataStore instance for settings, created via the
 * documented Context delegate (one instance per file per process).
 */
private val Context.settingsDataStore by preferencesDataStore(name = "settings")
