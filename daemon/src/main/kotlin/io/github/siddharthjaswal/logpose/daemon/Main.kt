package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.mcp.McpRpc
import io.github.siddharthjaswal.logpose.mcp.McpSessions
import io.github.siddharthjaswal.logpose.mock.ScenarioStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** `logpose serve` — LogPose's capture and all 21 MCP tools, with no IDE in the process. */
fun main(args: Array<String>) {
    when (val command = Cli.parse(args.toList())) {
        is Cli.Command.Exit -> {
            // Usage asked for goes to stdout; usage after a mistake goes to stderr, so a script
            // piping `--help` gets clean text and a mistake doesn't pollute a pipeline.
            if (command.exitCode == 0) println(command.message) else System.err.println(command.message)
            exitProcess(command.exitCode)
        }
        Cli.Command.Version -> println("logpose ${version()}")
        is Cli.Command.Serve -> serve(command.options)
    }
}

private fun version(): String =
    Daemon::class.java.`package`?.implementationVersion ?: "dev"

private fun serve(options: Cli.ServeOptions) {
    val log = Log()
    val daemon = Daemon(options, log)
    val stop = CountDownLatch(1)

    Runtime.getRuntime().addShutdownHook(
        Thread({
            log.info("shutting down")
            daemon.stop()
            stop.countDown()
        }, "logpose-shutdown")
    )

    runCatching { daemon.start() }.onFailure { e ->
        log.warn("could not start: " + explain(e, options))
        daemon.stop()
        exitProcess(1)
    }

    if (options.stdio) {
        // The client owns this process, so the main thread *is* the transport: it reads stdin
        // until EOF, which is the same shutdown SIGTERM triggers.
        daemon.serveStdio()
        daemon.stop()
    } else {
        // Park the main thread; everything real happens on the reader, scheduler and HTTP threads.
        stop.await()
    }
}

/**
 * Why the daemon could not start, in one line a human can act on.
 *
 * The only startup failure anyone actually hits is a taken port — a second daemon, or an IDE on
 * the same number — and the JDK's own text for it is "Address already in use", which names
 * neither. Everything else falls through to the exception's own message (never a stack trace:
 * the caller gets a sentence and exit 1, and a supervisor's log stays readable).
 */
internal fun explain(e: Throwable, options: Cli.ServeOptions): String =
    if (e is java.net.BindException) {
        "port ${options.port} is already in use (${e.message}). Another logpose daemon is " +
            "probably serving it — check with `lsof -nP -iTCP:${options.port}` — or pick another " +
            "with --port. Note that one daemon per device is the rule for mocks (--mocks), not " +
            "for reading: several captures can tail the same device safely."
    } else {
        e.message ?: e::class.java.simpleName
    }

/**
 * One process, one capture, one MCP session — assembled here.
 *
 * Split out of [main] so a test can start and stop the whole thing on an ephemeral port without a
 * process, and so the ordering that matters is visible in one place: settings first (they hold the
 * token), then the capture (it owns the store the session reads), then the session, then the
 * server — a client that connects the moment the port opens always finds a registered capture.
 */
class Daemon(private val options: Cli.ServeOptions, private val log: Log) {

