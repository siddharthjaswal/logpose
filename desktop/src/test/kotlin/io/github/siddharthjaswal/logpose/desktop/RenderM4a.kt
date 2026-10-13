package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.analysis.RowCollapse
import io.github.siddharthjaswal.logpose.analysis.WorkerLifecycle
import io.github.siddharthjaswal.logpose.desktop.app.AdbStatus
import io.github.siddharthjaswal.logpose.desktop.app.Placeholder
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.desktop.session.TimelineSnapshot
import io.github.siddharthjaswal.logpose.desktop.ui.LogPoseScreen
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenActions
import io.github.siddharthjaswal.logpose.desktop.ui.ScreenState
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.presentation.EventType
import io.github.siddharthjaswal.logpose.presentation.FilterState
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.settings.MutedEndpoints
import java.io.File

/**
 * M4a: the filter bar (wide, narrow, popover, echo row, filtered-to-nothing), the folded timeline
 * with live count-ups, and every per-kind detail card — the screen composable itself, off-screen.
 */
fun renderM4a(out: File) {
    val pixel = Adb.DeviceInfo("37211FDJH00ABC", "device", "Pixel_9a")
    val ready = AdbStatus.Ready("/Users/qa/Library/Application Support/LogPose/platform-tools/adb", downloaded = true)
    val lifecycle = WorkerLifecycle()
    val events = Fixtures.m4aStore(lifecycle).snapshot()
    // The location ping is muted, as a tester would have done from its context menu.
    MutedEndpoints.store = KeyValueStore.InMemory()
    MutedEndpoints.toggle((events.first { it.id == "m1" } as LogEvent.Http).tx)

    val snap = TimelineSnapshot(1, events)
    val everyKind = FilterState(types = EventType.entries.toSet())
    val expandedB = setOf(RowCollapse.keyOf(RowCollapse.GroupKind.NET_POLL, Fixtures.POLL_EXPANDED_ANCHOR))
    // The two live rows: a call 1.84s in flight, a worker whose row is 7.3s old (6.2s of it run).
    val ages = mapOf("x1" to 1_840L, "w-up" to 7_300L)

    fun state(
        filter: FilterState = FilterState(),
        selected: String? = null,
        expanded: Set<String> = emptySet(),
        pin: String? = null,
        popover: Boolean = false,
        find: String? = null,
    ) = ScreenState(
        ready, listOf(pixel), pixel.serial, SessionState.Running(pixel.serial, Fixtures.START - 2_000), snap, selected, Placeholder.None,
        filter = filter, expanded = expanded, pinnedOccurrence = pin,
        elapsedOf = { ages[it] }, transitions = { w -> lifecycle.transitions(lifecycle.keyOf(w)) },
        filterPopover = popover, detailFind = find,
    )

    for (dark in listOf(true, false)) {
        val t = if (dark) "dark" else "light"
        // Default filter (db opt-in), nothing selected: the wide permanent row, folded polls.
        shot(out, "m4a-filter-wide-$t", dark) { LogPoseScreen(state(), ScreenActions()) }
        // Every kind on, one poll expanded, the other collapsed and selected → occurrence stepper.
        shot(out, "m4a-timeline-folds-$t", dark) {
            LogPoseScreen(state(everyKind, selected = Fixtures.POLL_COLLAPSED_ANCHOR, expanded = expandedB), ScreenActions())
        }
        // The double-submit LogPose mocked: duplicate banner + MOCK + LogPose trace chip.
        shot(out, "m4a-http-dup-mock-$t", dark) { LogPoseScreen(state(everyKind, selected = "d2"), ScreenActions()) }
        // Filtered to nothing, with the measured loosener.
        shot(out, "m4a-filtered-to-nothing-$t", dark) {
            LogPoseScreen(state(FilterState(types = setOf(EventType.CONF), statusClasses = setOf(4))), ScreenActions())
        }
    }
    // The Filters popover over an active status + hide-noise selection (badge 2, echo row).
    shot(out, "m4a-filter-popover-dark", true) {
        LogPoseScreen(state(FilterState(statusClasses = setOf(4, 5), hideNoise = true), popover = true), ScreenActions())
    }
    shot(out, "m4a-filter-popover-light", false) {
        LogPoseScreen(state(FilterState(methods = setOf("POST"), duplicatesOnly = true), popover = true), ScreenActions())
    }
    // Echo rows: the HTTP-only mode switch, and "db hidden by default" from a search.
    shot(out, "m4a-echo-http-only-light", false) {
        LogPoseScreen(state(FilterState(statusClasses = setOf(5), methods = setOf("POST"), duplicatesOnly = true)), ScreenActions())
    }
    shot(out, "m4a-echo-db-hidden-dark", true) { LogPoseScreen(state(FilterState(urlQuery = "orders")), ScreenActions()) }
    // Narrow: the detail pane leaves the timeline < 440dp, so the type chips become glyphs.
    shot(out, "m4a-filter-narrow-dark", true, width = 1150) {
        LogPoseScreen(state(FilterState(types = setOf(EventType.NET, EventType.FCM), statusClasses = setOf(2)), selected = "h1"), ScreenActions())
    }
    shot(out, "m4a-filter-narrow-light", false, width = 1150) { LogPoseScreen(state(selected = "e1"), ScreenActions()) }
    // Per-kind cards.
    shot(out, "m4a-fcm-injected-dark", true) { LogPoseScreen(state(everyKind, selected = "f1"), ScreenActions()) }
    shot(out, "m4a-fcm-injected-light", false) { LogPoseScreen(state(everyKind, selected = "f1"), ScreenActions()) }
    shot(out, "m4a-db-detail-light", false) { LogPoseScreen(state(everyKind, selected = "t3"), ScreenActions()) }
    shot(out, "m4a-worker-transitions-dark", true) { LogPoseScreen(state(everyKind, selected = "w-sync"), ScreenActions()) }
    shot(out, "m4a-worker-running-light", false) { LogPoseScreen(state(everyKind, selected = "w-up"), ScreenActions()) }
    shot(out, "m4a-config-detail-light", false) { LogPoseScreen(state(everyKind, selected = "c1"), ScreenActions()) }
    shot(out, "m4a-analytics-collapsed-dark", true) { LogPoseScreen(state(everyKind, selected = "a1"), ScreenActions()) }
    shot(out, "m4a-http-pending-dark", true) { LogPoseScreen(state(everyKind, selected = "x1"), ScreenActions()) }
    // Find-in-body: Response pane in Raw with every match highlighted and n/total.
    shot(out, "m4a-json-find-dark", true) { LogPoseScreen(state(selected = "h1", find = "order"), ScreenActions()) }
    shot(out, "m4a-json-find-light", false) { LogPoseScreen(state(selected = "h1", find = "order"), ScreenActions()) }
    // A stepped-back occurrence: pinned to call 2 of the heartbeat run.
    shot(out, "m4a-occurrence-pinned-light", false) {
        LogPoseScreen(state(everyKind, selected = Fixtures.POLL_COLLAPSED_ANCHOR, pin = "p2"), ScreenActions())
    }
    MutedEndpoints.store = KeyValueStore.InMemory()
}
