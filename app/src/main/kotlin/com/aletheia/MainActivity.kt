package com.aletheia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.aletheia.ui.theme.AletheiaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AletheiaTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Placeholder(
                        text = "aletheia — scaffolding",
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
fun Placeholder(text: String, modifier: Modifier = Modifier) {
    Text(text = text, modifier = modifier)
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderPreview() {
    AletheiaTheme {
        Placeholder(text = "aletheia — scaffolding")
    }
}
