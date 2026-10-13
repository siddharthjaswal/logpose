package io.github.siddharthjaswal.logpose.capture

import io.github.siddharthjaswal.logpose.logcat.ControlMessage
import io.github.siddharthjaswal.logpose.logcat.TransactionParser
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.store.EventStore
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A capture as read from disk — a `.logpose` bundle or a bare `.ndjson` (the library's
 * `capture.ndjson` export, or one of the Mac app's session recordings).
 *
 * [lines] are kept verbatim and in file order; nothing is decoded until asked. Loading is
 * **replay**: every line goes through a fresh [TransactionParser] — the parser the live capture
 * uses — so a file lands in a store exactly as the session it came from did, in-place updates
 * (a response landing on its request) and app-restart boundaries (hellos) included.
 */
class Capture(
    /** Null for a bare `.ndjson`, which has no manifest. */
    val manifest: CaptureManifest?,
    val lines: List<String>,
    /** `screenshots/<name>` → PNG bytes, for the entries present in the bundle. */
    val screenshots: Map<String, ByteArray> = emptyMap(),
    /** The file name it was read from, for banners. */
    val sourceName: String = "",
) {
    /** The replay, computed once. */
    val replay: Replay by lazy { replay(lines) }

    /** Distinct timeline events in first-arrival order, each at its latest state. */
    val events: List<LogEvent> get() = replay.events

    /** Non-blank lines that were neither an event, a control message nor a chunk piece. */
    val malformedLines: Int get() = replay.malformedLines

    val bookmarks: List<Bookmark> get() = manifest?.bookmarks.orEmpty()

    /**
     * Feeds this capture into [store] the way a live session would: hellos become session
     * boundaries ([EventStore.noteHello]), events are added in file order. [onEvent] runs before
     * each add — the host's hook for warming its correlation cache, as its reader thread does.
     * [cancelled] is polled per line, so a host that moved on (back to live capture) stops the
     * replay instead of letting a file's events trickle into its live store.
     * Blocking and O(file): call it off the UI thread.
     */
    fun replayInto(store: EventStore, cancelled: () -> Boolean = { false }, onEvent: (LogEvent) -> Unit = {}) {
        val parser = TransactionParser()
        parser.onControl = { msg ->
            if (msg is ControlMessage.DeviceHello) store.noteHello(msg.hello.processId, msg.hello.pkg, msg.hello.libVersion)
        }
        for (line in lines) {
            if (cancelled()) return
            parser.accept(line)?.let { event ->
                onEvent(event)
                store.add(event)
            }
        }
    }

    /**
     * The decoded capture.
     *
     * @param hellosBefore event id → the app-run handshakes that arrived just before that event
     *   first appeared, which is all a re-export needs to put session boundaries back where they were.
     * @param trailingHellos handshakes after the last new event.
     */
    data class Replay(
        val events: List<LogEvent>,
        val hellos: List<Hello>,
        val hellosBefore: Map<String, List<Hello>>,
        val trailingHellos: List<Hello>,
        val malformedLines: Int,
    )

    companion object {
        fun replay(lines: List<String>): Replay {
            val parser = TransactionParser()
            val pendingHellos = ArrayList<Hello>()
            val allHellos = ArrayList<Hello>()
            var control = false
            parser.onControl = { msg ->
                control = true
                if (msg is ControlMessage.DeviceHello) {
                    pendingHellos.add(msg.hello); allHellos.add(msg.hello)
                }
            }
            val byId = LinkedHashMap<String, LogEvent>()
            val before = HashMap<String, List<Hello>>()
            var malformed = 0
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                control = false
                val event = parser.accept(line)
                if (event != null) {
                    if (event.id !in byId && pendingHellos.isNotEmpty()) {
                        before[event.id] = pendingHellos.toList()
                        pendingHellos.clear()
                    }
                    byId[event.id] = event
                } else if (!control && !isChunkPiece(line)) {
                    malformed++
                }
            }
            return Replay(byId.values.toList(), allHellos, before, pendingHellos.toList(), malformed)
        }

        // Pieces of a chunked payload legitimately return nothing until the last one lands.
        private fun isChunkPiece(line: String) =
            line.startsWith("{") && line.contains("\"seq\"") && line.contains("\"total\"")
    }
}

/**
 * Reads `.logpose` bundles and bare `.ndjson` captures, defensively: these files arrive from Slack.
 *
 *  - **Zip-slip**: an entry whose name is absolute, has a `..` segment or a backslash is refused
 *    outright — nothing is ever extracted to disk, but a bundle that tries is not one we wrote.
 *  - **Size caps** per entry, enforced while reading (a zip's declared sizes can lie), plus an
 *    entry-count cap, so a crafted bundle cannot exhaust memory.
 *  - **Forward compatibility**: unknown entries and unknown manifest keys are ignored; only a newer
 *    [CaptureManifest.formatVersion] is refused.
 *  - **Malformed lines** are skipped and counted ([Capture.malformedLines]), never fatal.
 */
object CaptureReader {

    const val MAX_EVENTS_BYTES: Long = 256L * 1024 * 1024
    const val MAX_MANIFEST_BYTES: Long = 1L * 1024 * 1024
    const val MAX_SCREENSHOT_BYTES: Long = 16L * 1024 * 1024
    const val MAX_SCREENSHOTS_TOTAL: Long = 256L * 1024 * 1024
    const val MAX_ENTRIES = 2_000

    fun read(file: File): Capture {
        if (!file.isFile) throw CaptureFormatException("${file.name} is not a file.")
        return file.inputStream().use { read(it, file.name) }
    }

