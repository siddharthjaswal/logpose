package io.github.siddharthjaswal.logpose.mock

import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * How an IDE → device command is framed on the wire, in one place.
 *
 * Both commands LogPose sends (a mock rule set and a push injection) travel to the library's
 * `MockCommandReceiver` as an `am broadcast` carrying base64 JSON, split across several
 * broadcasts when it doesn't fit a single one. This object owns that framing and nothing else:
 * no processes, no threads, no IntelliJ — so the part that has to match the device byte for byte
 * (slice size, extra names, base64 encoding) is unit-tested rather than trusted.
 *
 * Mirrors `logpose-android`'s `MockCommandReceiver` extras and `ChunkAssembly` reassembly:
 *
 * ```
 * am broadcast -n <pkg>/<receiver> -f 0x20 [--es cmd push] [--ei rev N]
 *              --ei seq <i> --ei total <n> --es payload <base64 slice>
 * ```
 *
 * `rev` orders rule sets and is what a rule-set stream reassembles under; a push has no
 * revision, so it is omitted entirely (the device then keys the stream on the extra's `-1`
 * default and tells back-to-back pushes apart by their second `seq 0`).
 */
object BroadcastCommand {

    /** The receiver the library registers in its manifest — real artifact only. */
    const val RECEIVER = "io.github.siddharthjaswal.logpose.mock.MockCommandReceiver"

    /** `FLAG_INCLUDE_STOPPED_PACKAGES`, so a freshly installed app still receives the command. */
    const val FLAG_INCLUDE_STOPPED_PACKAGES = "0x00000020"

    /**
     * Payload characters per broadcast. `am` arguments go through the shell, and a long single
     * argument is where this used to break; 2000 is the size the device's reassembly was written
     * against, so the two must move together.
     */
    const val SLICE_CHARS = 2000

    /** Command selector (the `cmd` extra). Absent means [CMD_RULES], which predates the extra. */
    const val CMD_RULES = "rules"
    const val CMD_PUSH = "push"

    /** Base64-encodes [json] and cuts it into the slices one broadcast each can carry. */
    fun slices(json: String): List<String> {
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        // A zero-length payload would produce an empty list and therefore no broadcast at all;
        // an empty rule set is a real command, so keep one (empty) slice.
        return if (encoded.isEmpty()) listOf("") else encoded.chunked(SLICE_CHARS)
    }

    /**
     * The `am broadcast` arguments for one slice, to be appended to an `adb [-s serial] shell`
     * prefix. [cmd] is omitted for a plain rule set so an older library — which knows no `cmd`
     * extra — sees exactly the command it always did; [revision] is omitted where the command
     * has none (a push).
     */
    fun args(
        target: String,
        cmd: String?,
        revision: Int?,
        seq: Int,
        total: Int,
        payload: String,
    ): List<String> = buildList {
        add("shell"); add("am"); add("broadcast")
        add("-n"); add("$target/$RECEIVER")
        add("-f"); add(FLAG_INCLUDE_STOPPED_PACKAGES)
        if (cmd != null) { add("--es"); add("cmd"); add(cmd) }
        if (revision != null) { add("--ei"); add("rev"); add(revision.toString()) }
        add("--ei"); add("seq"); add(seq.toString())
        add("--ei"); add("total"); add(total.toString())
        add("--es"); add("payload"); add(payload)
    }
}

/**
 * Runs one adb command and reports **why** it failed, or null when it worked.
 *
 * `am broadcast` happily exits 0 while printing `Error: …` (a missing app, a security exception),
 * so the output is read too — an exit code alone would report a push as sent that never arrived.
 * Shared by the rule-set pusher and the push injector so both judge failure identically.
 */
object AdbCommand {

    private const val PROCESS_TIMEOUT_SECONDS = 5L

    /** `am` reports plenty of failures on stdout with a zero exit code. */
    private val ERROR_MARKERS = listOf("Error:", "Exception", "Broadcast failed", "not found")

    fun run(cmd: List<String>): String? = runCatching {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        if (!p.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return "adb did not return within ${PROCESS_TIMEOUT_SECONDS}s"
        }
        // Read after exit: `am broadcast` output is a line or two, well within the pipe buffer,
        // so this can't deadlock the way draining a chatty process before waitFor could.
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        val exit = p.exitValue()
        when {
            exit != 0 -> "adb exited $exit${if (out.isEmpty()) "" else ": ${out.take(300)}"}"
            ERROR_MARKERS.any { out.contains(it, ignoreCase = true) } -> out.take(300)
            else -> null
        }
    }.getOrElse { t -> "could not run adb: ${t.message ?: t::class.java.simpleName}" }
}
