package io.github.siddharthjaswal.logpose.desktop.wireless

import io.github.siddharthjaswal.logpose.desktop.app.WirelessController
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The switcher's *Disconnect* / *Reconnect* on Wi-Fi phones. */
class DisconnectTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterEach fun done() = scope.cancel()

    private val serial = "adb-R5CT20ABCDE-QoXtBe._adb-tls-connect._tcp"
    private val memory = RememberedDevice("adb-R5CT20ABCDE-QoXtBe", "Galaxy S23", RememberedDevice.Kind.TLS, "192.168.1.20:41234", "adb-R5CT20ABCDE-QoXtBe")

    private fun controller(adb: FakeAdb): WirelessController {
        val store = KeyValueStore.InMemory()
        RememberedDevices(store).remember(memory)
        return WirelessController(scope, store, adb)
    }

    private fun awaitCall(adb: FakeAdb, vararg prefix: String) {
        val until = System.currentTimeMillis() + 5_000
        while (adb.count(*prefix) == 0 && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @Test fun `disconnecting a remembered phone pauses its auto-reconnect until Reconnect`() {
        val adb = FakeAdb { "" }
        val c = controller(adb)
        c.disconnect(serial)
        awaitCall(adb, "disconnect", serial)
        assertEquals(1, adb.count("disconnect", serial))
        assertEquals(setOf(memory.id), c.ui.value.paused)
        c.reconnectNow(memory.id)
        assertTrue(c.ui.value.paused.isEmpty())
    }

    @Test fun `disconnecting a phone LogPose doesn't remember just disconnects it`() {
        val adb = FakeAdb { "" }
        val c = controller(adb)
        c.disconnect("192.168.1.77:5555")
        awaitCall(adb, "disconnect", "192.168.1.77:5555")
        assertEquals(1, adb.count("disconnect", "192.168.1.77:5555"))
        assertTrue(c.ui.value.paused.isEmpty())
    }
}
