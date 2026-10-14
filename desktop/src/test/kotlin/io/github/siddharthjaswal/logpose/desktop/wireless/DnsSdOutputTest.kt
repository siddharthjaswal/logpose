package io.github.siddharthjaswal.logpose.desktop.wireless

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Every string here was captured from `/usr/bin/dns-sd` on a Mac (macOS 27) with real phones on home Wi-Fi. */
class DnsSdOutputTest {

    private val browseConnect = """
        Browsing for _adb-tls-connect._tcp.local
        DATE: ---Wed 14 Oct 2026---
        11:08:12.133  ...STARTING...
        Timestamp     A/R    Flags  if Domain               Service Type         Instance Name
        11:08:12.134  Add        2  14 local.               _adb-tls-connect._tcp. adb-00173359D000017-zABNgD
        11:08:13.177  Add        2  14 local.               _adb-tls-connect._tcp. adb-47041VDJH00186-VKmD1s
    """.trimIndent()

    @Test fun `browse - header lines are ignored, Add rows parsed`() {
        val events = browseConnect.lines().mapNotNull(DnsSdOutput::parseBrowseLine)
        assertEquals(
            listOf(
                DnsSdOutput.BrowseEvent(true, AdbOutput.ServiceType.CONNECT, "adb-00173359D000017-zABNgD", 14),
                DnsSdOutput.BrowseEvent(true, AdbOutput.ServiceType.CONNECT, "adb-47041VDJH00186-VKmD1s", 14),
            ),
            events,
        )
    }

    @Test fun `browse - the pairing service from the diagnosis, and a removal`() {
        val add = DnsSdOutput.parseBrowseLine("11:03:06.358  Add        2  14 local.               _adb-tls-pairing._tcp. logpose-KECwVKEjnL")
        assertEquals(DnsSdOutput.BrowseEvent(true, AdbOutput.ServiceType.PAIRING, "logpose-KECwVKEjnL", 14), add)
        val rmv = DnsSdOutput.parseBrowseLine("11:04:10.002  Rmv        0  14 local.               _adb-tls-pairing._tcp. logpose-KECwVKEjnL")
        assertEquals(false, rmv!!.added)
        assertEquals("logpose-KECwVKEjnL", rmv.instance)
    }

    @Test fun `browse - instance names with spaces survive`() {
        val e = DnsSdOutput.parseBrowseLine("11:08:12.134  Add        3  14 local.               _adb._tcp.           Living Room TV")
        assertEquals("Living Room TV", e!!.instance)
        assertEquals(AdbOutput.ServiceType.LEGACY, e.type)
    }

    @Test fun `browse - junk is null`() {
        listOf("", "Browsing for _adb-tls-pairing._tcp.local", "DATE: ---Wed 14 Oct 2026---", "11:08:12.133  ...STARTING...",
            "Timestamp     A/R    Flags  if Domain               Service Type         Instance Name").forEach {
            assertNull(DnsSdOutput.parseBrowseLine(it), it)
        }
    }

    @Test fun `resolve - can be reached at, with the TXT record`() {
        val out = """
            Lookup adb-00173359D000017-zABNgD._adb-tls-connect._tcp.local
            DATE: ---Wed 14 Oct 2026---
            11:08:26.921  ...STARTING...
            11:08:26.922  adb-00173359D000017-zABNgD._adb-tls-connect._tcp.local. can be reached at Android.local.:41983 (interface 14)
             api=36.0 name=A059P v=1
        """.trimIndent()
        assertEquals(DnsSdOutput.Resolved("Android.local.", 41983, 14, mapOf("api" to "36.0", "name" to "A059P", "v" to "1")), DnsSdOutput.parseResolve(out))
    }

    @Test fun `resolve - the pairing service from the diagnosis, and escaped TXT values`() {
        val r = DnsSdOutput.parseResolve("logpose-KECwVKEjnL._adb-tls-pairing._tcp.local. can be reached at Android_10IY7ZSZ.local.:42667 (interface 14)")
        assertEquals(DnsSdOutput.Resolved("Android_10IY7ZSZ.local.", 42667, 14), r)
        val txt = DnsSdOutput.parseTxt(" given_name=Pixel\\ 8 serial=47041VDJH00186 v=2.2 api=37.2 name=Pixel\\ 8")
        assertEquals("Pixel 8", txt["given_name"])
        assertEquals("47041VDJH00186", txt["serial"])
    }

    @Test fun `resolve - nothing yet is null`() {
        assertNull(DnsSdOutput.parseResolve("Lookup x._adb-tls-connect._tcp.local\nDATE: ---Wed 14 Oct 2026---\n11:08:26.921  ...STARTING..."))
    }

    @Test fun `address - v4, scoped v6 lower-cased, headers and negatives ignored`() {
        val out = """
            DATE: ---Wed 14 Oct 2026---
            11:08:41.129  ...STARTING...
            Timestamp     A/R  Flags         IF  Hostname                               Address                                      TTL
            11:08:41.130  Add  40000003      14  Android_63CXP52F.local.                FE80:0000:0000:0000:7896:B4FF:FE10:4B94%en0  120
            11:08:41.130  Add  40000002      14  Android_63CXP52F.local.                192.168.1.5                                  120
        """.trimIndent()
        val recs = out.lines().mapNotNull(DnsSdOutput::parseAddressLine)
        assertEquals(2, recs.size)
        assertEquals("fe80:0000:0000:0000:7896:b4ff:fe10:4b94%en0", recs[0].address)
        assertEquals(true, recs[0].ipv6)
        assertEquals(DnsSdOutput.AddressRecord(true, "Android_63CXP52F.local.", "192.168.1.5", 14), recs[1])
        assertEquals("192.168.1.10", DnsSdOutput.parseAddressLine("11:08:38.124  Add  40000002      14  Android.local.                         192.168.1.10                                 120")!!.address)
        assertNull(DnsSdOutput.parseAddressLine("11:08:38.124  Add  40000000      14  Android.local.                         0.0.0.0                                      120   No Such Record"))
    }

    @Test fun `adb address - v4 plain, v6 bracketed with scope`() {
        assertEquals("192.168.1.10:41983", DnsSdOutput.adbAddress("192.168.1.10", 41983))
        assertEquals("[fe80::3478:dbff:fe83:c15a%en0]:41983", DnsSdOutput.adbAddress("fe80::3478:dbff:fe83:c15a%en0", 41983))
        // …and adb's own address splitter takes it back apart.
        assertEquals("fe80::3478:dbff:fe83:c15a%en0" to 41983, AdbOutput.splitHostPort("[fe80::3478:dbff:fe83:c15a%en0]:41983"))
    }
}
