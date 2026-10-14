package io.github.siddharthjaswal.logpose.desktop.wireless

import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A child process whose stdout is read line by line; [kill] must always end it. */
interface LiveProcess {
    /** Blocks for the next stdout line; null at end of stream (the process ended or was killed). */
    fun readLine(): String?
    fun isAlive(): Boolean
    fun kill()
}

/** Starts processes — behind an interface so tests can prove every one is killed. */
fun interface ProcessRunner {
    fun start(command: List<String>): LiveProcess?

    object System : ProcessRunner {
        override fun start(command: List<String>): LiveProcess? = runCatching {
            val proc = ProcessBuilder(command).redirectErrorStream(true).start()
            proc.outputStream.close()
            val reader = proc.inputStream.bufferedReader()
            object : LiveProcess {
                override fun readLine(): String? = runCatching { reader.readLine() }.getOrNull()
                override fun isAlive(): Boolean = proc.isAlive
                override fun kill() {
                    proc.destroy() // SIGTERM — dns-sd exits on it
                    if (!proc.waitFor(300, TimeUnit.MILLISECONDS)) proc.destroyForcibly()
                    runCatching { reader.close() }
                }
            }
        }.getOrNull()
    }
}

/** A service found and resolved through macOS Bonjour. [ip] is IPv4 when the phone has one, else IPv6 with its scope. */
data class BonjourEndpoint(
    val instance: String,
    val type: AdbOutput.ServiceType,
    /** The mDNS host name, e.g. `Android_10IY7ZSZ.local.`. */
    val host: String,
    val ip: String,
    val port: Int,
    val txt: Map<String, String> = emptyMap(),
) {
    val address: String get() = DnsSdOutput.adbAddress(ip, port)
}

/**
 * Discovery straight through macOS's mDNSResponder, by driving `/usr/bin/dns-sd` (always present on
 * macOS; no JNA, no dependency). Three verbs:
 *
 *  - **browse** `dns-sd -B <type> local` — runs forever by design; one process per service type per
 *    need, read on a daemon thread, and killed by [Browse.close];
 *  - **resolve** `dns-sd -L <instance> <type> local` → host + port (one-shot, killed at the first
 *    answer or the deadline);
 *  - **address** `dns-sd -G v4v6 <host>` → an IPv4 (preferred) or scoped IPv6 (one-shot, likewise).
 *
 * Every process started is tracked in [live] until it is killed, and [closeAll] (window close, app
 * exit, and a JVM shutdown hook on the real instance) kills whatever is left — a dns-sd never
 * outlives the app. All of it is blocking process I/O: callers run on `Dispatchers.IO`.
 *
 * Local Network privacy (macOS 15+): mDNSResponder does the network I/O, but Apple attributes a
 * Bonjour browse to the *responsible* app — for a child process like dns-sd (or adb) that is
 * LogPose — so these browses are covered by LogPose's Local Network permission like adb's own
 * traffic is, and the first one is what makes macOS show the prompt (the app declares
 * NSLocalNetworkUsageDescription and NSBonjourServices for both adb service types).
 */
