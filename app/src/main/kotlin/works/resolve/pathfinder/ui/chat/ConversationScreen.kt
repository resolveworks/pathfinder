package works.resolve.pathfinder.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import works.resolve.pathfinder.R
import works.resolve.pathfinder.ai.ModelThinkingLevel

/**
 * The conversation transcript surface: messages plus the composer column.
 * The composer column is content, not scaffold chrome — it belongs to the
 * transcript view; the scaffold owns navigation-bar padding, the column only
 * lifts itself above the IME.
 */
@Composable
internal fun ChatSurface(
    uiState: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onSelectThinkingLevel: (ModelThinkingLevel) -> Unit,
    listState: LazyListState
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ConversationContent(
            uiState = uiState,
            listState = listState,
            modifier = Modifier.weight(1f)
        )
        Column(modifier = Modifier.imePadding()) {
            uiState.retryStatus?.let { retry ->
                RetryStatusRow(
                    attempt = retry.attempt,
                    maxAttempts = retry.maxAttempts
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
                isStreaming = uiState.isStreaming
            )
            SelectionBar(
                selectedModel = uiState.selectedModel,
                defaultModel = uiState.defaultModel,
                options = uiState.scopedModelOptions,
                onSelectModel = onSelectModel,
                thinkingLevel = uiState.thinkingLevel,
                availableThinkingLevels = uiState.availableThinkingLevels,
                defaultThinkingLevel = uiState.defaultThinkingLevel,
                onSelectThinkingLevel = onSelectThinkingLevel
            )
        }
    }
}

