package io.github.siddharthjaswal.logpose.desktop.wireless

import io.github.siddharthjaswal.logpose.desktop.wireless.SystemOutput.Firewall
import io.github.siddharthjaswal.logpose.desktop.wireless.SystemOutput.MacInterface
import io.github.siddharthjaswal.logpose.desktop.wireless.SystemOutput.PingStats
import io.github.siddharthjaswal.logpose.desktop.wireless.SystemOutput.Route

/** One adb service as one discovery source saw it. [address] is null when it wasn't resolved. */
data class SeenService(val type: AdbOutput.ServiceType, val instance: String, val address: String? = null, val name: String? = null)

/** An adb binary on this Mac and its version (`37.0.1-15733141`). */
data class AdbBinary(val path: String, val version: String?, val inUse: Boolean)

/**
 * Everything the doctor looked at, as plain values — gathered by [DoctorProbe], judged by
 * [ConnectionDoctor.diagnose], written up by [Diagnostics]. Nulls mean "couldn't tell".
 */
data class DoctorFacts(
    val macOs: String? = null,
    val appVersion: String? = null,
    // discovery
    val bonjourAvailable: Boolean = true,
    val bonjour: List<SeenService> = emptyList(),
    val adbDiscovery: AdbOutput.MdnsCheck? = null,
    val adb: List<SeenService> = emptyList(),
    /** What we are trying to reach: our QR pairing instance and/or a phone's connect guid. */
    val targetPairing: String? = null,
    val targetGuids: Set<String> = emptySet(),
    // network
    val interfaces: List<MacInterface> = emptyList(),
    val defaultInterface: String? = null,
    val phoneIp: String? = null,
    val ping: PingStats? = null,
    val tunnelRoutes: List<Route> = emptyList(),
    val vpnClients: List<String> = emptyList(),
    val vpnConnected: List<String> = emptyList(),
    val firewall: Firewall? = null,
    // adb
    val adbBinaries: List<AdbBinary> = emptyList(),
    val adbServers: Int? = null,
    val studioRunning: Boolean = false,
    /** Android Studio's SDK adb, when it exists and isn't the one LogPose uses — what "Use Android Studio's adb" switches to. */
    val studioAdb: String? = null,
    // the attempt that triggered the check, if any
    val pairing: PairingTrace? = null,
)

/** What a finding offers the tester. Each is wired to one verb in the sheet. */
enum class FixAction(val label: String) {
    USE_CABLE("Use USB cable"),
    PAIRING_CODE("Try pairing code"),
    CONNECT_BY_IP("Enter IP & port manually"),
    RETRY_QR("Try again"),
    OPEN_LOCAL_NETWORK("Open Local Network settings"),
    OPEN_FIREWALL("Open Firewall settings"),
    USE_STUDIO_ADB("Use Android Studio's adb"),
}

enum class Verdict(val rank: Int, val word: String) { PROBLEM(0, "Problem"), WARNING(1, "Warning"), INFO(2, "Note"), OK(3, "OK"), UNKNOWN(4, "Can't check") }

enum class CheckId { DISCOVERY, ADB_MISSED, ISOLATION, NOT_ADVERTISING, SUBNET, REACHABILITY, POWER_SAVE, VPN, FIREWALL, ADB_CONFLICT, LOCAL_NETWORK, PHONE_SIDE }

data class Check(val id: CheckId, val verdict: Verdict, val title: String, val detail: String, val actions: List<FixAction> = emptyList())

/**
 * The connection doctor's judgement: pure decisions over [DoctorFacts], each check a plain-English
 * verdict and the fix it offers. Ordered by severity, so [top] is the one line the stall panel shows.
 */
object ConnectionDoctor {

    /** RTTs a phone that is awake on home Wi-Fi beats easily; above them it is napping between beacons. */
    const val SLOW_AVG_MS = 50.0
    const val SLOW_MAX_MS = 150.0
    const val JITTER_MS = 40.0

    fun diagnose(f: DoctorFacts): List<Check> = buildList {
        addAll(discovery(f))
        subnet(f)?.let(::add)
        reachability(f)?.let(::add)
        add(vpn(f))
        firewall(f)?.let(::add)
        adbConflict(f)?.let(::add)
        add(localNetwork(f))
        add(phoneSide())
    }.sortedBy { it.verdict.rank }

