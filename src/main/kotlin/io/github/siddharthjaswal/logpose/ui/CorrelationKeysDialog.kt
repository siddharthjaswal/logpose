package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.analysis.CorrelationKeys
import io.github.siddharthjaswal.logpose.analysis.Suggestion
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * The project's correlation vocabulary: the keys that turn `Show waterfall — trace d107086f` into
 * `Show waterfall — order_id 21053953` (PRD §4.1).
 *
 * Two rules give this dialog its shape:
 *
 *  - **Nothing here is on until a human turns it on.** The list is seeded from
 *    [io.github.siddharthjaswal.logpose.analysis.Correlation.suggest] on a first open, but every
 *    suggested row arrives unticked and inert. Auto-detection is a discovery aid, never a silent
 *    grouping decision — a key that grouped rows because LogPose guessed it would be worse than
 *    no key at all.
 *  - **There are no built-in keys.** `order_id` is one app's vocabulary. An empty list is the
 *    correct starting state for a project nobody has taught yet.
 *
 * The dialog holds only rows and validation; what a key set *is* lives in [CorrelationKeys].
 */
class CorrelationKeysDialog(
    project: Project,
    configured: List<CorrelationKey>,
    /** Id-ish keys found in the current capture, ranked. Empty when nothing is captured yet. */
    private val suggestions: List<Suggestion>,
    /**
     * Whether this project has never configured a vocabulary — the one time suggestions seed the
     * list themselves. A user who cleared the list is not overruled the next time they open it.
     */
    seedSuggestions: Boolean = true,
) : DialogWrapper(project) {

    private class Row(
        var name: String,
        var enabled: Boolean,
        var allowShort: Boolean,
        /** What this key does in the capture right now — a suggestion's evidence, or blank. */
        var note: String,
    )

    private val rows = ArrayList<Row>()
    private val model = KeyTableModel()
    private val table = JBTable(model)

    /** True when this open is the one that seeds the vocabulary — drives the hint line. */
    private val seeded: Boolean

    init {
        title = "Correlation Keys"
        setOKButtonText("Save")

        configured.forEach { rows.add(Row(it.name, it.enabled, it.allowShortValues, noteFor(it.name))) }
        // Seeded, not blank: a project with no vocabulary yet gets the capture's own id-ish keys
        // to tick. A project that has been here before does not, so a key the user removed stays
        // removed — the "Suggest from capture" button is how they come back.
        seeded = seedSuggestions && rows.isEmpty()
        if (seeded) appendSuggestions()
        // The table was built from an empty list (property initializers run first), so it has to
        // be told the rows exist before the dialog is laid out.
        model.reloaded()

        init()
    }

    override fun createCenterPanel(): JComponent {
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(24)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.columnModel.getColumn(COL_ENABLED).apply { maxWidth = JBUI.scale(34); minWidth = JBUI.scale(34) }
        table.columnModel.getColumn(COL_NAME).preferredWidth = JBUI.scale(180)
        table.columnModel.getColumn(COL_SHORT).apply { maxWidth = JBUI.scale(96); minWidth = JBUI.scale(96) }
        table.columnModel.getColumn(COL_NOTE).preferredWidth = JBUI.scale(230)
        table.tableHeader.reorderingAllowed = false

        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction {
                rows.add(Row("", true, false, ""))
                model.rowsInserted(rows.size - 1, rows.size - 1)
                table.editCellAt(rows.size - 1, COL_NAME)
                table.editorComponent?.requestFocusInWindow()
            }
            .setRemoveAction {
                val index = table.selectedRow.takeIf { it in rows.indices } ?: return@setRemoveAction
                if (table.isEditing) table.cellEditor?.stopCellEditing()
                rows.removeAt(index)
                model.rowsDeleted(index)
            }
            .disableUpDownActions()
            .createPanel()

        val suggest = PillButton("Suggest from capture", filled = false).apply {
            toolTipText = if (suggestions.isEmpty())
                "No id-ish keys found in what's captured so far — capture a flow first."
            else "Add the id-ish keys found in this capture, unticked."
            isEnabled = suggestions.isNotEmpty()
            addActionListener {
                val before = rows.size
                appendSuggestions()
                // Qualified: inside a JButton's `apply`, a bare `model` is the *button's* model.
                if (rows.size > before) this@CorrelationKeysDialog.model.rowsInserted(before, rows.size - 1)
            }
        }

        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(600), JBUI.scale(330))
            add(hint(), BorderLayout.NORTH)
            add(decorated, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(6))).apply {
                    isOpaque = false
                    add(suggest)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    private fun hint(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(8)
        add(
            JBLabel(
                "<html>The key names the value; the value does the matching — an event joins the " +
                    "group when the <b>value</b> appears anywhere in it, even with no key beside " +
                    "it and no trace at all.</html>"
            ).apply {
                foreground = Theme.textDim
                font = JBUI.Fonts.label(11.5f)
            },
            BorderLayout.NORTH,
        )
        add(
            JBLabel(
                if (seeded && rows.isNotEmpty())
                    "Suggested from this capture — nothing groups until you tick it."
                else
                    "Keys are per project. Tick \"short\" only for a key whose values are under " +
                        "4 characters; short values over-group easily."
            ).apply {
                foreground = Theme.textMuted
                font = JBUI.Fonts.label(11f)
                border = JBUI.Borders.emptyTop(4)
            },
            BorderLayout.SOUTH,
        )
    }

    /** Suggestions this list doesn't already hold, appended unticked. */
    private fun appendSuggestions() {
        for (suggestion in suggestions) {
            if (rows.size >= CorrelationKeys.MAX_KEYS) return
            if (rows.any { CorrelationKeys.canonical(it.name) == CorrelationKeys.canonical(suggestion.key) }) continue
            if (CorrelationKeys.sanitizeName(suggestion.key) == null) continue
            rows.add(Row(suggestion.key, enabled = false, allowShort = false, note = noteOf(suggestion)))
        }
    }

    private fun noteFor(name: String): String =
        suggestions.firstOrNull { CorrelationKeys.canonical(it.key) == CorrelationKeys.canonical(name) }
            ?.let { noteOf(it) } ?: ""

    /** A suggestion's evidence: what it would group, and the value it last held. */
    private fun noteOf(suggestion: Suggestion): String {
        val grouped = "groups ${suggestion.eventsGrouped} event${if (suggestion.eventsGrouped == 1) "" else "s"}"
        val largest = "largest ${suggestion.largestGroup}"
        val latest = suggestion.latestValue?.let { "  ·  latest $it" } ?: ""
        return "$grouped  ·  $largest$latest"
    }

    /**
     * Validation runs on a timer while the dialog is open, so it must have **no side effects** —
     * committing the cell editor from here would end the user's edit every few hundred
     * milliseconds. It reads the half-typed name out of the live editor instead.
     */
    override fun doValidate(): ValidationInfo? {
        val editing = if (table.editingColumn == COL_NAME) table.editingRow else -1
        val names = rows.mapIndexed { index, row ->
            if (index == editing) (table.cellEditor?.cellEditorValue as? String)?.trim() ?: row.name
            else row.name
        }
        val seen = HashSet<String>()
        names.forEachIndexed { index, raw ->
            // A row still being typed into is not yet wrong for being incomplete.
            if (raw.isBlank()) {
                if (index == editing) return@forEachIndexed
                return ValidationInfo("Give every key a name, or remove the blank row.", table)
            }
            val name = CorrelationKeys.sanitizeName(raw)
                ?: return ValidationInfo(
                    "\"$raw\" isn't a key name — use letters, digits, _ . or -, starting with a letter.",
                    table,
                )
            if (!seen.add(CorrelationKeys.canonical(name))) {
                return ValidationInfo(
                    "\"$name\" is already in the list — order_id and orderId are the same key.",
                    table,
                )
            }
        }
        return null
    }

    override fun doOKAction() {
        // *Here* is where an open editor is committed — one gesture, at the user's own moment.
        if (table.isEditing) table.cellEditor?.stopCellEditing()
        super.doOKAction()
    }

    /** The validated key set, ready to persist. */
    fun result(): List<CorrelationKey> = CorrelationKeys.normalize(
        rows.map { CorrelationKey(it.name, enabled = it.enabled, allowShortValues = it.allowShort) }
    )

    private inner class KeyTableModel : AbstractTableModel() {

        // AbstractTableModel's fire* methods are protected, so the dialog asks through these.
        fun rowsInserted(from: Int, to: Int) = fireTableRowsInserted(from, to)
        fun reloaded() = fireTableDataChanged()
        fun rowsDeleted(index: Int) = fireTableRowsDeleted(index, index)

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4

        override fun getColumnName(column: Int) = when (column) {
            COL_ENABLED -> " "
            COL_NAME -> "Key"
            COL_SHORT -> "Short"
            else -> "In this capture"
        }

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            COL_ENABLED, COL_SHORT -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int) = columnIndex != COL_NOTE

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = with(rows[rowIndex]) {
            when (columnIndex) {
                COL_ENABLED -> enabled
                COL_NAME -> name
                COL_SHORT -> allowShort
                else -> note
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val row = rows[rowIndex]
            when (columnIndex) {
                COL_ENABLED -> row.enabled = value as? Boolean ?: false
                COL_NAME -> {
                    row.name = (value as? String).orEmpty().trim()
                    // A freshly typed key gets its evidence line if the capture knows it.
                    row.note = noteFor(row.name)
                    fireTableRowsUpdated(rowIndex, rowIndex)
                }
                COL_SHORT -> row.allowShort = value as? Boolean ?: false
            }
        }
    }

    private companion object {
        const val COL_ENABLED = 0
        const val COL_NAME = 1
        const val COL_SHORT = 2
        const val COL_NOTE = 3
    }
}
