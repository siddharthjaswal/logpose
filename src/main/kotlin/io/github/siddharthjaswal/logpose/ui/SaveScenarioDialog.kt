package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.mock.ScenarioStore
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Names a scenario before it's written to `.logpose/scenarios/`.
 *
 * Two modes, one dialog: saving the rules that are already active, or snapshotting the captured
 * session into rules. The snapshot mode adds the 2xx-only filter and — more importantly — a live
 * count of what will actually be written, including what the snapshot refuses to guess at, so
 * "12 endpoints · skipped 3 in-flight/bodyless" is visible *before* the file exists.
 */
class SaveScenarioDialog(
    project: Project,
    private val titleText: String,
    private val defaultName: String,
    /** Null for "save current rules"; otherwise the snapshot preview for a given 2xx-only flag. */
    private val preview: ((successOnly: Boolean) -> String)? = null,
    private val existingNames: Set<String> = emptySet(),
) : DialogWrapper(project) {

    private val name = JBTextField(defaultName)
    private val note = JBTextField()
    private val successOnly = JBCheckBox("Only successful (2xx) responses", false)
    private val previewLabel = JBLabel().apply {
        foreground = Theme.textDim
        font = JBUI.Fonts.label(11f)
    }
    private val fileLabel = JBLabel().apply {
        foreground = Theme.textMuted
        font = JBUI.Fonts.label(11f)
    }

    init {
        title = titleText
        setOKButtonText("Save")
        init()
        successOnly.addActionListener { updatePreview() }
        name.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateFile()
            override fun removeUpdate(e: DocumentEvent) = updateFile()
            override fun changedUpdate(e: DocumentEvent) = updateFile()
        })
        updatePreview()
        updateFile()
    }

    override fun createCenterPanel(): JComponent {
        val column = JPanel(VerticalLayout(JBUI.scale(4))).apply {
            preferredSize = Dimension(JBUI.scale(520), JBUI.scale(if (preview != null) 210 else 170))
        }
        column.add(row("Name", name))
        column.add(hint("Lowercase letters, digits, - and _ — it becomes the filename."))
        column.add(row("Note", note))
        if (preview != null) {
            column.add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 96, 0, 0)
                add(successOnly, BorderLayout.WEST)
            })
            column.add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 96, 0, 0)
                add(previewLabel, BorderLayout.WEST)
            })
        }
        column.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 96, 0, 0)
            add(fileLabel, BorderLayout.WEST)
        })
        return column
    }

    private fun updatePreview() {
        preview?.let { previewLabel.text = it(successOnly.isSelected) }
    }

    private fun updateFile() {
        val slug = ScenarioStore.sanitize(name.text)
        fileLabel.text = when {
            slug == null -> " "
            slug in existingNames -> "${ScenarioStore.REL_DIR}/$slug.json  ·  replaces the existing scenario"
            else -> "${ScenarioStore.REL_DIR}/$slug.json"
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (ScenarioStore.sanitize(name.text) == null) {
            return ValidationInfo("Give the scenario a name using letters, digits, - or _", name)
        }
        return null
    }

    /** The validated, filename-safe name. Never null once the dialog has been accepted. */
    fun scenarioName(): String = ScenarioStore.sanitize(name.text) ?: defaultName

    fun scenarioNote(): String? = note.text.trim().ifBlank { null }

    fun successOnly(): Boolean = successOnly.isSelected

    private fun row(labelText: String, field: Component): JComponent =
        object : JPanel(BorderLayout(JBUI.scale(10), 0)) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 0)
            add(JBLabel(labelText).apply {
                foreground = Theme.textDim
                preferredSize = Dimension(JBUI.scale(86), preferredSize.height)
            }, BorderLayout.WEST)
            add(field, BorderLayout.CENTER)
        }

    private fun hint(text: String): JComponent = JBLabel(text).apply {
        foreground = Theme.textMuted
        font = JBUI.Fonts.label(11f)
        border = JBUI.Borders.empty(0, 96, 4, 0)
    }
}
