package works.resolve.pathfinder.ui.chat

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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.ai.core.ModelThinkingLevel
import works.resolve.pathfinder.data.sessions.SessionSummary
import works.resolve.pathfinder.ui.openInCustomTab
import works.resolve.pathfinder.ui.chat.markdown.MarkdownText
import works.resolve.pathfinder.ui.theme.PathfinderTheme
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
        onSelectModel = viewModel::selectModel,
        onSetStartupDefault = viewModel::saveStartupDefault,
        onToggleModelScope = viewModel::toggleModelScope,
        onSelectThinkingLevel = viewModel::selectThinkingLevel,
        onSetDefaultThinkingLevel = viewModel::setThinkingLevelDefault,
        onSaveProviderCredential = viewModel::saveProviderCredential,
        onRemoveProviderCredential = viewModel::removeProviderCredential,
        authPrompts = viewModel::providerAuthPrompts,
        authMethods = viewModel::providerAuthMethods,
        onBeginProviderAuthLogin = viewModel::beginProviderAuthLogin,
        onSubmitAuthPrompt = viewModel::submitAuthPrompt,
        onCancelProviderAuthLogin = viewModel::cancelProviderAuthLogin,
        onRefreshProviderStatus = viewModel::refreshProviderStatus,
        // Search-provider integration (later branch) wires these to
        // viewModel::saveSearchProviderCredential,
        // viewModel::removeSearchProviderCredential,
        // viewModel::refreshSearchProviderStatus, and
        // viewModel::searchProviderAuthPrompts. Those ViewModel methods do
        // not exist yet on this isolated branch, so the route passes
        // placeholders to keep it compiling; no stubs are added to the
        // ViewModel itself.
        onSaveSearchProviderCredential = { _, _ -> },
        onRemoveSearchProviderCredential = { _ -> },
        onRefreshSearchProviderStatus = { },
        searchAuthPrompts = { emptyList() },
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
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onSetStartupDefault: (providerId: String, modelId: String) -> Unit,
    onToggleModelScope: (providerId: String, modelId: String, checked: Boolean) -> Unit,
    onSelectThinkingLevel: (ModelThinkingLevel) -> Unit,
    onSetDefaultThinkingLevel: (ModelThinkingLevel) -> Unit,
    onSaveProviderCredential: (providerId: String, apiKeyInput: String, envInputs: Map<String, String>) -> Unit,
    onRemoveProviderCredential: (providerId: String) -> Unit,
    authPrompts: (providerId: String) -> List<ProviderAuthPrompt>,
    authMethods: (providerId: String) -> List<AuthMethodInfo>,
    onBeginProviderAuthLogin: (providerId: String, method: AuthMethodInfo) -> Unit,
    onSubmitAuthPrompt: (answer: String) -> Unit,
    onCancelProviderAuthLogin: () -> Unit,
    onRefreshProviderStatus: () -> Unit,
    onSaveSearchProviderCredential: (providerId: String, apiKeyInput: String) -> Unit,
    onRemoveSearchProviderCredential: (providerId: String) -> Unit,
    onRefreshSearchProviderStatus: () -> Unit,
    searchAuthPrompts: (providerId: String) -> List<ProviderAuthPrompt>,
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
    // Reading currentPage keeps the top bar in sync with swipes.
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
    // single-entry root, which is never popped; a
    // Ready-state save bumps only this epoch, returning the user from
    // ProviderAuth to Providers. A failed or incomplete save never bumps
    // this epoch, so the form and its typed inputs stay intact.
    LaunchedEffect(uiState.credentialSuccessEpoch) {
        if (backStack.size > 1 && backStack.lastOrNull() is ProviderAuthNavKey) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    LaunchedEffect(uiState.searchCredentialSuccessEpoch) {
        if (backStack.size > 1 && backStack.lastOrNull() is SearchProviderAuthNavKey) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    val pushSettings: () -> Unit = { backStack.add(SettingsNavKey) }
    val pushModels: () -> Unit = { backStack.add(ModelsNavKey) }
    val pushDefaultModel: () -> Unit = { backStack.add(DefaultModelNavKey) }
    val pushDefaultThinking: () -> Unit = { backStack.add(DefaultThinkingNavKey) }
    val pushProviders: () -> Unit = { backStack.add(ProvidersNavKey) }
    val pushProviderAuth: (String) -> Unit = { backStack.add(ProviderAuthNavKey(it)) }
    val pushSearchProviders: () -> Unit = { backStack.add(SearchProvidersNavKey) }
    val pushSearchProviderAuth: (String) -> Unit = { backStack.add(SearchProviderAuthNavKey(it)) }
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
        val showPager = topKey == ChatNavKey && uiState.status == ChatStatus.Ready

        Scaffold(
            topBar = {
                if (uiState.status != ChatStatus.Loading) {
                    val canPop = backStack.size > 1
                    val chatRoot = topKey == ChatNavKey && uiState.status == ChatStatus.Ready
                    ChatTopBar(
                        title = when (topKey) {
                            SettingsNavKey -> stringResource(R.string.settings_title)
                            ModelsNavKey -> stringResource(R.string.settings_model)
                            DefaultModelNavKey -> stringResource(R.string.settings_default_model)
                            DefaultThinkingNavKey -> stringResource(R.string.settings_default_thinking)
                            ProvidersNavKey -> stringResource(R.string.providers_title)
                            SearchProvidersNavKey -> stringResource(R.string.search_providers_title)
                            is SearchProviderAuthNavKey -> uiState.searchProviderOptions
                                .firstOrNull { it.id == topKey.providerId }?.name
                                ?: stringResource(R.string.search_providers_title)
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
                        onOpenProviders = pushProviders,
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
                                        onDraftChange = onDraftChange,
                                        onSend = onSend,
                                        onStop = onStop,
                                        onSelectModel = onSelectModel,
                                        onSelectThinkingLevel = onSelectThinkingLevel,
                                        onNavigateTreeEntry = onNavigateTreeEntry,
                                        onTreeFilterChange = onTreeFilterChange,
                                    )
                                } else {
                                    ChatSurface(
                                        uiState = uiState,
                                        onDraftChange = onDraftChange,
                                        onSend = onSend,
                                        onStop = onStop,
                                        onSelectModel = onSelectModel,
                                        onSelectThinkingLevel = onSelectThinkingLevel,
                                    )
                                }
                            }
                            entry<SettingsNavKey> {
                                SettingsContent(
                                    defaultModel = uiState.defaultModel,
                                    defaultThinkingLevel = uiState.defaultThinkingLevel,
                                    showDefaultThinkingRow = uiState.availableThinkingLevels.isNotEmpty(),
                                    showThinking = uiState.showThinking,
                                    onOpenDefaultModel = pushDefaultModel,
                                    onOpenDefaultThinking = pushDefaultThinking,
                                    onOpenModels = pushModels,
                                    onOpenProviders = pushProviders,
                                    onOpenSearchProviders = pushSearchProviders,
                                    onToggleShowThinking = onToggleShowThinking,
                                )
                            }
                            entry<DefaultModelNavKey> {
                                DefaultModelContent(
                                    modelOptions = uiState.modelOptions,
                                    defaultModel = uiState.defaultModel,
                                    onSetDefault = onSetStartupDefault,
                                    onOpenProviders = pushProviders,
                                )
                            }
                            entry<DefaultThinkingNavKey> {
                                DefaultThinkingLevelContent(
                                    availableLevels = uiState.availableThinkingLevels,
                                    defaultLevel = uiState.defaultThinkingLevel,
                                    onSetDefault = onSetDefaultThinkingLevel,
                                )
                            }
                            entry<ModelsNavKey> {
                                ModelsContent(
                                    modelOptions = uiState.modelOptions,
                                    enabledModels = uiState.enabledModels,
                                    onToggleScope = onToggleModelScope,
                                    onOpenProviders = pushProviders,
                                )
                            }
                            entry<ProvidersNavKey> {
                                ProvidersContent(
                                    providerOptions = uiState.providerOptions,
                                    onRefresh = onRefreshProviderStatus,
                                    onOpenProvider = pushProviderAuth,
                                )
                            }
                            entry<SearchProvidersNavKey> {
                                SearchProvidersContent(
                                    providerOptions = uiState.searchProviderOptions,
                                    onRefresh = onRefreshSearchProviderStatus,
                                    onOpenProvider = pushSearchProviderAuth,
                                )
                            }
                            entry<SearchProviderAuthNavKey> { key ->
                                val option = uiState.searchProviderOptions
                                    .firstOrNull { it.id == key.providerId }
                                if (option != null) {
                                    // Search providers offer only API-key auth, so
                                    // this reuses the plain all-fields form with the
                                    // search catalog's prompts; the form is API-key
                                    // only and env inputs are not forwarded.
                                    ProviderAuthContent(
                                        provider = option,
                                        prompts = searchAuthPrompts(key.providerId),
                                        onSave = { apiKeyInput, _ ->
                                            onSaveSearchProviderCredential(
                                                key.providerId,
                                                apiKeyInput,
                                            )
                                        },
                                        onRemove = { onRemoveSearchProviderCredential(key.providerId) },
                                        onClose = popBackStack,
                                    )
                                }
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
private fun FailedContent(error: String, onOpenProviders: () -> Unit) {
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
        Button(onClick = onOpenProviders) {
            Text(stringResource(R.string.action_configure))
        }
    }
}

/**
 * Settings root: a submenu listing. The default rows (Pixel "Default apps"
 * convention) show the persisted default's summary and push the dedicated
 * pickers; the scoped-models curator, the provider credential list, and the
 * display-preference toggle keep their own rows. The "Default thinking
 * level" row hides when the live session's model offers no levels (no
 * session or a non-reasoning model) — same condition as the chat's thinking
 * chip.
 */
@Composable
private fun SettingsContent(
    defaultModel: SelectedModel?,
    defaultThinkingLevel: ModelThinkingLevel?,
    showDefaultThinkingRow: Boolean,
    showThinking: Boolean,
    onOpenDefaultModel: () -> Unit,
    onOpenDefaultThinking: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSearchProviders: () -> Unit,
    onToggleShowThinking: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_default_model)) },
            supportingContent = {
                Text(defaultModel?.modelName ?: stringResource(R.string.settings_not_set))
            },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onOpenDefaultModel),
        )
        if (showDefaultThinkingRow) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_default_thinking)) },
                supportingContent = {
                    Text(
                        defaultThinkingLevel
                            ?.let { thinkingLevelDescriptionText(it) }
                            ?: stringResource(R.string.settings_not_set),
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onOpenDefaultThinking),
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_model)) },
            supportingContent = { Text(stringResource(R.string.settings_model_scope_hint)) },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onOpenModels),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.providers_title)) },
            supportingContent = { Text(stringResource(R.string.settings_providers_hint)) },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onOpenProviders),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.search_providers_title)) },
            supportingContent = { Text(stringResource(R.string.search_providers_hint)) },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onOpenSearchProviders),
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
 * The startup-default model screen: a single-select radio list over every
 * model of configured providers (M3 single-choice list convention). Tapping
 * a row commits immediately — no confirm button — exactly like the Pixel
 * "Default apps" screens. This is the native-Android home of pi's picker
 * Ctrl+S persistence (pi itself has no settings-screen path for the
 * default); see [ModelPickerSheet] for the divergence note — setting the
 * default here does not switch the live session.
 */
