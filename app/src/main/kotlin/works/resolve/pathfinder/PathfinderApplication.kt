package works.resolve.pathfinder

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import works.resolve.pathfinder.agent.ChatRuntime
import works.resolve.pathfinder.agent.StubChatRuntime
import works.resolve.pathfinder.data.credentials.CredentialStore
import works.resolve.pathfinder.data.credentials.EncryptedCredentialStore
import works.resolve.pathfinder.data.credentials.KeystoreAeadCipher
import works.resolve.pathfinder.data.sessions.SessionStore
import works.resolve.pathfinder.data.settings.SettingsRepository
import works.resolve.pathfinder.logging.LogcatTelemetryContext
import works.resolve.pathfinder.ui.chat.ChatViewModel
import works.resolve.pathfinder.telemetry.TelemetryContext
import java.io.File

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
 * - [ChatRuntime]: the ViewModel⇄runtime seam, currently a stub (the Koog
 *   runtime lands in the next change).
 */
class PathfinderApplication : Application() {

    /** The app's single telemetry backend: spans rendered as structured Logcat lines. */
    val telemetry: TelemetryContext by lazy { LogcatTelemetryContext() }

    val credentials: CredentialStore by lazy {
        EncryptedCredentialStore(this, KeystoreAeadCipher())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(settingsDataStore)
    }

    val sessionStore: SessionStore by lazy {
        SessionStore(File(filesDir, SESSIONS_DIRECTORY))
    }

    /** Temporary stub seam; replaced by the Koog-backed runtime in the next change. */
    val chatRuntime: ChatRuntime by lazy { StubChatRuntime() }

    /** Conventional creation point for the chat controller. */
    val chatViewModelFactory = viewModelFactory {
        initializer {
            ChatViewModel(
                settingsRepository = settingsRepository,
                credentials = credentials,
                sessionStore = sessionStore,
                runtime = chatRuntime,
                telemetryContext = telemetry,
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
