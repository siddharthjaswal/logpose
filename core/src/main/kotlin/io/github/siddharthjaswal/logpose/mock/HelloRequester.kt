package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.logcat.output
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Asks an app that is already running to announce itself again.
 *
 * When capture attaches after the app launched, the launch `Hello` is gone — and with it the only
 * thing that tied the app's events to a run. They pile up in "session 0". This closes that gap
 * from the host side, using the one thing logcat's `-v brief` prefix always gives us: the pid.
 *
 * On the first event from a pid no run has claimed ([io.github.siddharthjaswal.logpose.store.EventStore.needsHello]),
 * after a short grace period (the launch hello may simply still be in flight), it:
 *  1. reads `/proc/<pid>/cmdline` to learn the package — the receiver is addressed by component,
 *     and we have not been told the package yet, which is the whole problem;
 *  2. broadcasts [BroadcastCommand.CMD_HELLO] to that package's `MockCommandReceiver`.
 *
 * Library 1.7.4+ answers with its current run's hello, and the store then attributes the pid's
 * earlier events to it. Older libraries ignore the command (it carries no payload, which their
 * receiver drops before reading `cmd`), so nothing changes for them; the pid still lets a hello
 * that arrives later for any reason claim the earlier rows.
 *
 * It never touches the mock rule channel, so it is safe from a read-only host (the daemon without
 * `--mocks`, the Mac app): the only effect on the device is one log line. One request per pid per
 * capture, all adb work on a daemon thread — never the caller's (which may be the EDT).
 *
 * @param stillNeeded re-checked after the grace period, so a hello that arrived meanwhile cancels it.
 * @param run runs an adb command and returns its stdout, or null when it failed. Injectable for tests.
 */
class HelloRequester(
    private val deviceSerial: () -> String?,
    private val stillNeeded: (Int) -> Boolean,
    private val run: (List<String>) -> String? = { Adb.output(it) },
    private val scheduler: ScheduledExecutorService = sharedScheduler,
    private val graceMillis: Long = GRACE_MILLIS,
) {
    private val asked = ConcurrentHashMap.newKeySet<Int>()

    /** Called with the pid of an event no run has claimed. Cheap; repeat calls are no-ops. */
    fun onUnclaimedPid(pid: Int) {
        if (!asked.add(pid)) return
        runCatching { scheduler.schedule({ request(pid) }, graceMillis, TimeUnit.MILLISECONDS) }
    }

    /** Forget which pids were asked — a new capture asks again. */
    fun reset() = asked.clear()

    internal fun request(pid: Int) {
        if (!stillNeeded(pid)) return
        val adb = Adb.resolve() ?: return
        val base = Adb.baseCmd(adb, deviceSerial())
        val pkg = run(base + listOf("shell", "cat", "/proc/$pid/cmdline"))?.let(::packageFromCmdline) ?: return
        run(base + BroadcastCommand.helloArgs(pkg))
    }

    companion object {
        /** Long enough for a launch hello racing the first events to land; short enough to feel instant. */
        const val GRACE_MILLIS = 1_500L

        private val PACKAGE = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+""")

        /**
         * The package from a process's `cmdline`: argv[0] up to the first NUL, minus a `:process`
         * suffix. Null for anything that isn't a package name — a native daemon, an exited process
         * (empty), an `adb` error message.
         */
        fun packageFromCmdline(raw: String): String? {
            val name = raw.substringBefore('\u0000').trim().substringBefore(':')
            return name.takeIf { PACKAGE.matches(it) }
        }

        private val sharedScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "logpose-hello-request").apply { isDaemon = true }
            }
    }
}
