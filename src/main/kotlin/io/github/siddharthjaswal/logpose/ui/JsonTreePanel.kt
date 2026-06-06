package io.github.siddharthjaswal.logpose.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.JTree
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * A titled card section that shows a [JsonElement] either as a collapsible,
 * syntax-colored tree (default) or as syntax-colored pretty-printed raw JSON.
 *
 * Painted as a rounded card (bg1) with a rounded-top bg2 header band carrying the
 * title, a Tree/Raw segmented toggle, and expand/collapse/copy actions.
 */
class JsonTreePanel(private val title: String, private val titleColor: () -> JBColor? = { null }) :
    JPanel(BorderLayout()) {

    private val arc = JBUI.scale(10)

    private val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode()))
    private val rawPane = JTextPane().apply {
        isEditable = false
        background = Theme.bg1
        border = JBUI.Borders.empty(6, 12)
        font = JBUI.Fonts.create("JetBrains Mono", 12)
    }
    private val titleLabel = JBLabel(title)
    private val statusLabel = JBLabel()
    private val pretty = Json { prettyPrint = true }
    private var element: JsonElement? = null

    private val cards = CardLayout()
    private val content = JPanel(cards).apply { isOpaque = false }
    private lateinit var headerComp: JComponent

    init {
        isOpaque = false
        border = JBUI.Borders.empty()

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.isOpaque = false
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NodeRenderer()
        tree.emptyText.text = "—"

        content.add(scroll(tree), "tree")
        content.add(scroll(rawPane), "raw")

        headerComp = header()
        add(headerComp, BorderLayout.NORTH)
        add(content, BorderLayout.CENTER)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width; val h = height
        g2.color = Theme.bg1
        g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc)
        val hh = headerComp.height
        if (hh > 0) {
            val clip = g2.clip
            g2.clip(RoundRectangle2D.Float(0f, 0f, (w - 1).toFloat(), (h - 1).toFloat(), arc.toFloat(), arc.toFloat()))
            g2.color = Theme.bg2
            g2.fillRect(0, 0, w, hh)
            g2.clip = clip
            g2.color = Theme.borderStrong
            g2.drawLine(0, hh - 1, w - 1, hh - 1)
        }
        g2.color = Theme.borderSubtle
        g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc)
        g2.dispose()
    }

    private fun scroll(c: JComponent) = JBScrollPane(c).apply {
        border = JBUI.Borders.empty(2, 2, 6, 2)
        viewport.isOpaque = false
        isOpaque = false
    }

    private fun header(): JComponent {
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD)
        titleLabel.foreground = Theme.text
        statusLabel.foreground = Theme.textDim
        statusLabel.border = JBUI.Borders.emptyLeft(8)

        val titleBox = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(titleLabel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.CENTER)
        }

        val toggle = Segmented(listOf("Tree", "Raw")) { i -> cards.show(content, if (i == 1) "raw" else "tree") }
        val actions = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(toggle)
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(10)))
            add(iconButton(AllIcons.Actions.Expandall, "Expand all") { TreeUtil.expandAll(tree) })
            add(iconButton(AllIcons.Actions.Collapseall, "Collapse all") { TreeUtil.collapseAll(tree, 0) })
            add(iconButton(AllIcons.Actions.Copy, "Copy as JSON") { copyJson() })
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(5, 12)
            add(titleBox, BorderLayout.WEST)
            add(actions, BorderLayout.EAST)
        }
    }

    private fun iconButton(icon: Icon, tip: String, onClick: () -> Unit) = JLabel(icon).apply {
        toolTipText = tip
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(2, 4)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = onClick()
        })
    }

    fun setStatus(text: String?) {
        statusLabel.text = text ?: ""
        titleColor()?.let { titleLabel.foreground = it }
    }

    fun setElement(el: JsonElement?) {
        element = el
        tree.emptyText.text = "—"
        val root = DefaultMutableTreeNode()
        if (el != null) addElement(root, null, el)
        tree.model = DefaultTreeModel(root)
        expandToDepth(root, maxDepth = 3)
        renderRaw(el)
    }

    fun showMessage(message: String) {
        element = null
        tree.model = DefaultTreeModel(DefaultMutableTreeNode())
        tree.emptyText.text = message
        renderRaw(null)
    }

    fun copyJson() {
        val text = element?.let { pretty.encodeToString(JsonElement.serializer(), it) } ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        Toast.show(this, "$title JSON copied")
    }

    // ---- raw (syntax-colored) ----

    private fun renderRaw(el: JsonElement?) {
        val doc = rawPane.styledDocument
        doc.remove(0, doc.length)
        if (el != null) writeJson(el, 0)
        rawPane.caretPosition = 0
    }

    private fun writeJson(el: JsonElement, indent: Int) {
        when (el) {
            is JsonObject -> {
                if (el.isEmpty()) { put("{}", Theme.jsonPunct); return }
                put("{\n", Theme.jsonPunct)
                val keys = el.keys.toList()
                keys.forEachIndexed { i, k ->
                    put("  ".repeat(indent + 1), Theme.jsonPunct)
                    put("\"$k\"", Theme.jsonKey); put(": ", Theme.jsonPunct)
                    writeJson(el.getValue(k), indent + 1)
                    put(if (i < keys.size - 1) ",\n" else "\n", Theme.jsonPunct)
                }
                put("  ".repeat(indent), Theme.jsonPunct); put("}", Theme.jsonPunct)
            }
            is JsonArray -> {
                if (el.isEmpty()) { put("[]", Theme.jsonPunct); return }
                put("[\n", Theme.jsonPunct)
                el.forEachIndexed { i, v ->
                    put("  ".repeat(indent + 1), Theme.jsonPunct)
                    writeJson(v, indent + 1)
                    put(if (i < el.size - 1) ",\n" else "\n", Theme.jsonPunct)
                }
                put("  ".repeat(indent), Theme.jsonPunct); put("]", Theme.jsonPunct)
            }
            is JsonNull -> put("null", Theme.jsonNull)
            is JsonPrimitive -> when {
                el.isString -> put("\"${el.content}\"", Theme.jsonString)
                el.content == "true" || el.content == "false" -> put(el.content, Theme.jsonBool)
                else -> put(el.content, Theme.jsonNumber)
            }
        }
    }

    private fun put(text: String, color: Color) {
        val attrs = SimpleAttributeSet().apply { StyleConstants.setForeground(this, color) }
        rawPane.styledDocument.insertString(rawPane.styledDocument.length, text, attrs)
    }

    // ---- tree ----

    private fun addElement(parent: DefaultMutableTreeNode, key: String?, el: JsonElement) {
        when (el) {
            is JsonObject -> {
                val preview = el.keys.take(4).joinToString(", ") + if (el.size > 4) ", …" else ""
                val container = if (key == null) parent
                else node(LpNode(key, null, suffix = "{${el.size}}", preview = preview)).also { parent.add(it) }
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
    private data class LpNode(
        val key: String?, val value: String?, val kind: Kind? = null,
        val suffix: String? = null, val preview: String? = null,
    )

    /** Two-segment rounded toggle (Tree / Raw). */
    private class Segmented(private val items: List<String>, private val onChange: (Int) -> Unit) : JPanel() {
        private var sel = 0
        private val labels = items.mapIndexed { i, t ->
            JLabel(t, JLabel.CENTER).apply {
                font = JBUI.Fonts.label(11f)
                border = JBUI.Borders.empty(2, 12)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = select(i)
                })
            }
        }

        init {
            isOpaque = false
            layout = java.awt.GridLayout(1, items.size, 0, 0)
            border = JBUI.Borders.empty(2)
            labels.forEach { add(it) }
            update()
        }

        private fun select(i: Int) {
            if (sel == i) return
            sel = i; update(); onChange(i)
        }

        private fun update() {
            labels.forEachIndexed { i, l -> l.foreground = if (i == sel) Theme.text else Theme.textDim }
            repaint()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = Theme.bg3
            g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
            val l = labels[sel]
            g2.color = Theme.bg0
            g2.fillRoundRect(l.x + 1, l.y + 1, l.width - 2, l.height - 2, 6, 6)
            g2.dispose()
        }
    }

    private inner class NodeRenderer : ColoredTreeCellRenderer() {
        init { isOpaque = false }
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val n = ((value as? DefaultMutableTreeNode)?.userObject as? LpNode) ?: return
            n.key?.let { append(it, attr(Theme.jsonKey)) }
            if (n.value != null) {
                if (n.key != null) append(": ", attr(Theme.jsonPunct))
                append(n.value, attrFor(n.kind))
            }
            n.suffix?.takeIf { it.isNotBlank() }?.let { append("  $it", attr(Theme.jsonCount)) }
            if (!expanded && !n.preview.isNullOrBlank()) append("   ${n.preview}", attr(Theme.jsonPunct, italic = true))
        }

        private fun attrFor(kind: Kind?) = when (kind) {
            Kind.STRING -> attr(Theme.jsonString)
            Kind.NUMBER -> attr(Theme.jsonNumber)
            Kind.BOOL -> attr(Theme.jsonBool)
            Kind.NULL -> attr(Theme.jsonNull)
            null -> attr(Theme.text)
        }

        private fun attr(color: JBColor, italic: Boolean = false): SimpleTextAttributes {
            val style = if (italic) SimpleTextAttributes.STYLE_ITALIC else SimpleTextAttributes.STYLE_PLAIN
            return SimpleTextAttributes(style, color)
        }
    }
}
