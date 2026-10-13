package io.github.siddharthjaswal.logpose.desktop.wireless

import io.github.siddharthjaswal.logpose.logcat.Adb
import java.util.concurrent.TimeUnit

/**
 * The adb verbs wireless setup needs, behind an interface so the pairing driver and the reconnect
 * loop are tested against canned outputs. Every call is **blocking process I/O**: callers run it on
 * `Dispatchers.IO`, never on the UI thread.
 */
interface AdbCommands {
    /** Runs `adb <args>` and returns its combined stdout+stderr, or null if adb couldn't run / timed out. */
    fun run(args: List<String>, timeoutSeconds: Long = 10): String?

    fun mdnsCheck(): AdbOutput.MdnsCheck =
        run(listOf("mdns", "check"), 5)?.let(AdbOutput::parseMdnsCheck) ?: AdbOutput.MdnsCheck.Unavailable("adb did not answer")

    fun mdnsServices(): List<AdbOutput.MdnsService> =
        run(listOf("mdns", "services"), 5)?.let(AdbOutput::parseMdnsServices).orEmpty()

    /** `adb pair` waits on the phone; 20 s covers a slow handshake without hanging the sheet. */
    fun pair(address: String, code: String): AdbOutput.PairResult =
        run(listOf("pair", address, code), 20)?.let(AdbOutput::parsePair)
            ?: AdbOutput.PairResult.Unreachable("adb pair timed out")

    /**
     * `adb connect` to an unroutable address blocks for its own TCP timeout (observed > 20 s), so it
     * is capped here; a timeout reads as unreachable.
     */
    fun connect(address: String): AdbOutput.ConnectResult =
        run(listOf("connect", address), 8)?.let(AdbOutput::parseConnect)
            ?: AdbOutput.ConnectResult.Unreachable("timed out connecting to $address")

    fun disconnect(address: String) {
        run(listOf("disconnect", address), 5)
    }

    fun devices(): List<Adb.DeviceInfo> = run(listOf("devices", "-l"), 5)?.let(Adb::parseDevices).orEmpty()

    fun shell(serial: String, command: String): String? = run(listOf("-s", serial, "shell", command), 8)

    fun tcpip(serial: String, port: Int): String? = run(listOf("-s", serial, "tcpip", port.toString()), 10)

    /** The real thing: core's resolved adb (the Mac app pins it via `Adb.explicitPath`). */
    object Real : AdbCommands {
        override fun run(args: List<String>, timeoutSeconds: Long): String? {
            val adb = Adb.resolve() ?: return null
            return runCatching {
                val proc = ProcessBuilder(listOf(adb) + args).redirectErrorStream(true).start()
                proc.outputStream.close() // `adb pair` without a code would prompt on stdin; never let it wait
                val out = StringBuilder()
                val reader = Thread {
                    runCatching { proc.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
                }.apply { isDaemon = true; start() }
                if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                    return@runCatching null
                }
                reader.join(1_000)
                synchronized(out) { out.toString() }
            }.getOrNull()
        }
    }
}
