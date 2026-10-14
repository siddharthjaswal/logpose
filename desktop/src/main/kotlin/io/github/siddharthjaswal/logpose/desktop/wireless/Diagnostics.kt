package io.github.siddharthjaswal.logpose.desktop.wireless

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * *Copy diagnostics*: the connection check as Markdown, for pasting into Slack or a support thread.
 * Built only from [DoctorFacts] and [Check]s — which never hold the pairing password — and the
 * user's home directory is shortened to `~`, so nothing personal or secret leaves in it.
 */
object Diagnostics {

    fun markdown(
        facts: DoctorFacts,
        checks: List<Check>,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        home: String = System.getProperty("user.home"),
    ): String = buildString {
        fun tidy(s: String) = if (home.isNotBlank()) s.replace(home, "~") else s
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(nowMillis))
        appendLine("**LogPose wireless diagnostics** — $time")
        appendLine()
        appendLine("- Mac: macOS ${facts.macOs ?: "?"}" + (facts.appVersion?.let { " · LogPose $it" } ?: ""))
        val inUse = facts.adbBinaries.firstOrNull { it.inUse }
        appendLine("- adb: ${inUse?.version ?: "?"}" + (inUse?.let { " (${tidy(it.path)})" } ?: "") +
            " · discovery: " + when (val d = facts.adbDiscovery) {
                is AdbOutput.MdnsCheck.Available -> d.backend
                is AdbOutput.MdnsCheck.Unavailable -> "unavailable (${d.detail.lineSequence().first().take(80)})"
                null -> "?"
            })
        facts.adbBinaries.filter { !it.inUse }.takeIf { it.isNotEmpty() }?.let { others ->
            appendLine("- Other adb: " + others.joinToString { "${it.version ?: "?"} (${tidy(it.path)})" })
        }
        appendLine("- adb servers running: ${facts.adbServers ?: "?"}" + if (facts.studioRunning) " · Android Studio is running" else "")
        appendLine()

        appendLine("**Findings**")
        checks.forEach { c -> appendLine("- [${c.verdict.word}] ${c.title}" + if (c.verdict != Verdict.OK) " — ${c.detail}" else "") }
        appendLine()

        appendLine("**Discovery**")
        appendLine("- macOS Bonjour: " + if (!facts.bonjourAvailable) "unavailable" else services(facts.bonjour, facts))
        appendLine("- adb mdns services: " + services(facts.adb, facts))
        appendLine()

        appendLine("**Network**")
        val lan = facts.interfaces.filterNot { SystemOutput.TUNNEL_IFACE.matches(it.name) }
        appendLine("- Mac: " + (lan.joinToString { "${it.name} ${it.cidr}" + if (it.name == facts.defaultInterface) " (default route)" else "" }.ifEmpty { "no IPv4 address" }))
        val p = facts.ping
        appendLine("- Phone: " + (facts.phoneIp ?: "not found") + (
            if (facts.phoneIp != null && p != null) " · ping ${p.received}/${p.sent}" +
                (if (p.avgMs != null) ", min/avg/max/stddev ${p.minMs}/${p.avgMs}/${p.maxMs}/${p.stddevMs} ms" else "")
            else ""
            ))
        val tunnels = facts.tunnelRoutes.joinToString { "${it.destination} via ${it.netif}" }
        appendLine("- VPN: " + buildList {
            if (facts.vpnClients.isNotEmpty()) add("running: ${facts.vpnClients.joinToString()}")
            if (facts.vpnConnected.isNotEmpty()) add("connected: ${facts.vpnConnected.joinToString()}")
            if (tunnels.isNotEmpty()) add("tunnel routes: $tunnels")
        }.joinToString(" · ").ifEmpty { "none (no VPN client, no IPv4 routes through a tunnel)" })
        appendLine("- Firewall: " + (facts.firewall?.let { "${if (it.enabled) "on" else "off"}, block-all ${if (it.blockAll) "on" else "off"}, stealth ${if (it.stealth) "on" else "off"}" } ?: "?"))

        val t = facts.pairing
        if (t != null) {
            appendLine()
            appendLine("**Last pairing attempt**")
            appendLine("- Outcome: ${t.outcome ?: "in progress (${t.currentStage?.name?.lowercase() ?: "starting"})"}")
            t.foundVia?.let { v ->
                val other = if (v == Via.BONJOUR) Via.ADB else Via.BONJOUR
                val otherSaw = t.pairingSeenAt[other]?.let { "${other.label} saw it at ${rel(it, t.startedAt)}" } ?: "${other.label} never saw it"
                appendLine("- Phone found via ${v.label} at ${rel(t.pairingSeenAt[v] ?: t.stageAt[Stage.FOUND], t.startedAt)} ($otherSaw)")
            } ?: if (t.serviceName != null) appendLine("- Phone never found (adb saw any service: ${t.adbSawAny}, Bonjour: ${t.bonjourSawAny})") else Unit
            t.connectVia?.let { appendLine("- Connected via ${it.label}" + (t.connectAddress?.let { a -> " ($a)" } ?: "")) }
            val stages = Stage.entries.mapNotNull { s -> t.stageAt[s]?.let { "${s.name.lowercase()} ${rel(it, t.startedAt)}" } }
            if (stages.isNotEmpty()) appendLine("- Timings: " + stages.joinToString(" → "))
        }
    }.trimEnd() + "\n"

    private fun services(list: List<SeenService>, f: DoctorFacts): String {
        if (list.isEmpty()) return "nothing"
        val ours = setOfNotNull(f.targetPairing) + f.targetGuids
        return list.joinToString { s ->
            val kind = when (s.type) {
                AdbOutput.ServiceType.PAIRING -> "pairing"
                AdbOutput.ServiceType.CONNECT -> "connect"
                else -> s.type.wire.ifEmpty { "other" }
            }
            "${s.instance} ($kind${s.address?.let { ", $it" } ?: ""})" + if (s.instance in ours) " ← ours" else ""
        }
    }

    private fun rel(at: Long?, start: Long): String =
        if (at == null) "?" else "+" + String.format(java.util.Locale.ROOT, "%.1f", (at - start) / 1000.0) + "s"
}
