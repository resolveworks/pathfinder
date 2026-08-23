package com.aletheia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aletheia.logging.AppLogger
import com.aletheia.logging.LogLevel
import com.aletheia.logging.LogcatLogger
import com.aletheia.ui.chat.ChatScreen
import com.aletheia.ui.theme.AletheiaTheme

class MainActivity : ComponentActivity() {

    private val logger: AppLogger = LogcatLogger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.log(LogLevel.Info, COMPONENT, "created")
        setContent {
            AletheiaTheme {
                ChatScreen(messages = emptyList())
            }
        }
    }

    private companion object {
        const val COMPONENT = "MainActivity"
    }
}
