package io.github.siddharthjaswal.logpose.desktop.wireless

import io.github.siddharthjaswal.logpose.settings.FileKeyValueStore
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** A phone this Mac has connected to over Wi-Fi, kept so it reconnects on its own next time. */
@Serializable
data class RememberedDevice(
    /** TLS: adb's guid (`adb-<serial>-<id>`, the `_adb-tls-connect._tcp` instance). Legacy: `usb:<cable serial>`. */
    val id: String,
    val name: String,
    val kind: Kind,
    /** Last ip:port it was reached on. For TLS phones only a hint — their connect port changes. */
    val address: String? = null,
    /** TLS only: the mDNS instance name to look up in `adb mdns services`. */
    val guid: String? = null,
    val lastConnectedAtMillis: Long = 0,
) {
    enum class Kind {
        /** Android 11+ Wireless debugging (paired, TLS, found by mDNS). */
        TLS,
        /** `adb tcpip 5555` (Android 10 and older, or by choice); lost when the phone restarts. */
        LEGACY,
    }

    /** Whether the adb transport [serial] is this phone. */
    fun matches(serial: String): Boolean =
        (guid != null && serial.startsWith(guid)) || (address != null && serial == address)
}

/**
 * The remembered-devices list, persisted as JSON under one key of the app's settings file —
 * `~/Library/Application Support/LogPose/settings.properties` ([forApp]), through core's
 * [FileKeyValueStore] (atomic temp-file-and-rename writes). Thread-safe.
 */
class RememberedDevices(private val store: KeyValueStore) {

    @Synchronized
    fun all(): List<RememberedDevice> = decode(store.get(KEY))

    /** Adds or replaces (by [RememberedDevice.id]) — a re-pair refreshes name and address. */
    @Synchronized
    fun remember(device: RememberedDevice) {
        save(all().filterNot { it.id == device.id } + device)
    }

    @Synchronized
    fun forget(id: String) {
        save(all().filterNot { it.id == id })
    }

    @Synchronized
    fun updateAddress(id: String, address: String) {
        save(all().map { if (it.id == id) it.copy(address = address) else it })
    }

    private fun save(list: List<RememberedDevice>) {
        store.set(KEY, if (list.isEmpty()) null else encode(list))
    }

    companion object {
        const val KEY = "logpose.desktop.wireless.devices"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val serializer = ListSerializer(RememberedDevice.serializer())

        fun encode(list: List<RememberedDevice>): String = json.encodeToString(serializer, list)

        /** Lenient: a corrupt or hand-edited value reads as "nothing remembered", never a crash. */
        fun decode(raw: String?): List<RememberedDevice> =
            raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

        fun settingsFile(appSupport: File): File = File(appSupport, "settings.properties")

        fun forApp(appSupport: File): FileKeyValueStore = FileKeyValueStore(settingsFile(appSupport))
    }
}
