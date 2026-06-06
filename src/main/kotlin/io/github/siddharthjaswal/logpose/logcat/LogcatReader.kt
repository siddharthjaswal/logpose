package io.github.siddharthjaswal.logpose.logcat

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams the device's logcat (filtered to the LogPose tag) and hands each raw
 * message line to [onLine] on a background thread.
 *
 * We run `adb logcat -v raw -s <TAG>:V`:
 *  - `-s <TAG>:V` silences everything except our tag — no app-log noise.
 *  - `-v raw` strips the timestamp/pid/level prefix, so each line IS the payload.
 *
 * The buffer is cleared before streaming so a Stop→Start doesn't replay the backlog.
 *
 * **All `adb` invocations run on the background pump thread, never the caller's (EDT)
 * thread** — a blocking `adb` call on the UI thread freezes the whole IDE, e.g. while
 * the emulator is shutting down and adb stalls.
 */
class LogcatReader(
    private val tag: String = DEFAULT_TAG,
    private val deviceSerial: String? = null,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    private var thread: Thread? = null

    /**
     * @param onStopped invoked (off the EDT) when streaming ends for any reason —
     *   user stop, the device disconnecting, or an adb error. Lets the UI reset state.
     */
    fun start(onLine: (String) -> Unit, onError: (String) -> Unit, onStopped: () -> Unit = {}) {
        if (!running.compareAndSet(false, true)) return

        val adb = resolveAdb()
        if (adb == null) {
            running.set(false)
            onError("adb not found. Set ANDROID_HOME / ANDROID_SDK_ROOT or add adb to PATH.")
            return
        }

        thread = Thread({ pump(adb, onLine, onError, onStopped) }, "logpose-logcat").apply {
            isDaemon = true
            start()
        }
    }

    private fun pump(adb: String, onLine: (String) -> Unit, onError: (String) -> Unit, onStopped: () -> Unit) {
        var proc: Process? = null
        try {
            // Clear the backlog here (background thread), not in start() — `adb logcat -c`
            // can block while the device is transitioning.
            clearBuffer(adb)

            val cmd = baseCmd(adb) + listOf("logcat", "-v", "raw", "-s", "$tag:V")
            // Merge stderr so adb's error output is drained by our single reader.
            proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process = proc
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (running.get()) {
                    val line = reader.readLine() ?: break // null = process ended (device gone)
                    if (line.isNotBlank()) onLine(line)
                }
            }
        } catch (t: Throwable) {
            if (running.get()) onError(t.message ?: t.toString())
        } finally {
            running.set(false)
            process = null
            runCatching { proc?.destroy() }
            runCatching { onStopped() }
        }
    }

    /** Empties the device log buffer asynchronously (never blocks the caller). */
    fun clearBuffer() {
        val adb = resolveAdb() ?: return
        Thread({ clearBuffer(adb) }, "logpose-clear").apply { isDaemon = true }.start()
    }

    private fun clearBuffer(adb: String) {
        runCatching {
            val p = ProcessBuilder(baseCmd(adb) + listOf("logcat", "-c"))
                .redirectErrorStream(true)
                .start()
            // Bound it — never wait forever on a stalled adb.
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly()
        }
    }

    /** Stops streaming. Returns immediately; the process is reaped on a daemon thread. */
    fun stop() {
        running.set(false)
        val p = process
        process = null
        thread = null
        if (p != null) {
            Thread({
                p.destroy()
                if (!p.waitFor(800, TimeUnit.MILLISECONDS)) p.destroyForcibly()
            }, "logpose-stop").apply { isDaemon = true }.start()
        }
    }

    fun isRunning(): Boolean = running.get()

    private fun baseCmd(adb: String): List<String> = buildList {
        add(adb)
        if (!deviceSerial.isNullOrBlank()) {
            add("-s"); add(deviceSerial)
        }
    }

    private fun resolveAdb(): String? {
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

    companion object {
        const val DEFAULT_TAG = "LogPose"
    }
}