class BonjourBrowser(
    private val runner: ProcessRunner = ProcessRunner.System,
    private val dnsSd: String = DNS_SD,
    private val exists: () -> Boolean = { File(dnsSd).canExecute() },
) {
    /** Processes started and not yet killed — visible to tests, and what [closeAll] ends. */
    val live: MutableSet<LiveProcess> = Collections.newSetFromMap(ConcurrentHashMap())

    val available: Boolean get() = exists()

    private fun start(args: List<String>): LiveProcess? =
        if (!available) null else runner.start(listOf(dnsSd) + args)?.also { live += it }

    private fun kill(p: LiveProcess) {
        runCatching { p.kill() }
        live -= p
    }

    /** A running `dns-sd -B`: the instances currently advertised, updated as Add/Rmv lines arrive. */
    inner class Browse internal constructor(val type: AdbOutput.ServiceType, private val proc: LiveProcess?, private val onEvent: (DnsSdOutput.BrowseEvent) -> Unit) : AutoCloseable {
        private val current = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        private val ever = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        @Volatile private var closed = false

        init {
            if (proc != null) Thread({
                while (!closed) {
                    val line = proc.readLine() ?: break
                    val e = DnsSdOutput.parseBrowseLine(line) ?: continue
                    if (e.type != type && e.type != AdbOutput.ServiceType.OTHER) continue
                    if (e.added) { current += e.instance; ever += e.instance } else current -= e.instance
                    runCatching { onEvent(e) }
                }
            }, "dns-sd-browse-${type.wire}").apply { isDaemon = true; start() }
        }

        val running: Boolean get() = proc != null && !closed
        fun instances(): Set<String> = current.toSet()
        /** Every instance seen since the browse started, including ones that went away again. */
        fun everSeen(): Set<String> = ever.toSet()

        override fun close() {
            if (closed) return
            closed = true
            proc?.let(::kill)
        }
    }

    /** Starts a browse for [type]; the caller owns it and must [Browse.close] it. */
    fun browse(type: AdbOutput.ServiceType, onEvent: (DnsSdOutput.BrowseEvent) -> Unit = {}): Browse =
        Browse(type, start(listOf("-B", type.wire, "local")), onEvent)

    /** Reads a one-shot command's lines until [pick] returns a value or [timeoutMillis] passes; always kills it. */
    private fun <T> oneShot(args: List<String>, timeoutMillis: Long, pick: (List<String>) -> T?): T? {
        val proc = start(args) ?: return null
        val queue = LinkedBlockingQueue<String>()
        val eof = "\u0000eof"
        Thread({
            while (true) { val l = proc.readLine() ?: break; queue.put(l) }
            queue.put(eof)
        }, "dns-sd-oneshot").apply { isDaemon = true; start() }
        val lines = mutableListOf<String>()
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        try {
            while (true) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return pick(lines)
                val l = queue.poll(left, TimeUnit.NANOSECONDS) ?: return pick(lines)
                if (l == eof) return pick(lines)
                lines += l
                pick(lines)?.let { return it }
            }
        } finally {
            kill(proc)
        }
    }

    /** resolve + address: a dialable endpoint, or null. */
    fun find(instance: String, type: AdbOutput.ServiceType, timeoutMillis: Long = 6_000): BonjourEndpoint? {
        val started = System.nanoTime()
        val r = resolve(instance, type, timeoutMillis * 2 / 3) ?: return null
        val left = (timeoutMillis - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(1_000)
        val ip = address(r.host, left) ?: return null
        return BonjourEndpoint(instance, type, r.host, ip, r.port, r.txt)
    }

    /**
     * `dns-sd -L` — where [instance] of [type] lives (host, port, TXT), or null if it doesn't answer in
     * time. Returns as soon as the TXT line after the answer arrives; the answer alone is kept if the
     * deadline passes first.
     */
    fun resolve(instance: String, type: AdbOutput.ServiceType, timeoutMillis: Long = 4_000): DnsSdOutput.Resolved? {
        var best: DnsSdOutput.Resolved? = null
        val got = oneShot(listOf("-L", instance, type.wire, "local"), timeoutMillis) { lines ->
            val r = DnsSdOutput.parseResolve(lines.joinToString("\n")) ?: return@oneShot null
            best = r
            val i = lines.indexOfFirst { it.contains("can be reached at") }
            // Done once the TXT line arrived (or something else followed the answer).
            if (i < lines.lastIndex) r else null
        }
        return got ?: best
    }

    /**
     * `dns-sd -G v4v6` — the host's IPv4, or its scoped IPv6 when it has no IPv4. An IPv6 answer gets
     * a short grace for the IPv4 that usually arrives in the same burst.
     */
    fun address(host: String, timeoutMillis: Long = 3_000): String? {
        var v4: String? = null
        var v6: String? = null
        var v6At = 0L
        oneShot(listOf("-G", "v4v6", host), timeoutMillis) { lines ->
            val recs = lines.mapNotNull(DnsSdOutput::parseAddressLine).filter { it.added }
            v4 = recs.firstOrNull { !it.ipv6 }?.address
            v6 = v6 ?: recs.firstOrNull { it.ipv6 }?.address
            if (v4 != null) return@oneShot v4
            if (v6 != null) {
                if (v6At == 0L) v6At = System.nanoTime()
                if (System.nanoTime() - v6At > 800_000_000) return@oneShot v6
            }
            null
        }
        return v4 ?: v6
    }

    /** Kills every dns-sd this browser started that is still running. */
    fun closeAll() {
        live.toList().forEach(::kill)
    }

    companion object {
        const val DNS_SD = "/usr/bin/dns-sd"

        /** The app-wide instance; its processes die with the JVM even if a window forgets to close. */
        val shared: BonjourBrowser by lazy {
            BonjourBrowser().also { b -> Runtime.getRuntime().addShutdownHook(Thread { b.closeAll() }) }
        }
    }
}

