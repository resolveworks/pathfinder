package works.resolve.pathfinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import works.resolve.pathfinder.logging.AppLogger
import works.resolve.pathfinder.logging.LogLevel
import works.resolve.pathfinder.logging.LogcatLogger
import works.resolve.pathfinder.ui.chat.ChatRoute
import works.resolve.pathfinder.ui.chat.ChatViewModel
import works.resolve.pathfinder.ui.theme.PathfinderTheme

class MainActivity : ComponentActivity() {

    private val logger: AppLogger = LogcatLogger()

    private val viewModel: ChatViewModel by viewModels {
        (application as PathfinderApplication).chatViewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        logger.log(LogLevel.Info, COMPONENT, "created")
        setContent {
            PathfinderTheme {
                ChatRoute(viewModel)
            }
        }
    }

    private companion object {
        const val COMPONENT = "MainActivity"
    }
}
