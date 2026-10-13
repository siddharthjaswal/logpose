package io.github.siddharthjaswal.logpose.desktop.wireless

import io.github.siddharthjaswal.logpose.desktop.wireless.AdbOutput.ConnectResult
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbOutput.MdnsCheck
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbOutput.PairResult
import io.github.siddharthjaswal.logpose.desktop.wireless.AdbOutput.ServiceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Strings marked "live" were captured from platform-tools 37.0.1 (adb 1.0.41) on macOS; the rest
 * are the literal format strings in adb's source (packages/modules/adb, main).
 */
class AdbOutputTest {

    @Test fun `mdns check - available on 37 x, on Openscreen builds, and unavailable`() {
        // live
        assertEquals(MdnsCheck.Available("adb discovery 0.0.0"), AdbOutput.parseMdnsCheck("mdns daemon version [adb discovery 0.0.0]\n"))
        assertEquals(MdnsCheck.Available("Openscreen discovery 0.0.0"), AdbOutput.parseMdnsCheck("mdns daemon version [Openscreen discovery 0.0.0]"))
        assertEquals(MdnsCheck.Available("1310405"), AdbOutput.parseMdnsCheck("mdns daemon version [1310405]"))
        assertTrue(AdbOutput.parseMdnsCheck("ERROR: mdns discovery disabled") is MdnsCheck.Unavailable)
        assertTrue(AdbOutput.parseMdnsCheck("ERROR: mdns daemon unavailable") is MdnsCheck.Unavailable)
        assertTrue(AdbOutput.parseMdnsCheck("") is MdnsCheck.Unavailable)
    }

    @Test fun `mdns services - empty network (live) is no services`() {
        assertEquals(emptyList<AdbOutput.MdnsService>(), AdbOutput.parseMdnsServices("List of discovered mdns services\n\n"))
    }

    @Test fun `mdns services - pairing, connect and legacy rows`() {
        val out = "List of discovered mdns services\n" +
            "adb-14141FDF600081-QoXtBe\t_adb-tls-connect._tcp\t192.168.1.20:39133\n" +
            "logpose-Ab3dEf9hJk\t_adb-tls-pairing._tcp\t192.168.1.20:37755\n" +
            "adb-R58M123-xyz\t_adb._tcp.\t10.0.0.7:5555\n"
        val s = AdbOutput.parseMdnsServices(out)
        assertEquals(3, s.size)
        assertEquals(AdbOutput.MdnsService("adb-14141FDF600081-QoXtBe", ServiceType.CONNECT, "192.168.1.20", 39133), s[0])
        assertEquals(ServiceType.PAIRING, s[1].type)
        assertEquals("192.168.1.20:37755", s[1].address)
        assertEquals("logpose-Ab3dEf9hJk", s[1].instance)
        assertEquals(ServiceType.LEGACY, s[2].type) // trailing dot tolerated
    }

    @Test fun `mdns services - space-aligned columns and junk lines`() {
        val out = "* daemon started successfully\nList of discovered mdns services\n" +
            "adb-X-Y   _adb-tls-connect._tcp   192.168.0.5:40001\ngarbage\nname\t_x._tcp\tnot-an-address\n"
        val s = AdbOutput.parseMdnsServices(out)
        assertEquals(listOf(AdbOutput.MdnsService("adb-X-Y", ServiceType.CONNECT, "192.168.0.5", 40001)), s)
    }

    @Test fun `pair - success carries the guid`() {
        assertEquals(
            PairResult.Success("adb-14141FDF600081-QoXtBe"),
            AdbOutput.parsePair("Successfully paired to 192.168.1.20:37755 [guid=adb-14141FDF600081-QoXtBe]\n"),
        )
    }

    @Test fun `pair - each failure string`() {
        assertEquals(PairResult.WrongCode, AdbOutput.parsePair("Failed: Wrong password or connection was dropped."))
        assertTrue(AdbOutput.parsePair("Failed: Unable to start pairing client.") is PairResult.Unreachable)
        assertTrue(AdbOutput.parsePair("Failed: unable to create pairing client.") is PairResult.Unreachable)
        // live: adb 37.0.1 with nothing listening on the pairing port
        assertTrue(AdbOutput.parsePair("error: protocol fault (couldn't read status message): Undefined error: 0") is PairResult.Unreachable)
        assertTrue(AdbOutput.parsePair("Failed to parse address for pairing: bad port") is PairResult.Unreachable)
        assertTrue(AdbOutput.parsePair("Invalid port while parsing address [1.2.3.4:0]") is PairResult.Unreachable)
        assertTrue(AdbOutput.parsePair("Failed: Successfully paired but server returned unknown response=x") is PairResult.Failed)
    }

