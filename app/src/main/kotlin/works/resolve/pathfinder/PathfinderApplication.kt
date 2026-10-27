package works.resolve.pathfinder

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import works.resolve.pathfinder.ai.auth.CatalogAuthRegistry
import works.resolve.pathfinder.ai.auth.CredentialStore
import works.resolve.pathfinder.ai.auth.ProductionCatalogAuthRegistry
import works.resolve.pathfinder.ai.auth.ProviderAuthService
import works.resolve.pathfinder.ai.auth.oauth.AppForegroundGate
import works.resolve.pathfinder.ai.providers.ProviderCatalog
import works.resolve.pathfinder.ai.transport.OkHttpTransport
import works.resolve.pathfinder.ai.transport.OkHttpWebSocketTransport
import works.resolve.pathfinder.codingagent.core.DEFAULT_PREAMBLE
import works.resolve.pathfinder.codingagent.core.PromptConfig
import works.resolve.pathfinder.codingagent.core.SettingsManager
import works.resolve.pathfinder.data.credentials.EncryptedCredentialStore
import works.resolve.pathfinder.data.credentials.KeystoreAeadCipher
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.runtime.NativeAgentFactory
import works.resolve.pathfinder.tools.webfetch.PdfPageFetcher
import works.resolve.pathfinder.tools.webfetch.RoutingPageFetcher
import works.resolve.pathfinder.tools.webfetch.WebFetchTool
import works.resolve.pathfinder.tools.webfetch.WebViewPageFetcher
import works.resolve.pathfinder.tools.websearch.BraveSearchExecutor
import works.resolve.pathfinder.tools.websearch.ExaSearchExecutor
import works.resolve.pathfinder.tools.websearch.SearXNGSearchExecutor
import works.resolve.pathfinder.tools.websearch.SearchProviderService
import works.resolve.pathfinder.tools.websearch.SearchTransport
import works.resolve.pathfinder.tools.websearch.SerperSearchExecutor
import works.resolve.pathfinder.tools.websearch.TavilySearchExecutor
import works.resolve.pathfinder.tools.websearch.WebSearchTool
import works.resolve.pathfinder.ui.chat.ChatViewModel

/**
 * Application-level manual dependency graph: every property is a lazy,
 * process-wide singleton owned here (no DI framework). No shared mutable
 * coroutine scope — the Preferences DataStore delegate owns its own scope.
 */
class PathfinderApplication : Application() {