    /** The finding worth interrupting for: the worst problem or warning, else a note, else null. */
    fun top(checks: List<Check>): Check? =
        checks.filter { it.id != CheckId.PHONE_SIDE }.minByOrNull { it.verdict.rank }?.takeIf { it.verdict.rank <= Verdict.INFO.rank }

    private fun anyAdbService(list: List<SeenService>) = list.any { it.type != AdbOutput.ServiceType.OTHER }

    private fun discovery(f: DoctorFacts): List<Check> {
        val out = mutableListOf<Check>()
        val bonjourAny = anyAdbService(f.bonjour)
        val adbAny = anyAdbService(f.adb)
        val phones = (f.bonjour + f.adb).filter { it.type == AdbOutput.ServiceType.CONNECT }.map { it.instance }.toSet()
        val pairing = f.pairing

        // Seen by Bonjour, missed by adb: the macOS adb discovery issue. Informational — LogPose copes.
        val targets = setOfNotNull(f.targetPairing) + f.targetGuids
        val bonjourNames = f.bonjour.map { it.instance }.toSet()
        val adbNames = f.adb.map { it.instance }.toSet()
        val missed = if (targets.isNotEmpty()) targets.filter { it in bonjourNames && it !in adbNames }
        else f.bonjour.filter { it.type == AdbOutput.ServiceType.CONNECT && it.instance !in adbNames }.map { it.instance }
        val pairedViaBonjour = pairing?.foundVia == Via.BONJOUR && Via.ADB !in pairing.pairingSeenAt
        if (missed.isNotEmpty() || pairedViaBonjour) {
            out += Check(
                CheckId.ADB_MISSED, Verdict.INFO,
                if (pairedViaBonjour) "adb's discovery missed your phone (a known adb issue on macOS) — LogPose paired directly over Bonjour"
                else "adb's discovery missed your phone (a known adb issue on macOS) — LogPose finds it directly over Bonjour",
                "macOS's own Bonjour sees the phone, adb's built-in discovery doesn't. Nothing to fix: LogPose uses " +
                    "the address macOS found. Android Studio, which relies on adb's discovery, may hang on this Mac.",
            )
        }

        if (f.bonjourAvailable && !bonjourAny && !adbAny) {
            out += Check(
                CheckId.ISOLATION, Verdict.PROBLEM,
                "This Mac can't see any phone on the network",
                "Nothing on this Wi-Fi is advertising Wireless debugging. If it's turned on on the phone, the network " +
                    "is probably keeping devices apart: office and guest Wi-Fi do this (\"client isolation\"), and so can a " +
                    "mesh or extender that puts the phone and the Mac on different bands. A pairing code won't help " +
                    "either — it needs the same path. Use the USB cable, or turn on the phone's hotspot and join it from this Mac.",
                listOf(FixAction.USE_CABLE),
            )
        } else if (f.targetPairing != null && f.targetPairing !in bonjourNames && f.targetPairing !in adbNames) {
            out += Check(
                CheckId.NOT_ADVERTISING, Verdict.WARNING,
                "Your phone isn't advertising the pairing code yet",
                "Other devices are visible on this Wi-Fi, but not the phone you're pairing. On the phone, stay on " +
                    "\"Pair device with QR code\" with the screen on, and check it's on this Wi-Fi — Wireless debugging " +
                    "turns off when the phone changes networks.",
                listOf(FixAction.PAIRING_CODE, FixAction.USE_CABLE),
            )
        } else if (bonjourAny || adbAny) {
            val via = buildList { if (bonjourAny) add("macOS Bonjour"); if (adbAny) add("adb") }.joinToString(" and ")
            out += Check(
                CheckId.DISCOVERY, Verdict.OK,
                if (phones.size == 1) "1 phone with Wireless debugging is visible" else "${phones.size} phones with Wireless debugging are visible",
                "Seen by $via." + if (!f.bonjourAvailable) " (macOS Bonjour couldn't be used.)" else "",
            )
        }
        if (!f.bonjourAvailable && !adbAny) {
            out += Check(CheckId.DISCOVERY, Verdict.UNKNOWN, "Couldn't search the network", "Neither dns-sd nor adb's discovery ran on this Mac.", listOf(FixAction.PAIRING_CODE, FixAction.USE_CABLE))
        }
        return out
    }

