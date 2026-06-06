package io.github.siddharthjaswal.logpose.logcat

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams the device's logcat (filtered to the LogPose tag) and hands each raw
 * message line to [onLine] on a background thread.
 *
 * We run `adb logcat -v raw -s <TAG>:V`:
 *  - `-s <TAG>:V` silences everything except our tag — no app-log noise.
 *  - `-v raw` strips the timestamp/pid/level prefix, so each line IS the payload.
 *
 * Crucially, we **clear the log buffer before streaming**. By default `adb logcat`
 * dumps the entire existing buffer first and only then tails new entries — which is
 * why a Stop→Start would otherwise replay every old transaction. Clearing first means
 * Start shows only traffic produced from that moment on.
 */
class LogcatReader(
    private val tag: String = DEFAULT_TAG,
    private val deviceSerial: String? = null,
) {
    private val running = AtomicBoolean(false)
    private var process: Process? = null
    private var thread: Thread? = null

    fun start(onLine: (String) -> Unit, onError: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) return

        val adb = resolveAdb()
        if (adb == null) {
            running.set(false)
            onError("adb not found. Set ANDROID_HOME / ANDROID_SDK_ROOT or add adb to PATH.")
            return
        }

        // Drop the backlog so we only stream new logs (no replay of old transactions).
        clearBuffer(adb)

        thread = Thread({ pump(adb, onLine, onError) }, "logpose-logcat").apply {
            isDaemon = true
            start()
        }
    }

    private fun pump(adb: String, onLine: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val cmd = baseCmd(adb) + listOf("logcat", "-v", "raw", "-s", "$tag:V")
            // Merge stderr into stdout so adb's error output is drained by our single
            // reader (an undrained stderr can block the process and leak it).
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            process = proc
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) onLine(line)
                }
            }
        } catch (t: Throwable) {
            if (running.get()) onError(t.message ?: t.toString())
        } finally {
            running.set(false)
        }
    }

    /** Empties the device log buffer. Safe to call whether or not capture is running. */
    fun clearBuffer() {
        resolveAdb()?.let { clearBuffer(it) }
    }

    private fun clearBuffer(adb: String) {
        runCatching {
            ProcessBuilder(baseCmd(adb) + listOf("logcat", "-c"))
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
    }

    fun stop() {
        running.set(false)
        process?.let { p ->
            p.destroy()
            // Give it a moment to exit, then force, so the reader thread unblocks and
            // the process is reaped rather than left as a zombie.
            if (!p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
        }
        process = null
        thread = null
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
