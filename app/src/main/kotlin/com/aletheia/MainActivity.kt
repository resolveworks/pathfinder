package com.aletheia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.aletheia.logging.AppLogger
import com.aletheia.logging.LogLevel
import com.aletheia.logging.LogcatLogger
import com.aletheia.ui.chat.ChatRoute
import com.aletheia.ui.chat.ChatViewModel
import com.aletheia.ui.theme.AletheiaTheme

class MainActivity : ComponentActivity() {

    private val logger: AppLogger = LogcatLogger()

    private val viewModel: ChatViewModel by viewModels {
        (application as AletheiaApplication).chatViewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.log(LogLevel.Info, COMPONENT, "created")
        setContent {
            AletheiaTheme {
                ChatRoute(viewModel)
            }
        }
    }

    private companion object {
        const val COMPONENT = "MainActivity"
    }
}
