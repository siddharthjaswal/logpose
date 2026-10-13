package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.capture.Bookmark
import io.github.siddharthjaswal.logpose.capture.CaptureReader
import io.github.siddharthjaswal.logpose.desktop.app.AppModel
import io.github.siddharthjaswal.logpose.desktop.session.CaptureSession
import io.github.siddharthjaswal.logpose.desktop.session.LineSource
import io.github.siddharthjaswal.logpose.desktop.session.SessionFiles
import io.github.siddharthjaswal.logpose.desktop.session.SessionMeta
import io.github.siddharthjaswal.logpose.desktop.session.SessionRecorder
import io.github.siddharthjaswal.logpose.desktop.share.ShareIo
import io.github.siddharthjaswal.logpose.desktop.share.ShareOptions
import io.github.siddharthjaswal.logpose.desktop.share.SharePlanner
import io.github.siddharthjaswal.logpose.desktop.share.ShareScope
import io.github.siddharthjaswal.logpose.desktop.share.SlackSummary
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * The M3 gate's file half, end to end without Slack: a QA session is recorded to disk (with a
 * bookmark and a screenshot), shared through the real share pipeline (scope → scrub → `.logpose`),
 * and the file is then opened twice — the way the plugin's *Open Capture File…* does it (core's
 * reader replayed into a plain `EventStore`, the panel's capacity) and through the Mac app's own
 * `AppModel.openCaptureFile` — and the two timelines must be identical.
 */
object M3Gate {

    class Result(
        val file: File,
        val recorded: List<LogEvent>,
        val plugin: List<LogEvent>,
        val desktop: List<LogEvent>,
        val model: AppModel,
        val summary: String,
    )

    private class FeedSource : LineSource {
        var onLine: (String) -> Unit = {}
        override fun start(onLine: (String) -> Unit, onError: (String) -> Unit, onStopped: () -> Unit) { this.onLine = onLine }
        override fun stop() {}
    }

    fun run(work: File, outDir: File, scope: ShareScope? = null): Result {
        // 1. A recorded session, as the app records one.
        val src = FeedSource()
        var rec: SessionRecorder? = null
        val sessionsDir = File(work, "sessions")
        val session = CaptureSession(sourceFor = { src }, clock = { Fixtures.START - 2_000 }, recorderFor = { serial, at ->
            SessionRecorder(SessionFiles(sessionsDir, SessionFiles.baseName(at, "Pixel_9a")), SessionMeta(serial, "Pixel 9a", "15", at), flushEveryMillis = 50)
                .also { rec = it }
        })
        session.start("37211FDJH00ABC")
        val lines = Fixtures.shareLines()
        lines.take(6).forEach(src.onLine)
        rec!!.addBookmark(Bookmark(Fixtures.START + 3_100, "tapped Accept here, spinner never stopped"))
        rec!!.addScreenshot(Fixtures.phonePng(), Fixtures.START + 3_200)
        lines.drop(6).forEach(src.onLine)
        val recorded = session.store.snapshot()
        session.stop()
        check(rec!!.awaitClosed()) { "recorder did not close" }
        val r = rec!!.also { it.updateMeta { m -> m.copy(endedAtMillis = Fixtures.START + 12_000) } }

        // 2. Share it through the real pipeline — scope, scrubber, preview, export.
        val source = ShareIo.fromRecorder(r, Fixtures.START + 12_000)
        val options = ShareOptions(scope = scope ?: ShareScope.default(source.bookmarks))
        val preview = SharePlanner.plan(source, options)
        outDir.mkdirs()
        val file = ShareIo.export(source, preview, outDir, "LogPose for Mac (M3 gate)", Fixtures.START + 12_000)
        val summary = SlackSummary.format(SharePlanner.manifest(source, preview, "LogPose for Mac (M3 gate)", Fixtures.START + 12_000), preview.events, file.name)

        // 3a. Open it as the plugin's Open Capture File does: read, then replay into the panel's store.
        val pluginStore = EventStore()
        CaptureReader.read(file).replayInto(pluginStore) { /* the panel warms correlation here */ }

        // 3b. Open it through the Mac app's own path.
        val model = runBlocking {
            val m = AppModel(this, sessionsDir = File(work, "app-sessions"), settings = KeyValueStore.InMemory())
            m.openCaptureFile(file)
            withTimeout(10_000) { while (m.viewing.value == null) delay(10) }
            coroutineContext.cancelChildren()
            m
        }
        return Result(file, recorded, pluginStore.snapshot(), model.session.store.snapshot(), model, summary)
    }
}
