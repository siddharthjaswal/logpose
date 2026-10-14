package io.github.siddharthjaswal.logpose.desktop.wireless

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Runs a system tool and returns its combined output, or null if it couldn't run / timed out. */
fun interface CommandRunner {
    fun run(command: List<String>, timeoutSeconds: Long): String?

    object System : CommandRunner {
        override fun run(command: List<String>, timeoutSeconds: Long): String? = runCatching {
            val proc = ProcessBuilder(command).redirectErrorStream(true).start()
            proc.outputStream.close()
            val out = StringBuilder()
            val reader = Thread { runCatching { proc.inputStream.bufferedReader().forEachLine { synchronized(out) { out.appendLine(it) } } } }
                .apply { isDaemon = true; start() }
            if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@runCatching null
            }
            reader.join(1_000)
            synchronized(out) { out.toString() }
        }.getOrNull()
    }
}

/** What the doctor is checking for: the pairing in progress (if any) and the phones the tester cares about. */
data class DoctorContext(
    val targetPairing: String? = null,
    val targetGuids: Set<String> = emptySet(),
    /** Last-known addresses (remembered phones) — a phone IP when discovery finds none. */
    val knownAddresses: List<String> = emptyList(),
    val pairing: PairingTrace? = null,
    val appVersion: String? = null,
)

/**
 * Gathers [DoctorFacts] by running the real tools — dns-sd (through [bonjour]), adb, `ifconfig`,
 * `route`, `netstat`, `ping`, `socketfilterfw`, `scutil`, `ps`, `sw_vers` — in parallel, in about
 * five seconds. Read-only: nothing here changes the Mac, adb's server or the phone. Blocking —
 * `Dispatchers.IO` only.
 */
