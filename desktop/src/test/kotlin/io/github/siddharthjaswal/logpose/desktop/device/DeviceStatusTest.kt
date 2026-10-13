package io.github.siddharthjaswal.logpose.desktop.device

import io.github.siddharthjaswal.logpose.logcat.Adb
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeviceStatusTest {
    @Test fun `adb state words become instructions`() {
        assertEquals("Tap Allow on the phone's USB-debugging prompt", DeviceStatuses.of("unauthorized").label)
        assertEquals("Reconnect the cable", DeviceStatuses.of("offline").label)
        assertTrue(DeviceStatuses.of("unauthorized").actionNeeded)
        assertTrue(DeviceStatuses.of("device").ready)
        assertFalse(DeviceStatuses.of("offline").ready)
    }

    @Test fun `no raw state word leaks for the known states`() {
        for (s in listOf("unauthorized", "offline", "device")) {
            assertFalse(DeviceStatuses.of(s).label.contains(s), s)
        }
        assertTrue(DeviceStatuses.of("weird").label.contains("weird"))
    }

    @Test fun `display name prefers a readable model`() {
        assertEquals("Pixel 9a", DeviceStatuses.displayName(Adb.DeviceInfo("emulator-5554", "device", "Pixel_9a")))
        assertEquals("R58M", DeviceStatuses.displayName(Adb.DeviceInfo("R58M", "unauthorized")))
    }
}
