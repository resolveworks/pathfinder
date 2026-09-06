package works.resolve.pathfinder.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ai.ModelThinkingLevel

/**
 * The "Default thinking level" row hides when the live session's model
 * offers no levels (no session or a non-reasoning model) — the same
 * condition as the chat's thinking chip.
 */
@Composable
internal fun SettingsContent(
    defaultModel: ModelOption?,
    defaultThinkingLevel: ModelThinkingLevel?,
    showDefaultThinkingRow: Boolean,
    showThinking: Boolean,
    onOpenDefaultModel: () -> Unit,
    onOpenDefaultThinking: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenSearchProviders: () -> Unit,
    onToggleShowThinking: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_default_model)) },
            supportingContent = {
                Text(defaultModel?.name ?: stringResource(R.string.settings_not_set))
            },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onOpenDefaultModel)
        )
        if (showDefaultThinkingRow) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_default_thinking)) },
                supportingContent = {
                    Text(
                        defaultThinkingLevel
                            ?.let { thinkingLevelDescriptionText(it) }
                            ?: stringResource(R.string.settings_not_set)
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier.clickable(onClick = onOpenDefaultThinking)
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.providers_title)) },
            supportingContent = { Text(stringResource(R.string.settings_providers_hint)) },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onOpenProviders)
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.search_providers_title)) },
            supportingContent = { Text(stringResource(R.string.search_providers_hint)) },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            },
            modifier = Modifier.clickable(onClick = onOpenSearchProviders)
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_show_thinking)) },
            supportingContent = { Text(stringResource(R.string.settings_show_thinking_hint)) },
            trailingContent = {
                Switch(
                    checked = showThinking,
                    onCheckedChange = onToggleShowThinking
                )
            },
            modifier = Modifier.clickable { onToggleShowThinking(!showThinking) }
        )
    }
}

/**
 * Startup-default model screen — the Settings home of pi's picker Ctrl+S
 * persistence. Commit-on-tap; unlike pi's Ctrl+S, setting the default does
 * not switch the live session (see [ChatViewModel.saveStartupDefault]).
 */
@Composable
internal fun DefaultModelContent(
    modelOptions: List<ModelOption>,
    defaultModel: ModelOption?,
    onSetDefault: (providerId: String, modelId: String) -> Unit,
    onOpenProviders: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (modelOptions.isEmpty()) {
            Text(
                text = stringResource(R.string.models_empty_configured_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenProviders) {
                Text(stringResource(R.string.action_set_up_providers))
            }
        } else {
            LazyColumn {
                items(modelOptions, key = ModelOption::key) { option ->
                    val isDefault = option.key == defaultModel?.key
                    ListItem(
                        headlineContent = { Text(option.name) },
                        supportingContent = { Text(option.providerName) },
                        trailingContent = {
                            RadioButton(selected = isDefault, onClick = null)
                        },
                        modifier = Modifier.clickable {
                            onSetDefault(option.providerId, option.modelId)
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Default thinking-level screen — the Settings home of pi's
 * thinking-selector Ctrl+S persistence. Commit-on-tap; unlike the model
 * default, tapping also switches the live session — applied first, then
 * persisted, pi's order (see [ChatViewModel.setThinkingLevelDefault]).
 */
@Composable
internal fun DefaultThinkingLevelContent(
    availableLevels: List<ModelThinkingLevel>,
    defaultLevel: ModelThinkingLevel?,
    onSetDefault: (ModelThinkingLevel) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        LazyColumn {
            items(availableLevels, key = { it.wire }) { level ->
                ListItem(
                    headlineContent = { Text(thinkingLevelLabel(level)) },
                    supportingContent = { Text(thinkingLevelDescriptionText(level)) },
                    trailingContent = {
                        RadioButton(selected = level == defaultLevel, onClick = null)
                    },
                    modifier = Modifier.clickable { onSetDefault(level) }
                )
                HorizontalDivider()
            }
        }
    }
}
