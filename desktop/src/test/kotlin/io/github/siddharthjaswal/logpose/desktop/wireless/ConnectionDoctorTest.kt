package io.github.siddharthjaswal.logpose.desktop.wireless

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

/** The doctor's decision table. The baseline is this Mac as diagnosed: healthy home Wi-Fi, phone napping. */
class ConnectionDoctorTest {
    private val guid = "adb-00173359D000017-zABNgD"
    private val pairName = "logpose-KECwVKEjnL"
    private val password = "s3cretPw0rd1"
    private val connect = AdbOutput.ServiceType.CONNECT
    private val pairing = AdbOutput.ServiceType.PAIRING

    private val home = SystemOutput.MacInterface("en0", "192.168.1.13", 24)
    private val fastPing = SystemOutput.PingStats(5, 5, 3.1, 4.0, 6.2, 1.1)
    private val sdk = "/Users/qa/Library/Android/sdk/platform-tools/adb"

    private fun facts(
        bonjour: List<SeenService> = listOf(SeenService(connect, guid, "192.168.1.10:41983")),
        adb: List<SeenService> = listOf(SeenService(connect, guid, "192.168.1.10:41983")),
        ping: SystemOutput.PingStats? = fastPing,
        phoneIp: String? = "192.168.1.10",
        targetPairing: String? = null,
        f: (DoctorFacts) -> DoctorFacts = { it },
    ) = f(
        DoctorFacts(
            macOs = "27.0.1", bonjourAvailable = true, bonjour = bonjour, adbDiscovery = AdbOutput.MdnsCheck.Available("adb discovery 0.0.0"),
            adb = adb, targetPairing = targetPairing, targetGuids = setOf(guid), interfaces = listOf(home), defaultInterface = "en0",
            phoneIp = phoneIp, ping = ping, firewall = SystemOutput.Firewall(false, false, false),
            adbBinaries = listOf(AdbBinary(sdk, "37.0.1-15733141", inUse = true)), adbServers = 1,
        ),
    )

    private fun of(f: DoctorFacts, id: CheckId) = ConnectionDoctor.diagnose(f).filter { it.id == id }

    @Test fun `all healthy - nothing to interrupt for`() {
        val checks = ConnectionDoctor.diagnose(facts())
        assertTrue(checks.filter { it.id != CheckId.PHONE_SIDE }.all { it.verdict == Verdict.OK }, checks.joinToString("\n"))
        assertNull(ConnectionDoctor.top(checks))
        assertEquals("1 phone with Wireless debugging is visible", of(facts(), CheckId.DISCOVERY).single().title)
    }

    @Test fun `Bonjour sees the pairing service, adb doesn't - informational, with the exact words`() {
        val f = facts(targetPairing = pairName, bonjour = listOf(SeenService(pairing, pairName, "192.168.1.10:42667")), adb = emptyList())
        val c = of(f, CheckId.ADB_MISSED).single()
        assertEquals(Verdict.INFO, c.verdict)
        assertEquals("adb's discovery missed your phone (a known adb issue on macOS) — LogPose finds it directly over Bonjour", c.title)
        val paired = f.copy(pairing = PairingTrace(serviceName = pairName, foundVia = Via.BONJOUR, pairingSeenAt = mapOf(Via.BONJOUR to 1L)))
        assertEquals("adb's discovery missed your phone (a known adb issue on macOS) — LogPose paired directly over Bonjour", of(paired, CheckId.ADB_MISSED).single().title)
        assertEquals(CheckId.ADB_MISSED, ConnectionDoctor.top(ConnectionDoctor.diagnose(f))!!.id) // a note, not an error
    }

    @Test fun `nothing advertised anywhere - isolation, cable offered, and Local Network suspected`() {
        val f = facts(bonjour = emptyList(), adb = emptyList(), phoneIp = null, ping = null)
        val c = of(f, CheckId.ISOLATION).single()
        assertEquals(Verdict.PROBLEM, c.verdict)
        assertEquals("This Mac can't see any phone on the network", c.title)
        assertTrue(c.detail.contains("client isolation") && c.detail.contains("mesh or extender") && c.detail.contains("pairing code won't help") && c.detail.contains("hotspot"))
        assertEquals(listOf(FixAction.USE_CABLE), c.actions)
        assertEquals(Verdict.WARNING, of(f, CheckId.LOCAL_NETWORK).single().verdict)
        assertEquals(listOf(FixAction.OPEN_LOCAL_NETWORK), of(f, CheckId.LOCAL_NETWORK).single().actions)
        assertEquals(CheckId.ISOLATION, ConnectionDoctor.top(ConnectionDoctor.diagnose(f))!!.id)
    }

