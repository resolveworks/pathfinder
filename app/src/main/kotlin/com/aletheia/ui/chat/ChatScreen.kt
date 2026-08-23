package com.aletheia.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aletheia.R
import com.aletheia.data.sessions.SessionSummary
import com.aletheia.ui.theme.AletheiaTheme

private const val PROVIDER_NAME = "Z.AI"
private const val STREAMING_PLACEHOLDER = "…"

/** Collects [ChatViewModel.uiState] and forwards intents from the pure [ChatScreen]. */
@Composable
fun ChatRoute(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ChatScreen(
        uiState = uiState,
        onDraftChange = viewModel::onDraftChange,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        onSaveConfiguration = viewModel::saveConfiguration,
        onNewSession = viewModel::newSession,
        onSwitchSession = viewModel::switchSession,
        onDismissError = viewModel::dismissError,
        modifier = modifier,
    )
}

/**
 * Pure, state-hoisted chat surface rendering [ChatUiState]. Every user action
 * is forwarded through an intent callback; the composable owns only ephemeral,
 * non-sensitive UI state (configuration form inputs, dropdown/menu visibility).
 *
 * The API-key input lives exclusively in Compose memory: it is never saved
 * across process death, logged, or written to any state that outlives the
 * configuration form, and it is cleared as soon as it is submitted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSaveConfiguration: (modelId: String, baseUrl: String?, apiKeyInput: String) -> Unit,
    onNewSession: () -> Unit,
    onSwitchSession: (sessionId: String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val showConfigByUser = remember { mutableStateOf(false) }
    // Returning to a non-Ready status (or saving configuration) implicitly
    // closes a user-opened configuration sheet.
    val showConfiguration = uiState.status == ChatStatus.NeedsConfiguration || showConfigByUser.value
    LaunchedEffect(uiState.status) {
        if (uiState.status != ChatStatus.Ready) showConfigByUser.value = false
    }

    uiState.error?.let { error ->
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(error)
            onDismissError()
        }
    }

    Scaffold(
        topBar = {
            when (uiState.status) {
                ChatStatus.Ready -> ChatTopBar(
                    uiState = uiState,
                    onNewSession = onNewSession,
                    onSwitchSession = onSwitchSession,
                    onOpenConfiguration = { showConfigByUser.value = true },
                )
                else -> Unit
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier,
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            when {
                uiState.status == ChatStatus.Loading -> LoadingContent()
                uiState.status == ChatStatus.Failed && !showConfiguration -> FailedContent(
                    error = uiState.error,
                    onOpenConfiguration = { showConfigByUser.value = true },
                )
                showConfiguration -> ConfigurationContent(
                    uiState = uiState,
                    onSave = onSaveConfiguration,
                    onClose = if (uiState.status == ChatStatus.Ready) {
                        { showConfigByUser.value = false }
                    } else {
                        null
                    },
                )
                else -> ConversationContent(
                    uiState = uiState,
                    onDraftChange = onDraftChange,
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
}

// ---- top bar ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    uiState: ChatUiState,
    onNewSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onOpenConfiguration: () -> Unit,
) {
    val activeTitle = uiState.sessionSummaries
        .firstOrNull { it.id == uiState.activeSessionId }?.title
        .orEmpty()
    var sessionsOpen by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Box {
                TextButton(onClick = { sessionsOpen = true }) {
                    Text(activeTitle.ifEmpty { stringResource(R.string.chat_title) })
                }
                DropdownMenu(expanded = sessionsOpen, onDismissRequest = { sessionsOpen = false }) {
                    uiState.sessionSummaries.forEach { summary ->
                        DropdownMenuItem(
                            text = { Text(summary.title) },
                            onClick = {
                                sessionsOpen = false
                                if (summary.id != uiState.activeSessionId) onSwitchSession(summary.id)
                            },
                        )
                    }
                }
            }
        },
        actions = {
            TextButton(onClick = onNewSession) { Text(stringResource(R.string.action_new_chat)) }
            TextButton(onClick = onOpenConfiguration) { Text(stringResource(R.string.action_settings)) }
        },
    )
}

// ---- status contents ----

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FailedContent(error: String?, onOpenConfiguration: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = error ?: stringResource(R.string.error_generic),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.padding(top = 16.dp))
        Button(onClick = onOpenConfiguration) {
            Text(stringResource(R.string.action_configure))
        }
    }
}

/**
 * The API-key input lives exclusively in ephemeral `remember` Compose memory
 * (never `rememberSaveable`):
 * a blank field while [ChatUiState.hasApiKey] is true keeps the stored key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigurationContent(
    uiState: ChatUiState,
    onSave: (modelId: String, baseUrl: String?, apiKeyInput: String) -> Unit,
    onClose: (() -> Unit)?,
) {
    var modelId by remember(uiState.selectedModelId) {
        mutableStateOf(uiState.selectedModelId ?: uiState.modelOptions.firstOrNull()?.id.orEmpty())
    }
    var modelMenuOpen by remember { mutableStateOf(false) }
    var baseUrl by remember(uiState.baseUrl) { mutableStateOf(uiState.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.configuration_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(R.string.configuration_provider, PROVIDER_NAME),
            style = MaterialTheme.typography.bodyMedium,
        )

        ExposedDropdownMenuBox(
            expanded = modelMenuOpen,
            onExpandedChange = { modelMenuOpen = it },
        ) {
            OutlinedTextField(
                value = uiState.modelOptions.firstOrNull { it.id == modelId }?.name ?: modelId,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.configuration_model)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuOpen) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            DropdownMenu(
                expanded = modelMenuOpen,
                onDismissRequest = { modelMenuOpen = false },
            ) {
                uiState.modelOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name) },
                        onClick = {
                            modelId = option.id
                            modelMenuOpen = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text(stringResource(R.string.configuration_base_url)) },
            placeholder = { Text(stringResource(R.string.configuration_base_url_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.configuration_api_key)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            supportingText = if (uiState.hasApiKey) {
                { Text(stringResource(R.string.configuration_api_key_keep_hint)) }
            } else {
                null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(modelId, baseUrl, apiKey)
                    // The key is submitted to the ViewModel intent and never
                    // kept locally afterwards.
                    apiKey = ""
                },
                enabled = modelId.isNotEmpty(),
            ) {
                Text(stringResource(R.string.action_save))
            }
            onClose?.let { close ->
                TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

// ---- conversation ----

@Composable
private fun ConversationContent(
    uiState: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val listState = rememberLazyListState()
    val lastMessageCount = uiState.messages.size
    val streamingId = uiState.streamingMessage?.id
    val streamingLength = uiState.streamingMessage?.text?.length

    LaunchedEffect(lastMessageCount, streamingId, streamingLength) {
        val total = lastMessageCount + if (streamingId != null) 1 else 0
        if (total > 0) listState.scrollToItem(total - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            if (lastMessageCount == 0 && streamingId == null) {
                Text(
                    text = stringResource(R.string.chat_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(uiState.messages, key = ChatMessage::id) { message ->
                    MessageItem(message)
                    HorizontalDivider()
                }
                uiState.streamingMessage?.let { streaming ->
                    item(key = streaming.id) {
                        MessageItem(
                            streaming.copy(
                                text = streaming.text.ifEmpty {
                                    if (streaming.error == null) STREAMING_PLACEHOLDER else ""
                                },
                            ),
                        )
                    }
                }
            }
        }
        Composer(
            draft = uiState.draft,
            onDraftChange = onDraftChange,
            onSend = onSend,
            onStop = onStop,
            enabled = uiState.status == ChatStatus.Ready,
            canSend = uiState.canSend,
            isStreaming = uiState.isStreaming,
        )
    }
}

@Composable
private fun MessageItem(message: ChatMessage) {
    ListItem(
        headlineContent = { Text(message.text) },
        overlineContent = {
            Text(
                when {
                    message.role == ChatRole.User -> stringResource(R.string.role_user)
                    message.error != null -> stringResource(R.string.role_assistant_failed)
                    else -> stringResource(R.string.role_assistant)
                },
            )
        },
        supportingContent = message.error?.let { error ->
            {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
    )
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    enabled: Boolean,
    canSend: Boolean,
    isStreaming: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = enabled && !isStreaming,
            label = { Text(stringResource(R.string.composer_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (isStreaming) {
            TextButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) }
        } else {
            TextButton(onClick = onSend, enabled = canSend) {
                Text(stringResource(R.string.action_send))
            }
        }
    }
}

// ---- previews ----

private val PREVIEW_MODEL_OPTIONS = listOf(
    ChatModelOption(id = "model-a", name = "Preview Model A"),
    ChatModelOption(id = "model-b", name = "Preview Model B"),
)

@Preview(showBackground = true)
@Composable
private fun ChatScreenNeedsConfigurationPreview() {
    AletheiaTheme {
        ChatScreen(
            uiState = ChatUiState(
                status = ChatStatus.NeedsConfiguration,
                modelOptions = PREVIEW_MODEL_OPTIONS,
                hasApiKey = false,
            ),
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onSaveConfiguration = { _, _, _ -> },
            onNewSession = {},
            onSwitchSession = {},
            onDismissError = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenReadyStreamingPreview() {
    AletheiaTheme {
        ChatScreen(
            uiState = ChatUiState(
                status = ChatStatus.Ready,
                modelOptions = PREVIEW_MODEL_OPTIONS,
                selectedModelId = "model-a",
                hasApiKey = true,
                activeSessionId = "s1",
                sessionSummaries = listOf(
                    SessionSummary(
                        id = "s1",
                        title = "Preview chat",
                        createdAt = 0L,
                        updatedAt = 0L,
                        messageCount = 2,
                    ),
                ),
                messages = listOf(
                    ChatMessage(id = "m1", role = ChatRole.User, text = "Hello there"),
                    ChatMessage(id = "m2", role = ChatRole.Assistant, text = "Hi! How can I help?"),
                ),
                streamingMessage = ChatMessage(id = "streaming-1", role = ChatRole.Assistant, text = "Sure, "),
                isStreaming = true,
            ),
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onSaveConfiguration = { _, _, _ -> },
            onNewSession = {},
            onSwitchSession = {},
            onDismissError = {},
        )
    }
}