/** pi's /model and /thinking bars as a compact chip row under the composer. */
@Composable
private fun SelectionBar(
    selectedModel: SelectedModel?,
    defaultModel: SelectedModel?,
    options: List<ModelOption>,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    thinkingLevel: ModelThinkingLevel?,
    availableThinkingLevels: List<ModelThinkingLevel>,
    defaultThinkingLevel: ModelThinkingLevel?,
    onSelectThinkingLevel: (ModelThinkingLevel) -> Unit,
    modifier: Modifier = Modifier
) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var thinkingSheetOpen by rememberSaveable { mutableStateOf(false) }
    // pi's footer condition `state.model?.reasoning`: a non-reasoning model's
    // only supported level is OFF, so >1 level means reasoning.
    val showThinkingChip = availableThinkingLevels.size > 1
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistChip(
            onClick = { sheetOpen = true },
            label = {
                Text(
                    text = selectedModel?.modelName ?: stringResource(R.string.model_picker_empty),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingIcon = {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        )
        if (showThinkingChip) {
            Spacer(Modifier.width(8.dp))
            AssistChip(
                onClick = { thinkingSheetOpen = true },
                label = {
                    val level = thinkingLevel ?: ModelThinkingLevel.OFF
                    Text(
                        text = if (level == ModelThinkingLevel.OFF) {
                            stringResource(R.string.thinking_chip_off)
                        } else {
                            thinkingLevelLabel(level)
                        },
                        maxLines = 1
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            )
        }
    }
    if (sheetOpen) {
        ModelPickerSheet(
            options = options,
            selectedModel = selectedModel,
            defaultModel = defaultModel,
            onSelect = { option ->
                sheetOpen = false
                onSelectModel(option.providerId, option.modelId)
            },
            onDismiss = { sheetOpen = false }
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
            onDismiss = { thinkingSheetOpen = false }
        )
    }
}

/** pi's thinking-level display names. */
@Composable
internal fun thinkingLevelLabel(level: ModelThinkingLevel): String = when (level) {
    ModelThinkingLevel.OFF -> stringResource(R.string.thinking_level_off)
    ModelThinkingLevel.MINIMAL -> stringResource(R.string.thinking_level_minimal)
    ModelThinkingLevel.LOW -> stringResource(R.string.thinking_level_low)
    ModelThinkingLevel.MEDIUM -> stringResource(R.string.thinking_level_medium)
    ModelThinkingLevel.HIGH -> stringResource(R.string.thinking_level_high)
    ModelThinkingLevel.XHIGH -> stringResource(R.string.thinking_level_xhigh)
    ModelThinkingLevel.MAX -> stringResource(R.string.thinking_level_max)
}

/** pi's plain level descriptions. */
@Composable
internal fun thinkingLevelDescriptionText(level: ModelThinkingLevel): String = when (level) {
    ModelThinkingLevel.OFF -> stringResource(R.string.thinking_level_desc_off)
    ModelThinkingLevel.MINIMAL -> stringResource(R.string.thinking_level_desc_minimal)
    ModelThinkingLevel.LOW -> stringResource(R.string.thinking_level_desc_low)
    ModelThinkingLevel.MEDIUM -> stringResource(R.string.thinking_level_desc_medium)
    ModelThinkingLevel.HIGH -> stringResource(R.string.thinking_level_desc_high)
    ModelThinkingLevel.XHIGH -> stringResource(R.string.thinking_level_desc_xhigh)
    ModelThinkingLevel.MAX -> stringResource(R.string.thinking_level_desc_max)
}

/** Row description with "· default" on the effective default — as in pi, an unset default is MEDIUM. */
@Composable
private fun thinkingLevelDescription(
    level: ModelThinkingLevel,
    defaultLevel: ModelThinkingLevel?
): String {
    val description = thinkingLevelDescriptionText(level)
    return if (level == (defaultLevel ?: ModelThinkingLevel.MEDIUM)) {
        stringResource(R.string.thinking_level_default_marker, description)
    } else {
        description
    }
}

/**
 * The thinking-level picker sheet (pi's thinking selector): the current
 * level checked, the default marked in the row description.
 *
 * Divergence from pi (deliberate, narrow): pi's Ctrl+S applies the row and
 * persists the default in one gesture; this sheet is purely ephemeral —
 * default-setting lives in Settings ▸ Default thinking level (pi has no
 * settings-screen path for it), so "use it now AND default it" takes two
 * steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkingLevelPickerSheet(
    availableLevels: List<ModelThinkingLevel>,
    selectedLevel: ModelThinkingLevel,
    defaultLevel: ModelThinkingLevel?,
    onSelect: (ModelThinkingLevel) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .heightIn(max = 480.dp)
            ) {
                items(availableLevels, key = { it.wire }) { level ->
                    ListItem(
                        headlineContent = { Text(thinkingLevelLabel(level)) },
                        supportingContent = { Text(thinkingLevelDescription(level, defaultLevel)) },
                        trailingContent = if (level == selectedLevel) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = stringResource(R.string.model_selected),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.clickable { onSelect(level) }
                    )
                }
            }
        }
    }
}

/**
 * The model picker sheet (pi's /model selector). Lists only the scoped
 * models — curating the scope is Settings' job (Settings ▸ Scoped models);
 * [options] already falls back to all catalog models when no scope is set.
 *
 * Divergence from pi (deliberate, narrow): pi's Ctrl+S applies the row and
 * persists the default in one gesture; this sheet is purely ephemeral —
 * default-setting lives in Settings ▸ Default model (pi has no
 * settings-screen path for it), so "use it now AND default it" takes two
 * steps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    options: List<ModelOption>,
    selectedModel: SelectedModel?,
    defaultModel: SelectedModel?,
    onSelect: (ModelOption) -> Unit,
    onDismiss: () -> Unit
) {
    // pi's sortModels: current model first, then default; the stable sort
    // keeps the option list's display order otherwise (pi breaks ties by
    // provider name).
    val isCurrent: (ModelOption) -> Boolean = { option ->
        selectedModel?.let { option.providerId == it.providerId && option.modelId == it.modelId } ==
            true
    }
    val isDefault: (ModelOption) -> Boolean = { option ->
        defaultModel?.let { option.providerId == it.providerId && option.modelId == it.modelId } ==
            true
    }
    val sortedOptions = options.sortedWith(
        compareByDescending<ModelOption> { isCurrent(it) }.thenByDescending { isDefault(it) }
    )
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            if (sortedOptions.isEmpty()) {
                Text(
                    text = stringResource(R.string.models_scope_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 480.dp)
                ) {
                    items(sortedOptions, key = { "${it.providerId}/${it.modelId}" }) { option ->
                        val isSelected = isCurrent(option)
                        ListItem(
                            headlineContent = { Text(option.name) },
                            supportingContent = {
                                Text(
                                    text = if (isDefault(option)) {
                                        stringResource(
                                            R.string.model_default_marker,
                                            option.providerName
                                        )
                                    } else {
                                        option.providerName
                                    }
                                )
                            },
                            trailingContent = if (isSelected) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = stringResource(
                                            R.string.model_selected
                                        ),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            } else {
                                null
                            },
                            modifier = Modifier.clickable { onSelect(option) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RetryStatusRow(attempt: Int, maxAttempts: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.chat_retrying, attempt, maxAttempts),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

@Composable
private fun CompactingStatusRow(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.chat_compacting),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
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
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        enabled = !isStreaming,
        placeholder = { Text(stringResource(R.string.chat_message_hint)) },
        trailingIcon = {
            if (isStreaming) {
                IconButton(onClick = onStop) {
                    Icon(
                        ComposerIcons.Stop,
                        contentDescription = stringResource(R.string.action_stop)
                    )
                }
            } else if (canSend) {
                IconButton(onClick = onSend) {
                    Icon(
                        ComposerIcons.Send,
                        contentDescription = stringResource(R.string.action_send)
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
        maxLines = 6,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
    )
}
