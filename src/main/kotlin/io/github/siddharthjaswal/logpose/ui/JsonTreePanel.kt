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
        font = JBUI.Fonts.create("JetBrains Mono", 13)
    }
    private val titleLabel = JBLabel(title)
    private val statusLabel = JBLabel()
    private val pretty = Json { prettyPrint = true }
    private var element: JsonElement? = null

    private val cards = CardLayout()
    private val content = JPanel(cards).apply { isOpaque = false }
    private lateinit var headerComp: JComponent

    // find
    private val matches = ArrayList<Int>()
    private var currentMatch = -1
    private val findField = com.intellij.ui.components.JBTextField()
    private val findCount = JBLabel("")
    private val allPainter = javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(Theme.findAll)
    private val findBar = buildFindBar()

    private var rawDirty = true
    private var rawVisible = false
    private lateinit var toggle: Segmented

    init {
        isOpaque = false
        border = JBUI.Borders.empty()

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.isOpaque = false
        tree.font = JBUI.Fonts.create("JetBrains Mono", 13)
        tree.rowHeight = JBUI.scale(22) // ~1.7 line-height for the data/mono spec
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = NodeRenderer()
        tree.emptyText.text = "—"

        content.add(scroll(tree), "tree")
        content.add(scroll(rawPane), "raw")

        headerComp = header()
        val centerWrap = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(findBar, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
        add(headerComp, BorderLayout.NORTH)
        add(centerWrap, BorderLayout.CENTER)
        registerFindShortcut()
    }

    private fun buildFindBar(): JComponent {
        findField.emptyText.text = "Find in $title…"
        findField.preferredSize = java.awt.Dimension(JBUI.scale(220), findField.preferredSize.height)
        findField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = runFind(findField.text)
        })
        findField.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_ENTER -> moveMatch(if (e.isShiftDown) -1 else 1)
                    java.awt.event.KeyEvent.VK_ESCAPE -> hideFind()
                }
            }
        })
        findCount.foreground = Theme.textDim
        findCount.border = JBUI.Borders.empty(0, 8)

        val controls = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(findCount)
            add(iconButton(AllIcons.Actions.PreviousOccurence, "Previous (Shift+Enter)") { moveMatch(-1) })
            add(iconButton(AllIcons.Actions.NextOccurence, "Next (Enter)") { moveMatch(1) })
            add(iconButton(AllIcons.Actions.Close, "Close (Esc)") { hideFind() })
        }
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = Theme.bg2
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(Theme.borderStrong, 0, 0, 1, 0),
                JBUI.Borders.empty(4, 10),
            )
            isVisible = false
            add(findField, BorderLayout.WEST)
            add(controls, BorderLayout.EAST)
        }
    }

    private fun registerFindShortcut() {
        val ks = javax.swing.KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_F,
            java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx,
        )
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(ks, "logposeFind")
        actionMap.put("logposeFind", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = showFind()
        })
    }

    private fun showFind() {
        toggle.choose(1) // switch the segmented control to Raw (renders raw if needed)
        findBar.isVisible = true
        revalidate()
        findField.requestFocusInWindow()
        runFind(findField.text)
    }

    private fun hideFind() {
        findBar.isVisible = false
        rawPane.highlighter.removeAllHighlights()
        matches.clear(); currentMatch = -1
        revalidate()
    }

    private fun runFind(query: String) {
        val hl = rawPane.highlighter
        hl.removeAllHighlights()
        matches.clear(); currentMatch = -1
        if (query.isBlank()) { findCount.text = ""; return }
        // Case-insensitive search on the ORIGINAL text so offsets map to the document
        // (lowercasing can change length for some Unicode and shift highlight positions).
        val text = rawPane.text
        var i = text.indexOf(query, 0, ignoreCase = true)
        while (i >= 0) {
            matches.add(i)
            runCatching { hl.addHighlight(i, i + query.length, allPainter) }
            i = text.indexOf(query, i + query.length, ignoreCase = true)
        }
        if (matches.isNotEmpty()) { currentMatch = 0; showCurrent() }
        updateCount()
    }

    private fun moveMatch(dir: Int) {
        if (matches.isEmpty()) return
        currentMatch = (currentMatch + dir + matches.size) % matches.size
        showCurrent(); updateCount()
    }

    private fun showCurrent() {
        val start = matches[currentMatch]
        val end = start + findField.text.length
        rawPane.select(start, end)
        runCatching { @Suppress("DEPRECATION") rawPane.modelToView(start)?.let { rawPane.scrollRectToVisible(it) } }
    }

    private fun updateCount() {
        findCount.text = if (matches.isEmpty()) "0/0" else "${currentMatch + 1}/${matches.size}"
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width; val h = height
        g2.color = Theme.bg1
        g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc)
        val hh = if (::headerComp.isInitialized) headerComp.height else 0
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

        toggle = Segmented(listOf("Tree", "Raw")) { i -> showMode(raw = i == 1) }
        val actions = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(toggle)
            add(javax.swing.Box.createHorizontalStrut(JBUI.scale(10)))
            add(iconButton(AllIcons.Actions.Find, "Find (⌘F)") { showFind() })
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
        // Raw is rendered lazily — only build it when actually shown (cheap selections).
        rawDirty = true
        if (rawVisible) {
            ensureRaw()
            if (findBar.isVisible) runFind(findField.text)
        }
    }

    fun showMessage(message: String) {
        element = null
        tree.model = DefaultTreeModel(DefaultMutableTreeNode())
        tree.emptyText.text = message
        renderRaw(null); rawDirty = false
    }

    private fun showMode(raw: Boolean) {
        rawVisible = raw
        cards.show(content, if (raw) "raw" else "tree")
        if (raw) ensureRaw()
    }

    private fun ensureRaw() {
        if (rawDirty) { renderRaw(element); rawDirty = false }
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

        fun choose(i: Int) = select(i)

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