    fun read(input: InputStream, name: String = ""): Capture {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(4)
        val magic = ByteArray(4)
        val n = buffered.readNBytes(magic, 0, 4)
        buffered.reset()
        val isZip = n == 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte() &&
            magic[2] == 3.toByte() && magic[3] == 4.toByte()
        return if (isZip) readZip(buffered, name) else readNdjson(buffered, name)
    }

    private fun readNdjson(input: InputStream, name: String): Capture {
        val bytes = readBounded(input, MAX_EVENTS_BYTES, name.ifBlank { "capture" })
        return Capture(null, splitLines(bytes), sourceName = name)
    }

    private fun readZip(input: InputStream, name: String): Capture {
        var manifestBytes: ByteArray? = null
        var eventsBytes: ByteArray? = null
        val shots = LinkedHashMap<String, ByteArray>()
        var shotsTotal = 0L
        val seen = HashSet<String>()
        ZipInputStream(input).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw CaptureFormatException("$name has more than $MAX_ENTRIES entries.")
                val entryName = entry.name
                checkEntryName(entryName)
                if (entry.isDirectory) continue
                if (!seen.add(entryName)) throw CaptureFormatException("$name lists $entryName twice.")
                when {
                    entryName == CaptureFormat.MANIFEST -> manifestBytes = readBounded(zip, MAX_MANIFEST_BYTES, entryName)
                    entryName == CaptureFormat.EVENTS -> eventsBytes = readBounded(zip, MAX_EVENTS_BYTES, entryName)
                    entryName.startsWith(CaptureFormat.SCREENSHOTS_DIR) -> {
                        val file = entryName.removePrefix(CaptureFormat.SCREENSHOTS_DIR)
                        // Anything not a flat PNG name is a newer writer's business, not ours.
                        if (CaptureFormat.isSafeScreenshotName(file)) {
                            val bytes = readBounded(zip, MAX_SCREENSHOT_BYTES, entryName)
                            shotsTotal += bytes.size
                            if (shotsTotal > MAX_SCREENSHOTS_TOTAL) throw CaptureFormatException("$name's screenshots exceed the size limit.")
                            shots[file] = bytes
                        }
                    }
                    else -> Unit // unknown entry: forward-compat, ignored
                }
                zip.closeEntry()
            }
        }
        val manifest = manifestBytes?.let { bytes ->
            val m = runCatching { CaptureFormat.json.decodeFromString(CaptureManifest.serializer(), bytes.decodeToString()) }
                .getOrElse { throw CaptureFormatException("$name has an unreadable manifest: ${it.message}") }
            if (m.formatVersion > CaptureFormat.VERSION) {
                throw CaptureFormatException(
                    "$name was written in capture format ${m.formatVersion}; this LogPose reads up to ${CaptureFormat.VERSION}. Update LogPose to open it.",
                )
            }
            m
        }
        val events = eventsBytes ?: throw CaptureFormatException("$name has no ${CaptureFormat.EVENTS} — not a LogPose capture.")
        return Capture(manifest, splitLines(events), shots, name)
    }

    /** Refuses names that would escape an extraction root, even though we never extract. */
    internal fun checkEntryName(entryName: String) {
        val bad = entryName.isEmpty() || entryName.startsWith("/") || entryName.contains('\\') ||
            entryName.contains('\u0000') || (entryName.length > 1 && entryName[1] == ':') ||
            entryName.split('/').any { it == ".." }
        if (bad) throw CaptureFormatException("Refusing unsafe entry name in capture: '$entryName'.")
    }

    private fun readBounded(input: InputStream, limit: Long, what: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            total += r
            if (total > limit) throw CaptureFormatException("$what is larger than ${limit / (1024 * 1024)} MB.")
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    private fun splitLines(bytes: ByteArray): List<String> =
        bytes.decodeToString().lineSequence().map { it.trimEnd('\r') }.filter { it.isNotBlank() }.toList()

    /** Whether a file name looks like something [read] opens — for file choosers. */
    fun accepts(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".${CaptureFormat.EXTENSION}") || lower.endsWith(".ndjson") || lower.endsWith(".jsonl")
    }
}

/** The one place an event or a hello becomes a capture line. */
object CaptureLines {
    fun of(event: LogEvent): String = of(event.envelope)
    fun of(envelope: Envelope): String = CaptureFormat.lineJson.encodeToString(Envelope.serializer(), envelope)
    fun of(hello: Hello): String = CaptureFormat.lineJson.encodeToString(Hello.serializer(), hello)

    /**
     * A replay flattened back to lines — one per distinct event at its final state, with each
     * app-run handshake put back just before the first event of its run. [included] narrows it
     * to a slice (a share scope): a run whose events were all left out loses its hello too, and
     * the first included event of a run still gets its run's hello even when the event that
     * originally followed it was cut. [envelopeOf] is where a share scrubs each event on its way out.
     */
    fun of(
        replay: Capture.Replay,
        included: Set<String>? = null,
        envelopeOf: (LogEvent) -> Envelope = { it.envelope },
    ): List<String> = buildList {
        var pending: List<Hello> = emptyList()
        for (e in replay.events) {
            replay.hellosBefore[e.id]?.let { pending = it }
            if (included != null && e.id !in included) continue
            pending.forEach { add(of(it)) }
            pending = emptyList()
            add(of(envelopeOf(e)))
        }
        if (included == null) replay.trailingHellos.forEach { add(of(it)) }
    }
}
