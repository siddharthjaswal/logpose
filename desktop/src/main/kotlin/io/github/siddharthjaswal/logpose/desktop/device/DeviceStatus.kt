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

    fun of(state: String): DeviceStatus = when (state.lowercase()) {
        "device" -> DeviceStatus("Ready", ready = true, actionNeeded = false)
        "unauthorized" -> DeviceStatus("Tap Allow on the phone's USB-debugging prompt", ready = false, actionNeeded = true)
        "offline" -> DeviceStatus("Reconnect the cable", ready = false, actionNeeded = true)
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