@Composable
private fun DefaultModelContent(
    modelOptions: List<ModelOption>,
    defaultModel: SelectedModel?,
    onSetDefault: (providerId: String, modelId: String) -> Unit,
    onOpenProviders: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        if (modelOptions.isEmpty()) {
            Text(
                text = stringResource(R.string.models_empty_configured_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenProviders) {
                Text(stringResource(R.string.action_set_up_providers))
            }
        } else {
            LazyColumn {
                items(modelOptions, key = { "${it.providerId}/${it.modelId}" }) { option ->
                    val isDefault = defaultModel?.let {
                        option.providerId == it.providerId && option.modelId == it.modelId
                    } == true
                    ListItem(
                        headlineContent = { Text(option.name) },
                        supportingContent = { Text(option.providerName) },
                        trailingContent = {
                            RadioButton(selected = isDefault, onClick = null)
                        },
                        modifier = Modifier.clickable {
                            onSetDefault(option.providerId, option.modelId)
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * The default thinking-level screen: a commit-on-tap radio list (Pixel
 * "Default apps" convention) over the live session model's supported
 * levels — the native-Android home of pi's thinking-selector Ctrl+S
 * persistence. Tapping a row calls [ChatViewModel.setThinkingLevelDefault],
 * which applies the level to the live session first and persists after,
 * mirroring pi's Ctrl+S order. Unlike pi's single gesture, though, this is
 * a Settings screen, so "switch now" and "set default" remain separate
 * intents; see [ThinkingLevelPickerSheet] for the divergence note.
 */
@Composable
private fun DefaultThinkingLevelContent(
    availableLevels: List<ModelThinkingLevel>,
    defaultLevel: ModelThinkingLevel?,
    onSetDefault: (ModelThinkingLevel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        LazyColumn {
            items(availableLevels, key = { it.wire }) { level ->
                ListItem(
                    headlineContent = { Text(thinkingLevelLabel(level)) },
                    supportingContent = { Text(thinkingLevelDescriptionText(level)) },
                    trailingContent = {
                        RadioButton(selected = level == defaultLevel, onClick = null)
                    },
                    modifier = Modifier.clickable { onSetDefault(level) },
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * Models screen: the scoped-models curator (pi's /scoped-models). Rows are
 * every model of configured providers with a checkbox; an absent scope
 * (never curated) shows everything checked. Toggles persist immediately
 * as the ordered `enabledModels` list and only affect which models the
 * chat picker offers — never the running model.
 */
@Composable
private fun ModelsContent(
    modelOptions: List<ModelOption>,
    enabledModels: List<String>?,
    onToggleScope: (providerId: String, modelId: String, checked: Boolean) -> Unit,
    onOpenProviders: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filteredOptions = modelOptions.filter { option ->
        val q = query.trim()
        q.isEmpty() || option.name.contains(q, ignoreCase = true) ||
            option.modelId.contains(q, ignoreCase = true) ||
            option.providerName.contains(q, ignoreCase = true)
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
        if (modelOptions.isEmpty()) {
            Text(
                text = stringResource(R.string.models_empty_configured_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenProviders) {
                Text(stringResource(R.string.action_set_up_providers))
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filteredOptions, key = { "${it.providerId}/${it.modelId}" }) { option ->
                    val modelRef = "${option.providerId}/${option.modelId}"
                    val checked = enabledModels?.contains(modelRef) ?: true
                    ListItem(
                        headlineContent = { Text(option.name) },
                        supportingContent = { Text(option.providerName) },
                        trailingContent = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { onToggleScope(option.providerId, option.modelId, it) },
                            )
                        },
                        modifier = Modifier.clickable {
                            onToggleScope(option.providerId, option.modelId, !checked)
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Providers screen (pi's /login list): every catalog provider with live
 * configured/unconfigured status, filtered by name/id substring. Shares
 * [ProviderListContent] with the search-providers screen.
 */
@Composable
private fun ProvidersContent(
    providerOptions: List<ProviderOption>,
    onRefresh: () -> Unit,
    onOpenProvider: (providerId: String) -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    ProviderListContent(
        providerOptions = providerOptions,
        searchHint = stringResource(R.string.provider_search_hint),
        onOpenProvider = onOpenProvider,
    )
}

/**
 * Search-providers screen (Settings ▸ Search providers): the same list as
 * the providers screen over the search-provider catalog. API-key only — no
 * method selection or OAuth.
 */
@Composable
private fun SearchProvidersContent(
    providerOptions: List<ProviderOption>,
    onRefresh: () -> Unit,
    onOpenProvider: (providerId: String) -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    ProviderListContent(
        providerOptions = providerOptions,
        searchHint = stringResource(R.string.search_provider_search_hint),
        onOpenProvider = onOpenProvider,
    )
}

/**
 * Shared provider list: search field plus name-sorted rows with live
 * configured/unconfigured status, filtered by name/id substring.
 */
@Composable
private fun ProviderListContent(
    providerOptions: List<ProviderOption>,
    searchHint: String,
    onOpenProvider: (providerId: String) -> Unit,
) {
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
            label = { Text(searchHint) },
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
 * Android projection of pi's login dialog. Provider events and prompts keep
 * their upstream shapes and ordering, but the phone UI presents only the
 * current action: an explicit browser button, a provider-required device
 * code, or a real choice/text prompt. Terminal-oriented raw URLs, progress
 * transcripts, and the raced manual-code fallback are intentionally not
 * rendered here.
 */
@Composable
private fun AuthFlowContent(
    flow: ProviderAuthFlow,
    onSubmit: (answer: String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val browserEvent = flow.events.lastOrNull {
        it is AuthEvent.AuthUrl || it is AuthEvent.DeviceCode
    }
    val infoEvent = flow.events.filterIsInstance<AuthEvent.Info>().lastOrNull()
    val prompt = flow.pendingPrompt?.takeUnless { it.kind == AuthPromptKind.MANUAL_CODE }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when {
            browserEvent != null -> {
                AuthEventItem(event = browserEvent, onOpenUri = context::openInCustomTab)
                AuthWaitingIndicator()
            }
            prompt != null -> AuthPromptItem(prompt = prompt, onSubmit = onSubmit)
            infoEvent != null -> {
                AuthEventItem(event = infoEvent, onOpenUri = context::openInCustomTab)
                AuthWaitingIndicator()
            }
            else -> AuthWaitingIndicator()
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.action_cancel_sign_in))
        }
    }
}

@Composable
private fun AuthWaitingIndicator() {
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

/** One actionable login event, without exposing its raw URL. */
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
                        Text(link.label ?: stringResource(R.string.auth_more_info))
                    }
                }
            }
        }
        is AuthEvent.AuthUrl -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.auth_continue_browser),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { onOpenUri(event.url) }) {
                    Text(stringResource(R.string.auth_open_browser))
                }
            }
        }
        is AuthEvent.DeviceCode -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.auth_continue_browser),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.auth_user_code, event.userCode),
                    style = MaterialTheme.typography.titleMedium,
                )
                Button(onClick = { onOpenUri(event.verificationUri) }) {
                    Text(stringResource(R.string.auth_open_browser))
                }
            }
        }
        is AuthEvent.Progress -> Unit
    }
}

/**
 * A real login prompt: a selection list or an ephemeral single-line input.
 * ManualCode is a pi fallback for remote browsers and is filtered by
 * [AuthFlowContent] because Pathfinder's browser runs on the same device.
 */
@Composable
private fun AuthPromptItem(
    prompt: PendingAuthPrompt,
    onSubmit: (answer: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (prompt.kind == AuthPromptKind.SELECT) {
            Text(prompt.message, style = MaterialTheme.typography.bodyLarge)
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
                placeholder = { prompt.placeholder?.let { Text(it) } },
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit(answer) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onSubmit(answer) }) {
                Text(stringResource(R.string.action_submit))
            }
        }
    }
}

// ---- conversation ----

/**
 * Two-page swipeable chat surface: page 0 is the chat page ([ChatSurface]:
 * transcript plus composer with its status rows), page 1 the session-tree
 * panel ([TreePanel] over [ChatUiState.treeRows]). Each page owns its
 * bottom edge, so the composer swipes away with the conversation and the
 * tree gets the full height. The drawer keeps its stock behavior
 * (built-in edge-swipe-to-open + menu button); only the pager's own
 * gestures handle page swiping.
 */
@Composable
private fun ConversationPager(
    uiState: ChatUiState,
    pagerState: PagerState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onSelectThinkingLevel: (ModelThinkingLevel) -> Unit,
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
            else -> ChatSurface(
                uiState = uiState,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onStop = onStop,
                onSelectModel = onSelectModel,
                onSelectThinkingLevel = onSelectThinkingLevel,
            )
        }
    }
}

