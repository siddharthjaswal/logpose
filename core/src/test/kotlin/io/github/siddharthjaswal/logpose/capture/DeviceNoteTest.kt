package io.github.siddharthjaswal.logpose.capture

import io.github.siddharthjaswal.logpose.model.Hello
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `device.note` — a not-stock device (a guest-slimmed emulator) — is an addition, readable both ways. */
class DeviceNoteTest {
    @TempDir lateinit var dir: File

    private val line = CaptureLines.of(Hello(pkg = "in.shadowfax.gandalf", libVersion = "1.7.4", processId = "p1"))

    @Test fun `a note survives write and read`() {
        val note = "Android slimmed by LogPose: 41 background packages disabled, cached processes capped at 4"
        val m = CaptureManifest(writtenBy = "LogPose for Mac 1.0.0", device = CaptureManifest.DeviceInfo("sdk gphone64 arm64", "16", "emulator-5554", note))
        val c = CaptureReader.read(CaptureWriter.write(File(dir, "n.logpose"), m, listOf(line)))
        assertEquals(note, c.manifest?.device?.note)
    }

    @Test fun `a manifest from before the note reads as no note`() {
        val old = """{"formatVersion":1,"writtenBy":"LogPose for Mac 1.0.0","device":{"model":"Pixel 9a","androidVersion":"16","serial":"x"}}"""
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry(CaptureFormat.MANIFEST)); z.write(old.toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry(CaptureFormat.EVENTS)); z.write(line.toByteArray()); z.closeEntry()
        }
        val c = CaptureReader.read(bos.toByteArray().inputStream(), "old.logpose")
        assertEquals("Pixel 9a", c.manifest?.device?.model)
        assertNull(c.manifest?.device?.note)
    }
}
