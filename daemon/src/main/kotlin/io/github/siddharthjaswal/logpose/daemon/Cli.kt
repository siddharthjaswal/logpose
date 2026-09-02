package io.github.siddharthjaswal.logpose.daemon

import java.io.File

/**
 * The daemon's command line, parsed by hand.
 *
 * Hand-rolled on purpose: the surface is one subcommand and eight flags, and the daemon's whole
 * claim is that it is :core plus kotlinx-serialization plus stdlib — a CLI library would be the
 * first dependency added for convenience rather than need.
 *
 * Pure: [parse] touches no filesystem, no environment and no clock beyond what it is handed, so
 * every branch below is a unit test rather than a run of the binary.
 */
object Cli {

    const val DEFAULT_PORT = 63343

    /** What `logpose serve` was asked to do. */
    data class ServeOptions(
        val port: Int = DEFAULT_PORT,
        /** Anchors `.logpose/` — scenarios, the daemon's settings file. Defaults to the cwd. */
        val projectDir: File,
        /** `-s <serial>` for every adb call; null means auto (adb's own default device). */
        val device: String? = null,
        /** From `--token`; null falls through to the env, then the settings file, then generated. */
        val token: String? = null,
        /** `--no-bodies` maps to the session's `exposeBodies` — bodies become `payload_withheld`. */
        val exposeBodies: Boolean = true,
        /** `--mocks`: become the device's single mock writer. Off by default (PRD §7). */
        val mocks: Boolean = false,
        /** `--clear`: run `adb logcat -c` at capture start. Off by default (PRD §7). */
        val clear: Boolean = false,
        /** `--name`: what the session calls itself over MCP. Defaults to the project dir's name. */
        val name: String? = null,
        /**
         * `--stdio`: speak MCP over stdin/stdout instead of binding a port, for a client that
         * launches the daemon itself. Mutually exclusive with `--port` — a process can't be both
         * the thing you connect to and the thing that was spawned for you.
         */
        val stdio: Boolean = false,
    ) {
        /** The session's display name — the flag, else the directory's own name. */
        val projectName: String
            get() = name?.takeIf { it.isNotBlank() }
                ?: projectDir.absoluteFile.name.takeIf { it.isNotBlank() }
                ?: "logpose"
    }

    /** The outcome of parsing: something to run, or something to print and exit with. */
    sealed interface Command {
        data class Serve(val options: ServeOptions) : Command
        object Version : Command

        /** Print [message] and exit [exitCode] — 0 for an asked-for `--help`, 2 for a mistake. */
        data class Exit(val exitCode: Int, val message: String) : Command
    }

    fun parse(args: List<String>, cwd: File = File(".").absoluteFile): Command {
        if (args.isEmpty()) return Command.Exit(2, usage())
        return when (val verb = args.first()) {
            "serve" -> parseServe(args.drop(1), cwd)
            "version", "--version", "-v" -> Command.Version
            "help", "--help", "-h" -> Command.Exit(0, usage())
            else -> Command.Exit(2, "logpose: unknown command '$verb'\n\n" + usage())
        }
    }

    private fun parseServe(args: List<String>, cwd: File): Command {
        var options = ServeOptions(projectDir = cwd)
        var i = 0
        // Tracked separately from the value: --port 63343 is still "asked for a port", and pairing
        // it with --stdio is a mistake worth naming rather than silently resolving.
        var portGiven = false

        // `--flag value` and `--flag=value` both work: the first is what a human types, the second
        // is what scripts and `claude mcp add` recipes tend to produce.
        fun valueFor(flag: String, inline: String?): String? {
            if (inline != null) return inline
            val next = args.getOrNull(i + 1) ?: return null
            i++
            return next
        }

        while (i < args.size) {
            val raw = args[i]
            val flag = raw.substringBefore('=')
            val inline = if ('=' in raw) raw.substringAfter('=') else null

            when (flag) {
                "--port" -> {
                    val v = valueFor(flag, inline) ?: return Command.Exit(2, missing(flag))
                    val port = v.toIntOrNull()
                        ?: return Command.Exit(2, "logpose serve: --port expects a number, got '$v'")
                    if (port !in 1..65535) {
                        return Command.Exit(2, "logpose serve: --port must be 1-65535, got $port")
                    }
                    options = options.copy(port = port)
                    portGiven = true
                }
                "--project-dir" -> {
                    val v = valueFor(flag, inline) ?: return Command.Exit(2, missing(flag))
                    options = options.copy(projectDir = File(v).absoluteFile)
                }
                "--device" -> {
                    val v = valueFor(flag, inline) ?: return Command.Exit(2, missing(flag))
                    options = options.copy(device = v)
                }
                "--token" -> {
                    val v = valueFor(flag, inline) ?: return Command.Exit(2, missing(flag))
                    options = options.copy(token = v)
                }
                "--name" -> {
                    val v = valueFor(flag, inline) ?: return Command.Exit(2, missing(flag))
                    options = options.copy(name = v)
                }
                "--stdio" -> options = options.copy(stdio = true)
                "--no-bodies" -> options = options.copy(exposeBodies = false)
                "--mocks" -> options = options.copy(mocks = true)
                "--clear" -> options = options.copy(clear = true)
                "--help", "-h" -> return Command.Exit(0, usage())
                else -> return Command.Exit(2, "logpose serve: unknown flag '$flag'\n\n" + usage())
            }
            i++
        }
        if (options.stdio && portGiven) {
            return Command.Exit(
                2,
                "logpose serve: --stdio and --port are mutually exclusive. --stdio speaks MCP on " +
                    "stdin/stdout for a client that launched this process; --port binds a socket " +
                    "for a client that connects to it. Pick one.\n\n" + usage(),
            )
        }
        return Command.Serve(options)
    }

    private fun missing(flag: String) = "logpose serve: $flag expects a value\n\n" + usage()

    fun usage(): String = """
        logpose — headless LogPose: capture an Android device and serve MCP, with no IDE.

        Usage:
          logpose serve [options]
          logpose version

        Options:
          --port N            MCP HTTP port on 127.0.0.1 (default $DEFAULT_PORT)
          --stdio             Speak MCP over stdin/stdout instead of binding a port, for a client
                              that launches this process itself. No token (the pipe is the
                              authentication); every log line goes to stderr. Not with --port.
          --project-dir DIR   Anchors .logpose/ — scenarios and daemon settings (default: cwd)
          --device SERIAL     Which attached device to tail (default: adb's own choice)
          --token T           MCP token; else ${'$'}LOGPOSE_TOKEN, else saved, else generated
          --name N            Session name reported over MCP (default: the project dir's name)
          --no-bodies         Withhold request/response bodies from MCP results
          --mocks             Become this device's mock writer. Only ONE process may be:
                              rules are a single wholesale set keyed by a revision counter, so a
                              daemon and an IDE both writing will fight and lose each other's
                              rules. Without this flag the write tools decline politely.
          --clear             Run `adb logcat -c` at capture start. OFF by default: the clear is
                              global to the device and would wipe a running IDE's backlog.

        Environment:
          LOGPOSE_TOKEN       Used when --token is absent.
    """.trimIndent()
}