/**
 * The conversation page: [ConversationContent] above the composer column
 * (the model [SelectionBar], transient [RetryStatusRow]/[CompactingStatusRow]
 * rows, and the [Composer]). The composer column is page content, not
 * scaffold chrome — it moves with the chat page when swiping to the tree —
 * and owns its own navigation-bar and IME padding.
 */
@Composable
private fun ChatSurface(
    uiState: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onSelectThinkingLevel: (ModelThinkingLevel) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ConversationContent(
            uiState = uiState,
            modifier = Modifier.weight(1f),
        )
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding(),
        ) {
            SelectionBar(
                selectedModel = uiState.selectedModel,
                defaultModel = uiState.defaultModel,
                allOptions = uiState.modelOptions,
                scopedOptions = uiState.scopedModelOptions,
                scopeConfigured = !uiState.enabledModels.isNullOrEmpty(),
                onSelectModel = onSelectModel,
                thinkingLevel = uiState.thinkingLevel,
                availableThinkingLevels = uiState.availableThinkingLevels,
                defaultThinkingLevel = uiState.defaultThinkingLevel,
                onSelectThinkingLevel = onSelectThinkingLevel,
            )
            uiState.retryStatus?.let { retry ->
                RetryStatusRow(
                    attempt = retry.attempt,
                    maxAttempts = retry.maxAttempts,
                )
            }
            if (uiState.isCompacting) {
                CompactingStatusRow()
            }
            Composer(
                draft = uiState.draft,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onStop = onStop,
                canSend = uiState.canSend,
                isStreaming = uiState.isStreaming,
            )
        }
    }
}

