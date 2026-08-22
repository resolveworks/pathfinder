package com.aletheia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.aletheia.agent.AgentConfig
import com.aletheia.agent.AgentRuntime
import com.aletheia.logging.AppLogger
import com.aletheia.logging.LogLevel
import com.aletheia.logging.LogcatLogger
import com.aletheia.ui.chat.ChatRoute
import com.aletheia.ui.chat.ChatViewModel
import com.aletheia.ui.theme.AletheiaTheme

class MainActivity : ComponentActivity() {

    private val logger: AppLogger = LogcatLogger()
    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModel.factory(
            agent = AgentRuntime(applicationContext, logger),
            config = AgentConfig(providerId = "faux", modelId = "faux-1"),
            logger = logger,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logger.log(LogLevel.Info, COMPONENT, "created")
        setContent {
            AletheiaTheme {
                ChatRoute(viewModel = chatViewModel)
            }
        }
    }

    private companion object {
        const val COMPONENT = "MainActivity"
    }
}