    @Test fun `others visible, not our pairing service - the phone isn't advertising`() {
        val f = facts(targetPairing = pairName)
        val c = of(f, CheckId.NOT_ADVERTISING).single()
        assertEquals(Verdict.WARNING, c.verdict)
        assertTrue(c.detail.contains("Pair device with QR code") && c.detail.contains("changes networks"))
    }

    @Test fun `different subnet`() {
        val c = of(facts(phoneIp = "10.0.5.20"), CheckId.SUBNET).single()
        assertEquals(Verdict.PROBLEM, c.verdict)
        assertEquals("Your phone is on a different network", c.title)
        assertTrue(c.detail.contains("10.0.5.20") && c.detail.contains("192.168.1.13/24"))
        assertEquals(Verdict.OK, of(facts(), CheckId.SUBNET).single().verdict)
    }

    @Test fun `power save - the live RTTs from the diagnosis`() {
        val napping = SystemOutput.PingStats(5, 5, 6.347, 53.217, 130.115, 52.660)
        val c = of(facts(ping = napping), CheckId.POWER_SAVE).single()
        assertEquals(Verdict.WARNING, c.verdict)
        assertEquals("Your phone is saving Wi-Fi power", c.title)
        assertTrue(c.detail.contains("6.3–130 ms") && c.detail.contains("keep the screen on and stay on the Wireless debugging page while pairing"), c.detail)
        assertTrue(of(facts(), CheckId.POWER_SAVE).isEmpty())
    }

    @Test fun `no ping answers - unreachable`() {
        val c = of(facts(ping = SystemOutput.PingStats(5, 0, null, null, null, null)), CheckId.REACHABILITY).single()
        assertEquals(Verdict.PROBLEM, c.verdict)
        assertEquals("This Mac can't reach your phone at 192.168.1.10", c.title)
    }

    @Test fun `utun interfaces present but no VPN - no warning`() {
        // This Mac: utun0–3 exist (iCloud / Continuity) but carry no IPv4 routes; no VPN process.
        val f = facts { it.copy(tunnelRoutes = emptyList(), vpnClients = emptyList()) }
        val c = of(f, CheckId.VPN).single()
        assertEquals(Verdict.OK, c.verdict)
        assertEquals("No VPN in the way", c.title)
    }

    @Test fun `a tunnel carrying the default route, or a running VPN client, warns`() {
        val full = facts { it.copy(tunnelRoutes = listOf(SystemOutput.Route("0/1", "10.8.0.1", "utun4"), SystemOutput.Route("128.0/1", "10.8.0.1", "utun4"))) }
        val c = of(full, CheckId.VPN).single()
        assertEquals(Verdict.WARNING, c.verdict)
        assertEquals("A VPN can block local devices — pause it while pairing", c.title)
        assertTrue(c.detail.contains("utun4 carries this Mac's default route"), c.detail)

        val split = facts { it.copy(tunnelRoutes = listOf(SystemOutput.Route("10/8", "10.8.0.1", "utun4"))) } // corporate split tunnel, not the LAN
        assertEquals(Verdict.OK, of(split, CheckId.VPN).single().verdict)

        val zs = of(facts { it.copy(vpnClients = listOf("Zscaler")) }, CheckId.VPN).single()
        assertEquals(Verdict.INFO, zs.verdict)
        assertTrue(zs.detail.startsWith("Zscaler is running"))
        assertEquals(Verdict.WARNING, of(facts { it.copy(vpnConnected = listOf("Office VPN")) }, CheckId.VPN).single().verdict)
    }

    @Test fun `firewall block-all warns with the settings button, stealth is fine`() {
        val c = of(facts { it.copy(firewall = SystemOutput.Firewall(true, true, false)) }, CheckId.FIREWALL).single()
        assertEquals(Verdict.WARNING, c.verdict)
        assertEquals(listOf(FixAction.OPEN_FIREWALL), c.actions)
        assertEquals(Verdict.OK, of(facts { it.copy(firewall = SystemOutput.Firewall(true, false, true)) }, CheckId.FIREWALL).single().verdict)
    }

