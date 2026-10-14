package io.github.siddharthjaswal.logpose.desktop.wireless

/**
 * Parsers for the macOS tools the connection doctor reads: `ifconfig`, `netstat -rn -f inet`,
 * `ping`, `socketfilterfw`, `scutil --nc list`, `ps`, `adb version`. Pure and unit-tested against
 * output captured on a real Mac (macOS 27).
 */
object SystemOutput {

    // ── ifconfig ────────────────────────────────────────────────────────────────────────────────

    /** An IPv4 address on a Mac interface: `en0 192.168.1.13/24`. */
    data class MacInterface(val name: String, val ip: String, val prefixLength: Int) {
        val cidr: String get() = "$ip/$prefixLength"
        fun contains(other: String): Boolean = Ipv4.sameNetwork(ip, other, prefixLength)
    }

    /**
     * `ifconfig`'s blocks (`en0: flags=…` then indented `inet 192.168.1.13 netmask 0xffffff00 …`),
     * keeping IPv4 addresses on real LAN-ish interfaces — not loopback, not link-local 169.254.
     */
    fun parseIfconfig(output: String): List<MacInterface> {
        val out = mutableListOf<MacInterface>()
        var iface: String? = null
        for (line in output.lines()) {
            if (line.isNotEmpty() && !line[0].isWhitespace()) {
                iface = line.substringBefore(':').trim()
                continue
            }
            val t = line.trim().split(Regex("\\s+"))
            if (t.firstOrNull() != "inet" || iface == null) continue
            val ip = t.getOrNull(1) ?: continue
            val mask = t.indexOf("netmask").takeIf { it >= 0 }?.let { t.getOrNull(it + 1) }
            val prefix = mask?.let(::prefixFromMask) ?: 32
            if (ip.startsWith("127.") || ip.startsWith("169.254.")) continue
            out += MacInterface(iface, ip, prefix)
        }
        return out
    }

    /** `0xffffff00` (ifconfig) or `255.255.255.0` → 24. */
    fun prefixFromMask(mask: String): Int? {
        val bits = if (mask.startsWith("0x")) mask.removePrefix("0x").toLongOrNull(16) else Ipv4.toLong(mask)
        return bits?.let { java.lang.Long.bitCount(it and 0xFFFFFFFFL) }
    }

    // ── netstat -rn -f inet ─────────────────────────────────────────────────────────────────────

    data class Route(val destination: String, val gateway: String, val netif: String)

    /** The rows of `netstat -rn -f inet` (Destination, Gateway, Flags, Netif, Expire). */
    fun parseRoutes(output: String): List<Route> =
        output.lines().mapNotNull { line ->
            val t = line.trim().split(Regex("\\s+"))
            if (t.size < 4 || t[0] == "Destination" || t[0] == "Routing" || t[0] == "Internet:") return@mapNotNull null
            Route(t[0], t[1], t[3])
        }

    /** Interfaces a VPN client creates. macOS always has a few idle `utunN` (iCloud Private Relay, Continuity, Back to My Mac) — an interface alone means nothing; only routes through one do. */
    val TUNNEL_IFACE = Regex("^(utun|ipsec|ppp|tun|tap|gpd|wg|zt|feth)\\d+$")

    /** netstat's destination (`default`, `10/8`, `100.64/10`, `192.168.1`, `192.168.1.5/32`, `0/1`) as network + prefix. */
    fun parseDestination(dest: String): Pair<Long, Int>? {
        if (dest == "default") return 0L to 0
        val net = dest.substringBefore('/')
        val octets = net.split('.')
        if (octets.isEmpty() || octets.size > 4 || octets.any { it.toIntOrNull()?.takeIf { v -> v in 0..255 } == null }) return null
        val prefix = dest.substringAfter('/', "").toIntOrNull() ?: (octets.size * 8)
        val padded = (octets + List(4 - octets.size) { "0" }).joinToString(".")
        return (Ipv4.toLong(padded) ?: return null) to prefix
    }

    // ── ping ────────────────────────────────────────────────────────────────────────────────────

    data class PingStats(val sent: Int, val received: Int, val minMs: Double?, val avgMs: Double?, val maxMs: Double?, val stddevMs: Double?) {
        val lossPercent: Int get() = if (sent == 0) 100 else ((sent - received) * 100 / sent)
    }

    /** `5 packets transmitted, 5 packets received, 0.0% packet loss` + `round-trip min/avg/max/stddev = 6.347/53.217/130.115/52.660 ms`. */
    fun parsePing(output: String): PingStats? {
        val counts = Regex("(\\d+) packets transmitted, (\\d+) (?:packets )?received").find(output) ?: return null
        val rtt = Regex("min/avg/max/(?:stddev|mdev) = ([\\d.]+)/([\\d.]+)/([\\d.]+)/([\\d.]+)").find(output)
        val v = rtt?.groupValues?.drop(1)?.map { it.toDoubleOrNull() }
        return PingStats(counts.groupValues[1].toInt(), counts.groupValues[2].toInt(), v?.get(0), v?.get(1), v?.get(2), v?.get(3))
    }

    // ── socketfilterfw ──────────────────────────────────────────────────────────────────────────

    data class Firewall(val enabled: Boolean, val blockAll: Boolean, val stealth: Boolean)

