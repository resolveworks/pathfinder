package works.resolve.distill

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import works.resolve.distill.logging.AppLogger
import works.resolve.distill.logging.LogLevel
import works.resolve.distill.logging.LogcatLogger
import works.resolve.distill.ui.chat.ChatRoute
import works.resolve.distill.ui.chat.ChatViewModel
import works.resolve.distill.ui.theme.DistillTheme

class MainActivity : ComponentActivity() {

    private val logger: AppLogger = LogcatLogger()

    private val viewModel: ChatViewModel by viewModels {
        (application as DistillApplication).chatViewModelFactory
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        logger.log(LogLevel.Info, COMPONENT, "created")
        setContent {
            DistillTheme {
                ChatRoute(viewModel)
            }
        }
    }

    private companion object {
        const val COMPONENT = "MainActivity"
    }
}
