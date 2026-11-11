package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.appcontrol.AppAction
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The confirm before "Clear all data & restart" (`pm clear`): says what is lost, in the words the
 * user needs before a log-out, and offers to give the runtime permissions back afterwards.
 */
class ClearAppDataDialog(project: Project, private val pkg: String) : DialogWrapper(project) {

    private val regrant = JBCheckBox("Re-grant runtime permissions afterwards", false)

    /** Whether the user asked for the granted runtime permissions to be restored after the clear. */
    val regrantPermissions: Boolean get() = regrant.isSelected

    init {
        title = "Clear All Data for $pkg?"
        setOKButtonText("Clear All Data & Restart")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(VerticalLayout(JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(4, 0)
        add(JBLabel("<html><b>$pkg</b></html>"))
        add(JBLabel("<html><body style='width:380px'>${AppAction.CLEAR_DATA_WARNING}</body></html>"))
        add(regrant)
        add(JBLabel("<html><body style='width:380px'>Reads the runtime permissions granted now and grants them back, " +
            "so the app doesn't open on permission prompts. Special access (overlay, exact alarms, all-files) " +
            "isn't covered.</body></html>").apply {
            foreground = Theme.textMuted
            font = JBUI.Fonts.label(11f)
        })
    }
}
