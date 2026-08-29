package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.analysis.Correlation
import io.github.siddharthjaswal.logpose.analysis.FindByValue
import io.github.siddharthjaswal.logpose.analysis.FindQuery
import io.github.siddharthjaswal.logpose.analysis.Grouping
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent

/**
 * "Find by value…" — arriving with an id from a ticket, a backend log or a QA report and asking
 * for everything about it (PRD §4.2.1).
 *
 * The dialog itself decides nothing. [Correlation.parseFindQuery] reads the input (bare value or
 * `key=value`, quotes and whitespace stripped, short values refused), and [FindByValue.preview]
 * turns the parse plus a match count into the line under the field. That's deliberate: the count
 * must be visible *before* committing so a typo reads as a typo, and "too short to match safely"
 * must be said out loud rather than shown as an empty waterfall — both are decisions, both are
 * tested without a dialog.
 *
 * Counting walks the capture, so keystrokes are debounced; the caller's [resolve] is expected to
 * group off cached haystacks ([io.github.siddharthjaswal.logpose.analysis.CorrelationIndex]).
 */
class FindByValueDialog(
    project: Project,
    initial: String = "",
    /** How many events a grouping would open — the caller groups off its cache. */
    private val resolve: (Grouping) -> Int,
    /** The configured key holding a bare value, or null ([Correlation.keyLabelFor]). */
    private val keyLabelFor: (String) -> String?,
    /** Whether a named key has opted in to short values — the only way to overrule the floor. */
    private val allowsShortValues: (String) -> Boolean = { false },
) : DialogWrapper(project) {

    private val input = JBTextField(initial)
    private val preview = JBLabel(" ").apply { font = JBUI.Fonts.label(11.5f) }
    private var result: FindByValue.Preview = FindByValue.Preview("", null, FindByValue.Tone.NEUTRAL)

    private val debounce = Timer(180) { recompute() }.apply { isRepeats = false }

    init {
        title = "Find by Value"
        setOKButtonText("Show waterfall")
        input.emptyText.text = "21053953   or   order_id=21053953"
        input.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = debounce.restart()
        })
        init()
        recompute()
    }

    override fun getPreferredFocusedComponent(): JComponent = input

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(JBUI.scale(460), JBUI.scale(108))
        add(
            JBLabel("Paste an id — an order, a trip, a request id. Any event carrying it joins the group.").apply {
                foreground = Theme.textDim
                font = JBUI.Fonts.label(11.5f)
                border = JBUI.Borders.emptyBottom(8)
            },
            BorderLayout.NORTH,
        )
        add(input, BorderLayout.CENTER)
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(8)
                add(preview, BorderLayout.WEST)
            },
            BorderLayout.SOUTH,
        )
    }

    private fun recompute() {
        result = FindByValue.preview(parsed(), resolve, keyLabelFor)
        preview.text = result.message.ifBlank { " " }
        preview.foreground = when (result.tone) {
            FindByValue.Tone.READY -> Theme.accent
            FindByValue.Tone.PROBLEM -> Theme.warn
            FindByValue.Tone.NEUTRAL -> Theme.textMuted
        }
        isOKActionEnabled = result.grouping != null
    }

    /**
     * The input, read twice at most: once under the default guard, and again allowing short
     * values when the user typed `key=value` for a key that has opted in. Refusing `pin=210`
     * while telling the user to tick "short values" for `pin` would be a message that lies.
     */
    private fun parsed(): FindQuery {
        val query = Correlation.parseFindQuery(input.text)
        val key = (query as? FindQuery.TooShort)?.key
        if (key == null || !allowsShortValues(key)) return query
        return Correlation.parseFindQuery(input.text, allowShortValues = true)
    }

    override fun doOKAction() {
        // A fast typist can hit Enter inside the debounce window; settle first so the dialog
        // never commits a grouping that belongs to an earlier keystroke.
        if (debounce.isRunning) { debounce.stop(); recompute() }
        if (result.grouping != null) super.doOKAction()
    }

    override fun dispose() {
        debounce.stop()
        super.dispose()
    }

    /** The group to open, or null when the dialog was cancelled. */
    fun grouping(): Grouping? = result.grouping
}