class DoctorProbe(
    private val adb: AdbCommands,
    private val adbPath: () -> String?,
    private val bonjourBrowser: BonjourBrowser = BonjourBrowser.shared,
    private val cmd: CommandRunner = CommandRunner.System,
    private val home: String = java.lang.System.getProperty("user.home"),
    private val browseMillis: Long = 3_000,
) {

    /** [live]: the pairing flow's running Bonjour session, reused so the doctor sees what the flow saw. */
    fun run(ctx: DoctorContext, live: BonjourSession? = null): DoctorFacts {
        val own = if (live == null || !live.available) DnsSdSession(bonjourBrowser, setOf(AdbOutput.ServiceType.PAIRING, AdbOutput.ServiceType.CONNECT)) else null
        val session = own ?: live!!
        try {
            val system = CompletableFuture.supplyAsync { systemFacts() }
            val adbFacts = CompletableFuture.supplyAsync { adbFacts() }
            // Let the browse collect for a moment (a power-saving phone answers late), then resolve.
            if (own != null) Thread.sleep(browseMillis)
            val types = listOf(AdbOutput.ServiceType.PAIRING, AdbOutput.ServiceType.CONNECT)
            val wanted = ctx.targetGuids + setOfNotNull(ctx.targetPairing)
            val bonjourSeen = types.flatMap { t ->
                val names = session.everSeen(t) + session.seen(t)
                // Resolve the phones we care about first, then a few others, bounded.
                names.sortedBy { if (it in wanted) 0 else 1 }.take(4).map { n ->
                    SeenService(t, n, session.awaitEndpoint(n, t, 3_000)?.address)
                } + names.sortedBy { if (it in wanted) 0 else 1 }.drop(4).map { SeenService(t, it) }
            }
            val (discovery, adbSeen) = adbFacts.get(10, TimeUnit.SECONDS)
            val phoneIp = pickPhoneIp(ctx, bonjourSeen, adbSeen)
            val ping = phoneIp?.takeIf(Ipv4::isV4)?.let { ip ->
                cmd.run(listOf("/sbin/ping", "-c", "5", "-i", "0.3", "-t", "6", ip), 10)?.let(SystemOutput::parsePing)
            }
            val s = system.get(15, TimeUnit.SECONDS)
            return s.copy(
                appVersion = ctx.appVersion,
                bonjourAvailable = session.available || own?.available == true,
                bonjour = bonjourSeen,
                adbDiscovery = discovery,
                adb = adbSeen,
                targetPairing = ctx.targetPairing,
                targetGuids = ctx.targetGuids,
                phoneIp = phoneIp,
                ping = ping,
                pairing = ctx.pairing,
            )
        } finally {
            own?.close()
        }
    }

    private fun adbFacts(): Pair<AdbOutput.MdnsCheck, List<SeenService>> {
        val check = adb.mdnsCheck()
        val services = adb.mdnsServices().map { SeenService(it.type, it.instance, it.address) }
        return check to services
    }

    private fun systemFacts(): DoctorFacts {
        val macOs = cmd.run(listOf("/usr/bin/sw_vers", "-productVersion"), 3)?.trim()?.takeIf { it.isNotEmpty() }
        val ifaces = cmd.run(listOf("/sbin/ifconfig"), 3)?.let(SystemOutput::parseIfconfig).orEmpty()
        val default = cmd.run(listOf("/sbin/route", "-n", "get", "default"), 3)
            ?.lines()?.firstOrNull { it.trim().startsWith("interface:") }?.substringAfter(':')?.trim()
        val routes = cmd.run(listOf("/usr/sbin/netstat", "-rn", "-f", "inet"), 3)?.let(SystemOutput::parseRoutes).orEmpty()
            .filter { SystemOutput.TUNNEL_IFACE.matches(it.netif) }
        val names = cmd.run(listOf("/bin/ps", "-axco", "comm"), 3)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val commands = cmd.run(listOf("/bin/ps", "-axo", "pid=,command="), 3).orEmpty()
        val vpnConnected = cmd.run(listOf("/usr/sbin/scutil", "--nc", "list"), 3)?.let(SystemOutput::parseScutilNc).orEmpty()
        val fwTool = "/usr/libexec/ApplicationFirewall/socketfilterfw"
        val firewall = SystemOutput.parseFirewall(
            cmd.run(listOf(fwTool, "--getglobalstate"), 3), cmd.run(listOf(fwTool, "--getblockall"), 3), cmd.run(listOf(fwTool, "--getstealthmode"), 3),
        )
        val inUse = adbPath()?.let { canonical(it) }
        val binaries = adbCandidates(inUse).map { path ->
            AdbBinary(path, cmd.run(listOf(path, "version"), 5)?.let(SystemOutput::adbVersion), path == inUse)
        }
        val sdkAdb = canonical("$home/Library/Android/sdk/platform-tools/adb").takeIf { File(it).canExecute() && it != inUse }
        return DoctorFacts(
            macOs = macOs, interfaces = ifaces, defaultInterface = default, tunnelRoutes = routes,
            vpnClients = SystemOutput.vpnClients(names), vpnConnected = vpnConnected, firewall = firewall,
            adbBinaries = binaries, adbServers = SystemOutput.adbServers(commands).size,
            studioRunning = SystemOutput.studioRunning(names), studioAdb = sdkAdb,
        )
    }

    private fun canonical(path: String): String = runCatching { File(path).canonicalPath }.getOrDefault(path)

    /** The adb binaries a QA Mac usually has: the one in use, the Android SDK's, Homebrew's, LogPose's download. */
    private fun adbCandidates(inUse: String?): List<String> = buildList {
        inUse?.let(::add)
        java.lang.System.getenv("ANDROID_HOME")?.let { add("$it/platform-tools/adb") }
        java.lang.System.getenv("ANDROID_SDK_ROOT")?.let { add("$it/platform-tools/adb") }
        add("$home/Library/Android/sdk/platform-tools/adb")
        add("/opt/homebrew/bin/adb")
        add("/usr/local/bin/adb")
        add("$home/Library/Application Support/LogPose/platform-tools/adb")
    }.filter { File(it).canExecute() }.map(::canonical).distinct()

    companion object {
        /** The phone to ping: a target's resolved address, then the pairing address, any resolved phone, a remembered address. */
        fun pickPhoneIp(ctx: DoctorContext, bonjour: List<SeenService>, adb: List<SeenService>): String? {
            val all = bonjour + adb
            fun host(address: String?) = address?.let { AdbOutput.splitHostPort(it)?.first }
            val wanted = ctx.targetGuids + setOfNotNull(ctx.targetPairing)
            return all.firstOrNull { it.instance in wanted && it.address != null }?.address?.let(::host)
                ?: host(ctx.pairing?.pairAddress)
                ?: all.firstOrNull { it.type == AdbOutput.ServiceType.CONNECT && it.address != null }?.address?.let(::host)
                ?: ctx.knownAddresses.firstNotNullOfOrNull { host(it) }
        }
    }
}