    /**
     * `--getglobalstate` → `Firewall is disabled. (State = 0)` / `Firewall is enabled. (State = 1)`;
     * `--getblockall` → `Firewall has block all state set to disabled.` (older: `Block all INCOMING connections: DISABLED`);
     * `--getstealthmode` → `Firewall stealth mode is off` (older: `Stealth mode disabled`).
     */
    fun parseFirewall(global: String?, blockAll: String?, stealth: String?): Firewall? {
        if (global == null) return null
        fun on(s: String?) = s != null && Regex("\\b(enabled|on)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s) &&
            !Regex("\\b(disabled|off)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)
        val enabled = Regex("State = ([12])").containsMatchIn(global) || on(global)
        return Firewall(enabled, on(blockAll) || (blockAll?.contains("State = 2") == true) || global.contains("State = 2"), on(stealth))
    }

    // ── scutil --nc list ────────────────────────────────────────────────────────────────────────

    /** Names of system VPN configurations that are `(Connected)`: `* (Connected)  ABCD… IPSec "Office VPN"  [IPSec]`. */
    fun parseScutilNc(output: String): List<String> =
        output.lines().filter { it.contains("(Connected)") }.mapNotNull { line ->
            Regex("\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: line.trim().takeIf { it.isNotEmpty() }
        }

    // ── VPN clients by process name ─────────────────────────────────────────────────────────────

    /** Process-name fragments of VPN clients QA laptops actually run, and the name to show. */
    val VPN_CLIENTS: List<Pair<Regex, String>> = listOf(
        Regex("zscaler", RegexOption.IGNORE_CASE) to "Zscaler",
        Regex("^(PanGPS|PanGPA|GlobalProtect)", RegexOption.IGNORE_CASE) to "GlobalProtect",
        Regex("(vpnagentd|AnyConnect|Cisco Secure Client)", RegexOption.IGNORE_CASE) to "Cisco AnyConnect",
        Regex("^(tailscaled|Tailscale|IPNExtension)", RegexOption.IGNORE_CASE) to "Tailscale",
        Regex("(CloudflareWARP|Cloudflare WARP|warp-svc)", RegexOption.IGNORE_CASE) to "Cloudflare WARP",
        Regex("^(FortiClient|fctservctl|FortiTray)", RegexOption.IGNORE_CASE) to "FortiClient",
        Regex("(Pulse Secure|Ivanti Secure|dsAccessService)", RegexOption.IGNORE_CASE) to "Ivanti / Pulse Secure",
        Regex("^(NordVPN|nordvpnd)", RegexOption.IGNORE_CASE) to "NordVPN",
        Regex("ExpressVPN", RegexOption.IGNORE_CASE) to "ExpressVPN",
        Regex("ProtonVPN", RegexOption.IGNORE_CASE) to "Proton VPN",
        Regex("^Mullvad", RegexOption.IGNORE_CASE) to "Mullvad",
        Regex("^Surfshark", RegexOption.IGNORE_CASE) to "Surfshark",
        Regex("^(openvpn|OpenVPN Connect)", RegexOption.IGNORE_CASE) to "OpenVPN",
        Regex("^(wireguard-go|WireGuard)", RegexOption.IGNORE_CASE) to "WireGuard",
        Regex("^(NetExtender|SonicWall)", RegexOption.IGNORE_CASE) to "SonicWall NetExtender",
    )

    /** VPN clients among `ps -axco comm` process names, each named once. */
    fun vpnClients(processNames: List<String>): List<String> =
        VPN_CLIENTS.filter { (re, _) -> processNames.any { re.containsMatchIn(it.trim()) } }.map { it.second }.distinct()

    /** adb servers in `ps -axo pid=,command=`: `21434 adb -L tcp:5037 fork-server server --reply-fd 4`. */
    fun adbServers(psCommands: String): List<String> =
        psCommands.lines().map { it.trim() }.filter { Regex("(^|/|\\s)adb\\s.*fork-server\\s+server").containsMatchIn(it) }

    /** Android Studio's main process (`studio`, from Android Studio.app) in `ps -axco comm`. */
    fun studioRunning(processNames: List<String>): Boolean = processNames.any { it.trim() == "studio" || it.trim().startsWith("Android Studio") }

    /** `Version 37.0.1-15733141` from `adb version`. */
    fun adbVersion(output: String): String? =
        Regex("^Version\\s+(\\S+)", RegexOption.MULTILINE).find(output)?.groupValues?.get(1)
}

/** Dotted-quad arithmetic for the same-subnet and route-coverage checks. */
object Ipv4 {
    fun toLong(ip: String): Long? {
        val p = ip.split('.')
        if (p.size != 4) return null
        var v = 0L
        for (o in p) v = (v shl 8) or (o.toIntOrNull()?.takeIf { it in 0..255 } ?: return null).toLong()
        return v
    }

    fun mask(prefix: Int): Long = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix.coerceAtMost(32))) and 0xFFFFFFFFL

    fun sameNetwork(a: String, b: String, prefix: Int): Boolean {
        val x = toLong(a) ?: return false
        val y = toLong(b) ?: return false
        return (x and mask(prefix)) == (y and mask(prefix))
    }

    fun covers(network: Long, prefix: Int, ip: String): Boolean {
        val y = toLong(ip) ?: return false
        return (network and mask(prefix)) == (y and mask(prefix))
    }

    fun isV4(s: String): Boolean = toLong(s) != null
}
