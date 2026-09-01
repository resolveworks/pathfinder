package works.resolve.pathfinder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import works.resolve.pathfinder.ui.chat.ChatRoute
import works.resolve.pathfinder.ui.chat.ChatViewModel
import works.resolve.pathfinder.ui.theme.PathfinderTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        (application as PathfinderApplication).chatViewModelFactory
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppForegrounded()
    }

    override fun onPause() {
        viewModel.onAppBackgrounded()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PathfinderTheme {
                ChatRoute(viewModel)
            }
        }
    }
}
