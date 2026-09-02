package io.github.siddharthjaswal.logpose.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.PluginId
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
import io.github.siddharthjaswal.logpose.analysis.Correlation
import io.github.siddharthjaswal.logpose.analysis.CorrelationIndex
import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.analysis.Groupings
import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.analysis.Suggestion
import io.github.siddharthjaswal.logpose.analysis.WorkerLifecycle
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.logcat.ControlMessage
import io.github.siddharthjaswal.logpose.logcat.DeviceChoice
import io.github.siddharthjaswal.logpose.logcat.LogcatReader
import io.github.siddharthjaswal.logpose.logcat.TransactionParser
import io.github.siddharthjaswal.logpose.mcp.LogPoseMcpHandler
import io.github.siddharthjaswal.logpose.mcp.McpSessions
import io.github.siddharthjaswal.logpose.mcp.McpTools
import io.github.siddharthjaswal.logpose.mock.DeviceCapability
import io.github.siddharthjaswal.logpose.mock.DeviceFeature
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.mock.PushController
import io.github.siddharthjaswal.logpose.mock.PushReplay
import io.github.siddharthjaswal.logpose.mock.ScenarioSnapshot
import io.github.siddharthjaswal.logpose.mock.ScenarioStore
import io.github.siddharthjaswal.logpose.mock.SyncState
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.PushMessage
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import io.github.siddharthjaswal.logpose.store.EventStore
import io.github.siddharthjaswal.logpose.ui.ComposePushDialog
import io.github.siddharthjaswal.logpose.ui.CorrelationKeysDialog
import io.github.siddharthjaswal.logpose.settings.CorrelationSettings
import io.github.siddharthjaswal.logpose.presentation.CurlBuilder
import io.github.siddharthjaswal.logpose.presentation.EventType
import io.github.siddharthjaswal.logpose.ui.FindByValueDialog
import io.github.siddharthjaswal.logpose.ui.FcmDetailView
import io.github.siddharthjaswal.logpose.ui.FilterBar
import io.github.siddharthjaswal.logpose.presentation.FilterPresentation
import io.github.siddharthjaswal.logpose.presentation.FilterState
import io.github.siddharthjaswal.logpose.ui.FilteredToNothingPanel
import io.github.siddharthjaswal.logpose.ui.GenericDetailView
import io.github.siddharthjaswal.logpose.presentation.KindPresenter
import io.github.siddharthjaswal.logpose.ui.isPending
import io.github.siddharthjaswal.logpose.ui.LinkLabel
import io.github.siddharthjaswal.logpose.ui.LogPoseNotifications
import io.github.siddharthjaswal.logpose.ui.MockDiff
import io.github.siddharthjaswal.logpose.ui.MockRuleDialog
import io.github.siddharthjaswal.logpose.ui.MocksBar
import io.github.siddharthjaswal.logpose.settings.MutedEndpoints
import io.github.siddharthjaswal.logpose.ui.SaveScenarioDialog
import io.github.siddharthjaswal.logpose.ui.StatusDot
import io.github.siddharthjaswal.logpose.ui.Theme
import io.github.siddharthjaswal.logpose.ui.Toast
import io.github.siddharthjaswal.logpose.ui.TraceWaterfallPanel
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
import io.github.siddharthjaswal.logpose.presentation.RowContent

/** The LogPose tool window: a master/detail view over captured HTTP transactions. */
class LogPosePanel(private val project: com.intellij.openapi.project.Project) : JPanel(BorderLayout()), Disposable {

    /**
     * Where this project's persisted settings live. Core takes a [io.github.siddharthjaswal.logpose.settings.KeyValueStore]
     * rather than a `Project` so the same controllers run headless; in the IDE it is the same
     * `PropertiesComponent` those settings have always been written to.
     */
    private val projectStore = IdeKeyValueStore.forProject(project)

    private val store = EventStore()
    private val parser = TransactionParser()

    /**
     * A `var`, because the target device is a constructor argument: picking a different device
     * replaces the reader. Only startCapture assigns it (on the EDT), and every start resolves
     * the serial afresh, so a stale reader can never be re-attached against the wrong device.
     */
    private var reader = LogcatReader()

    /**
     * The device this project captures from — null is *auto* (no `-s`, today's behaviour).
     * Persisted per project; adb is only consulted when the picker opens or capture starts,
     * always off the EDT.
     */
    private var selectedDeviceSerial: String? = DeviceSelection.serial(project)

    /** What `adb devices -l` last reported — only for labelling the picker, never for logic. */
    private var knownDevices: List<Adb.DeviceInfo> = emptyList()

    private val devicePicker = LinkLabel("").apply {
        toolTipText = "Which attached device LogPose reads. The list refreshes from `adb devices -l` " +
            "when it opens; Auto tails the default device, as adb picks it."
        onClick = { openDevicePicker() }
    }

    /**
     * Correlation's cache: one searchable haystack and one key extraction per event, computed on
     * the reader thread as events arrive.
     *
     * Nothing about correlation may run on a paint or on the 150 ms refresh tick — the scans are
     * O(payload) — so every read path here goes through the index, and the renderer uses its
     * paint-safe read, which answers "not cached" instead of scanning.
     */
    private val correlation = CorrelationIndex().apply { setKeys(CorrelationSettings.keys(projectStore)) }

    private val renderer = TransactionListRenderer()
    // Latest duplicate-burst marks, keyed by transaction id; recomputed each refresh and read
    // by the renderer, the row tooltip, and the detail banner.
    private var duplicateMarks: Map<String, DuplicateDetector.Mark> = emptyMap()

    /**
     * The state sequence each worker request was observed to pass through.
     *
     * The store cannot answer this: the library reuses `workId` as the envelope id, so a re-put
     * replaces the earlier state rather than adding a row — which is what makes a worker one
     * mutating row, and what destroys enqueued/running once the terminal state lands. Written on the
     * reader thread beside the correlation warm-up; read by one context-menu item.
     */
    private val workerLifecycle = WorkerLifecycle()

    /**
     * Which collapsed groups the user has opened, by [RowCollapse.Row.key].
     *
     * A panel field, deliberately: the renderer is a rubber stamp with no per-row state, and the
     * list model is thrown away and rebuilt every 150ms. The key embeds the group's **anchor event
     * id**, so it survives both — and it is never an index and never a row object's identity, so a
     * new poll arriving cannot silently re-key a group the user expanded. Pruned each refresh
     * against the keys the pass actually produced, so a long session can't accumulate keys whose
     * anchors the store has since evicted.
     */
    private val expandedGroups = HashSet<String>()

    /**
     * Every event id in the list, mapped to the row standing for it — a folded poll's members and a
     * transaction's ceremony included. This is what lets a waterfall lane click reach a row that no
     * longer has an element of its own, and what expands a collapsed row back into its members when
     * a timeline is copied.
     */
    private var memberRows: Map<String, RowCollapse.Row> = emptyMap()

    /**
     * The filtered list the current rows were folded from — kept so the context menu can ask what a
     * row would belong to if *nothing* were expanded, which is the only way "Collapse this run"
     * can find its target from one of the members it was expanded into. Recomputing there costs one
     * O(n) pass on a right-click, which is far cheaper than folding twice on every 150ms tick.
     */
    private var lastFiltered: List<LogEvent> = emptyList()

    /**
     * The collapsed run the detail card is currently describing, by [RowCollapse.Row.key], and the
     * occurrence within it the user stepped to.
     *
     * A null pin means *follow the latest*, which is how a run opens (§6: the row shows the latest
     * timestamp, so the card agrees with it). The first arrow click pins, and a pinned card holds
     * still while the run keeps growing underneath it — only the `n / N` total moves. The pin is
     * dropped the moment the selection moves to a different row, so it can never describe a run the
     * user has left.
     */
    private var shownGroupKey: String? = null
    private var pinnedOccurrenceId: String? = null

    private val list = object : JBList<RowCollapse.Row>(javax.swing.DefaultListModel<RowCollapse.Row>()) {
        override fun getToolTipText(event: MouseEvent): String? {
            val idx = locationToIndex(event.point)
            if (idx < 0) return null
            val bounds = getCellBounds(idx, idx) ?: return null
            if (!bounds.contains(event.point)) return null
            val row = model.getElementAt(idx)
            (row as? RowCollapse.Row.Group)?.let { return groupTooltip(it) }
            val tx = (row.lead as? LogEvent.Http)?.tx ?: return null
            return duplicateMarks[tx.id]?.let { duplicateTooltip(tx, it) }
        }
    }
    private val detail = TransactionDetailView(project)
    private val fcmDetail = FcmDetailView(project)
    private val genericDetail = GenericDetailView(project)
    /**
     * The one card that isn't about the selected row: a whole trace on a time axis. Its data is a
     * snapshot taken here, on the EDT, once per refresh tick — the panel itself never touches the
     * store, so painting can't block behind the reader thread.
     */
    private val waterfall = TraceWaterfallPanel(
        hostAge = { id -> store.elapsedMillis(id) },
        onSelectEvent = { id -> selectEventInList(id) },
        onSwitchGrouping = { grouping -> showWaterfall(grouping, waterfallAlternatives) },
        onEditKeys = { editCorrelationKeys() },
        onFindByValue = { findByValue() },
    )
    // Routes the single detail slot between the per-kind views. "generic" is the fallback for
    // every app-defined kind, so an unknown event is still inspectable.
    private val detailCards = CardLayout()
    private val detailPane = JPanel(detailCards).apply {
        isOpaque = true; background = Theme.bg0
        add(detail, "http")
        add(fcmDetail, "fcm")
        add(genericDetail, "generic")
        add(waterfall, "waterfall")
    }
    /** Non-null while the waterfall card is showing; the grouping it's showing. */
    private var waterfallGrouping: Grouping? = null
    /** The other groupings the row that opened the waterfall belongs to, for the header switcher. */
    private var waterfallAlternatives: List<Grouping> = emptyList()
    private val filterBar = FilterBar()

    /**
     * The list half of the splitter, and the one state that replaces it: `events > 0 && matches
     * == 0`. Both buttons act through the bar, so "Clear filters" and a contextual loosening are
     * the same operations the chips are, not a second path into the filter state.
     */
    private val filteredToNothing = FilteredToNothingPanel(
        onClearFilters = { filterBar.clearAllFilters() },
        onLoosen = { id -> filterBar.loosen(id) },
    )
    private val listCards = CardLayout()
    private val listPane = JPanel(listCards).apply { isOpaque = true; background = Theme.bg0 }

