package works.resolve.aletheia.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import works.resolve.aletheia.R
import works.resolve.aletheia.data.sessions.SessionSummary
import works.resolve.aletheia.ui.theme.AletheiaTheme
import kotlinx.coroutines.launch

private const val PROVIDER_NAME = "Z.AI"
private const val STREAMING_PLACEHOLDER = "…"

/** Collects [ChatViewModel.uiState], owns the Nav3 back stack, and forwards intents from the pure [ChatScreen]. */
@Composable
fun ChatRoute(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The initial stack comes from the first collected state (Loading with
    // startKey = Chat); initialize() soon swaps it via the reset effect in
    // ChatScreen. Loading still renders LoadingContent, never a flash of Chat.
    val backStack = rememberNavBackStack(uiState.startKey)
    ChatScreen(
        uiState = uiState,
        backStack = backStack,
        onDraftChange = viewModel::onDraftChange,
        onSend = viewModel::send,
        onStop = viewModel::stop,
        onSaveConfiguration = viewModel::saveConfiguration,
        onNewSession = viewModel::newSession,
        onSwitchSession = viewModel::switchSession,
        onToggleShowThinking = viewModel::setShowThinking,
        onDismissError = viewModel::dismissError,
        modifier = modifier,
    )
}

/**
 * Pure, state-hoisted chat surface rendering [ChatUiState] behind a Nav3
 * back stack ([backStack], navigation chrome hoisted like drawerState). The
 * ViewModel signals navigation through the state ([ChatUiState.startKey] and
 * [ChatUiState.navigationEpoch]); whenever either changes, the stack is reset
 * to exactly [ChatUiState.startKey], which makes an unconfigured app a
 * dead-end settings surface and returns the user to the chat after a
 * successful save or session adoption. Every user action is forwarded through
 * an intent callback; the composable owns only ephemeral, non-sensitive UI
 * state (configuration form inputs, dropdown/menu visibility, drawer state).
 *
 * The API-key input lives exclusively in Compose memory: it is never saved
 * across process death, logged, or written to any state that outlives the
 * configuration form, and it is cleared as soon as it is submitted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    backStack: MutableList<NavKey>,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSaveConfiguration: (modelId: String, baseUrl: String?, apiKeyInput: String) -> Unit,
    onNewSession: () -> Unit,
    onSwitchSession: (sessionId: String) -> Unit,
    onToggleShowThinking: (Boolean) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            onDismissError()
        }
    }

    // Reset signal from the ViewModel: rebuild the stack to exactly the
    // current root. A single-entry stack means NavDisplay's onBack does
    // nothing on it, so NeedsConfiguration is a dead end, and a bumped epoch
    // (session adopted / configuration saved) always lands back on the root.
    LaunchedEffect(uiState.startKey, uiState.navigationEpoch) {
        backStack.clear()
        backStack.add(uiState.startKey)
    }

    val pushSettings: () -> Unit = { backStack.add(SettingsNavKey) }
    val pushModelSettings: () -> Unit = { backStack.add(ModelSettingsNavKey) }
    val popSettings: () -> Unit = {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    val topKey = backStack.lastOrNull() ?: ChatNavKey

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Session navigation is meaningless before the app is configured.
        gesturesEnabled = uiState.status == ChatStatus.Ready,
        drawerContent = {
            ChatDrawerContent(
                uiState = uiState,
                onNewSession = {
                    scope.launch { drawerState.close() }
                    onNewSession()
                },
                onSwitchSession = { sessionId ->
                    scope.launch { drawerState.close() }
                    onSwitchSession(sessionId)
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    pushSettings()
                },
            )
        },
        modifier = modifier,
    ) {
        val showConversation = uiState.status != ChatStatus.Loading &&
            uiState.status != ChatStatus.Failed &&
            topKey == ChatNavKey

        Scaffold(
            topBar = {
                if (uiState.status == ChatStatus.Ready) {
                    ChatTopBar(
                        title = when (topKey) {
                            SettingsNavKey -> stringResource(R.string.settings_title)
                            ModelSettingsNavKey -> stringResource(R.string.settings_model)
                            else -> stringResource(R.string.chat_title)
                        },
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                    )
                }
            },
            bottomBar = {
                if (showConversation) {
                    Composer(
                        draft = uiState.draft,
                        onDraftChange = onDraftChange,
                        onSend = onSend,
                        onStop = onStop,
                        canSend = uiState.canSend,
                        isStreaming = uiState.isStreaming,
                        modifier = Modifier
                            .navigationBarsPadding()
                            .imePadding(),
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                when {
                    uiState.status == ChatStatus.Loading -> LoadingContent()
                    // Any settings-family surface pushed on top of a failed
                    // init replaces the error surface; popping returns to it.
                    uiState.status == ChatStatus.Failed &&
                        topKey != SettingsNavKey && topKey != ModelSettingsNavKey -> FailedContent(
                        error = uiState.error ?: stringResource(R.string.error_generic),
                        onOpenSettings = pushModelSettings,
                    )
                    else -> NavDisplay(
                        backStack = backStack,
                        onBack = { backStack.removeLastOrNull() },
                        entryProvider = entryProvider {
                            entry<ChatNavKey> { ConversationContent(uiState = uiState) }
                            entry<SettingsNavKey> {
                                SettingsContent(
                                    showThinking = uiState.showThinking,
                                    onOpenModelSettings = pushModelSettings,
                                    onToggleShowThinking = onToggleShowThinking,
                                )
                            }
                            entry<ModelSettingsNavKey> {
                                ConfigurationContent(
                                    uiState = uiState,
                                    onSave = onSaveConfiguration,
                                    onClose = if (uiState.status == ChatStatus.NeedsConfiguration) {
                                        null
                                    } else {
                                        popSettings
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

// ---- navigation drawer ----

/**
 * Default sessions sidebar: a plain titled header, a new-chat button and
 * the lazily rendered session list above, settings pinned at the bottom —
 * stock M3 pieces per the developer.android.com drawer guidance.
 */
@Composable
private fun ChatDrawerContent(
    uiState: ChatUiState,
    onNewSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    ModalDrawerSheet {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        HorizontalDivider()
        FilledTonalButton(
            onClick = onNewSession,
            modifier = Modifier
                .fillMaxWidth()
                .padding(NavigationDrawerItemDefaults.ItemPadding)
                .padding(vertical = 8.dp),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.action_new_chat))
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(uiState.sessionSummaries, key = SessionSummary::id) { summary ->
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = summary.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    selected = summary.id == uiState.activeSessionId,
                    onClick = {
                        if (summary.id != uiState.activeSessionId) onSwitchSession(summary.id)
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }
        HorizontalDivider()
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.settings_title)) },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            selected = false,
            onClick = onOpenSettings,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
    }
}

