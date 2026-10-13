package io.github.siddharthjaswal.logpose.desktop.share

import io.github.siddharthjaswal.logpose.capture.Capture
import io.github.siddharthjaswal.logpose.capture.CaptureManifest
import io.github.siddharthjaswal.logpose.capture.CaptureReader
import io.github.siddharthjaswal.logpose.capture.CaptureWriter
import io.github.siddharthjaswal.logpose.desktop.session.SessionRecorder
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

/**
 * The blocking half of sharing: reading the full record off disk, writing the bundle, and the
 * macOS glue (clipboard, Finder). Every function here is called on `Dispatchers.IO`, except the
 * clipboard ones, which AWT wants on any thread but costs nothing.
 */
object ShareIo {

    /**
     * The full disk record of a recorded session — flushed first, so a share taken mid-session
     * includes the last second. This is what makes long sessions shareable past the store's cap.
     */
    fun fromRecorder(rec: SessionRecorder, nowMillis: Long): ShareSource {
        rec.flush()
        val capture = if (rec.files.events.isFile) CaptureReader.read(rec.files.events) else Capture(null, emptyList())
        val meta = rec.meta
        return ShareSource(
            capture = capture,
            device = CaptureManifest.DeviceInfo(meta.deviceModel, meta.androidVersion, meta.serial),
            app = meta.appPackage?.let { CaptureManifest.AppInfo(it, meta.libVersion) },
            startedAtMillis = meta.startedAtMillis,
            endedAtMillis = meta.endedAtMillis ?: nowMillis,
            bookmarks = meta.bookmarks,
            screenshots = meta.screenshots,
            loadScreenshot = { name -> File(rec.files.screenshots, name).takeIf { it.isFile }?.readBytes() },
        )
    }

    /** An opened capture re-shared (scrubbed again, or cut down further). */
    fun fromCapture(capture: Capture): ShareSource {
        val m = capture.manifest
        val times = capture.events.map { it.timestampMillis }.filter { it > 0 }
        val hello = capture.replay.hellos.lastOrNull()
        return ShareSource(
            capture = capture,
            device = m?.device,
            app = m?.app ?: hello?.let { CaptureManifest.AppInfo(it.pkg, it.libVersion) },
            startedAtMillis = m?.session?.startMillis?.takeIf { it > 0 } ?: times.minOrNull() ?: 0,
            endedAtMillis = m?.session?.endMillis?.takeIf { it > 0 } ?: capture.events.maxOfOrNull { it.envelope.endedAt ?: it.timestampMillis } ?: 0,
            bookmarks = m?.bookmarks.orEmpty(),
            screenshots = m?.screenshots.orEmpty(),
        )
    }

    fun export(source: ShareSource, preview: SharePreview, dir: File, writtenBy: String, nowMillis: Long): File {
        val manifest = SharePlanner.manifest(source, preview, writtenBy, nowMillis)
        val shots = preview.screenshots.mapNotNull { ref -> source.loadScreenshot(ref.file)?.let { ref.file to it } }.toMap()
        val target = SharePlanner.uniqueTarget(dir, SharePlanner.fileName(source.app, source.device, nowMillis))
        return CaptureWriter.write(target, manifest, preview.lines, shots)
    }

    fun downloadsDir(): File = File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory }
        ?: File(System.getProperty("user.home"))

    fun copyText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    /** Puts the file itself on the clipboard (a file list — what Finder and Slack accept on paste). */
    fun copyFile(file: File) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(FileTransferable(listOf(file)), null)
    }

    /** Selects the file in a Finder window. */
    fun revealInFinder(file: File) {
        val d = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        if (d != null && d.isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
            d.browseFileDirectory(file)
        } else {
            ProcessBuilder("open", "-R", file.absolutePath).start()
        }
    }
}

/** A `java.io.File` list for drag-and-drop and the clipboard. */
class FileTransferable(private val files: List<File>) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return files
    }
}