    private val statusDot = StatusDot()

    private val pluginVersion: String? =
        PluginManager.getInstance().findEnabledPlugin(PluginId.getId("io.github.siddharthjaswal.logpose"))?.version
    private val versionLabel = JBLabel().apply {
        foreground = Theme.textDim
        font = JBUI.Fonts.label(11f)
        verticalAlignment = javax.swing.SwingConstants.CENTER
    }

    private val mocksController = MocksController(projectStore)

    /**
     * Push injection: the same reverse channel as mocks, a different state machine (a one-shot
     * command with a delivery outcome, not a revisioned set). Reads its device facts from the
     * mocks controller, which owns the handshake.
     */
    private val pushController = PushController(
        packageName = { mocksController.deviceState().pkg },
        deviceSerial = { mocksController.deviceSerial },
        libVersion = { mocksController.deviceLibVersion() },
    )

    /** Committable scenario files under `<project>/.logpose/scenarios`; null for a project with
     *  no directory on disk. */
    private val scenarioStore = ScenarioStore.forProject(project.basePath)
    /** Name + rule count of each saved scenario, read off the EDT and cached for the menu. */
    private var scenarioInfos: List<ScenarioInfo> = emptyList()

    private val mocksBar = MocksBar(
        onEdit = { editMockRule(it) },
        onDelete = { mocksController.remove(it) },
        onToggle = { id, on -> mocksController.setEnabled(id, on) },
        onDisableAll = { mocksController.disableAll() },
        onDiff = { rule -> MockDiff.show(project, rule, mocksController.baseBodyFor(rule.id)) },
        onScenarios = { near -> showScenariosPopup(near) },
    )

