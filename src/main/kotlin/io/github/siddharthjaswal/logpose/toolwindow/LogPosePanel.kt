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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.logcat.LogcatReader
import io.github.siddharthjaswal.logpose.logcat.TransactionParser
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.store.TransactionStore
import com.intellij.openapi.ide.CopyPasteManager
import io.github.siddharthjaswal.logpose.ui.CurlBuilder
import io.github.siddharthjaswal.logpose.ui.MutedEndpoints
import io.github.siddharthjaswal.logpose.ui.TransactionDetailView
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

/** The LogPose tool window: a master/detail view over captured HTTP transactions. */
class LogPosePanel : JPanel(BorderLayout()), Disposable {

    private val store = TransactionStore()
    private val parser = TransactionParser()
    private val reader = LogcatReader()

    private val list = JBList(DefaultListModel<Transaction>())
    private val detail = TransactionDetailView()
    private val filterField = JBTextField()
    private val countLabel = JBLabel()

    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val refreshScheduled = AtomicBoolean(false)
    private var suppressSelectionEvents = false

    init {
        border = JBUI.Borders.empty()

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = TransactionListRenderer()
        list.addListSelectionListener {
            if (!suppressSelectionEvents && !it.valueIsAdjusting) detail.show(list.selectedValue)
        }
        list.addMouseListener(MutePopup())

        filterField.emptyText.text = "filter:  /orders   status:5xx   method:POST   -heartbeat"
        filterField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refreshList()
        })

        val splitter = OnePixelSplitter(false, 0.38f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = detail
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

        countLabel.foreground = JBColor.GRAY
        countLabel.border = JBUI.Borders.empty(0, 10)

        val north = JPanel(BorderLayout())
        north.add(toolbar.component, BorderLayout.WEST)
        north.add(filterField, BorderLayout.CENTER)
        north.add(countLabel, BorderLayout.EAST)
        north.border = BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border())
        return north
    }

    private fun startCapture() {
        parser.reset()
        reader.start(
            onLine = { line -> parser.accept(line)?.let { store.add(it) } },
            onError = { msg -> refreshAlarm.addRequest({ detail.showError("⚠ LogPose capture error:\n\n$msg") }, 0) },
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
        val all = store.snapshot()
        val filtered = TransactionStore.filter(all, filterField.text)
        countLabel.text = "${filtered.size}/${all.size}"

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

    override fun dispose() = reader.stop()

    /** Right-click a row to mute/unmute its endpoint (persists across restarts). */
    private inner class MutePopup : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) = maybeShow(e)
        override fun mouseReleased(e: MouseEvent) = maybeShow(e)

        private fun maybeShow(e: MouseEvent) {
            if (!e.isPopupTrigger) return
            val idx = list.locationToIndex(e.point).takeIf { it >= 0 } ?: return
            list.selectedIndex = idx
            val tx = list.selectedValue ?: return
            val key = MutedEndpoints.keyOf(tx)
            val muted = MutedEndpoints.isMuted(tx)

            JPopupMenu().apply {
                add(JMenuItem("Copy as cURL").apply {
                    addActionListener { copyToClipboard(CurlBuilder.build(tx)) }
                })
                add(JMenuItem("Copy URL").apply {
                    addActionListener { copyToClipboard(tx.request.url) }
                })
                addSeparator()
                add(JMenuItem(if (muted) "Unmute  $key" else "Mute  $key").apply {
                    addActionListener { MutedEndpoints.toggle(tx); list.repaint() }
                })
                if (MutedEndpoints.patterns().isNotEmpty()) {
                    add(JMenuItem("Clear all mutes").apply {
                        addActionListener { MutedEndpoints.clearAll(); list.repaint() }
                    })
                }
                show(list, e.x, e.y)
            }
        }
    }

    private fun copyToClipboard(text: String) =
        CopyPasteManager.getInstance().setContents(StringSelection(text))

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
            reader.clearBuffer()
            detail.show(null)
            refreshList()
        }
    }
}