    @Test fun `adb conflicts - two servers, or Studio running a different version`() {
        val two = of(facts { it.copy(adbServers = 2) }, CheckId.ADB_CONFLICT).single()
        assertEquals(Verdict.WARNING, two.verdict)
        val mixed = facts {
            it.copy(
                adbBinaries = listOf(AdbBinary("/Users/qa/Library/Application Support/LogPose/platform-tools/adb", "37.0.1-15733141", true), AdbBinary(sdk, "35.0.2-12147458", false)),
                studioRunning = true, studioAdb = sdk,
            )
        }
        val c = of(mixed, CheckId.ADB_CONFLICT).single()
        assertEquals(Verdict.WARNING, c.verdict)
        assertEquals(listOf(FixAction.USE_STUDIO_ADB), c.actions)
        assertTrue(c.detail.contains("35.0.2-12147458"))
        // Different versions installed but Studio not running: just a note in an OK line.
        assertEquals(Verdict.OK, of(mixed.copy(studioRunning = false), CheckId.ADB_CONFLICT).single().verdict)
    }

    @Test fun `severity order puts the worst first`() {
        val f = facts(ping = SystemOutput.PingStats(5, 5, 6.3, 53.2, 130.1, 52.7), phoneIp = "10.0.5.20") { it.copy(vpnClients = listOf("Zscaler")) }
        val checks = ConnectionDoctor.diagnose(f)
        assertEquals(checks.sortedBy { it.verdict.rank }, checks)
        assertEquals(CheckId.SUBNET, ConnectionDoctor.top(checks)!!.id)
    }

    @Test fun `diagnostics report - format, findings, timings, and no password anywhere`() = kotlinx.coroutines.runBlocking {
        // A real pairing run through the flow, with the password in play, feeds the report.
        val t = FakeTime()
        var paired = false
        val adb = FakeAdb { args ->
            when (args.first()) {
                "mdns" -> MDNS_HEADER
                "pair" -> { paired = true; "Successfully paired to ${args[1]} [guid=$guid]" }
                "devices" -> "List of devices attached\n" + (if (paired) "$guid._adb-tls-connect._tcp device model:A059P\n" else "")
                else -> error("unexpected $args")
            }
        }
        val code = QrPairingCode(pairName, password)
        val bonjour = FakeBonjour(t, sees = { mapOf(pairing to setOf(pairName)) }, endpoints = { i, _ -> BonjourEndpoint(i, pairing, "Android_10IY7ZSZ.local.", "192.168.1.10", 42667) })
        val flow = PairingFlow(adb, t.clock, t.sleep, bonjour = bonjour, log = PairingLog(t.clock))
        flow.runQr(code) {}
        val f = facts(
            targetPairing = pairName, adb = emptyList(),
            bonjour = listOf(SeenService(pairing, pairName, "192.168.1.10:42667"), SeenService(connect, guid, "192.168.1.10:41983")),
            ping = SystemOutput.PingStats(5, 5, 6.347, 53.217, 130.115, 52.660),
        ) { it.copy(pairing = flow.log.snapshot(), appVersion = "1.0.0", adbBinaries = listOf(AdbBinary("/Users/qa/Library/Android/sdk/platform-tools/adb", "37.0.1-15733141", true))) }
        val md = Diagnostics.markdown(f, ConnectionDoctor.diagnose(f), nowMillis = 1_792_000_000_000, zone = ZoneId.of("UTC"), home = "/Users/qa")
        assertFalse(md.contains(password), md)
        assertTrue(md.startsWith("**LogPose wireless diagnostics** — "), md)
        assertTrue(md.contains("- Mac: macOS 27.0.1 · LogPose 1.0.0"), md)
        assertTrue(md.contains("- adb: 37.0.1-15733141 (~/Library/Android/sdk/platform-tools/adb) · discovery: adb discovery 0.0.0"), md)
        assertFalse(md.contains("/Users/qa"), "home folder shortened")
        assertTrue(md.contains("[Note] adb's discovery missed your phone"), md)
        assertTrue(md.contains("[Warning] Your phone is saving Wi-Fi power"), md)
        assertTrue(md.contains("- macOS Bonjour: $pairName (pairing, 192.168.1.10:42667) ← ours"), md)
        assertTrue(md.contains("- adb mdns services: nothing"), md)
        assertTrue(md.contains("- Phone: 192.168.1.10 · ping 5/5, min/avg/max/stddev 6.347/53.217/130.115/52.66 ms"), md)
        assertTrue(md.contains("- VPN: none"), md)
        assertTrue(md.contains("- Phone found via macOS Bonjour at +0.0s (adb's discovery never saw it)"), md)
        assertTrue(md.contains("- Connected via adb's auto-connect"), md)
        assertTrue(md.contains("- Timings: waiting +0.0s → found +0.0s → pairing +0.0s → connecting +0.0s → connected +0.0s"), md)
        assertNotNull(md)
    }
}
