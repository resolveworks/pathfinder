package works.resolve.pathfinder.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import works.resolve.pathfinder.ai.core.ModelThinkingLevel

internal const val ChatPageIndex = 0
internal const val TreePageIndex = 1
internal const val ChatPagerPageCount = 2

/**
 * Two-page swipeable chat surface: chat page and session-tree page. Only
 * the pager's own gestures handle page swiping — the drawer keeps its
 * stock edge-swipe-to-open.
 */
@Composable
internal fun ConversationPager(
    uiState: ChatUiState,
    pagerState: PagerState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onSelectThinkingLevel: (ModelThinkingLevel) -> Unit,
    onToggleToolOutputExpansion: () -> Unit,
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
                onToggleToolOutputExpansion = onToggleToolOutputExpansion,
            )
        }
    }
}

/**
 * The conversation page. The composer column is page content, not scaffold
 * chrome — it moves with the chat page when swiping to the tree — and owns
 * its own navigation-bar and IME padding.
 */
@Composable
internal fun ChatSurface(
    uiState: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onSelectThinkingLevel: (ModelThinkingLevel) -> Unit,
    onToggleToolOutputExpansion: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        ConversationContent(
            uiState = uiState,
            modifier = Modifier.weight(1f),
            onToggleToolOutputExpansion = onToggleToolOutputExpansion,
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

/** pi's /model and /thinking bars as a chip row. */
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
private fun thinkingLevelDescription(level: ModelThinkingLevel, defaultLevel: ModelThinkingLevel?): String {
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
 * The model picker sheet (pi's /model selector). Shows the Scoped view when
 * a scope is configured (All otherwise), with an All/Scoped toggle — pi's
 * scope toggle; the All view keeps the scope a soft constraint.
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
    // pi's sortModels: current model first, then default; the stable sort
    // keeps the option list's display order otherwise (pi breaks ties by
    // provider name).
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
