package com.aletheia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aletheia.ui.chat.ChatRoute
import com.aletheia.ui.theme.AletheiaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AletheiaTheme {
                ChatRoute()
            }
        }
    }
}
