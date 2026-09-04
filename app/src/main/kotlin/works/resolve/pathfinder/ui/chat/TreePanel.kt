package works.resolve.pathfinder.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import works.resolve.pathfinder.R

/**
 * Panel rendering a conversation's branching history as a navigable tree
 * (pi's /tree view). Purely presentational: it consumes [TreeRow]s produced
 * by the tree projection and reports navigation taps; fold and search state
 * are panel-local.
 *
 * Guides draw as Canvas geometry and fold icons use Material Symbols rather
 * than terminal glyphs: unlike a monospace TUI, Android font fallback does
 * not guarantee that │/├/└ and ⊟/⊞ share metrics or even a baseline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreePanel(
    rows: List<TreeRow>,
    filter: TreeFilter,
    onFilterChange: (TreeFilter) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Folded row ids; cleared on query or filter change, as in pi.
    var foldedIds by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = {
            it.toList()
        }, restore = { it.toSet() })
    ) { mutableStateOf(emptySet()) }

    TreePanelContent(
        rows = rows,
        query = query,
        onQueryChange = { newQuery ->
            query = newQuery
            foldedIds = emptySet()
        },
        foldedIds = foldedIds,
        onFoldedIdsChange = { foldedIds = it },
        filter = filter,
        onFilterChange = onFilterChange,
        onNavigate = onNavigate,
        modifier = modifier,
        listState = listState
    )
}

/** Stateful [TreePanel] minus the saveable state; previews preset folds here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TreePanelContent(
    rows: List<TreeRow>,
    query: String,
    onQueryChange: (String) -> Unit,
    foldedIds: Set<String>,
    onFoldedIdsChange: (Set<String>) -> Unit,
    filter: TreeFilter,
    onFilterChange: (TreeFilter) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    Column(modifier = modifier) {
        TreePanelHeader(
            query = query,
            onQueryChange = onQueryChange,
            filter = filter,
            onFilterChange = { newFilter ->
                // Stale folds would hide entries the new filter makes visible.
                onFoldedIdsChange(emptySet())
                onFilterChange(newFilter)
            }
        )

        val visibleRows = filterTreeRows(rows = rows, query = query, foldedIds = foldedIds)
        when {
            rows.isEmpty() -> EmptyTreeText(stringResource(R.string.tree_empty))

            visibleRows.isEmpty() -> EmptyTreeText(stringResource(R.string.tree_no_matches))

            else -> LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
                items(visibleRows, key = { it.id }) { row ->
                    TreeRowItem(
                        row = row,
                        folded = row.id in foldedIds,
                        onToggleFold = {
                            onFoldedIdsChange(
                                if (row.id in foldedIds) foldedIds - row.id else foldedIds + row.id
                            )
                        },
                        onNavigate = { onNavigate(row.id) }
                    )
                }
            }
        }
    }
}

internal fun filterTreeRows(
    rows: List<TreeRow>,
    query: String,
    foldedIds: Set<String>
): List<TreeRow> {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matched = if (tokens.isEmpty()) {
        rows
    } else {
        rows.filter { row ->
            // Tool rows search over the rendered title's pieces: the tool
            // name (in preview) and the parsed input argument.
            val text = (row.preview + " " + (row.toolCall?.input ?: "")).lowercase()
            tokens.all { text.contains(it) }
        }
    }
    return matched.filter { row ->
        row.path.dropLast(1).none { it in foldedIds }
    }
}

/** One guide cell per indent level. */
internal enum class TreeGuideCell { EMPTY, GUTTER, TEE, ELBOW }

/** Pure guide layout: pi's TreeList.render() reduced to drawable cells. */
internal fun treeGuideCells(row: TreeRow): List<TreeGuideCell> = List(row.indent) { level ->
    when {
        level in row.gutters -> TreeGuideCell.GUTTER
        level != row.indent - 1 -> TreeGuideCell.EMPTY
        row.connector == TreeConnector.TEE -> TreeGuideCell.TEE
        row.connector == TreeConnector.ELBOW -> TreeGuideCell.ELBOW
        else -> TreeGuideCell.EMPTY
    }
}

