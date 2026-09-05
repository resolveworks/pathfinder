package works.resolve.pathfinder.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ai.ModelThinkingLevel
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.codingagent.core.session.SessionSummary
import works.resolve.pathfinder.ui.theme.PathfinderTheme

@Composable
fun ChatRoute(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // The chat/tree view selection is presentation state: saveable, owned
    // here, and hoisted into the stateless ChatScreen.
    var conversationView by rememberSaveable { mutableStateOf(ConversationView.Chat) }
    // The initial stack comes from the first collected state (Loading with
    // startKey = Chat); initialize() soon swaps it via the reset effect in
    // ChatScreen, so Loading never flashes Chat.
    val backStack = rememberNavBackStack(uiState.startKey)
    ChatScreen(
        uiState = uiState,
        backStack = backStack,
        conversationView = conversationView,
        onConversationViewChange = { conversationView = it },
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
        onSaveSearchProviderCredential = viewModel::saveSearchProviderCredential,
        onRemoveSearchProviderCredential = viewModel::removeSearchProviderCredential,
        onRefreshSearchProviderStatus = viewModel::refreshSearchProviderStatus,
        searchAuthPrompts = viewModel::searchProviderAuthPrompts,
        onNewSession = viewModel::newSession,
        onSwitchSession = viewModel::switchSession,
        onSessionSearchQueryChange = viewModel::onSessionSearchQueryChange,
        onSessionSearchSortChange = viewModel::setSessionSearchSort,
        onToggleShowThinking = viewModel::setShowThinking,
        onNavigateTreeEntry = viewModel::navigateToTreeEntry,
        onTreeFilterChange = viewModel::setTreeFilter,
        onDismissError = viewModel::dismissError,
        modifier = modifier
    )
}

