package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.settings.CorrelationSettings
import io.github.siddharthjaswal.logpose.settings.FileKeyValueStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The dogfood finding closed: a correlation vocabulary configured on the IDE side is the one the
 * daemon groups on, because both back `CorrelationSettings` with the same
 * `.logpose/correlation.properties`. These assert the daemon's exact wiring from [Daemon] — the
 * shared-correlation store handed to [Capture], and the one-time migration off the old private
 * `daemon.properties`.
 */
class DaemonCorrelationSharingTest {

    @TempDir lateinit var dir: File

    private val log = Log(verboseStateChanges = false)

    private fun options() = Cli.ServeOptions(projectDir = dir)

    @Test
    fun `the daemon reads a vocabulary the IDE wrote to the shared file`() {
        // The IDE's Correlation keys… dialog persists through FileKeyValueStore.sharedCorrelation.
        val ide = FileKeyValueStore.sharedCorrelation(dir)
        CorrelationSettings.setKeys(ide, listOf(CorrelationKey("order_id"), CorrelationKey("chain_vehicle_id")))

        // The daemon wires Capture exactly as Daemon does: its own settings for private state, the
        // shared file for correlation.
        val settings = FileKeyValueStore.forProject(dir)
        val correlationSettings = FileKeyValueStore.sharedCorrelation(dir).also {
            CorrelationSettings.migrateIfNeeded(from = settings, to = it)
        }
        val capture = Capture(options(), settings, correlationSettings, log)

        assertEquals(
            listOf("order_id", "chain_vehicle_id"),
            capture.correlation.keys().map { it.name },
            "a key configured in the IDE must be the one the daemon groups on",
        )
    }

    @Test
    fun `a vocabulary hand-seeded into daemon-properties is migrated to the shared file once`() {
        // The pre-sharing recipe: keys hand-written into the daemon's own settings file.
        val settings = FileKeyValueStore.forProject(dir)
        CorrelationSettings.setKeys(settings, listOf(CorrelationKey("order_id")))

        // Daemon's construction migrates it across, so the shared file — the one the IDE reads —
        // now carries it, and the daemon reads it from there.
        val correlationSettings = FileKeyValueStore.sharedCorrelation(dir).also {
            CorrelationSettings.migrateIfNeeded(from = settings, to = it)
        }
        val capture = Capture(options(), settings, correlationSettings, log)

        assertEquals(listOf("order_id"), capture.correlation.keys().map { it.name })
        // It really landed on disk in the shared file — an IDE opening the same dir would see it.
        assertTrue(File(dir, ".logpose/correlation.properties").isFile)
        assertEquals(
            listOf("order_id"),
            CorrelationSettings.keys(FileKeyValueStore.sharedCorrelation(dir)).map { it.name },
        )
    }
}
