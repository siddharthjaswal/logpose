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

        thread = Thread({ pump(adb, onLine, onError) }, "logpose-logcat").apply {
            isDaemon = true
            start()
        }
    }

    private fun pump(adb: String, onLine: (String) -> Unit, onError: (String) -> Unit) {
        try {
            val cmd = buildList {
                add(adb)
                if (!deviceSerial.isNullOrBlank()) {
                    add("-s"); add(deviceSerial)
                }
                add("logcat"); add("-v"); add("raw"); add("-s"); add("$tag:V")
            }
            val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
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

    fun stop() {
        running.set(false)
        process?.destroy()
        process = null
        thread = null
    }

    fun isRunning(): Boolean = running.get()

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
