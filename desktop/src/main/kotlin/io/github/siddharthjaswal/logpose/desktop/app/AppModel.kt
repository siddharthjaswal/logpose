package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.desktop.adb.AdbLocator
import io.github.siddharthjaswal.logpose.desktop.adb.PlatformTools
import io.github.siddharthjaswal.logpose.desktop.session.CaptureSession
import io.github.siddharthjaswal.logpose.desktop.session.QuietDetector
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineFeed
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.wireless.RememberedDevices
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.siddharthjaswal.logpose.capture.Bookmark
import io.github.siddharthjaswal.logpose.capture.Capture
import io.github.siddharthjaswal.logpose.capture.CaptureManifest
import io.github.siddharthjaswal.logpose.capture.CaptureReader
import io.github.siddharthjaswal.logpose.desktop.device.ScreenCapture
import io.github.siddharthjaswal.logpose.desktop.session.SessionFiles
import io.github.siddharthjaswal.logpose.desktop.session.SessionMeta
import io.github.siddharthjaswal.logpose.desktop.session.SessionRecorder
import io.github.siddharthjaswal.logpose.desktop.share.ShareIo
import io.github.siddharthjaswal.logpose.desktop.timeline.SessionMarks
import io.github.siddharthjaswal.logpose.desktop.timeline.ShotMark
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbCommands
import java.io.File
import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.desktop.timeline.MenuItem
import io.github.siddharthjaswal.logpose.desktop.timeline.RowCells
import io.github.siddharthjaswal.logpose.desktop.timeline.RowCommand
import io.github.siddharthjaswal.logpose.desktop.timeline.RowMenu
import io.github.siddharthjaswal.logpose.desktop.timeline.Selection
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineView
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineViews
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.presentation.FilterState
import io.github.siddharthjaswal.logpose.settings.MutedEndpoints
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import io.github.siddharthjaswal.logpose.analysis.Correlation
import io.github.siddharthjaswal.logpose.analysis.FindByValue
import io.github.siddharthjaswal.logpose.analysis.FindQuery
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.desktop.correlation.KeysDraft
import io.github.siddharthjaswal.logpose.desktop.correlation.Vocabulary
import io.github.siddharthjaswal.logpose.desktop.mcp.DesktopMcp
import io.github.siddharthjaswal.logpose.desktop.timeline.MenuContext
import io.github.siddharthjaswal.logpose.desktop.waterfall.Flows
import io.github.siddharthjaswal.logpose.desktop.waterfall.WaterfallRequest
import io.github.siddharthjaswal.logpose.desktop.waterfall.WaterfallView
import io.github.siddharthjaswal.logpose.settings.CorrelationSettings

/** Where adb stands — the first thing the window decides. */
sealed interface AdbStatus {
    data object Checking : AdbStatus
    /** Nothing on this Mac; the first-run screen asks to download platform-tools. */
    data object Missing : AdbStatus
    data class Downloading(val stage: String, val progress: Float?) : AdbStatus
    data class Ready(val path: String, val downloaded: Boolean) : AdbStatus
    data class Failed(val message: String) : AdbStatus
}

/**
 * The window's state and the only place that touches adb, the network or the disk — always on
 * [Dispatchers.IO]. Composables read the flows and call the verbs; they never block.
 */
/** A capture file open in the window, read-only (M3): the plugin's *Open capture file* twin. */
data class ViewingFile(val name: String, val file: File, val events: Int, val malformed: Int, val manifest: CaptureManifest?)

/** A one-line message under the toolbar — a screenshot that failed, a file that wouldn't open. */
data class Notice(val text: String, val error: Boolean = false, val id: Long = System.nanoTime())

/** The keys sheet: the draft rows and where they will be saved. */
data class KeysUi(val draft: KeysDraft, val where: String, val loading: Boolean = false)

/** *Find by value…*: what was typed and what core says back (count before commit). */
data class FindUi(val text: String = "", val preview: FindByValue.Preview = FindByValue.Preview("", null, FindByValue.Tone.NEUTRAL))