    private fun subnet(f: DoctorFacts): Check? {
        val ip = f.phoneIp?.takeIf(Ipv4::isV4) ?: return null
        val lan = f.interfaces.filterNot { SystemOutput.TUNNEL_IFACE.matches(it.name) }
        if (lan.isEmpty()) return Check(CheckId.SUBNET, Verdict.UNKNOWN, "Couldn't read this Mac's network address", "ifconfig listed no IPv4 address.")
        val home = lan.firstOrNull { it.contains(ip) }
        return if (home != null) {
            Check(CheckId.SUBNET, Verdict.OK, "Phone and Mac are on the same network", "Phone $ip, Mac ${home.name} ${home.cidr}.")
        } else {
            val mac = (lan.firstOrNull { it.name == f.defaultInterface } ?: lan.first())
            Check(
                CheckId.SUBNET, Verdict.PROBLEM,
                "Your phone is on a different network",
                "The phone is at $ip but this Mac is on ${mac.cidr} (${mac.name}). Join the same Wi-Fi on both — not a " +
                    "guest network or a separately named 5 GHz twin.",
                listOf(FixAction.USE_CABLE),
            )
        }
    }

    private fun reachability(f: DoctorFacts): Check? {
        val ip = f.phoneIp ?: return null
        val p = f.ping ?: return Check(CheckId.REACHABILITY, Verdict.UNKNOWN, "Couldn't ping the phone", "ping didn't run.")
        if (p.received == 0) {
            return Check(
                CheckId.REACHABILITY, Verdict.PROBLEM,
                "This Mac can't reach your phone at $ip",
                "The phone is listed on the network but didn't answer ${p.sent} pings. Wake the phone and keep the " +
                    "screen on; if it still doesn't answer, the network (client isolation) or a VPN is in the way.",
                listOf(FixAction.USE_CABLE),
            )
        }
        val avg = p.avgMs ?: 0.0
        val max = p.maxMs ?: 0.0
        val sd = p.stddevMs ?: 0.0
        val range = "${fmt(p.minMs)}–${fmt(p.maxMs)} ms"
        return if (avg > SLOW_AVG_MS || max > SLOW_MAX_MS || sd > JITTER_MS || p.lossPercent > 0) {
            Check(
                CheckId.POWER_SAVE, Verdict.WARNING,
                "Your phone is saving Wi-Fi power",
                "It answers in $range (average ${fmt(avg)} ms${if (p.lossPercent > 0) ", ${p.lossPercent}% lost" else ""}) — an " +
                    "awake phone on home Wi-Fi answers in a few ms. A napping phone answers discovery late; keep the " +
                    "screen on and stay on the Wireless debugging page while pairing.",
            )
        } else {
            Check(CheckId.REACHABILITY, Verdict.OK, "Your phone answers quickly", "Ping $ip: $range, average ${fmt(avg)} ms.")
        }
    }

