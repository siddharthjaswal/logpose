package io.github.siddharthjaswal.logpose.desktop.device

import io.github.siddharthjaswal.logpose.logcat.Adb

/**
 * adb's state words, translated for a QA tester (PRD §5.1): the sidebar never shows `unauthorized`
 * or `offline`, it says what to *do*.
 */
data class DeviceStatus(
    /** Short label under the device name. */
    val label: String,
    /** True when a session can start on it. */
    val ready: Boolean,
    /** Whether the label is an instruction (painted warn) rather than a plain state. */
    val actionNeeded: Boolean,
)

object DeviceStatuses {

    /** [wireless]: the transport is Wi-Fi, so "reconnect the cable" would be the wrong instruction. */
    fun of(state: String, wireless: Boolean = false): DeviceStatus = when (state.lowercase()) {
        "device" -> DeviceStatus("Ready", ready = true, actionNeeded = false)
        "unauthorized" ->
            if (wireless) DeviceStatus("Tap Allow on the phone, or pair it again", ready = false, actionNeeded = true)
            else DeviceStatus("Tap Allow on the phone's USB-debugging prompt", ready = false, actionNeeded = true)
        "offline" ->
            if (wireless) DeviceStatus("Wi-Fi connection lost — reconnecting…", ready = false, actionNeeded = false)
            else DeviceStatus("Reconnect the cable", ready = false, actionNeeded = true)
        "authorizing" -> DeviceStatus("Waiting for the phone to authorize…", ready = false, actionNeeded = false)
        "connecting" -> DeviceStatus("Connecting…", ready = false, actionNeeded = false)
        "no permissions", "no" -> DeviceStatus("macOS can't access this device — reconnect it", ready = false, actionNeeded = true)
        "recovery", "bootloader", "sideload", "rescue" ->
            DeviceStatus("In $state mode — restart the phone normally", ready = false, actionNeeded = true)
        else -> DeviceStatus("Not ready ($state)", ready = false, actionNeeded = true)
    }

    /** `Pixel 9a` from adb's `Pixel_9a`; the serial when there is no model. */
    fun displayName(device: Adb.DeviceInfo): String = device.model?.replace('_', ' ') ?: device.serial
}
