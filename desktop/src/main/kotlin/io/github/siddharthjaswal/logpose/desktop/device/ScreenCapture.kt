package io.github.siddharthjaswal.logpose.desktop.device

import io.github.siddharthjaswal.logpose.logcat.Adb
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Device screenshots for the session (PRD §5.2): `adb -s <serial> exec-out screencap -p`, which
 * streams a PNG on stdout. Blocking process I/O — call [capture] on `Dispatchers.IO`.
 *
 * Two failures a tester actually meets get a plain sentence instead of a broken image:
 * a locked phone or an app that sets FLAG_SECURE comes back **all black**, and adb itself can
 * answer with an error message instead of a PNG.
 */
object ScreenCapture {

    sealed interface Outcome {
        data class Ok(val png: ByteArray) : Outcome
        data class Failed(val message: String) : Outcome
    }

    const val BLACK =
        "The phone sent back a black screen — it's locked, or the app blocks screenshots (a secure screen). Unlock it, or try another screen."

    fun capture(serial: String, timeoutSeconds: Long = 10): Outcome {
        val adb = Adb.resolve() ?: return Outcome.Failed("adb isn't available.")
        return runCatching {
            val proc = ProcessBuilder(Adb.baseCmd(adb, serial) + listOf("exec-out", "screencap", "-p")).start()
            proc.outputStream.close()
            var bytes = ByteArray(0)
            var err = ""
            val out = Thread { bytes = runCatching { proc.inputStream.readBytes() }.getOrDefault(ByteArray(0)) }.apply { isDaemon = true; start() }
            val errT = Thread { err = runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault("") }.apply { isDaemon = true; start() }
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return Outcome.Failed("The phone didn't answer within $timeoutSeconds seconds.")
            }
            out.join(2_000); errT.join(1_000)
            check(bytes, err)
        }.getOrElse { Outcome.Failed("Couldn't take a screenshot: ${it.message ?: it}") }
    }

    /** Judges what adb returned. Pure apart from decoding the image. */
    fun check(bytes: ByteArray, stderr: String = ""): Outcome {
        if (!isPng(bytes)) {
            val said = (stderr.ifBlank { bytes.take(200).toByteArray().decodeToString() }).trim().lineSequence().firstOrNull()?.take(160).orEmpty()
            return Outcome.Failed(
                if (said.isBlank()) "The phone didn't return a screenshot." else "The phone didn't return a screenshot: $said",
            )
        }
        val img = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
            ?: return Outcome.Failed("The screenshot couldn't be read.")
        if (isBlack(img)) return Outcome.Failed(BLACK)
        return Outcome.Ok(bytes)
    }

    fun isPng(b: ByteArray): Boolean =
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    /** Samples a 24×24 grid; every sample near-black means the screen was withheld. */
    fun isBlack(img: java.awt.image.BufferedImage): Boolean {
        val n = 24
        for (yi in 0 until n) for (xi in 0 until n) {
            val x = (img.width - 1) * xi / (n - 1)
            val y = (img.height - 1) * yi / (n - 1)
            val rgb = img.getRGB(x, y)
            val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
            if (r > 10 || g > 10 || b > 10) return false
        }
        return true
    }
}
