package io.github.siddharthjaswal.logpose.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * A field-by-field editor for a captured JSON response. Every leaf is a row you can edit in
 * place (via the inspector strip); in **merge** mode each leaf also carries an override
 * checkbox — ticked leaves become the merge patch, everything else stays backend-generated.
 * Objects/arrays fold so you can focus on the handful of fields you care about, and a changed
 * value shows the original beside it.
 *
 * Produces either a merge patch ([resultJson] in merge mode — only ticked leaves, arrays merged
 * by index) or the full edited body (replace mode). Falls back cleanly for non-JSON bodies.
 */
class JsonPatchTree : JPanel(BorderLayout()) {

    private val lenient = Json { isLenient = true; ignoreUnknownKeys = true }
    private val pretty = Json { prettyPrint = true }

    private var merge = true
    private var nonJson: String? = null // set when the base body isn't JSON (edit as raw text)

    private val root = JNode("", Kind.OBJECT, JsonObject(emptyMap()))

    // No parent<->child cascade: only individual leaf checks matter.
    private val tree = CheckboxTree(Renderer(), root, CheckboxTreeBase.CheckPolicy(false, false, false, false)).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        rowHeight = JBUI.scale(28)
        border = JBUI.Borders.empty(6, 8)
    }
    private val model get() = tree.model as DefaultTreeModel

    private val hintLabel = JBLabel().apply { foreground = Theme.textMuted; font = JBUI.Fonts.label(11f) }
    private val inspector = Inspector()

    init {
        isOpaque = false
        add(toolbar(), BorderLayout.NORTH)
        add(JBScrollPane(tree).apply {
            border = JBUI.Borders.customLine(Theme.borderStrong, 1)
            viewport.isOpaque = false; isOpaque = false
        }, BorderLayout.CENTER)
        add(inspector, BorderLayout.SOUTH)

        tree.addTreeSelectionListener { inspector.bind(selectedLeaf()) }
    }

    /**
     * Seeds the tree from [baseJson] (the captured response). [current] is any existing rule
     * body — a patch in merge mode (its keys pre-tick), or the full edited body in replace mode.
     */
    fun setBase(baseJson: String?, current: String?, mergeMode: Boolean) {
        merge = mergeMode
        hintLabel.text = if (merge)
            "Tick a field to override it; unticked fields stay backend-generated."
        else
            "Edit any value. Fold sections you don't need."
        val base = parseOrNull(baseJson) ?: parseOrNull(current)
        if (base == null) {
            // Not JSON (or nothing captured) — remember the raw text; the dialog shows the text
            // editor instead in that case, but keep something valid here.
            nonJson = current ?: baseJson
            root.removeAllChildren()
            model.reload()
            inspector.bind(null)
            return
        }
        nonJson = null
        val cur = parseOrNull(current)
        val rebuilt = build("", null, base, cur)
        root.removeAllChildren()
        rebuilt.children().toList().forEach { root.add(it as JNode) }
        root.kind = rebuilt.kind
        root.original = base
        model.reload()
        expandTop()
        inspector.bind(null)
    }

    /** True when the base wasn't JSON — the caller should fall back to the raw text editor. */
    fun isNonJson(): Boolean = nonJson != null

    /** The merge patch (merge mode) or the full edited body (replace mode), pretty-printed. */
    fun resultJson(): String {
        nonJson?.let { return it }
        val el = if (merge) (emitPatch(root) ?: JsonObject(emptyMap())) else emitFull(root)
        return pretty.encodeToString(JsonElement.serializer(), el)
    }

    // ---- building -------------------------------------------------------------------------

    private fun build(label: String, key: String?, el: JsonElement, current: JsonElement?): JNode = when (el) {
        is JsonObject -> JNode(label, Kind.OBJECT, el, objectKey = key).also { n ->
            n.isEnabled = false // container checkbox is inert (hidden in the renderer)
            el.forEach { (k, v) -> n.add(build(k, k, v, (current as? JsonObject)?.get(k))) }
        }
        is JsonArray -> JNode(label, Kind.ARRAY, el, objectKey = key).also { n ->
            n.isEnabled = false
            el.forEachIndexed { i, v -> n.add(build("[$i]", null, v, (current as? JsonArray)?.getOrNull(i))) }
        }
        else -> JNode(label, Kind.LEAF, el, objectKey = key).also { n ->
            n.isChecked = current != null
            if (current != null) n.edited = current
        }
    }

    private fun emitPatch(node: JNode): JsonElement? = when (node.kind) {
        Kind.LEAF -> if (node.isChecked) (node.edited ?: node.original) else null
        Kind.OBJECT -> {
            val entries = node.jChildren().mapNotNull { c -> emitPatch(c)?.let { (c.objectKey ?: "") to it } }
            if (entries.isEmpty()) null else JsonObject(entries.toMap())
        }
        Kind.ARRAY -> {
            val kids = node.jChildren()
            val emits = kids.map { emitPatch(it) }
            if (emits.all { it == null }) null
            // Gaps keep the original element so the index-wise device merge is a no-op there.
            else JsonArray(kids.mapIndexed { i, c -> emits[i] ?: c.original ?: JsonNull })
        }
    }

    private fun emitFull(node: JNode): JsonElement = when (node.kind) {
        Kind.LEAF -> node.edited ?: node.original ?: JsonNull
        Kind.OBJECT -> JsonObject(node.jChildren().associate { (it.objectKey ?: "") to emitFull(it) })
        Kind.ARRAY -> JsonArray(node.jChildren().map { emitFull(it) })
    }

    // ---- toolbar + inspector --------------------------------------------------------------

    private fun toolbar(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(2))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 2, 8, 2)
        add(iconLink(AllIcons.General.Add, "Add a new field") { addField() })
        add(hintLabel)
    }

    private fun addField() {
        val sel = tree.lastSelectedPathComponent as? JNode
        val parent = when {
            sel != null && sel.kind == Kind.OBJECT -> sel
            sel != null && (sel.parent as? JNode)?.kind == Kind.OBJECT -> sel.parent as JNode
            else -> root
        }
        if (parent.kind != Kind.OBJECT) return // arrays: add via editing, not here
        val n = JNode("newField", Kind.LEAF, null, edited = JsonPrimitive(""), objectKey = "newField", added = true)
        n.isChecked = true
        model.insertNodeInto(n, parent, parent.childCount)
        tree.expandPath(pathTo(parent))
        tree.selectionPath = pathTo(n)
        inspector.bind(n)
    }

    private fun selectedLeaf(): JNode? = (tree.lastSelectedPathComponent as? JNode)?.takeIf { it.kind == Kind.LEAF }

    private fun expandTop() {
        // Expand the first two levels so the shape is visible but deep lists stay folded.
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) as JNode
            tree.expandPath(pathTo(child))
        }
        tree.expandPath(pathTo(root))
    }

    private fun pathTo(node: JNode) = javax.swing.tree.TreePath(model.getPathToRoot(node))

    private fun refresh(node: JNode) = model.nodeChanged(node)

    private fun parseOrNull(s: String?): JsonElement? =
        s?.takeIf { it.isNotBlank() }?.let { runCatching { lenient.parseToJsonElement(it) }.getOrNull() }

    private enum class Kind { OBJECT, ARRAY, LEAF }

    private class JNode(
        val label: String,
        var kind: Kind,
        var original: JsonElement?,
        var edited: JsonElement? = null,
        var objectKey: String? = null,
        var added: Boolean = false,
    ) : CheckedTreeNode(label) {
        fun jChildren(): List<JNode> = (0 until childCount).map { getChildAt(it) as JNode }
    }

    /** Renders `key: value` / `key {n}` / `key [n]`; hides the checkbox on containers. */
    private inner class Renderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(
            tree: javax.swing.JTree?, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
        ) {
            val node = value as? JNode ?: return
            // myCheckbox, not the deprecated getCheckbox().
            myCheckbox.isVisible = merge && node.kind == Kind.LEAF
            val tr = textRenderer
            val name = if (node.added) (node.objectKey ?: "") else node.label
            if (name.isNotEmpty()) tr.append("$name", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            when (node.kind) {
                Kind.OBJECT -> tr.append("  {${node.childCount}}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                Kind.ARRAY -> tr.append("  [${node.childCount}]", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                Kind.LEAF -> {
                    if (name.isNotEmpty()) tr.append(": ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    val v = node.edited ?: node.original
                    val changed = node.edited != null && node.edited != node.original
                    tr.append(preview(v), if (changed) CHANGED else valueAttr(v))
                    if (changed && node.original != null) {
                        tr.append("     was ", WAS_LABEL)
                        tr.append(preview(node.original), STRUCK)
                    }
                }
            }
        }
    }

    private inner class Inspector : JPanel() {
        private var node: JNode? = null
        private val keyField = JBTextField(14)
        private val valueField = JBTextField(22)
        private val typeCombo = com.intellij.openapi.ui.ComboBox(arrayOf("string", "number", "boolean", "null"))
        private val removeBtn = LinkLabel("Remove") { removeCurrent() }
        private var updating = false

        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 2, 2, 2)
            add(JBLabel("Field").withMuted()); add(strut(6)); add(keyField); add(strut(10))
            add(JBLabel("Value").withMuted()); add(strut(6)); add(valueField); add(strut(8))
            add(typeCombo); add(strut(10)); add(removeBtn); add(Box.createHorizontalGlue())

            keyField.document.addDocumentListener(onEdit { n -> n.objectKey = keyField.text })
            valueField.document.addDocumentListener(onEdit { applyValue(it) })
            typeCombo.addActionListener { if (!updating) selectedLeaf()?.let { applyValue(it) } }
            bind(null)
        }

        fun bind(n: JNode?) {
            node = n
            updating = true
            val enabled = n != null
            keyField.isEnabled = enabled && n?.added == true
            valueField.isEnabled = enabled
            typeCombo.isEnabled = enabled
            removeBtn.isVisible = n?.added == true
            if (n == null) {
                keyField.text = ""; valueField.text = ""
            } else {
                keyField.text = n.objectKey ?: n.label
                val v = n.edited ?: n.original
                valueField.text = displayValue(v)
                typeCombo.selectedItem = typeOf(v)
            }
            updating = false
        }

        private fun applyValue(n: JNode) {
            val el = toElement(valueField.text, typeCombo.selectedItem as String)
            n.edited = el
            if (merge) n.isChecked = true
            refresh(n)
        }

        private fun onEdit(apply: (JNode) -> Unit) = object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (updating) return
                node?.let { apply(it); refresh(it) }
            }
        }

        private fun removeCurrent() {
            val n = node ?: return
            val parent = n.parent as? JNode ?: return
            model.removeNodeFromParent(n)
            bind(null); refresh(parent)
        }

        private fun strut(px: Int) = Box.createHorizontalStrut(JBUI.scale(px))
    }

    // ---- value helpers --------------------------------------------------------------------

    private fun preview(el: JsonElement?): String = when (el) {
        null, is JsonNull -> "null"
        is JsonPrimitive -> if (el.isString) "\"${el.content.take(60)}\"" else el.content
        is JsonObject -> "{…}"
        is JsonArray -> "[…]"
    }

    private fun displayValue(el: JsonElement?): String = when (el) {
        null, is JsonNull -> ""
        is JsonPrimitive -> el.content
        else -> el.toString()
    }

    private fun typeOf(el: JsonElement?): String = when {
        el == null || el is JsonNull -> "null"
        el is JsonPrimitive && el.isString -> "string"
        el is JsonPrimitive && (el.content == "true" || el.content == "false") -> "boolean"
        el is JsonPrimitive -> "number"
        else -> "string"
    }

    private fun valueAttr(el: JsonElement?): SimpleTextAttributes = when {
        el == null || el is JsonNull -> SimpleTextAttributes(Font.PLAIN, Theme.jsonNull)
        el is JsonPrimitive && el.isString -> SimpleTextAttributes(Font.PLAIN, Theme.jsonString)
        el is JsonPrimitive && (el.content == "true" || el.content == "false") -> SimpleTextAttributes(Font.PLAIN, Theme.jsonBool)
        el is JsonPrimitive -> SimpleTextAttributes(Font.PLAIN, Theme.jsonNumber)
        else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
    }

    private fun toElement(text: String, type: String): JsonElement = when (type) {
        "number" -> text.toLongOrNull()?.let { JsonPrimitive(it) }
            ?: text.toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(text)
        "boolean" -> JsonPrimitive(text.trim().equals("true", ignoreCase = true))
        "null" -> JsonNull
        else -> JsonPrimitive(text)
    }

    private fun JBLabel.withMuted(): JBLabel = apply { foreground = Theme.textDim; font = JBUI.Fonts.label(11f) }

    /**
     * A [LinkLabel] carrying a leading icon. Not an [IconButton]: the visible text is the point —
     * "Add a new field" is discoverable, a bare `+` with a tooltip is not.
     */
    private fun iconLink(icon: javax.swing.Icon, text: String, onClick: () -> Unit) =
        LinkLabel(text, onClick).apply {
            this.icon = icon
            iconTextGap = JBUI.scale(4)
        }

    private companion object {
        val CHANGED = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, Theme.accent)
        val WAS_LABEL = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, Theme.textMuted)
        val STRUCK = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_STRIKEOUT or SimpleTextAttributes.STYLE_SMALLER, Theme.textMuted,
        )
    }
}
