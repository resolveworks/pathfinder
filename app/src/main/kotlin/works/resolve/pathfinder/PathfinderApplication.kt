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
import works.resolve.pathfinder.codingagent.core.SettingsManager
import works.resolve.pathfinder.data.credentials.EncryptedCredentialStore
import works.resolve.pathfinder.data.credentials.KeystoreAeadCipher
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.runtime.NativeAgentFactory
import works.resolve.pathfinder.ssh.BitmapImageProcessing
import works.resolve.pathfinder.ssh.MachineKeyStore
import works.resolve.pathfinder.ssh.MachineStore
import works.resolve.pathfinder.ssh.SshConnectionHelper
import works.resolve.pathfinder.ssh.SshConnectionProvider
import works.resolve.pathfinder.ssh.TofuHostKeyConfirmer
import works.resolve.pathfinder.tools.webfetch.WebFetchTool
import works.resolve.pathfinder.tools.webfetch.WebViewPageFetcher
import works.resolve.pathfinder.tools.websearch.BraveWebSearchTool
import works.resolve.pathfinder.tools.websearch.SearchProviderService
import works.resolve.pathfinder.ui.chat.ChatViewModel

/**
 * Application-level manual dependency graph: every property is a lazy,
 * process-wide singleton owned here (no DI framework). No shared mutable
 * coroutine scope — the Preferences DataStore delegate owns its own scope.
 */
class PathfinderApplication : Application() {

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
        SearchProviderService(credentials)
    }

    /** Resolves the key per call, so a key stored later in the app's lifetime is picked up. */
    val webSearchTool: BraveWebSearchTool by lazy {
        BraveWebSearchTool(
            client = okHttpClient,
            apiKeyResolver = {
                searchProviderService.apiKey(SearchProviderService.BRAVE_PROVIDER_ID)
            }
        )
    }

    /** Renders pages in a hidden WebView outside the default (user) WebView profile. */
    val webFetchTool: WebFetchTool by lazy {
        WebFetchTool(WebViewPageFetcher(this))
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

    /** Machine configs, per-machine keys, and TOFU host-key state. */
    val machineStore: MachineStore by lazy {
        val keyCipher = KeystoreAeadCipher()
        MachineStore(
            machinesDataStore,
            MachineKeyStore(
                File(filesDir, "machine-keys"),
                keyCipher::encrypt,
                keyCipher::decrypt
            )
        )
    }

    val sshConnectionHelper: SshConnectionHelper by lazy { SshConnectionHelper(machineStore) }

    /** Interactive TOFU host-key decisions for every dial. */
    val hostKeyConfirmer: TofuHostKeyConfirmer by lazy { TofuHostKeyConfirmer() }

    /** Process-wide lazy SSH connections; the coding tools dial on demand. */
    val sshConnectionProvider: SshConnectionProvider by lazy {
        SshConnectionProvider(
            machineStore,
            sshConnectionHelper,
            hostKeyConfirmer,
            settingsRepository.selectedMachineId
        )
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
            bashTempDir = cacheDir.path,
            imageProcessing = BitmapImageProcessing(),
            sshConnectionProvider = sshConnectionProvider
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
                agentFactory = agentFactory,
                searchProviderService = searchProviderService,
                machineStore = machineStore,
                hostKeyConfirmer = hostKeyConfirmer,
                sshConnectionProvider = sshConnectionProvider,
                modelResolver = agentFactory::resolveModel,
                appForegroundGate = appForegroundGate
            )
        }
    }

    private companion object {
        const val SESSIONS_DIRECTORY = "sessions"
        const val CONNECT_TIMEOUT_SECONDS = 30L

        /**
         * Inter-read idle cap for response headers and streamed bodies,
         * shared by every user of the client. The analog of the undici
         * dispatcher (headersTimeout/bodyTimeout both 300s) that pi installs
         * process-wide; OkHttp applies it per read op, so a stream whose gaps
         * stay under the limit never times out, and silence beyond it fails
         * like pi's bodyTimeout.
         */
        const val HTTP_IDLE_TIMEOUT_MS = 300_000L
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

private val Context.machinesDataStore by preferencesDataStore(name = "machines")
