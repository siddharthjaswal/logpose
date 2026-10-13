package io.github.siddharthjaswal.logpose.desktop.adb

import io.github.siddharthjaswal.logpose.logcat.Adb
import java.io.File

/**
 * Where the Mac app's adb comes from (PRD §4), as a pure decision over an injected filesystem.
 *
 * Order: whatever core's [Adb.resolve] finds (the developer case — it shares their running adb
 * server), then the copy this app downloaded into Application Support. Null means neither exists
 * and the first-run download screen is shown.
 *
 * One wrinkle forces a step of our own: core's last candidate is the bare word `adb`, returned
 * *unchecked* so a shell's PATH can resolve it later. That is right for the plugin, but a Finder-
 * launched app has a minimal PATH and no SDK, so "adb" there usually means "nothing". So a bare
 * `adb` is only accepted once a real executable is found on PATH (plus Homebrew's two prefixes,
 * which a Finder launch never has on PATH).
 */
object AdbLocator {

    /** `~/Library/Application Support/LogPose` — the app's own data root. */
    fun appSupportDir(home: String = System.getProperty("user.home")): File =
        File(home, "Library/Application Support/LogPose")

    /** Where the downloaded platform-tools live once installed. */
    fun downloadedAdb(home: String = System.getProperty("user.home")): File =
        File(appSupportDir(home), "platform-tools/adb")

    fun locate(
        coreResolved: String? = Adb.resolve(),
        pathEnv: String? = System.getenv("PATH"),
        home: String = System.getProperty("user.home"),
        canExecute: (File) -> Boolean = { it.isFile && it.canExecute() },
    ): String? {
        if (coreResolved != null && coreResolved != "adb" && canExecute(File(coreResolved))) return coreResolved
        val pathDirs = pathEnv.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() } +
            listOf("/opt/homebrew/bin", "/usr/local/bin")
        pathDirs.map { File(it, "adb") }.firstOrNull(canExecute)?.let { return it.path }
        return downloadedAdb(home).takeIf(canExecute)?.path
    }

    /** Points every core adb caller (reader, device list, mock pusher) at [path]. */
    fun install(path: String) {
        Adb.explicitPath = path
    }
}
