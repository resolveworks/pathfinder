package works.resolve.distill.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.List
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
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
import works.resolve.distill.R
import works.resolve.distill.ai.auth.AuthEvent
import works.resolve.distill.ai.auth.AuthMethodInfo
import works.resolve.distill.ai.auth.AuthType
import works.resolve.distill.data.sessions.SessionSummary
import works.resolve.distill.ui.chat.markdown.MarkdownText
import works.resolve.distill.ui.theme.DistillTheme
import kotlinx.coroutines.launch

private const val STREAMING_PLACEHOLDER = "…"

/** HorizontalPager over the chat surface: page 0 = conversation, page 1 = session tree. */
private const val ChatPageIndex = 0
private const val TreePageIndex = 1
private const val ChatPagerPageCount = 2

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
        authMethods = viewModel::providerAuthMethods,
        onBeginProviderAuthLogin = viewModel::beginProviderAuthLogin,
        onSubmitAuthPrompt = viewModel::submitAuthPrompt,
        onCancelProviderAuthLogin = viewModel::cancelProviderAuthLogin,
        onRefreshProviderStatus = viewModel::refreshProviderStatus,
        onNewSession = viewModel::newSession,
        onSwitchSession = viewModel::switchSession,
        onToggleShowThinking = viewModel::setShowThinking,
        onNavigateTreeEntry = viewModel::navigateToTreeEntry,
        onTreeFilterChange = viewModel::setTreeFilter,
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
    authMethods: (providerId: String) -> List<AuthMethodInfo>,
    onBeginProviderAuthLogin: (providerId: String, method: AuthMethodInfo) -> Unit,
    onSubmitAuthPrompt: (answer: String) -> Unit,
    onCancelProviderAuthLogin: () -> Unit,
    onRefreshProviderStatus: () -> Unit,
    onNewSession: () -> Unit,
    onSwitchSession: (sessionId: String) -> Unit,
    onToggleShowThinking: (Boolean) -> Unit,
    onNavigateTreeEntry: (entryId: String) -> Unit,
    onTreeFilterChange: (TreeFilter) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState { ChatPagerPageCount }
    // Reading currentPage keeps the top bar and composer in sync with swipes.
    val onTreePage = pagerState.currentPage == TreePageIndex

    // Back handling: while the pager shows the tree page, the system back
    // gesture returns to the chat page instead of leaving the Chat root
    // (BackHandler wins over Nav3's onBack while enabled).
    BackHandler(enabled = onTreePage) {
        scope.launch { pagerState.animateScrollToPage(ChatPageIndex) }
    }

    // Session switch: land on the chat page. Also covers the initial state
    // after init (activeSessionId goes null -> real id).
    LaunchedEffect(uiState.activeSessionId) {
        pagerState.scrollToPage(ChatPageIndex)
    }

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
    // Pager navigation helpers, explicitly () -> Unit (launch returns a Job).
    val openTreePage: () -> Unit = { scope.launch { pagerState.animateScrollToPage(TreePageIndex) } }
    val backToChatPage: () -> Unit = { scope.launch { pagerState.animateScrollToPage(ChatPageIndex) } }
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

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
        val showPager = showConversation && uiState.status == ChatStatus.Ready

        Scaffold(
            topBar = {
                if (uiState.status != ChatStatus.Loading) {
                    val canPop = backStack.size > 1
                    val chatRoot = topKey == ChatNavKey && uiState.status == ChatStatus.Ready
                    ChatTopBar(
                        title = when (topKey) {
                            SettingsNavKey -> stringResource(R.string.settings_title)
                            ModelSettingsNavKey -> stringResource(R.string.settings_model)
                            ProvidersNavKey -> stringResource(R.string.providers_title)
                            is ProviderAuthNavKey -> uiState.providerOptions
                                .firstOrNull { it.id == topKey.providerId }?.name
                                ?: stringResource(R.string.providers_title)
                            else -> stringResource(
                                if (onTreePage) R.string.tree_title else R.string.chat_title,
                            )
                        },
                        // The drawer belongs to the Chat root; nested
                        // destinations navigate up instead. A forced root
                        // (unconfigured app) is a dead end: no Up arrow. On
                        // the tree page the menu icon is replaced by a back
                        // arrow returning to the chat page.
                        onOpenDrawer = if (topKey == ChatNavKey &&
                            uiState.status == ChatStatus.Ready &&
                            !onTreePage
                        ) {
                            openDrawer
                        } else {
                            null
                        },
                        onBack = when {
                            chatRoot && onTreePage -> backToChatPage
                            canPop -> popBackStack
                            else -> null
                        },
                        actions = if (chatRoot && !onTreePage) {
                            {
                                IconButton(onClick = openTreePage) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.List,
                                        contentDescription = stringResource(R.string.tree_open),
                                    )
                                }
                            }
                        } else {
                            {}
                        },
                    )
                }
            },
            bottomBar = {
                if (showConversation && !onTreePage) {
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
                            entry<ChatNavKey> {
                                if (showPager) {
                                    ConversationPager(
                                        uiState = uiState,
                                        pagerState = pagerState,
                                        onNavigateTreeEntry = onNavigateTreeEntry,
                                        onTreeFilterChange = onTreeFilterChange,
                                    )
                                } else {
                                    ConversationContent(uiState = uiState)
                                }
                            }
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
                                    ProviderAuthEntry(
                                        provider = option,
                                        flow = uiState.authFlow?.takeIf { it.providerId == key.providerId },
                                        prompts = authPrompts(key.providerId),
                                        methods = authMethods(key.providerId),
                                        onSave = { apiKeyInput, envInputs ->
                                            onSaveProviderCredential(key.providerId, apiKeyInput, envInputs)
                                        },
                                        onRemove = { onRemoveProviderCredential(key.providerId) },
                                        onBeginLogin = { method ->
                                            onBeginProviderAuthLogin(key.providerId, method)
                                        },
                                        onSubmitPrompt = onSubmitAuthPrompt,
                                        onCancelLogin = onCancelProviderAuthLogin,
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
    actions: @Composable RowScope.() -> Unit = {},
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
        actions = actions,
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

// ---- provider auth (pi's /login method selection and login dialog) ----

/**
 * The provider-auth screen: routes between pi's three login surfaces — the
 * authentication-method selector (account/subscription vs API key, shown
 * only when the provider offers more than one method), the all-fields
 * API-key form (a sole API-key method goes straight there), and the
 * interactive login flow (a sole OAuth method starts immediately). While a
 * login flow for this provider is in flight, it replaces whatever surface
 * was showing (pi's login dialog replaces the editor).
 */
@Composable
private fun ProviderAuthEntry(
    provider: ProviderOption,
    flow: ProviderAuthFlow?,
    prompts: List<ProviderAuthPrompt>,
    methods: List<AuthMethodInfo>,
    onSave: (apiKeyInput: String, envInputs: Map<String, String>) -> Unit,
    onRemove: () -> Unit,
    onBeginLogin: (method: AuthMethodInfo) -> Unit,
    onSubmitPrompt: (answer: String) -> Unit,
    onCancelLogin: () -> Unit,
    onClose: () -> Unit,
) {
    // System back during an active flow cancels the login first (pi's
    // dialog escape); otherwise back pops the screen as usual.
    BackHandler(enabled = flow != null) { onCancelLogin() }

    if (flow != null) {
        AuthFlowContent(
            flow = flow,
            onSubmit = onSubmitPrompt,
            onCancel = onCancelLogin,
        )
        return
    }

    var showApiKeyForm by remember(provider.id) {
        mutableStateOf(providerAuthScreenMode(methods) == ProviderAuthScreenMode.API_KEY_FORM)
    }
    when (providerAuthScreenMode(methods)) {
        ProviderAuthScreenMode.API_KEY_FORM -> ProviderAuthContent(
            provider = provider,
            prompts = prompts,
            onSave = onSave,
            onRemove = onRemove,
            onClose = onClose,
        )
        ProviderAuthScreenMode.METHOD_CHOICE -> if (showApiKeyForm) {
            ProviderAuthContent(
                provider = provider,
                prompts = prompts,
                onSave = onSave,
                onRemove = onRemove,
                onClose = onClose,
            )
        } else {
            AuthMethodSelectorContent(
                providerName = provider.name,
                methods = methods,
                onSelect = { method ->
                    if (method.type == AuthType.API_KEY) {
                        showApiKeyForm = true
                    } else {
                        onBeginLogin(method)
                    }
                },
            )
        }
        ProviderAuthScreenMode.START_OAUTH -> {
            val method = methods.first()
            // Sole account method: start immediately (pi's startProviderLogin
            // opens the login dialog without a selector step).
            LaunchedEffect(provider.id) { onBeginLogin(method) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.auth_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCancelLogin) {
                    Text(stringResource(R.string.action_cancel_sign_in))
                }
            }
        }
        ProviderAuthScreenMode.NO_METHODS -> Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.auth_no_methods),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Authentication-method selector (pi's auth-type selector): one row per
 * offered method, labeled with the method's own label (the catalog label
 * or the OAuth login label) and supporting text distinguishing
 * account/subscription sign-in from an API key.
 */
@Composable
private fun AuthMethodSelectorContent(
    providerName: String,
    methods: List<AuthMethodInfo>,
    onSelect: (method: AuthMethodInfo) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.auth_method_title, providerName),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(16.dp))
        methods.forEach { method ->
            ListItem(
                headlineContent = { Text(method.label) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (method.isSubscription) R.string.auth_method_account else R.string.auth_method_api_key,
                        ),
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable { onSelect(method) },
            )
            HorizontalDivider()
        }
    }
}

