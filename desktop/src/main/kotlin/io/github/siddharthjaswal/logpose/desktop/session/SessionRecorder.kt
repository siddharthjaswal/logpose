package io.github.siddharthjaswal.logpose.desktop.session

import io.github.siddharthjaswal.logpose.capture.Bookmark
import io.github.siddharthjaswal.logpose.capture.ScreenshotRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What a recorded session knows about itself beyond its events — written beside the NDJSON as
 * `<base>.meta.json` and turned into a `.logpose` manifest at share time.
 */
@Serializable
data class SessionMeta(
    val serial: String,
    val deviceModel: String? = null,
    val androidVersion: String? = null,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val appPackage: String? = null,
    val libVersion: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val screenshots: List<ScreenshotRef> = emptyList(),
) {
    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; prettyPrint = true }
    }
}

/**
 * The three paths one recorded session owns, all sharing one `<timestamp>-<device>` base name:
 * the event log, its metadata, and a folder of screenshots.
 */
data class SessionFiles(val dir: File, val base: String) {
    val events: File get() = File(dir, "$base.ndjson")
    val meta: File get() = File(dir, "$base.meta.json")
    val screenshots: File get() = File(dir, "$base.screenshots")

    fun readMeta(): SessionMeta? = runCatching {
        SessionMeta.json.decodeFromString(SessionMeta.serializer(), meta.readText())
    }.getOrNull()

    companion object {
        const val KEEP_SESSIONS = 20

        /** `~/Library/Application Support/LogPose/sessions`. */
        fun defaultDir(appSupport: File): File = File(appSupport, "sessions")

        /** `20261013-140211-Pixel_9a` — sortable by name, safe as a file name. */
        fun baseName(startedAtMillis: Long, device: String): String {
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(startedAtMillis))
            val safe = device.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(40).ifBlank { "device" }
            return "$ts-$safe"
        }

        /**
         * Keeps the newest [keep] recorded sessions and deletes the rest (events, meta and
         * screenshots together). Newest by base name, which leads with the start time.
         */
        fun prune(dir: File, keep: Int = KEEP_SESSIONS, except: String? = null): List<String> {
            val bases = dir.listFiles { f -> f.isFile && f.name.endsWith(".ndjson") }.orEmpty()
                .map { it.name.removeSuffix(".ndjson") }.sortedDescending()
            val doomed = bases.drop(keep).filter { it != except }
            for (b in doomed) {
                val files = SessionFiles(dir, b)
                files.events.delete()
                files.meta.delete()
                files.screenshots.deleteRecursively()
            }
            return doomed
        }
    }
}

/**
 * Appends every event of a capture session to disk, so the share flow can read the **full** record
 * however long the session ran — the in-memory store caps at [CaptureSession.STORE_CAPACITY] and is
 * only the live view (PRD §5.2).
 *
 * All disk work happens on one background writer thread: [append] (called on the logcat reader
 * thread) only enqueues; the writer drains, buffers, and flushes every [flushEveryMillis] and on
 * [close]. Even creating the directory and pruning old sessions happens there, so nothing here can
 * stall the UI thread or the reader. Metadata changes (bookmarks, the hello, device facts) mark the
 * meta dirty; the writer rewrites `<base>.meta.json` atomically on its next turn.
 */
