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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aletheia.ui.theme.AletheiaTheme

@Composable
fun ChatRoute(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ChatScreen(
        state = state,
        onDraftChange = viewModel::onDraftChange,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val lastMessage = state.messages.lastOrNull()

    LaunchedEffect(state.messages.size, lastMessage?.text) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("aletheia") })
        },
        bottomBar = {
            BottomAppBar(modifier = Modifier.imePadding()) {
                TextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    enabled = !state.isInitializing,
                    label = { Text("Message") },
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (state.canSend) onSend() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (state.isStreaming) {
                    TextButton(onClick = onStop) {
                        Text("Stop")
                    }
                } else {
                    Button(onClick = onSend, enabled = state.canSend) {
                        Text(if (state.isInitializing) "Starting…" else "Send")
                    }
                }
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        modifier = modifier,
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (state.messages.isEmpty()) {
                if (state.isInitializing) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else {
                    Text(
                        text = "Send a message to pi.",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.messages, key = ChatMessage::id) { message ->
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
            Text(message.text.ifEmpty { "…" })
        },
        overlineContent = {
            Text(if (message.role == ChatRole.User) "You" else "pi")
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenPreview() {
    AletheiaTheme {
        ChatScreen(
            state = ChatUiState(
                isInitializing = false,
                messages = listOf(
                    ChatMessage(1, ChatRole.User, "Is pi running?"),
                    ChatMessage(2, ChatRole.Assistant, "QuickJS is running pi-agent-core."),
                ),
                draft = "",
            ),
            onDraftChange = {},
            onSend = {},
            onStop = {},
        )
    }
}
