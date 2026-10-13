package io.github.siddharthjaswal.logpose.desktop.wireless

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairingFlowTest {
    private val code = QrPairingCode("logpose-Ab3dEf9hJk", "s3cretPw0rd1")
    private val guid = "adb-14141FDF600081-QoXtBe"

    private fun flow(adb: AdbCommands, t: FakeTime) =
        PairingFlow(adb, t.clock, t.sleep, discoveryTimeoutMillis = 90_000, connectTimeoutMillis = 20_000, pollMillis = 1_000)

    @Test fun `QR - discovering, pairing, connecting, connected via adb's auto-connect`() = runBlocking {
        val t = FakeTime()
        val start = t.now
        var paired = false
        val adb = FakeAdb { args ->
            when (args.take(2)) {
                listOf("mdns", "services") ->
                    if (t.now - start >= 5_000) MDNS_HEADER + "${code.serviceName}\t_adb-tls-pairing._tcp\t192.168.1.20:37755\n" else MDNS_HEADER
                listOf("pair", "192.168.1.20:37755") -> {
                    assertEquals(code.password, args[2])
                    paired = true
                    "Successfully paired to 192.168.1.20:37755 [guid=$guid]"
                }
                listOf("devices", "-l") -> "List of devices attached\n" +
                    (if (paired) "$guid._adb-tls-connect._tcp device product:tegu model:Pixel_9a device:tegu transport_id:3\n" else "")
                else -> error("unexpected $args")
            }
        }
        val seen = mutableListOf<PairingState>()
        val result = flow(adb, t).runQr(code) { seen += it }
        assertEquals(PairingState.Connected("$guid._adb-tls-connect._tcp", "Pixel 9a", guid, null), result)
        val kinds = seen.map { it::class.simpleName }.distinct()
        assertEquals(listOf("WaitingForScan", "Pairing", "Connecting", "Connected"), kinds)
        assertEquals(0, adb.count("connect")) // adb auto-connected; we never had to dial
    }

    @Test fun `QR - connects explicitly through the connect service when adb doesn't auto-connect`() = runBlocking {
        val t = FakeTime()
        var connected = false
        val adb = FakeAdb { args ->
            when (args.first()) {
                "mdns" -> MDNS_HEADER + "${code.serviceName}\t_adb-tls-pairing._tcp\t192.168.1.20:37755\n" +
                    "$guid\t_adb-tls-connect._tcp\t192.168.1.20:41234\n"
                "pair" -> "Successfully paired to 192.168.1.20:37755 [guid=$guid]"
                "connect" -> { assertEquals("192.168.1.20:41234", args[1]); connected = true; "connected to 192.168.1.20:41234" }
                "devices" -> "List of devices attached\n" + (if (connected) "192.168.1.20:41234 device model:SM_S918B\n" else "")
                else -> error("unexpected $args")
            }
        }
        val result = flow(adb, t).runQr(code) {}
        assertEquals(PairingState.Connected("192.168.1.20:41234", "SM S918B", guid, "192.168.1.20:41234"), result)
    }

    @Test fun `QR - a silent network times out as client isolation`() = runBlocking {
        val t = FakeTime()
        val adb = FakeAdb { if (it.first() == "mdns") MDNS_HEADER else error("unexpected $it") }
        val result = flow(adb, t).runQr(code) {}
        assertEquals(PairingState.TimedOut(TimeoutCause.NETWORK_SILENT), result)
        assertTrue(adb.count("mdns", "services") in 90..92) // polled once a second for the whole window
        assertEquals(0, adb.count("pair"))
    }

    @Test fun `QR - other phones visible but not ours is a different timeout`() = runBlocking {
        val t = FakeTime()
        val adb = FakeAdb { MDNS_HEADER + "adb-OTHER-xyz\t_adb-tls-connect._tcp\t192.168.1.77:40000\n" }
        assertEquals(PairingState.TimedOut(TimeoutCause.PHONE_NOT_SEEN), flow(adb, t).runQr(code) {})
    }

    @Test fun `QR - someone else's pairing service is ignored`() = runBlocking {
        val t = FakeTime()
        val adb = FakeAdb { MDNS_HEADER + "studio-SomeoneElse\t_adb-tls-pairing._tcp\t192.168.1.9:37000\n" }
        assertEquals(PairingState.TimedOut(TimeoutCause.PHONE_NOT_SEEN), flow(adb, t).runQr(code) {})
        assertEquals(0, adb.count("pair"))
    }

    @Test fun `code - wrong code and unreachable fail with their own reasons`() = runBlocking {
        val t = FakeTime()
        val wrong = FakeAdb { if (it.first() == "pair") "Failed: Wrong password or connection was dropped." else error("$it") }
        val r1 = flow(wrong, t).runCode("192.168.1.20:37755", "123456", null) {}
        assertEquals(FailReason.WRONG_CODE, (r1 as PairingState.Failed).reason)

        val gone = FakeAdb { if (it.first() == "pair") "Failed: Unable to start pairing client." else error("$it") }
        val r2 = flow(gone, t).runCode("192.168.1.20:37755", "123456", null) {}
        assertEquals(FailReason.UNREACHABLE, (r2 as PairingState.Failed).reason)

        val timeout = FakeAdb { null } // adb pair hung past its cap
        assertEquals(FailReason.UNREACHABLE, (flow(timeout, t).runCode("1.2.3.4:5", "123456", null) {} as PairingState.Failed).reason)
    }

    @Test fun `code - paired but no connect service times out asking for the address, then the address connects`() = runBlocking {
        val t = FakeTime()
        var connected = false
        val adb = FakeAdb { args ->
            when (args.first()) {
                "pair" -> "Successfully paired to 192.168.1.20:37755 [guid=$guid]"
                "mdns" -> MDNS_HEADER // mDNS blocked: the connect service never shows
                "devices" -> "List of devices attached\n" + (if (connected) "192.168.1.20:41234 device model:Pixel_9a\n" else "")
                "connect" -> { connected = true; "connected to ${args[1]}" }
                else -> error("unexpected $args")
            }
        }
        val first = flow(adb, t).runCode("192.168.1.20:37755", "123456", null) {}
        assertEquals(PairingState.TimedOut(TimeoutCause.CONNECT_NOT_FOUND), first)
        val second = flow(adb, t).runConnect("192.168.1.20:41234") {}
        assertTrue(second is PairingState.Connected, "$second")
    }

    @Test fun `connect refused by the phone is not authorized`() = runBlocking {
        val t = FakeTime()
        val adb = FakeAdb { args ->
            when (args.first()) {
                "devices" -> "List of devices attached\n"
                "connect" -> "failed to authenticate to 192.168.1.20:41234"
                else -> error("unexpected $args")
            }
        }
        val r = flow(adb, t).runConnect("192.168.1.20:41234") {}
        assertEquals(FailReason.NOT_AUTHORIZED, (r as PairingState.Failed).reason)
    }

    @Test fun `copy - every terminal state has words, problems are flagged`() {
        val states = listOf(
            PairingState.WaitingForScan(10_000, 90_000), PairingState.Pairing("1.2.3.4:5"), PairingState.Connecting(guid),
            PairingState.Connected("s", "Pixel 9a", guid, null),
        ) + TimeoutCause.entries.map { PairingState.TimedOut(it) } + FailReason.entries.map { PairingState.Failed(it, "detail") }
        for (s in states) {
            val line = PairingCopy.describe(s)
            assertTrue(line.title.isNotBlank() && line.body.isNotBlank(), "$s")
            assertEquals(s is PairingState.TimedOut || s is PairingState.Failed, line.problem, "$s")
        }
        assertEquals("Scan this code on your phone…", PairingCopy.describe(states[0]).title)
        assertTrue(PairingCopy.describe(PairingState.TimedOut(TimeoutCause.NETWORK_SILENT)).body.contains("client isolation"))
        assertTrue(PairingCopy.describe(PairingState.TimedOut(TimeoutCause.NETWORK_SILENT)).body.contains("cable"))
    }

    @Test fun `serial helpers`() {
        assertEquals(guid, PairingFlow.guidOf("$guid._adb-tls-connect._tcp"))
        assertEquals(null, PairingFlow.guidOf("192.168.1.20:5555"))
        assertTrue(isWirelessSerial("192.168.1.20:5555"))
        assertTrue(isWirelessSerial("$guid._adb-tls-connect._tcp"))
        assertTrue(!isWirelessSerial("emulator-5554") && !isWirelessSerial("37211FDJH00ABC"))
    }
}
