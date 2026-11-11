package io.github.siddharthjaswal.logpose.appcontrol

/**
 * The three things LogPose can do to the app it is capturing. Each ends in a relaunch, so each
 * produces a new app run, which is what lets the capture show the before and after as two runs.
 *
 * [marker] is the title of the timeline row the action leaves behind, so a capture says what was done
 * to the app. [progress] is the line a host shows while the destructive step runs.
 */
enum class AppAction(
    /** Stable id: MCP results, settings and tests use it. */
    val id: String,
    val label: String,
    val marker: String,
    val progress: String,
) {
    RESTART("restart", "Restart app", "Restarted by LogPose", "Stopping…"),
    CLEAR_CACHE("clear_cache", "Clear cache & restart", "Cache cleared", "Clearing cache…"),
    CLEAR_DATA("clear_data", "Clear all data & restart", "App data cleared", "Clearing app data…"),
    ;

    /** Whether the user is asked first. Only the action that logs the app out is. */
    val destructive: Boolean get() = this == CLEAR_DATA

    companion object {
        /**
         * What a confirm dialog says before [CLEAR_DATA]. Only what was verified on API 34 and 36
         * emulators: `pm clear` deleted internal + device-protected data (databases, shared prefs,
         * files/, no_backup/, caches) and the app's Android/data and Android/media folders (its OBB
         * folder survived), and reset every runtime permission to not granted.
         */
        const val CLEAR_DATA_WARNING =
            "This logs the app out, deletes its databases, files and settings (its Android/data and " +
                "Android/media folders too), and resets its permissions. It can't be undone."

        /** Said when `run-as` refuses: a release build's cache can't be reached without clearing everything. */
        const val NOT_DEBUGGABLE_ADVICE =
            "Clearing only the cache needs a debuggable build (run-as). Use “Clear all data & restart” " +
                "instead, which also logs the app out."
    }
}

/**
 * The device-side shell command lines, one per step. Pure strings so they are unit-tested exactly as
 * they go over `adb -s <serial> shell <line>` — adb hands the line to the device's `sh`, so every
 * value interpolated here is validated first ([isValidPackage], [isValidComponent], [isValidPermission])
 * and nothing user-typed reaches the shell unchecked.
 *
 * Floor: API 24. That is where `cmd package` arrived and where adb's shell protocol v2 started
 * returning the remote exit status, which [AppOutput] relies on alongside the text.
 */
object AppCommands {

    const val LAUNCHER = "android.intent.category.LAUNCHER"

    /**
     * An Android application id: two or more dot-separated segments, each a Java identifier
     * (letters, digits, `_`, starting with a letter). That is exactly what the build tools accept,
     * so `.debug` suffixes and the like pass untouched. Nothing a shell treats specially can.
     */
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** `pkg/cls` or `pkg/.cls`, as `resolve-activity --brief` prints it. `$` is allowed for nested classes. */
    private val COMPONENT = Regex("^[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+$")

    private val PERMISSION = Regex("^[A-Za-z][A-Za-z0-9_.]*$")

    fun isValidPackage(pkg: String): Boolean = PACKAGE.matches(pkg)
    fun isValidComponent(component: String): Boolean = COMPONENT.matches(component)
    fun isValidPermission(permission: String): Boolean = PERMISSION.matches(permission)

    private fun pkg(pkg: String): String = pkg.also { require(isValidPackage(it)) { "Not a package name: $it" } }

    /** `ro.build.version.sdk` — only reported, the commands below are the same on every supported level. */
    fun sdkLevel(): String = "getprop ro.build.version.sdk"

    /** Prints `package:/data/app/…/base.apk` when installed; nothing (exit 1) when not. */
    fun packagePath(pkg: String): String = "pm path ${pkg(pkg)}"

    fun forceStop(pkg: String): String = "am force-stop ${pkg(pkg)}"

    /**
     * The launcher activity, as the home screen would resolve it. `--brief` prints the resolve info and
     * the component on its last line (API 24+), or "No activity found".
     */
    fun resolveLauncher(pkg: String): String =
        "cmd package resolve-activity --brief -c $LAUNCHER ${pkg(pkg)}"

    /**
     * Starts [component] the way a launcher tap does. The component is single-quoted because a nested
     * class name may carry a `$`, which the device shell would otherwise expand.
     */
    fun start(component: String): String {
        require(isValidComponent(component)) { "Not a component: $component" }
        return "am start -n '$component' -a android.intent.action.MAIN -c $LAUNCHER"
    }

    /** The fallback when the launcher can't be resolved: one launcher intent, no random events. */
    fun monkeyLaunch(pkg: String): String = "monkey -p ${pkg(pkg)} -c $LAUNCHER 1"

