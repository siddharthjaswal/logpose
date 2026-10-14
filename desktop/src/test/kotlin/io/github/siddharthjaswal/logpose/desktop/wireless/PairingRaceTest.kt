package io.github.siddharthjaswal.logpose.desktop.wireless

import io.github.siddharthjaswal.logpose.logcat.Adb
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A Bonjour session scripted against fake time: what it sees, and when each endpoint is resolved. */
class FakeBonjour(
    private val t: FakeTime,
    private val sees: (now: Long) -> Map<AdbOutput.ServiceType, Set<String>> = { emptyMap() },
    private val endpoints: (instance: String, now: Long) -> BonjourEndpoint? = { _, _ -> null },
    override val available: Boolean = true,
) : BonjourSession {
    val invalidated = mutableListOf<String>()
    var closed = false
    override fun seen(type: AdbOutput.ServiceType) = sees(t.now)[type].orEmpty()
    override fun endpoint(instance: String, type: AdbOutput.ServiceType) = if (instance in seen(type)) endpoints(instance, t.now) else null
    override fun awaitEndpoint(instance: String, type: AdbOutput.ServiceType, timeoutMillis: Long) = endpoint(instance, type)
    override fun invalidate(instance: String) { invalidated += instance }
    override fun close() { closed = true }
}

class PairingRaceTest {
    private val code = QrPairingCode("logpose-KECwVKEjnL", "s3cretPw0rd1")
    private val guid = "adb-00173359D000017-zABNgD"
    private val pairing = AdbOutput.ServiceType.PAIRING
    private val connect = AdbOutput.ServiceType.CONNECT

    private fun flow(adb: AdbCommands, t: FakeTime, bonjour: BonjourSession) =
        PairingFlow(adb, t.clock, t.sleep, discoveryTimeoutMillis = 90_000, connectTimeoutMillis = 20_000, pollMillis = 1_000, bonjour = bonjour, log = PairingLog(t.clock))

    /** adb that never lists the pairing service (the macOS bug), but pairs and auto-connects. */
    private fun blindAdb(t: FakeTime, onPair: (String) -> Unit = {}): FakeAdb {
        var paired = false
        return FakeAdb { args ->
            when (args.first()) {
                "mdns" -> MDNS_HEADER + "$guid\t_adb-tls-connect._tcp\t192.168.1.10:41983\n"
                "pair" -> { onPair(args[1]); assertEquals(code.password, args[2]); paired = true; "Successfully paired to ${args[1]} [guid=$guid]" }
                "devices" -> "List of devices attached\n" + (if (paired) "$guid._adb-tls-connect._tcp device product:AsteroidsProIND model:A059P device:Asteroids transport_id:1\n" else "")
                else -> error("unexpected $args")
            }
        }
    }

    @Test fun `Bonjour wins - adb never sees the pairing service, pair goes straight to the Bonjour address`() = runBlocking {
        val t = FakeTime()
        val start = t.now
        var pairedAt: String? = null
        val bonjour = FakeBonjour(
            t,
            sees = { now -> if (now - start >= 6_000) mapOf(pairing to setOf(code.serviceName), connect to setOf(guid)) else mapOf(connect to setOf(guid)) },
            endpoints = { i, now -> if (i == code.serviceName && now - start >= 7_000) BonjourEndpoint(i, pairing, "Android_10IY7ZSZ.local.", "192.168.1.10", 42667) else null },
        )
        val f = flow(blindAdb(t) { pairedAt = it }, t, bonjour)
        val seen = mutableListOf<PairingState>()
        val result = f.runQr(code) { seen += it }
        assertEquals("192.168.1.10:42667", pairedAt)
        assertTrue(result is PairingState.Connected, "$result")
        assertEquals(PairingState.Pairing("192.168.1.10:42667", Via.BONJOUR), seen.first { it is PairingState.Pairing })
        val tr = f.log.snapshot()
        assertEquals(Via.BONJOUR, tr.foundVia)
        assertEquals(setOf(Via.BONJOUR), tr.pairingSeenAt.keys) // adb never saw it
        assertEquals(6_000L, tr.pairingSeenAt[Via.BONJOUR]!! - tr.startedAt)
        assertEquals(Via.ADB_AUTO, tr.connectVia)
        assertEquals("connected", tr.outcome)
        assertEquals(listOf(Stage.WAITING, Stage.FOUND, Stage.PAIRING, Stage.CONNECTING, Stage.CONNECTED), Stage.entries.filter { it in tr.stageAt })
        assertEquals(code.serviceName, tr.serviceName)
        assertFalse(tr.toString().contains(code.password), "the trace never holds the password")
    }

    @Test fun `adb wins - it lists the service before Bonjour resolves it`() = runBlocking {
        val t = FakeTime()
        var pairedAt: String? = null
        var paired = false
        val adb = FakeAdb { args ->
            when (args.first()) {
                "mdns" -> MDNS_HEADER + "${code.serviceName}\t_adb-tls-pairing._tcp\t192.168.1.10:42667\n"
                "pair" -> { pairedAt = args[1]; paired = true; "Successfully paired to ${args[1]} [guid=$guid]" }
                "devices" -> "List of devices attached\n" + (if (paired) "$guid._adb-tls-connect._tcp device model:A059P\n" else "")
                else -> error("unexpected $args")
            }
        }
        // Bonjour sees it too, but its endpoint is still resolving.
        val bonjour = FakeBonjour(t, sees = { mapOf(pairing to setOf(code.serviceName)) })
        val f = flow(adb, t, bonjour)
        f.runQr(code) {}
        assertEquals("192.168.1.10:42667", pairedAt)
        val tr = f.log.snapshot()
        assertEquals(Via.ADB, tr.foundVia)
        assertEquals(setOf(Via.ADB, Via.BONJOUR), tr.pairingSeenAt.keys) // both saw it in the same tick
    }

