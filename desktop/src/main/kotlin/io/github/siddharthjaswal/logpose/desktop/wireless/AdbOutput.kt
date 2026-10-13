package io.github.siddharthjaswal.logpose.desktop.wireless

/**
 * Parsers for what adb prints during wireless setup. Pure and unit-tested against strings captured
 * from a real adb (platform-tools 37.0.1) and read out of adb's source
 * (`packages/modules/adb`: `client/adb_wifi.cpp`, `client/transport_mdns.cpp`,
 * `client/transport_emulator.cpp`, `adb.cpp`).
 *
 * adb reports most failures on **stdout with exit code 0** (`adb connect` to a refused port, a wrong
 * pairing code), so callers decide on the text, never on the exit code alone.
 */
object AdbOutput {

    // ── adb mdns check ──────────────────────────────────────────────────────────────────────────

    sealed interface MdnsCheck {
        /** e.g. `mdns daemon version [adb discovery 0.0.0]` (37.x) or `[Openscreen discovery 0.0.0]`. */
        data class Available(val backend: String) : MdnsCheck
        /** `ERROR: mdns discovery disabled`, `ERROR: mdns daemon unavailable`, or anything unexpected. */
        data class Unavailable(val detail: String) : MdnsCheck
    }

    fun parseMdnsCheck(output: String): MdnsCheck {
        val line = output.lineSequence().map { it.trim() }.firstOrNull { it.contains("mdns daemon version") }
        if (line != null) {
            val backend = line.substringAfter('[', "").substringBeforeLast(']', "").ifBlank { line }
            return MdnsCheck.Available(backend)
        }
        return MdnsCheck.Unavailable(output.trim().ifEmpty { "adb printed nothing" })
    }

    // ── adb mdns services ───────────────────────────────────────────────────────────────────────

    enum class ServiceType(val wire: String) {
        /** A phone showing *Pair device with QR code / pairing code*. */
        PAIRING("_adb-tls-pairing._tcp"),
        /** A phone with Wireless debugging on, ready to accept paired hosts. */
        CONNECT("_adb-tls-connect._tcp"),
        /** Legacy `adb tcpip` advertisement (rare; some emulators/TVs). */
        LEGACY("_adb._tcp"),
        OTHER(""),
        ;

        companion object {
            fun of(raw: String): ServiceType {
                val t = raw.trim().trimEnd('.')
                return entries.firstOrNull { it != OTHER && it.wire == t } ?: OTHER
            }
        }
    }

    /** One line of `adb mdns services`: `instance\tservice-type\tip:port`. */
    data class MdnsService(val instance: String, val type: ServiceType, val host: String, val port: Int) {
        val address: String get() = "$host:$port"
    }