    /**
     * A no-op under the app's uid — run before anything is stopped, so a release build is reported
     * before the app has been touched.
     */
    fun runAsProbe(pkg: String): String = "run-as ${pkg(pkg)} true"

    /**
     * Empties the app's internal cache dirs as the app itself (run-as starts in its data dir), keeping
     * the dirs. `.[!.]*` catches dotfiles that `*` skips without matching `.` or `..`; a glob that
     * matches nothing stays literal, which `rm -f` ignores.
     */
    fun clearInternalCache(pkg: String): String =
        "run-as ${pkg(pkg)} sh -c 'rm -rf cache/* cache/.[!.]* code_cache/* code_cache/.[!.]*'"

    /** Where shared-storage cache lives. */
    fun externalCacheDir(pkg: String): String = "/sdcard/Android/data/${pkg(pkg)}/cache"

    /**
     * Empties the shared-storage cache as the shell user, reporting which case it was. Prints
     * [AppOutput.EXTERNAL_ABSENT] when the app never made one, [AppOutput.EXTERNAL_CLEARED] when emptied,
     * and the `rm` error otherwise (scoped storage on some builds keeps the shell out).
     */
    fun clearExternalCache(pkg: String): String {
        val dir = externalCacheDir(pkg)
        return "if [ -d $dir ]; then rm -rf $dir/* $dir/.[!.]* && echo ${AppOutput.EXTERNAL_CLEARED}; " +
            "else echo ${AppOutput.EXTERNAL_ABSENT}; fi"
    }

    fun pmClear(pkg: String): String = "pm clear ${pkg(pkg)}"

    /** Read before `pm clear` when the user asked to keep permissions; see [AppOutput.grantedRuntimePermissions]. */
    fun dumpsysPackage(pkg: String): String = "dumpsys package ${pkg(pkg)}"

    fun grant(pkg: String, permission: String): String {
        require(isValidPermission(permission)) { "Not a permission: $permission" }
        return "pm grant ${pkg(pkg)} $permission"
    }
}

/**
 * Reading what those commands printed. Pure, and tested against the wording real devices produce,
 * since the exit status alone isn't enough: `am start` exits 0 after printing "Error: …" on several
 * API levels, and `pm clear` prints "Failed" with a zero exit on some.
 */
object AppOutput {

    const val EXTERNAL_CLEARED = "logpose:external-cleared"
    const val EXTERNAL_ABSENT = "logpose:external-absent"

    /** Why adb itself, rather than the device command, failed. */
    enum class AdbProblem { NO_ADB, OFFLINE, UNAUTHORIZED, NOT_FOUND, TIMEOUT }

    /**
     * adb's own failures, which arrive on the same merged stream as the device's output. Null when the
     * output is the device command's own.
     */
    fun adbProblem(output: String, exit: Int?): AdbProblem? {
        val text = output.lowercase()
        return when {
            exit == null -> AdbProblem.TIMEOUT
            "unauthorized" in text && (text.contains("error:") || text.contains("adb:")) -> AdbProblem.UNAUTHORIZED
            "device offline" in text -> AdbProblem.OFFLINE
            Regex("(error|adb): device '.*' not found").containsMatchIn(text) -> AdbProblem.NOT_FOUND
            "no devices/emulators found" in text -> AdbProblem.NOT_FOUND
            else -> null
        }
    }

    fun describe(problem: AdbProblem, serial: String): String = when (problem) {
        AdbProblem.NO_ADB -> "adb wasn't found. Install the Android SDK platform-tools, or point LogPose at adb."
        AdbProblem.OFFLINE -> "$serial is offline — it is still booting or adb lost it. Wait a moment, or run `adb reconnect`."
        AdbProblem.UNAUTHORIZED -> "$serial is unauthorized — accept the USB-debugging prompt on the device."
        AdbProblem.NOT_FOUND -> "$serial isn't connected — adb doesn't list it."
        AdbProblem.TIMEOUT -> "$serial didn't answer in time."
    }

    /** `pm path` prints `package:<apk>` per split for an installed package, and nothing otherwise. */
    fun isInstalled(output: String): Boolean = output.lineSequence().any { it.trim().startsWith("package:") }

    /**
     * The component from `cmd package resolve-activity --brief`: its last line, when that is one.
     * "No activity found" (no launcher activity, or not installed) and anything else give null, which
     * sends the caller to the monkey fallback.
     */
    fun launcherComponent(output: String): String? =
        output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull()
            ?.takeIf { AppCommands.isValidComponent(it) }

