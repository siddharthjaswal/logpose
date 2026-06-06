package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.logcat.LogcatReader
import io.github.siddharthjaswal.logpose.logcat.TransactionParser
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.store.TransactionStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.Component
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/**
 * The LogPose tool window: a master/detail view over captured HTTP transactions.
 */
class LogPosePanel : javax.swing.JPanel(BorderLayout()), Disposable {

    private val store = TransactionStore()
    private val parser = TransactionParser()
    private val reader = LogcatReader()

    private val list = JBList(DefaultListModel<Transaction>())
    private val detail = JBTextArea()
    private val filterField = JBTextField()

    private val pretty = Json { prettyPrint = true; encodeDefaults = true }

    // Coalesces bursts of incoming transactions into at most one UI rebuild per tick,
    // so a busy app doesn't swamp the EDT with thousands of full-list refreshes.
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val refreshScheduled = AtomicBoolean(false)

    // Set while we swap the list model programmatically, so the resulting selection
    // churn doesn't keep re-rendering the detail pane during live capture.
    private var suppressSelectionEvents = false

    init {
        border = JBUI.Borders.empty()

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = TransactionCellRenderer()
        list.addListSelectionListener {
            if (!suppressSelectionEvents && !it.valueIsAdjusting) showDetail(list.selectedValue)
        }

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

        store.addListener { scheduleRefresh() }
    }

    private fun buildToolbar(): Component {
        val group = DefaultActionGroup().apply {
            add(CaptureToggleAction())
            add(ClearAction())
        }
        val toolbar: ActionToolbar =
            ActionManager.getInstance().createActionToolbar("LogPose", group, true)
        toolbar.targetComponent = this

        val north = javax.swing.JPanel(BorderLayout())
        north.add(toolbar.component, BorderLayout.WEST)
        north.add(filterField, BorderLayout.CENTER)
        north.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
        return north
    }

    private fun startCapture() {
        parser.reset()
        reader.start(
            onLine = { line -> parser.accept(line)?.let { store.add(it) } },
            onError = { msg ->
                refreshAlarm.addRequest({ detail.text = "⚠ LogPose capture error:\n\n$msg" }, 0)
            },
        )
        scheduleRefresh()
    }

    private fun stopCapture() = reader.stop()

    /** Debounced, coalesced refresh: at most one rebuild per ~150ms regardless of volume. */
    private fun scheduleRefresh() {
        if (refreshScheduled.compareAndSet(false, true)) {
            refreshAlarm.addRequest({
                refreshScheduled.set(false)
                refreshList()
            }, 150)
        }
    }

    private fun refreshList() {
        val selectedId = list.selectedValue?.id
        val filtered = TransactionStore.filter(store.snapshot(), filterField.text)

        // Build a fresh, detached model (cheap — no listeners fire), then swap it in
        // with a single structure-changed event instead of N per-element events.
        val model = DefaultListModel<Transaction>()
        filtered.forEach { model.addElement(it) }

        suppressSelectionEvents = true
        try {
            list.model = model
            if (selectedId != null) {
                for (i in 0 until model.size()) {
                    if (model.get(i).id == selectedId) { list.selectedIndex = i; break }
                }
            }
        } finally {
            suppressSelectionEvents = false
        }
    }

    private fun showDetail(tx: Transaction?) {
        detail.text = if (tx == null) "" else pretty.encodeToString(tx)
        detail.caretPosition = 0
    }

    override fun dispose() = reader.stop()

    private inner class CaptureToggleAction :
        AnAction("Capture", "Start/stop reading logcat", AllIcons.Actions.Execute), Toggleable {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
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
        AnAction("Clear", "Clear captured transactions and the device log buffer", AllIcons.Actions.GC) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            store.clear()
            // Also empty the device buffer so cleared logs don't replay on the next Start.
            reader.clearBuffer()
            detail.text = ""
            refreshList()
        }
    }
}
