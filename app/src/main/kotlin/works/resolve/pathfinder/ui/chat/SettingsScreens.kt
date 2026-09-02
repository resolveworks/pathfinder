package works.resolve.pathfinder.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ai.core.ModelThinkingLevel

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
internal fun SettingsContent(
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
internal fun DefaultModelContent(
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
internal fun DefaultThinkingLevelContent(
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
internal fun ModelsContent(
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
