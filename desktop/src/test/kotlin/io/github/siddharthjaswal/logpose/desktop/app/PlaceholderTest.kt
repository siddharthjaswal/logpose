package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.desktop.session.QuietDetector.Verdict
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.logcat.Adb
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PlaceholderTest {
    private val ready = Adb.DeviceInfo("S1", "device", "Pixel_9a")
    private val running = SessionState.Running("S1", 0)

    @Test fun `the three failure states are distinct`() {
        assertEquals(Placeholder.NoAdb, Placeholder.resolve(false, emptyList(), null, SessionState.Idle, null, 0))
        assertEquals(Placeholder.NoDevice, Placeholder.resolve(true, emptyList(), null, SessionState.Idle, null, 0))
        assertEquals(Placeholder.NoLogPose("Pixel 9a"), Placeholder.resolve(true, listOf(ready), "Pixel 9a", running, Verdict.NO_LOGPOSE, 0))
    }

    @Test fun `the in-between states`() {
        assertEquals(Placeholder.DeviceNotReady, Placeholder.resolve(true, listOf(ready.copy(state = "unauthorized")), null, SessionState.Idle, null, 0))
        assertEquals(Placeholder.NotStarted("Pixel 9a"), Placeholder.resolve(true, listOf(ready), "Pixel 9a", SessionState.Idle, null, 0))
        assertEquals(Placeholder.Listening("Pixel 9a"), Placeholder.resolve(true, listOf(ready), "Pixel 9a", running, Verdict.LISTENING, 0))
        assertEquals(Placeholder.Ended("gone"), Placeholder.resolve(true, emptyList(), null, SessionState.Ended("S1", "gone"), null, 0))
    }

    @Test fun `rows always win`() {
        assertEquals(Placeholder.None, Placeholder.resolve(true, emptyList(), null, SessionState.Idle, null, 3))
    }
}