    /**
     * The shared app client: one OkHttp instance for provider streaming
     * ([transport], [webSocketTransport]) and app tools. Its read timeout is
     * the 300s inter-read idle cap — the undici `bodyTimeout` analog pi
     * installs process-wide — so streamed bodies carry no whole-request
     * deadline here; per-request header-phase deadlines (the provider-SDK
     * fetch-timeout analog) are set by the transport on each call, never by
     * this client. OAuth deliberately does not use this client:
     * `OkHttpOAuthHttpClient` owns a private one because its whole-exchange
     * call deadlines (the `AbortSignal.timeout` analog) are a different
     * deadline model — merging the clients would couple OAuth's per-exchange
     * deadlines to the streaming idle model.
     */
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HTTP_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
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
        EncryptedCredentialStore(this, KeystoreAeadCipher())
    }

    /**
     * App foreground state, fed from MainActivity's onResume/onPause via
     * [ChatViewModel]; OAuth flows gate loopback waits and all OAuth network
     * work on it.
     */
    val appForegroundGate: AppForegroundGate by lazy { AppForegroundGate() }

    /** Keeps the process in the foreground-service state while an OAuth login has its browser leg. */
    val loginProcessGuard: LoginProcessGuard by lazy {
        LoginProcessGuard { active ->
            if (active) LoginForegroundService.start(this) else LoginForegroundService.stop(this)
        }
    }

    /** OAuth flows shared by the login UI and runtime auth resolution. */
    val authRegistry: CatalogAuthRegistry by lazy {
        ProductionCatalogAuthRegistry(appForegroundGate)
    }

    val authService: ProviderAuthService by lazy {
        ProviderAuthService(
            catalog = modelCatalog,
            registry = authRegistry,
            credentials = credentials
        )
    }

    val searchProviderService: SearchProviderService by lazy {
        SearchProviderService(settingsRepository, credentials)
    }

    /**
     * The active provider and its key are resolved per call, so a setting or
     * credential change mid-session lands on the next tool call.
     */
    val webSearchTool: WebSearchTool by lazy {
        WebSearchTool(
            executors = listOf(
                BraveSearchExecutor(SearchTransport(okHttpClient)),
                TavilySearchExecutor(SearchTransport(okHttpClient)),
                SerperSearchExecutor(SearchTransport(okHttpClient)),
                ExaSearchExecutor(SearchTransport(okHttpClient)),
                SearXNGSearchExecutor(SearchTransport(okHttpClient))
            ),
            initialProviderId = runBlocking { searchProviderService.activeProviderId() },
            activeProviderId = { searchProviderService.activeProviderId() },
            credentialFor = { providerId -> searchProviderService.apiKey(providerId) }
        )
    }

    /** Renders pages in a hidden WebView outside the default (user) WebView profile; PDFs go through androidx.pdf. */
    val webFetchTool: WebFetchTool by lazy {
        WebFetchTool(
            RoutingPageFetcher(
                webViewFetcher = WebViewPageFetcher(this),
                pdfFetcher = PdfPageFetcher(this, okHttpClient)
            )
        )
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsDataStore)
    }

    /**
     * The single settings manager for the whole process: every runtime-field
     * mutation goes through it, and the repository is only its storage
     * backend. Built with a bounded blocking load — the composition root has
     * no suspend context and runs once before anything reads settings.
     */
    val settingsManager: SettingsManager by lazy {
        runBlocking { SettingsManager.fromStorage(settingsRepository) }
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
            settingsManager = settingsManager,
            authRegistry = authRegistry,
            tools = listOf(webSearchTool, webFetchTool),
            promptConfig = {
                val settings = settingsRepository.currentSettings()
                PromptConfig(customPrompt = settings.customPrompt)
            }
        )
    }

    val chatViewModelFactory = viewModelFactory {
        initializer {
            ChatViewModel(
                settingsStore = settingsRepository,
                settingsManager = settingsManager,
                catalog = modelCatalog,
                authService = authService,
                sessionsDir = File(filesDir, SESSIONS_DIRECTORY),
                shareCacheDir = File(cacheDir, EXPORTS_DIRECTORY),
                agentFactory = agentFactory,
                searchProviderService = searchProviderService,
                webSearchTool = webSearchTool,
                modelResolver = agentFactory::resolveModel,
                appForegroundGate = appForegroundGate,
                loginProcessGuard = loginProcessGuard,
                defaultSystemPrompt = DEFAULT_PREAMBLE
            )
        }
    }

    private companion object {
        const val SESSIONS_DIRECTORY = "sessions"
        const val EXPORTS_DIRECTORY = "exports"
        const val CONNECT_TIMEOUT_SECONDS = 30L

        /**
         * Inter-read idle cap for response headers and streamed bodies,
         * shared by every user of the client. The analog of the undici
         * dispatcher (headersTimeout/bodyTimeout both 300s) that pi installs
         * process-wide; OkHttp applies it per read op, so a stream whose gaps
         * stay under the limit never times out, and silence beyond it fails
         * like pi's bodyTimeout.
         *
         * Numerically equal to the header-phase request deadline
         * (NativeAgentFactory's REQUEST_TIMEOUT_MS) but semantically distinct
         * — idle gap vs DNS-through-headers deadline. Do not merge them into
         * one shared constant.
         */
        const val HTTP_IDLE_TIMEOUT_MS = 300_000L
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
