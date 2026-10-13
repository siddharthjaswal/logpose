package io.github.siddharthjaswal.logpose.desktop.device

import io.github.siddharthjaswal.logpose.desktop.wireless.RememberedDevice
import io.github.siddharthjaswal.logpose.desktop.wireless.isCableSerial
import io.github.siddharthjaswal.logpose.desktop.wireless.isWirelessSerial
import io.github.siddharthjaswal.logpose.logcat.Adb

enum class Transport { CABLE, WIFI, EMULATOR }

/** One row of the device sidebar: an adb transport, or a remembered Wi-Fi phone that isn't here. */
data class SidebarEntry(
    /** Stable row key: the adb serial, or `remembered:<id>` for an absent phone. */
    val key: String,
    /** The adb serial when the phone is attached (selectable); null for an absent remembered phone. */
    val serial: String?,
    val name: String,
    /** Small mono line under the name: serial, or the last-known address. */
    val detail: String,
    val transport: Transport,
    val status: DeviceStatus,
    /** The remembered record behind a Wi-Fi row — what *Forget* removes. */
    val remembered: RememberedDevice?,
    /** A cable phone that's ready: offer *Switch to wireless*. */
    val canSwitchToWireless: Boolean,
)

object SidebarEntries {

    /**
     * Attached transports first (in adb's order), then remembered phones that aren't attached, each
     * saying what the app is doing about it. A remembered phone whose transport is present but
     * offline is one row (the transport), never two.
     */
    fun build(
        devices: List<Adb.DeviceInfo>,
        remembered: List<RememberedDevice>,
        reconnecting: Set<String> = emptySet(),
    ): List<SidebarEntry> {
        val attached = devices.map { d ->
            val wireless = isWirelessSerial(d.serial)
            val memory = remembered.firstOrNull { it.matches(d.serial) }
            val transport = when {
                wireless -> Transport.WIFI
                d.serial.startsWith("emulator-") -> Transport.EMULATOR
                else -> Transport.CABLE
            }
            SidebarEntry(
                key = d.serial,
                serial = d.serial,
                name = d.model?.replace('_', ' ') ?: memory?.name ?: d.serial,
                detail = if (wireless) memory?.address ?: shortSerial(d.serial) else d.serial,
                transport = transport,
                status = DeviceStatuses.of(d.state, wireless),
                remembered = memory,
                canSwitchToWireless = d.ready && isCableSerial(d),
            )
        }
        val absent = remembered.filter { r -> devices.none { r.matches(it.serial) } }.map { r ->
            val trying = r.id in reconnecting
            val label = when {
                trying -> "Reconnecting…"
                r.kind == RememberedDevice.Kind.LEGACY ->
                    "Not connected. If the phone restarted, plug in the cable and switch to wireless again."
                else -> "Not on this Wi-Fi — reconnects when it's back"
            }
            SidebarEntry(
                key = "remembered:${r.id}",
                serial = null,
                name = r.name,
                detail = r.address ?: r.guid ?: r.id,
                transport = Transport.WIFI,
                status = DeviceStatus(label, ready = false, actionNeeded = false),
                remembered = r,
                canSwitchToWireless = false,
            )
        }
        return attached + absent
    }

    /** `adb-14141FDF600081-QoXtBe._adb-tls-connect._tcp` → `14141FDF600081`, which is what a tester can match to the phone. */
    fun shortSerial(serial: String): String {
        val guid = serial.substringBefore("._adb")
        val m = Regex("^adb-(.+)-[^-]+$").matchEntire(guid)
        return m?.groupValues?.get(1) ?: serial
    }
}
