package io.github.siddharthjaswal.logpose.logcat

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Looks up what each pid is running *right now*, as its full process name (`com.acme`,
 * `com.acme:service`), or null when the pid is gone. Asynchronous: [answer] may arrive on another
 * thread, later. The store uses it to tell a restart from a second process: a new run of a package
 * only supersedes an older run whose process has died (or whose pid now runs something else).
 */
fun interface ProcessNames {
    fun lookup(pids: Collection<Int>, answer: (Map<Int, String?>) -> Unit)

    companion object {
        /** The process name in a `/proc/<pid>/cmdline`: argv[0] up to the first NUL, `:suffix` kept. */
        fun fromCmdline(raw: String?): String? =
            raw?.substringBefore('\u0000')?.trim()?.takeIf { it.isNotEmpty() && !it.contains(' ') && !it.contains('/') }
    }
}

/**
 * [ProcessNames] over `adb shell cat /proc/<pid>/cmdline`, on its own daemon thread — never the
 * caller's (the reader thread, or the EDT). An adb that fails answers null for that pid, which the
 * store reads as "gone": the pre-existing behaviour, still undone by the next sign of life.
 */
class AdbProcessNames(
    private val deviceSerial: () -> String?,
    private val run: (List<String>) -> String? = Adb::output,
    private val executor: ExecutorService = sharedExecutor,
) : ProcessNames {

    override fun lookup(pids: Collection<Int>, answer: (Map<Int, String?>) -> Unit) {
        val wanted = pids.toSet()
        runCatching {
            executor.execute {
                val adb = Adb.resolve()
                val base = adb?.let { Adb.baseCmd(it, deviceSerial()) }
                answer(wanted.associateWith { pid ->
                    base?.let { ProcessNames.fromCmdline(run(it + listOf("shell", "cat", "/proc/$pid/cmdline"))) }
                })
            }
        }.onFailure { answer(wanted.associateWith { null }) }
    }

    private companion object {
        val sharedExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "logpose-process-names").apply { isDaemon = true }
        }
    }
}

/** Runs [cmd] with a 5 s bound and returns stdout on exit 0, else null. Blocking: never on the EDT. */
fun Adb.output(cmd: List<String>): String? = runCatching {
    val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
    if (!p.waitFor(5, TimeUnit.SECONDS)) {
        p.destroyForcibly()
        return null
    }
    p.inputStream.readBytes().toString(Charsets.UTF_8).takeIf { p.exitValue() == 0 }
}.getOrNull()
