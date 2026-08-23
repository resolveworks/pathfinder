package works.resolve.aletheia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import works.resolve.aletheia.logging.AppLogger
import works.resolve.aletheia.logging.LogLevel
import works.resolve.aletheia.logging.LogcatLogger
import works.resolve.aletheia.ui.chat.ChatRoute
import works.resolve.aletheia.ui.chat.ChatViewModel
import works.resolve.aletheia.ui.theme.AletheiaTheme

class MainActivity : ComponentActivity() {

    private val logger: AppLogger = LogcatLogger()

    private val viewModel: ChatViewModel by viewModels {
        (application as AletheiaApplication).chatViewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
