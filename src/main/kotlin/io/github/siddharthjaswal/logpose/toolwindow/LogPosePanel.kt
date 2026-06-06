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
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import io.github.siddharthjaswal.logpose.logcat.LogcatReader
import io.github.siddharthjaswal.logpose.logcat.TransactionParser
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.store.TransactionStore
import io.github.siddharthjaswal.logpose.ui.CurlBuilder
import io.github.siddharthjaswal.logpose.ui.FilterBar
import io.github.siddharthjaswal.logpose.ui.MutedEndpoints
import io.github.siddharthjaswal.logpose.ui.StatusDot
import io.github.siddharthjaswal.logpose.ui.Theme
import io.github.siddharthjaswal.logpose.ui.Toast
import io.github.siddharthjaswal.logpose.ui.TransactionDetailView
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/** The LogPose tool window: a master/detail view over captured HTTP transactions. */
class LogPosePanel(project: com.intellij.openapi.project.Project) : JPanel(BorderLayout()), Disposable {

    private val store = TransactionStore()
    private val parser = TransactionParser()
    private val reader = LogcatReader()

    private val renderer = TransactionListRenderer()
    private val list = JBList(javax.swing.DefaultListModel<Transaction>())
    private val detail = TransactionDetailView(project)
    private val filterBar = FilterBar()
    private val statusDot = StatusDot()

    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val refreshScheduled = AtomicBoolean(false)
    private var suppressSelectionEvents = false

    init {
        isOpaque = true
        background = Theme.bg0

        list.isOpaque = true
        list.background = Theme.bg0
        list.emptyText.text = "No requests yet — press ▶ to capture, then use your app"
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = renderer
        list.addListSelectionListener {
            if (!suppressSelectionEvents && !it.valueIsAdjusting) detail.show(list.selectedValue)
        }
        val mouse = ListMouse()
        list.addMouseListener(mouse)
        list.addMouseMotionListener(mouse)

        filterBar.onChange = { refreshList() }

        val listScroll = JBScrollPane(list).apply {
            border = JBUI.Borders.empty(); viewport.isOpaque = true; viewport.background = Theme.bg0
            minimumSize = Dimension(JBUI.scale(220), 0)
        }
        detail.minimumSize = Dimension(JBUI.scale(320), 0)
        val splitter = OnePixelSplitter(false, 0.44f).apply {
            firstComponent = listScroll
            secondComponent = detail
            setHonorComponentsMinimumSize(true)
        }

        add(buildHeader(), BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        store.addListener { scheduleRefresh() }
    }

    private fun buildHeader(): Component {
        val group = DefaultActionGroup().apply {
            add(CaptureToggleAction()); add(ClearAction())
        }
        val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar("LogPose", group, true)
        toolbar.targetComponent = this

        // Capture / Clear on the left, with the pulsing status dot. No "LogPose" label —
        // the IDE already titles the tool window.
        val actionsLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            isOpaque = false
            add(statusDot)
            add(toolbar.component)
        }
        val toolbarRow = JPanel(BorderLayout()).apply {
            isOpaque = true; background = Theme.bg0
            border = JBUI.Borders.empty(0, 6)
            add(actionsLeft, BorderLayout.WEST)
        }

        val filterWrap = JPanel(BorderLayout()).apply {
            isOpaque = true; background = Theme.bg0
            border = JBUI.Borders.customLine(Theme.borderStrong, 1, 0, 1, 0)
            add(filterBar, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(toolbarRow, BorderLayout.NORTH)
            add(filterWrap, BorderLayout.CENTER)
        }
    }

    private fun startCapture() {
        parser.reset()
        statusDot.capturing = true
        reader.start(
            onLine = { line -> parser.accept(line)?.let { store.add(it) } },
            onError = { msg -> refreshAlarm.addRequest({ detail.showError("⚠ LogPose capture error:\n\n$msg") }, 0) },
        )
        scheduleRefresh()
    }

    private fun stopCapture() {
        statusDot.capturing = false
        reader.stop()
    }

    private fun scheduleRefresh() {
        if (refreshScheduled.compareAndSet(false, true)) {
            refreshAlarm.addRequest({ refreshScheduled.set(false); refreshList() }, 150)
        }
    }

    private fun refreshList() {
        val selectedId = list.selectedValue?.id
        val all = store.snapshot()
        val state = filterBar.state()
        val filtered = all.filter { state.matches(it) }
        filterBar.setCount(filtered.size, all.size)

        val model = javax.swing.DefaultListModel<Transaction>()
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

    override fun dispose() {
        reader.stop()
        statusDot.dispose()
    }

    private fun copyToClipboard(text: String, toast: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        Toast.show(list, toast)
    }

    /** Handles hover (cURL affordance), left-click cURL copy, and the right-click menu. */
    private inner class ListMouse : MouseAdapter() {
        override fun mouseMoved(e: MouseEvent) {
            val idx = indexAt(e)
            if (renderer.hoveredIndex != idx) { renderer.hoveredIndex = idx; list.repaint() }
        }

        override fun mouseExited(e: MouseEvent) {
            if (renderer.hoveredIndex != -1) { renderer.hoveredIndex = -1; list.repaint() }
        }

        override fun mouseClicked(e: MouseEvent) {
            if (!SwingUtilities.isLeftMouseButton(e)) return
            val idx = indexAt(e)
            if (idx < 0) return
            // Only the hovered, non-muted row paints the cURL affordance.
            if (idx != renderer.hoveredIndex) return
            val tx = list.model.getElementAt(idx)
            val bounds = list.getCellBounds(idx, idx) ?: return
            if (!MutedEndpoints.isMuted(tx) && renderer.isInCurlZone(bounds.width, e.x - bounds.x)) {
                copyToClipboard(CurlBuilder.build(tx), "cURL copied")
            }
        }

        override fun mousePressed(e: MouseEvent) = maybePopup(e)
        override fun mouseReleased(e: MouseEvent) = maybePopup(e)

        private fun indexAt(e: MouseEvent): Int {
            val idx = list.locationToIndex(e.point)
            if (idx < 0) return -1
            val bounds = list.getCellBounds(idx, idx) ?: return -1
            return if (bounds.contains(e.point)) idx else -1
        }

        private fun maybePopup(e: MouseEvent) {
            if (!e.isPopupTrigger) return
            val idx = indexAt(e).takeIf { it >= 0 } ?: return
            list.selectedIndex = idx
            val tx = list.selectedValue ?: return
            val key = MutedEndpoints.keyOf(tx)
            val muted = MutedEndpoints.isMuted(tx)

            JPopupMenu().apply {
                add(item("Copy as cURL") { copyToClipboard(CurlBuilder.build(tx), "cURL copied") })
                add(item("Copy as JSON") {
                    copyToClipboard(prettyJson.encodeToString(Transaction.serializer(), tx), "Transaction JSON copied")
                })
                add(item("Copy URL") { copyToClipboard(tx.request.url, "URL copied") })
                tx.response?.body?.text?.let { body ->
                    add(item("Copy response body") { copyToClipboard(body, "Response body copied") })
                }
                addSeparator()
                add(item(if (muted) "Unmute  $key" else "Mute  $key") { MutedEndpoints.toggle(tx); list.repaint() })
                if (MutedEndpoints.patterns().isNotEmpty()) {
                    add(item("Clear all mutes") { MutedEndpoints.clearAll(); list.repaint() })
                }
                show(list, e.x, e.y)
            }
        }

        private fun item(text: String, action: () -> Unit) =
            JMenuItem(text).apply { addActionListener { action() } }
    }

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
