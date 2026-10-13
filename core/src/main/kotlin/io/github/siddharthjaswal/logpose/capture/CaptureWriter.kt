package io.github.siddharthjaswal.logpose.capture

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a `.logpose` bundle: `manifest.json`, `events.ndjson` (one line per [eventLines] entry —
 * the library's exact line format), and `screenshots/<name>.png`.
 *
 * The file appears atomically (temp file + rename), so a half-written capture never sits in
 * Downloads looking like a real one. Pure I/O, no clock: the caller fills the manifest.
 */
object CaptureWriter {

    fun write(
        out: File,
        manifest: CaptureManifest,
        eventLines: List<String>,
        screenshots: Map<String, ByteArray> = emptyMap(),
    ): File {
        for (name in screenshots.keys) {
            require(CaptureFormat.isSafeScreenshotName(name)) { "Unsafe screenshot name: $name" }
        }
        // A manifest pointing at a missing file would be a broken capture; drop the dangling refs.
        val fixed = manifest.copy(screenshots = manifest.screenshots.filter { it.file in screenshots })
        out.absoluteFile.parentFile?.mkdirs()
        val tmp = File(out.absoluteFile.parentFile, ".${out.name}.part")
        try {
            tmp.outputStream().buffered().use { write(it, fixed, eventLines, screenshots) }
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
        return out
    }

    fun write(output: OutputStream, manifest: CaptureManifest, eventLines: List<String>, screenshots: Map<String, ByteArray>) {
        val zip = ZipOutputStream(output)
        zip.putNextEntry(ZipEntry(CaptureFormat.MANIFEST))
        zip.write(CaptureFormat.json.encodeToString(CaptureManifest.serializer(), manifest).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry(CaptureFormat.EVENTS))
        val nl = '\n'.code
        for (line in eventLines) {
            require('\n' !in line) { "An event line may not contain a newline." }
            zip.write(line.toByteArray())
            zip.write(nl)
        }
        zip.closeEntry()

        for ((name, bytes) in screenshots) {
            val entry = ZipEntry(CaptureFormat.SCREENSHOTS_DIR + name)
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }
}
