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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import works.resolve.aletheia.ui.chat.markdown.MarkdownText
import works.resolve.aletheia.ui.theme.AletheiaTheme
import kotlinx.coroutines.launch

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
        onSaveModelSelection = viewModel::saveModelSelection,
        onSaveProviderCredential = viewModel::saveProviderCredential,
        onRemoveProviderCredential = viewModel::removeProviderCredential,
        authPrompts = viewModel::providerAuthPrompts,
        onRefreshProviderStatus = viewModel::refreshProviderStatus,
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
 * configuration form. Submitting does not clear it — the form is popped
 * (and its inputs disposed) only after the save is confirmed successful
 * via the state's credential-success epoch, so a failed save retains the
 * typed inputs for correction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    backStack: MutableList<NavKey>,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSaveModelSelection: (providerId: String, modelId: String) -> Unit,
    onSaveProviderCredential: (providerId: String, apiKeyInput: String, envInputs: Map<String, String>) -> Unit,
    onRemoveProviderCredential: (providerId: String) -> Unit,
    authPrompts: (providerId: String) -> List<ProviderAuthPrompt>,
    onRefreshProviderStatus: () -> Unit,
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

    // Successful credential save: pop exactly one credential form
    // (state-driven — no ViewModel navigation callback). Guarded so it
    // composes safely with the reset above, which runs first: first-run
    // saves bump both epochs and the reset already rebuilt the stack to a
    // single-entry root (ModelSettings/Chat), which is never popped; a
    // Ready-state save bumps only this epoch, returning the user from
    // ProviderAuth to Providers. A failed or incomplete save never bumps
    // this epoch, so the form and its typed inputs stay intact.
    LaunchedEffect(uiState.credentialSuccessEpoch) {
        if (backStack.size > 1 && backStack.lastOrNull() is ProviderAuthNavKey) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    val pushSettings: () -> Unit = { backStack.add(SettingsNavKey) }
    val pushModelSettings: () -> Unit = { backStack.add(ModelSettingsNavKey) }
    val pushProviders: () -> Unit = { backStack.add(ProvidersNavKey) }
    val pushProviderAuth: (String) -> Unit = { backStack.add(ProviderAuthNavKey(it)) }
    val popBackStack: () -> Unit = {
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
                if (uiState.status != ChatStatus.Loading) {
                    val canPop = backStack.size > 1
                    ChatTopBar(
                        title = when (topKey) {
                            SettingsNavKey -> stringResource(R.string.settings_title)
                            ModelSettingsNavKey -> stringResource(R.string.settings_model)
                            ProvidersNavKey -> stringResource(R.string.providers_title)
                            is ProviderAuthNavKey -> uiState.providerOptions
                                .firstOrNull { it.id == topKey.providerId }?.name
                                ?: stringResource(R.string.providers_title)
                            else -> stringResource(R.string.chat_title)
                        },
                        // The drawer belongs to the Chat root; nested
                        // destinations navigate up instead. A forced root
                        // (unconfigured app) is a dead end: no Up arrow.
                        onOpenDrawer = if (topKey == ChatNavKey &&
                            uiState.status == ChatStatus.Ready
                        ) {
                            { scope.launch { drawerState.open() } }
                        } else {
                            null
                        },
                        onBack = if (canPop) popBackStack else null,
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
                    // Any settings-family destination pushed on top of a
                    // failed init replaces the error surface; popping returns.
                    uiState.status == ChatStatus.Failed && topKey == ChatNavKey -> FailedContent(
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
                                    onOpenProviders = pushProviders,
                                    onToggleShowThinking = onToggleShowThinking,
                                )
                            }
                            entry<ModelSettingsNavKey> {
                                ModelSettingsContent(
                                    uiState = uiState,
                                    onSave = onSaveModelSelection,
                                    onOpenProviders = pushProviders,
                                    onClose = if (uiState.status == ChatStatus.NeedsConfiguration) {
                                        null
                                    } else {
                                        popBackStack
                                    },
                                )
                            }
                            entry<ProvidersNavKey> {
                                ProvidersContent(
                                    providerOptions = uiState.providerOptions,
                                    onRefresh = onRefreshProviderStatus,
                                    onOpenProvider = pushProviderAuth,
                                )
                            }
                            entry<ProviderAuthNavKey> { key ->
                                val option = uiState.providerOptions
                                    .firstOrNull { it.id == key.providerId }
                                if (option != null) {
                                    ProviderAuthContent(
                                        provider = option,
                                        prompts = authPrompts(key.providerId),
                                        onSave = { apiKeyInput, envInputs ->
                                            onSaveProviderCredential(key.providerId, apiKeyInput, envInputs)
                                        },
                                        onRemove = { onRemoveProviderCredential(key.providerId) },
                                        onClose = popBackStack,
                                    )
                                }
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
 * Default sessions sidebar: a header row pairing the app title with a
 * settings icon on the trailing edge, a new-chat button and the lazily
 * rendered session list — stock M3 pieces per the developer.android.com
 * drawer guidance.
 */
@Composable
private fun ChatDrawerContent(
    uiState: ChatUiState,
    onNewSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    ModalDrawerSheet {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                )
            }
        }
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
    }
}

// ---- top bar ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String,
    onOpenDrawer: (() -> Unit)?,
    onBack: (() -> Unit)?,
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            when {
                onOpenDrawer != null -> IconButton(onClick = onOpenDrawer) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = stringResource(R.string.action_menu),
                    )
                }
                onBack != null -> IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
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
 * Settings root: a submenu listing. Rows push the model configuration form,
 * the provider credential list, or toggle display preferences directly.
 */
