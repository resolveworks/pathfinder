package com.aletheia.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aletheia.ui.theme.AletheiaTheme

private const val UNAVAILABLE_MESSAGE =
    "Chat is unavailable while the agent runtime awaits its native port."

/**
 * Interim chat surface. The agent runtime is pending a native port, so the composer is
 * permanently disabled rather than accepting input it cannot act on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("aletheia") })
        },
        bottomBar = {
            BottomAppBar(modifier = Modifier.imePadding()) {
                TextField(
                    value = "",
                    onValueChange = {},
                    enabled = false,
                    label = { Text("Message") },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {}, enabled = false) {
                    Text("Send")
                }
            }
        },
        modifier = modifier,
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (messages.isEmpty()) {
                Text(
                    text = UNAVAILABLE_MESSAGE,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(messages, key = ChatMessage::id) { message ->
                    MessageItem(message)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MessageItem(message: ChatMessage) {
    ListItem(
        headlineContent = {
            Text(message.text)
        },
        overlineContent = {
            Text(if (message.role == ChatRole.User) "You" else "Assistant")
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenPreview() {
    AletheiaTheme {
        ChatScreen(messages = emptyList())
    }
}
