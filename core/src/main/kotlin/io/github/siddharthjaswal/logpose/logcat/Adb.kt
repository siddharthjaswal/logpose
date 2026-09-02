package io.github.siddharthjaswal.logpose.logcat

import java.io.File

/**
 * Shared `adb` resolution + command prefix, used by both [LogcatReader] (tailing logs) and the
 * mock pusher (broadcasting rules). Kept tiny and process-free — callers own their own
 * process lifecycle and threading (all adb work stays off the EDT).
 */
object Adb {

    /** Resolves an adb executable from the SDK env vars, common install paths, then PATH. */
    fun resolve(): String? {
        val candidates = buildList {
            System.getenv("ANDROID_HOME")?.let { add("$it/platform-tools/adb") }
            System.getenv("ANDROID_SDK_ROOT")?.let { add("$it/platform-tools/adb") }
            val home = System.getProperty("user.home")
            add("$home/Library/Android/sdk/platform-tools/adb") // macOS default
            add("$home/Android/Sdk/platform-tools/adb")         // Linux default
            add("adb")                                          // PATH fallback
        }
        return candidates.firstOrNull { it == "adb" || File(it).canExecute() }
    }

    /** `adb [-s serial]` — the prefix every device-scoped command shares. */
    fun baseCmd(adb: String, deviceSerial: String?): List<String> = buildList {
        add(adb)
        if (!deviceSerial.isNullOrBlank()) {
            add("-s"); add(deviceSerial)
        }
    }

    /** One attached device, as `adb devices -l` reports it. */
    data class DeviceInfo(
        val serial: String,
        /** adb's state word: `device`, `offline`, `unauthorized`, … */
        val state: String,
        /** The `model:` property when present (e.g. `Pixel_9a`), else null. */
        val model: String? = null,
    ) {
        /** Whether adb can actually run commands against it right now. */
        val ready: Boolean get() = state == "device"

        /** `Pixel_9a (emulator-5554)` — the label the picker shows. */
        val label: String get() = model?.let { "$it ($serial)" } ?: serial
    }

    /**
     * Runs `adb devices -l` and parses the result. Blocking process I/O — call it from a pooled
     * thread, never the EDT. Returns empty on any failure (no adb, stalled adb, no server).
     */
    fun devices(timeoutSeconds: Long = 5): List<DeviceInfo> {
        val adb = resolve() ?: return emptyList()
        return runCatching {
            val proc = ProcessBuilder(listOf(adb, "devices", "-l"))
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@runCatching emptyList()
            }
            parseDevices(output)
        }.getOrDefault(emptyList())
    }

    /**
     * Parses `adb devices -l` output. Pure and unit-tested — the shape is
     * `serial<whitespace>state key:value key:value …`, after a `List of devices attached`
     * header line and around adb's occasional daemon-start chatter.
     */
    fun parseDevices(output: String): List<DeviceInfo> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("List of devices") || it.startsWith("*") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val serial = parts[0]
                val state = parts[1]
                // The state word never contains ':'; a line whose second token does is chatter.
                if (state.contains(':')) return@mapNotNull null
                val model = parts.drop(2)
                    .firstOrNull { it.startsWith("model:") }
                    ?.removePrefix("model:")
                    ?.takeIf { it.isNotBlank() }
                DeviceInfo(serial, state, model)
            }
            .toList()
}