/**
 * The selector row between the transcript and the composer (pi's /model and
 * /thinking bars). The model chip shows the live session model and opens the
 * [ModelPickerSheet]: rows over the scoped models by default (All view when
 * no scope is configured) with an All/Scoped toggle — pi's selector scope
 * toggle; the scope is what's offered, never a hard constraint. One tap
 * selects (pi's Enter); the sheet is purely ephemeral — setting the default
 * lives in Settings (pi's Ctrl+S adapted to the Android convention; see
 * [ModelPickerSheet]). Next to it, the thinking chip (pi's
 * footer thinking state, footer.ts:184-188 — shown only for reasoning
 * models, whose supported levels are never just OFF) shows the live thinking
 * level and opens the [ThinkingLevelPickerSheet] with the same ephemeral
 * pick semantics.
 */
@Composable
private fun SelectionBar(
    selectedModel: SelectedModel?,
    defaultModel: SelectedModel?,
    allOptions: List<ModelOption>,
    scopedOptions: List<ModelOption>,
    scopeConfigured: Boolean,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    thinkingLevel: ModelThinkingLevel?,
    availableThinkingLevels: List<ModelThinkingLevel>,
    defaultThinkingLevel: ModelThinkingLevel?,
    onSelectThinkingLevel: (ModelThinkingLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var thinkingSheetOpen by rememberSaveable { mutableStateOf(false) }
    // pi's footer condition `state.model?.reasoning`: a non-reasoning model's
    // only supported level is OFF, so >1 level means reasoning.
    val showThinkingChip = availableThinkingLevels.size > 1
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = { sheetOpen = true },
            label = {
                Text(
                    text = selectedModel?.modelName ?: stringResource(R.string.model_picker_empty),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
        if (showThinkingChip) {
            Spacer(Modifier.width(8.dp))
            AssistChip(
                onClick = { thinkingSheetOpen = true },
                label = {
                    // pi's footer label (footer.ts:185-187): "thinking off"
                    // at OFF, otherwise the bare level name.
                    val level = thinkingLevel ?: ModelThinkingLevel.OFF
                    Text(
                        text = if (level == ModelThinkingLevel.OFF) {
                            stringResource(R.string.thinking_chip_off)
                        } else {
                            thinkingLevelLabel(level)
                        },
                        maxLines = 1,
                    )
                },
            )
        }
    }
    if (sheetOpen) {
        ModelPickerSheet(
            allOptions = allOptions,
            scopedOptions = scopedOptions,
            scopeConfigured = scopeConfigured,
            selectedModel = selectedModel,
            defaultModel = defaultModel,
            onSelect = { option ->
                sheetOpen = false
                onSelectModel(option.providerId, option.modelId)
            },
            onDismiss = { sheetOpen = false },
        )
    }
    if (thinkingSheetOpen) {
        ThinkingLevelPickerSheet(
            availableLevels = availableThinkingLevels,
            selectedLevel = thinkingLevel ?: ModelThinkingLevel.OFF,
            defaultLevel = defaultThinkingLevel,
            onSelect = { level ->
                thinkingSheetOpen = false
                onSelectThinkingLevel(level)
            },
            onDismiss = { thinkingSheetOpen = false },
        )
    }
}

/** pi's level display names (lowercase wire vocabulary, selector labels). */
@Composable
private fun thinkingLevelLabel(level: ModelThinkingLevel): String = when (level) {
    ModelThinkingLevel.OFF -> stringResource(R.string.thinking_level_off)
    ModelThinkingLevel.MINIMAL -> stringResource(R.string.thinking_level_minimal)
    ModelThinkingLevel.LOW -> stringResource(R.string.thinking_level_low)
    ModelThinkingLevel.MEDIUM -> stringResource(R.string.thinking_level_medium)
    ModelThinkingLevel.HIGH -> stringResource(R.string.thinking_level_high)
    ModelThinkingLevel.XHIGH -> stringResource(R.string.thinking_level_xhigh)
    ModelThinkingLevel.MAX -> stringResource(R.string.thinking_level_max)
}

/** pi's plain level description (thinking-selector.ts:26-34 LEVEL_DESCRIPTIONS). */
@Composable
private fun thinkingLevelDescriptionText(level: ModelThinkingLevel): String = when (level) {
    ModelThinkingLevel.OFF -> stringResource(R.string.thinking_level_desc_off)
    ModelThinkingLevel.MINIMAL -> stringResource(R.string.thinking_level_desc_minimal)
    ModelThinkingLevel.LOW -> stringResource(R.string.thinking_level_desc_low)
    ModelThinkingLevel.MEDIUM -> stringResource(R.string.thinking_level_desc_medium)
    ModelThinkingLevel.HIGH -> stringResource(R.string.thinking_level_desc_high)
    ModelThinkingLevel.XHIGH -> stringResource(R.string.thinking_level_desc_xhigh)
    ModelThinkingLevel.MAX -> stringResource(R.string.thinking_level_desc_max)
}

/**
 * One level's row description: pi's LEVEL_DESCRIPTIONS
 * (thinking-selector.ts:26-34) with the "· default" suffix on the effective
 * default — pi marks `getDefaultThinkingLevel() ?? DEFAULT_THINKING_LEVEL`,
 * so an unset default marks "medium" (interactive-mode.ts:4817).
 */
@Composable
private fun thinkingLevelDescription(level: ModelThinkingLevel, defaultLevel: ModelThinkingLevel?): String {
    val description = thinkingLevelDescriptionText(level)
    return if (level == (defaultLevel ?: ModelThinkingLevel.MEDIUM)) {
        stringResource(R.string.thinking_level_default_marker, description)
    } else {
        description
    }
}

/**
 * The thinking-level picker sheet (pi's thinking selector,
 * ThinkingSelectorComponent): one row per level the model supports with the
 * current level marked by a check and the default marked in the row
 * description ("· default", thinking-selector.ts). A tap picks (pi's Enter)
 * and closes the sheet.
 *
 * Divergence from pi (deliberate, narrow): pi's Ctrl+S applies the
 * highlighted row AND persists it as the default in one gesture
 * (thinking-selector.ts keybinding); Pathfinder's sheet is purely
 * ephemeral — default-setting lives in Settings ▸ Default thinking level
 * (Android's Settings-managed-defaults convention; pi has no
 * settings-screen path for the default). "Use it now AND default it"
 * therefore takes two steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingLevelPickerSheet(
    availableLevels: List<ModelThinkingLevel>,
    selectedLevel: ModelThinkingLevel,
    defaultLevel: ModelThinkingLevel?,
    onSelect: (ModelThinkingLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 480.dp),
            ) {
                items(availableLevels, key = { it.wire }) { level ->
                    ListItem(
                        headlineContent = { Text(thinkingLevelLabel(level)) },
                        supportingContent = { Text(thinkingLevelDescription(level, defaultLevel)) },
                        trailingContent = if (level == selectedLevel) {
                            {
                                // pi's thinking selector marks the current
                                // level with a check.
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.model_selected),
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.clickable { onSelect(level) },
                    )
                }
            }
        }
    }
}

/**
 * The model picker sheet (pi's /model selector): a list with the current
 * selection marked by a check, the persisted default badged " · default",
 * and pi's sortModels order (model-selector.ts:227-235) — current model
 * first, default second, then the existing provider/model display order.
 * Shows the Scoped view when a scope is configured (All otherwise), with
 * an All/Scoped toggle — pi's scope toggle; the All view keeps the scope a
 * soft constraint.
 *
 * Divergence from pi (deliberate, narrow): pi's Ctrl+S applies the
 * highlighted row AND persists it as the default in one gesture
 * (model-selector.ts keybinding); Pathfinder's sheet is purely ephemeral —
 * default-setting lives in Settings ▸ Default model (Android's
 * Settings-managed-defaults convention; pi has no settings-screen path for
 * the default). "Use it now AND default it" therefore takes two steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    allOptions: List<ModelOption>,
    scopedOptions: List<ModelOption>,
    scopeConfigured: Boolean,
    selectedModel: SelectedModel?,
    defaultModel: SelectedModel?,
    onSelect: (ModelOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var allView by rememberSaveable { mutableStateOf(!scopeConfigured) }
    val options = if (allView) allOptions else scopedOptions
    // pi's sortModels (model-selector.ts:227-235): current model first,
    // default model second; the stable sort keeps the option list's
    // provider/model display order for everything else (pi compares
    // provider names there).
    val isCurrent: (ModelOption) -> Boolean = { option ->
        selectedModel?.let { option.providerId == it.providerId && option.modelId == it.modelId } == true
    }
    val isDefault: (ModelOption) -> Boolean = { option ->
        defaultModel?.let { option.providerId == it.providerId && option.modelId == it.modelId } == true
    }
    val sortedOptions = options.sortedWith(
        compareByDescending<ModelOption> { isCurrent(it) }.thenByDescending { isDefault(it) },
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            if (scopeConfigured) {
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = !allView,
                        onClick = { allView = false },
                        label = { Text(stringResource(R.string.model_picker_scoped)) },
                    )
                    FilterChip(
                        selected = allView,
                        onClick = { allView = true },
                        label = { Text(stringResource(R.string.model_picker_all)) },
                    )
                }
            }
            if (sortedOptions.isEmpty()) {
                Text(
                    text = stringResource(R.string.models_scope_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 480.dp),
                ) {
                    items(sortedOptions, key = { "${it.providerId}/${it.modelId}" }) { option ->
                        val isSelected = isCurrent(option)
                        ListItem(
                            headlineContent = { Text(option.name) },
                            supportingContent = {
                                // pi appends the muted " · default" badge to
                                // the default row's provider (model-selector.ts:316-323).
                                Text(
                                    text = if (isDefault(option)) {
                                        stringResource(R.string.model_default_marker, option.providerName)
                                    } else {
                                        option.providerName
                                    },
                                )
                            },
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
                            modifier = Modifier.clickable { onSelect(option) },
                        )
                    }
                }
            }
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
    modifier: Modifier = Modifier,
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
            is ChatBlock.ToolCall -> 0
        }
    }

    // A reversed lazy list makes index 0 the bottom of the viewport. Reset to
    // that valid index whenever a session opens, a message is added, or the
    // streaming item grows. Including activeSessionId matters when switching
    // between transcripts that happen to contain the same number of messages.
    LaunchedEffect(uiState.activeSessionId, messageCount, uiState.pendingTools.size, streamingId, streamingLength) {
        if (messageCount > 0 || uiState.pendingTools.isNotEmpty() || streamingId != null) {
            listState.requestScrollToItem(0)
        }
    }

    // Per-block expanded overrides (ephemeral view state keyed by stable
    // "messageId:blockIndex"): a block the user never tapped keeps following
    // the persisted showThinking default; a tapped block locks in the user's
    // choice and outlives later changes to the setting.
    val thinkingOverrides = rememberSaveable(saver = thinkingOverridesSaver()) {
        mutableStateMapOf<String, Boolean>().apply { putAll(initialThinkingOverrides) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (messageCount == 0 && uiState.pendingTools.isEmpty() && streamingId == null) {
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
                    val hasToolCall = streaming.blocks.any { it is ChatBlock.ToolCall }
                    MessageItem(
                        message = if (hasVisibleText || hasThinking || hasToolCall || streaming.error != null) {
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
            items(uiState.pendingTools, key = { "pending-${it.toolCallId}" }) { pending ->
                ToolResultRow(
                    toolName = pending.toolName,
                    summary = null,
                    isError = false,
                    running = true,
                )
                HorizontalDivider()
            }
            items(uiState.messages.asReversed(), key = ChatMessage::id) { message ->
                if (message.isCompactionMarker) {
                    CompactedDivider()
                } else if (message.role == ChatRole.Tool) {
                    message.toolResult?.let { result ->
                        ToolResultRow(
                            toolName = result.toolName,
                            summary = result.summary,
                            isError = result.isError,
                            running = false,
                        )
                    }
                } else {
                    MessageItem(
                        message = message,
                        showThinking = uiState.showThinking,
                        thinkingOverrides = thinkingOverrides,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * Minimal divider marking a compaction cut in the active path (pi's
 * CompactionEntry): centered label between rules; the summary itself lives
 * in LLM context only.
 */
@Composable
private fun CompactedDivider() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.chat_compacted),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
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
                            is ChatBlock.ToolCall -> ToolCallChip(block.name)
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
 * Inline label for an assistant tool call (pi's ToolCall): name-only chip —
 * the raw JSON arguments are never rendered. Disabled: a call is inert UI
 * metadata, not an interaction.
 */
@Composable
private fun ToolCallChip(name: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(name) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = stringResource(R.string.tool_row_icon),
            )
        },
    )
}

