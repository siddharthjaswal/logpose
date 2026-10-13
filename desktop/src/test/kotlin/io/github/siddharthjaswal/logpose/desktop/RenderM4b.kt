package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.analysis.Correlation
import io.github.siddharthjaswal.logpose.analysis.CorrelationIndex
import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.analysis.FindByValue
import io.github.siddharthjaswal.logpose.analysis.Grouping
import io.github.siddharthjaswal.logpose.desktop.advanced.MockDraft
import io.github.siddharthjaswal.logpose.desktop.advanced.MockWriter
import io.github.siddharthjaswal.logpose.desktop.advanced.PushDraft
import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.AdvancedUi
import io.github.siddharthjaswal.logpose.desktop.app.FindUi
import io.github.siddharthjaswal.logpose.desktop.app.KeysUi
import io.github.siddharthjaswal.logpose.desktop.app.MockEditorUi
import io.github.siddharthjaswal.logpose.desktop.app.Placeholder
import io.github.siddharthjaswal.logpose.desktop.app.ScenarioInfo
import io.github.siddharthjaswal.logpose.desktop.correlation.KeysDraft
import io.github.siddharthjaswal.logpose.desktop.correlation.Vocabulary
import io.github.siddharthjaswal.logpose.desktop.mcp.McpUi
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineViews
import io.github.siddharthjaswal.logpose.desktop.ui.LogPoseScreen
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenActions
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenState
import io.github.siddharthjaswal.logpose.desktop.waterfall.Flows
import io.github.siddharthjaswal.logpose.desktop.waterfall.WaterfallRequest
import io.github.siddharthjaswal.logpose.desktop.waterfall.WaterfallView
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.mcp.McpHttpTransport
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.mock.SyncState
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.MockStep
import io.github.siddharthjaswal.logpose.presentation.EventType
import io.github.siddharthjaswal.logpose.presentation.FilterState
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.settings.MutedEndpoints
import java.io.File

/**
 * M4b: the waterfall (dark + light: failed lane, open span, untimed, slowest bold, switcher), the
 * correlation keys sheet with suggestions, find-by-value with a live count, the echo row's
 * correlation chip, Advanced on (mocks strip, mock editor, compose push), the read-only mock-writer
 * explanation and the Serve MCP panel with its connect line.
 */