@Composable
private fun TreePanelHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: TreeFilter,
    onFilterChange: (TreeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.tree_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.tree_clear_search)
                        )
                    }
                }
            }
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            TreeFilter.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = filter == mode,
                    onClick = { onFilterChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, TreeFilter.entries.size),
                    label = {
                        Text(
                            stringResource(
                                when (mode) {
                                    TreeFilter.DEFAULT -> R.string.tree_filter_all
                                    TreeFilter.USER_ONLY -> R.string.tree_filter_user_only
                                }
                            )
                        )
                    }
                )
            }
        }
    }
}

/**
 * One full-width tree row: pi's Unicode TUI prefix rendered as Canvas branch
 * geometry plus a Material Symbols fold icon.
 */
@Composable
private fun TreeRowItem(
    row: TreeRow,
    folded: Boolean,
    onToggleFold: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val foldLabel = stringResource(
        if (folded) R.string.tree_unfold_branch else R.string.tree_fold_branch
    )
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TreeRowHeight)
            .clickable(onClick = onNavigate)
            .padding(horizontal = TreeRowHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (row.indent > 0) {
            TreeGuide(
                row = row,
                folded = folded,
                onToggleFold = onToggleFold,
                foldLabel = foldLabel,
                modifier = Modifier
                    .width(TreeGuideLevelWidth * row.indent)
                    .fillMaxHeight()
            )
        }

        // Pi only exposes a root's fold marker after it has been folded.
        if (folded && row.connector == TreeConnector.NONE) {
            Box(
                modifier = Modifier
                    .size(RootFoldTargetSize)
                    .clickable(
                        onClickLabel = foldLabel,
                        role = Role.Button,
                        onClick = onToggleFold
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Icon(
                    imageVector = TreeIcons.AddBox,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(TreeFoldIconSize)
                )
            }
        }

        if (row.isOnActivePath) {
            Canvas(modifier = Modifier.size(ActivePathMarkerWidth, TreeRowHeight)) {
                drawCircle(
                    color = accent,
                    radius = ActivePathDotRadius.toPx(),
                    center = center.copy(x = ActivePathDotRadius.toPx())
                )
            }
        }

        Text(
            // Tool-result rows title themselves from the originating call,
            // exactly like the chat's tool rows.
            text = row.toolCall?.let { toolCallTitle(it.name, it.input) } ?: row.preview,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Canvas implementation of pi's │ gutters and ├/└ branch connectors. */
@Composable
private fun TreeGuide(
    row: TreeRow,
    folded: Boolean,
    onToggleFold: () -> Unit,
    foldLabel: String,
    modifier: Modifier = Modifier
) {
    val guideColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cells = treeGuideCells(row)
    val hasFoldIcon = row.connector != TreeConnector.NONE && (row.isFoldable || folded)
    val interactionModifier = if (hasFoldIcon) {
        Modifier.clickable(
            onClickLabel = foldLabel,
            role = Role.Button,
            onClick = onToggleFold
        )
    } else {
        Modifier
    }

    Box(modifier = modifier.then(interactionModifier)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val levelWidth = TreeGuideLevelWidth.toPx()
            val branchXOffset = TreeGuideBranchX.toPx()
            val centerY = size.height / 2f
            val strokeWidth = TreeGuideStrokeWidth.toPx()
            val iconStart = TreeGuideFoldIconX.toPx()
            val plainConnectorEnd = TreeGuidePlainConnectorEnd.toPx()

            cells.forEachIndexed { level, cell ->
                val branchX = level * levelWidth + branchXOffset
                when (cell) {
                    TreeGuideCell.EMPTY -> Unit

                    TreeGuideCell.GUTTER -> drawLine(
                        color = guideColor,
                        start = androidx.compose.ui.geometry.Offset(branchX, 0f),
                        end = androidx.compose.ui.geometry.Offset(branchX, size.height),
                        strokeWidth = strokeWidth,
                        cap = StrokeCap.Butt
                    )

                    TreeGuideCell.TEE, TreeGuideCell.ELBOW -> {
                        drawLine(
                            color = guideColor,
                            start = androidx.compose.ui.geometry.Offset(branchX, 0f),
                            end = androidx.compose.ui.geometry.Offset(
                                branchX,
                                if (cell == TreeGuideCell.TEE) size.height else centerY
                            ),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Butt
                        )
                        drawLine(
                            color = guideColor,
                            start = androidx.compose.ui.geometry.Offset(branchX, centerY),
                            end = androidx.compose.ui.geometry.Offset(
                                level * levelWidth +
                                    if (hasFoldIcon) iconStart else plainConnectorEnd,
                                centerY
                            ),
                            strokeWidth = strokeWidth,
                            cap = StrokeCap.Butt
                        )
                    }
                }
            }
        }

        if (hasFoldIcon) {
            Icon(
                imageVector = if (folded) TreeIcons.AddBox else TreeIcons.IndeterminateCheckBox,
                contentDescription = null,
                tint = guideColor,
                modifier = Modifier
                    .offset(x = TreeGuideLevelWidth * (row.indent - 1) + TreeGuideFoldIconX)
                    .align(Alignment.CenterStart)
                    .size(TreeFoldIconSize)
            )
        }
    }
}

private val TreeRowHeight = 56.dp
private val TreeRowHorizontalPadding = 16.dp
private val TreeGuideLevelWidth = 28.dp
private val TreeGuideBranchX = 4.dp
private val TreeGuideFoldIconX = 8.dp
private val TreeGuidePlainConnectorEnd = 18.dp
private val TreeGuideStrokeWidth = 1.dp
private val TreeFoldIconSize = 18.dp
private val RootFoldTargetSize = 24.dp
private val ActivePathMarkerWidth = 14.dp
private val ActivePathDotRadius = 2.dp

@Composable
private fun EmptyTreeText(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(24.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private fun row(
    id: String,
    parentPath: List<String>,
    indent: Int,
    connector: TreeConnector = TreeConnector.NONE,
    gutters: List<Int> = emptyList(),
    onActivePath: Boolean = false,
    currentLeaf: Boolean = false,
    foldable: Boolean = false,
    preview: String
): TreeRow = TreeRow(
    id = id,
    path = parentPath + id,
    indent = indent,
    connector = connector,
    gutters = gutters,
    isOnActivePath = onActivePath,
    isCurrentLeaf = currentLeaf,
    isFoldable = foldable,
    preview = preview
)

@Preview(showBackground = true)
@Composable
private fun TreePanelLinearPreview() {
    val rows = listOf(
        row(
            "u1",
            emptyList(),
            0,
            onActivePath = true,
            foldable = true,
            preview = "You: explain MVVM"
        ),
        row(
            "a1",
            listOf("u1"),
            0,
            onActivePath = true,
            currentLeaf = true,
            preview = "Assistant: MVVM separates…"
        )
    )
    MaterialTheme {
        TreePanel(
            rows = rows,
            filter = TreeFilter.DEFAULT,
            onFilterChange = {},
            onNavigate = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TreePanelForkedPreview() {
    // Three-way fork as pi orders it: active branch first, abandoned
    // branches after, the last abandoned sibling on └─.
    val rows = listOf(
        row(
            "u1",
            emptyList(),
            0,
            onActivePath = true,
            foldable = true,
            preview = "You: write a haiku"
        ),
        row(
            "u2b",
            listOf("u1"),
            1,
            TreeConnector.TEE,
            foldable = true,
            onActivePath = true,
            preview = "You: make it longer"
        ),
        row(
            "a2b",
            listOf("u1", "u2b"),
            2,
            gutters = listOf(0),
            onActivePath = true,
            currentLeaf = true,
            preview = "Assistant: silent snow drifts…"
        ),
        row(
            "u2a",
            listOf("u1"),
            1,
            TreeConnector.TEE,
            foldable = true,
            preview = "You: make it shorter"
        ),
        row("a2a", listOf("u1", "u2a"), 2, gutters = listOf(0), preview = "Assistant: snow falls"),
        row(
            "a1",
            listOf("u1"),
            1,
            TreeConnector.ELBOW,
            foldable = true,
            preview = "Assistant: silent snow…"
        ),
        row("a1x", listOf("u1", "a1"), 2, preview = "Assistant: snow drifts")
    )
    // The first answer (a1) starts folded, hiding its child.
    val folded = remember { mutableStateOf(setOf("a1")) }
    MaterialTheme {
        TreePanelContent(
            rows = rows,
            query = "",
            onQueryChange = {},
            foldedIds = folded.value,
            onFoldedIdsChange = { folded.value = it },
            filter = TreeFilter.DEFAULT,
            onFilterChange = {},
            onNavigate = {},
            modifier = Modifier.fillMaxSize()
        )
    }
}
