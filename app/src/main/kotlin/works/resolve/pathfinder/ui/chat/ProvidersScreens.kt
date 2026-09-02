package works.resolve.pathfinder.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ai.auth.AuthEvent
import works.resolve.pathfinder.ai.auth.AuthMethodInfo
import works.resolve.pathfinder.ai.auth.AuthType
import works.resolve.pathfinder.ui.openInCustomTab

/**
 * Providers screen (pi's /login list): every catalog provider with live
 * configured/unconfigured status, filtered by name/id substring. Shares
 * [ProviderListContent] with the search-providers screen.
 */
@Composable
internal fun ProvidersContent(
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
internal fun SearchProvidersContent(
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
internal fun ProviderAuthContent(
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
internal fun ProviderAuthEntry(
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