class SessionRecorder(
    val files: SessionFiles,
    initial: SessionMeta,
    private val flushEveryMillis: Long = 1_000,
    private val keepSessions: Int = SessionFiles.KEEP_SESSIONS,
) {
    @Volatile var meta: SessionMeta = initial
        private set

    private val queue = LinkedBlockingQueue<Any>()
    @Volatile private var closed = false
    @Volatile private var metaDirty = true
    private val done = CountDownLatch(1)
    @Volatile var linesWritten: Long = 0
        private set
    @Volatile var lastError: String? = null
        private set

    private val thread = Thread(::run, "logpose-recorder").apply { isDaemon = true; start() }

    private object Nudge
    private object Close
    private class FlushRequest(val latch: CountDownLatch)

    /** One event line (no trailing newline). Non-blocking; safe from any thread. */
    fun append(line: String) {
        if (!closed) queue.offer(line)
    }

    @Synchronized
    fun updateMeta(change: (SessionMeta) -> SessionMeta) {
        meta = change(meta)
        metaDirty = true
        queue.offer(Nudge)
    }

    fun addBookmark(b: Bookmark) = updateMeta { it.copy(bookmarks = it.bookmarks + b) }

    /**
     * Saves a screenshot beside the session and records it. Blocking file I/O — call it from a
     * background dispatcher (the screenshot itself came from adb on one).
     */
    fun addScreenshot(png: ByteArray, atMillis: Long): Pair<ScreenshotRef, File> {
        val name = synchronized(this) { "shot-${meta.screenshots.size + 1}-${atMillis}.png" }
        files.screenshots.mkdirs()
        val f = File(files.screenshots, name)
        f.writeBytes(png)
        val ref = ScreenshotRef(atMillis, name)
        updateMeta { it.copy(screenshots = it.screenshots + ref) }
        return ref to f
    }

    /** Blocks until everything appended so far is on disk (for the share flow and tests). */
    fun flush(timeoutMillis: Long = 5_000): Boolean {
        if (done.count == 0L) return true
        val l = CountDownLatch(1)
        queue.offer(FlushRequest(l))
        return l.await(timeoutMillis, TimeUnit.MILLISECONDS) || done.count == 0L
    }

    /** Ends the recording: stamps the end time, drains and closes on the writer thread. Non-blocking. */
    fun close(endedAtMillis: Long) {
        if (closed) return
        updateMeta { it.copy(endedAtMillis = endedAtMillis) }
        closed = true
        queue.offer(Close)
    }

    fun awaitClosed(timeoutMillis: Long = 5_000): Boolean = done.await(timeoutMillis, TimeUnit.MILLISECONDS)

    private fun run() {
        var writer: BufferedWriter? = null
        try {
            files.dir.mkdirs()
            SessionFiles.prune(files.dir, keepSessions - 1, except = files.base)
            writer = java.io.OutputStreamWriter(java.io.FileOutputStream(files.events, true), Charsets.UTF_8).buffered(64 * 1024)
            var lastFlush = System.currentTimeMillis()
            var dirtyLines = false
            loop@ while (true) {
                val item = queue.poll(flushEveryMillis, TimeUnit.MILLISECONDS)
                val batch = ArrayList<Any>()
                if (item != null) { batch.add(item); queue.drainTo(batch) }
                var flushReq: MutableList<FlushRequest>? = null
                var closing = false
                for (x in batch) {
                    when (x) {
                        is String -> { writer.write(x); writer.write('\n'.code); linesWritten++; dirtyLines = true }
                        is FlushRequest -> (flushReq ?: ArrayList<FlushRequest>().also { flushReq = it }).add(x)
                        Close -> closing = true
                        else -> Unit
                    }
                }
                val now = System.currentTimeMillis()
                if (closing || flushReq != null || (dirtyLines && now - lastFlush >= flushEveryMillis)) {
                    writer.flush(); dirtyLines = false; lastFlush = now
                }
                if (metaDirty) writeMeta()
                flushReq?.forEach { it.latch.countDown() }
                if (closing) break@loop
            }
        } catch (t: Throwable) {
            lastError = t.message ?: t.toString()
        } finally {
            runCatching { writer?.flush(); writer?.close() }
            runCatching { if (metaDirty) writeMeta() }
            // Anyone still waiting on a flush is released by `done`.
            done.countDown()
            while (true) { val x = queue.poll() ?: break; if (x is FlushRequest) x.latch.countDown() }
        }
    }

    private fun writeMeta() {
        metaDirty = false
        val tmp = File(files.dir, ".${files.meta.name}.part")
        tmp.writeText(SessionMeta.json.encodeToString(SessionMeta.serializer(), meta))
        Files.move(tmp.toPath(), files.meta.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
