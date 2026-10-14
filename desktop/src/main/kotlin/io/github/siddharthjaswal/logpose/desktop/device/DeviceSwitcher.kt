package io.github.siddharthjaswal.logpose.desktop.device

import io.github.siddharthjaswal.logpose.desktop.app.AvdRow
import io.github.siddharthjaswal.logpose.desktop.app.LaunchUi
import io.github.siddharthjaswal.logpose.desktop.emulators.LaunchStage
import io.github.siddharthjaswal.logpose.desktop.wireless.RememberedDevice
import io.github.siddharthjaswal.logpose.logcat.Adb

/** The switcher popover's three groups, in the order they are shown. */
enum class SwitcherSection(val title: String) {
    CONNECTED("Connected"),
    EMULATORS("Emulators"),
    REMEMBERED("Remembered Wi-Fi phones"),
}

/** One row of the toolbar's device switcher. Pure values: the popover, the chip and the tests read these. */
sealed interface SwitcherItem {
    val key: String
    val section: SwitcherSection
    val title: String
    val subtitle: String
    /** Lower-cased text the search field matches against (name, serial, kind words, badges). */
    val searchText: String

    /** An adb transport: a phone over USB or Wi-Fi, or a running emulator (with its AVD when known). */
    data class Connected(
        val entry: SidebarEntry,
        val avd: AvdRow?,
        val selected: Boolean,
        val capturing: Boolean,
        /** The guest-tweak summary when LogPose slimmed this emulator's Android. */
        val guest: String?,
    ) : SwitcherItem {
        override val key: String get() = entry.key
        override val section: SwitcherSection get() = SwitcherSection.CONNECTED
        override val title: String get() = avd?.avd?.displayName ?: entry.name
        override val subtitle: String get() = if (capturing) "Capturing" else entry.status.label
        val serial: String get() = entry.serial!!
        val ready: Boolean get() = entry.status.ready
        override val searchText: String
            get() = words(title, entry.name, entry.detail, entry.serial, avd?.avd?.name, transportWords(entry.transport),
                if (guest != null) "android slimmed" else null, if (avd?.slim?.slimmed == true) "slimmed" else null, entry.status.label)
    }

    /** An AVD that isn't running (or is still booting, [launch] non-null). */
    data class Avd(val row: AvdRow, val launch: LaunchUi?) : SwitcherItem {
        override val key: String get() = "avd:${row.avd.name}"
        override val section: SwitcherSection get() = SwitcherSection.EMULATORS
        override val title: String get() = row.avd.displayName
        override val subtitle: String get() = launch?.let(DeviceSwitcher::stageLabel) ?: row.avd.imageLine
        /** Starting or booting right now — the row shows progress instead of Launch buttons. */
        val booting: Boolean get() = launch != null && launch.stage !is LaunchStage.Failed && launch.stage !is LaunchStage.Ready
        val failed: LaunchStage.Failed? get() = launch?.stage as? LaunchStage.Failed
        override val searchText: String
            get() = words(title, row.avd.name, row.avd.imageLine, "emulator avd virtual",
                if (row.slim.slimmed) "slimmed" else null, if (row.avd.playStore) "play store" else null,
                if (row.avd.pageSize16k) "16 kb 16k" else null, if (row.guest != null) "android slimmed" else null)
    }

    /** A remembered Wi-Fi phone that isn't attached. */
    data class Remembered(val entry: SidebarEntry) : SwitcherItem {
        override val key: String get() = entry.key
        override val section: SwitcherSection get() = SwitcherSection.REMEMBERED
        override val title: String get() = entry.name
        override val subtitle: String get() = entry.status.label
        val device: RememberedDevice get() = entry.remembered!!
        override val searchText: String get() = words(title, entry.detail, "wi-fi wifi wireless remembered offline", entry.status.label)
    }
}

/** What ⏎ (or a click on the row body) does. */
sealed interface SwitcherAction {
    data class Select(val serial: String) : SwitcherAction
    data class LaunchLean(val avd: String) : SwitcherAction
    data class Reconnect(val id: String) : SwitcherAction
    data object None : SwitcherAction
}

object DeviceSwitcher {

    /**
     * Merges adb's transports, the AVD scan and the remembered phones into one list:
     *  - **Connected**: phones first (adb's order), then emulators (adb's order). A running emulator is
     *    named after its AVD when the scan knows which one it is.
     *  - **Emulators**: AVDs not visible to adb — one being launched first, then by name.
     *  - **Remembered**: offline remembered Wi-Fi phones, by name.
     * The order is stable across polls, so a row never jumps under the pointer.
     */
    fun build(
        devices: List<Adb.DeviceInfo>,
        remembered: List<RememberedDevice>,
        avds: List<AvdRow>,
        selectedSerial: String?,
        capturingSerial: String? = null,
        reconnecting: Set<String> = emptySet(),
        paused: Set<String> = emptySet(),
        launch: LaunchUi? = null,
        guestBySerial: Map<String, String> = emptyMap(),
    ): List<SwitcherItem> {
        val entries = SidebarEntries.build(devices, remembered, reconnecting, paused)
        val connected = entries.filter { it.serial != null }.map { e ->
            SwitcherItem.Connected(
                entry = e,
                avd = avds.firstOrNull { it.running?.serial == e.serial },
                selected = e.serial == selectedSerial,
                capturing = e.serial == capturingSerial,
                guest = guestBySerial[e.serial],
            )
        }.sortedBy { if (it.entry.transport == Transport.EMULATOR) 1 else 0 }
        val attached = devices.map { it.serial }.toSet()
        val stopped = avds.filter { r -> r.running?.serial == null || r.running.serial !in attached }
            .map { r -> SwitcherItem.Avd(r, launch?.takeIf { it.avd == r.avd.name }) }
            .sortedWith(compareBy<SwitcherItem.Avd> { if (it.launch != null) 0 else 1 }.thenBy { it.title.lowercase() })
        val absent = entries.filter { it.serial == null }.map(SwitcherItem::Remembered).sortedBy { it.title.lowercase() }
        return connected + stopped + absent
    }

