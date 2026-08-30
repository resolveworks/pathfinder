package works.resolve.pathfinder

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import works.resolve.pathfinder.agent.ChatRuntime
import works.resolve.pathfinder.agent.KoogChatRuntime
import works.resolve.pathfinder.ai.openaicodex.CodexOAuthClient
import works.resolve.pathfinder.data.credentials.CredentialStore
import works.resolve.pathfinder.data.credentials.EncryptedCredentialStore
import works.resolve.pathfinder.data.credentials.KeystoreAeadCipher
import works.resolve.pathfinder.data.sessions.SessionStore
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.ui.chat.ChatViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-level manual dependency graph. Everything is process-wide
 * single-instance and created lazily on first use; there is no DI framework
 * and no shared mutable coroutine scope (the single Preferences DataStore
 * owns its own scope per the documented factory contract).
 *
 * The graph is deliberately flat and conventional:
 *
 * - [CredentialStore] on the Android-Keystore-backed [KeystoreAeadCipher];
 * - [SettingsRepository] on a single Preferences DataStore file;
 * - [SessionStore] rooted under app-private `filesDir/sessions`;
 * - [ChatRuntime]: the ViewModel⇄runtime seam, backed by Koog executor
 *   clients ([KoogChatRuntime]).
 */
class PathfinderApplication : Application() {

    val credentials: CredentialStore by lazy {
        EncryptedCredentialStore(this, KeystoreAeadCipher())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsDataStore)
    }

    val sessionStore: SessionStore by lazy {
        SessionStore(File(filesDir, SESSIONS_DIRECTORY))
    }

    /** App-scoped runtime scope; supervisor so one failed stream does not kill siblings. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Koog-backed runtime: per-prompt clients over a shared OkHttp engine (process lifetime). */
    val chatRuntime: ChatRuntime by lazy { KoogChatRuntime(credentials, applicationScope) }

    /**
     * Lightweight dedicated Ktor/OkHttp client for the Codex OAuth device-flow
     * endpoints — consistent with the app's Ktor/OkHttp stack, kept separate
     * from the chat runtime's engine (auth traffic is short-lived and rare).
     */
    val codexOAuthClient: CodexOAuthClient by lazy {
        CodexOAuthClient(HttpClient(OkHttp))
    }

    /** Conventional creation point for the chat controller. */
    val chatViewModelFactory = viewModelFactory {
        initializer {
            ChatViewModel(
                settingsRepository = settingsRepository,
                credentials = credentials,
                sessionStore = sessionStore,
                runtime = chatRuntime,
                codexOAuthClient = codexOAuthClient,
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
    }

    private companion object {
        const val SESSIONS_DIRECTORY = "sessions"
    }
}

/**
 * Single Preferences DataStore instance for settings, created via the
 * documented Context delegate (one instance per file per process).
 */
private val Context.settingsDataStore by preferencesDataStore(name = "settings")
