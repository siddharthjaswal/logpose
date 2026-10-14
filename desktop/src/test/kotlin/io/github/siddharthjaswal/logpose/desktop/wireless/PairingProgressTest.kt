package io.github.siddharthjaswal.logpose.desktop.wireless

import io.github.siddharthjaswal.logpose.desktop.wireless.PairingProgress.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingProgressTest {
    private val t0 = 1_000_000L

    @Test fun `QR stages with labels, statuses and elapsed times`() {
        val trace = PairingTrace(
            serviceName = "logpose-x", startedAt = t0,
            stageAt = mapOf(Stage.WAITING to t0, Stage.FOUND to t0 + 12_300, Stage.PAIRING to t0 + 12_300),
            foundVia = Via.BONJOUR, pairAddress = "192.168.1.10:42667",
        )
        val rows = PairingProgress.rows(PairingState.Pairing("192.168.1.10:42667", Via.BONJOUR), trace, t0 + 14_000)
        assertEquals(
            listOf(
                "Waiting for your phone to scan" to Status.DONE,
                "Phone found (via macOS Bonjour)" to Status.DONE,
                "Pairing with 192.168.1.10" to Status.ACTIVE,
                "Connecting…" to Status.PENDING,
                "Connected" to Status.PENDING,
            ),
            rows.map { it.label to it.status },
        )
        assertEquals(listOf(12_300L, null, 1_700L, null, null), rows.map { it.elapsedMillis })
    }

    @Test fun `waiting is the active first stage`() {
        val trace = PairingTrace(serviceName = "logpose-x", startedAt = t0, stageAt = mapOf(Stage.WAITING to t0))
        val rows = PairingProgress.rows(PairingState.WaitingForScan(8_000, 90_000), trace, t0 + 8_000)
        assertEquals("Waiting for your phone to scan…", rows[0].label)
        assertEquals(Status.ACTIVE, rows[0].status)
        assertEquals("Find the phone on the network", rows[1].label)
    }

    @Test fun `connected via adb auto-connect, and a failure marks the stage it died in`() {
        val done = PairingTrace(
            serviceName = "logpose-x", startedAt = t0, foundVia = Via.ADB, connectVia = Via.ADB_AUTO, pairAddress = "192.168.1.10:1",
            stageAt = mapOf(Stage.WAITING to t0, Stage.FOUND to t0 + 1, Stage.PAIRING to t0 + 1, Stage.CONNECTING to t0 + 2_000, Stage.CONNECTED to t0 + 3_000), endedAt = t0 + 3_000,
        )
        val rows = PairingProgress.rows(PairingState.Connected("s", "A059P", "g", null, Via.ADB_AUTO), done, t0 + 60_000)
        assertTrue(rows.all { it.status == Status.DONE })
        assertEquals("adb connected the phone on its own", rows[3].label)
        assertEquals(1_000L, rows[3].elapsedMillis)

        val stuck = done.copy(stageAt = done.stageAt - Stage.CONNECTED, endedAt = t0 + 22_000)
        val failed = PairingProgress.rows(PairingState.TimedOut(TimeoutCause.CONNECT_NOT_FOUND), stuck, t0 + 60_000)
        assertEquals(Status.FAILED, failed[3].status)
        assertEquals(20_000L, failed[3].elapsedMillis) // frozen at the end, not still counting
    }

    @Test fun `pairing-code flow starts at Pairing`() {
        val trace = PairingTrace(startedAt = t0, stageAt = mapOf(Stage.PAIRING to t0), pairAddress = "192.168.1.20:37755")
        val rows = PairingProgress.rows(PairingState.Pairing("192.168.1.20:37755"), trace, t0)
        assertEquals(listOf(Stage.PAIRING, Stage.CONNECTING, Stage.CONNECTED), rows.map { it.stage })
    }

    @Test fun `stall after 15 s without a new stage, only while running`() {
        val trace = PairingTrace(serviceName = "x", startedAt = t0, stageAt = mapOf(Stage.WAITING to t0))
        val waiting = PairingState.WaitingForScan(0, 90_000)
        assertFalse(PairingProgress.stalled(waiting, trace, t0 + 14_999))
        assertTrue(PairingProgress.stalled(waiting, trace, t0 + 15_000))
        assertFalse(PairingProgress.stalled(PairingState.TimedOut(TimeoutCause.PHONE_NOT_SEEN), trace, t0 + 60_000))
        assertEquals("1m 05s", PairingProgress.formatElapsed(65_000))
        assertEquals("9s", PairingProgress.formatElapsed(9_400))
    }
}