    /** `am start` reports failure in text: "Error: Activity class … does not exist", "Error type 3", an exception. */
    fun startSucceeded(output: String, exit: Int?): Boolean {
        if (exit != null && exit != 0) return false
        val text = output.trim()
        if (text.isEmpty()) return exit == 0
        return text.lineSequence().none { line ->
            val l = line.trim()
            l.startsWith("Error") || l.startsWith("Exception") || l.contains("SecurityException") ||
                l.startsWith("java.lang.")
        }
    }

    /** monkey says "Events injected: 1" when it sent the intent; "No activities found to run, monkey aborted." when not. */
    fun monkeySucceeded(output: String): Boolean =
        output.contains("Events injected: 1") && !output.contains("monkey aborted", ignoreCase = true)

    /** Why `run-as` refused, in the words that matter to the user. */
    enum class RunAsFailure { NOT_DEBUGGABLE, UNKNOWN_PACKAGE, NOT_AN_APP, OTHER }

    /**
     * Null when `run-as` ran the command. The wording has moved across releases — "package not
     * debuggable: x" (API 24+), "Package 'x' is not debuggable" (older), "unknown package: x" /
     * "Package 'x' is unknown" — so the match is on the stable words.
     */
    fun runAsFailure(output: String, exit: Int?): RunAsFailure? {
        val text = output.lowercase()
        val mentionsRunAs = "run-as:" in text
        return when {
            "not debuggable" in text -> RunAsFailure.NOT_DEBUGGABLE
            mentionsRunAs && ("unknown package" in text || "is unknown" in text) -> RunAsFailure.UNKNOWN_PACKAGE
            mentionsRunAs && "not an application" in text -> RunAsFailure.NOT_AN_APP
            exit != null && exit != 0 -> RunAsFailure.OTHER
            mentionsRunAs -> RunAsFailure.OTHER
            else -> null
        }
    }

    /** `pm clear` prints "Success"; anything else ("Failed", an exception) is a failure. */
    fun pmClearSucceeded(output: String): Boolean =
        output.lineSequence().any { it.trim() == "Success" } &&
            output.lineSequence().none { it.trim().startsWith("Failed") || it.contains("Exception") }

    /** What the shared-storage cache step found. */
    enum class ExternalCache { CLEARED, ABSENT, UNREACHABLE }

    fun externalCache(output: String): ExternalCache = when {
        EXTERNAL_CLEARED in output -> ExternalCache.CLEARED
        EXTERNAL_ABSENT in output -> ExternalCache.ABSENT
        else -> ExternalCache.UNREACHABLE
    }

    /** `pm grant` prints nothing on success, and an exception line on failure. */
    fun grantSucceeded(output: String, exit: Int?): Boolean =
        (exit == null || exit == 0) && output.lineSequence().none {
            val l = it.trim()
            l.startsWith("Exception") || l.startsWith("Error") || l.startsWith("Security exception") ||
                l.contains("java.lang.")
        }

    /**
     * Runtime permissions currently granted, from `dumpsys package <pkg>` — only the first user's
     * `runtime permissions:` block, and only ones a `pm grant` can give back. A permission the system
     * pinned (`SYSTEM_FIXED`, `POLICY_FIXED`) is skipped: `pm clear` doesn't touch those, and granting
     * them is refused anyway.
     *
     * The block looks like this on API 24 through 36:
     * ```
     *     User 0: ceDataInode=… installed=true …
     *       runtime permissions:
     *         android.permission.CAMERA: granted=true, flags=[ USER_SET|USER_SENSITIVE_WHEN_GRANTED ]
     *         android.permission.ACCESS_FINE_LOCATION: granted=false, flags=[ USER_SET ]
     * ```
     */
    fun grantedRuntimePermissions(dumpsys: String): List<String> {
        val lines = dumpsys.lines()
        val start = lines.indexOfFirst { it.trim() == "runtime permissions:" }
        if (start < 0) return emptyList()
        val headerIndent = lines[start].indentWidth()
        val entry = Regex("^([A-Za-z][A-Za-z0-9_.]*)[:,]\\s*granted=(true|false)(.*)$")
        val granted = ArrayList<String>()
        for (raw in lines.drop(start + 1)) {
            if (raw.isBlank()) break
            if (raw.indentWidth() <= headerIndent) break
            val m = entry.find(raw.trim()) ?: continue
            if (m.groupValues[2] != "true") continue
            val flags = m.groupValues[3]
            if ("SYSTEM_FIXED" in flags || "POLICY_FIXED" in flags) continue
            if (AppCommands.isValidPermission(m.groupValues[1])) granted += m.groupValues[1]
        }
        return granted.distinct()
    }

    private fun String.indentWidth(): Int = takeWhile { it == ' ' || it == '\t' }.length
}