class AppModel(
    private val scope: CoroutineScope,
    sessionsDir: File = SessionFiles.defaultDir(AdbLocator.appSupportDir()),
    sessionOverride: CaptureSession? = null,
    private val settings: KeyValueStore = RememberedDevices.forApp(AdbLocator.appSupportDir()),
    appSupport: File = AdbLocator.appSupportDir(),
) {
    /**
     * Every session is recorded to `sessions/<timestamp>-<device>.ndjson` (M3) — the store is only
     * the live view. The device's model names the file; its Android version is filled in from adb
     * once the session is under way (off the UI thread).
     */
    val session: CaptureSession = sessionOverride ?: CaptureSession(recorderFor = { serial, startedAt ->
        val model = _devices.value.firstOrNull { it.serial == serial }?.model
        SessionRecorder(
            SessionFiles(sessionsDir, SessionFiles.baseName(startedAt, model ?: serial)),
            SessionMeta(serial = serial, deviceModel = model?.replace('_', ' '), startedAtMillis = startedAt),
        )
    })

    private val _adb = MutableStateFlow<AdbStatus>(AdbStatus.Checking)
    val adb: StateFlow<AdbStatus> = _adb.asStateFlow()

    private val _devices = MutableStateFlow<List<Adb.DeviceInfo>>(emptyList())
    val devices: StateFlow<List<Adb.DeviceInfo>> = _devices.asStateFlow()

    private val _selectedSerial = MutableStateFlow<String?>(null)
    val selectedSerial: StateFlow<String?> = _selectedSerial.asStateFlow()

    private val _selectedEventId = MutableStateFlow<String?>(null)
    val selectedEventId: StateFlow<String?> = _selectedEventId.asStateFlow()

    val feed = TimelineFeed(session.store)
    val snapshots: StateFlow<TimelineSnapshot> get() = feed.snapshots
    val sessionState: StateFlow<SessionState> get() = session.state

    private var feedJob: Job? = null

    private val _marks = MutableStateFlow(SessionMarks.EMPTY)
    /** Bookmarks and screenshots of the live session (or of the opened file). */
    val marks: StateFlow<SessionMarks> = _marks.asStateFlow()

    private val _pendingBookmark = MutableStateFlow<Long?>(null)
    /** Non-null while the note prompt is open: the instant ⌘B was pressed. */
    val pendingBookmark: StateFlow<Long?> = _pendingBookmark.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _viewing = MutableStateFlow<ViewingFile?>(null)
    val viewing: StateFlow<ViewingFile?> = _viewing.asStateFlow()
    private var viewedCapture: Capture? = null

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    /** Rows ⌘-clicked into the selection — what *Share → only selected rows* exports. */
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    private val _screenshotBusy = MutableStateFlow(false)
    val screenshotBusy: StateFlow<Boolean> = _screenshotBusy.asStateFlow()

    val share = ShareController(scope, writtenBy = "LogPose for Mac ${appVersion()}")

    /** The device a mock-writer decision is about: the capturing one, else the selected one. */
    private fun currentSerial(): String? =
        (session.state.value as? SessionState.Running)?.serial ?: _selectedSerial.value.takeIf { _viewing.value == null }

    /** Advanced (PRD §5.5): mocks, scenarios, push — and the one-mock-writer-per-device rule. */
    val advanced = AdvancedController(
        scope, settings, appSupport,
        currentSerial = ::currentSerial,
        capturing = { serial -> (session.state.value as? SessionState.Running)?.serial == serial },
        say = { text, error -> say(text, error) },
    )

    /** The once-a-day *Update available* check (M5); inert for a `dev` build. */
    val updates = UpdateController(scope, settings, appVersion())

    /** Serve MCP (PRD §7). */
    val mcp = DesktopMcp(settings, session, advanced, projectName = {
        advanced.location.projectDir?.name ?: "LogPose for Mac"
    })

    init {
        // Mutes are a global view preference (the plugin keeps them application-wide too), kept in
        // the app's settings file beside the remembered devices.
        MutedEndpoints.store = settings
        session.hooks = advanced
    }

    // ---- M4b: correlation, waterfall ------------------------------------------------------------

    private val _correlationFilter = MutableStateFlow<Grouping?>(null)
    /** The correlation chip in the echo row: a key or pasted-value grouping the list is narrowed to. */
    val correlationFilter: StateFlow<Grouping?> = _correlationFilter.asStateFlow()

    /** Bumped when the vocabulary changes, so the view and the waterfall re-derive. */
    private val keysVersion = MutableStateFlow(0)

    private val _waterfallRequest = MutableStateFlow<WaterfallRequest?>(null)
    private val _waterfall = MutableStateFlow<WaterfallView?>(null)
    /** Non-null while the detail pane shows a flow; its members are an immutable snapshot. */
    val waterfall: StateFlow<WaterfallView?> = _waterfall.asStateFlow()

    private val _keys = MutableStateFlow<KeysUi?>(null)
    val keys: StateFlow<KeysUi?> = _keys.asStateFlow()

    private val _find = MutableStateFlow<FindUi?>(null)
    val find: StateFlow<FindUi?> = _find.asStateFlow()
    private var findJob: Job? = null

    private val _serveBusy = MutableStateFlow(false)
    val serveBusy: StateFlow<Boolean> = _serveBusy.asStateFlow()

    /** The correlation store for the current location — the project's shared file, opened fresh, or the app's. */
    private fun correlationStore(): KeyValueStore = Vocabulary.correlationStore(advanced.location, settings)

    /** Reads the vocabulary (off the UI thread) into the index and re-extracts what's captured. */
    fun reloadKeys() {
        scope.launch(Dispatchers.IO) {
            val keys = CorrelationSettings.keys(correlationStore())
            session.correlation.setKeys(keys)
            session.store.snapshot().forEach { session.correlation.warmValues(it) }
            keysVersion.value++
        }
    }

    private fun groupingsFor(event: LogEvent): List<Grouping> =
        Flows.groupingsFor(event, session.correlation.matchable(event))

    private fun inGroup(event: LogEvent, g: Grouping): Boolean = Correlation.containsValue(session.correlation.textOf(event), g.value)

    fun openWaterfall(request: WaterfallRequest) { _waterfallRequest.value = request }

    fun closeWaterfall() { _waterfallRequest.value = null }

    /** The row's `⇉ flow` button: the row becomes selected (as a click on it would) and its best grouping opens. */
    fun openFlow(row: RowCollapse.Row) {
        scope.launch {
            val request = withContext(Dispatchers.Default) { Flows.fromRowButton(row.lead, session.correlation.matchable(row.lead)) } ?: return@launch
            selectEvent(row.anchorId, keepWaterfall = true)
            openWaterfall(request)
        }
    }

    /** A detail card's trace chip. */
    fun openTrace(traceId: String) = openWaterfall(Flows.fromTraceChip(traceId))

    /** A lane click: selects that event's row — which, as in the plugin, is also how the card is left. */
    fun selectLane(id: String) {
        val view = _view.value
        val holder = view.memberIndex[id]
        if (holder is RowCollapse.Row.Group && holder.key !in _expanded.value) _expanded.value = _expanded.value + holder.key
        if (holder == null) { say("That row is hidden by the current filter"); return }
        selectEvent(holder.anchorId.takeIf { holder.isGroup } ?: id)
        if (holder.isGroup) pinOccurrence(id)
    }

    /** Narrowing the timeline to a grouping: a removable chip for a key, the search box for a trace. */
    fun filterBy(grouping: Grouping) {
        if (grouping.isTrace) {
            _correlationFilter.value = null
            setFilter(_filter.value.copy(urlQuery = grouping.value))
        } else {
            _correlationFilter.value = grouping
        }
    }

    fun setCorrelationFilter(grouping: Grouping?) { _correlationFilter.value = grouping }

    /** Opens the keys sheet, suggestions computed off the UI thread from the capture. */
    fun openKeys() {
        val where = advanced.location.label
        _keys.value = KeysUi(KeysDraft(emptyList(), false, emptyList()), where, loading = true)
        scope.launch {
            val draft = withContext(Dispatchers.Default) {
                val store = correlationStore()
                val suggestions = runCatching { Correlation.suggest(session.store.snapshot()) }.getOrDefault(emptyList())
                KeysDraft.open(CorrelationSettings.keys(store), suggestions, firstTime = !CorrelationSettings.configured(store))
            }
            if (_keys.value != null) _keys.value = KeysUi(draft, where)
        }
    }

    fun editKeys(draft: KeysDraft) { _keys.value = _keys.value?.copy(draft = draft) }

    fun closeKeys() { _keys.value = null }

    /** Persists the vocabulary, drops a chip whose key went away, and re-extracts off the UI thread. */
    fun saveKeys() {
        val ui = _keys.value ?: return
        if (ui.draft.problem() != null) return
        val keys = ui.draft.result()
        _keys.value = null
        _correlationFilter.value?.let { active ->
            if (active.kind == Grouping.Kind.KEY && keys.none { it.name.equals(active.key, true) && it.enabled }) _correlationFilter.value = null
        }
        scope.launch(Dispatchers.IO) {
            CorrelationSettings.setKeys(correlationStore(), keys)
            session.correlation.setKeys(keys)
            session.store.snapshot().forEach { session.correlation.warmValues(it) }
            keysVersion.value++
        }
    }

    fun openFind() { _find.value = FindUi() }

    fun closeFind() { findJob?.cancel(); _find.value = null }

    /** Debounced (180ms, the plugin's), counted off the UI thread over cached haystacks. */
    fun updateFind(text: String) {
        _find.value = _find.value?.copy(text = text) ?: return
        findJob?.cancel()
        findJob = scope.launch {
            delay(180)
            val preview = withContext(Dispatchers.Default) { findPreview(text) }
            _find.value = _find.value?.takeIf { it.text == text }?.copy(preview = preview) ?: _find.value
        }
    }

    /** core's parse + preview; the second parse honours a key that opted in to short values. */
    fun findPreview(text: String): FindByValue.Preview {
        val events = session.store.snapshot()
        val keys = session.correlation.keys()
        var query = Correlation.parseFindQuery(text)
        val shortKey = (query as? FindQuery.TooShort)?.key
        if (shortKey != null && keys.any { it.name.equals(shortKey, true) && it.enabled && it.allowShortValues }) {
            query = Correlation.parseFindQuery(text, allowShortValues = true)
        }
        return FindByValue.preview(
            query,
            matchCount = { g -> Flows.membersOf(g, events, keys, session.correlation::textOf).size },
            keyLabelFor = { v -> mcpKeyLabel(events, v) },
        )
    }

    private fun mcpKeyLabel(events: List<LogEvent>, value: String): String? {
        val uncached = ArrayList<LogEvent>()
        for (i in events.indices.reversed()) {
            val cached = session.correlation.cachedValues(events[i])
            if (cached == null) { uncached.add(events[i]); continue }
            cached.firstOrNull { it.value.equals(value, ignoreCase = true) }?.let { return it.key }
        }
        if (uncached.isEmpty()) return null
        return Correlation.keyLabelFor(uncached.asReversed(), session.correlation.keys(), value)
    }

    /** Enter / *Show waterfall*: settles a pending keystroke first, so a fast typist never opens a stale group. */
    fun commitFind() {
        val ui = _find.value ?: return
        scope.launch {
            findJob?.cancel()
            val preview = withContext(Dispatchers.Default) { findPreview(ui.text) }
            val g = preview.grouping
            if (g == null) { _find.value = ui.copy(preview = preview); return@launch }
            _find.value = null
            openWaterfall(Flows.fromFind(g))
        }
    }

    // ---- Advanced + Serve MCP -------------------------------------------------------------------

    fun setAdvanced(on: Boolean) { advanced.setAdvanced(on); mcp.refresh() }

    fun setMockWriter(serial: String, on: Boolean) { advanced.setWriter(serial, on); mcp.refresh() }

    fun setProjectDir(dir: File?) {
        advanced.setProjectDir(dir)
        // After the location settles (setProjectDir hops to IO itself), re-read the vocabulary.
        scope.launch { delay(150); reloadKeys() }
    }

    fun setServing(on: Boolean) {
        if (_serveBusy.value) return
        _serveBusy.value = true
        scope.launch {
            withContext(Dispatchers.IO) { if (on) mcp.start() else mcp.stop() }
            _serveBusy.value = false
        }
    }

    // ---- M4a: filter, folds, the derived timeline view -----------------------------------------

    private val _filter = MutableStateFlow(FilterState())
    /** The filter bar's state (without the haystack hook, which is added when the view is built). */
    val filter: StateFlow<FilterState> = _filter.asStateFlow()

    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    /** Collapsed groups the tester opened, by `RowCollapse.Row.key` (anchor-based, so stable). */
    val expanded: StateFlow<Set<String>> = _expanded.asStateFlow()

    private val muteVersion = MutableStateFlow(0)

    private val _pinnedOccurrence = MutableStateFlow<String?>(null)
    /** The occurrence of a collapsed run the tester stepped to; null follows the latest. */
    val pinnedOccurrence: StateFlow<String?> = _pinnedOccurrence.asStateFlow()

    private val _view = MutableStateFlow(TimelineView.EMPTY)
    /**
     * The filtered, folded timeline — rebuilt off the UI thread whenever the coalesced snapshot,
     * the filter, the expansion set or a mute changes. Still one rebuild per 200ms snapshot at most.
     */
    val view: StateFlow<TimelineView> = _view.asStateFlow()

    private var viewJob: Job? = null
    private var builds = 0L
    /** Where the last plain or ⌘-click landed, so shift-click knows where a range starts. */
    private var rangeAnchor: String? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launchView() {
        if (viewJob != null) return
        viewJob = scope.launch {
            combine(feed.snapshots, _filter, _expanded, muteVersion, combine(_correlationFilter, keysVersion) { c, _ -> c }) { snap, f, e, _, c -> ViewInputs(snap, f, e, c) }
                .conflate()
                .collect { (snap, f, e, c) ->
                    val built = withContext(Dispatchers.Default) {
                        // Body search reaches the CorrelationIndex haystack (bodies, not headers), for
                        // queries of 3+ characters — FilterState's own rule, through its textOf hook.
                        TimelineViews.build(
                            snap.events, f.copy(textOf = session.correlation::textOf), e, session.store::sessionOf, ++builds,
                            correlation = c, inGroup = ::inGroup, hasKey = session.correlation::hasCachedKeyValue,
                        )
                    }
                    _view.value = built
                    val pruned = TimelineViews.prune(e, built)
                    if (pruned.size != e.size) _expanded.value = pruned
                }
        }
    }

    private data class ViewInputs(val snap: TimelineSnapshot, val f: FilterState, val e: Set<String>, val c: Grouping?)

    /** The waterfall's members, re-derived off the UI thread on every snapshot, request or key change. */
    private var waterfallJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun launchWaterfall() {
        if (waterfallJob != null) return
        waterfallJob = scope.launch {
            combine(feed.snapshots, _waterfallRequest, keysVersion) { snap, r, _ -> snap to r }
                .conflate()
                .collect { (snap, request) ->
                    _waterfall.value = request?.let { r ->
                        withContext(Dispatchers.Default) {
                            WaterfallView(r, Flows.membersOf(r.grouping, snap.events, session.correlation.keys(), session.correlation::textOf))
                        }
                    }
                }
        }
    }

    fun setFilter(state: FilterState) { _filter.value = state.copy(textOf = null) }

    fun toggleExpanded(key: String) {
        _expanded.value = _expanded.value.let { if (key in it) it - key else it + key }
    }

    fun pinOccurrence(id: String) { _pinnedOccurrence.value = id }

    /** Mutes or unmutes an endpoint (a settings-file write, so off the UI thread), then re-folds. */
    fun toggleMute(tx: Transaction) {
        scope.launch {
            withContext(Dispatchers.IO) { MutedEndpoints.toggle(tx) }
            muteVersion.value++
        }
    }

    fun clearMutes() {
        scope.launch {
            withContext(Dispatchers.IO) { MutedEndpoints.clearAll() }
            muteVersion.value++
        }
    }

    /** ⌘-click on a row: its members join or leave the multi-selection. */
    fun toggleSelectedRow(row: RowCollapse.Row) {
        val next = Selection.toggle(_selectedIds.value, row)
        _selectedIds.value = next
        rangeAnchor = row.anchorId
        _selectedEventId.value = if (row.memberIds.any { it in next }) row.anchorId else null
    }

    /** Shift-click: everything from the last clicked row to this one. */
    fun selectRange(row: RowCollapse.Row) {
        _selectedIds.value = Selection.range(_view.value.rows.map { it.row }, rangeAnchor ?: _selectedEventId.value, row)
        _selectedEventId.value = row.anchorId
    }

    /** The event the detail card shows for [row]: its lead, or the occurrence the tester stepped to. */
    fun shownEvent(row: RowCollapse.Row): LogEvent {
        val group = row as? RowCollapse.Row.Group ?: return row.lead
        val pin = _pinnedOccurrence.value?.takeIf { it in group.memberIds } ?: return row.lead
        return TimelineViews.occurrencesOf(_view.value, group).firstOrNull { it.id == pin } ?: row.lead
    }

    /** The context menu for a row — or, on a multi-selection that includes it, Copy timeline. */
    fun menuFor(cells: RowCells): List<MenuItem> {
        val view = _view.value
        val selected = _selectedIds.value
        val row = cells.row
        val selectedRows = view.rows.map { it.row }.filter { r -> r.memberIds.any { it in selected } }
        if (selectedRows.size > 1 && row.memberIds.any { it in selected }) {
            return listOf(RowMenu.timelineItem(RowMenu.timelineLines(selectedRows, view.filtered.associateBy { it.id })))
        }
        val expanded = _expanded.value
        val collapse = TimelineViews.naturalGroupFor(view, row, expanded, session.store::sessionOf)?.let { it to (it.key in expanded) }
        val shown = shownEvent(row)
        return RowMenu.items(row, shown, collapse, MenuContext(groupingsFor(shown), advanced = advanced.ui.value.advanced))
    }

    fun run(command: RowCommand) {
        when (command) {
            is RowCommand.Copy -> copy(command.text, command.toast)
            is RowCommand.ToggleMute -> toggleMute(command.tx)
            RowCommand.ClearMutes -> clearMutes()
            is RowCommand.ToggleExpand -> toggleExpanded(command.groupKey)
            is RowCommand.ShowWaterfall -> openWaterfall(command.request)
            is RowCommand.FilterBy -> filterBy(command.grouping)
            is RowCommand.MockEndpoint -> advanced.openMockEditor(command.tx)
            is RowCommand.ResendPush -> advanced.resendPush(command.msg)
            is RowCommand.ComposePush -> advanced.openCompose(command.seed)
        }
    }

    /** Puts [text] on the macOS clipboard and says so under the toolbar. */
    fun copy(text: String, toast: String) {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
        }.onSuccess { say(toast) }.onFailure { say("Couldn't copy: ${it.message}", error = true) }
    }

    /** Wi-Fi pairing, *Switch to wireless*, remembered phones and their auto-reconnect (M2). */
    val wireless = WirelessController(
        scope, settings,
        onConnected = { serial -> if (session.state.value !is SessionState.Running) _selectedSerial.value = serial },
        appVersion = appVersion(),
        // The connection doctor's "Use Android Studio's adb": one adb for both tools ends the server tug-of-war.
        onUseAdb = { path ->
            AdbLocator.install(path)
            _adb.value = AdbStatus.Ready(path, downloaded = false)
        },
    )

    fun start() {
        if (feedJob == null) feedJob = feed.launchIn(scope)
        launchView()
        launchWaterfall()
        locateAdb()
        reloadKeys()
        // The mock-writer decision follows the device in focus and the session's state.
        scope.launch { combine(_selectedSerial, session.state, _viewing) { a, b, c -> Triple(a, b, c) }.collect { advanced.refresh(); mcp.refresh() } }
        if (mcp.wasServing()) setServing(true)
        updates.start()
    }

    fun locateAdb() {
        scope.launch {
            _adb.value = AdbStatus.Checking
            val found = withContext(Dispatchers.IO) { AdbLocator.locate() }
            _adb.value = if (found == null) {
                AdbStatus.Missing
            } else {
                AdbLocator.install(found)
                AdbStatus.Ready(found, downloaded = found == AdbLocator.downloadedAdb().path)
            }
        }
    }

    /** Called only after the tester consented on the first-run screen. */
    fun downloadPlatformTools() {
        scope.launch {
            _adb.value = AdbStatus.Downloading("Reading Google's SDK manifest…", null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val archive = PlatformTools.parseManifest(PlatformTools.fetchManifest())
                        ?: error("Google's manifest lists no macOS platform-tools.")
                    val appSupport = AdbLocator.appSupportDir()
                    val zip = File(appSupport, "download/${archive.fileName}")
                    try {
                        PlatformTools.download(archive, zip) { p ->
                            _adb.value = AdbStatus.Downloading("Downloading ${archive.fileName}…", p)
                        }
                        _adb.value = AdbStatus.Downloading("Verifying checksum…", null)
                        PlatformTools.verify(zip, archive)?.let { error(it) }
                        _adb.value = AdbStatus.Downloading("Installing…", null)
                        PlatformTools.install(zip, appSupport).path
                    } finally {
                        zip.delete()
                    }
                }
            }
            result.fold(
                onSuccess = { path ->
                    AdbLocator.install(path)
                    _adb.value = AdbStatus.Ready(path, downloaded = true)
                },
                onFailure = { _adb.value = AdbStatus.Failed(it.message ?: it.toString()) },
            )
        }
    }

    /**
     * Polls `adb devices -l` every [periodMillis] for as long as the calling coroutine lives — the
     * window launches it only while it is visible (not minimized), so a hidden app never spawns adb.
     */
    suspend fun pollDevices(periodMillis: Long = 2_000) {
        while (kotlin.coroutines.coroutineContext.isActive) {
            val list = withContext(Dispatchers.IO) { Adb.devices() }
            _devices.value = list
            val selected = _selectedSerial.value
            if (selected == null || list.none { it.serial == selected }) {
                // One ready phone is the common case: select it so Start is one click.
                val ready = list.filter { it.ready }
                if (ready.size == 1 && session.state.value !is SessionState.Running) _selectedSerial.value = ready.single().serial
            }
            delay(periodMillis)
        }
    }

    fun selectDevice(serial: String) {
        if (session.state.value is SessionState.Running) return
        _selectedSerial.value = serial
    }

    fun startSession() {
        val serial = _selectedSerial.value ?: return
        if (_viewing.value != null) return
        _waterfallRequest.value = null
        _selectedEventId.value = null
        _selectedIds.value = emptySet()
        _marks.value = SessionMarks.EMPTY
        _pendingBookmark.value = null
        session.start(serial)
        feed.publishIfDirty()
        // Device facts for the recording's metadata — adb, so off the UI thread.
        val rec = session.recorder ?: return
        scope.launch(Dispatchers.IO) {
            val release = AdbCommands.Real.shell(serial, "getprop ro.build.version.release")?.trim()?.lineSequence()?.firstOrNull()?.trim()
            if (!release.isNullOrBlank() && !release.contains(' ')) rec.updateMeta { it.copy(androidVersion = release) }
        }
    }

    fun stopSession() {
        commitBookmark(null)
        session.stop()
    }

    fun clearTimeline() {
        _waterfallRequest.value = null
        session.store.clear()
        _selectedEventId.value = null
        _selectedIds.value = emptySet()
    }

    fun selectEvent(id: String?, keepWaterfall: Boolean = false) {
        // Any explicit selection leaves the waterfall — the card follows the row again (the plugin's rule).
        if (!keepWaterfall) _waterfallRequest.value = null
        // A different row has no occurrence to remember; the same row keeps its pin.
        if (id != _selectedEventId.value) _pinnedOccurrence.value = null
        _selectedEventId.value = id
        // The selected row is the selection Share's "only selected rows" exports: a collapsed row
        // contributes every event it stands for.
        _selectedIds.value = if (id == null) emptySet() else (_view.value.rowFor(id)?.row?.memberIds?.toSet() ?: setOf(id))
        rangeAnchor = id
    }


    // ---- bookmarks (⌘B) ---------------------------------------------------------------------

    /** Whether ⌘B means anything right now: only a live session has a "now" to mark. */
    val canBookmark: Boolean get() = session.state.value is SessionState.Running && session.recorder != null

    /** ⌘B: the marker's time is *now*, not when the note is finished. Opens the note prompt. */
    fun beginBookmark() {
        if (!canBookmark) return
        if (_pendingBookmark.value != null) return
        _pendingBookmark.value = System.currentTimeMillis()
    }

    /** Enter saves with [note]; Esc passes null and saves the marker without one. */
    fun commitBookmark(note: String?) {
        val at = _pendingBookmark.value ?: return
        _pendingBookmark.value = null
        val b = Bookmark(at, note?.trim().orEmpty())
        session.recorder?.addBookmark(b)
        _marks.value = _marks.value.copy(bookmarks = _marks.value.bookmarks + b)
    }

    // ---- screenshots --------------------------------------------------------------------------

    fun takeScreenshot() {
        val running = session.state.value as? SessionState.Running ?: return
        val rec = session.recorder ?: return
        if (_screenshotBusy.value) return
        _screenshotBusy.value = true
        val at = System.currentTimeMillis()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                when (val o = ScreenCapture.capture(running.serial)) {
                    is ScreenCapture.Outcome.Ok -> runCatching { rec.addScreenshot(o.png, at) }.fold(
                        onSuccess = { (ref, file) -> ShotMark(ref, file = file) },
                        onFailure = { e -> ScreenCapture.Outcome.Failed("Couldn't save the screenshot: ${e.message}") },
                    )
                    is ScreenCapture.Outcome.Failed -> o
                }
            }
            _screenshotBusy.value = false
            when (result) {
                is ShotMark -> _marks.value = _marks.value.copy(shots = _marks.value.shots + result)
                is ScreenCapture.Outcome.Failed -> say(result.message, error = true)
            }
        }
    }

    fun say(text: String, error: Boolean = false) {
        val n = Notice(text, error)
        _notice.value = n
        scope.launch {
            delay(8_000)
            if (_notice.value == n) _notice.value = null
        }
    }

    fun dismissNotice() { _notice.value = null }

    // ---- opening a capture file (read-only) ---------------------------------------------------

    /**
     * Opens a `.logpose` / `.ndjson` read-only: reads it off the UI thread, stops a running session
     * (already recorded to disk, so nothing is lost), and replays the file into the timeline the way
     * a live session fills it. [Back to live][backToLive] returns to capturing.
     */
    fun openCaptureFile(file: File) {
        scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { CaptureReader.read(file) } }
            val capture = read.getOrElse { e ->
                say("Couldn't open ${file.name}: ${e.message ?: e}", error = true)
                return@launch
            }
            if (session.state.value is SessionState.Running) stopSession()
            _pendingBookmark.value = null
            _waterfallRequest.value = null
            _selectedEventId.value = null
            _selectedIds.value = emptySet()
            viewedCapture = capture
            withContext(Dispatchers.IO) {
                session.store.clear()
                session.correlation.clear()
                session.workerLifecycle.clear()
                capture.replayInto(session.store) {
                    session.correlation.warm(it)
                    if (it is LogEvent.Worker) session.workerLifecycle.note(it, System.currentTimeMillis())
                }
            }
            _marks.value = SessionMarks(
                capture.bookmarks,
                capture.manifest?.screenshots.orEmpty().map { ShotMark(it, bytes = capture.screenshots[it.file]) },
            )
            _viewing.value = ViewingFile(file.name, file, capture.events.size, capture.malformedLines, capture.manifest)
            feed.publishIfDirty()
            if (capture.malformedLines > 0) say("${capture.malformedLines} unreadable lines in ${file.name} were skipped.")
        }
    }

    fun backToLive() {
        if (_viewing.value == null) return
        _waterfallRequest.value = null
        _viewing.value = null
        viewedCapture = null
        _marks.value = SessionMarks.EMPTY
        _selectedEventId.value = null
        _selectedIds.value = emptySet()
        session.store.clear()
        session.correlation.clear()
        feed.publishIfDirty()
    }

    // ---- share ----------------------------------------------------------------------------------

    /** Whether there is anything to share: an opened file, or a recorded session. */
    val canShare: Boolean get() = _viewing.value != null || session.recorder != null

    fun openShare() {
        val viewed = viewedCapture
        val ids = _selectedIds.value
        if (viewed != null) {
            share.open(_viewing.value?.name ?: "file", ids) { ShareIo.fromCapture(viewed) }
            return
        }
        val rec = session.recorder ?: return
        share.open("this session", ids) { ShareIo.fromRecorder(rec, System.currentTimeMillis()) }
    }

    fun quietVerdict(nowMillis: Long): QuietDetector.Verdict? {
        val running = session.state.value as? SessionState.Running ?: return null
        return QuietDetector.verdict(running.startedAtMillis, nowMillis, session.firstLogPoseAtMillis)
    }

    /** Retries remembered Wi-Fi phones while the window is visible (launched beside [pollDevices]). */
    suspend fun reconnectRemembered() = wireless.reconnectLoop({ _devices.value })

    fun shutdown() {
        wireless.shutdown()
        // A writer's fail-safe clear first, while adb and the controller are still wired.
        advanced.shutdown()
        mcp.stop(remember = false)
        session.stop()
        // Give the recorder a moment to flush its last lines; it is a daemon thread otherwise.
        session.recorder?.awaitClosed(2_000)
    }

    companion object {
        fun appVersion(): String = AppModel::class.java.`package`?.implementationVersion ?: "dev"
    }
}