// ---- top bar ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String,
    onOpenDrawer: () -> Unit,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = stringResource(R.string.action_menu),
                )
            }
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
private fun FailedContent(error: String, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.padding(top = 16.dp))
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.action_configure))
        }
    }
}

/**
 * Settings root: a submenu listing. Rows push the model configuration form
 * or toggle display preferences directly.
 */
@Composable
private fun SettingsContent(
    showThinking: Boolean,
    onOpenModelSettings: () -> Unit,
    onToggleShowThinking: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_model)) },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onOpenModelSettings),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_show_thinking)) },
            supportingContent = { Text(stringResource(R.string.settings_show_thinking_hint)) },
            trailingContent = {
                Switch(
                    checked = showThinking,
                    onCheckedChange = onToggleShowThinking,
                )
            },
            modifier = Modifier.clickable { onToggleShowThinking(!showThinking) },
        )
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
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
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
private fun ConversationContent(uiState: ChatUiState) {
    val listState = rememberLazyListState()
    val messageCount = uiState.messages.size
    val streamingId = uiState.streamingMessage?.id
    val streamingLength = uiState.streamingMessage?.text?.length

    LaunchedEffect(messageCount, streamingId, streamingLength) {
        val total = messageCount + if (streamingId != null) 1 else 0
        if (total > 0) listState.scrollToItem(total - 1)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (messageCount == 0 && streamingId == null) {
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
    canSend: Boolean,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = !isStreaming,
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

@Composable
private fun PreviewChatScreen(
    uiState: ChatUiState,
    startKey: NavKey = ChatNavKey,
    extraKeys: List<NavKey> = emptyList(),
) {
    val backStack = rememberNavBackStack(startKey).apply { addAll(extraKeys) }
    AletheiaTheme {
        ChatScreen(
            uiState = uiState,
            backStack = rememberNavBackStack(startKey),
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onSaveConfiguration = { _, _, _ -> },
            onNewSession = {},
            onSwitchSession = {},
            onToggleShowThinking = {},
            onDismissError = {},
            modifier = Modifier,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenNeedsConfigurationPreview() {
    PreviewChatScreen(
        ChatUiState(
            status = ChatStatus.NeedsConfiguration,
            startKey = SettingsNavKey,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            hasApiKey = false,
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenSettingsPreview() {
    PreviewChatScreen(
        ChatUiState(
            status = ChatStatus.Ready,
            startKey = SettingsNavKey,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModelId = "model-a",
            hasApiKey = true,
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenSettingsRootPreview() {
    PreviewChatScreen(
        uiState = ChatUiState(
            status = ChatStatus.Ready,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModelId = "model-a",
            hasApiKey = true,
        ),
        extraKeys = listOf(SettingsNavKey),
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenModelSettingsPreview() {
    PreviewChatScreen(
        uiState = ChatUiState(
            status = ChatStatus.Ready,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModelId = "model-a",
            hasApiKey = true,
        ),
        extraKeys = listOf(SettingsNavKey, ModelSettingsNavKey),
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenReadyStreamingPreview() {
    PreviewChatScreen(
        ChatUiState(
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
    )
}