/**
 * Pure, state-hoisted chat surface. The API-key input lives exclusively in
 * Compose memory: it is never saved across process death, logged, or written
 * to any state that outlives the configuration form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    backStack: MutableList<NavKey>,
    conversationView: ConversationView,
    onConversationViewChange: (ConversationView) -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onSetStartupDefault: (providerId: String, modelId: String) -> Unit,
    onToggleModelScope: (providerId: String, modelId: String, checked: Boolean) -> Unit,
    onSelectThinkingLevel: (ModelThinkingLevel) -> Unit,
    onSetDefaultThinkingLevel: (ModelThinkingLevel) -> Unit,
    onSaveProviderCredential: (
        providerId: String,
        apiKeyInput: String,
        envInputs: Map<String, String>
    ) -> Unit,
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
    onSessionSearchQueryChange: (query: String) -> Unit,
    onSessionSearchSortChange: (sort: SessionSearchSort) -> Unit,
    onToggleShowThinking: (Boolean) -> Unit,
    onNavigateTreeEntry: (entryId: String) -> Unit,
    onTreeFilterChange: (TreeFilter) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Panel scroll state lives above the Chat/Tree switch so each view keeps
    // its own position across view toggles. A different transcript starts
    // with fresh list state positioned at its bottom anchor.
    val chatScrollState = key(uiState.activeSessionId) { rememberTranscriptScrollState(uiState) }
    val treeListState = rememberLazyListState()
    // Session whose tree has been positioned on its current leaf at open.
    var positionedTreeSessionId by rememberSaveable { mutableStateOf<String?>(null) }

    val sendAndFollow: () -> Unit = {
        chatScrollState.followBottom()
        onSend()
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            onDismissError()
        }
    }

    // Reset signal from the ViewModel: rebuild the stack to exactly the
    // current root. A single-entry stack makes NavDisplay's onBack a no-op,
    // so NeedsConfiguration is a dead end; a bumped epoch (session adopted /
    // configuration saved) lands back on the root.
    LaunchedEffect(uiState.startKey, uiState.navigationEpoch) {
        backStack.clear()
        backStack.add(uiState.startKey)
    }

    // Successful credential save: pop exactly one credential screen
    // (state-driven — no ViewModel navigation callback). On first-run saves
    // both epochs bump, so the reset above runs first and leaves a
    // single-entry root the guard never pops. A failed save never bumps
    // this epoch, so the form and its typed inputs stay intact.
    LaunchedEffect(uiState.credentialSuccessEpoch) {
        val top = backStack.lastOrNull()
        if (backStack.size > 1 &&
            (top is ProviderAuthNavKey || top is ProviderApiKeyNavKey)
        ) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    // A login in flight is its own destination on top of the provider's
    // screen; when the flow ends (success, failure, or cancel) the entry
    // pops back to where the login was started. A restored stack whose
    // flow died with the process pops the dead entry here too.
    LaunchedEffect(uiState.authFlow?.providerId) {
        val flowProviderId = uiState.authFlow?.providerId
        val top = backStack.lastOrNull()
        when {
            flowProviderId != null && top is ProviderAuthNavKey &&
                top.providerId == flowProviderId ->
                backStack.add(ProviderLoginNavKey(flowProviderId))

            flowProviderId == null && top is ProviderLoginNavKey ->
                backStack.removeAt(backStack.lastIndex)
        }
    }

    LaunchedEffect(uiState.searchCredentialSuccessEpoch) {
        if (backStack.size > 1 && backStack.lastOrNull() is SearchProviderAuthNavKey) {
            backStack.removeAt(backStack.lastIndex)
        }
    }

    // A session switch (or init's null -> real id) shows the transcript.
    LaunchedEffect(uiState.activeSessionId) {
        onConversationViewChange(ConversationView.Chat)
    }

    // The tree opens positioned on the current leaf (pi's tree opens with
    // the cursor on it) once per session; later opens restore the panel's
    // own scroll position.
    LaunchedEffect(conversationView, uiState.activeSessionId, uiState.treeRows) {
        if (conversationView != ConversationView.Tree) return@LaunchedEffect
        if (positionedTreeSessionId == uiState.activeSessionId) return@LaunchedEffect
        val leafIndex = uiState.treeRows.indexOfFirst { it.isCurrentLeaf }
        if (leafIndex < 0) return@LaunchedEffect
        treeListState.scrollToItem(leafIndex)
        // scrollToItem parks the leaf at the viewport top, stranding blank
        // space when little follows it; scrolling back by the trailing gap
        // (clamped) lands the true bottom.
        val info = treeListState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        val endGap = info.viewportEndOffset - (lastVisible.offset + lastVisible.size)
        if (endGap > 0) treeListState.scrollBy(-endGap.toFloat())
        positionedTreeSessionId = uiState.activeSessionId
    }

    val pushSettings: () -> Unit = { backStack.add(SettingsNavKey) }
    val pushModels: () -> Unit = { backStack.add(ModelsNavKey) }
    val pushDefaultModel: () -> Unit = { backStack.add(DefaultModelNavKey) }
    val pushDefaultThinking: () -> Unit = { backStack.add(DefaultThinkingNavKey) }
    val pushProviders: () -> Unit = { backStack.add(ProvidersNavKey) }
    val pushProviderAuth: (String) -> Unit = { backStack.add(ProviderAuthNavKey(it)) }
    val pushProviderApiKeyForm: (String) -> Unit = { backStack.add(ProviderApiKeyNavKey(it)) }
    val pushSearchProviders: () -> Unit = { backStack.add(SearchProvidersNavKey) }
    val pushSearchProviderAuth: (String) -> Unit = { backStack.add(SearchProviderAuthNavKey(it)) }
    val popBackStack: () -> Unit = {
        // Popping the login destination cancels its flow: a login must
        // never outlive the screen it belongs to.
        if (backStack.lastOrNull() is ProviderLoginNavKey) {
            onCancelProviderAuthLogin()
        }
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }
    val topKey = backStack.lastOrNull() ?: ChatNavKey
    val chatRoot = topKey == ChatNavKey && uiState.status == ChatStatus.Ready
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    // System back on the tree view returns to the transcript instead of
    // leaving the Chat root (BackHandler wins over Nav3's onBack while
    // enabled).
    BackHandler(enabled = chatRoot && conversationView == ConversationView.Tree) {
        onConversationViewChange(ConversationView.Chat)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Session navigation is meaningless before the app is configured.
        gesturesEnabled = uiState.status == ChatStatus.Ready,
        drawerContent = {
            ChatDrawerContent(
                uiState = uiState,
                onSessionSearchQueryChange = onSessionSearchQueryChange,
                onSessionSearchSortChange = onSessionSearchSortChange,
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
                }
            )
        },
        modifier = modifier
    ) {
        Scaffold(
            topBar = {
                if (uiState.status != ChatStatus.Loading) {
                    val canPop = backStack.size > 1
                    ChatTopBar(
                        title = when (topKey) {
                            SettingsNavKey -> stringResource(R.string.settings_title)

                            ModelsNavKey -> stringResource(R.string.settings_model)

                            DefaultModelNavKey -> stringResource(R.string.settings_default_model)

                            DefaultThinkingNavKey -> stringResource(
                                R.string.settings_default_thinking
                            )

                            ProvidersNavKey -> stringResource(R.string.providers_title)

                            SearchProvidersNavKey -> stringResource(R.string.search_providers_title)

                            is SearchProviderAuthNavKey ->
                                uiState.searchProviderOptions
                                    .firstOrNull { it.id == topKey.providerId }?.name
                                    ?: stringResource(R.string.search_providers_title)

                            else -> providerNavKeyId(topKey)
                                ?.let { providerId ->
                                    uiState.providerOptions
                                        .firstOrNull { it.id == providerId }?.name
                                }
                                ?: stringResource(R.string.session_title)
                        },
                        // The drawer belongs to the Chat root (on both its
                        // views); nested destinations navigate up instead. A
                        // forced root (unconfigured app) is a dead end: no Up
                        // arrow.
                        onOpenDrawer = if (chatRoot) openDrawer else null,
                        onBack = if (canPop) popBackStack else null,
                        actions = if (chatRoot) {
                            {
                                ConversationViewToggle(
                                    view = conversationView,
                                    onViewChange = onConversationViewChange
                                )
                            }
                        } else {
                            {}
                        }
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .consumeWindowInsets(contentPadding)
            ) {
                when {
                    uiState.status == ChatStatus.Loading -> LoadingContent()

                    // Any settings-family destination pushed on top of a
                    // failed init replaces the error surface; popping returns.
                    uiState.status == ChatStatus.Failed && topKey == ChatNavKey -> FailedContent(
                        error = uiState.error ?: stringResource(R.string.error_generic),
                        onOpenProviders = pushProviders
                    )

                    else -> NavDisplay(
                        backStack = backStack,
                        onBack = popBackStack,
                        entryProvider = entryProvider {
                            entry<ChatNavKey> {
                                if (chatRoot) {
                                    when (conversationView) {
                                        ConversationView.Chat -> ChatSurface(
                                            uiState = uiState,
                                            onDraftChange = onDraftChange,
                                            onSend = sendAndFollow,
                                            onStop = onStop,
                                            onSelectModel = onSelectModel,
                                            onSelectThinkingLevel = onSelectThinkingLevel,
                                            scrollState = chatScrollState
                                        )

                                        ConversationView.Tree -> TreePanel(
                                            rows = uiState.treeRows,
                                            filter = uiState.treeFilter,
                                            onFilterChange = onTreeFilterChange,
                                            onNavigate = { entryId ->
                                                onNavigateTreeEntry(entryId)
                                                // pi's tree selector closes on
                                                // selection (even when navigation
                                                // is rejected): return to the
                                                // transcript.
                                                onConversationViewChange(ConversationView.Chat)
                                            },
                                            listState = treeListState,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                } else {
                                    ChatSurface(
                                        uiState = uiState,
                                        onDraftChange = onDraftChange,
                                        onSend = sendAndFollow,
                                        onStop = onStop,
                                        onSelectModel = onSelectModel,
                                        onSelectThinkingLevel = onSelectThinkingLevel,
                                        scrollState = chatScrollState
                                    )
                                }
                            }
                            entry<SettingsNavKey> {
                                SettingsContent(
                                    defaultModel = uiState.defaultModel,
                                    defaultThinkingLevel = uiState.defaultThinkingLevel,
                                    showDefaultThinkingRow =
                                        uiState.availableThinkingLevels.isNotEmpty(),
                                    showThinking = uiState.showThinking,
                                    onOpenDefaultModel = pushDefaultModel,
                                    onOpenDefaultThinking = pushDefaultThinking,
                                    onOpenModels = pushModels,
                                    onOpenProviders = pushProviders,
                                    onOpenSearchProviders = pushSearchProviders,
                                    onToggleShowThinking = onToggleShowThinking
                                )
                            }
                            entry<DefaultModelNavKey> {
                                DefaultModelContent(
                                    modelOptions = uiState.modelOptions,
                                    defaultModel = uiState.defaultModel,
                                    onSetDefault = onSetStartupDefault,
                                    onOpenProviders = pushProviders
                                )
                            }
                            entry<DefaultThinkingNavKey> {
                                DefaultThinkingLevelContent(
                                    availableLevels = uiState.availableThinkingLevels,
                                    defaultLevel = uiState.defaultThinkingLevel,
                                    onSetDefault = onSetDefaultThinkingLevel
                                )
                            }
                            entry<ModelsNavKey> {
                                ModelsContent(
                                    modelOptions = uiState.modelOptions,
                                    enabledModels = uiState.enabledModels,
                                    onToggleScope = onToggleModelScope,
                                    onOpenProviders = pushProviders
                                )
                            }
                            entry<ProvidersNavKey> {
                                ProvidersContent(
                                    providerOptions = uiState.providerOptions,
                                    onRefresh = onRefreshProviderStatus,
                                    onOpenProvider = pushProviderAuth
                                )
                            }
                            entry<SearchProvidersNavKey> {
                                SearchProvidersContent(
                                    providerOptions = uiState.searchProviderOptions,
                                    onRefresh = onRefreshSearchProviderStatus,
                                    onOpenProvider = pushSearchProviderAuth
                                )
                            }
                            entry<SearchProviderAuthNavKey> { key ->
                                val option = uiState.searchProviderOptions
                                    .firstOrNull { it.id == key.providerId }
                                if (option != null) {
                                    // Search providers offer only API-key auth:
                                    // reuse the all-fields form with the search
                                    // catalog's prompts; env inputs are not
                                    // forwarded.
                                    ProviderAuthContent(
                                        provider = option,
                                        prompts = searchAuthPrompts(key.providerId),
                                        onSave = { apiKeyInput, _ ->
                                            onSaveSearchProviderCredential(
                                                key.providerId,
                                                apiKeyInput
                                            )
                                        },
                                        onRemove = {
                                            onRemoveSearchProviderCredential(key.providerId)
                                        },
                                        onClose = popBackStack
                                    )
                                }
                            }
                            entry<ProviderAuthNavKey> { key ->
                                val option = uiState.providerOptions
                                    .firstOrNull { it.id == key.providerId }
                                if (option != null) {
                                    ProviderAuthScreen(
                                        provider = option,
                                        prompts = authPrompts(key.providerId),
                                        methods = authMethods(key.providerId),
                                        onSave = { apiKeyInput, envInputs ->
                                            onSaveProviderCredential(
                                                key.providerId,
                                                apiKeyInput,
                                                envInputs
                                            )
                                        },
                                        onRemove = { onRemoveProviderCredential(key.providerId) },
                                        onOpenApiKeyForm = {
                                            pushProviderApiKeyForm(key.providerId)
                                        },
                                        onBeginLogin = { method ->
                                            onBeginProviderAuthLogin(key.providerId, method)
                                        },
                                        onClose = popBackStack
                                    )
                                }
                            }
                            entry<ProviderApiKeyNavKey> { key ->
                                val option = uiState.providerOptions
                                    .firstOrNull { it.id == key.providerId }
                                if (option != null) {
                                    ProviderAuthContent(
                                        provider = option,
                                        prompts = authPrompts(key.providerId),
                                        onSave = { apiKeyInput, envInputs ->
                                            onSaveProviderCredential(
                                                key.providerId,
                                                apiKeyInput,
                                                envInputs
                                            )
                                        },
                                        onRemove = { onRemoveProviderCredential(key.providerId) },
                                        onClose = popBackStack
                                    )
                                }
                            }
                            entry<ProviderLoginNavKey> { key ->
                                uiState.authFlow
                                    ?.takeIf { it.providerId == key.providerId }
                                    ?.let { flow ->
                                        ProviderLoginScreen(
                                            flow = flow,
                                            onSubmit = onSubmitAuthPrompt,
                                            onCancel = popBackStack
                                        )
                                    }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatDrawerContent(
    uiState: ChatUiState,
    onSessionSearchQueryChange: (String) -> Unit,
    onSessionSearchSortChange: (SessionSearchSort) -> Unit,
    onNewSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    ModalDrawerSheet {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = stringResource(R.string.settings_title)
                )
            }
        }
        HorizontalDivider()
        FilledTonalButton(
            onClick = onNewSession,
            modifier = Modifier
                .fillMaxWidth()
                .padding(NavigationDrawerItemDefaults.ItemPadding)
                .padding(vertical = 8.dp)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.IconSize)
            )
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.action_new_chat))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = uiState.sessionSearchQuery,
                onValueChange = onSessionSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.session_search_hint)) },
                trailingIcon = {
                    if (uiState.sessionSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSessionSearchQueryChange("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.session_search_clear)
                            )
                        }
                    }
                }
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                SessionSearchSort.entries.forEachIndexed { index, sort ->
                    SegmentedButton(
                        selected = uiState.sessionSearchSort == sort,
                        onClick = { onSessionSearchSortChange(sort) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index,
                            SessionSearchSort.entries.size
                        ),
                        label = {
                            Text(
                                stringResource(
                                    when (sort) {
                                        SessionSearchSort.RECENT ->
                                            R.string.session_search_sort_recent

                                        SessionSearchSort.RELEVANCE ->
                                            R.string.session_search_sort_relevance
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }
        val queryBlank = uiState.sessionSearchQuery.isBlank()
        val listedSessions =
            if (queryBlank) uiState.sessionSummaries else uiState.sessionSearchResults
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(listedSessions, key = SessionSummary::id) { summary ->
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = summary.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    selected = summary.id == uiState.activeSessionId,
                    onClick = {
                        if (summary.id != uiState.activeSessionId) onSwitchSession(summary.id)
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
            if (!queryBlank && !uiState.isSessionSearching && listedSessions.isEmpty()) {
                item(key = "session-search-no-results") {
                    Text(
                        text = stringResource(R.string.session_search_no_results),
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String,
    onOpenDrawer: (() -> Unit)?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            when {
                onOpenDrawer != null -> IconButton(onClick = onOpenDrawer) {
                    Icon(
                        Icons.Default.Menu,
                        contentDescription = stringResource(R.string.action_menu)
                    )
                }

                onBack != null -> IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back)
                    )
                }
            }
        },
        actions = actions
    )
}

/**
 * Stock single-choice segmented toggle switching the chat root between its
 * transcript and session-tree views; the tree panel's All/User-only filter
 * row is the same component at full width.
 */