    @Test fun `connect - success, already, auth, and the unreachable family`() {
        assertEquals(ConnectResult.Connected("192.168.1.20:39133"), AdbOutput.parseConnect("connected to 192.168.1.20:39133\n"))
        assertEquals(ConnectResult.AlreadyConnected("192.168.1.20:5555"), AdbOutput.parseConnect("already connected to 192.168.1.20:5555"))
        assertTrue(AdbOutput.parseConnect("failed to authenticate to 192.168.1.20:5555") is ConnectResult.NotAuthorized)
        // live
        assertTrue(AdbOutput.parseConnect("failed to connect to '127.0.0.1:5999': Connection refused") is ConnectResult.Unreachable)
        assertTrue(
            AdbOutput.parseConnect("failed to resolve host: 'nonexistent.invalid': nodename nor servname provided, or not known")
                is ConnectResult.Unreachable,
        )
        assertTrue(AdbOutput.parseConnect("failed to connect to 192.168.1.20:5555") is ConnectResult.Unreachable)
        assertTrue(AdbOutput.parseConnect("cannot connect to 10.0.0.9:5555: Operation timed out (60)") is ConnectResult.Unreachable)
        assertTrue(AdbOutput.parseConnect("") is ConnectResult.Failed)
    }

    @Test fun `tcpip success line`() {
        assertTrue(AdbOutput.tcpipOk("restarting in TCP mode port: 5555\n"))
        assertFalse(AdbOutput.tcpipOk("error: no devices/emulators found"))
    }

    @Test fun `wifi ip from ip route - picks wlan src, ignores cellular and vpn`() {
        val route = """
            10.112.44.0/26 dev rmnet_data1 proto kernel scope link src 10.112.44.17
            10.8.0.0/24 dev tun0 proto kernel scope link src 10.8.0.3
            192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.42
        """.trimIndent()
        assertEquals("192.168.1.42", AdbOutput.wifiIpFromRoute(route))
        assertNull(AdbOutput.wifiIpFromRoute("10.112.44.0/26 dev rmnet_data1 proto kernel scope link src 10.112.44.17"))
        assertNull(AdbOutput.wifiIpFromRoute(""))
    }

    @Test fun `wifi ip from ip addr show wlan0`() {
        val addr = """
            30: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP group default qlen 3000
                link/ether 02:00:00:44:55:66 brd ff:ff:ff:ff:ff:ff
                inet 192.168.1.42/24 brd 192.168.1.255 scope global wlan0
                   valid_lft forever preferred_lft forever
                inet6 fe80::ff:fe44:5566/64 scope link
        """.trimIndent()
        assertEquals("192.168.1.42", AdbOutput.wifiIpFromAddr(addr))
        assertNull(AdbOutput.wifiIpFromAddr("Device \"wlan0\" does not exist."))
    }

    @Test fun `wifi ip prefers route and falls back to addr`() {
        assertEquals("10.0.0.2", AdbOutput.wifiIp("", "    inet 10.0.0.2/24 brd 10.0.0.255 scope global wlan0"))
        assertEquals("192.168.1.42", AdbOutput.wifiIp("192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.42", null))
        assertNull(AdbOutput.wifiIp(null, null))
        // A link-local fallback address is not a usable Wi-Fi address.
        assertNull(AdbOutput.wifiIp("169.254.0.0/16 dev wlan0 scope link src 169.254.3.4", null))
    }

    @Test fun `typed addresses and codes are normalised`() {
        assertEquals("192.168.1.20:37755", AdbOutput.normalizeAddress(" 192.168.1.20:37755 "))
        assertNull(AdbOutput.normalizeAddress("192.168.1.20"))
        assertNull(AdbOutput.normalizeAddress("192.168.1.20:99999"))
        assertEquals("123456", AdbOutput.normalizePairingCode("123 456"))
        assertEquals("123456", AdbOutput.normalizePairingCode("123-456"))
        assertNull(AdbOutput.normalizePairingCode("12345"))
        assertNull(AdbOutput.normalizePairingCode("12a456"))
    }
}
