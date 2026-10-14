package io.github.siddharthjaswal.logpose.desktop.emulators

import io.github.siddharthjaswal.logpose.capture.CaptureManifest
import io.github.siddharthjaswal.logpose.desktop.share.SlackSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.TimeZone

class EmulatorSdkAndNoteTest {
    private val home = "/Users/q"

    @Test fun `the emulator is found like adb - env, adb's own SDK, then the default`() {
        val have = setOf("/env/sdk/emulator/emulator", "/studio/sdk/emulator/emulator", "/Users/q/Library/Android/sdk/emulator/emulator")
        val exec: (File) -> Boolean = { it.path in have }
        assertEquals(File("/env/sdk"), EmulatorSdk.locate(null, { if (it == "ANDROID_HOME") "/env/sdk" else null }, home, exec)?.root)
        assertEquals(File("/studio/sdk"), EmulatorSdk.locate("/studio/sdk/platform-tools/adb", { null }, home, exec)?.root)
        assertEquals(File("/Users/q/Library/Android/sdk"), EmulatorSdk.locate("/Users/q/Library/Application Support/LogPose/platform-tools/adb", { null }, home, exec)?.root)
        assertNull(EmulatorSdk.locate(null, { null }, home) { false })
    }

    @Test fun `a guest-slimmed capture says so in the Slack summary, a stock one doesn't`() {
        val utc = TimeZone.getTimeZone("UTC")
        val slimmed = CaptureManifest(device = CaptureManifest.DeviceInfo("sdk gphone64 arm64", "16", "emulator-5554", "Android slimmed by LogPose: 2 background packages disabled, cached processes capped at 4"))
        assertTrue(SlackSummary.format(slimmed, emptyList(), "a.logpose", utc).contains("*Device:* sdk gphone64 arm64 · Android 16 · Android slimmed by LogPose (not stock)"))
        val stock = slimmed.copy(device = slimmed.device!!.copy(note = null))
        assertFalse(SlackSummary.format(stock, emptyList(), "a.logpose", utc).contains("slimmed"))
    }
}