    @Test fun `both ready in the same tick - adb's free snapshot is taken first`() = runBlocking {
        val t = FakeTime()
        var paired = false
        val adb = FakeAdb { args ->
            when (args.first()) {
                "mdns" -> MDNS_HEADER + "${code.serviceName}\t_adb-tls-pairing._tcp\t192.168.1.10:42667\n"
                "pair" -> { paired = true; "Successfully paired to ${args[1]} [guid=$guid]" }
                "devices" -> "List of devices attached\n" + (if (paired) "$guid._adb-tls-connect._tcp device model:A059P\n" else "")
                else -> error("unexpected $args")
            }
        }
        val bonjour = FakeBonjour(t, sees = { mapOf(pairing to setOf(code.serviceName)) }, endpoints = { i, _ -> BonjourEndpoint(i, pairing, "h.local.", "192.168.1.10", 42667) })
        val f = flow(adb, t, bonjour)
        f.runQr(code) {}
        assertEquals(Via.ADB, f.log.snapshot().foundVia)
        assertEquals(1, adb.count("pair"))
    }

    @Test fun `neither - Bonjour saw other phones, so the phone wasn't seen (not isolation)`() = runBlocking {
        val t = FakeTime()
        val adb = FakeAdb { if (it.first() == "mdns") MDNS_HEADER else error("unexpected $it") }
        val bonjour = FakeBonjour(t, sees = { mapOf(connect to setOf("adb-47041VDJH00186-VKmD1s")) })
        val f = flow(adb, t, bonjour)
        assertEquals(PairingState.TimedOut(TimeoutCause.PHONE_NOT_SEEN), f.runQr(code) {})
        val tr = f.log.snapshot()
        assertTrue(tr.bonjourSawAny && !tr.adbSawAny)
        assertEquals("timed out: PHONE_NOT_SEEN", tr.outcome)
    }

    @Test fun `neither - both silent is network isolation`() = runBlocking {
        val t = FakeTime()
        val adb = FakeAdb { if (it.first() == "mdns") MDNS_HEADER else error("unexpected $it") }
        val f = flow(adb, t, FakeBonjour(t))
        assertEquals(PairingState.TimedOut(TimeoutCause.NETWORK_SILENT), f.runQr(code) {})
        assertEquals(0, adb.count("pair"))
    }

    @Test fun `connect step - Bonjour finds the connect port adb doesn't list, and a failed dial re-resolves`() = runBlocking {
        val t = FakeTime()
        var connected = false
        val dialled = mutableListOf<String>()
        val adb = FakeAdb { args ->
            when (args.first()) {
                "pair" -> "Successfully paired to 192.168.1.10:37755 [guid=$guid]"
                "mdns" -> MDNS_HEADER // adb's discovery sees nothing at all
                "connect" -> {
                    dialled += args[1]
                    if (args[1] == "192.168.1.10:41983") { connected = true; "connected to 192.168.1.10:41983" } else "failed to connect to ${args[1]}: Connection refused"
                }
                "devices" -> "List of devices attached\n" + (if (connected) "192.168.1.10:41983 device model:A059P\n" else "")
                else -> error("unexpected $args")
            }
        }
        // Bonjour's first answer is a stale port; once invalidated, it re-resolves to the live one.
        val invalidated = mutableListOf<String>()
        val bonjour = object : BonjourSession by FakeBonjour(t, sees = { mapOf(connect to setOf(guid)) }) {
            override fun endpoint(instance: String, type: AdbOutput.ServiceType) =
                BonjourEndpoint(instance, connect, "Android.local.", "192.168.1.10", if (invalidated.isEmpty()) 40000 else 41983)
            override fun invalidate(instance: String) { invalidated += instance }
        }
        val f = flow(adb, t, bonjour)
        val result = f.runCode("192.168.1.10:37755", "123456", null) {}
        assertEquals(listOf("192.168.1.10:40000", "192.168.1.10:41983"), dialled)
        assertEquals(listOf(guid), invalidated)
        assertEquals(PairingState.Connected("192.168.1.10:41983", "A059P", guid, "192.168.1.10:41983", Via.BONJOUR), result)
        assertEquals(Via.BONJOUR, f.log.snapshot().connectVia)
        assertEquals(setOf(Via.BONJOUR), f.log.snapshot().connectSeenAt.keys)
    }

    @Test fun `reconnect - Bonjour first, adb list second, last address only when neither discovery works`() {
        val tls = RememberedDevice(guid, "A059P", RememberedDevice.Kind.TLS, "192.168.1.10:39999", guid)
        val adb = FakeAdb { args -> if (args.first() == "connect") "connected to ${args[1]}" else error("unexpected $args") }
        val t = FakeTime()
        val bonjour = FakeBonjour(t, sees = { mapOf(connect to setOf(guid)) }, endpoints = { i, _ -> BonjourEndpoint(i, connect, "Android.local.", "192.168.1.10", 41983) })
        val stale = listOf(AdbOutput.MdnsService(guid, connect, "192.168.1.10", 39999))
        val o = Reconnector(adb).attempt(tls, stale, mdnsWorks = true, devices = emptyList<Adb.DeviceInfo>(), bonjour = bonjour)
        assertEquals(Reconnector.Outcome(true, "192.168.1.10:41983", "192.168.1.10:41983", Via.BONJOUR), o)

        // Bonjour working but the phone not advertising: nothing is dialled.
        val none = Reconnector(adb).attempt(tls, emptyList(), mdnsWorks = false, devices = emptyList(), bonjour = FakeBonjour(t))
        assertEquals(Reconnector.Outcome(false, null), none)
        assertEquals(1, adb.count("connect"))
    }
}
