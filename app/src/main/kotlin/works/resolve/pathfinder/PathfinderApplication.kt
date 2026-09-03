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
import works.resolve.pathfinder.tools.webfetch.WebFetchTool
import works.resolve.pathfinder.tools.webfetch.WebViewPageFetcher
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool
import works.resolve.pathfinder.tools.websearch.SearchProviderService
import works.resolve.pathfinder.logging.PathfinderDiagnostics
import works.resolve.pathfinder.ui.chat.ChatViewModel
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Application-level manual dependency graph: every property is a lazy,
 * process-wide singleton owned here (no DI framework). No shared mutable
 * coroutine scope — the Preferences DataStore delegate owns its own scope.
 */
class PathfinderApplication : Application() {

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
     * Lets Codex requests use pi's default "auto" transport: WebSocket-first
     * with per-session SSE fallback and cached context over the pooled
     * connection.
     */
    val webSocketTransport: OkHttpWebSocketTransport by lazy {
        OkHttpWebSocketTransport(client = okHttpClient)
    }

    val credentials: CredentialStore by lazy {
        EncryptedCredentialStore(this, KeystoreAeadCipher(), diagnostics = diagnostics)
    }

    /**
     * App foreground state, fed from MainActivity's onResume/onPause via
     * [ChatViewModel]; OAuth flows gate loopback waits and all OAuth network
     * work on it.
     */
    val appForegroundGate: AppForegroundGate by lazy { AppForegroundGate() }

    /** OAuth flows shared by the login UI and runtime auth resolution. */
    val authRegistry: CatalogAuthRegistry by lazy { ProductionCatalogAuthRegistry(appForegroundGate) }

    val authService: ProviderAuthService by lazy {
        ProviderAuthService(
            catalog = modelCatalog,
            registry = authRegistry,
            credentials = credentials,
        )
    }

    val searchProviderService: SearchProviderService by lazy {
        SearchProviderService(credentials)
    }

    /** Resolves the key per call, so a key stored later in the app's lifetime is picked up. */
    val webSearchTool: BraveWebSearchTool by lazy {
        BraveWebSearchTool(
            client = okHttpClient,
            apiKeyResolver = { searchProviderService.apiKey(SearchProviderService.BRAVE_PROVIDER_ID) },
        )
    }

    /** Renders pages in a hidden WebView outside the default (user) WebView profile. */
    val webFetchTool: WebFetchTool by lazy {
        WebFetchTool(WebViewPageFetcher(this))
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsDataStore)
    }

    val sessionStore: SessionStore by lazy {
        SessionStore(File(filesDir, SESSIONS_DIRECTORY), diagnostics = diagnostics)
    }

    /** Generated from pi; never hand-edit the bundled asset. */
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
            tools = listOf(webSearchTool, webFetchTool),
        )
    }

    val chatViewModelFactory = viewModelFactory {
        initializer {
            ChatViewModel(
                settingsRepository = settingsRepository,
                catalog = modelCatalog,
                authService = authService,
                sessionStore = sessionStore,
                agentFactory = agentFactory,
                searchProviderService = searchProviderService,
                modelResolver = agentFactory::resolveModel,
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

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
