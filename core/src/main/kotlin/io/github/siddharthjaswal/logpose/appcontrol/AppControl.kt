package io.github.siddharthjaswal.logpose.appcontrol

import io.github.siddharthjaswal.logpose.logcat.Adb
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** One `adb -s <serial> shell <line>`, answered with its merged output and exit status. */
fun interface AppShell {
    /** [Result.exit] is null when the command didn't finish in [timeoutMillis] or couldn't start. */
    fun run(serial: String, line: String, timeoutMillis: Long): Result

    data class Result(val exit: Int?, val output: String)

    companion object {
        /** The real one. Blocking process I/O — never call it on the EDT or a UI thread. */
        val Adb: AppShell = AdbAppShell
    }
}

private object AdbAppShell : AppShell {
    override fun run(serial: String, line: String, timeoutMillis: Long): AppShell.Result {
        val adb = Adb.resolve() ?: return AppShell.Result(null, NO_ADB)
        return runCatching {
            val process = ProcessBuilder(Adb.baseCmd(adb, serial) + listOf("shell", line))
                .redirectErrorStream(true)
                .start()
            // Drained on its own thread so a chatty command can't fill the pipe and stall waitFor.
            val buffer = ByteArrayOutputStream()
            val drain = Thread({ runCatching { process.inputStream.copyTo(buffer) } }, "logpose-app-shell")
                .apply { isDaemon = true; start() }
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return AppShell.Result(null, synchronized(buffer) { buffer.toString(Charsets.UTF_8) })
            }
            drain.join(1_000)
            AppShell.Result(process.exitValue(), synchronized(buffer) { buffer.toString(Charsets.UTF_8) })
        }.getOrElse { AppShell.Result(null, it.message ?: it.toString()) }
    }

    const val NO_ADB = "logpose: adb not found"
}

/**
 * Runs one [AppAction] against one package on one device, step by step, and says exactly what
 * happened. Pure orchestration over an [AppShell], so every branch is unit-tested without a device.
 *
 * The order is the safety argument:
 *  1. **Look before touching.** Is the package installed? For a cache clear, will `run-as` work? A
 *     release build is reported *before* the app is stopped, and never quietly escalated to a full
 *     clear — the caller offers that as a separate, confirmed action.
 *  2. **Stop, then change.** Deleting files under a running app races its own writes.
 *  3. **Always relaunch.** Even if a clear step failed after the stop, the app is started again, so
 *     the user is never left with a dead app and a report.
 *
 * Blocking: call it from a pooled thread. [progress] is called on that thread.
 */
class AppControl(private val shell: AppShell = AppShell.Adb) {

    data class Options(
        /**
         * [AppAction.CLEAR_DATA] only: read the granted runtime permissions first and `pm grant` them
         * back after the clear. Permissions the device refuses to grant are listed, not hidden.
         */
        val regrantPermissions: Boolean = false,
    )

    /** Why an action didn't do what was asked. */
    enum class Failure {
        INVALID_PACKAGE, NO_ADB, OFFLINE, UNAUTHORIZED, DEVICE_NOT_FOUND, TIMEOUT,
        NOT_INSTALLED, NOT_DEBUGGABLE, CLEAR_FAILED, LAUNCH_FAILED,
    }

    /** One command that ran. */
    data class Step(val name: String, val command: String, val ok: Boolean, val output: String)

    data class Report(
        val action: AppAction,
        val serial: String,
        val pkg: String,
        val steps: List<Step>,
        /** Null when every step did its job. */
        val failure: Failure? = null,
        /** Plain-language reason, when [failure] is set. */
        val error: String? = null,
        /** True once `am force-stop` ran — the old run is over even if a later step failed. */
        val stopped: Boolean = false,
        /** True once the clear itself succeeded (always false for a plain restart). */
        val cleared: Boolean = false,
        /** `am start -n <component>` or `monkey`, once the app was launched. */
        val launchedVia: String? = null,
        val externalCache: AppOutput.ExternalCache? = null,
        val regranted: List<String> = emptyList(),
        val regrantFailed: List<String> = emptyList(),
        /** The device's API level, when it answered. */
        val sdk: Int? = null,
    ) {
        val ok: Boolean get() = failure == null
        val launched: Boolean get() = launchedVia != null

        /**
         * True when a cache-only clear was refused because the build isn't debuggable: the UI then
         * offers "Clear all data" as its own, confirmed action.
         */
        val offerClearAll: Boolean get() = failure == Failure.NOT_DEBUGGABLE

        /** The marker title this run earned: the clear's when it happened, else a plain restart's. */
        val markerTitle: String
            get() = if (action != AppAction.RESTART && cleared) action.marker else AppAction.RESTART.marker
    }

