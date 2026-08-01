package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import org.jetbrains.ide.BuiltInServerManager
import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.logcat.ControlMessage
import io.github.siddharthjaswal.logpose.logcat.LogcatReader
import io.github.siddharthjaswal.logpose.logcat.TransactionParser
import io.github.siddharthjaswal.logpose.mcp.LogPoseMcpHandler
import io.github.siddharthjaswal.logpose.mcp.McpSessions
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.store.EventStore
import io.github.siddharthjaswal.logpose.ui.CurlBuilder
import io.github.siddharthjaswal.logpose.ui.FcmDetailView
import io.github.siddharthjaswal.logpose.ui.FilterBar
import io.github.siddharthjaswal.logpose.ui.GenericDetailView
import io.github.siddharthjaswal.logpose.ui.KindPresenter
import io.github.siddharthjaswal.logpose.ui.isPending
import io.github.siddharthjaswal.logpose.ui.MockDiff
import io.github.siddharthjaswal.logpose.ui.MockRuleDialog
import io.github.siddharthjaswal.logpose.ui.MocksBar
import io.github.siddharthjaswal.logpose.ui.MutedEndpoints
import io.github.siddharthjaswal.logpose.ui.StatusDot
import io.github.siddharthjaswal.logpose.ui.Theme
import io.github.siddharthjaswal.logpose.ui.Toast
import io.github.siddharthjaswal.logpose.ui.TransactionDetailView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/** The LogPose tool window: a master/detail view over captured HTTP transactions. */
class LogPosePanel(private val project: com.intellij.openapi.project.Project) : JPanel(BorderLayout()), Disposable {

    private val store = EventStore()
    private val parser = TransactionParser()
    private val reader = LogcatReader()

    private val renderer = TransactionListRenderer()
    // Latest duplicate-burst marks, keyed by transaction id; recomputed each refresh and read
    // by the renderer, the row tooltip, and the detail banner.
    private var duplicateMarks: Map<String, DuplicateDetector.Mark> = emptyMap()
    private val list = object : JBList<LogEvent>(javax.swing.DefaultListModel<LogEvent>()) {
        override fun getToolTipText(event: MouseEvent): String? {
            val idx = locationToIndex(event.point)
            if (idx < 0) return null
            val bounds = getCellBounds(idx, idx) ?: return null
            if (!bounds.contains(event.point)) return null
            val tx = (model.getElementAt(idx) as? LogEvent.Http)?.tx ?: return null
            return duplicateMarks[tx.id]?.let { duplicateTooltip(tx, it) }
        }
    }
    private val detail = TransactionDetailView(project)
    private val fcmDetail = FcmDetailView(project)
    private val genericDetail = GenericDetailView(project)
    // Routes the single detail slot between the per-kind views. "generic" is the fallback for
    // every app-defined kind, so an unknown event is still inspectable.
    private val detailCards = CardLayout()
    private val detailPane = JPanel(detailCards).apply {
        isOpaque = true; background = Theme.bg0
        add(detail, "http")
        add(fcmDetail, "fcm")
        add(genericDetail, "generic")
    }
    private val filterBar = FilterBar()
    private val statusDot = StatusDot()

    private val mocksController = MocksController(project)
    private val mocksBar = MocksBar(
        onEdit = { editMockRule(it) },
        onDelete = { mocksController.remove(it) },
        onToggle = { id, on -> mocksController.setEnabled(id, on) },
        onDisableAll = { mocksController.disableAll() },
        onDiff = { rule -> MockDiff.show(project, rule, mocksController.baseBodyFor(rule.id)) },
    )

    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val refreshScheduled = AtomicBoolean(false)
    private var suppressSelectionEvents = false
    private var lastShown: LogEvent? = null

    // Drives the in-flight UI: ticking duration + spinner while any request is pending.
    private val liveTimer = javax.swing.Timer(250) { onLiveTick() }

    // Lets a coding agent read this project's capture over MCP. The token both authenticates
    // the caller and selects which open project's capture to serve.
    private val mcpToken = McpSessions.tokenFor(project)

    // Whether logcat is being tailed right now — read from the MCP (Netty) thread, so an agent
    // can tell "no matching events" from "capture isn't running". Kept off the UI's statusDot,
    // which lives on the EDT.
    @Volatile private var captureActive = false
    private var reattachAttempts = 0
    private val MAX_REATTACH = 5
    private val REATTACH_DELAY_MS = 2_000