    /**
     * Parses `adb mdns services`. The client prints a `List of discovered mdns services` header, then
     * the server's `%s\t%s\t%s:%u\n` rows (instance, service type, IPv4, port). Tabs are the real
     * separator; whitespace is accepted too in case an older Bonjour backend aligns columns.
     */
    fun parseMdnsServices(output: String): List<MdnsService> =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("List of discovered") && !it.startsWith("*") }
            .mapNotNull { line ->
                val parts = if ('\t' in line) line.split('\t').map { it.trim() } else line.split(Regex("\\s+"))
                if (parts.size < 3) return@mapNotNull null
                val (host, port) = splitHostPort(parts.last()) ?: return@mapNotNull null
                // An instance name may in principle contain spaces; everything before the type is it.
                val type = ServiceType.of(parts[parts.size - 2])
                val instance = parts.subList(0, parts.size - 2).joinToString(" ")
                if (instance.isEmpty()) null else MdnsService(instance, type, host, port)
            }
            .toList()

    // ── adb pair ────────────────────────────────────────────────────────────────────────────────

    sealed interface PairResult {
        /** `Successfully paired to 192.168.1.20:37755 [guid=adb-14141FDF600081-QoXtBe]`. */
        data class Success(val guid: String?) : PairResult
        /** `Failed: Wrong password or connection was dropped.` */
        data object WrongCode : PairResult
        /** The pairing port isn't answering: the phone left the pairing screen, or the address is wrong. */
        data class Unreachable(val detail: String) : PairResult
        data class Failed(val detail: String) : PairResult
    }

    fun parsePair(output: String): PairResult {
        val text = output.trim()
        val success = text.lineSequence().firstOrNull { it.trim().startsWith("Successfully paired to") }
        if (success != null) {
            val guid = Regex("\\[guid=([^\\]]+)]").find(success)?.groupValues?.get(1)
            return PairResult.Success(guid)
        }
        return when {
            text.contains("Wrong password", ignoreCase = true) -> PairResult.WrongCode
            text.contains("Unable to start pairing client", ignoreCase = true) ||
                text.contains("unable to create pairing client", ignoreCase = true) ||
                // adb 37.x, observed live, when nothing listens on the pairing port.
                text.contains("protocol fault", ignoreCase = true) ||
                text.contains("Failed to parse address", ignoreCase = true) ||
                text.contains("Invalid port", ignoreCase = true) -> PairResult.Unreachable(text)
            else -> PairResult.Failed(text.ifEmpty { "adb printed nothing" })
        }
    }

    // ── adb connect / disconnect ────────────────────────────────────────────────────────────────

    sealed interface ConnectResult {
        data class Connected(val serial: String) : ConnectResult
        data class AlreadyConnected(val serial: String) : ConnectResult
        /** `failed to authenticate to …`: the phone refused this Mac's key (needs pairing / Allow). */
        data class NotAuthorized(val detail: String) : ConnectResult
        /** Refused, unroutable, unresolvable or timed out — the phone isn't listening there. */
        data class Unreachable(val detail: String) : ConnectResult
        data class Failed(val detail: String) : ConnectResult
    }

    fun parseConnect(output: String): ConnectResult {
        val text = output.trim()
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        return when {
            first.startsWith("already connected to ") -> ConnectResult.AlreadyConnected(first.removePrefix("already connected to ").trim())
            first.startsWith("connected to ") -> ConnectResult.Connected(first.removePrefix("connected to ").trim())
            first.startsWith("failed to authenticate") -> ConnectResult.NotAuthorized(first)
            first.startsWith("failed to connect") || first.startsWith("cannot connect") ||
                first.startsWith("failed to resolve host") || first.contains("timed out", ignoreCase = true) ->
                ConnectResult.Unreachable(first)
            else -> ConnectResult.Failed(text.ifEmpty { "adb printed nothing" })
        }
    }

    /** `adb tcpip 5555` prints `restarting in TCP mode port: 5555` on success. */
    fun tcpipOk(output: String): Boolean = output.contains("restarting in TCP mode")

    // ── Wi-Fi address of a cable-attached phone ─────────────────────────────────────────────────

    /**
     * The phone's Wi-Fi IPv4, from `ip route` (preferred: one line names both the interface and the
     * `src` address, e.g. `192.168.1.0/24 dev wlan0 proto kernel scope link src 192.168.1.42`) or,
     * failing that, `ip addr show wlan0` (`inet 192.168.1.42/24 brd … scope global wlan0`).
     * Cellular (`rmnet*`) and VPN (`tun*`) interfaces are never chosen.
     */
    fun wifiIp(ipRoute: String?, ipAddr: String?): String? =
        ipRoute?.let(::wifiIpFromRoute) ?: ipAddr?.let(::wifiIpFromAddr)

    private val WIFI_IFACE = Regex("^(wlan|swlan|wifi|eth)\\d*$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun wifiIpFromRoute(output: String): String? =
        output.lineSequence().mapNotNull { line ->
            val t = line.trim().split(Regex("\\s+"))
            val dev = t.indexOf("dev").takeIf { it >= 0 }?.let { t.getOrNull(it + 1) } ?: return@mapNotNull null
            val src = t.indexOf("src").takeIf { it >= 0 }?.let { t.getOrNull(it + 1) } ?: return@mapNotNull null
            if (WIFI_IFACE.matches(dev) && isUsableIpv4(src)) dev to src else null
        }.sortedBy { if (it.first.startsWith("wlan")) 0 else 1 }.firstOrNull()?.second

    fun wifiIpFromAddr(output: String): String? =
        output.lineSequence().mapNotNull { line ->
            val t = line.trim().split(Regex("\\s+"))
            if (t.firstOrNull() != "inet") return@mapNotNull null
            val ip = t.getOrNull(1)?.substringBefore('/') ?: return@mapNotNull null
            val iface = t.last()
            if (isUsableIpv4(ip) && WIFI_IFACE.matches(iface)) ip else null
        }.firstOrNull()

    private fun isUsableIpv4(s: String): Boolean {
        val m = IPV4.matchEntire(s) ?: return false
        val o = m.groupValues.drop(1).map { it.toInt() }
        if (o.any { it > 255 }) return false
        return o[0] != 127 && o[0] != 0 && !(o[0] == 169 && o[1] == 254)
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** `192.168.1.20:37755` → host and port; null if it isn't one. */
    fun splitHostPort(s: String): Pair<String, Int>? {
        val i = s.lastIndexOf(':')
        if (i <= 0 || i == s.length - 1) return null
        val port = s.substring(i + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return s.substring(0, i).removePrefix("[").removeSuffix("]") to port
    }

    /** A form field the tester typed: `192.168.1.20:37755` with stray spaces tolerated. */
    fun normalizeAddress(input: String): String? {
        val s = input.trim().replace(" ", "")
        val (host, port) = splitHostPort(s) ?: return null
        if (host.isEmpty()) return null
        return "$host:$port"
    }

    /** The six digits the phone shows, tolerating `123 456` and `123-456`. */
    fun normalizePairingCode(input: String): String? =
        input.filter { it.isDigit() }.takeIf { it.length == 6 && input.all { c -> c.isDigit() || c == ' ' || c == '-' } }
}