    /** Every whitespace-separated term must appear somewhere in the row's [SwitcherItem.searchText]. */
    fun filter(items: List<SwitcherItem>, query: String): List<SwitcherItem> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return items
        return items.filter { item -> terms.all { it in item.searchText } }
    }

    /** The non-empty sections, in display order. */
    fun sections(items: List<SwitcherItem>): List<Pair<SwitcherSection, List<SwitcherItem>>> =
        SwitcherSection.entries.mapNotNull { s -> items.filter { it.section == s }.takeIf { it.isNotEmpty() }?.let { s to it } }

    /** Where the highlight starts: the selected device, else the first row. */
    fun initialHighlight(items: List<SwitcherItem>): String? =
        (items.firstOrNull { it is SwitcherItem.Connected && it.selected } ?: items.firstOrNull())?.key

    /**
     * ↑ / ↓: the next row in display order, wrapping at the ends. A highlight that a new search hid
     * restarts from the top (↓) or the bottom (↑).
     */
    fun move(items: List<SwitcherItem>, current: String?, delta: Int): String? {
        if (items.isEmpty()) return null
        val ordered = sections(items).flatMap { it.second }
        val i = ordered.indexOfFirst { it.key == current }
        if (i < 0) return (if (delta >= 0) ordered.first() else ordered.last()).key
        return ordered[Math.floorMod(i + delta, ordered.size)].key
    }

    /** The highlight to keep after the list changed (a poll, a keystroke): the same row if it's still there. */
    fun keep(items: List<SwitcherItem>, current: String?): String? =
        current?.takeIf { k -> items.any { it.key == k } } ?: sections(items).firstOrNull()?.second?.firstOrNull()?.key

    /**
     * ⏎: select a ready phone or emulator (not while a session runs on another one), launch a stopped
     * AVD lean, or redial a remembered phone. Anything else does nothing — the row says why.
     */
    fun primary(item: SwitcherItem, sessionRunning: Boolean, emulatorAvailable: Boolean = true): SwitcherAction = when (item) {
        is SwitcherItem.Connected -> if (item.ready && !sessionRunning) SwitcherAction.Select(item.serial) else SwitcherAction.None
        is SwitcherItem.Avd -> if (!item.booting && emulatorAvailable) SwitcherAction.LaunchLean(item.row.avd.name) else SwitcherAction.None
        is SwitcherItem.Remembered -> SwitcherAction.Reconnect(item.device.id)
    }

    /** The toolbar chip: the selected device, else the one capturing, else the first connected one. */
    fun chipItem(items: List<SwitcherItem>): SwitcherItem.Connected? {
        val connected = items.filterIsInstance<SwitcherItem.Connected>()
        return connected.firstOrNull { it.capturing } ?: connected.firstOrNull { it.selected } ?: connected.firstOrNull()
    }

    fun stageLabel(l: LaunchUi): String = when (val s = l.stage) {
        LaunchStage.Starting -> "Starting the emulator…"
        LaunchStage.WaitingForAdb -> "Waiting for adb to see ${l.serial}…"
        LaunchStage.Booting -> "Booting Android…"
        LaunchStage.Tweaking -> "Slimming Android…"
        LaunchStage.Ready -> "Ready — ${l.serial}"
        is LaunchStage.Failed -> s.message
    }

    /** How far along a launch is, by stage, for the inline bar (boot time can't be predicted, stages can). */
    fun launchProgress(l: LaunchUi): Float = when (l.stage) {
        LaunchStage.Starting -> 0.1f
        LaunchStage.WaitingForAdb -> 0.3f
        LaunchStage.Booting -> 0.55f
        LaunchStage.Tweaking -> 0.85f
        LaunchStage.Ready -> 1f
        is LaunchStage.Failed -> 1f
    }

    /** Elapsed seconds of a launch, for the inline progress. */
    fun launchSeconds(l: LaunchUi): Long = ((l.nowMillis - l.startedAtMillis).coerceAtLeast(0)) / 1000

}

private fun transportWords(t: Transport): String = when (t) {
    Transport.CABLE -> "usb cable phone"
    Transport.WIFI -> "wi-fi wifi wireless phone"
    Transport.EMULATOR -> "emulator avd virtual"
}

private fun words(vararg parts: String?): String = parts.filterNotNull().joinToString(" ").lowercase()
