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
    onSaveModelSelection: (providerId: String, modelId: String, baseUrl: String?) -> Unit,
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
 * configured providers only, plus the base-URL override for the local
 * selection. Picking a row only changes local state; Save commits it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSettingsContent(
    uiState: ChatUiState,
    onSave: (providerId: String, modelId: String, baseUrl: String?) -> Unit,
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
    var baseUrl by remember(uiState.selectedModel) {
        mutableStateOf(uiState.selectedModel?.baseUrlOverride.orEmpty())
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
        if (selection != null) {
            // Advanced disclosure keeps the base-URL override out of sight until
            // needed; the local value survives collapsing so Save keeps it.
            var advancedExpanded by remember(uiState.selectedModel) {
                mutableStateOf(!uiState.selectedModel?.baseUrlOverride.isNullOrBlank())
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.model_settings_advanced)) },
                trailingContent = {
                    Icon(
                        imageVector = if (advancedExpanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = stringResource(
                            if (advancedExpanded) R.string.model_settings_advanced_collapse
                            else R.string.model_settings_advanced_expand,
                        ),
                    )
                },
                modifier = Modifier.clickable { advancedExpanded = !advancedExpanded },
            )
            if (advancedExpanded) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.configuration_base_url)) },
                    placeholder = {
                        Text(
                            selection?.defaultBaseUrl?.takeIf { it.isNotEmpty() }
                                ?: stringResource(R.string.configuration_base_url_hint),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { selection?.let { onSave(it.providerId, it.modelId, baseUrl) } },
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
 * process death or recomposition-surviving state, never logged, and cleared
 * on submit. Blank secret input keeps the stored key.
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
                supportingText = if (isSecret && provider.configured) {
                    { Text(stringResource(R.string.configuration_api_key_keep_hint)) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // Stacked so narrow widths never put Save, Cancel, and the
        // destructive Forget action in one horizontal row.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    onSave(apiKeyInput, envInputs.toMap())
                    apiKeyInput = ""
                    envInputs.clear()
                },
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
        // pi renders user markdown literally (markers preserved, not parsed);
        // the MVP equivalent here is plain text, so only the assistant path
        // goes through MarkdownText.
        headlineContent = {
            if (message.role == ChatRole.Assistant) {
                MarkdownText(markdown = message.text)
            } else {
                Text(message.text)
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
        defaultBaseUrl = "https://api.example.invalid/v4",
    ),
    ModelOption(
        providerId = "zai",
        providerName = "Z.AI",
        modelId = "model-b",
        name = "Preview Model B",
        defaultBaseUrl = "https://api.example.invalid/v4",
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
    baseUrlOverride = null,
    defaultBaseUrl = "https://api.example.invalid/v4",
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
            onSaveModelSelection = { _, _, _ -> },
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
            startKey = SettingsNavKey,
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
            startKey = SettingsNavKey,
            providerOptions = PREVIEW_PROVIDER_OPTIONS,
        ),
        extraKeys = listOf(ModelSettingsNavKey),
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
                ChatMessage(id = "m1", role = ChatRole.User, text = "Hello there"),
                ChatMessage(id = "m2", role = ChatRole.Assistant, text = "Hi! How can I help?"),
            ),
            streamingMessage = ChatMessage(id = "streaming-1", role = ChatRole.Assistant, text = "Sure, "),
            isStreaming = true,
        ),
    )
}
