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
}