    init {
        isOpaque = true
        background = Theme.bg0

        McpSessions.register(
            mcpToken,
            McpSessions.Session(
                projectName = project.name,
                store = store,
                hostAgeMillis = { id -> store.elapsedMillis(id) },
                exposeBodies = { McpSessions.exposeBodies(project) },
                captureRunning = { captureActive },
                clearCapture = { store.clear() },
                mocks = McpMocks(),
            ),
        )

        renderer.elapsedProvider = { tx ->
            if (tx.isPending()) store.elapsedMillis(tx.id) else null
        }
        liveTimer.start()

        list.isOpaque = true
        list.background = Theme.bg0
        list.emptyText.apply {
            text = "No requests captured yet"
            appendLine(
                "1.  Press ▶ above to start capturing",
                com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES, null,
            )
            appendLine(
                "2.  Add LogPoseInterceptor to your app's OkHttpClient (debug/staging)",
                com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES, null,
            )
            appendLine(
                "     without it, there's nothing on the LogPose tag to read",
                com.intellij.ui.SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, null,
            )
            appendLine(
                "3.  Optional: call LogPose.logFcmMessage(…) to see FCM pushes here too",
                com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES, null,
            )
            appendLine(
                "Setup guide →",
                com.intellij.ui.SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                java.awt.event.ActionListener {
                    com.intellij.ide.BrowserUtil.browse("https://github.com/siddharthjaswal/logpose#getting-started")
                },
            )
        }
        // Multi-select so a run of rows can be copied as a compact timeline (⌘/Ctrl+C). The
        // detail pane follows the lead selection.
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        val copyKey = KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx)
        list.inputMap.put(copyKey, "copyTimeline")
        list.actionMap.put("copyTimeline", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = copySelectedTimeline()
        })
        // Keyboard range-select: Shift+↑/↓ extends the selection (the actions are provided by
        // the list UI). Bound explicitly so a parent shortcut can't swallow them.
        list.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.SHIFT_DOWN_MASK), "selectNextRowExtendSelection",
        )
        list.inputMap.put(
            KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.SHIFT_DOWN_MASK), "selectPreviousRowExtendSelection",
        )
        list.cellRenderer = renderer
        // Enable per-row tooltips (our JBList overrides getToolTipText for duplicate rows).
        javax.swing.ToolTipManager.sharedInstance().registerComponent(list)
        list.addListSelectionListener {
            if (!suppressSelectionEvents && !it.valueIsAdjusting) showDetail(list.selectedValue)
        }
        val mouse = ListMouse()
        list.addMouseListener(mouse)
        list.addMouseMotionListener(mouse)

        filterBar.onChange = { refreshList() }

        val listScroll = JBScrollPane(list).apply {
            border = JBUI.Borders.empty(); viewport.isOpaque = true; viewport.background = Theme.bg0
            minimumSize = Dimension(JBUI.scale(220), 0)
        }
        detailPane.minimumSize = Dimension(JBUI.scale(320), 0)
        val splitter = OnePixelSplitter(false, 0.44f).apply {
            firstComponent = listScroll
            secondComponent = detailPane
            setHonorComponentsMinimumSize(true)
        }

        add(buildHeader(), BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        store.addListener { scheduleRefresh() }

        // Reverse channel: device hello / mock acks arrive on the reader thread → route to the
        // controller, then repaint the mocks bar on the EDT.
        parser.onControl = { msg ->
            // A hello is both a mock-sync signal and the only reliable marker of an app restart,
            // so the store sees it too — that's what keeps two launches from being reported as
            // one timeline.
            if (msg is ControlMessage.DeviceHello) {
                store.noteHello(msg.hello.processId, msg.hello.pkg, msg.hello.libVersion)
            }
            mocksController.onControl(msg)
        }
        mocksController.addListener {
            refreshAlarm.addRequest({ mocksBar.refresh(mocksController.rules(), mocksController.deviceState()) }, 0)
        }
        mocksBar.refresh(mocksController.rules(), mocksController.deviceState())
    }

    private fun buildHeader(): Component {
        val group = DefaultActionGroup().apply {
            add(CaptureToggleAction()); add(ClearAction()); add(ConnectAgentAction())
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
            border = JBUI.Borders.empty(3, 8)
            add(actionsLeft, BorderLayout.WEST)
        }

        val filterWrap = JPanel(BorderLayout()).apply {
            isOpaque = true; background = Theme.bg0
            border = JBUI.Borders.customLine(Theme.borderStrong, 1, 0, 1, 0)
            add(filterBar, BorderLayout.CENTER)
        }

        // Filter bar then the mocks strip (which self-hides when there are no rules).
        val belowToolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(filterWrap, BorderLayout.NORTH)
            add(mocksBar, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(toolbarRow, BorderLayout.NORTH)
            add(belowToolbar, BorderLayout.CENTER)
        }
    }

    private fun startCapture() {
        parser.reset()
        captureActive = true
        reattachAttempts = 0
        statusDot.capturing = true
        mocksController.onCaptureStarted()
        attachReader()
        scheduleRefresh()
    }

    /**
     * Tails logcat, and — if the stream ends while we still think we're capturing — reattaches a
     * few times before giving up. Capture used to die silently on an app reinstall (adb drops the
     * stream); an agent then saw empty queries with no signal. Any received line resets the
     * attempt counter, so a healthy capture that later drops still gets a fresh set of retries.
     */
    private fun attachReader() {
        reader.start(
            onLine = { line ->
                reattachAttempts = 0
                parser.accept(line)?.let { store.add(it) }
            },
            onError = { msg ->
                refreshAlarm.addRequest({
                    detailCards.show(detailPane, "http")
                    detail.showError("⚠ LogPose capture error:\n\n$msg")
                }, 0)
            },
            onStopped = {
                // captureActive is cleared by stopCapture() before reader.stop(), so if it's still
                // set here the stream ended on its own — a transient device/adb drop worth retrying.
                if (captureActive && reattachAttempts < MAX_REATTACH) {
                    reattachAttempts++
                    refreshAlarm.addRequest({ if (captureActive) attachReader() }, REATTACH_DELAY_MS)
                } else {
                    captureActive = false
                    refreshAlarm.addRequest({ statusDot.capturing = false }, 0)
                }
            },
        )
    }

    private fun stopCapture() {
        captureActive = false
        statusDot.capturing = false
        reader.stop()
        // Fail-safe: clear any rules the device is holding so a forgotten mock can't linger
        // after capture ends. Local rules persist for the next session.
        mocksController.onCaptureStopped()
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

        // Detect duplicate bursts over the FULL (time-ordered) capture, not the filtered view —
        // filtering must not break a burst chain or change a call's ordinal. Duplicates are an
        // HTTP-only concept, so only the HTTP events feed the detector.
        val httpTxs = all.filterIsInstance<LogEvent.Http>().map { it.tx }
        duplicateMarks = DuplicateDetector.analyze(httpTxs)
        renderer.duplicateProvider = { duplicateMarks[it.id] }

        val filtered = all.filter {
            state.matches(it) && (!state.duplicatesOnly || duplicateMarks.containsKey(it.id))
        }
        filterBar.setCount(filtered.size, all.size)

        val model = javax.swing.DefaultListModel<LogEvent>()
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

        // If the selected transaction's data changed (e.g. pending → completed), re-render
        // the detail even though the selection index didn't change.
        val sel = list.selectedValue
        if (sel != lastShown) showDetail(sel)
    }

    private fun showDetail(event: LogEvent?) {
        lastShown = event
        when (event) {
            is LogEvent.Http -> {
                detail.show(event.tx, duplicateMarks[event.id])
                detailCards.show(detailPane, "http")
            }
            is LogEvent.Fcm -> {
                fcmDetail.show(event.msg)
                detailCards.show(detailPane, "fcm")
            }
            null -> {
                detail.show(null)
                detailCards.show(detailPane, "http")
            }
            // db / worker / config / app-defined all render through the presenter-driven view.
            else -> {
                genericDetail.show(event)
                detailCards.show(detailPane, "generic")
            }
        }
    }

    private fun duplicateTooltip(tx: Transaction, mark: DuplicateDetector.Mark): String {
        val headline = when (mark.severity) {
            DuplicateDetector.Severity.STRONG -> "Possible double-submit"
            DuplicateDetector.Severity.MEDIUM -> "Redundant duplicate request"
            DuplicateDetector.Severity.INFO -> "Repeated request"
        }
        val ord = when (mark.ordinal) { 2 -> "2nd"; 3 -> "3rd"; else -> "${mark.ordinal}th" }
        val extra = if (mark.severity == DuplicateDetector.Severity.STRONG)
            "<br/>It fired before the previous one responded — likely a missing debounce or disabled-button guard."
        else ""
        return "<html><b>$headline</b><br/>$ord identical ${tx.request.method} call in a quick burst.$extra</html>"
    }

    private fun onLiveTick() {
        renderer.spinnerFrame++
        val model = list.model
        val anyPending = (0 until model.size).any { (model.getElementAt(it) as? LogEvent.Http)?.tx?.isPending() == true }
        if (anyPending) list.repaint()
        val sel = list.selectedValue
        if (sel is LogEvent.Http && sel.tx.isPending()) detail.tick(store.elapsedMillis(sel.id), renderer.spinnerFrame)
    }

    override fun dispose() {
        liveTimer.stop()
        reader.stop()
        mocksController.onCaptureStopped()
        statusDot.dispose()
        // Drop the MCP session so a closed project's capture stops being readable.
        McpSessions.unregister(mcpToken)
    }

    private fun copyToClipboard(text: String, toast: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        Toast.show(list, toast)
    }

    /** Opens the mock editor pre-filled from a captured transaction, and registers the rule. */
    private fun mockTransaction(tx: Transaction) {
        val dialog = MockRuleDialog(project, MockRuleDialog.fromTransaction(tx), tx.response?.body?.text, "Mock endpoint")
        if (dialog.showAndGet()) mocksController.addOrUpdate(dialog.result(), dialog.capturedBaseBody())
    }

    private fun editMockRule(rule: io.github.siddharthjaswal.logpose.model.MockRule) {
        val dialog = MockRuleDialog(project, rule, mocksController.baseBodyFor(rule.id), "Edit mock rule")
        if (dialog.showAndGet()) mocksController.addOrUpdate(dialog.result(), dialog.capturedBaseBody())
    }

    /**
     * Copies the selected rows as a compact, paste-ready timeline — one `METHOD path` (or
     * `FCM channel`) per line, in list order — so the sequence of calls can be shared without
     * any of the request/response detail. `selectedValuesList` is already index-ordered.
     */
    private fun copySelectedTimeline() {
        val events = list.selectedValuesList
        if (events.isEmpty()) return
        val text = events.joinToString("\n") { timelineLabel(it) }
        copyToClipboard(text, if (events.size == 1) "Copied 1 row" else "Copied ${events.size} rows")
    }

    private fun timelineLabel(event: LogEvent): String = when (event) {
        is LogEvent.Http -> "${event.tx.request.method} ${event.tx.request.path.ifBlank { event.tx.request.url }}"
        is LogEvent.Fcm -> "FCM ${fcmTimelineLabel(event.msg)}"
        else -> "${KindPresenter.kindLabel(event)} ${KindPresenter.present(event)?.title ?: event.id}"
    }

    private fun fcmTimelineLabel(msg: FcmMessage): String {
        val channel = msg.data.entries.firstOrNull { it.key.equals("channel", ignoreCase = true) }
            ?.value?.takeIf { it.isNotBlank() }
        return channel
            ?: msg.collapseKey?.takeIf { it.isNotBlank() }
            ?: msg.from?.takeIf { it.isNotBlank() }
            ?: if (msg.event == "token") "token refreshed" else "data message"
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
            // Shift/⌘/Ctrl clicks are extending the selection — don't treat them as a cURL copy.
            if (e.isShiftDown || e.isMetaDown || e.isControlDown) return
            val idx = indexAt(e)
            if (idx < 0) return
            // Only the hovered, non-muted row paints the cURL affordance (HTTP rows only).
            if (idx != renderer.hoveredIndex) return
            val tx = (list.model.getElementAt(idx) as? LogEvent.Http)?.tx ?: return
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
            // Right-clicking outside the current selection moves to that row; within it, keep the
            // whole multi-selection so "Copy timeline" acts on everything selected.
            if (idx !in list.selectedIndices) list.selectedIndex = idx

            val group = if (list.selectedIndices.size > 1) {
                DefaultActionGroup().apply {
                    add(act("Copy timeline (${list.selectedIndices.size} rows)", AllIcons.Actions.Copy) { copySelectedTimeline() })
                }
            } else when (val ev = list.selectedValue ?: return) {
                is LogEvent.Http -> httpGroup(ev.tx)
                is LogEvent.Fcm -> fcmGroup(ev.msg)
                else -> structuredGroup(ev)
            }
            showActionPopup(group, e)
        }

        private fun httpGroup(tx: Transaction): ActionGroup {
            val key = MutedEndpoints.keyOf(tx)
            val muted = MutedEndpoints.isMuted(tx)
            return DefaultActionGroup().apply {
                add(act("Copy as cURL", AllIcons.Actions.Copy) { copyToClipboard(CurlBuilder.build(tx), "cURL copied") })
                add(act("Copy as JSON", AllIcons.Actions.Copy) {
                    copyToClipboard(prettyJson.encodeToString(Transaction.serializer(), tx), "Transaction JSON copied")
                })
                add(act("Copy URL", AllIcons.Actions.Copy) { copyToClipboard(tx.request.url, "URL copied") })
                tx.response?.body?.text?.let { body ->
                    add(act("Copy response body", AllIcons.Actions.Copy) { copyToClipboard(body, "Response body copied") })
                }
                addSeparator()
                add(act("Mock this endpoint…", AllIcons.Actions.Execute) { mockTransaction(tx) })
                addSeparator()
                add(act(if (muted) "Unmute  $key" else "Mute  $key", AllIcons.Actions.Suspend) { MutedEndpoints.toggle(tx); list.repaint() })
                if (MutedEndpoints.patterns().isNotEmpty()) {
                    add(act("Clear all mutes", AllIcons.Actions.GC) { MutedEndpoints.clearAll(); list.repaint() })
                }
            }
        }

        private fun fcmGroup(msg: FcmMessage): ActionGroup = DefaultActionGroup().apply {
            add(act("Copy as JSON", AllIcons.Actions.Copy) {
                copyToClipboard(prettyJson.encodeToString(FcmMessage.serializer(), msg), "FCM JSON copied")
            })
            if (msg.data.isNotEmpty()) {
                add(act("Copy data payload", AllIcons.Actions.Copy) {
                    copyToClipboard(
                        prettyJson.encodeToString(kotlinx.serialization.serializer<Map<String, String>>(), msg.data),
                        "Data payload copied",
                    )
                })
            }
        }

        private fun structuredGroup(ev: LogEvent): ActionGroup = DefaultActionGroup().apply {
            add(act("Copy as JSON", AllIcons.Actions.Copy) {
                copyToClipboard(
                    prettyJson.encodeToString(Envelope.serializer(), ev.envelope),
                    "Event JSON copied",
                )
            })
            add(act("Copy payload", AllIcons.Actions.Copy) {
                copyToClipboard(
                    prettyJson.encodeToString(JsonElement.serializer(), ev.envelope.payload),
                    "Payload copied",
                )
            })
            ev.traceId?.let { trace ->
                add(act("Filter by trace  $trace", AllIcons.Actions.Find) {
                    filterBar.setQuery(trace)
                })
            }
        }

        /** A one-off menu action (native IDE popup item). */
        private fun act(text: String, icon: javax.swing.Icon?, run: () -> Unit): AnAction =
            object : DumbAwareAction(text, null, icon) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = run()
            }

        /** Shows a native, rounded, keyboard-navigable action-group popup at the click. */
        private fun showActionPopup(group: ActionGroup, e: MouseEvent) {
            JBPopupFactory.getInstance()
                .createActionGroupPopup(
                    null, group, DataContext.EMPTY_CONTEXT,
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
                )
                .show(RelativePoint(e))
        }
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
            showDetail(null)
            refreshList()
        }
    }

    /**
     * Exposes this project's mock rules to the MCP server, so an agent can close the loop:
     * read the real failure, then serve a response that reproduces or fixes the state.
     *
     * These calls arrive on a Netty IO thread, never the EDT. That's safe because
     * [MocksController] is synchronized and already takes calls from the reader thread, and the
     * panel's listener marshals its UI refresh through a Swing [Alarm].
     */
    private inner class McpMocks : io.github.siddharthjaswal.logpose.mcp.McpTools.Mocks {
        override fun list() = mocksController.rules()
        override fun hits() = mocksController.deviceState().hits
        override fun deviceHint(): String {
            val device = mocksController.deviceState()
            return if (device.helloSeen) {
                "${device.pkg ?: "device"} · synced rev ${device.syncedRevision}"
            } else {
                "waiting for the app to announce itself — restart the app (or start capture " +
                    "before launching it); needs logpose-android ≥ 1.1.0. Rules won't serve yet."
            }
        }
        override fun deviceReady() = mocksController.deviceState().helloSeen
        override fun create(rule: io.github.siddharthjaswal.logpose.model.MockRule, baseBody: String?) =
            mocksController.addOrUpdate(rule, baseBody)
        override fun setEnabled(id: String, enabled: Boolean) = mocksController.setEnabled(id, enabled)
        override fun delete(id: String) = mocksController.remove(id)
    }

    /**
     * Copies the one-line command that points a coding agent at this project's capture, so the
     * agent can read what the app actually did instead of being told about it second-hand.
     */
    private inner class ConnectAgentAction : AnAction(
        "Connect Coding Agent",
        "Copy the MCP command that lets Claude Code (or any MCP client) read this capture",
        AllIcons.Actions.Lightning,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            val port = BuiltInServerManager.getInstance().port
            val command = "claude mcp add --transport http logpose " +
                "http://localhost:$port${LogPoseMcpHandler.PATH} " +
                "--header \"${LogPoseMcpHandler.TOKEN_HEADER}: $mcpToken\""
            copyToClipboard(command, "MCP connect command copied — paste it in your terminal")
        }
    }
}