/**
 * One tool row (pi's ToolExecutionComponent semantics, native adaptation):
 * tool name first with a bounded one-line summary, a loader while the
 * execution is running and a done/failed label after, error-colored when
 * the result is an error.
 */
@Composable
private fun ToolResultRow(
    toolName: String,
    summary: String?,
    isError: Boolean,
    running: Boolean,
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = stringResource(R.string.tool_row_icon),
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(
                text = toolName,
                color = if (isError) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        supportingContent = summary?.let { text ->
            {
                Text(
                    text = text,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = stringResource(
                        if (isError) R.string.tool_status_failed else R.string.tool_status_done,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
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

/** Compact status line while the agent backs off before an auto-retry (nothing on success). */
@Composable
private fun RetryStatusRow(attempt: Int, maxAttempts: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.chat_retrying, attempt, maxAttempts),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** Transient "Compacting…" status between compaction_start and compaction_end. */
@Composable
private fun CompactingStatusRow(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.chat_compacting),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
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

private val PREVIEW_SEARCH_PROVIDER_OPTIONS = listOf(
    ProviderOption("brave", "Brave Search", configured = true),
)

private val PREVIEW_CLOUDFLARE_PROMPTS = listOf(
    ProviderAuthPrompt("CLOUDFLARE_API_KEY", "Enter the Cloudflare API key", secret = true),
    ProviderAuthPrompt("CLOUDFLARE_ACCOUNT_ID", "Enter the Cloudflare account ID", secret = false),
)

private val PREVIEW_AUTH_METHODS = listOf(
    AuthMethodInfo(works.resolve.pathfinder.ai.auth.AuthType.OAUTH, "Sign in with a Z.AI account", isSubscription = true),
    AuthMethodInfo(works.resolve.pathfinder.ai.auth.AuthType.API_KEY, "Z.AI API key", isSubscription = false),
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
    searchAuthPrompts: (String) -> List<ProviderAuthPrompt> = { emptyList() },
) {
    PathfinderTheme {
        ChatScreen(
            uiState = uiState,
            backStack = rememberNavBackStack(startKey).apply { addAll(extraKeys) },
            onDraftChange = {},
            onSend = {},
            onStop = {},
            onSelectModel = { _, _ -> },
            onSetStartupDefault = { _, _ -> },
            onToggleModelScope = { _, _, _ -> },
            onSelectThinkingLevel = { },
            onSetDefaultThinkingLevel = { },
            onSaveProviderCredential = { _, _, _ -> },
            onRemoveProviderCredential = { },
            authPrompts = authPrompts,
            authMethods = authMethods,
            onBeginProviderAuthLogin = { _, _ -> },
            onSubmitAuthPrompt = { },
            onCancelProviderAuthLogin = { },
            onRefreshProviderStatus = {},
            onSaveSearchProviderCredential = { _, _ -> },
            onRemoveSearchProviderCredential = { _ -> },
            onRefreshSearchProviderStatus = {},
            searchAuthPrompts = searchAuthPrompts,
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
        ),
        extraKeys = listOf(SettingsNavKey),
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
        ),
        extraKeys = listOf(SettingsNavKey, ProvidersNavKey),
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenSearchProvidersPreview() {
    PreviewChatScreen(
        uiState = ChatUiState(
            status = ChatStatus.Ready,
            providerOptions = PREVIEW_PROVIDER_OPTIONS,
            searchProviderOptions = PREVIEW_SEARCH_PROVIDER_OPTIONS,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModel = PREVIEW_SELECTED_MODEL,
        ),
        extraKeys = listOf(SettingsNavKey, SearchProvidersNavKey),
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenSearchProviderAuthPreview() {
    PreviewChatScreen(
        uiState = ChatUiState(
            status = ChatStatus.Ready,
            providerOptions = PREVIEW_PROVIDER_OPTIONS,
            searchProviderOptions = PREVIEW_SEARCH_PROVIDER_OPTIONS,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModel = PREVIEW_SELECTED_MODEL,
        ),
        extraKeys = listOf(
            SettingsNavKey,
            SearchProvidersNavKey,
            SearchProviderAuthNavKey("brave"),
        ),
        searchAuthPrompts = { providerId ->
            if (providerId == "brave") {
                listOf(ProviderAuthPrompt("BRAVE_API_KEY", "Enter Brave Search API key", secret = true))
            } else {
                emptyList()
            }
        },
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
        ),
        extraKeys = listOf(SettingsNavKey, ProvidersNavKey, ProviderAuthNavKey("cloudflare-ai-gateway")),
        authPrompts = { providerId ->
            if (providerId == "cloudflare-ai-gateway") PREVIEW_CLOUDFLARE_PROMPTS else emptyList()
        },
        authMethods = { providerId ->
            if (providerId == "cloudflare-ai-gateway") {
                listOf(AuthMethodInfo(works.resolve.pathfinder.ai.auth.AuthType.API_KEY, "Cloudflare API key", isSubscription = false))
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
    PathfinderTheme {
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
