package io.github.siddharthjaswal.logpose.desktop.wireless

import io.github.siddharthjaswal.logpose.logcat.Adb

/**
 * When to retry each remembered phone that isn't connected: right away the first time it goes
 * missing, then backing off ×2 from [baseMillis] up to [maxMillis], so a phone that left the office
 * doesn't get dialled every few seconds all afternoon. A phone that is present resets its backoff.
 * Pure — the clock is a parameter.
 */
class ReconnectScheduler(
    private val baseMillis: Long = 5_000,
    private val maxMillis: Long = 60_000,
) {
    private data class Backoff(val failures: Int, val nextAtMillis: Long)

    private val backoff = HashMap<String, Backoff>()

    /** The remembered phones to try now: absent (not in [presentIds]) and past their backoff. */
    @Synchronized
    fun due(nowMillis: Long, remembered: List<RememberedDevice>, presentIds: Set<String>): List<RememberedDevice> {
        presentIds.forEach { backoff.remove(it) }
        backoff.keys.retainAll(remembered.map { it.id }.toSet())
        return remembered.filter { it.id !in presentIds && (backoff[it.id]?.nextAtMillis ?: Long.MIN_VALUE) <= nowMillis }
    }

    @Synchronized
    fun record(id: String, nowMillis: Long, success: Boolean) {
        if (success) {
            backoff.remove(id)
            return
        }
        val failures = (backoff[id]?.failures ?: 0) + 1
        val wait = (baseMillis shl (failures - 1).coerceAtMost(20)).coerceAtMost(maxMillis)
        backoff[id] = Backoff(failures, nowMillis + wait)
    }

    @Synchronized
    fun nextAttemptAt(id: String): Long? = backoff[id]?.nextAtMillis
}

/** One reconnect try for a remembered phone. Blocking adb calls — `Dispatchers.IO`. */
class Reconnector(private val adb: AdbCommands) {

    data class Outcome(val connected: Boolean, val address: String?, val serial: String? = null)

    /**
     * TLS phones are looked up by guid in [services] because their connect port changes whenever
     * Wireless debugging restarts; if mDNS works and the phone isn't advertising, it isn't on this
     * network and nothing is dialled (no blocking connect to a dead address). If mDNS doesn't work on
     * this Mac, the last address is tried. Legacy phones don't advertise; their fixed ip:5555 is dialled.
     * A stale `offline` transport is dropped first, or adb answers "already connected" forever.
     */
    fun attempt(
        device: RememberedDevice,
        services: List<AdbOutput.MdnsService>,
        mdnsWorks: Boolean,
        devices: List<Adb.DeviceInfo>,
    ): Outcome {
        val address = when (device.kind) {
            RememberedDevice.Kind.TLS -> services.firstOrNull { it.type == AdbOutput.ServiceType.CONNECT && it.instance == device.guid }?.address
                ?: device.address?.takeIf { !mdnsWorks }
            RememberedDevice.Kind.LEGACY -> device.address
        } ?: return Outcome(false, null)
        devices.firstOrNull { device.matches(it.serial) && !it.ready }?.let { adb.disconnect(it.serial) }
        return when (val r = adb.connect(address)) {
            is AdbOutput.ConnectResult.Connected -> Outcome(true, address, r.serial)
            is AdbOutput.ConnectResult.AlreadyConnected -> Outcome(true, address, r.serial)
            else -> Outcome(false, address)
        }
    }
}
