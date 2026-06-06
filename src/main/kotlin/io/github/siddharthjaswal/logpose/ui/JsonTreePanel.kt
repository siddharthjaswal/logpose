package io.github.siddharthjaswal.logpose.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * A titled section that renders a [JsonElement] as a collapsible, syntax-colored
 * tree, with a toolbar for Expand-All / Collapse-All / Copy-as-JSON.
 *
 * Reused for the Overview, Request and Response panes — each just gets a different
 * element. Copy yields the pretty-printed JSON of whatever this section holds.
 */
class JsonTreePanel(title: String) : JPanel(BorderLayout()) {

    private val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode()))
    private val pretty = Json { prettyPrint = true }
    private var element: JsonElement? = null

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NodeRenderer()
        tree.emptyText.text = "—"

        add(header(title), BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    private fun header(title: String): JComponent {
        val label = JBLabel(title).apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(3, 8)
        }
        val actions = DefaultActionGroup().apply {
            add(simpleAction("Expand All", AllIcons.Actions.Expandall) { TreeUtil.expandAll(tree) })
            add(simpleAction("Collapse All", AllIcons.Actions.Collapseall) { TreeUtil.collapseAll(tree, 0) })
            add(simpleAction("Copy as JSON", AllIcons.Actions.Copy) { copyJson() })
        }
        val tb = ActionManager.getInstance().createActionToolbar("LogPoseSection", actions, true)
        tb.targetComponent = this

        return JPanel(BorderLayout()).apply {
            add(label, BorderLayout.WEST)
            add(tb.component, BorderLayout.EAST)
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
        }
    }

    fun setElement(el: JsonElement?) {
        element = el
        tree.emptyText.text = "—"
        val root = DefaultMutableTreeNode()
        if (el != null) addElement(root, null, el)
        tree.model = DefaultTreeModel(root)
        expandToDepth(root, maxDepth = 3)
    }

    fun showMessage(message: String) {
        element = null
        tree.model = DefaultTreeModel(DefaultMutableTreeNode())
        tree.emptyText.text = message
    }

    fun copyJson() {
        val text = element?.let { pretty.encodeToString(JsonElement.serializer(), it) } ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun simpleAction(text: String, icon: javax.swing.Icon, run: () -> Unit) =
        object : AnAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = run()
        }

    private fun addElement(parent: DefaultMutableTreeNode, key: String?, el: JsonElement) {
        when (el) {
            is JsonObject -> {
                val container = if (key == null) parent
                else node(LpNode(key, null, suffix = "{${el.size}}")).also { parent.add(it) }
                el.forEach { (k, v) -> addElement(container, k, v) }
            }
            is JsonArray -> {
                val container = if (key == null) parent
                else node(LpNode(key, null, suffix = "[${el.size}]")).also { parent.add(it) }
                el.forEachIndexed { i, v -> addElement(container, i.toString(), v) }
            }
            is JsonNull -> parent.add(node(LpNode(key, "null", Kind.NULL)))
            is JsonPrimitive -> {
                val kind = when {
                    el.isString -> Kind.STRING
                    el.content == "true" || el.content == "false" -> Kind.BOOL
                    else -> Kind.NUMBER
                }
                parent.add(node(LpNode(key, el.content, kind)))
            }
        }
    }

    private fun expandToDepth(root: DefaultMutableTreeNode, maxDepth: Int) {
        fun walk(node: DefaultMutableTreeNode, depth: Int) {
            if (depth >= maxDepth) return
            tree.expandPath(javax.swing.tree.TreePath(node.path))
            for (i in 0 until node.childCount) walk(node.getChildAt(i) as DefaultMutableTreeNode, depth + 1)
        }
        for (i in 0 until root.childCount) walk(root.getChildAt(i) as DefaultMutableTreeNode, 1)
    }

    private fun node(n: LpNode) = DefaultMutableTreeNode(n)

    private enum class Kind { STRING, NUMBER, BOOL, NULL }
    private data class LpNode(val key: String?, val value: String?, val kind: Kind? = null, val suffix: String? = null)

    private class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val n = ((value as? DefaultMutableTreeNode)?.userObject as? LpNode) ?: return
            n.key?.let { append(it, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, KEY_COLOR)) }
            if (n.value != null) {
                if (n.key != null) append(": ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(n.value, attrFor(n.kind))
            }
            n.suffix?.takeIf { it.isNotBlank() }?.let { append("   $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
        }

        private fun attrFor(kind: Kind?) = when (kind) {
            Kind.STRING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, STRING_COLOR)
            Kind.NUMBER -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, NUMBER_COLOR)
            Kind.BOOL -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, KEYWORD_COLOR)
            Kind.NULL -> SimpleTextAttributes.GRAYED_ATTRIBUTES
            null -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }

        companion object {
            private val KEY_COLOR = JBColor(0x871094, 0xCB7AD9)
            private val STRING_COLOR = JBColor(0x067D17, 0x6A8759)
            private val NUMBER_COLOR = JBColor(0x1750EB, 0x6897BB)
            private val KEYWORD_COLOR = JBColor(0x0033B3, 0xCC7832)
        }
    }
}