@Composable
private fun ConversationViewToggle(
    view: ConversationView,
    onViewChange: (ConversationView) -> Unit,
    modifier: Modifier = Modifier
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        ConversationView.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = view == entry,
                onClick = { onViewChange(entry) },
                shape = SegmentedButtonDefaults.itemShape(index, ConversationView.entries.size),
                label = {
                    Text(
                        stringResource(
                            when (entry) {
                                ConversationView.Chat -> R.string.view_chat
                                ConversationView.Tree -> R.string.view_tree
                            }
                        )
                    )
                }
            )
        }
    }
}

/** The provider id of a provider-family destination key, or null for other roots. */
private fun providerNavKeyId(key: NavKey): String? = when (key) {
    is ProviderAuthNavKey -> key.providerId
    is ProviderApiKeyNavKey -> key.providerId
    is ProviderLoginNavKey -> key.providerId
    else -> null
}

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
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.padding(top = 16.dp))
        Button(onClick = onOpenProviders) {
            Text(stringResource(R.string.action_configure))
        }
    }
}

private val PREVIEW_MODEL_OPTIONS = listOf(
    ModelOption(
        providerId = "zai",
        providerName = "Z.AI",
        modelId = "model-a",
        name = "Preview Model A"
    ),
    ModelOption(
        providerId = "zai",
        providerName = "Z.AI",
        modelId = "model-b",
        name = "Preview Model B"
    )
)