fun renderM4b(out: File) {
    MutedEndpoints.store = KeyValueStore.InMemory()
    val pixel = Adb.DeviceInfo("emulator-5554", "device", "sdk_gphone64_arm64")
    val ready = AdbStatus.Ready("/Users/dev/Library/Android/sdk/platform-tools/adb", downloaded = false)
    val events = Fixtures.m4bStore().snapshot()
    val snap = TimelineSnapshot(1, events)
    val everyKind = FilterState(types = EventType.entries.toSet())
    val index = CorrelationIndex().apply { setKeys(listOf(CorrelationKey("order_id"))); events.forEach(::warm) }
    val key = Grouping(Grouping.Kind.KEY, "order_id", "OR-881")
    val trace = Flows.traceGrouping("trc-flow")
    val flowEvents = events.filter { it.id.startsWith("fl-") }
    val waterfall = WaterfallView(WaterfallRequest(key, listOf(key, trace)), Flows.membersOf(key, flowEvents, index.keys(), index::textOf))
    check(waterfall.events.size == 7) { "flow fixture: ${waterfall.events.map { it.id }}" }
    val ages = mapOf("fl-eta" to 1_420L, "x1" to 1_840L, "w-up" to 7_300L)

    // A synced writer: the device acknowledged revision 3 with both enabled rules.
    val sync = SyncState().apply { onLocalRevision(3); onPush(3, 2, System.currentTimeMillis()); onAck(3, 2) }
    val device = MocksController.DeviceState("in.shadowfax.gandalf.debug", "1.7.3", true, 3, mapOf("loc" to 14, "acc" to 2), sync.snapshot(), capturing = true)
    val rules = listOf(
        MockRule("loc", "PUT", "/app/v1/rider/*/location/", status = 200, body = "{}", latencyMillis = 800),
        MockRule("acc", "POST", "/v1/orders/*/accept", matchBodyContains = "OR-881",
            responses = listOf(MockStep(status = 500), MockStep(status = 200, body = """{"status":"ACCEPTED"}"""))),
        MockRule("eta", "GET", "/v1/orders/*/eta", behavior = MockRule.BEHAVIOR_TIMEOUT, latencyMillis = 5_000, enabled = false),
    )
    val loc = Vocabulary.resolve(null, File("/Users/qa/Library/Application Support/LogPose"))
    val writerUi = AdvancedUi(
        advanced = true, serial = pixel.serial, mode = MockWriter.Mode.WRITER, rules = rules, device = device,
        scenarios = listOf(ScenarioInfo("accept-fails-once", 2, "500 then 200"), ScenarioInfo("offline-eta", 1, null)), location = loc,
    )
    val token = "3f9c2a71d0b84e6f9a5c1e27b4d8f063"
    val mcp = McpUi(serving = true, port = 63344, token = token, portNote = "Port 63343 is taken (a logpose daemon?), so this app is on 63344.", readOnly = false)

    fun state(
        selected: String? = null,
        filter: FilterState = everyKind,
        waterfallView: WaterfallView? = null,
        correlation: Grouping? = null,
        advanced: AdvancedUi = AdvancedUi(),
        mcpUi: McpUi = McpUi(),
        keys: KeysUi? = null,
        find: FindUi? = null,
    ) = ScreenState(
        ready, listOf(pixel), pixel.serial, SessionState.Running(pixel.serial, System.currentTimeMillis()), snap, selected, Placeholder.None,
        filter = filter, elapsedOf = { ages[it] },
        view = TimelineViews.build(
            events, filter, correlation = correlation,
            inGroup = { e, g -> Correlation.containsValue(index.textOf(e), g.value) }, hasKey = index::hasCachedKeyValue,
        ),
        correlation = correlation, waterfall = waterfallView, advanced = advanced, mcp = mcpUi, keys = keys, find = find,
    )

    for (dark in listOf(true, false)) {
        val t = if (dark) "dark" else "light"
        // The accept lane selected (its row too), switcher showing order_id | trace.
        shot(out, "m4b-waterfall-$t", dark) { LogPoseScreen(state(selected = "fl-accept", waterfallView = waterfall), ScreenActions()) }
    }
    // The trace tab of the same flow (no untimed db row: it carries no trace) — the switcher's other side.
    shot(out, "m4b-waterfall-trace-dark", true) {
        LogPoseScreen(state(selected = "fl-get", waterfallView = WaterfallView(WaterfallRequest(trace, listOf(key, trace)), flowEvents.filter { it.traceId == "trc-flow" })), ScreenActions())
    }
    // A row armed with ⇉ flow (selected, keyed) beside its detail card.
    shot(out, "m4b-row-flow-button-light", false) { LogPoseScreen(state(selected = "d2"), ScreenActions()) }

    // Correlation keys: a first open, seeded with this capture's suggestions — unticked, with evidence.
    val suggestions = Correlation.suggest(events)
    for (dark in listOf(true, false)) {
        shot(out, "m4b-keys-dialog-${if (dark) "dark" else "light"}", dark) {
            LogPoseScreen(state(keys = KeysUi(KeysDraft.open(emptyList(), suggestions, firstTime = true), loc.label)), ScreenActions())
        }
    }
    // Find by value with the count before commit, and the too-short refusal.
    fun preview(text: String) = FindByValue.preview(
        Correlation.parseFindQuery(text),
        { g -> Flows.membersOf(g, events, index.keys(), index::textOf).size },
        { v -> Correlation.keyLabelFor(events, index.keys(), v) },
    )
    shot(out, "m4b-find-by-value-dark", true) { LogPoseScreen(state(find = FindUi("OR-881", preview("OR-881"))), ScreenActions()) }
    shot(out, "m4b-find-too-short-light", false) { LogPoseScreen(state(find = FindUi("88", preview("88"))), ScreenActions()) }
    // The echo row's correlation chip narrowing the list.
    for (dark in listOf(true, false)) {
        shot(out, "m4b-echo-correlation-chip-${if (dark) "dark" else "light"}", dark) {
            LogPoseScreen(state(correlation = key, filter = everyKind.copy(hideNoise = true)), ScreenActions())
        }
    }
    // Advanced on, as the writer: the strip + the Serve MCP panel with the connect line.
    for (dark in listOf(true, false)) {
        shot(out, "m4b-advanced-mocks-strip-${if (dark) "dark" else "light"}", dark, height = 860) {
            LogPoseScreen(state(advanced = writerUi, mcpUi = mcp), ScreenActions())
        }
    }
    // The mock editor: Mock this endpoint… on the accept call, as a fail-once sequence with a matcher.
    val accept = events.first { it.id == "fl-accept" } as LogEvent.Http
    val draft = MockDraft.fromTransaction(accept.tx, "new1").copy(matchBody = "OR-881").withSteps(true).let {
        it.copy(steps = listOf(it.steps[0].copy(status = "500", body = """{"error":"internal"}"""), it.steps[0].copy(status = "200", body = """{"order_id":"OR-881","status":"ACCEPTED"}""")))
    }
    shot(out, "m4b-mock-editor-dark", true, height = 900) {
        LogPoseScreen(state(advanced = writerUi.copy(editor = MockEditorUi(draft, "Mock endpoint", accept.tx.response?.body?.text))), ScreenActions())
    }
    shot(out, "m4b-mock-editor-merge-light", false, height = 900) {
        val merge = MockDraft.fromTransaction(events.first { it.id == "h1" }.let { (it as LogEvent.Http).tx }, "m2").copy(mode = MockRule.MODE_PATCH, body = "{\n  \"has_more\": true\n}", latency = "1200")
        LogPoseScreen(state(advanced = writerUi.copy(editor = MockEditorUi(merge, "Mock endpoint", null))), ScreenActions())
    }
    // Compose push, seeded from the captured push.
    val f1 = events.first { it.id == "f1" } as LogEvent.Fcm
    val seed = io.github.siddharthjaswal.logpose.mock.PushReplay.toMessage(f1.msg, "", 0).copy(messageId = null, sentTimeMillis = null)
    shot(out, "m4b-compose-push-light", false) { LogPoseScreen(state(advanced = writerUi.copy(compose = PushDraft.of(seed))), ScreenActions()) }
    // Read-only: an IDE owns this phone's mocks; the app shows them and explains, with the hand-over.
    val readOnly = writerUi.copy(mode = MockWriter.Mode.READ_ONLY, device = device.copy(hits = mapOf("loc" to 3)))
    for (dark in listOf(true, false)) {
        shot(out, "m4b-mocks-read-only-${if (dark) "dark" else "light"}", dark, height = 860) {
            LogPoseScreen(state(advanced = readOnly, mcpUi = mcp.copy(readOnly = true, portNote = null, port = McpHttpTransport.DEFAULT_PORT)), ScreenActions())
        }
    }
    MutedEndpoints.store = KeyValueStore.InMemory()
}
