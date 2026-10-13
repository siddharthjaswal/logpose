package io.github.siddharthjaswal.logpose.desktop.wireless

import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.settings.FileKeyValueStore
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LegacySwitchTest {
    private val serial = "37211FDJH00ABC"
    private val route = "192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.42\n"

    @Test fun `reads the wifi ip over the cable, tcpip 5555, then connects`() = runBlocking {
        val t = FakeTime()
        var attempts = 0
        val adb = FakeAdb { args ->
            when {
                args == listOf("-s", serial, "shell", "ip route") -> route
                args == listOf("-s", serial, "tcpip", "5555") -> "restarting in TCP mode port: 5555\n"
                args == listOf("connect", "192.168.1.42:5555") ->
                    if (++attempts < 2) "failed to connect to '192.168.1.42:5555': Connection refused" else "connected to 192.168.1.42:5555"
                else -> error("unexpected $args")
            }
        }
        assertEquals(LegacySwitch.Result.Connected("192.168.1.42:5555", "192.168.1.42:5555"), LegacySwitch(adb, t.sleep).switch(serial))
        assertEquals(0, adb.count("-s", serial, "shell", "ip addr show wlan0")) // route was enough
    }

    @Test fun `falls back to ip addr, and reports no wifi`() = runBlocking {
        val t = FakeTime()
        val noRoute = FakeAdb { args ->
            when (args.getOrNull(3)) {
                "ip route" -> "10.1.2.0/26 dev rmnet_data1 proto kernel scope link src 10.1.2.3\n"
                "ip addr show wlan0" -> "Device \"wlan0\" does not exist.\n"
                else -> error("unexpected $args")
            }
        }
        assertEquals(LegacySwitch.Result.NoWifi, LegacySwitch(noRoute, t.sleep).switch(serial))
        assertEquals(0, noRoute.count("-s", serial, "tcpip"))
    }

    @Test fun `tcpip refusal and an unreachable phone are distinct failures`() = runBlocking {
        val t = FakeTime()
        val refuses = FakeAdb { args -> if (args.getOrNull(3) == "ip route") route else "error: closed" }
        assertTrue(LegacySwitch(refuses, t.sleep).switch(serial) is LegacySwitch.Result.TcpipFailed)

        val isolated = FakeAdb { args ->
            when (args.getOrNull(2)) {
                "shell" -> route
                "tcpip" -> "restarting in TCP mode port: 5555"
                else -> null // connect timed out (client isolation)
            }
        }
        val r = LegacySwitch(isolated, t.sleep).switch(serial)
        assertTrue(r is LegacySwitch.Result.ConnectFailed, "$r")
        assertEquals(5, isolated.count("connect"))
        assertTrue(LegacySwitch.describe(r).contains("same Wi-Fi"))
    }
}

class RememberedDevicesTest {
    private val tls = RememberedDevice("adb-14141FDF600081-QoXtBe", "Pixel 9a", RememberedDevice.Kind.TLS, "192.168.1.20:41234", "adb-14141FDF600081-QoXtBe", 1)
    private val legacy = RememberedDevice("usb:R58M123", "Galaxy A51", RememberedDevice.Kind.LEGACY, "192.168.1.31:5555", null, 2)

    @Test fun `round-trips through the real settings file`(@TempDir dir: File) {
        val file = RememberedDevices.settingsFile(dir)
        RememberedDevices(FileKeyValueStore(file)).apply {
            remember(tls)
            remember(legacy)
            remember(tls.copy(name = "Pixel 9a (work)")) // upsert by id, no duplicate
        }
        assertTrue(file.isFile)
        val reread = RememberedDevices(FileKeyValueStore(file)).all()
        assertEquals(listOf(legacy, tls.copy(name = "Pixel 9a (work)")), reread)
    }

    @Test fun `forget and update address`() {
        val store = KeyValueStore.InMemory()
        val r = RememberedDevices(store)
        r.remember(tls); r.remember(legacy)
        r.updateAddress(tls.id, "192.168.1.20:45555")
        assertEquals("192.168.1.20:45555", r.all().first { it.id == tls.id }.address)
        r.forget(tls.id)
        assertEquals(listOf(legacy), r.all())
        r.forget(legacy.id)
        assertNull(store.get(RememberedDevices.KEY)) // empty list leaves no key behind
    }