private val PREVIEW_PROVIDER_OPTIONS = listOf(
    ProviderOption("anthropic", "Anthropic", configured = true),
    ProviderOption("cloudflare-ai-gateway", "Cloudflare AI Gateway", configured = true),
    ProviderOption("openai", "OpenAI", configured = false),
    ProviderOption("zai", "Z.AI", configured = false)
)

private val PREVIEW_SEARCH_PROVIDER_OPTIONS = listOf(
    ProviderOption("brave", "Brave Search", configured = true)
)

private val PREVIEW_CLOUDFLARE_PROMPTS = listOf(
    ProviderAuthPrompt("CLOUDFLARE_API_KEY", "Enter the Cloudflare API key", secret = true),
    ProviderAuthPrompt("CLOUDFLARE_ACCOUNT_ID", "Enter the Cloudflare account ID", secret = false)
)

private val PREVIEW_AUTH_METHODS = listOf(
    AuthMethodInfo(
        works.resolve.pathfinder.ai.auth.AuthType.OAUTH,
        "Sign in with a Z.AI account",
        isSubscription = true
    ),
    AuthMethodInfo(
        works.resolve.pathfinder.ai.auth.AuthType.API_KEY,
        "Z.AI API key",
        isSubscription = false
    )
)

private val PREVIEW_SELECTED_MODEL = SelectedModel(
    providerId = "zai",
    providerName = "Z.AI",
    modelId = "model-a",
    modelName = "Preview Model A"
)