    /**
     * Runs [action]. [beforeLaunch] fires once, after the stop and the clear and just before the app is
     * started again — the moment a host records the timeline marker, so it lands between the old run's
     * rows and the new run's.
     */
    fun run(
        action: AppAction,
        serial: String,
        pkg: String,
        options: Options = Options(),
        progress: (String) -> Unit = {},
        beforeLaunch: (Report) -> Unit = {},
    ): Report {
        val steps = ArrayList<Step>()
        fun report(
            failure: Failure? = null, error: String? = null, stopped: Boolean = false, cleared: Boolean = false,
            launchedVia: String? = null, external: AppOutput.ExternalCache? = null,
            regranted: List<String> = emptyList(), regrantFailed: List<String> = emptyList(), sdk: Int? = null,
        ) = Report(action, serial, pkg, steps.toList(), failure, error, stopped, cleared, launchedVia, external, regranted, regrantFailed, sdk)

        if (!AppCommands.isValidPackage(pkg)) {
            return report(Failure.INVALID_PACKAGE, "“$pkg” isn't an Android package name (e.g. com.example.app.debug).")
        }

        // Any adb-level failure ends the run where it happened, with adb's reason in plain words.
        fun exec(name: String, line: String, timeout: Long = DEFAULT_TIMEOUT_MS, ok: (AppShell.Result) -> Boolean): Pair<AppShell.Result, Failure?> {
            val result = shell.run(serial, line, timeout)
            val problem = if (result.output.contains(AdbAppShell.NO_ADB)) AppOutput.AdbProblem.NO_ADB
            else AppOutput.adbProblem(result.output, result.exit)
            steps += Step(name, line, problem == null && ok(result), result.output.trim())
            val failure = when (problem) {
                null -> null
                AppOutput.AdbProblem.NO_ADB -> Failure.NO_ADB
                AppOutput.AdbProblem.OFFLINE -> Failure.OFFLINE
                AppOutput.AdbProblem.UNAUTHORIZED -> Failure.UNAUTHORIZED
                AppOutput.AdbProblem.NOT_FOUND -> Failure.DEVICE_NOT_FOUND
                AppOutput.AdbProblem.TIMEOUT -> Failure.TIMEOUT
            }
            return result to failure
        }
        fun adbError(failure: Failure): String = AppOutput.describe(
            when (failure) {
                Failure.NO_ADB -> AppOutput.AdbProblem.NO_ADB
                Failure.OFFLINE -> AppOutput.AdbProblem.OFFLINE
                Failure.UNAUTHORIZED -> AppOutput.AdbProblem.UNAUTHORIZED
                Failure.DEVICE_NOT_FOUND -> AppOutput.AdbProblem.NOT_FOUND
                else -> AppOutput.AdbProblem.TIMEOUT
            },
            serial,
        )

        progress("Checking $pkg…")
        val (installed, installFailure) = exec("check installed", AppCommands.packagePath(pkg)) { AppOutput.isInstalled(it.output) }
        if (installFailure != null) return report(installFailure, adbError(installFailure))
        if (!AppOutput.isInstalled(installed.output)) {
            return report(Failure.NOT_INSTALLED, "$pkg isn't installed on $serial.")
        }
        val sdk = shell.run(serial, AppCommands.sdkLevel(), DEFAULT_TIMEOUT_MS).output.trim().toIntOrNull()

        if (action == AppAction.CLEAR_CACHE) {
            val (probe, probeFailure) = exec("run-as probe", AppCommands.runAsProbe(pkg)) { AppOutput.runAsFailure(it.output, it.exit) == null }
            if (probeFailure != null) return report(probeFailure, adbError(probeFailure), sdk = sdk)
            when (AppOutput.runAsFailure(probe.output, probe.exit)) {
                null -> Unit
                AppOutput.RunAsFailure.NOT_DEBUGGABLE -> return report(
                    Failure.NOT_DEBUGGABLE,
                    "$pkg isn't a debuggable build, so its cache can't be reached on its own. Nothing was changed. " +
                        AppAction.NOT_DEBUGGABLE_ADVICE,
                    sdk = sdk,
                )
                else -> return report(
                    Failure.NOT_DEBUGGABLE,
                    "run-as refused $pkg (${probe.output.trim().lineSequence().firstOrNull().orEmpty()}), so its cache " +
                        "can't be reached on its own. Nothing was changed. ${AppAction.NOT_DEBUGGABLE_ADVICE}",
                    sdk = sdk,
                )
            }
        }

        val permissions = if (action == AppAction.CLEAR_DATA && options.regrantPermissions) {
            progress("Reading granted permissions…")
            val (dump, dumpFailure) = exec("read permissions", AppCommands.dumpsysPackage(pkg), timeout = 15_000) { true }
            if (dumpFailure != null) return report(dumpFailure, adbError(dumpFailure), sdk = sdk)
            AppOutput.grantedRuntimePermissions(dump.output)
        } else emptyList()

        progress("Stopping…")
        val (_, stopFailure) = exec("force-stop", AppCommands.forceStop(pkg)) { it.exit == null || it.exit == 0 }
        if (stopFailure != null) return report(stopFailure, adbError(stopFailure), sdk = sdk)

        var cleared = false
        var clearError: String? = null
        var external: AppOutput.ExternalCache? = null
        val regranted = ArrayList<String>()
        val regrantFailed = ArrayList<String>()
        when (action) {
            AppAction.RESTART -> Unit
            AppAction.CLEAR_CACHE -> {
                progress("Clearing cache…")
                val (rm, rmFailure) = exec("clear cache", AppCommands.clearInternalCache(pkg)) { AppOutput.runAsFailure(it.output, it.exit) == null }
                if (rmFailure != null) return report(rmFailure, adbError(rmFailure), stopped = true, sdk = sdk)
                if (AppOutput.runAsFailure(rm.output, rm.exit) == null) {
                    cleared = true
                    val (ext, extFailure) = exec("clear external cache", AppCommands.clearExternalCache(pkg)) {
                        AppOutput.externalCache(it.output) != AppOutput.ExternalCache.UNREACHABLE
                    }
                    if (extFailure != null) return report(extFailure, adbError(extFailure), stopped = true, cleared = true, sdk = sdk)
                    external = AppOutput.externalCache(ext.output)
                } else {
                    clearError = "Clearing the cache failed: ${rm.output.trim().ifEmpty { "run-as exited ${rm.exit}" }}"
                }
            }
            AppAction.CLEAR_DATA -> {
                progress("Clearing app data…")
                val (clear, clearFailure) = exec("pm clear", AppCommands.pmClear(pkg), timeout = 30_000) { AppOutput.pmClearSucceeded(it.output) }
                if (clearFailure != null) return report(clearFailure, adbError(clearFailure), stopped = true, sdk = sdk)
                if (AppOutput.pmClearSucceeded(clear.output)) {
                    cleared = true
                    if (permissions.isNotEmpty()) {
                        progress("Re-granting ${permissions.size} permission(s)…")
                        for (permission in permissions) {
                            val (grant, grantFailure) = exec("grant $permission", AppCommands.grant(pkg, permission)) {
                                AppOutput.grantSucceeded(it.output, it.exit)
                            }
                            if (grantFailure == null && AppOutput.grantSucceeded(grant.output, grant.exit)) regranted += permission
                            else regrantFailed += permission
                        }
                    }
                } else {
                    clearError = "pm clear didn't succeed: ${clear.output.trim().ifEmpty { "no output" }}"
                }
            }
        }

        beforeLaunch(report(stopped = true, cleared = cleared, external = external, regranted = regranted, regrantFailed = regrantFailed, sdk = sdk))

        progress("Launching…")
        var launchedVia: String? = null
        val (resolved, resolveFailure) = exec("resolve launcher", AppCommands.resolveLauncher(pkg)) { AppOutput.launcherComponent(it.output) != null }
        if (resolveFailure != null) {
            return report(resolveFailure, adbError(resolveFailure), true, cleared, null, external, regranted, regrantFailed, sdk)
        }
        val component = AppOutput.launcherComponent(resolved.output)
        if (component != null) {
            val (start, _) = exec("start", AppCommands.start(component)) { AppOutput.startSucceeded(it.output, it.exit) }
            if (AppOutput.startSucceeded(start.output, start.exit)) launchedVia = "am start -n $component"
        }
        if (launchedVia == null) {
            val (monkey, _) = exec("monkey launch", AppCommands.monkeyLaunch(pkg), timeout = 15_000) { AppOutput.monkeySucceeded(it.output) }
            if (AppOutput.monkeySucceeded(monkey.output)) launchedVia = "monkey"
        }

        val launchError = if (launchedVia == null) "$pkg was stopped but couldn't be launched again — it has no launcher activity " +
            "LogPose could start. Open it on the device." else null
        val failure = when {
            clearError != null -> Failure.CLEAR_FAILED
            launchError != null -> Failure.LAUNCH_FAILED
            else -> null
        }
        return report(failure, listOfNotNull(clearError, launchError).joinToString(" ").ifEmpty { null }, true, cleared, launchedVia, external, regranted, regrantFailed, sdk)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
