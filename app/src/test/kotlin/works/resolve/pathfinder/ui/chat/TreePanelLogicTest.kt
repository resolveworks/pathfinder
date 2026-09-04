package works.resolve.pathfinder.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TreePanelLogicTest {

    private fun row(id: String, path: List<String>, preview: String): TreeRow = TreeRow(
        id = id,
        path = path,
        indent = path.size - 1,
        connector = TreeConnector.NONE,
        gutters = emptyList(),
        isOnActivePath = false,
        isCurrentLeaf = false,
        isFoldable = false,
        body = TreeRowBody.Text(preview)
    )

    private val tree = listOf(
        row("u1", listOf("u1"), "You: explain the MVVM pattern"),
        row("a1", listOf("u1", "a1"), "Assistant: MVVM separates UI and state"),
        row("u2", listOf("u1", "a1", "u2"), "You: show a Kotlin example"),
        row("a2", listOf("u1", "a1", "u2", "a2"), "Assistant: here is a ViewModel")
    )

    // ---- search and fold visibility ----

    @Test
    fun `empty query and no folds return all rows`() {
        assertEquals(tree, filterTreeRows(tree, "", emptySet()))
        assertEquals(tree, filterTreeRows(tree, "   ", emptySet()))
    }

    @Test
    fun `single token matches case-insensitively`() {
        val result = filterTreeRows(tree, "MVVM", emptySet())
        assertEquals(listOf("u1", "a1"), result.map { it.id })
    }

    @Test
    fun `multiple tokens must all match (AND) on the same row`() {
        val result = filterTreeRows(tree, "kotlin SHOW", emptySet())
        assertEquals(listOf("u2"), result.map { it.id })
    }

    @Test
    fun `tool rows match on the tool name and the parsed input`() {
        val tree = listOf(
            row("u1", listOf("u1"), "You: find compose docs"),
            row("t1", listOf("u1", "t1"), "").copy(
                body = TreeRowBody.Tool("web_search", "kotlin compose")
            ),
            row("a1", listOf("u1", "t1", "a1"), "Assistant: here is what I found")
        )
        assertEquals(
            listOf("t1"),
            filterTreeRows(tree, "web_search", emptySet()).map { it.id }
        )
        assertEquals(
            listOf("t1"),
            filterTreeRows(tree, "COMPOSE kotlin", emptySet()).map { it.id }
        )
        // The input does not leak into other rows' matching.
        assertEquals(
            emptyList<TreeRow>(),
            filterTreeRows(tree, "found web_search", emptySet())
        )
    }

    @Test
    fun `token matching no row yields empty result`() {
        assertEquals(emptyList<TreeRow>(), filterTreeRows(tree, "nonexistent token", emptySet()))
    }

    @Test
    fun `folding a row hides its descendants but not siblings`() {
        val forked = listOf(
            row("root", listOf("root"), "root"),
            row("left", listOf("root", "left"), "left child"),
            row("leftChild", listOf("root", "left", "leftChild"), "left grandchild"),
            row("right", listOf("root", "right"), "right child")
        )
        val result = filterTreeRows(forked, "", setOf("left"))
        assertEquals(listOf("root", "left", "right"), result.map { it.id })
    }

    @Test
    fun `fold mid-tree hides only the subtree below it`() {
        val result = filterTreeRows(tree, "", setOf("a1"))
        assertEquals(listOf("u1", "a1"), result.map { it.id })

        val deeper = filterTreeRows(tree, "", setOf("u2"))
        assertEquals(listOf("u1", "a1", "u2"), deeper.map { it.id })
    }

    @Test
    fun `folded ancestor also hides search matches beneath it`() {
        // u2 itself stays visible but does not match the query.
        val result = filterTreeRows(tree, "ViewModel", setOf("u2"))
        assertEquals(emptyList<TreeRow>(), result)
    }

    @Test
    fun `folding a leaf id hides nothing but itself stays visible`() {
        val result = filterTreeRows(tree, "", setOf("a2"))
        assertEquals(listOf("u1", "a1", "u2", "a2"), result.map { it.id })
    }

    // ---- guide layout ----

    private fun guideRow(
        indent: Int,
        connector: TreeConnector = TreeConnector.NONE,
        gutters: List<Int> = emptyList(),
        foldable: Boolean = false
    ): TreeRow = TreeRow(
        id = "x",
        path = List(indent + 1) { "a$it" },
        indent = indent,
        connector = connector,
        gutters = gutters,
        isOnActivePath = false,
        isCurrentLeaf = false,
        isFoldable = foldable,
        body = TreeRowBody.Text("p")
    )

    @Test
    fun `roots and flat chains have no guide cells`() {
        assertEquals(emptyList<TreeGuideCell>(), treeGuideCells(guideRow(indent = 0)))
    }

    @Test
    fun `connectors occupy the final cell before the body`() {
        assertEquals(listOf(TreeGuideCell.TEE), treeGuideCells(guideRow(1, TreeConnector.TEE)))
        assertEquals(listOf(TreeGuideCell.ELBOW), treeGuideCells(guideRow(1, TreeConnector.ELBOW)))
    }

    @Test
    fun `gutters occupy ancestor branch levels`() {
        assertEquals(
            listOf(TreeGuideCell.GUTTER, TreeGuideCell.EMPTY),
            treeGuideCells(guideRow(2, gutters = listOf(0)))
        )
        assertEquals(
            listOf(TreeGuideCell.EMPTY, TreeGuideCell.EMPTY),
            treeGuideCells(guideRow(2))
        )
        assertEquals(
            listOf(TreeGuideCell.GUTTER, TreeGuideCell.EMPTY, TreeGuideCell.ELBOW),
            treeGuideCells(guideRow(3, TreeConnector.ELBOW, gutters = listOf(0)))
        )
        assertEquals(
            listOf(TreeGuideCell.GUTTER, TreeGuideCell.GUTTER, TreeGuideCell.TEE),
            treeGuideCells(guideRow(3, TreeConnector.TEE, gutters = listOf(0, 1)))
        )
    }
}
