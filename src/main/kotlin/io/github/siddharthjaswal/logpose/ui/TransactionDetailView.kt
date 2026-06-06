package io.github.siddharthjaswal.logpose.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Structured, collapsible view of a single [Transaction]: separate Request and
 * Response sections, headers as a group, and — the key bit — the body string is
 * parsed back into a real, explorable JSON tree instead of an escaped one-liner.
 */
class TransactionDetailView : JPanel(BorderLayout()) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode()))

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NodeRenderer()
        tree.emptyText.text = "Select a request to inspect"

        add(buildToolbar(), BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    private fun buildToolbar(): JComponent {
        val group = DefaultActionGroup().apply {
            add(object : AnAction("Expand All", null, AllIcons.Actions.Expandall) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = TreeUtil.expandAll(tree)
            })
            add(object : AnAction("Collapse All", null, AllIcons.Actions.Collapseall) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = TreeUtil.collapseAll(tree, 1)
            })
        }
        val tb = ActionManager.getInstance().createActionToolbar("LogPoseDetail", group, true)
        tb.targetComponent = this
        return tb.component
    }

    fun show(tx: Transaction?) {
        tree.emptyText.text = "Select a request to inspect"
        val root = DefaultMutableTreeNode()
        if (tx != null) build(root, tx)
        tree.model = DefaultTreeModel(root)
        // Open sections + first body level; keep deep JSON collapsed.
        expandToDepth(root, maxDepth = 3)
    }

    fun showError(message: String) {
        tree.model = DefaultTreeModel(DefaultMutableTreeNode())
        tree.emptyText.text = message
    }

    private fun build(root: DefaultMutableTreeNode, tx: Transaction) {
        val overview = section("Overview")
        overview.add(leaf("id", tx.id, Kind.STRING))
        tx.durationMillis?.let { overview.add(leaf("duration", "$it ms", Kind.NUMBER)) }
        if (tx.startedAtMillis > 0) overview.add(leaf("startedAtMillis", tx.startedAtMillis.toString(), Kind.NUMBER))
        tx.error?.let { overview.add(leaf("error", it, Kind.STRING)) }
        root.add(overview)

        val req = section("Request")
        req.add(leaf("method", tx.request.method, Kind.KEYWORD))
        req.add(leaf("url", tx.request.url, Kind.STRING))
        if (tx.request.host.isNotBlank()) req.add(leaf("host", tx.request.host, Kind.STRING))
        if (tx.request.path.isNotBlank()) req.add(leaf("path", tx.request.path, Kind.STRING))
        req.add(headers(tx.request.headers))
        req.add(bodyNode(tx.request.body))
        root.add(req)

        val res = section("Response")
        tx.response?.let { r ->
            res.add(leaf("code", r.code.toString(), Kind.NUMBER))
            if (r.message.isNotBlank()) res.add(leaf("message", r.message, Kind.STRING))
            res.add(headers(r.headers))
            res.add(bodyNode(r.body))
        } ?: res.add(leaf(null, if (tx.error != null) "(failed)" else "(pending)", Kind.NULL))
        root.add(res)
    }

    private fun headers(map: Map<String, String>): DefaultMutableTreeNode {
        val node = node(LpNode("headers", null, suffix = "{${map.size}}"))
        map.forEach { (k, v) -> node.add(leaf(k, v, Kind.STRING)) }
        return node
    }

    private fun bodyNode(body: Body?): DefaultMutableTreeNode {
        if (body == null) return leaf("body", "(none)", Kind.NULL)
        val suffix = buildString {
            append(body.contentType ?: "?")
            if (body.sizeBytes >= 0) append(" · ${humanSize(body.sizeBytes)}")
            if (body.truncated) append(" · truncated")
        }
        val node = node(LpNode("body", null, suffix = suffix))

        when {
            body.parts != null -> body.parts!!.forEachIndexed { i, p ->
                val part = node(LpNode("part[$i]", null, suffix = p.filename ?: p.name ?: ""))
                p.name?.let { part.add(leaf("name", it, Kind.STRING)) }
                p.filename?.let { part.add(leaf("filename", it, Kind.STRING)) }
                p.contentType?.let { part.add(leaf("contentType", it, Kind.STRING)) }
                part.add(leaf("size", humanSize(p.sizeBytes), Kind.NUMBER))
                node.add(part)
            }
            body.text != null -> {
                val element = runCatching { json.parseToJsonElement(body.text!!) }.getOrNull()
                if (element != null) addElement(node, null, element)
                else node.add(leaf(null, body.text, Kind.STRING))
            }
            else -> node.add(leaf(null, "(empty)", Kind.NULL))
        }
        return node
    }

    private fun addElement(parent: DefaultMutableTreeNode, key: String?, el: kotlinx.serialization.json.JsonElement) {
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
            is JsonNull -> parent.add(leaf(key, "null", Kind.NULL))
            is JsonPrimitive -> {
                val kind = when {
                    el.isString -> Kind.STRING
                    el.content == "true" || el.content == "false" -> Kind.BOOL
                    else -> Kind.NUMBER
                }
                parent.add(leaf(key, el.content, kind))
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

    // ---- node model ----

    enum class Kind { NONE, STRING, NUMBER, BOOL, NULL, KEYWORD }
    data class LpNode(val key: String?, val value: String?, val kind: Kind = Kind.NONE, val suffix: String? = null, val section: Boolean = false)

    private fun node(n: LpNode) = DefaultMutableTreeNode(n)
    private fun leaf(key: String?, value: String?, kind: Kind) = DefaultMutableTreeNode(LpNode(key, value, kind))
    private fun section(title: String) = DefaultMutableTreeNode(LpNode(title, null, section = true))

    private fun humanSize(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024))
    }

    private class NodeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val n = ((value as? DefaultMutableTreeNode)?.userObject as? LpNode) ?: return
            when {
                n.section -> append(n.key ?: "", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                n.key != null -> append(n.key, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, KEY_COLOR))
            }
            if (n.value != null) {
                if (n.key != null && !n.section) append(": ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append(n.value, attrFor(n.kind))
            }
            n.suffix?.takeIf { it.isNotBlank() }?.let {
                append("   $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }

        private fun attrFor(kind: Kind) = when (kind) {
            Kind.STRING -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, STRING_COLOR)
            Kind.NUMBER -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, NUMBER_COLOR)
            Kind.BOOL, Kind.KEYWORD -> SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, KEYWORD_COLOR)
            Kind.NULL -> SimpleTextAttributes.GRAYED_ATTRIBUTES
            Kind.NONE -> SimpleTextAttributes.REGULAR_ATTRIBUTES
        }

        companion object {
            private val KEY_COLOR = JBColor(0x871094, 0xCB7AD9)     // purple-ish key
            private val STRING_COLOR = JBColor(0x067D17, 0x6A8759)  // green string
            private val NUMBER_COLOR = JBColor(0x1750EB, 0x6897BB)  // blue number
            private val KEYWORD_COLOR = JBColor(0x0033B3, 0xCC7832) // keyword/bool
        }
    }
}