@Composable
private fun PreviewChatScreen(
    uiState: ChatUiState,
    startKey: NavKey = ChatNavKey,
    extraKeys: List<NavKey> = emptyList(),
    conversationView: ConversationView = ConversationView.Chat,
    authPrompts: (String) -> List<ProviderAuthPrompt> = { emptyList() },
    authMethods: (String) -> List<AuthMethodInfo> = { emptyList() },
    searchAuthPrompts: (String) -> List<ProviderAuthPrompt> = { emptyList() }
) {
    PathfinderTheme {
        ChatScreen(
            uiState = uiState,
            backStack = rememberNavBackStack(startKey).apply { addAll(extraKeys) },
            conversationView = conversationView,
            onConversationViewChange = {},
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
            onSessionSearchQueryChange = {},
            onSessionSearchSortChange = {},
            onToggleShowThinking = {},
            onNavigateTreeEntry = {},
            onTreeFilterChange = {},
            onDismissError = {},
            modifier = Modifier
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
                ProviderOption(
                    "cloudflare-ai-gateway",
                    "Cloudflare AI Gateway",
                    configured = false
                )
            )
        )
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
            selectedModel = PREVIEW_SELECTED_MODEL
        )
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenSettingsRootPreview() {
    PreviewChatScreen(
        uiState = ChatUiState(
            status = ChatStatus.Ready,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModel = PREVIEW_SELECTED_MODEL
        ),
        extraKeys = listOf(SettingsNavKey)
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
            selectedModel = PREVIEW_SELECTED_MODEL
        ),
        extraKeys = listOf(SettingsNavKey, ProvidersNavKey)
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
            selectedModel = PREVIEW_SELECTED_MODEL
        ),
        extraKeys = listOf(SettingsNavKey, SearchProvidersNavKey)
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
            selectedModel = PREVIEW_SELECTED_MODEL
        ),
        extraKeys = listOf(
            SettingsNavKey,
            SearchProvidersNavKey,
            SearchProviderAuthNavKey("brave")
        ),
        searchAuthPrompts = { providerId ->
            if (providerId == "brave") {
                listOf(
                    ProviderAuthPrompt("BRAVE_API_KEY", "Enter Brave Search API key", secret = true)
                )
            } else {
                emptyList()
            }
        }
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
            selectedModel = PREVIEW_SELECTED_MODEL
        ),
        extraKeys = listOf(
            SettingsNavKey,
            ProvidersNavKey,
            ProviderAuthNavKey("cloudflare-ai-gateway")
        ),
        authPrompts = { providerId ->
            if (providerId == "cloudflare-ai-gateway") PREVIEW_CLOUDFLARE_PROMPTS else emptyList()
        },
        authMethods = { providerId ->
            if (providerId == "cloudflare-ai-gateway") {
                listOf(
                    AuthMethodInfo(
                        works.resolve.pathfinder.ai.auth.AuthType.API_KEY,
                        "Cloudflare API key",
                        isSubscription = false
                    )
                )
            } else {
                emptyList()
            }
        }
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
            selectedModel = PREVIEW_SELECTED_MODEL
        ),
        extraKeys = listOf(SettingsNavKey, ProvidersNavKey, ProviderAuthNavKey("zai")),
        authPrompts = { providerId ->
            if (providerId == "zai") {
                listOf(ProviderAuthPrompt("ZAI_API_KEY", "Enter Z.AI API key", secret = true))
            } else {
                emptyList()
            }
        },
        authMethods = { providerId ->
            if (providerId ==
                "zai"
            ) {
                PREVIEW_AUTH_METHODS
            } else {
                emptyList()
            }
        }
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
                    AuthEvent.AuthUrl(
                        "https://auth.example.invalid/authorize",
                        "Approve the request"
                    ),
                    AuthEvent.DeviceCode("ABCD-1234", "https://verify.example.invalid/device"),
                    AuthEvent.Progress("Waiting for approval")
                ),
                pendingPrompt = PendingAuthPrompt(
                    kind = AuthPromptKind.MANUAL_CODE,
                    message = "Enter the code shown in the browser"
                )
            )
        ),
        extraKeys = listOf(
            SettingsNavKey,
            ProvidersNavKey,
            ProviderAuthNavKey("zai"),
            ProviderLoginNavKey("zai")
        ),
        authMethods = { providerId ->
            if (providerId ==
                "zai"
            ) {
                PREVIEW_AUTH_METHODS
            } else {
                emptyList()
            }
        }
    )
}

