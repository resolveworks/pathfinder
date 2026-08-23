package works.resolve.aletheia

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import works.resolve.aletheia.agent.NativeAgentFactory
import works.resolve.aletheia.ai.transport.OkHttpTransport
import works.resolve.aletheia.data.credentials.CredentialStore
import works.resolve.aletheia.data.credentials.KeystoreAeadCipher
import works.resolve.aletheia.data.sessions.SessionStore
import works.resolve.aletheia.data.settings.SettingsRepository
import works.resolve.aletheia.logging.AppLogger
import works.resolve.aletheia.logging.LogLevel
import works.resolve.aletheia.logging.LogcatLogger
import works.resolve.aletheia.ui.chat.ChatViewModel
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
 * - one shared [OkHttpClient]/[OkHttpTransport] for all provider requests;
 * - [CredentialStore] on the Android-Keystore-backed [KeystoreAeadCipher];
 * - [SettingsRepository] on a single Preferences DataStore file;
 * - [SessionStore] rooted under app-private `filesDir/sessions`;
 * - [NativeAgentFactory] wiring the native Z.AI runtime.
 */
class AletheiaApplication : Application() {

    val logger: AppLogger = LogcatLogger()

    val transport: OkHttpTransport by lazy {
        OkHttpTransport(
            client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build(),
        )
    }

    val credentials: CredentialStore by lazy {
        CredentialStore(this, KeystoreAeadCipher())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsDataStore)
    }

    val sessionStore: SessionStore by lazy {
        SessionStore(File(filesDir, SESSIONS_DIRECTORY))
    }

    val agentFactory: NativeAgentFactory by lazy {
        NativeAgentFactory(credentials = credentials, transport = transport)
    }

    /** Conventional creation point for the chat controller. */
    val chatViewModelFactory = viewModelFactory {
        initializer {
            ChatViewModel(
                settingsRepository = settingsRepository,
                credentials = credentials,
                sessionStore = sessionStore,
                agentFactory = agentFactory,
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        logger.log(LogLevel.Info, COMPONENT, "application created")
    }

    private companion object {
        const val COMPONENT = "App"
        const val SESSIONS_DIRECTORY = "sessions"
        const val CONNECT_TIMEOUT_SECONDS = 30L
    }
}

/**
 * Single Preferences DataStore instance for settings, created via the
 * documented Context delegate (one instance per file per process).
 */
private val Context.settingsDataStore by preferencesDataStore(name = "settings")
