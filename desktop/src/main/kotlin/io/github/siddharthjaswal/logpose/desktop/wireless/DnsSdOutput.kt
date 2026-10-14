package io.github.siddharthjaswal.logpose.desktop.wireless

/**
 * Parsers for what macOS's `/usr/bin/dns-sd` prints — the command-line face of the system's
 * mDNSResponder. Pure, and unit-tested against strings captured from this Mac (macOS 27, a phone
 * on home Wi-Fi). dns-sd prints one record per line and flushes after each result, so a long-running
 * browse can be read line by line; with no result yet it prints nothing at all (the header lines are
 * only flushed with the first result), so nothing here waits for a header.
 *
 * Why this exists: adb 37's own discovery (`adb mdns check` → `[adb discovery 0.0.0]`) missed a
 * phone's `_adb-tls-pairing._tcp` service that mDNSResponder saw at once, on a healthy home network
 * with the phone in Wi-Fi power-save. QR pairing therefore must not depend on adb's discovery.
 */
object DnsSdOutput {

    // ── dns-sd -B <type> local ──────────────────────────────────────────────────────────────────

    /** One `Add` / `Rmv` row of a browse. */
    data class BrowseEvent(val added: Boolean, val type: AdbOutput.ServiceType, val instance: String, val interfaceIndex: Int)

    // `11:08:12.134  Add        2  14 local.               _adb-tls-connect._tcp. adb-00173359D000017-zABNgD`
    // Columns: time, A/R, flags, if, domain, service type, instance name (the rest of the line — it may hold spaces).
    private val BROWSE = Regex("^(\\d{1,2}:\\d{2}:\\d{2}\\.\\d+)\\s+(Add|Rmv)\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(.+)$")

    /**
     * A browse row, or null for anything else: `Browsing for …`, `DATE: ---…---`, `…STARTING…`, the
     * `Timestamp  A/R  Flags …` column header, and blank lines.
     */
    fun parseBrowseLine(line: String): BrowseEvent? {
        val m = BROWSE.matchEntire(line.trim()) ?: return null
        val (_, ar, _, iface, _, type, instance) = m.destructured
        return BrowseEvent(ar == "Add", AdbOutput.ServiceType.of(type), instance.trim(), iface.toIntOrNull() ?: 0)
    }

    // ── dns-sd -L <instance> <type> local ───────────────────────────────────────────────────────

    /** Where an instance lives: an mDNS host name (`Android_10IY7ZSZ.local.`) and port, plus its TXT record. */
    data class Resolved(val host: String, val port: Int, val interfaceIndex: Int, val txt: Map<String, String> = emptyMap())

    // `… adb-…._adb-tls-connect._tcp.local. can be reached at Android.local.:41983 (interface 14)`
    private val REACHED = Regex("can be reached at (\\S+):(\\d+)\\s*(?:\\(interface (\\d+)\\))?")

    /**
     * The first `can be reached at host:port (interface N)` line of a lookup, with the TXT line that
     * follows it (` api=36.0 name=A059P v=1`, values with `\ ` escaped spaces), or null if none yet.
     */
    fun parseResolve(output: String): Resolved? {
        val lines = output.lines()
        val i = lines.indexOfFirst { REACHED.containsMatchIn(it) }
        if (i < 0) return null
        val m = REACHED.find(lines[i])!!
        val port = m.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val txt = lines.getOrNull(i + 1)?.takeIf { it.startsWith(" ") && '=' in it }?.let(::parseTxt).orEmpty()
        return Resolved(m.groupValues[1], port, m.groupValues[3].toIntOrNull() ?: 0, txt)
    }

    /** ` given_name=Pixel\ 8 serial=47041VDJH00186 v=2.2` → keys to values, `\ ` unescaped. */
    fun parseTxt(line: String): Map<String, String> {
        val tokens = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        val s = line.trim()
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> { cur.append(s[i + 1]); i++ }
                c == ' ' -> { if (cur.isNotEmpty()) tokens += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty()) tokens += cur.toString()
        return tokens.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    // ── dns-sd -G v4|v6|v4v6 <host> ─────────────────────────────────────────────────────────────

    data class AddressRecord(val added: Boolean, val host: String, val address: String, val interfaceIndex: Int) {
        val ipv6: Boolean get() = ':' in address
    }

    // `11:08:38.124  Add  40000002      14  Android.local.                         192.168.1.10                                 120`
    // `11:08:41.130  Add  40000003      14  Android_63CXP52F.local.                FE80:0000:0000:0000:7896:B4FF:FE10:4B94%en0  120`
    private val ADDR = Regex("^(\\d{1,2}:\\d{2}:\\d{2}\\.\\d+)\\s+(Add|Rmv)\\s+([0-9A-Fa-f]+)\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)(.*)$")

    /** An address row, or null for headers and negative answers (`No Such Record`, `0.0.0.0`). */
    fun parseAddressLine(line: String): AddressRecord? {
        val m = ADDR.matchEntire(line.trim()) ?: return null
        val (_, ar, _, iface, host, address, _, rest) = m.destructured
        if (rest.contains("No Such Record", ignoreCase = true)) return null
        if (address == "0.0.0.0" || address.trim(':', '0').isEmpty()) return null
        val looksLikeIp = IPV4.matches(address) || (':' in address && address.substringBefore('%').all { it.isLetterOrDigit() || it == ':' })
        if (!looksLikeIp) return null
        return AddressRecord(ar == "Add", host, address.lowercaseIpv6(), iface.toIntOrNull() ?: 0)
    }

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** dns-sd prints IPv6 expanded and upper-case (`FE80:0000:…%en0`); adb takes it either way, people read lower case. */
    private fun String.lowercaseIpv6(): String =
        if (':' !in this) this else substringBefore('%').lowercase() + (if ('%' in this) "%" + substringAfter('%') else "")

    /** The `ip:port` adb wants: bracketed for IPv6 (`[fe80::1%en0]:41983` — adb 37 accepts it for both pair and connect, verified live). */
    fun adbAddress(ip: String, port: Int): String = if (':' in ip) "[$ip]:$port" else "$ip:$port"
}