private val PREVIEW_TREE_ROWS = listOf(
    TreeRow(
        id = "u1",
        path = listOf("u1"),
        indent = 0,
        connector = TreeConnector.NONE,
        gutters = emptyList(),
        isOnActivePath = true,
        isCurrentLeaf = false,
        isFoldable = false,
        body = TreeRowBody.Text("You: Hello there")
    ),
    TreeRow(
        id = "a1",
        path = listOf("u1", "a1"),
        indent = 0,
        connector = TreeConnector.NONE,
        gutters = emptyList(),
        isOnActivePath = true,
        isCurrentLeaf = true,
        isFoldable = false,
        body = TreeRowBody.Text("Assistant: Hi! How can I help?")
    )
)

@Preview(showBackground = true)
@Composable
private fun ChatScreenChatViewPreview() {
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
                    messageCount = 1
                )
            ),
            messages = listOf(
                ChatMessage(
                    id = "m1",
                    role = ChatRole.User,
                    blocks = listOf(ChatBlock.Text("Hello there"))
                )
            )
        )
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatScreenTreeViewPreview() {
    PreviewChatScreen(
        ChatUiState(
            status = ChatStatus.Ready,
            modelOptions = PREVIEW_MODEL_OPTIONS,
            selectedModel = PREVIEW_SELECTED_MODEL,
            activeSessionId = "s1",
            treeRows = PREVIEW_TREE_ROWS
        ),
        conversationView = ConversationView.Tree
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
                    messageCount = 2
                )
            ),
            messages = listOf(
                ChatMessage(
                    id = "m1",
                    role = ChatRole.User,
                    blocks = listOf(ChatBlock.Text("Hello there"))
                ),
                ChatMessage(
                    id = "m2",
                    role = ChatRole.Assistant,
                    blocks = listOf(ChatBlock.Text("Hi! How can I help?"))
                )
            ),
            streamingMessage = ChatMessage(
                id = "streaming-1",
                role = ChatRole.Assistant,
                blocks = listOf(ChatBlock.Text("Sure, "))
            ),
            isStreaming = true
        )
    )
}