    private val settings = FileKeyValueStore.forProject(options.projectDir)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "logpose-work").apply { isDaemon = true }
    }
    private val capture = Capture(options, settings, log)
    private val scenarios = ScenarioStore.forProject(options.projectDir.absolutePath)

    val token: String = resolveToken(options.token, System.getenv(TOKEN_ENV), settings)

    private var transport: HttpTransport? = null
    private var stdio: StdioTransport? = null
    @Volatile private var boundPort: Int = options.port

    fun start() {
        options.projectDir.mkdirs()

        val glue = DaemonSession(capture, options, scenarios, pool, log)
        val session = glue.session()
        val rpc = McpRpc(
            // Over stdio the pipe is the credential — the client started this process — so the
            // lookup answers any token; over HTTP anything on the machine can knock, so it doesn't.
            sessions = if (options.stdio) StdioTransport.anySession(session) else glue.Lookup(token, session),
            hint = DaemonSession.AuthHint,
            unavailable = DaemonSession.Unavailable,
        )

        if (options.stdio) {
            stdio = StdioTransport(rpc, System.`in`, System.out, log)
        } else {
            val server = HttpTransport(
                port = options.port,
                rpc = rpc,
                log = log,
                health = { HttpTransport.Health(capture.store.snapshot().size, capture.state()) },
            )
            boundPort = server.start()
            transport = server
        }

        capture.start()
        announce()
    }

    /** Reads stdin until EOF on the calling thread. Only meaningful after a `--stdio` [start]. */
    fun serveStdio() {
        stdio?.run()
    }

    /**
     * The daemon's Connect-Coding-Agent: the exact command to paste, printed on **stdout** because
     * it is the output of the command rather than commentary about it.
     *
     * Except under `--stdio`, where stdout belongs to the JSON-RPC stream and every last line —
     * the recipe included — goes to stderr instead.
     */
    private fun announce() {
        log.info("project ${options.projectName} · dir ${options.projectDir}")
        log.info(
            "mocks: " + if (options.mocks) {
                "WRITABLE — this daemon is the device's mock writer. Do not run a second writer " +
                    "(an IDE with the LogPose tool window open) against the same device."
            } else {
                "read-only (pass --mocks to let create_mock / load_scenario / inject_fcm write)"
            }
        )
        if (!options.clear) log.info("logcat backlog left intact (--clear to wipe it at start)")
        if (!options.exposeBodies) log.info("bodies withheld from MCP results (--no-bodies)")
        if (options.stdio) {
            // Not one byte of this may reach stdout: the client is reading JSON-RPC there, and a
            // banner in the middle of the stream is a parse error, not a nicety.
            log.info("MCP on stdin/stdout (no token — the pipe is the authentication)")
            log.info(
                "Recipe: claude mcp add logpose -- java -jar logpose-daemon.jar serve --stdio " +
                    "--project-dir ${options.projectDir}"
            )
            return
        }
        log.info("MCP listening on http://127.0.0.1:$boundPort${HttpTransport.PATH}")
        log.out("")
        log.out("Connect a coding agent:")
        log.out(
            "  claude mcp add --transport http logpose " +
                "http://localhost:$boundPort${HttpTransport.PATH} " +
                "--header \"${McpRpc.TOKEN_HEADER}: $token\""
        )
        log.out("")
    }

    /** Idempotent — the shutdown hook and an explicit stop can both run. */
    @Synchronized
    fun stop() {
        // Reader first: nothing new should enter the store while we are tearing down, and the mock
        // clear below needs adb, which the reader's own exit does not contend for.
        //
        // The device rules are cleared ONLY when this daemon owns them. Without --mocks that
        // broadcast would push an empty rule set over whatever the real writer (an IDE) has live.
        capture.stop(clearDeviceRules = options.mocks)
        transport?.stop()
        transport = null
        // The store the settings file holds is written through on every set(); this is the
        // belt-and-braces flush so a mock revision bumped microseconds ago is on disk.
        settings.flush()
        pool.shutdown()
        runCatching { pool.awaitTermination(2, TimeUnit.SECONDS) }
    }

    /** The port actually bound — the requested one, or the OS's pick when 0 was asked for. */
    fun port(): Int = boundPort

    fun events(): Int = capture.store.snapshot().size

    internal fun projectDirFile(): File = options.projectDir

    companion object {
        const val TOKEN_ENV = "LOGPOSE_TOKEN"

        /**
         * `--token` → `$LOGPOSE_TOKEN` → the settings file → freshly generated and saved.
         *
         * The last two steps are [McpSessions.tokenFor], the plugin's own: a generated token is
         * persisted, so the `claude mcp add` line printed on the first run keeps working on the
         * next one. An explicit token — flag or env — is deliberately **not** persisted; it is the
         * caller's to manage, and writing it would silently outlive the run that set it.
         */
        fun resolveToken(explicit: String?, env: String?, store: io.github.siddharthjaswal.logpose.settings.KeyValueStore): String =
            explicit?.takeIf { it.isNotBlank() }
                ?: env?.takeIf { it.isNotBlank() }
                ?: McpSessions.tokenFor(store)
    }
}