    /**
     * A VPN only matters when it carries traffic: a default or split-default route (`0/1`,
     * `128.0/1`) through a tunnel interface, a tunnel route covering the phone, a connected system VPN,
     * or a known VPN client running. Idle `utunN` interfaces alone (every Mac has some) are not a VPN.
     */
    private fun vpn(f: DoctorFacts): Check {
        val capturing = f.tunnelRoutes.filter { r ->
            val d = SystemOutput.parseDestination(r.destination) ?: return@filter false
            d.second <= 1 || (f.phoneIp?.let { Ipv4.covers(d.first, d.second, it) } == true)
        }
        val reasons = buildList {
            capturing.map { it.netif }.distinct().forEach { add("$it carries ${if (capturing.any { r -> r.netif == it && SystemOutput.parseDestination(r.destination)!!.second <= 1 }) "this Mac's default route" else "the route to your phone"}") }
            f.vpnConnected.forEach { add("\"$it\" is connected") }
            f.vpnClients.forEach { add("$it is running") }
        }
        return if (reasons.isEmpty()) {
            Check(CheckId.VPN, Verdict.OK, "No VPN in the way", "No VPN client running and no traffic routed through a tunnel.")
        } else {
            Check(
                CheckId.VPN, if (capturing.isNotEmpty() || f.vpnConnected.isNotEmpty()) Verdict.WARNING else Verdict.INFO,
                "A VPN can block local devices — pause it while pairing",
                reasons.joinToString("; ") + ". Many VPNs send all traffic, " +
                    "including to your phone, through the tunnel. Pause it, pair, and check again.",
            )
        }
    }

    private fun firewall(f: DoctorFacts): Check? {
        val fw = f.firewall ?: return null
        return when {
            fw.enabled && fw.blockAll -> Check(
                CheckId.FIREWALL, Verdict.WARNING,
                "The macOS firewall blocks all incoming connections",
                "adb's own connections go out from this Mac and usually still work, but if pairing stalls, turn off " +
                    "\"Block all incoming connections\" while you pair.",
                listOf(FixAction.OPEN_FIREWALL),
            )
            fw.enabled && fw.stealth -> Check(CheckId.FIREWALL, Verdict.OK, "Firewall: on, stealth mode", "Stealth mode only hides this Mac from pings; it doesn't affect adb.")
            fw.enabled -> Check(CheckId.FIREWALL, Verdict.OK, "Firewall: on", "Allows adb's outgoing connections.")
            else -> Check(CheckId.FIREWALL, Verdict.OK, "Firewall: off", "Nothing on this Mac blocks the connection.")
        }
    }

    private fun adbConflict(f: DoctorFacts): Check? {
        if (f.adbBinaries.isEmpty() && f.adbServers == null) return null
        val inUse = f.adbBinaries.firstOrNull { it.inUse }
        val versions = f.adbBinaries.mapNotNull { it.version }.distinct()
        val servers = f.adbServers ?: 0
        val others = f.adbBinaries.filter { !it.inUse && it.version != null && it.version != inUse?.version }
        return when {
            servers > 1 -> Check(
                CheckId.ADB_CONFLICT, Verdict.WARNING,
                "$servers adb servers are running",
                "Two adb servers fight over the same phones and connections drop. Quit the other tool (or run " +
                    "`adb kill-server` once) so only one is left.",
                listOfNotNull(FixAction.USE_STUDIO_ADB.takeIf { f.studioAdb != null }),
            )
            versions.size > 1 && f.studioRunning && others.isNotEmpty() -> Check(
                CheckId.ADB_CONFLICT, Verdict.WARNING,
                "Android Studio and LogPose use different adb versions",
                "LogPose uses adb ${inUse?.version ?: "?"}; ${others.joinToString { "${it.version} at ${it.path}" }} is also " +
                    "installed. With both running, each restarts the other's adb server (\"server version doesn't match\") " +
                    "and connections drop. Use one adb.",
                listOfNotNull(FixAction.USE_STUDIO_ADB.takeIf { f.studioAdb != null }),
            )
            else -> Check(
                CheckId.ADB_CONFLICT, Verdict.OK,
                "adb ${inUse?.version ?: versions.firstOrNull() ?: ""}".trim() + if (servers == 1) ", one server" else "",
                if (versions.size > 1) "Other versions are installed (${versions.joinToString()}) but aren't running against it." else "No competing adb.",
            )
        }
    }

    /**
     * macOS 15+ gates local-network access per app and offers no API to read the switch. If Bonjour
     * (a browse attributed to LogPose) sees phones, the permission is evidently fine; if it sees
     * nothing at all, a denied permission is one explanation, so the setting is offered.
     */
    private fun localNetwork(f: DoctorFacts): Check {
        val seesSomething = anyAdbService(f.bonjour)
        return when {
            seesSomething -> Check(CheckId.LOCAL_NETWORK, Verdict.OK, "Local Network access works", "LogPose can see devices on your Wi-Fi, so macOS isn't blocking it.", listOf(FixAction.OPEN_LOCAL_NETWORK))
            else -> Check(
                CheckId.LOCAL_NETWORK, if (anyAdbService(f.adb)) Verdict.UNKNOWN else Verdict.WARNING,
                "macOS may be blocking LogPose from your Wi-Fi",
                "On macOS 15 and later, apps need permission to reach devices on the local network. macOS asks the " +
                    "first time LogPose looks for a phone; if you clicked Don't Allow, turn LogPose on under Privacy & " +
                    "Security → Local Network. (macOS doesn't let apps read this setting, so LogPose can't tell for sure.)",
                listOf(FixAction.OPEN_LOCAL_NETWORK),
            )
        }
    }

    private fun phoneSide() = Check(
        CheckId.PHONE_SIDE, Verdict.INFO,
        "On the phone",
        "Wireless debugging is per Wi-Fi network: it switches off when the phone joins another network. A pairing " +
            "code expires when you leave its dialog. Keep the screen on until LogPose says Connected.",
    )

    private fun fmt(v: Double?): String = when {
        v == null -> "?"
        v >= 10 -> v.toInt().toString()
        else -> String.format(java.util.Locale.ROOT, "%.1f", v)
    }
}
