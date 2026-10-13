package io.github.siddharthjaswal.logpose.desktop.device

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ScreenCaptureTest {
    private fun png(fill: Color, dot: Color? = null): ByteArray {
        val img = BufferedImage(108, 240, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics(); g.color = fill; g.fillRect(0, 0, 108, 240)
        if (dot != null) { g.color = dot; g.fillRect(40, 100, 30, 30) }
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    @Test fun `a real screen is ok`() {
        assertTrue(ScreenCapture.check(png(Color.BLACK, Color.WHITE)) is ScreenCapture.Outcome.Ok)
        assertTrue(ScreenCapture.check(png(Color(240, 240, 240))) is ScreenCapture.Outcome.Ok)
    }

    @Test fun `a black screen — locked or FLAG_SECURE — is a plain message`() {
        assertEquals(ScreenCapture.Outcome.Failed(ScreenCapture.BLACK), ScreenCapture.check(png(Color.BLACK)))
    }

    @Test fun `adb errors instead of a png say what adb said`() {
        val o = ScreenCapture.check("error: device unauthorized.\nmore".toByteArray())
        assertEquals(ScreenCapture.Outcome.Failed("The phone didn't return a screenshot: error: device unauthorized."), o)
        assertEquals(ScreenCapture.Outcome.Failed("The phone didn't return a screenshot."), ScreenCapture.check(ByteArray(0)))
    }
}