    private data class ScenarioInfo(val name: String, val rules: Int, val note: String?)

    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true }
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val refreshScheduled = AtomicBoolean(false)
    private var suppressSelectionEvents = false
    private var lastShown: LogEvent? = null

    // Drives the in-flight UI: ticking duration + spinner while any request is pending.
    private val liveTimer = javax.swing.Timer(250) { onLiveTick() }

    // Lets a coding agent read this project's capture over MCP. The token both authenticates
    // the caller and selects which open project's capture to serve.
    private val mcpToken = McpSessions.tokenFor(projectStore)

    // Whether logcat is being tailed right now — read from the MCP (Netty) thread, so an agent
    // can tell "no matching events" from "capture isn't running". Kept off the UI's statusDot,
    // which lives on the EDT.
    @Volatile private var captureActive = false
    private var reattachAttempts = 0
    private val MAX_REATTACH = 5
    private val REATTACH_DELAY_MS = 2_000

    init {
        // Idempotent, and belt-and-braces with the tool window factory: nothing may read a mute
        // through core's in-memory fallback while the IDE has the real ones on disk.
        MutedEndpoints.store = IdeKeyValueStore.application()

        isOpaque = true
        background = Theme.bg0

        McpSessions.register(
            mcpToken,
            McpSessions.Session(
                projectName = project.name,
                store = store,
                hostAgeMillis = { id -> store.elapsedMillis(id) },
                exposeBodies = { McpSessions.exposeBodies(projectStore) },
                captureRunning = { captureActive },
                clearCapture = { store.clear() },
                mocks = McpMocks(),
                push = McpPush(),
                // Scenarios are files, so a project with no directory on disk simply doesn't
                // offer them — the tools then say so rather than failing obscurely.
                scenarios = scenarioStore?.let { McpScenarios(it) },
                // The same vocabulary and the same cache the UI groups by, so an agent's
                // get_related and a click on the row's waterfall glyph open the same flow.
                correlations = McpCorrelations(),
                // The store's own waiter registry: an agent parks a predicate and the reader
                // thread wakes it, instead of the agent polling list_events in a loop.
                waits = McpTools.Waits { timeout, predicate -> store.addWaiter(timeout, predicate) },
            ),
        )

        renderer.elapsedProvider = { tx ->
            if (tx.isPending()) store.elapsedMillis(tx.id) else null
        }
        // The running worker's count-up. Same clock as the pending-HTTP timer, so the two read the
        // same way; it measures the envelope's open span, which for a worker is queue + run. The
        // renderer subtracts the queue the device reported (RowContent.TimeCell.LiveCountUp's
        // offset), leaving the run — and where the device reported none, the whole span stands and
        // the detail's `timing` line says what it includes.
        renderer.eventElapsedProvider = { id -> store.elapsedMillis(id).takeIf { it > 0 } }
        // Cache-only, by contract: this is called for every painted row.
        renderer.groupingProvider = { event -> correlation.hasCachedKeyValue(event) }
        liveTimer.start()

        // Stepping through a collapsed run pins the occurrence, so the card holds still while the
        // run keeps growing. Nothing else moves: the list selection stays on the row (the other
        // occurrences have no row of their own), and the waterfall is untouched.
        detail.onOccurrenceChanged = { id -> pinnedOccurrenceId = id }

        // A trace id shown on any detail card is also the way into that flow's waterfall.
        detail.onOpenTrace = { trace -> showWaterfall(traceGrouping(trace)) }
        fcmDetail.onOpenTrace = { trace -> showWaterfall(traceGrouping(trace)) }
        genericDetail.onOpenTrace = { trace -> showWaterfall(traceGrouping(trace)) }

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
            if (it.valueIsAdjusting) return@addListSelectionListener
            // List and waterfall are one selection model: the lane whose row is selected wears the
            // row's own accent tint and rail. Kept in step even when the selection was *restored*
            // under a refresh (which is suppressed below), since the waterfall survives those.
            // A collapsed run's lead is its latest occurrence, so the lane that lights up is the
            // one the detail is showing.
            waterfall.setSelectedId(list.selectedValue?.lead?.id)
            if (!suppressSelectionEvents) showDetailFor(list.selectedValue)
        }
        val mouse = ListMouse()
        list.addMouseListener(mouse)
        list.addMouseMotionListener(mouse)

        filterBar.onChange = { refreshList() }
        filterBar.onFindByValue = { findByValue() }
        filterBar.onCorrelationKeys = { editCorrelationKeys() }
        // HTTP search reads the same cached haystack the correlation chip matches against —
        // warmed on the reader thread as events arrive, a map lookup by the time a keystroke
        // filters. The cache recomputes on a miss (an evicted entry), which is the cost model
        // the chip already accepted.
        filterBar.httpTextOf = { event -> correlation.textOf(event) }
        refreshDevicePickerLabel()

        val listScroll = JBScrollPane(list).apply {
            border = JBUI.Borders.empty(); viewport.isOpaque = true; viewport.background = Theme.bg0
        }
        // Two states share the list's half of the splitter: the timeline, and — when the capture is
        // full but the filter has emptied it — the state that says so. The list's own empty text is
        // then free to mean the one thing it should: nothing has been captured at all.
        listPane.add(listScroll, "list")
        listPane.add(filteredToNothing, "empty")
        listPane.minimumSize = Dimension(JBUI.scale(220), 0)
        detailPane.minimumSize = Dimension(JBUI.scale(320), 0)
        val splitter = OnePixelSplitter(false, 0.44f).apply {
            firstComponent = listPane
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
            refreshAlarm.addRequest({ refreshMocksBar() }, 0)
        }
        // Push acks share the reverse channel with mock acks but not their meaning, so they're
        // handed straight to the push controller.
        mocksController.onPushAck = { ack -> pushController.onAck(ack) }
        pushController.onProblem = { title, detail ->
            LogPoseNotifications.warn(project, title, detail)
        }
        // Sync failures (adb refused the broadcast, the device never acknowledged the rules) are
        // about the session, not the selected row — they go to the IDE's notifications, never the
        // detail pane. The controller stays free of platform UI by reporting through this sink.
        mocksController.onProblem = { title, detail ->
            LogPoseNotifications.warn(project, title, detail)
        }
        refreshMocksBar()
        reloadScenarios()
    }

    /** The one place the mocks strip is repainted, so every caller shows the same three facts. */
    private fun refreshMocksBar() {
        mocksBar.refresh(mocksController.rules(), mocksController.deviceState(), scenarioInfos.size)
        refreshVersionLabel()
    }

    /** Plugin version, plus the device library's once a hello has named it — skew is what bites. */
    private fun refreshVersionLabel() {
        val plugin = pluginVersion?.let { "v$it" }
        val device = mocksController.deviceState().libVersion?.let { "device $it" }
        versionLabel.text = listOfNotNull(plugin, device).joinToString(" · ")
        versionLabel.toolTipText = when {
            device != null -> "LogPose plugin $plugin, on-device library ${device.removePrefix("device ")}"
            plugin != null -> "LogPose plugin $plugin — the library version appears once a device says hello"
            else -> null
        }
    }

    private fun buildHeader(): Component {
        val group = DefaultActionGroup().apply {
            add(CaptureToggleAction()); add(ClearAction())
            addSeparator()
            // The two things LogPose can *start*: a push into the app, and a whole bottled
            // session. Both are also reachable where they're most natural (an FCM row's menu,
            // the mocks strip); the toolbar is what makes them reachable with an empty timeline.
            add(ComposePushAction()); add(ScenariosAction())
            addSeparator()
            add(ConnectAgentAction())
        }
        val toolbar: ActionToolbar = ActionManager.getInstance().createActionToolbar("LogPose", group, true)
        toolbar.targetComponent = this

        // Capture / Clear on the left, with the pulsing status dot. No "LogPose" label —
        // the IDE already titles the tool window.
        val actionsLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
            isOpaque = false
            add(statusDot)
            add(toolbar.component)
            // The device selector: a quiet link, because with 0 or 1 devices it has nothing to
            // say — "auto" is correct and identical to before it existed. It earns attention only
            // when a second device makes the choice real, and then it names the choice.
            add(devicePicker)
        }
        val toolbarRow = JPanel(BorderLayout()).apply {
            isOpaque = true; background = Theme.bg0
            border = JBUI.Borders.empty(3, 8)
            add(actionsLeft, BorderLayout.WEST)
            add(versionLabel, BorderLayout.EAST)
        }
        refreshVersionLabel()

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

    /**
     * Bumped by every start *and* stop, and checked when the off-EDT serial resolution hops back:
     * a start whose resolution is still in flight when the user stops (or stops and starts again)
     * must not attach its reader — without this, a quick stop/start could leave two readers
     * tailing, one of them orphaned by the `reader` var reassignment.
     */
    private var captureGeneration = 0

    private fun startCapture() {
        parser.reset()
        captureActive = true
        val generation = ++captureGeneration
        reattachAttempts = 0
        statusDot.capturing = true
        // Which device to tail can require `adb devices -l` (two attached devices and no explicit
        // choice would make a bare `adb logcat` fail), so the serial is resolved on a pooled
        // thread first, and only then — back on the EDT — do the reader attach and the mock push
        // go out, both against the same serial.
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val ready = Adb.devices().filter { it.ready }
            val choice = DeviceChoice.choose(selectedDeviceSerial, ready)
            SwingUtilities.invokeLater {
                // Stopped (or stopped-and-restarted) before the serial resolved.
                if (!captureActive || generation != captureGeneration) return@invokeLater
                knownDevices = ready
                refreshDevicePickerLabel()
                mocksController.deviceSerial = choice.serial
                reader = LogcatReader(deviceSerial = choice.serial)
                mocksController.onCaptureStarted()
                refreshMocksBar()
                attachReader()
                scheduleRefresh()
                choice.notice?.let { LogPoseNotifications.info(project, "LogPose: device", it) }
            }
        }
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
                parser.accept(line)?.let { event ->
                    // Correlation is computed here — on the reader thread, as the event arrives —
                    // and never again. Warming before the store's listeners fire means the EDT's
                    // refresh and the first paint of the row both find a cache hit.
                    correlation.warm(event)
                    // The store is about to overwrite this worker's previous state in place, so the
                    // sequence is recorded here or nowhere.
                    if (event is LogEvent.Worker) workerLifecycle.note(event, System.currentTimeMillis())
                    store.add(event)
                }
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
        captureGeneration++ // invalidates any start still resolving its device off the EDT
        statusDot.capturing = false
        reader.stop()
        // Fail-safe: clear any rules the device is holding so a forgotten mock can't linger
        // after capture ends. Local rules persist for the next session.
        mocksController.onCaptureStopped()
        // Acks ride back on logcat, so a push still in flight can no longer be answered — drop
        // the waiters instead of letting them all time out into notifications.
        pushController.reset()
        // Repaint here rather than from the controller: dispose() takes the same path, and
        // scheduling onto a disposed panel's alarm is exactly the kind of thing that logs a stack
        // trace at IDE shutdown.
        refreshMocksBar()
    }

    // ---- device picker ------------------------------------------------------------------------

    /** What the toolbar link says: the chosen device (by model when known), or nothing loud. */
    private fun refreshDevicePickerLabel() {
        val serial = selectedDeviceSerial
        devicePicker.text = when (serial) {
            null -> "device: auto ▾"
            else -> "device: ${knownDevices.firstOrNull { it.serial == serial }?.label ?: serial} ▾"
        }
    }

    /** Lists devices off the EDT — the list refreshes on every open, never on a timer. */
    private fun openDevicePicker() {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val devices = Adb.devices()
            SwingUtilities.invokeLater {
                knownDevices = devices.filter { it.ready }
                refreshDevicePickerLabel()
                showDevicePopup(devices)
            }
        }
    }

    private fun showDevicePopup(devices: List<Adb.DeviceInfo>) {
        val checked = AllIcons.Actions.Checked
        val group = DefaultActionGroup().apply {
            add(
                act("Auto — first attached device", if (selectedDeviceSerial == null) checked else null) {
                    selectDevice(null, "the default device")
                }
            )
            val ready = devices.filter { it.ready }
            if (ready.isNotEmpty()) addSeparator("Attached")
            ready.forEach { device ->
                add(
                    act(device.label, if (device.serial == selectedDeviceSerial) checked else null) {
                        selectDevice(device.serial, device.label)
                    }
                )
            }
            // Not selectable — adb can't run commands against them — but silently hiding a phone
            // that is plugged in and merely unauthorized would read as a broken picker.
            devices.filterNot { it.ready }.takeIf { it.isNotEmpty() }?.let { rest ->
                addSeparator("Not ready")
                rest.forEach { add(act("${it.label} — ${it.state}", null) {}) }
            }
            if (devices.isEmpty()) {
                addSeparator()
                add(act("No devices attached", null) {})
            }
        }
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            null, group, DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
        )
        if (devicePicker.isShowing) popup.showUnderneathOf(devicePicker) else popup.showInFocusCenter()
    }

    /**
     * Applies a device choice. Order matters when capturing: stop first — that clears the mock
     * rules still held by the *old* device — then move the serial, then start again, which pushes
     * the rules to the new one and says so.
     */
    private fun selectDevice(serial: String?, label: String) {
        if (serial == selectedDeviceSerial) return
        val wasCapturing = captureActive
        if (wasCapturing) stopCapture()
        selectedDeviceSerial = serial
        DeviceSelection.setSerial(project, serial)
        mocksController.deviceSerial = serial
        refreshDevicePickerLabel()
        if (wasCapturing) {
            LogPoseNotifications.info(project, "LogPose: device changed", "Capture restarting on $label.")
            startCapture()
        }
    }

    private fun scheduleRefresh() {
        if (refreshScheduled.compareAndSet(false, true)) {
            refreshAlarm.addRequest({ refreshScheduled.set(false); refreshList() }, 150)
        }
    }

    private fun refreshList() {
        // The anchor is always a real event id, and a group's anchor never moves as the run grows —
        // which is exactly why selection is saved by it rather than by the lead (a poll run's lead
        // is its latest member, so it changes once per arrival).
        val selectedAnchor = list.selectedValue?.anchorId
        val all = store.snapshot()
        val state = filterBar.state()

        // Detect duplicate bursts over the FULL (time-ordered) capture, not the filtered view —
        // filtering must not break a burst chain or change a call's ordinal. Duplicates are an
        // HTTP-only concept, so only the HTTP events feed the detector.
        val httpTxs = all.filterIsInstance<LogEvent.Http>().map { it.tx }
        duplicateMarks = DuplicateDetector.analyze(httpTxs)
        renderer.duplicateProvider = { duplicateMarks[it.id] }

        // Like "duplicates only", the correlation chip can't live in FilterState: a value hides in
        // bodies the row never shows, so it's matched against the cached haystack instead.
        val grouping = filterBar.correlationFilter()
        val filtered = all.filter { passes(it, state, grouping) }
        filterBar.setCount(filtered.size, all.size)
        // Derived filter-bar state, computed here — on a filter change — and never during a paint:
        // whether the search would have matched db events the DB opt-in is hiding (the echo row's
        // "db hidden by default · show"), and, when nothing matches at all, what to say about it.
        val hiddenDb = FilterPresentation.wouldMatchHiddenDb(state, all)
        filterBar.setHiddenDbMatch(hiddenDb)
        showListState(all, filtered, state, grouping, hiddenDb)

        // Collapsing runs LAST, over the already-filtered list, and it is the only thing between the
        // filter and the model. It is pure presentation: the store still holds every event, and MCP,
        // the waterfall, get_trace, correlation, duplicate detection and export all read the store
        // (or this snapshot) rather than the list model, so none of them can see a fold. Do not
        // route any of them through `list.model`.
        //
        // Note the ordering dependency: DuplicateDetector.analyze above feeds the pass its STRONG
        // marks, which is what keeps an overlapping double-submit out of a `×N` pill.
        val rows = RowCollapse.collapse(filtered, expandedGroups, duplicateMarks, store::sessionOf)
        memberRows = RowCollapse.memberIndex(rows)
        lastFiltered = filtered
        // Drop expansion keys whose groups no longer exist — a run that broke apart, or an anchor
        // the store's cap evicted. Without this the set grows for the life of the session.
        // Pruned by whether the group's ANCHOR is still in the list, not by whether a Group row was
        // emitted: an expanded group emits its members as plain rows and produces no Group at all,
        // so pruning on the emitted rows would re-collapse it on the very next tick.
        expandedGroups.retainAll { key -> key.substringAfter(':') in memberRows }

        val model = javax.swing.DefaultListModel<RowCollapse.Row>()
        rows.forEach { model.addElement(it) }

        suppressSelectionEvents = true
        try {
            list.model = model
            if (selectedAnchor != null) restoreSelection(model, selectedAnchor)
        } finally {
            suppressSelectionEvents = false
        }

        val sel = list.selectedValue
        val shown = waterfallGrouping
        if (shown != null) {
            // The waterfall is a view over a whole flow, not over the selection, so a refresh
            // re-snapshots it instead of letting the selected row's detail take the card back.
            waterfall.show(shown, membersOf(shown, all), waterfallAlternatives, sel?.lead?.id)
        } else if (sel?.lead != lastShown) {
            // If the selected transaction's data changed (e.g. pending → completed, or a poll run
            // gaining a newer latest occurrence), re-render the detail even though the selection
            // index didn't change. For a run this is also how `n / 30` becomes `n / 31` — and,
            // because the pin survives, how a card the user stepped back to stays where they put it.
            showDetailFor(sel)
        }
    }

    /**
     * Puts the selection back on the row that holds [anchorId], after the model was rebuilt.
     *
     * Two passes, and the second is what makes collapsing survivable in both directions: a row that
     * has just been absorbed into a forming run is now *inside* a group's members, and a group that
     * was expanded (or broke apart on a failed call) is now its own single row. Neither can be
     * defeated by a member arriving, because a group's anchor never moves.
     */
    private fun restoreSelection(model: javax.swing.DefaultListModel<RowCollapse.Row>, anchorId: String) {
        for (i in 0 until model.size()) {
            if (model.get(i).anchorId == anchorId) { list.selectedIndex = i; return }
        }
        for (i in 0 until model.size()) {
            if (anchorId in model.get(i).memberIds) { list.selectedIndex = i; return }
        }
    }

    /**
     * What a collapsed row says when you hover it.
     *
     * The row itself has no space for a time range, and — because it sits at its **first** member's
     * position while showing the latest occurrence's content — no way to state one honestly. This is
     * also the only place the filter-relativity can be said out loud: the pass runs over the
     * filtered list, so `×30` means thirty rows *matching the current filter*, not thirty calls the
     * app necessarily made.
     */
    private fun groupTooltip(group: RowCollapse.Row.Group): String {
        val facts = group.facts
        val label = KindPresenter.rowLabel(group.lead)
        val clock = java.text.SimpleDateFormat("HH:mm:ss")
        val span = facts.latestAtMillis.takeIf { it > 0 }
            ?.let { "latest ${clock.format(java.util.Date(it))}" }
            .orEmpty()
        return when (group.groupKind) {
            RowCollapse.GroupKind.NET_POLL -> buildString {
                append("<html><b>${facts.count} calls</b> matching the current filter<br/>")
                append("$label<br/>")
                facts.medianDurationMillis?.let { append("median ${it}ms") }
                if (span.isNotEmpty()) { if (facts.medianDurationMillis != null) append(" · "); append(span) }
                append("<br/>Double-click to expand</html>")
            }
            RowCollapse.GroupKind.DB_TXN -> buildString {
                append("<html><b>Transaction</b> — ${facts.count} ceremony rows folded<br/>")
                append("${facts.statements} wrapped statement(s), still listed individually<br/>")
                append("Double-click to expand</html>")
            }
        }
    }

    /**
     * Whether one event survives the whole filter — the structured [FilterState] plus the two
     * narrowings that need the rest of the capture to decide (duplicate membership, and a
     * correlation value that can hide in a body no row shows).
     *
     * One predicate, used by the list, by the "what would this filter show" counts behind the
     * empty state's loosener, so a button can't offer rows the list would then reject.
     */
    private fun passes(event: LogEvent, state: FilterState, grouping: Grouping?): Boolean =
        state.matches(event) &&
            (!state.duplicatesOnly || duplicateMarks.containsKey(event.id)) &&
            (grouping == null || Correlation.containsValue(correlation.textOf(event), grouping.value))

    /**
     * Picks which of the list half's two cards is showing.
     *
     * The setup guide (the list's own empty text) is right for exactly one situation — nothing has
     * been captured — and was wrong for the far more common one, where 218 events exist and a
     * filter is hiding all of them. That case gets its own state, and the counting it needs runs
     * only here: one re-filter per active narrowing, in the branch where the list is already empty.
     */
    private fun showListState(
        all: List<LogEvent>,
        filtered: List<LogEvent>,
        state: FilterState,
        grouping: Grouping?,
        hiddenDb: Boolean,
    ) {
        if (all.isEmpty() || filtered.isNotEmpty()) {
            listCards.show(listPane, "list")
            return
        }
        filteredToNothing.show(
            FilterPresentation.emptyState(
                total = all.size,
                kinds = all.groupingBy { it.kind }.eachCount(),
                state = state,
                correlationLabel = grouping?.shortLabel,
                wouldMatchHiddenDb = hiddenDb,
                relaxations = relaxations(all, state, grouping),
            )
        )
        listCards.show(listPane, "empty")
    }

    /**
     * How many rows each active filter is costing, measured rather than guessed: the filter is run
     * again with that one narrowing dropped, and the count is what the loosener would show.
     */
    private fun relaxations(
        all: List<LogEvent>,
        state: FilterState,
        grouping: Grouping?,
    ): List<FilterPresentation.Relaxation> = buildList {
        val label = grouping?.shortLabel
        fun candidate(id: FilterPresentation.FilterId, relaxed: FilterState, g: Grouping?) {
            add(
                FilterPresentation.Relaxation(
                    id, FilterPresentation.looseningLabel(id, label),
                    all.count { passes(it, relaxed, g) },
                )
            )
        }
        if (state.statusClasses.isNotEmpty()) {
            candidate(FilterPresentation.FilterId.STATUS, state.copy(statusClasses = emptySet()), grouping)
        }
        if (state.methods.isNotEmpty()) {
            candidate(FilterPresentation.FilterId.METHOD, state.copy(methods = emptySet()), grouping)
        }
        if (grouping != null) candidate(FilterPresentation.FilterId.CORRELATION, state, null)
        if (state.hideNoise) {
            candidate(FilterPresentation.FilterId.HIDE_NOISE, state.copy(hideNoise = false), grouping)
        }
        if (state.duplicatesOnly) {
            candidate(FilterPresentation.FilterId.DUPES, state.copy(duplicatesOnly = false), grouping)
        }
        if (state.urlQuery.isNotBlank()) {
            candidate(FilterPresentation.FilterId.SEARCH, state.copy(urlQuery = ""), grouping)
        }
        if (state.types.isNotEmpty()) {
            candidate(FilterPresentation.FilterId.TYPES, state.copy(types = emptySet()), grouping)
        }
        // The DB opt-in is the one narrowing with no chip switched on, so without this the empty
        // state can neither name it nor offer to lift it: a capture of nothing but db events, with
        // no filter active at all, was told "several filters are narrowing at once" and handed a
        // "Clear filters" button that changed nothing. Offered only when granting db actually
        // brings rows back, so a genuinely different cause (a status filter, which hides every
        // non-HTTP kind) keeps the blame.
        if (EventType.DB !in state.types && all.any { it is LogEvent.Db }) {
            val withDb = state.copy(types = state.types + EventType.DB)
            val wouldShow = all.count { passes(it, withDb, grouping) }
            if (wouldShow > 0) {
                val id = FilterPresentation.FilterId.DB_OPT_IN
                add(FilterPresentation.Relaxation(id, FilterPresentation.looseningLabel(id, label), wouldShow))
            }
        }
    }

    /**
     * Shows one flow as a waterfall. The event list is snapshotted here, on the EDT, and handed
     * over as an immutable list — [TraceWaterfallPanel] never reads the store itself.
     */
    private fun showWaterfall(grouping: Grouping, alternatives: List<Grouping> = emptyList()) {
        waterfallGrouping = grouping
        // The tabs belong to the row this was opened from, so an entry point that doesn't know
        // them (a detail card's trace chip) shows none rather than the last row's.
        waterfallAlternatives = alternatives
        // The row this was opened from stays selected in the list, and its lane says so.
        waterfall.show(grouping, membersOf(grouping, store.snapshot()), alternatives, list.selectedValue?.lead?.id)
        detailCards.show(detailPane, "waterfall")
    }

    /**
     * The events one grouping holds.
     *
     * A trace groups by equality, as it always has. A key or a pasted value groups by
     * [Correlation.group] — matched on the **value**, delimiter-bounded, over the cached
     * haystacks, which is what reaches an event in another trace or with no trace at all. The
     * key's own length floor and short-value opt-in travel with it when it's configured.
     */
    private fun membersOf(grouping: Grouping, all: List<LogEvent>): List<LogEvent> {
        if (grouping.isTrace) return all.filter { it.traceId == grouping.value }
        val configured = grouping.key?.let { name ->
            correlation.keys().firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
        return if (configured != null) {
            Correlation.group(all, configured, grouping.value, correlation::textOf)
        } else {
            Correlation.group(all, grouping.key, grouping.value, textOf = correlation::textOf)
        }
    }

    private fun traceGrouping(traceId: String) = Grouping(Grouping.Kind.TRACE, null, traceId)

    /** Every grouping a row offers, best first: configured keys in order, then its trace. */
    private fun groupingsFor(event: LogEvent): List<Grouping> =
        Groupings.forEvent(correlation.matchable(event), event.traceId)

    /** The hover glyph's one click: the row's best grouping — a key if it has one, else its trace. */
    private fun openBestGrouping(event: LogEvent) {
        val groupings = groupingsFor(event)
        val best = groupings.firstOrNull() ?: return
        showWaterfall(best, groupings)
    }

    /** Narrowing the timeline to a grouping: a removable chip for a key, the search box for a trace. */
    private fun filterByGrouping(grouping: Grouping) {
        if (grouping.isTrace) {
            filterBar.setCorrelationFilter(null)
            filterBar.setQuery(grouping.value)
        } else {
            filterBar.setCorrelationFilter(grouping)
        }
    }

    // ---- correlation keys ---------------------------------------------------------------------

    /**
     * Opens the keys dialog, seeded from the capture.
     *
     * [Correlation.suggest] walks every event's payload *and* its searchable text, which is a
     * one-shot cost sized for opening a dialog — but not one to pay on the EDT with a full
     * buffer, so it runs on a pooled thread and the dialog opens when it's done.
     */
    private fun editCorrelationKeys() {
        val events = store.snapshot()
        val before = correlation.keys()
        val firstTime = !CorrelationSettings.configured(projectStore)
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val suggestions: List<Suggestion> = runCatching { Correlation.suggest(events) }.getOrDefault(emptyList())
            SwingUtilities.invokeLater {
                val dialog = CorrelationKeysDialog(project, before, suggestions, seedSuggestions = firstTime)
                if (dialog.showAndGet()) applyCorrelationKeys(dialog.result())
            }
        }
    }

    /**
     * Persists a new vocabulary and re-extracts against it.
     *
     * The re-extraction is the part that matters: changing keys invalidates every cached
     * extraction, and until they're rebuilt the rows can only offer their traces. Rebuilding runs
     * off the EDT (it's a payload scan per event) and the list is refreshed once it's done.
     */
    private fun applyCorrelationKeys(keys: List<CorrelationKey>) {
        CorrelationSettings.setKeys(projectStore, keys)
        correlation.setKeys(keys)
        // A grouping by a key that no longer exists would keep filtering by a value nobody can
        // see the reason for, so it's dropped with the key.
        filterBar.correlationFilter()?.let { active ->
            if (active.kind == Grouping.Kind.KEY && keys.none { it.name.equals(active.key, true) && it.enabled }) {
                filterBar.setCorrelationFilter(null)
            }
        }
        val events = store.snapshot()
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            events.forEach { correlation.warmValues(it) }
            SwingUtilities.invokeLater { refreshList(); list.repaint() }
        }
    }

    /**
     * "Find by value…" — the entry point that needs no row at all: paste an id from a ticket or a
     * backend log and open everything carrying it.
     */
    private fun findByValue() {
        val events = store.snapshot()
        val dialog = FindByValueDialog(
            project,
            resolve = { grouping -> membersOf(grouping, events).size },
            keyLabelFor = { value -> keyLabelFor(events, value) },
            allowsShortValues = { key ->
                correlation.keys().any { it.name.equals(key, true) && it.enabled && it.allowShortValues }
            },
        )
        if (dialog.showAndGet()) dialog.grouping()?.let { showWaterfall(it) }
    }

    /**
     * The configured key holding [value], for labelling a bare paste.
     *
     * Cached extractions answer first — newest-first, as [Correlation.keyLabelFor] does — and only
     * the events the cache doesn't know are handed to it, so a keystroke can't turn into a full
     * re-extraction of the capture.
     */
    private fun keyLabelFor(events: List<LogEvent>, value: String): String? {
        val uncached = ArrayList<LogEvent>()
        for (index in events.indices.reversed()) {
            val cached = correlation.cachedValues(events[index])
            if (cached == null) { uncached.add(events[index]); continue }
            cached.firstOrNull { it.value.equals(value, ignoreCase = true) }?.let { return it.key }
        }
        if (uncached.isEmpty()) return null
        return Correlation.keyLabelFor(uncached.asReversed(), correlation.keys(), value)
    }

    /**
     * Selects an event's row from somewhere other than the list — today, a waterfall lane. A row
     * the current filter hides can't be selected, and saying so beats a click that does nothing.
     *
     * A lane may point at an event that is currently *folded* into a collapsed row. Selecting the
     * group and silently showing a different occurrence would be the collapse lying, and the old
     * "hidden by the current filter" toast would be plainly false — the row is visible, just folded.
     * So the group is expanded first, and then the member's own row is selected.
     */
    private fun selectEventInList(id: String) {
        val holder = memberRows[id]
        if (holder is RowCollapse.Row.Group && expandedGroups.add(holder.key)) {
            refreshList()
        }
        val model = list.model
        for (i in 0 until model.size) {
            val row = model.getElementAt(i)
            if (row.lead.id != id && id !in row.memberIds) continue
            list.selectedIndex = i
            list.ensureIndexIsVisible(i)
            // Selecting an already-selected row fires no event, so the detail is shown explicitly.
            // The lane click named one specific call, so if the row it landed on is a collapsed run
            // the card opens on *that* occurrence — not on the run's latest.
            showDetailFor(row, pin = id)
            return
        }
        Toast.show(list, "That row is hidden by the current filter")
    }

    /**
     * Opens or closes a collapsed run, then keeps its anchor in view.
     *
     * The toggle rebuilds the model rather than repainting, because row heights differ (34 vs 26)
     * and a JList caches the heights it laid out — a 34px row drawn into a 26px slot is what a
     * repaint-only version would give.
     */
    private fun toggleExpanded(group: RowCollapse.Row.Group) {
        if (!expandedGroups.add(group.key)) expandedGroups.remove(group.key)
        refreshList()
        val model = list.model
        for (i in 0 until model.size) {
            if (model.getElementAt(i).anchorId == group.anchorId) { list.ensureIndexIsVisible(i); break }
        }
    }

    /**
     * The group a row would belong to if nothing were expanded, so an already-expanded run can be
     * folded back from any of its members. Costs nothing while nothing is expanded.
     */
    private fun naturalGroupFor(row: RowCollapse.Row): RowCollapse.Row.Group? {
        if (expandedGroups.isEmpty()) return null
        val natural = RowCollapse.memberIndex(
            RowCollapse.collapse(lastFiltered, emptySet(), duplicateMarks, store::sessionOf)
        )
        return natural[row.lead.id] as? RowCollapse.Row.Group
    }

    /**
     * The state sequence a worker was **observed** to pass through.
     *
     * Offered only when there is a sequence — see [WorkerLifecycle.hasTransitions]. A row replayed
     * from WorkManager's persisted store on attach is a single terminal sighting, and a menu item
     * that opens an empty view is worse than no menu item.
     */
    private fun showStateTransitions(event: LogEvent.Worker) {
        val transitions = workerLifecycle.transitions(workerLifecycle.keyOf(event))
        if (transitions.size < 2) {
            Toast.show(list, "No state transitions were observed for this worker")
            return
        }
        val clock = java.text.SimpleDateFormat("HH:mm:ss.SSS")
        val lines = transitions.joinToString("<br/>") { t ->
            val at = t.atMillis.takeIf { it > 0 } ?: t.hostMillis
            val attempt = if (t.runAttempt > 1) "  ·  attempt ${t.runAttempt}" else ""
            "<code>${clock.format(java.util.Date(at))}</code>&nbsp;&nbsp;${t.state}$attempt"
        }
        val html = "<html><body style='width:340px'><b>${event.work.worker}</b><br/><br/>$lines<br/><br/>" +
            "<i>These are the transitions LogPose observed. WorkManager can move between two states " +
            "in the gap between observer emissions, so this is a record of sightings, not its full " +
            "history.</i></body></html>"
        val body = JBLabel(html).apply {
            border = JBUI.Borders.empty(10, 12)
            isOpaque = true
            background = Theme.bg1
            foreground = Theme.text
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(body, null)
            .setTitle("Observed state transitions")
            .setResizable(false)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .showInCenterOf(list)
    }

    /**
     * Shows the detail for a **row**, which is not always the detail for one event.
     *
     * A collapsed polling run stands for N calls, and the card renders one of them with an
     * `occurrence n / N` stepper. Which one: the [pin] when the caller names a member (a waterfall
     * lane click knows exactly which call it meant), else the occurrence the user last stepped to
     * on this same run, else — for a run just selected — the latest, matching what the row shows.
     *
     * Only NET_POLL groups get this. A folded transaction is *ceremony* around statements that are
     * still listed individually, so its rows are not interchangeable occurrences of one call and a
     * stepper over them would be inventing a relationship the wire doesn't describe.
     */
    private fun showDetailFor(row: RowCollapse.Row?, pin: String? = null) {
        val group = (row as? RowCollapse.Row.Group)?.takeIf { it.groupKind == RowCollapse.GroupKind.NET_POLL }
        val occurrences = group?.let { occurrencesOf(it) }?.takeIf { it.size > 1 }
        if (group == null || occurrences == null) {
            showDetail(row?.lead)
            return
        }
        when {
            pin != null -> { shownGroupKey = group.key; pinnedOccurrenceId = pin }
            group.key != shownGroupKey -> { shownGroupKey = group.key; pinnedOccurrenceId = null }
        }
        // A pinned call the run no longer contains (evicted by the store's cap, or filtered out)
        // falls back to the latest rather than to nothing.
        val index = pinnedOccurrenceId
            ?.let { id -> occurrences.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: occurrences.lastIndex
        // The row's lead is the run's latest call; recording it here is what stops refreshList from
        // re-rendering this card on every tick.
        lastShown = group.lead
        waterfallGrouping = null
        waterfallAlternatives = emptyList()
        detail.showOccurrences(
            occurrences.map { TransactionDetailView.Occurrence(it.tx, it.envelope) },
            index,
        ) { tx -> duplicateMarks[tx.id] }
        detailCards.show(detailPane, "http")
    }

    /**
     * The calls a collapsed run stands for, in arrival order.
     *
     * Resolved from the filtered snapshot the rows were folded from — every member is in it by
     * construction — so the detail card never reaches into the store from the EDT, and a member
     * that has since been evicted simply isn't offered.
     */
    private fun occurrencesOf(group: RowCollapse.Row.Group): List<LogEvent.Http> {
        val wanted = group.memberIds.toHashSet()
        val byId = HashMap<String, LogEvent.Http>(wanted.size)
        for (event in lastFiltered) if (event is LogEvent.Http && event.id in wanted) byId[event.id] = event
        return group.memberIds.mapNotNull { byId[it] }
    }

    /**
     * The event the detail card is showing for [row] — its lead, unless the user has stepped a
     * collapsed run back to an earlier occurrence. The row's context menu acts on this, so
     * `Copy as cURL` copies the call on screen rather than a different one from the same run.
     */
    private fun shownEvent(row: RowCollapse.Row): LogEvent {
        val group = row as? RowCollapse.Row.Group ?: return row.lead
        val pin = pinnedOccurrenceId
            ?.takeIf { group.key == shownGroupKey && it in group.memberIds }
            ?: return row.lead
        return occurrencesOf(group).firstOrNull { it.id == pin } ?: row.lead
    }

    private fun showDetail(event: LogEvent?) {
        lastShown = event
        // Anything that isn't a collapsed run has no occurrence to remember.
        shownGroupKey = null
        pinnedOccurrenceId = null
        // Any explicit selection leaves the waterfall — the card follows the row again.
        waterfallGrouping = null
        waterfallAlternatives = emptyList()
        when (event) {
            is LogEvent.Http -> {
                detail.show(event.tx, duplicateMarks[event.id], event.envelope)
                detailCards.show(detailPane, "http")
            }
            is LogEvent.Fcm -> {
                // The envelope carries the trace/parent/timestamp the FCM payload doesn't, so the
                // push detail gets the same chips a structured row has.
                fcmDetail.show(event.msg, event.envelope)
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
        // Two things animate in the list: an in-flight request's spinner and count-up, and a
        // running worker's pulsing dot and count-up. A collapsed run can't be either — a member
        // joins only once it has completed 2xx — so only a row's lead needs testing.
        val anyLive = (0 until model.size).any { i ->
            when (val event = model.getElementAt(i).lead) {
                is LogEvent.Http -> event.tx.isPending()
                is LogEvent.Worker -> event.work.state.equals(WorkerEvent.STATE_RUNNING, ignoreCase = true)
                else -> false
            }
        }
        if (anyLive) list.repaint()
        val sel = list.selectedValue?.lead
        if (sel is LogEvent.Http && sel.tx.isPending()) detail.tick(store.elapsedMillis(sel.id), renderer.spinnerFrame)
        // An open span in the waterfall grows towards "now", so the card animates on the same
        // timer as the in-flight rows rather than owning a second one.
        if (waterfallGrouping != null) waterfall.tick(renderer.spinnerFrame)
    }

    override fun dispose() {
        liveTimer.stop()
        reader.stop()
        mocksController.onCaptureStopped()
        pushController.reset()
        statusDot.dispose()
        waterfall.dispose()
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

    // ---- Push injection ---------------------------------------------------------------------

    /** Sends a captured push back to the app, verbatim but for a fresh id, send time and trace. */
    private fun replayPush(msg: FcmMessage) {
        if (pushController.readyToInject() == null) return
        sendPush(PushReplay.toMessage(msg, PushReplay.newId(), System.currentTimeMillis()))
    }

    /** Opens the composer, optionally pre-filled from a captured push. */
    private fun composePush(seed: PushMessage = PushMessage()) {
        if (pushController.readyToInject() == null) return
        val dialog = ComposePushDialog(project, seed)
        if (dialog.showAndGet()) sendPush(dialog.result())
    }

    /**
     * A captured push as composer input: every field a human might edit, and neither of the two
     * the send stamps for itself (a fresh message id and "now").
     */
    private fun seededFrom(msg: FcmMessage): PushMessage =
        PushReplay.toMessage(msg, messageId = "", sentTimeMillis = 0)
            .copy(messageId = null, sentTimeMillis = null)

    private fun sendPush(message: PushMessage) {
        val stamped = message.copy(
            messageId = message.messageId?.takeIf { it.isNotBlank() } ?: PushReplay.newId(),
            sentTimeMillis = System.currentTimeMillis(),
        )
        pushController.injectPush(PushReplay.inject(stamped)) { outcome ->
            // The ack arrives on the reader (or scheduler) thread; everything visible happens on
            // the EDT. Failures already went out as notifications from the controller.
            if (outcome != null && outcome.reachedApp) {
                SwingUtilities.invokeLater { Toast.show(list, "Push delivered (${outcome.delivered})") }
            }
        }
    }

    // ---- Scenarios --------------------------------------------------------------------------

    /** Re-reads `.logpose/scenarios` off the EDT and repaints the strip with what it found. */
    private fun reloadScenarios() {
        val store = scenarioStore ?: return
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val infos = runCatching {
                store.list().map { ScenarioInfo(it.name, it.rules.size, it.note) }
            }.getOrDefault(emptyList())
            SwingUtilities.invokeLater {
                scenarioInfos = infos
                refreshMocksBar()
            }
        }
    }

    private fun showScenariosPopup(near: Component?) {
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "Scenarios", scenariosGroup(), DataContext.EMPTY_CONTEXT,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
        )
        if (near != null && near.isShowing) popup.showUnderneathOf(near) else popup.showInFocusCenter()
    }

    private fun scenariosGroup(): ActionGroup = DefaultActionGroup().apply {
        if (scenarioStore == null) {
            add(act("Scenarios need a project on disk", null) {})
            return@apply
        }
        add(act("Save current rules as…", AllIcons.General.Add) { saveRulesAsScenario() })
        add(act("Snapshot session into scenario…", AllIcons.Actions.Download) { snapshotSessionAsScenario() })
        if (scenarioInfos.isNotEmpty()) addSeparator("Saved  ·  ${ScenarioStore.REL_DIR}")
        scenarioInfos.forEach { info ->
            add(DefaultActionGroup("${info.name}   (${info.rules} rules)", true).apply {
                templatePresentation.description = info.note
                add(act("Load (merge into active rules)", AllIcons.Actions.Execute) { loadScenario(info.name, replace = false) })
                add(act("Load (replace active rules)", AllIcons.Actions.Refresh) { loadScenario(info.name, replace = true) })
                addSeparator()
                add(act("Delete", AllIcons.General.Remove) { deleteScenario(info.name) })
            })
        }
    }

    private fun saveRulesAsScenario() {
        val store = scenarioStore ?: return
        val rules = mocksController.rules()
        if (rules.isEmpty()) {
            LogPoseNotifications.warn(
                project, "LogPose: no rules to save",
                "There are no mock rules yet. Right-click a captured request → \"Mock this " +
                    "endpoint…\", or snapshot the session into a scenario instead.",
            )
            return
        }
        val dialog = SaveScenarioDialog(
            project, "Save mock rules as scenario",
            defaultName = suggestScenarioName(),
            existingNames = scenarioInfos.map { it.name }.toSet(),
        )
        if (dialog.showAndGet()) writeScenario(store, dialog.scenarioName(), dialog.scenarioNote(), rules, null)
    }

    private fun snapshotSessionAsScenario() {
        val scenarios = scenarioStore ?: return
        val events = store.snapshot()
        val dialog = SaveScenarioDialog(
            project, "Snapshot session into scenario",
            defaultName = suggestScenarioName(),
            // Live preview: the 2xx filter changes what gets written, and what a snapshot
            // *refuses* to write is as important as what it does (FR-C2).
            preview = { successOnly -> ScenarioSnapshot.fromEvents(events, successOnly).summary() },
            existingNames = scenarioInfos.map { it.name }.toSet(),
        )
        if (!dialog.showAndGet()) return
        val result = ScenarioSnapshot.fromEvents(events, dialog.successOnly())
        if (result.rules.isEmpty()) {
            LogPoseNotifications.warn(
                project, "LogPose: nothing to snapshot",
                "No completed HTTP responses in the capture to build rules from. " + result.summary(),
            )
            return
        }
        writeScenario(scenarios, dialog.scenarioName(), dialog.scenarioNote(), result.rules, result.summary())
    }

    private fun writeScenario(
        store: ScenarioStore,
        name: String,
        note: String?,
        rules: List<io.github.siddharthjaswal.logpose.model.MockRule>,
        detail: String?,
    ) {
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val file = runCatching {
                store.save(ScenarioStore.Scenario(name, System.currentTimeMillis(), note, rules))
            }.getOrNull()
            SwingUtilities.invokeLater {
                if (file == null) {
                    LogPoseNotifications.warn(
                        project, "LogPose: scenario not saved",
                        "Could not write ${ScenarioStore.REL_DIR}/$name.json.",
                    )
                } else {
                    LogPoseNotifications.info(
                        project, "LogPose: scenario saved",
                        "'$name' · ${rules.size} rule(s) → ${ScenarioStore.REL_DIR}/$name.json" +
                            (detail?.let { "\n$it" } ?: "") +
                            "\n\nScenario files contain captured response bodies — review before committing.",
                    )
                }
                reloadScenarios()
            }
        }
    }

    private fun loadScenario(name: String, replace: Boolean) {
        val store = scenarioStore ?: return
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val scenario = runCatching { store.load(name) }.getOrNull()
            SwingUtilities.invokeLater {
                if (scenario == null) {
                    LogPoseNotifications.warn(
                        project, "LogPose: scenario not loaded",
                        "Could not read ${ScenarioStore.REL_DIR}/$name.json.",
                    )
                    reloadScenarios()
                    return@invokeLater
                }
                // One action, one revision, one push — and the strip then reports whether the
                // device actually acknowledged it (FR-C1); "loaded" is not the same as "live".
                if (replace) mocksController.replaceAll(scenario.rules) else mocksController.merge(scenario.rules)
                refreshMocksBar()
                val live = mocksController.deviceState().capturing
                LogPoseNotifications.info(
                    project, "LogPose: scenario loaded",
                    "'$name' · ${scenario.rules.size} rule(s) " +
                        (if (replace) "replaced the active rules." else "merged into the active rules.") +
                        if (live) " Pushing to the device — the MOCKS strip turns green once it's acknowledged."
                        else " Start capture to push them to the device.",
                )
            }
        }
    }

    private fun deleteScenario(name: String) {
        val store = scenarioStore ?: return
        val confirmed = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            "Delete ${ScenarioStore.REL_DIR}/$name.json? Active rules are not affected.",
            "Delete Scenario", "Delete", "Cancel", null,
        ) == com.intellij.openapi.ui.Messages.YES
        if (!confirmed) return
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { store.delete(name) }
            SwingUtilities.invokeLater { reloadScenarios() }
        }
    }

    /** A first guess at a scenario name, unique against what's already saved. */
    private fun suggestScenarioName(): String {
        val base = ScenarioStore.sanitize(project.name) ?: "scenario"
        val taken = scenarioInfos.map { it.name }.toSet()
        if (base !in taken) return base
        var i = 2
        while ("$base-$i" in taken) i++
        return "$base-$i"
    }

    /**
     * Copies the selected rows as a compact, paste-ready timeline — one `METHOD path` (or
     * `FCM channel`) per line, in list order — so the sequence of calls can be shared without
     * any of the request/response detail. `selectedValuesList` is already index-ordered.
     */
    private fun copySelectedTimeline() {
        val rows = list.selectedValuesList
        if (rows.isEmpty()) return
        // A collapsed row is expanded back into its members here. A copied timeline is an artefact
        // that leaves LogPose — pasted into a ticket, read by someone who cannot expand anything —
        // so it carries the truth (fifteen lines) and never the summary (one).
        val byId = store.snapshot().associateBy { it.id }
        val events = rows.flatMap { row -> row.memberIds.mapNotNull { byId[it] } }
        if (events.isEmpty()) return
        val text = events.joinToString("\n") { timelineLabel(it) }
        copyToClipboard(text, if (events.size == 1) "Copied 1 row" else "Copied ${events.size} rows")
    }

    /**
     * A row as one line. The name itself comes from [KindPresenter.rowLabel] — the same one the
     * waterfall's lanes use — with the kind prefixed, since a pasted timeline has no glyph to
     * carry it.
     */
    private fun timelineLabel(event: LogEvent): String = when (event) {
        is LogEvent.Http -> KindPresenter.rowLabel(event)   // already leads with the method
        is LogEvent.Fcm -> "FCM ${KindPresenter.rowLabel(event)}"
        else -> "${KindPresenter.kindLabel(event)} ${KindPresenter.rowLabel(event)}"
    }

    /** A one-off menu action (native IDE popup item), shared by the row menus and the toolbar. */
    private fun act(text: String, icon: javax.swing.Icon?, run: () -> Unit): AnAction =
        object : DumbAwareAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun actionPerformed(e: AnActionEvent) = run()
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
            val row = list.model.getElementAt(idx)
            // The buttons are tested *before* anything else this click could mean. They now paint
            // on the selected row too, and the selected row is exactly the one a waterfall click
            // would otherwise bounce back to the detail view — so hitting a button has to be the
            // whole click, not a prelude to one.
            if (clickedAction(e, idx, row)) return
            // §6 says "click expands", but a single click is already spoken for by selection, so
            // the gesture is a double-click (and a context-menu item, which the tooltip names).
            if (e.clickCount == 2 && row is RowCollapse.Row.Group) {
                toggleExpanded(row)
                return
            }
            // Clicking the row that's already selected fires no selection event, so a click meant
            // to leave the waterfall would otherwise do nothing at all.
            if (waterfallGrouping != null) showDetailFor(row)
        }

        /**
         * Runs the action button under the pointer, if the click landed on one. Returns whether it
         * did.
         *
         * A row is *armed* when it paints its buttons — the hovered one and the selected one, the
         * same predicate the renderer paints by ([TransactionListRenderer.actionsArmed]) — and each
         * button fires only where the renderer would have drawn it
         * ([TransactionListRenderer.paintsCurl] / [TransactionListRenderer.paintsFlow]) inside the
         * cell it would have drawn it in ([io.github.siddharthjaswal.logpose.ui.RowGeometry]).
         * Nothing here re-derives a coordinate, so a button can't drift away from its hit target.
         */
        private fun clickedAction(e: MouseEvent, idx: Int, row: RowCollapse.Row): Boolean {
            if (!renderer.actionsArmed(idx, list.isSelectedIndex(idx))) return false
            val bounds = list.getCellBounds(idx, idx) ?: return false
            val x = e.x - bounds.x
            // Two disjoint bands: cURL over the size cell (HTTP only, as before), the flow over the
            // duration cell (any row that has one). A muted row and a collapsed row paint neither,
            // and both predicates already say so, so their clicks are still swallowed.
            if (renderer.isInCurlZone(bounds.width, x) && renderer.paintsCurl(row)) {
                copyToClipboard(CurlBuilder.build((row.lead as LogEvent.Http).tx), "cURL copied")
                return true
            }
            if (renderer.isInFlowZone(bounds.width, x) && renderer.paintsFlow(row)) {
                openBestGrouping(row.lead)
                return true
            }
            return false
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
            } else {
                val row = list.selectedValue ?: return
                // Not the row's lead but the occurrence the card is showing: on a run the user has
                // stepped back through, "Copy as cURL" must copy the call they are looking at.
                val event = shownEvent(row)
                val rowGroup = when (event) {
                    is LogEvent.Http -> httpGroup(event)
                    is LogEvent.Fcm -> fcmGroup(event)
                    else -> structuredGroup(event)
                }
                // The key leads: `order_id 21053953` is the id a human knows, and it groups what
                // a trace structurally can't. When no configured key is on this row the menu is
                // exactly what it always was.
                withCollapseActions(row, withKeyActions(event, rowGroup))
            }
            showActionPopup(group, e)
        }

        /**
         * Prepends one `Show waterfall` / `Filter by` pair per configured key this row carries.
         *
         * A row with several keys gets several pairs, in the order the keys were configured —
         * `order_id` before `trip_id` — because that order is the user's own statement of which
         * id they think in.
         */
        /**
         * Prepends `Expand …` / `Collapse …` when the row is (or belongs to) a collapsed run.
         *
         * §6 says "click expands", but single click is already selection, so the gesture is a
         * double-click — and this is the discoverable form of it, exactly as the row's painted
         * buttons also appear in the menu.
         */
        private fun withCollapseActions(row: RowCollapse.Row, rest: ActionGroup): ActionGroup {
            val group = row as? RowCollapse.Row.Group
                ?: naturalGroupFor(row)?.takeIf { it.key in expandedGroups }
                ?: return rest
            val expanded = group.key in expandedGroups
            val label = when (group.groupKind) {
                RowCollapse.GroupKind.NET_POLL ->
                    if (expanded) "Collapse ${group.facts.count} repeated calls"
                    else "Expand ${group.facts.count} occurrences"
                RowCollapse.GroupKind.DB_TXN ->
                    if (expanded) "Collapse transaction" else "Expand transaction"
            }
            return DefaultActionGroup().apply {
                add(act(label, AllIcons.Actions.Expandall) { toggleExpanded(group) })
                addSeparator()
                add(rest)
            }
        }

        private fun withKeyActions(event: LogEvent, rest: ActionGroup): ActionGroup {
            val keys = groupingsFor(event).filterNot { it.isTrace }
            if (keys.isEmpty()) return rest
            return DefaultActionGroup().apply {
                keys.forEach { grouping ->
                    add(act("Show waterfall  —  ${grouping.shortLabel}", AllIcons.Actions.ShowAsTree) {
                        showWaterfall(grouping, groupingsFor(event))
                    })
                    add(act("Filter by ${grouping.shortLabel}", AllIcons.Actions.Find) {
                        filterByGrouping(grouping)
                    })
                }
                addSeparator()
                // A non-popup child group is flattened into the same menu, so the row's own
                // actions keep their order and separators without being copied out of it.
                add(rest)
            }
        }

        /**
         * `Show in flow` — the row's `⇉` button as a menu item, so the action isn't reachable only
         * by discovering a hover target.
         *
         * Offered on **HTTP rows only**, and there only when nothing else in the menu already opens
         * the same view. Every configured key a row carries becomes a `Show waterfall — order_id …`
         * item ([withKeyActions]), and FCM and generic rows turn their trace into one
         * ([addTraceActions]) — HTTP rows do neither, so a traced HTTP call is the single place
         * where the button has no menu equivalent at all. Two items that open the same waterfall
         * would be a worse menu than one.
         */
        private fun flowAction(event: LogEvent.Http): AnAction? {
            val groupings = groupingsFor(event)
            if (groupings.isEmpty() || groupings.any { !it.isTrace }) return null
            return act("Show in flow", AllIcons.Actions.ShowAsTree) { openBestGrouping(event) }
        }

        private fun httpGroup(event: LogEvent.Http): ActionGroup {
            val tx = event.tx
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
                flowAction(event)?.let { addSeparator(); add(it) }
                addSeparator()
                add(act("Mock this endpoint…", AllIcons.Actions.Execute) { mockTransaction(tx) })
                addSeparator()
                add(act(if (muted) "Unmute  $key" else "Mute  $key", AllIcons.Actions.Suspend) { MutedEndpoints.toggle(tx); list.repaint() })
                if (MutedEndpoints.patterns().isNotEmpty()) {
                    add(act("Clear all mutes", AllIcons.Actions.GC) { MutedEndpoints.clearAll(); list.repaint() })
                }
            }
        }

        private fun fcmGroup(event: LogEvent.Fcm): ActionGroup = DefaultActionGroup().apply {
            val msg = event.msg
            // A push is where a flow starts, so the first thing offered on one is starting it
            // again — every field of the captured message, delivered back into the app.
            if (PushReplay.canReplay(msg)) {
                add(act("Re-send this push", AllIcons.Actions.Upload) { replayPush(msg) })
                add(act("Compose push…", AllIcons.Actions.Execute) { composePush(seededFrom(msg)) })
                addSeparator()
            }
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
            // FCM rows were the one kind without this: a push is exactly the row you most want to
            // pivot from to "everything that push set off".
            event.traceId?.takeIf { it.isNotBlank() }?.let { trace ->
                addSeparator()
                addTraceActions(event, trace)
            }
        }

        private fun structuredGroup(ev: LogEvent): ActionGroup = DefaultActionGroup().apply {
            // A worker is one row for its whole life (the library reuses `workId` as the envelope
            // id, and the store updates that row in place), so the sequence it passed through is
            // not otherwise reachable from the timeline. Offered only when one was actually
            // observed — see WorkerLifecycle.
            if (ev is LogEvent.Worker && workerLifecycle.hasTransitions(workerLifecycle.keyOf(ev))) {
                add(act("Show state transitions", AllIcons.Actions.ShowAsTree) { showStateTransitions(ev) })
                addSeparator()
            }
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
            ev.traceId?.takeIf { it.isNotBlank() }?.let { trace ->
                addSeparator()
                addTraceActions(ev, trace)
            }
        }

        /**
         * The two things to do with a trace, offered wherever one is available: read it as a
         * waterfall, or narrow the timeline to it. Events with no trace get neither — an entry
         * point that opens an empty view is worse than no entry point.
         *
         * Both now say *trace* out loud. They used to read `Show waterfall d107086f`, which names
         * a hash a human has never seen and can't recognize; beside a `order_id 21053953` item it
         * has to be obvious which of the two you're choosing.
         */
        private fun DefaultActionGroup.addTraceActions(event: LogEvent, trace: String) {
            val grouping = traceGrouping(trace)
            add(act("Show waterfall  —  trace $trace", AllIcons.Actions.ShowAsTree) {
                showWaterfall(grouping, groupingsFor(event))
            })
            add(act("Filter by trace $trace", AllIcons.Actions.Find) { filterByGrouping(grouping) })
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
        // Keyed on captureActive, not reader.isRunning(): the reader now attaches a beat after
        // Start (the serial resolves off the EDT first), and the toggle must not read that gap
        // as "not capturing" — a double-click would start two captures.
        override fun actionPerformed(e: AnActionEvent) {
            if (captureActive) stopCapture() else startCapture()
        }
        override fun update(e: AnActionEvent) {
            val running = captureActive
            Toggleable.setSelected(e.presentation, running)
            e.presentation.icon = if (running) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
            e.presentation.text = if (running) "Stop Capture" else "Start Capture"
        }
    }

    /**
     * Starts a flow instead of watching one: composes a synthetic push and has the device deliver
     * it in-process. Gated on the device's library version — refusing loudly beats a click that
     * silently does nothing (see [PushController.readyToInject]).
     */
    private inner class ComposePushAction : AnAction(
        "Compose Push",
        "Deliver a synthetic FCM push into the running app (needs logpose-android ≥ 1.7.0)",
        AllIcons.Actions.Upload,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = composePush()
    }

    /** Save / snapshot / load the committable rule sets under `.logpose/scenarios`. */
    private inner class ScenariosAction : AnAction(
        "Scenarios",
        "Save the current mocks, snapshot the session, or load a saved scenario",
        AllIcons.Actions.ListFiles,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            showScenariosPopup(e.inputEvent?.component)
        }
    }

    private inner class ClearAction :
        AnAction("Clear", "Clear captured transactions and the device log buffer", AllIcons.Actions.GC) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            store.clear()
            correlation.clear()
            workerLifecycle.clear()
            // Expansion is session state over rows that no longer exist.
            expandedGroups.clear()
            memberRows = emptyMap()
            lastFiltered = emptyList()
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
            val name = device.pkg ?: "device"
            val withheld = if (device.withheldRules > 0)
                " · ${device.withheldRules} rule(s) withheld (need logpose-android ≥ " +
                    "${io.github.siddharthjaswal.logpose.mock.DeviceFeature.RICH_MATCHERS.since})"
            else ""
            if (!device.helloSeen) {
                return "waiting for the app to announce itself — restart the app (or start capture " +
                    "before launching it); needs logpose-android ≥ 1.1.0. Rules won't serve yet."
            }
            // An agent asking "are my mocks live?" gets the acknowledged answer, not a guess:
            // pending and failed are reported as such rather than dressed up as synced.
            return when (device.sync.phase) {
                SyncState.Phase.FAILED ->
                    "$name · NOT synced: ${device.sync.message}. Rules may not be serving.$withheld"
                SyncState.Phase.PENDING ->
                    "$name · pushing rev ${device.sync.revision}, device has not acknowledged it yet$withheld"
                else -> "$name · synced rev ${device.syncedRevision}$withheld"
            }
        }
        override fun deviceReady() = mocksController.deviceState().helloSeen
        override fun deviceLibVersion() = mocksController.deviceLibVersion()
        override fun create(rule: io.github.siddharthjaswal.logpose.model.MockRule, baseBody: String?) =
            mocksController.addOrUpdate(rule, baseBody)
        override fun setEnabled(id: String, enabled: Boolean) = mocksController.setEnabled(id, enabled)
        override fun delete(id: String) = mocksController.remove(id)
    }

    /**
     * Push injection over MCP. Readiness is answered *without* the notification
     * [PushController.readyToInject] raises: an agent's failed call is reported in its own result,
     * and popping a notification for it would put the agent's mistakes in the developer's face.
     * Real send failures still notify, because those are the developer's to fix.
     */
    private inner class McpPush : McpTools.Push {
        override fun deviceHint(): String {
            val device = mocksController.deviceState()
            if (!device.helloSeen) return "waiting for the app to announce itself"
            return (device.pkg ?: "device") +
                (device.libVersion?.takeIf { it.isNotBlank() }?.let { " · logpose-android $it" } ?: "")
        }

        override fun notReady(): String? {
            val device = mocksController.deviceState()
            if (!device.helloSeen) {
                return "the app hasn't announced itself to this capture. The gate is the app→IDE " +
                    "handshake, not just capture: if the app was already running when capture " +
                    "started, RESTART IT (or start capture before launching it)."
            }
            if (!pushController.deviceSupportsPush()) {
                return "the device's library is too old — push injection needs logpose-android ≥ " +
                    "${DeviceFeature.PUSH_INJECTION.since}" +
                    (device.libVersion?.takeIf { it.isNotBlank() }?.let { " (it reports $it)" } ?: "") +
                    ". An older library has no receiver for the command, so the push would be " +
                    "silently dropped."
            }
            return null
        }

        override fun inject(
            inject: io.github.siddharthjaswal.logpose.model.PushInject,
            onAck: (McpTools.Push.Ack?) -> Unit,
        ) {
            // Off the caller's thread before anything touches the filesystem: this is invoked
            // from the MCP transport's IO thread, which resolving adb has no business blocking.
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                pushController.injectPush(inject) { outcome ->
                    onAck(outcome?.let { McpTools.Push.Ack(it.delivered, it.error) })
                }
            }
        }
    }

    /**
     * Correlation over MCP: the configured keys, and the cache that keeps grouping off a payload
     * scan.
     *
     * Every read is the *same* [CorrelationIndex] the tool window uses, which is the point — an
     * agent's `get_related(key='order_id')` and the row menu's `Show waterfall — order_id …` group
     * an identical set, because they read one cache and one vocabulary rather than two.
     * [offThread] is the honest part: these calls arrive on the MCP transport's IO thread, and a
     * cache miss here is a payload scan, so the tools do their work on a pooled thread.
     */
    private inner class McpCorrelations : McpTools.Correlations {
        override fun keys(): List<CorrelationKey> = correlation.keys()
        override fun textOf(event: LogEvent): String = correlation.textOf(event)
        override fun valuesOf(event: LogEvent) = correlation.valuesOf(event)
        override fun keyLabelFor(events: List<LogEvent>, value: String): String? =
            this@LogPosePanel.keyLabelFor(events, value)

        override fun offThread(work: () -> Unit) {
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread(work)
        }
    }

    /**
     * Scenario files over MCP. Every call hops to a pooled thread before touching the disk — this
     * is invoked from the MCP transport's IO thread, which must never block — and the rule
     * mutation rides the same hop, so a load reaches the device through exactly the path the
     * Scenarios menu uses (one revision, one push, sync reported honestly).
     */
    private inner class McpScenarios(private val scenarios: ScenarioStore) : McpTools.Scenarios {

        override fun list(onResult: (List<McpTools.Scenarios.Info>) -> Unit) = offEdt {
            onResult(
                runCatching {
                    scenarios.list().map {
                        McpTools.Scenarios.Info(it.name, it.rules.size, it.createdAt, it.note)
                    }
                }.getOrDefault(emptyList())
            )
        }

        override fun load(
            name: String,
            replace: Boolean,
            onResult: (McpTools.Scenarios.LoadReport) -> Unit,
        ) = offEdt {
            val scenario = runCatching { scenarios.load(name) }.getOrNull()
            if (scenario == null) {
                onResult(McpTools.Scenarios.LoadReport(name, found = false))
                return@offEdt
            }
            if (replace) mocksController.replaceAll(scenario.rules) else mocksController.merge(scenario.rules)
            val device = mocksController.deviceState()
            // Counted from the rules themselves rather than read off the last push: when capture
            // isn't running nothing was pushed, and the controller's own tally would be stale.
            val withheld = scenario.rules.count {
                it.enabled && !DeviceCapability.canPush(it, device.libVersion)
            }
            onResult(
                McpTools.Scenarios.LoadReport(
                    name = name,
                    found = true,
                    rules = scenario.rules.size,
                    replaced = replace,
                    activeRules = mocksController.activeCount(),
                    deviceHint = McpMocks().deviceHint(),
                    live = device.capturing,
                    withheld = withheld,
                )
            )
            SwingUtilities.invokeLater { refreshMocksBar() }
        }

        override fun save(
            name: String,
            note: String?,
            fromSession: Boolean,
            successOnly: Boolean,
            onResult: (McpTools.Scenarios.SaveReport) -> Unit,
        ) = offEdt {
            // Snapshot semantics are the UI's, not a second implementation: rows LogPose itself
            // served are always skipped, so an agent can't bottle the plugin's own output and
            // pass it off as what the backend said.
            val snapshot = if (fromSession) ScenarioSnapshot.fromEvents(store.snapshot(), successOnly) else null
            val rules = snapshot?.rules ?: mocksController.rules()
            if (rules.isEmpty()) {
                onResult(
                    McpTools.Scenarios.SaveReport(
                        name, error = if (fromSession)
                            "Nothing to snapshot: no completed HTTP responses in the capture to " +
                                "build rules from. ${snapshot?.summary().orEmpty()}"
                        else
                            "There are no mock rules to save. Create one with create_mock, or " +
                                "save from='session' to bottle the capture instead.",
                    )
                )
                return@offEdt
            }
            val file = runCatching {
                scenarios.save(ScenarioStore.Scenario(name, System.currentTimeMillis(), note, rules))
            }.getOrNull()
            onResult(
                if (file == null) McpTools.Scenarios.SaveReport(
                    name, error = "Could not write ${ScenarioStore.REL_DIR}/$name.json.",
                ) else McpTools.Scenarios.SaveReport(
                    name = name,
                    rules = rules.size,
                    path = "${ScenarioStore.REL_DIR}/$name.json",
                    detail = snapshot?.summary(),
                )
            )
            SwingUtilities.invokeLater { reloadScenarios() }
        }

        private fun offEdt(block: () -> Unit) {
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread(block)
        }
    }

    /**
     * The Connect Coding Agent flow: copy the one-line command that points an MCP client at this
     * project's capture, and — in the same small popup, because this is where the decision is
     * made — whether that client may read response bodies at all. The toggle is the UI for
     * [McpSessions.setExposeBodies], which `plugin.xml` has advertised since 1.6 but nothing
     * offered; it applies immediately (the handler reads the flag per request).
     */
    private inner class ConnectAgentAction : AnAction(
        "Connect Coding Agent",
        "Copy the MCP command that lets Claude Code (or any MCP client) read this capture",
        AllIcons.Actions.Lightning,
    ) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            showConnectAgentPopup(e.inputEvent?.component)
        }
    }

    private fun showConnectAgentPopup(near: Component?) {
        lateinit var popup: com.intellij.openapi.ui.popup.JBPopup

        val copy = io.github.siddharthjaswal.logpose.ui.PillButton("Copy connect command", filled = true).apply {
            addActionListener {
                val port = BuiltInServerManager.getInstance().port
                val command = "claude mcp add --transport http logpose " +
                    "http://localhost:$port${LogPoseMcpHandler.PATH} " +
                    "--header \"${LogPoseMcpHandler.TOKEN_HEADER}: $mcpToken\""
                popup.cancel()
                copyToClipboard(command, "MCP connect command copied — paste it in your terminal")
            }
        }

        // Bound directly to the persisted per-project flag; no staging, no OK button.
        lateinit var expose: io.github.siddharthjaswal.logpose.ui.ToggleSwitch
        expose = io.github.siddharthjaswal.logpose.ui.ToggleSwitch(McpSessions.exposeBodies(projectStore)) {
            McpSessions.setExposeBodies(projectStore, expose.on)
        }

        val exposeLabel = JBLabel("Expose response bodies to agents").apply {
            foreground = Theme.text
            font = JBUI.Fonts.label(12f).asBold()
            border = JBUI.Borders.emptyLeft(8)
        }
        val explanation = JBLabel(
            "<html>When off, agents still see every request, status and timing —<br/>" +
                "bodies come back as <code>payload_withheld</code> over MCP.</html>",
        ).apply {
            foreground = Theme.textMuted
            font = JBUI.Fonts.label(11f)
        }

        fun left(c: JComponent) = c.apply { alignmentX = LEFT_ALIGNMENT }
        val content = JPanel().apply {
            isOpaque = true
            background = Theme.bg1
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12)
            add(left(copy))
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(12)))
            add(left(JPanel().apply {
                isOpaque = false
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
                add(expose)
                add(exposeLabel)
            }))
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
            add(left(explanation))
        }

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .createPopup()
        if (near != null && near.isShowing) popup.showUnderneathOf(near) else popup.showInCenterOf(this)
    }
}