/**
 * Interactive login flow (pi's login dialog): ordered progress/info events
 * and the pending prompt, when one is suspended. Links, auth URLs, and the
 * device verification URI open only through explicit user-triggered
 * buttons ([LocalUriHandler.openUri]) — nothing auto-launches. Text/Secret/
 * ManualCode answers live in ephemeral `remember` state only (never
 * `rememberSaveable`) and cross straight back into the suspended prompt.
 */
@Composable
private fun AuthFlowContent(
    flow: ProviderAuthFlow,
    onSubmit: (answer: String) -> Unit,
    onCancel: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (flow.pendingPrompt == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.auth_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        flow.events.forEach { event ->
            AuthEventItem(event = event, onOpenUri = { url -> uriHandler.openUri(url) })
        }
        flow.pendingPrompt?.let { prompt ->
            AuthPromptItem(prompt = prompt, onSubmit = onSubmit, onCancel = onCancel)
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel_sign_in))
        }
    }
}

/** One login event: info text with links, auth URL, device code, or progress. */
@Composable
private fun AuthEventItem(
    event: AuthEvent,
    onOpenUri: (url: String) -> Unit,
) {
    when (event) {
        is AuthEvent.Info -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(event.message, style = MaterialTheme.typography.bodyMedium)
                event.links.forEach { link ->
                    FilledTonalButton(onClick = { onOpenUri(link.url) }) {
                        Text(link.label ?: link.url)
                    }
                }
            }
        }
        is AuthEvent.AuthUrl -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = event.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                event.instructions?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { onOpenUri(event.url) }) {
                    Text(stringResource(R.string.auth_open_url))
                }
            }
        }
        is AuthEvent.DeviceCode -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = event.verificationUri,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = stringResource(R.string.auth_user_code, event.userCode),
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = { onOpenUri(event.verificationUri) }) {
                    Text(stringResource(R.string.auth_open_url))
                }
            }
        }
        is AuthEvent.Progress -> Text(
            text = event.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The pending prompt: a selection list (submitting an option id) or an
 * ephemeral single-line input (Text/Secret/ManualCode). The answer never
 * enters saved or hoisted state — it is passed straight to [onSubmit].
 */
@Composable
private fun AuthPromptItem(
    prompt: PendingAuthPrompt,
    onSubmit: (answer: String) -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(prompt.message, style = MaterialTheme.typography.bodyLarge)
        prompt.placeholder?.let {
            Text(
                text = stringResource(R.string.auth_prompt_placeholder, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (prompt.kind == AuthPromptKind.SELECT) {
            prompt.options.forEach { option ->
                ListItem(
                    headlineContent = { Text(option.label) },
                    supportingContent = option.description?.let { desc -> { Text(desc) } },
                    modifier = Modifier.clickable { onSubmit(option.id) },
                )
                HorizontalDivider()
            }
        } else {
            // Ephemeral, keyed by the prompt itself so a new prompt resets
            // the field; never rememberSaveable (no process-death retention).
            var answer by remember(prompt.message) { mutableStateOf("") }
            val secret = prompt.kind == AuthPromptKind.SECRET
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                label = { Text(prompt.message) },
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit(answer) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSubmit(answer) }) {
                    Text(stringResource(R.string.action_submit))
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

// ---- conversation ----

/**
 * Two-page swipeable chat surface: page 0 is the conversation, page 1 the
 * session-tree panel ([TreePanel] over [ChatUiState.treeRows]). The drawer
 * keeps its stock behavior (built-in edge-swipe-to-open + menu button); only
 * the pager's own gestures handle page swiping.
 */
@Composable
private fun ConversationPager(
    uiState: ChatUiState,
    pagerState: PagerState,
    onNavigateTreeEntry: (entryId: String) -> Unit,
    onTreeFilterChange: (TreeFilter) -> Unit,
) {
    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
        when (page) {
            TreePageIndex -> TreePanel(
                rows = uiState.treeRows,
                filter = uiState.treeFilter,
                onFilterChange = onTreeFilterChange,
                onNavigate = onNavigateTreeEntry,
                modifier = Modifier.fillMaxSize(),
            )
            else -> ConversationContent(uiState = uiState)
        }
    }
}

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

    // A reversed lazy list makes index 0 the bottom of the viewport. Reset to
    // that valid index whenever a session opens, a message is added, or the
    // streaming item grows. Including activeSessionId matters when switching
    // between transcripts that happen to contain the same number of messages.
    LaunchedEffect(uiState.activeSessionId, messageCount, streamingId, streamingLength) {
        if (messageCount > 0 || streamingId != null) listState.requestScrollToItem(0)
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
        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier.fillMaxSize(),
        ) {
            // reverseLayout places the first item at the bottom, so emit the
            // newest item first while preserving chronological visual order.
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
            items(uiState.messages.asReversed(), key = ChatMessage::id) { message ->
                MessageItem(
                    message = message,
                    showThinking = uiState.showThinking,
                    thinkingOverrides = thinkingOverrides,
                )
                HorizontalDivider()
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

private val PREVIEW_AUTH_METHODS = listOf(
    AuthMethodInfo(works.resolve.distill.ai.auth.AuthType.OAUTH, "Sign in with a Z.AI account", isSubscription = true),
    AuthMethodInfo(works.resolve.distill.ai.auth.AuthType.API_KEY, "Z.AI API key", isSubscription = false),
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
    authMethods: (String) -> List<AuthMethodInfo> = { emptyList() },
) {
    DistillTheme {
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
            authMethods = authMethods,
            onBeginProviderAuthLogin = { _, _ -> },
            onSubmitAuthPrompt = { },
            onCancelProviderAuthLogin = { },
            onRefreshProviderStatus = {},
            onNewSession = {},
            onSwitchSession = {},
            onToggleShowThinking = {},
            onNavigateTreeEntry = {},
            onTreeFilterChange = {},
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
        authMethods = { providerId ->
            if (providerId == "cloudflare-ai-gateway") {
                listOf(AuthMethodInfo(works.resolve.distill.ai.auth.AuthType.API_KEY, "Cloudflare API key", isSubscription = false))
            } else {
                emptyList()
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenAuthMethodChoicePreview() {
    PreviewChatScreen(
        uiState = ChatUiState(
            status = ChatStatus.Ready,
            providerOptions = PREVIEW_PROVIDER_OPTIONS,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModel = PREVIEW_SELECTED_MODEL,
            configured = true,
        ),
        extraKeys = listOf(SettingsNavKey, ProvidersNavKey, ProviderAuthNavKey("zai")),
        authPrompts = { providerId ->
            if (providerId == "zai") {
                listOf(ProviderAuthPrompt("ZAI_API_KEY", "Enter Z.AI API key", secret = true))
            } else {
                emptyList()
            }
        },
        authMethods = { providerId -> if (providerId == "zai") PREVIEW_AUTH_METHODS else emptyList() },
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenAuthFlowPreview() {
    PreviewChatScreen(
        uiState = ChatUiState(
            status = ChatStatus.Ready,
            providerOptions = PREVIEW_PROVIDER_OPTIONS,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModel = PREVIEW_SELECTED_MODEL,
            configured = true,
            authFlow = ProviderAuthFlow(
                providerId = "zai",
                method = PREVIEW_AUTH_METHODS.first(),
                events = listOf(
                    AuthEvent.Info("Open the link and sign in", emptyList()),
                    AuthEvent.AuthUrl("https://auth.example.invalid/authorize", "Approve the request"),
                    AuthEvent.DeviceCode("ABCD-1234", "https://verify.example.invalid/device"),
                    AuthEvent.Progress("Waiting for approval"),
                ),
                pendingPrompt = PendingAuthPrompt(
                    kind = AuthPromptKind.MANUAL_CODE,
                    message = "Enter the code shown in the browser",
                ),
            ),
        ),
        extraKeys = listOf(SettingsNavKey, ProvidersNavKey, ProviderAuthNavKey("zai")),
        authMethods = { providerId -> if (providerId == "zai") PREVIEW_AUTH_METHODS else emptyList() },
    )
}

@Preview(showBackground = true)
@Composable
private fun ConversationContentThinkingPreview() {
    DistillTheme {
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
private fun ChatScreenPagerPreview() {
    PreviewChatScreen(
        ChatUiState(
            status = ChatStatus.Ready,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModel = PREVIEW_SELECTED_MODEL,
            configured = true,
            activeSessionId = "s1",
            sessionSummaries = listOf(
                SessionSummary(id = "s1", title = "Preview chat", createdAt = 0L, updatedAt = 0L, messageCount = 1),
            ),
            messages = listOf(
                ChatMessage(id = "m1", role = ChatRole.User, blocks = listOf(ChatBlock.Text("Hello there"))),
            ),
        ),
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
                ChatMessage(id = "m1", role = ChatRole.User, blocks = listOf(ChatBlock.Text("Hello there"))),
                ChatMessage(id = "m2", role = ChatRole.Assistant, blocks = listOf(ChatBlock.Text("Hi! How can I help?"))),
            ),
            streamingMessage = ChatMessage(id = "streaming-1", role = ChatRole.Assistant, blocks = listOf(ChatBlock.Text("Sure, "))),
            isStreaming = true,
        ),
    )
}