    @Test fun `corrupt value reads as nothing remembered`() {
        assertEquals(emptyList<RememberedDevice>(), RememberedDevices.decode("{not json"))
        assertEquals(emptyList<RememberedDevice>(), RememberedDevices.decode(null))
    }

    @Test fun `matches a transport by guid prefix or by address`() {
        assertTrue(tls.matches("adb-14141FDF600081-QoXtBe._adb-tls-connect._tcp"))
        assertTrue(tls.matches("192.168.1.20:41234"))
        assertFalse(tls.matches("192.168.1.20:5555"))
        assertTrue(legacy.matches("192.168.1.31:5555"))
        assertFalse(legacy.matches("R58M123")) // the cable transport is not the Wi-Fi one
    }
}

class ReconnectTest {
    private val tls = RememberedDevice("adb-A-1", "Pixel 9a", RememberedDevice.Kind.TLS, "192.168.1.20:41234", "adb-A-1")
    private val legacy = RememberedDevice("usb:B", "Galaxy", RememberedDevice.Kind.LEGACY, "192.168.1.31:5555")

    @Test fun `only absent devices are due, with exponential backoff capped at the max`() {
        val s = ReconnectScheduler(baseMillis = 5_000, maxMillis = 60_000)
        val all = listOf(tls, legacy)
        assertEquals(listOf(legacy), s.due(0, all, presentIds = setOf(tls.id)))
        val gaps = mutableListOf<Long>()
        var now = 0L
        repeat(6) {
            s.record(legacy.id, now, success = false)
            val next = s.nextAttemptAt(legacy.id)!!
            gaps += next - now
            assertEquals(emptyList<RememberedDevice>(), s.due(next - 1, all, setOf(tls.id)))
            assertEquals(listOf(legacy), s.due(next, all, setOf(tls.id)))
            now = next
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), gaps)
    }

    @Test fun `a device coming back resets its backoff`() {
        val s = ReconnectScheduler()
        s.record(legacy.id, 0, success = false)
        s.record(legacy.id, 5_000, success = false)
        s.due(6_000, listOf(legacy), presentIds = setOf(legacy.id))
        assertNull(s.nextAttemptAt(legacy.id))
        assertEquals(listOf(legacy), s.due(6_001, listOf(legacy), emptySet()))
    }

    @Test fun `tls reconnects via the mdns connect port, which moved`() {
        val adb = FakeAdb { args -> if (args.first() == "connect") "connected to ${args[1]}" else error("$args") }
        val services = listOf(AdbOutput.MdnsService("adb-A-1", AdbOutput.ServiceType.CONNECT, "192.168.1.20", 45555))
        val o = Reconnector(adb).attempt(tls, services, mdnsWorks = true, devices = emptyList())
        assertEquals(Reconnector.Outcome(true, "192.168.1.20:45555", "192.168.1.20:45555"), o)
    }

    @Test fun `tls not advertising is not dialled when mdns works, but is when mdns is broken`() {
        val adb = FakeAdb { args -> if (args.first() == "connect") "failed to connect to '${args[1]}': Connection refused" else error("$args") }
        assertEquals(Reconnector.Outcome(false, null), Reconnector(adb).attempt(tls, emptyList(), mdnsWorks = true, devices = emptyList()))
        assertEquals(0, adb.count("connect"))
        val o = Reconnector(adb).attempt(tls, emptyList(), mdnsWorks = false, devices = emptyList())
        assertEquals(Reconnector.Outcome(false, "192.168.1.20:41234"), o)
        assertEquals(1, adb.count("connect"))
    }

    @Test fun `legacy dials its fixed address, dropping a stale offline transport first`() {
        val adb = FakeAdb { args ->
            when (args.first()) {
                "disconnect" -> "disconnected ${args[1]}"
                "connect" -> "connected to ${args[1]}"
                else -> error("$args")
            }
        }
        val o = Reconnector(adb).attempt(legacy, emptyList(), true, listOf(Adb.DeviceInfo("192.168.1.31:5555", "offline")))
        assertTrue(o.connected)
        assertEquals(listOf(listOf("disconnect", "192.168.1.31:5555"), listOf("connect", "192.168.1.31:5555")), adb.calls)
    }
}
