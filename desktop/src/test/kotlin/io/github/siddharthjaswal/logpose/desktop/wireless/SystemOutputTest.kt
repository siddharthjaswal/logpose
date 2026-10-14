package io.github.siddharthjaswal.logpose.desktop.wireless

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Strings captured on this Mac (macOS 27.0.1): Wi-Fi en0 192.168.1.13/24, idle utun0–3, no VPN, firewall off. */
class SystemOutputTest {

    companion object {
        val IFCONFIG = """
            lo0: flags=8049<UP,LOOPBACK,RUNNING,MULTICAST> mtu 16384
            	inet 127.0.0.1 netmask 0xff000000
            gif0: flags=8010<POINTOPOINT,MULTICAST> mtu 1280
            en0: flags=8863<UP,BROADCAST,SMART,RUNNING,SIMPLEX,MULTICAST> mtu 1500
            	inet 192.168.1.13 netmask 0xffffff00 broadcast 192.168.1.255
            awdl0: flags=8863<UP,BROADCAST,SMART,RUNNING,SIMPLEX,MULTICAST> mtu 1500
            utun0: flags=8051<UP,POINTOPOINT,RUNNING,MULTICAST> mtu 1500
            utun1: flags=8051<UP,POINTOPOINT,RUNNING,MULTICAST> mtu 1380
            utun2: flags=8051<UP,POINTOPOINT,RUNNING,MULTICAST> mtu 2000
            utun3: flags=8051<UP,POINTOPOINT,RUNNING,MULTICAST> mtu 1000
        """.trimIndent()

        val NETSTAT = """
            Routing tables

            Internet:
            Destination        Gateway            Flags               Netif Expire
            default            192.168.1.1        UGScg                 en0
            127                127.0.0.1          UCS                   lo0
            127.0.0.1          127.0.0.1          UH                    lo0
            169.254            link#14            UCS                   en0      !
            192.168.1          link#14            UCS                   en0      !
            192.168.1.10       36:78:db:83:c1:5a  UHLWIi                en0   1105
            224.0.0/4          link#14            UmCS                  en0      !
            255.255.255.255/32 link#14            UCS                   en0      !
        """.trimIndent()

        val PING = """
            PING 192.168.1.10 (192.168.1.10): 56 data bytes
            64 bytes from 192.168.1.10: icmp_seq=0 ttl=64 time=16.755 ms
            64 bytes from 192.168.1.10: icmp_seq=1 ttl=64 time=103.366 ms
            64 bytes from 192.168.1.10: icmp_seq=2 ttl=64 time=9.504 ms
            64 bytes from 192.168.1.10: icmp_seq=3 ttl=64 time=6.347 ms
            64 bytes from 192.168.1.10: icmp_seq=4 ttl=64 time=130.115 ms

            --- 192.168.1.10 ping statistics ---
            5 packets transmitted, 5 packets received, 0.0% packet loss
            round-trip min/avg/max/stddev = 6.347/53.217/130.115/52.660 ms
        """.trimIndent()
    }

    @Test fun `ifconfig keeps en0, drops loopback, utun has no IPv4`() {
        val ifs = SystemOutput.parseIfconfig(IFCONFIG)
        assertEquals(listOf(SystemOutput.MacInterface("en0", "192.168.1.13", 24)), ifs)
        assertTrue(ifs[0].contains("192.168.1.10"))
        assertFalse(ifs[0].contains("192.168.2.10"))
        assertEquals(24, SystemOutput.prefixFromMask("255.255.255.0"))
    }

    @Test fun `netstat rows and destinations`() {
        val routes = SystemOutput.parseRoutes(NETSTAT)
        assertEquals(SystemOutput.Route("default", "192.168.1.1", "en0"), routes.first())
        assertTrue(routes.none { SystemOutput.TUNNEL_IFACE.matches(it.netif) }, "no IPv4 route rides a utun on this Mac")
        assertEquals(0L to 0, SystemOutput.parseDestination("default"))
        assertEquals(Ipv4.toLong("192.168.1.0")!! to 24, SystemOutput.parseDestination("192.168.1"))
        assertEquals(Ipv4.toLong("100.64.0.0")!! to 10, SystemOutput.parseDestination("100.64/10"))
        assertEquals(0L to 1, SystemOutput.parseDestination("0/1"))
        assertEquals(Ipv4.toLong("128.0.0.0")!! to 1, SystemOutput.parseDestination("128.0/1"))
    }

    @Test fun `ping stats`() {
        val p = SystemOutput.parsePing(PING)!!
        assertEquals(5, p.sent); assertEquals(5, p.received); assertEquals(0, p.lossPercent)
        assertEquals(6.347, p.minMs); assertEquals(53.217, p.avgMs); assertEquals(130.115, p.maxMs); assertEquals(52.660, p.stddevMs)
        val dead = SystemOutput.parsePing("--- 192.168.1.99 ping statistics ---\n5 packets transmitted, 0 packets received, 100.0% packet loss\n")!!
        assertEquals(100, dead.lossPercent); assertEquals(null, dead.avgMs)
    }

    @Test fun `firewall states`() {
        val off = SystemOutput.parseFirewall("Firewall is disabled. (State = 0)", "Firewall has block all state set to disabled.", "Firewall stealth mode is off")
        assertEquals(SystemOutput.Firewall(enabled = false, blockAll = false, stealth = false), off)
        val strict = SystemOutput.parseFirewall("Firewall is enabled. (State = 1)", "Firewall has block all state set to enabled.", "Firewall stealth mode is on")
        assertEquals(SystemOutput.Firewall(enabled = true, blockAll = true, stealth = true), strict)
    }

    @Test fun `vpn clients, connected configurations, adb servers, studio, adb version`() {
        assertEquals(listOf("Zscaler", "Tailscale"), SystemOutput.vpnClients(listOf("launchd", "ZscalerTunnel", "Zscaler", "tailscaled", "Finder")))
        assertTrue(SystemOutput.vpnClients(listOf("launchd", "WindowServer", "adb", "studio")).isEmpty())
        assertEquals(listOf("Office VPN"), SystemOutput.parseScutilNc("Available network connection services in the current set (*=enabled):\n* (Connected)      1A2B IPSec              \"Office VPN\"                     [IPSec]\n* (Disconnected)   3C4D PPP --> L2TP        \"Old\"                            [PPP/L2TP]"))
        val ps = "21434 adb -L tcp:5037 fork-server server --reply-fd 4\n21441 /Users/aurora/Library/Android/sdk/platform-tools/adb logcat -v raw -s LogPose:V\n"
        assertEquals(1, SystemOutput.adbServers(ps).size)
        assertTrue(SystemOutput.studioRunning(listOf("studio")))
        assertEquals("37.0.1-15733141", SystemOutput.adbVersion("Android Debug Bridge version 1.0.41\nVersion 37.0.1-15733141\nInstalled as /Users/aurora/Library/Android/sdk/platform-tools/adb\n"))
    }
}
