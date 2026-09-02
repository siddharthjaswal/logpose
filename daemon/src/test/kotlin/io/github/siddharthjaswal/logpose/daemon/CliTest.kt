package io.github.siddharthjaswal.logpose.daemon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class CliTest {

    private val cwd = File("/tmp/some-project").absoluteFile

    private fun serve(vararg args: String): Cli.ServeOptions {
        val command = Cli.parse(listOf("serve") + args, cwd)
        assertTrue(command is Cli.Command.Serve, "expected serve, got $command")
        return (command as Cli.Command.Serve).options
    }

    @Test
    fun `bare serve uses the documented defaults`() {
        val options = serve()
        assertEquals(63343, options.port)
        assertEquals(cwd, options.projectDir)
        assertNull(options.device)
        assertNull(options.token)
        assertTrue(options.exposeBodies)
        // The two coexistence defaults the PRD is emphatic about.
        assertFalse(options.mocks, "mocks must be read-only unless asked")
        assertFalse(options.clear, "the daemon must not wipe a coexisting IDE's logcat backlog")
    }

    @Test
    fun `every flag parses in its spaced form`() {
        val options = serve(
            "--port", "7000",
            "--project-dir", "/tmp/other",
            "--device", "emulator-5554",
            "--token", "abc123",
            "--name", "gandalf",
            "--no-bodies",
            "--mocks",
            "--clear",
        )
        assertEquals(7000, options.port)
        assertEquals(File("/tmp/other").absoluteFile, options.projectDir)
        assertEquals("emulator-5554", options.device)
        assertEquals("abc123", options.token)
        assertEquals("gandalf", options.projectName)
        assertFalse(options.exposeBodies)
        assertTrue(options.mocks)
        assertTrue(options.clear)
    }

    @Test
    fun `every valued flag also parses in its equals form`() {
        val options = serve(
            "--port=7001",
            "--project-dir=/tmp/other",
            "--device=emulator-5556",
            "--token=t",
            "--name=n",
        )
        assertEquals(7001, options.port)
        assertEquals(File("/tmp/other").absoluteFile, options.projectDir)
        assertEquals("emulator-5556", options.device)
        assertEquals("t", options.token)
        assertEquals("n", options.projectName)
    }

    @Test
    fun `project name falls back to the project directory's own name`() {
        assertEquals("some-project", serve().projectName)
        assertEquals("other", serve("--project-dir", "/tmp/other").projectName)
    }

    @Test
    fun `an unknown flag is usage plus exit 2`() {
        val command = Cli.parse(listOf("serve", "--nope"), cwd)
        assertTrue(command is Cli.Command.Exit)
        command as Cli.Command.Exit
        assertEquals(2, command.exitCode)
        assertTrue(command.message.contains("--nope"))
        assertTrue(command.message.contains("Usage:"))
    }

    @Test
    fun `an unknown subcommand is usage plus exit 2`() {
        val command = Cli.parse(listOf("tail"), cwd) as Cli.Command.Exit
        assertEquals(2, command.exitCode)
        assertTrue(command.message.contains("tail"))
    }

    @Test
    fun `no arguments at all is usage plus exit 2`() {
        assertEquals(2, (Cli.parse(emptyList(), cwd) as Cli.Command.Exit).exitCode)
    }

    @Test
    fun `help is usage plus exit 0`() {
        assertEquals(0, (Cli.parse(listOf("--help"), cwd) as Cli.Command.Exit).exitCode)
        assertEquals(0, (Cli.parse(listOf("serve", "--help"), cwd) as Cli.Command.Exit).exitCode)
    }

    @Test
    fun `version is its own command`() {
        assertEquals(Cli.Command.Version, Cli.parse(listOf("version"), cwd))
        assertEquals(Cli.Command.Version, Cli.parse(listOf("--version"), cwd))
    }

    @Test
    fun `a non-numeric or out-of-range port is rejected`() {
        val bad = Cli.parse(listOf("serve", "--port", "http"), cwd) as Cli.Command.Exit
        assertEquals(2, bad.exitCode)
        assertTrue(bad.message.contains("expects a number"))

        val huge = Cli.parse(listOf("serve", "--port", "70000"), cwd) as Cli.Command.Exit
        assertEquals(2, huge.exitCode)
        assertTrue(huge.message.contains("1-65535"))
    }

    @Test
    fun `a valued flag with nothing after it is rejected rather than swallowing the next flag`() {
        val command = Cli.parse(listOf("serve", "--token"), cwd) as Cli.Command.Exit
        assertEquals(2, command.exitCode)
        assertTrue(command.message.contains("--token expects a value"))
    }

    @Test
    fun `--stdio is off by default and sets the flag when asked for`() {
        val plain = Cli.parse(listOf("serve"), cwd) as Cli.Command.Serve
        assertFalse(plain.options.stdio)

        val stdio = Cli.parse(listOf("serve", "--stdio"), cwd) as Cli.Command.Serve
        assertTrue(stdio.options.stdio)
        // The port field keeps its default; nothing binds it under --stdio.
        assertEquals(Cli.DEFAULT_PORT, stdio.options.port)
    }

    @Test
    fun `--stdio with --port is a usage error, in either order`() {
        listOf(
            listOf("serve", "--stdio", "--port", "63343"),
            listOf("serve", "--port=63343", "--stdio"),
        ).forEach { args ->
            val command = Cli.parse(args, cwd) as Cli.Command.Exit
            assertEquals(2, command.exitCode, "for $args")
            assertTrue(command.message.contains("mutually exclusive"), command.message)
        }
        // Naming the default port explicitly is still naming it — the check is on the flag.
        assertTrue(Cli.parse(listOf("serve", "--stdio", "--port", "1"), cwd) is Cli.Command.Exit)
    }

    @Test
    fun `--stdio composes with the flags that are not about transport`() {
        val command = Cli.parse(
            listOf("serve", "--stdio", "--project-dir", cwd.path, "--mocks", "--no-bodies"), cwd,
        ) as Cli.Command.Serve
        assertTrue(command.options.stdio)
        assertTrue(command.options.mocks)
        assertFalse(command.options.exposeBodies)
    }

    @Test
    fun `usage names the single-writer rule and the logcat-clear default`() {
        // These two sentences are the daemon's whole coexistence story; if they ever vanish from
        // --help, someone will run two writers against one device and spend an afternoon on it.
        val usage = Cli.usage()
        assertTrue(usage.contains("Only ONE process"))
        assertTrue(usage.contains("OFF by default"))
        assertTrue(usage.contains("LOGPOSE_TOKEN"))
        assertTrue(usage.contains("--stdio"))
    }
}