@Composable
private fun SettingsContent(
    showThinking: Boolean,
    onOpenModelSettings: () -> Unit,
    onOpenProviders: () -> Unit,
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
            headlineContent = { Text(stringResource(R.string.providers_title)) },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onOpenProviders),
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
 * Model settings screen (pi's /model): a searchable picker over models of
 * configured providers only. Picking a row only changes local state; Save
 * commits it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSettingsContent(
    uiState: ChatUiState,
    onSave: (providerId: String, modelId: String) -> Unit,
    onOpenProviders: () -> Unit,
    onClose: (() -> Unit)?,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val preselected = uiState.modelOptions.firstOrNull { option ->
        uiState.selectedModel?.let { option.providerId == it.providerId && option.modelId == it.modelId } == true
    }
    var selection by remember(uiState.selectedModel, uiState.modelOptions) {
        mutableStateOf(preselected)
    }
    val filteredOptions = uiState.modelOptions.filter { option ->
        val q = query.trim()
        q.isEmpty() || option.name.contains(q, ignoreCase = true) ||
            option.modelId.contains(q, ignoreCase = true) ||
            option.providerName.contains(q, ignoreCase = true)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        preselected?.let { selected ->
            filteredOptions.indexOfFirst { it.modelId == selected.modelId && it.providerId == selected.providerId }
                .takeIf { it >= 0 }
                ?.let { listState.scrollToItem(it) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.model_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(filteredOptions, key = { "${it.providerId}/${it.modelId}" }) { option ->
                val isSelected = selection?.providerId == option.providerId &&
                    selection?.modelId == option.modelId
                ListItem(
                    headlineContent = { Text(option.name) },
                    supportingContent = { Text(option.providerName) },
                    trailingContent = if (isSelected) {
                        {
                            // pi's model selector marks the current model with a check.
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.model_selected),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.clickable { selection = option },
                )
                HorizontalDivider()
            }
        }
        if (uiState.modelOptions.isEmpty()) {
            Text(
                text = stringResource(R.string.models_empty_configured_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenProviders) {
                Text(stringResource(R.string.action_set_up_providers))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { selection?.let { onSave(it.providerId, it.modelId) } },
                enabled = selection != null,
            ) {
                Text(stringResource(R.string.action_save))
            }
            onClose?.let { close ->
                TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    }
}

/**
 * Providers screen (pi's /login list): every catalog provider with live
 * configured/unconfigured status, filtered by name/id substring.
 */
@Composable
private fun ProvidersContent(
    providerOptions: List<ProviderOption>,
    onRefresh: () -> Unit,
    onOpenProvider: (providerId: String) -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }

    var query by rememberSaveable { mutableStateOf("") }
    val filtered = providerOptions.filter { option ->
        val q = query.trim()
        q.isEmpty() || option.name.contains(q, ignoreCase = true) ||
            option.id.contains(q, ignoreCase = true)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.provider_search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.no_matching_providers),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn {
                items(filtered, key = ProviderOption::id) { option ->
                    ListItem(
                        headlineContent = { Text(option.name) },
                        supportingContent = { Text(option.id) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(
                                        if (option.configured) R.string.provider_status_configured
                                        else R.string.provider_status_unconfigured,
                                    ),
                                    color = if (option.configured) {
                                        MaterialTheme.colorScheme.secondary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                )
                            }
                        },
                        modifier = Modifier.clickable { onOpenProvider(option.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Credential form for one provider (pi's auth dialog): one field per catalog
 * prompt in order — the first is the secret API key, later prompts fill env
 * slots. All inputs live in plain Compose memory only: never saved across
 * process death or recomposition-surviving state, never logged. Submitting
 * does not clear the inputs — the form is popped (and its inputs disposed)
 * only after the save is confirmed successful via the state's
 * credential-success epoch, so a failed save retains them for correction.
 */
@Composable
private fun ProviderAuthContent(
    provider: ProviderOption,
    prompts: List<ProviderAuthPrompt>,
    onSave: (apiKeyInput: String, envInputs: Map<String, String>) -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    var apiKeyInput by remember { mutableStateOf("") }
    val envInputs = remember(prompts) { mutableStateMapOf<String, String>() }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        prompts.forEach { prompt ->
            val isSecret = prompt.secret
            OutlinedTextField(
                value = if (isSecret) apiKeyInput else envInputs[prompt.envKey].orEmpty(),
                onValueChange = {
                    if (isSecret) apiKeyInput = it else envInputs[prompt.envKey] = it
                },
                label = { Text(prompt.message) },
                visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Stacked so narrow widths never put Save, Cancel, and the
        // destructive Forget action in one horizontal row.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(apiKeyInput, envInputs.toMap()) },
            ) {
                Text(stringResource(R.string.action_save))
            }
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
        }
        if (provider.configured) {
            TextButton(
                onClick = { confirmRemove = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.action_remove_provider))
            }
        }
    }

    if (confirmRemove) {
        val name = provider.name
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.action_remove_provider)) },
            text = { Text(stringResource(R.string.remove_provider_confirm, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        onRemove()
                        onClose()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.action_remove_provider))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// ---- conversation ----

/** Concatenated text of the text blocks; the displayable body for user (plain-text) messages. */
private fun ChatMessage.displayText(): String =
    blocks.filterIsInstance<ChatBlock.Text>().joinToString("\n\n") { it.text }

/**
 * Persists per-thinking-block expanded overrides across process death as two
 * parallel flat lists (key, value, key, value, …); both [String] and [Boolean]
 * are Bundle-saveable.
 */
private fun thinkingOverridesSaver() = listSaver<MutableMap<String, Boolean>, Any>(
    save = { map ->
        buildList {
            map.forEach { (key, expanded) ->
                add(key)
                add(expanded)
            }
        }
    },
    restore = { list ->
        mutableStateMapOf<String, Boolean>().apply {
            var i = 0
            while (i + 1 < list.size) {
                put(list[i] as String, list[i + 1] as Boolean)
                i += 2
            }
        }
    },
)

@Composable
private fun ConversationContent(
    uiState: ChatUiState,
    initialThinkingOverrides: Map<String, Boolean> = emptyMap(),
) {
    val listState = rememberLazyListState()
    val messageCount = uiState.messages.size
    val streamingId = uiState.streamingMessage?.id
    // Covers text AND thinking so a later chunk keeps auto-scrolling during
    // thinking-only streaming; thinking changes alone also advance it.
    val streamingLength = uiState.streamingMessage?.blocks?.sumOf { block ->
        when (block) {
            is ChatBlock.Text -> block.text.length
            is ChatBlock.Thinking -> block.text.length
        }
    }

    // Target one PAST the last item (totalItemsCount): measure finds nothing
    // forward, backfills upward (LazyListMeasure "scroll back" branch), and the
    // viewport lands with the end of ALL content at the bottom edge — the true
    // bottom of the transcript, not the top of the last item. Applied during the
    // same remeasure that delivers new/changed items, so no top-of-transcript
    // flash on session open or while streaming.
    LaunchedEffect(messageCount, streamingId, streamingLength) {
        val total = messageCount + if (streamingId != null) 1 else 0
        if (total > 0) listState.requestScrollToItem(total)
    }

    // Per-block expanded overrides (ephemeral view state keyed by stable
    // "messageId:blockIndex"): a block the user never tapped keeps following
    // the persisted showThinking default; a tapped block locks in the user's
    // choice and outlives later changes to the setting.
    val thinkingOverrides = rememberSaveable(saver = thinkingOverridesSaver()) {
        mutableStateMapOf<String, Boolean>().apply { putAll(initialThinkingOverrides) }
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
                MessageItem(
                    message = message,
                    showThinking = uiState.showThinking,
                    thinkingOverrides = thinkingOverrides,
                )
                HorizontalDivider()
            }
            uiState.streamingMessage?.let { streaming ->
                item(key = streaming.id) {
                    val hasVisibleText = streaming.blocks.any { it is ChatBlock.Text && it.text.isNotBlank() }
                    val hasThinking = streaming.blocks.any { it is ChatBlock.Thinking }
                    MessageItem(
                        message = if (hasVisibleText || hasThinking || streaming.error != null) {
                            streaming
                        } else {
                            // No visible content at all yet: same "…" placeholder
                            // as before. A thinking-only stream renders its real
                            // blocks (thinking header + loader) instead.
                            streaming.copy(blocks = listOf(ChatBlock.Text(STREAMING_PLACEHOLDER)))
                        },
                        isStreaming = true,
                        showThinking = uiState.showThinking,
                        thinkingOverrides = thinkingOverrides,
                    )
                }
            }
        }
    }
}

/**
 * One chat row. User messages render plain concatenated text; assistant
 * messages render their blocks in content order — text blocks as markdown,
 * thinking blocks as collapsible [ThinkingBlock]s whose default expanded
 * state follows [showThinking] until the user taps one (then the per-block
 * [thinkingOverrides] entry wins, surviving changes to the setting).
 */
@Composable
private fun MessageItem(
    message: ChatMessage,
    showThinking: Boolean,
    thinkingOverrides: MutableMap<String, Boolean>,
    isStreaming: Boolean = false,
) {
    ListItem(
        // pi renders user markdown literally (markers preserved, not parsed);
        // the MVP equivalent here is plain text, so only the assistant path
        // goes through MarkdownText.
        headlineContent = {
            if (message.role == ChatRole.Assistant) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    message.blocks.forEachIndexed { index, block ->
                        when (block) {
                            is ChatBlock.Text -> MarkdownText(markdown = block.text)
                            is ChatBlock.Thinking -> {
                                val key = "${message.id}:$index"
                                val expanded = thinkingOverrides[key] ?: showThinking
                                ThinkingBlock(
                                    text = block.text,
                                    expanded = expanded,
                                    // Loader only while thinking is actively being
                                    // produced: the streaming message's LAST block
                                    // and a Thinking block (earlier runs are done).
                                    showLoader = isStreaming && index == message.blocks.lastIndex,
                                    onToggle = { thinkingOverrides[key] = !expanded },
                                )
                            }
                        }
                    }
                }
            } else {
                Text(message.displayText())
            }
        },
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

/**
 * Collapsible unit for one thinking run (pi's assistant-message thinking):
 * a tappable header row — lowercase "thinking" label, a small loader only
 * while the owning message is still streaming, and an expand chevron — plus,
 * when expanded, the reasoning rendered as dimmed italic markdown.
 */
@Composable
private fun ThinkingBlock(
    text: String,
    expanded: Boolean,
    showLoader: Boolean,
    onToggle: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.thinking_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showLoader) {
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            MarkdownText(
                markdown = text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                italic = true,
            )
        }
    }
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
    ModelOption(
        providerId = "zai",
        providerName = "Z.AI",
        modelId = "model-a",
        name = "Preview Model A",
    ),
    ModelOption(
        providerId = "zai",
        providerName = "Z.AI",
        modelId = "model-b",
        name = "Preview Model B",
    ),
)

private val PREVIEW_PROVIDER_OPTIONS = listOf(
    ProviderOption("anthropic", "Anthropic", configured = true),
    ProviderOption("cloudflare-ai-gateway", "Cloudflare AI Gateway", configured = true),
    ProviderOption("openai", "OpenAI", configured = false),
    ProviderOption("zai", "Z.AI", configured = false),
)

private val PREVIEW_CLOUDFLARE_PROMPTS = listOf(
    ProviderAuthPrompt("CLOUDFLARE_API_KEY", "Enter the Cloudflare API key", secret = true),
    ProviderAuthPrompt("CLOUDFLARE_ACCOUNT_ID", "Enter the Cloudflare account ID", secret = false),
)

private val PREVIEW_SELECTED_MODEL = SelectedModel(
    providerId = "zai",
    providerName = "Z.AI",
    modelId = "model-a",
    modelName = "Preview Model A",
)

@Composable
private fun PreviewChatScreen(
    uiState: ChatUiState,
    startKey: NavKey = ChatNavKey,
    extraKeys: List<NavKey> = emptyList(),
    authPrompts: (String) -> List<ProviderAuthPrompt> = { emptyList() },
) {
    AletheiaTheme {
        ChatScreen(
            uiState = uiState,
            backStack = rememberNavBackStack(startKey).apply { addAll(extraKeys) },
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onSaveModelSelection = { _, _ -> },
            onSaveProviderCredential = { _, _, _ -> },
            onRemoveProviderCredential = { },
            authPrompts = authPrompts,
            onRefreshProviderStatus = {},
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
            startKey = ProvidersNavKey,
            providerOptions = listOf(
                ProviderOption("zai", "Z.AI", configured = false),
                ProviderOption("cloudflare-ai-gateway", "Cloudflare AI Gateway", configured = false),
            ),
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
            selectedModel = PREVIEW_SELECTED_MODEL,
            configured = true,
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
            selectedModel = PREVIEW_SELECTED_MODEL,
            configured = true,
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
            selectedModel = PREVIEW_SELECTED_MODEL,
            configured = true,
        ),
        extraKeys = listOf(SettingsNavKey, ModelSettingsNavKey),
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenModelSettingsEmptyPreview() {
    PreviewChatScreen(
        uiState = ChatUiState(
            status = ChatStatus.NeedsConfiguration,
            startKey = ModelSettingsNavKey,
            providerOptions = PREVIEW_PROVIDER_OPTIONS,
            modelOptions = PREVIEW_MODEL_OPTIONS,
        ),
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenProvidersPreview() {
    PreviewChatScreen(
        uiState = ChatUiState(
            status = ChatStatus.Ready,
            providerOptions = PREVIEW_PROVIDER_OPTIONS,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModel = PREVIEW_SELECTED_MODEL,
            configured = true,
        ),
        extraKeys = listOf(SettingsNavKey, ProvidersNavKey),
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenProviderAuthPreview() {
    PreviewChatScreen(
        uiState = ChatUiState(
            status = ChatStatus.Ready,
            providerOptions = PREVIEW_PROVIDER_OPTIONS,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModel = PREVIEW_SELECTED_MODEL,
            configured = true,
        ),
        extraKeys = listOf(SettingsNavKey, ProvidersNavKey, ProviderAuthNavKey("cloudflare-ai-gateway")),
        authPrompts = { providerId ->
            if (providerId == "cloudflare-ai-gateway") PREVIEW_CLOUDFLARE_PROMPTS else emptyList()
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun ConversationContentThinkingPreview() {
    AletheiaTheme {
        ConversationContent(
            uiState = ChatUiState(
                status = ChatStatus.Ready,
                // Default collapsed; the explicit per-block overrides below
                // expand the first run only, exercising both states at once.
                showThinking = false,
                messages = listOf(
                    ChatMessage(
                        id = "m1",
                        role = ChatRole.User,
                        blocks = listOf(ChatBlock.Text("What is 2 + 2?")),
                    ),
                    ChatMessage(
                        id = "m2",
                        role = ChatRole.Assistant,
                        blocks = listOf(
                            ChatBlock.Thinking("The user asks a simple arithmetic question. *2 + 2* equals **4** — no tools needed."),
                            ChatBlock.Text("2 + 2 = **4**."),
                            ChatBlock.Thinking("Answered directly; offering the derivation seems unnecessary."),
                        ),
                    ),
                ),
            ),
            initialThinkingOverrides = mapOf("m2:0" to true),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenReadyStreamingPreview() {
    PreviewChatScreen(
        ChatUiState(
            status = ChatStatus.Ready,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModel = PREVIEW_SELECTED_MODEL,
            configured = true,
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
                ChatMessage(id = "m1", role = ChatRole.User, blocks = listOf(ChatBlock.Text("Hello there"))),
                ChatMessage(id = "m2", role = ChatRole.Assistant, blocks = listOf(ChatBlock.Text("Hi! How can I help?"))),
            ),
            streamingMessage = ChatMessage(id = "streaming-1", role = ChatRole.Assistant, blocks = listOf(ChatBlock.Text("Sure, "))),
            isStreaming = true,
        ),
    )
}
