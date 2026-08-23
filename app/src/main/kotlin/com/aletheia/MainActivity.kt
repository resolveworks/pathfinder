package com.aletheia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aletheia.logging.AppLogger
import com.aletheia.logging.LogLevel
import com.aletheia.logging.LogcatLogger
import com.aletheia.ui.chat.ChatScreen
import com.aletheia.ui.chat.ChatStatus
import com.aletheia.ui.chat.ChatUiState
import com.aletheia.ui.theme.AletheiaTheme

class MainActivity : ComponentActivity() {

    private val logger: AppLogger = LogcatLogger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.log(LogLevel.Info, COMPONENT, "created")
        setContent {
            AletheiaTheme {
                // TEMPORARY: static placeholder state until the next chunk
                // wires the real dependencies into a ChatViewModel/ChatRoute.
                ChatScreen(
                    uiState = ChatUiState(status = ChatStatus.NeedsConfiguration),
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    onSaveConfiguration = { _, _, _ -> },
                    onNewSession = {},
                    onSwitchSession = {},
                    onDismissError = {},
                )
            }
        }
    }

    private companion object {
        const val COMPONENT = "MainActivity"
    }
}
