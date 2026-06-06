package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.logcat.LogcatReader
import io.github.siddharthjaswal.logpose.logcat.TransactionParser
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.store.TransactionStore
import com.intellij.icons.AllIcons
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * The LogPose tool window: a master/detail view over captured HTTP transactions.
 *
 *  ┌─────────────────────────────────────────────┐
 *  │ [▶ Capture] [⟲ Clear]   filter: [__________] │
 *  ├──────────────────┬──────────────────────────┤
 *  │ POST 200 /orders │  pretty-printed JSON of   │
 *  │ GET  404 /user   │  the selected transaction │
 *  │ ...              │                           │
 *  └──────────────────┴──────────────────────────┘
 */
class LogPosePanel : JPanel(BorderLayout()), Disposable {

    private val store = TransactionStore()
    private val parser = TransactionParser()
    private val reader = LogcatReader()

    private val listModel = DefaultListModel<Transaction>()
    private val list = JBList(listModel)
    private val detail = JBTextArea()
    private val filterField = JBTextField()

    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    init {
        border = JBUI.Borders.empty()

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = TransactionCellRenderer()
        list.addListSelectionListener { showDetail(list.selectedValue) }

        detail.isEditable = false
        detail.lineWrap = false

        filterField.emptyText.text = "filter:  /orders   status:5xx   method:POST   -heartbeat"
        filterField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshList()
        })

        val splitter = OnePixelSplitter(false, 0.35f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = JBScrollPane(detail)
        }

        add(buildToolbar(), BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        store.addListener {
            ApplicationManager.getApplication().invokeLater { refreshList() }
        }
    }

    private fun buildToolbar(): Component {
        val group = DefaultActionGroup().apply {
            add(CaptureToggleAction())
            add(ClearAction())
        }
        val toolbar: ActionToolbar =
            ActionManager.getInstance().createActionToolbar("LogPose", group, true)
        toolbar.targetComponent = this

        val north = JPanel(BorderLayout())
        north.add(toolbar.component, BorderLayout.WEST)
        north.add(filterField, BorderLayout.CENTER)
        north.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
        return north
    }

    private fun startCapture() {
        parser.reset()
        reader.start(
            onLine = { line ->
                parser.accept(line)?.let { tx -> store.add(tx) }
            },
            onError = { msg ->
                ApplicationManager.getApplication().invokeLater {
                    detail.text = "⚠ LogPose capture error:\n\n$msg"
                }
            },
        )
    }

    private fun stopCapture() = reader.stop()

    private fun refreshList() {
        val selectedId = list.selectedValue?.id
        val filtered = TransactionStore.filter(store.snapshot(), filterField.text)
        listModel.clear()
        filtered.forEach { listModel.addElement(it) }
        // Preserve selection across refreshes.
        if (selectedId != null) {
            for (i in 0 until listModel.size()) {
                if (listModel.get(i).id == selectedId) { list.selectedIndex = i; break }
            }
        }
    }

    private fun showDetail(tx: Transaction?) {
        detail.text = if (tx == null) "" else pretty.encodeToString(tx)
        detail.caretPosition = 0
    }

    override fun dispose() = reader.stop()

    private inner class CaptureToggleAction :
        AnAction("Capture", "Start/stop reading logcat", AllIcons.Actions.Execute), Toggleable {
        override fun actionPerformed(e: AnActionEvent) {
            if (reader.isRunning()) stopCapture() else startCapture()
        }
        override fun update(e: AnActionEvent) {
            val running = reader.isRunning()
            Toggleable.setSelected(e.presentation, running)
            e.presentation.icon = if (running) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
            e.presentation.text = if (running) "Stop Capture" else "Start Capture"
        }
    }

    private inner class ClearAction :
        AnAction("Clear", "Clear captured transactions", AllIcons.Actions.GC) {
        override fun actionPerformed(e: AnActionEvent) {
            store.clear()
            detail.text = ""
        }
    }
}