/**
 * What one pairing / reconnect needs from Bonjour, as a non-blocking view over running browses. The
 * pairing and reconnect drivers poll it like they poll `adb mdns services`, so a fake drives tests.
 */
interface BonjourSession : AutoCloseable {
    /** False without dns-sd (never on a real Mac): the flow then relies on adb alone. */
    val available: Boolean

    /** Instances of [type] advertised right now. */
    fun seen(type: AdbOutput.ServiceType): Set<String>

    /** Every instance of [type] seen since the session started. */
    fun everSeen(type: AdbOutput.ServiceType): Set<String> = seen(type)

    /** The resolved endpoint of [instance] if it is ready; otherwise starts resolving it (if seen) and returns null. */
    fun endpoint(instance: String, type: AdbOutput.ServiceType): BonjourEndpoint?

    /** Blocks up to [timeoutMillis] for [instance]'s endpoint (only if it is being advertised). */
    fun awaitEndpoint(instance: String, type: AdbOutput.ServiceType, timeoutMillis: Long): BonjourEndpoint?

    /** Forget a cached endpoint (a connect to it failed — its port may have changed). */
    fun invalidate(instance: String)

    override fun close()

    object None : BonjourSession {
        override val available = false
        override fun seen(type: AdbOutput.ServiceType) = emptySet<String>()
        override fun endpoint(instance: String, type: AdbOutput.ServiceType): BonjourEndpoint? = null
        override fun awaitEndpoint(instance: String, type: AdbOutput.ServiceType, timeoutMillis: Long): BonjourEndpoint? = null
        override fun invalidate(instance: String) = Unit
        override fun close() = Unit
    }
}

/**
 * The real [BonjourSession]: one `dns-sd -B` per type in [types] for the session's life, resolves on
 * a small daemon pool (at most one in flight per instance), endpoints cached until the instance goes
 * away (`Rmv`) or is [invalidate]d. [close] kills the browses and any in-flight resolve.
 */
class DnsSdSession(
    private val browser: BonjourBrowser,
    types: Set<AdbOutput.ServiceType>,
) : BonjourSession {
    private val cache = ConcurrentHashMap<String, BonjourEndpoint>()
    private val inFlight = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "dns-sd-resolve").apply { isDaemon = true } }
    @Volatile private var closed = false

    private val browses = if (browser.available) types.associateWith { t ->
        browser.browse(t) { e -> if (!e.added) cache.remove(key(e.instance, t)) }
    } else emptyMap()

    override val available: Boolean get() = browses.isNotEmpty() && !closed

    private fun key(instance: String, type: AdbOutput.ServiceType) = "${type.wire}/$instance"

    override fun seen(type: AdbOutput.ServiceType): Set<String> = browses[type]?.instances().orEmpty()
    override fun everSeen(type: AdbOutput.ServiceType): Set<String> = browses[type]?.everSeen().orEmpty()

    override fun endpoint(instance: String, type: AdbOutput.ServiceType): BonjourEndpoint? {
        cache[key(instance, type)]?.let { return it }
        if (closed || instance !in seen(type)) return null
        val k = key(instance, type)
        if (inFlight.add(k)) {
            runCatching {
                pool.execute {
                    try {
                        if (!closed) runCatching { browser.find(instance, type) }.getOrNull()?.let { if (!closed) cache[k] = it }
                    } finally {
                        inFlight -= k
                    }
                }
            }.onFailure { inFlight -= k }
        }
        return null
    }

    override fun awaitEndpoint(instance: String, type: AdbOutput.ServiceType, timeoutMillis: Long): BonjourEndpoint? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!closed) {
            endpoint(instance, type)?.let { return it }
            if (instance !in seen(type) || System.currentTimeMillis() >= deadline) return null
            Thread.sleep(100)
        }
        return null
    }

    override fun invalidate(instance: String) {
        cache.keys.removeIf { it.endsWith("/$instance") }
    }

    override fun close() {
        if (closed) return
        closed = true
        browses.values.forEach { it.close() }
        pool.shutdownNow() // interrupts in-flight resolves; their oneShot `finally` kills the dns-sd
    }
}
