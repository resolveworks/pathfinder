package works.resolve.aletheia.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import works.resolve.aletheia.R

/**
 * Panel rendering a conversation's branching history as a navigable tree,
 * ported from pi's /tree view: role-prefixed one-line previews, active-path
 * highlighting, a current-leaf marker, and foldable branch points. Rows are
 * standard Material 3 [ListItem]s indented by depth; the role prefix lives
 * in the preview text itself.
 *
 * The panel is purely presentational: it consumes [TreeRow]s produced by the
 * tree projection and reports navigation taps; fold and search state are
 * panel-local.
 *
 * @param rows flattened tree rows, root first
 * @param filter current filter mode
 * @param onFilterChange invoked when the user picks another filter
 * @param onNavigate invoked with a row's entry id when the row is tapped
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreePanel(
    rows: List<TreeRow>,
    filter: TreeFilter,
    onFilterChange: (TreeFilter) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Folded branch-point ids. Cleared whenever the search query changes so
    // matches inside collapsed branches become visible (pi does the same).
    var foldedIds by rememberSaveable(
        stateSaver = listSaver<Set<String>, String>(save = { it.toList() }, restore = { it.toSet() })
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
) {
    Column(modifier = modifier) {
        TreePanelHeader(
            query = query,
            onQueryChange = { newQuery ->
                // Fold-clear on query change so matches inside collapsed
                // branches become visible (pi does the same).
                onFoldedIdsChange(emptySet())
                onQueryChange(newQuery)
            },
            filter = filter,
            onFilterChange = onFilterChange,
        )

        val visibleRows = filterTreeRows(rows = rows, query = query, foldedIds = foldedIds)
        when {
            rows.isEmpty() -> EmptyTreeText(stringResource(R.string.tree_empty))
            visibleRows.isEmpty() -> EmptyTreeText(stringResource(R.string.tree_no_matches))
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(visibleRows, key = { it.id }) { row ->
                    TreeRowItem(
                        row = row,
                        folded = row.isBranchPoint && row.id in foldedIds,
                        onToggleFold = {
                            onFoldedIdsChange(
                                if (row.id in foldedIds) foldedIds - row.id else foldedIds + row.id
                            )
                        },
                        onNavigate = { onNavigate(row.id) },
                    )
                }
            }
        }
    }
}

/**
 * Pure visibility filter: search tokens (case-insensitive AND over the
 * preview) select candidate rows, then rows hidden under a folded ancestor
 * (any path element except the row's own id) are dropped.
 */
internal fun filterTreeRows(
    rows: List<TreeRow>,
    query: String,
    foldedIds: Set<String>,
): List<TreeRow> {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matched = if (tokens.isEmpty()) {
        rows
    } else {
        rows.filter { row -> tokens.all { row.preview.lowercase().contains(it) } }
    }
    return matched.filter { row ->
        // path ends with the row's own id; every element before it is an ancestor
        row.path.dropLast(1).none { it in foldedIds }
    }
}

@Composable
private fun TreePanelHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: TreeFilter,
    onFilterChange: (TreeFilter) -> Unit,
    modifier: Modifier = Modifier,
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
                            contentDescription = stringResource(R.string.tree_clear_search),
                        )
                    }
                }
            },
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
                    },
                )
            }
        }
    }
}

@Composable
private fun TreeRowItem(
    row: TreeRow,
    folded: Boolean,
    onToggleFold: () -> Unit,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onNavigate)
            // Depth indent replaces pi's │ tree guides: each level shifts the
            // row start, the standard indented-list idiom on Android.
            .padding(start = (row.depth * TREE_INDENT_DP).dp),
        colors = ListItemDefaults.colors(
            containerColor = if (row.isOnActivePath) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        headlineContent = {
            Text(
                text = row.preview,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.isCurrentLeaf) {
                    Text(
                        text = stringResource(R.string.tree_current_leaf),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                // Fold affordance mirrors pi's fold markers: a chevron on
                // expanded branch points, "+" when folded.
                if (row.isBranchPoint) {
                    IconButton(onClick = onToggleFold) {
                        Icon(
                            imageVector = if (folded) Icons.Filled.Add else Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(
                                if (folded) R.string.tree_unfold_branch else R.string.tree_fold_branch
                            ),
                        )
                    }
                }
            }
        },
    )
}

/** Start inset per tree depth level, in dp. */
private val TREE_INDENT_DP = 16

@Composable
private fun EmptyTreeText(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

// --- Previews -------------------------------------------------------------

private fun row(
    id: String,
    parentPath: List<String>,
    depth: Int,
    onActivePath: Boolean = false,
    currentLeaf: Boolean = false,
    user: Boolean = false,
    branchPoint: Boolean = false,
    preview: String,
): TreeRow = TreeRow(
    id = id,
    path = parentPath + id,
    depth = depth,
    isOnActivePath = onActivePath,
    isCurrentLeaf = currentLeaf,
    isUser = user,
    isBranchPoint = branchPoint,
    preview = preview,
)

@Preview(showBackground = true)
@Composable
private fun TreePanelLinearPreview() {
    val rows = listOf(
        row("u1", emptyList(), 0, onActivePath = true, user = true, preview = "You: explain MVVM"),
        row("a1", listOf("u1"), 1, onActivePath = true, currentLeaf = true, preview = "Assistant: MVVM separates…"),
    )
    MaterialTheme {
        TreePanel(
            rows = rows,
            filter = TreeFilter.DEFAULT,
            onFilterChange = {},
            onNavigate = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun TreePanelForkedPreview() {
    // Root → branch point → abandoned branch (folded) and the active branch.
    val rows = listOf(
        row("u1", emptyList(), 0, onActivePath = true, user = true, preview = "You: write a haiku"),
        row("a1", listOf("u1"), 1, onActivePath = true, branchPoint = true, preview = "Assistant: silent snow…"),
        // Abandoned branch (off the active path), itself a fork, folded here
        row("u2a", listOf("u1", "a1"), 2, user = true, branchPoint = true, preview = "You: make it shorter"),
        row("a2a", listOf("u1", "a1", "u2a"), 3, preview = "Assistant: snow falls"),
        row("a2c", listOf("u1", "a1", "u2a"), 3, preview = "Assistant: snow drifts"),
        // Active branch
        row("u2b", listOf("u1", "a1"), 2, onActivePath = true, user = true, preview = "You: make it longer"),
        row("a2b", listOf("u1", "a1", "u2b"), 3, onActivePath = true, currentLeaf = true, preview = "Assistant: silent snow drifts down…"),
    )
    // The abandoned branch point (u2a) starts folded.
    val folded = remember { mutableStateOf(setOf("u2a")) }
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
            modifier = Modifier.fillMaxSize(),
        )
    }
}
