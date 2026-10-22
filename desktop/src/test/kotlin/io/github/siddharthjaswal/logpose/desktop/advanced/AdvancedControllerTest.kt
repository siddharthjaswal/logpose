package io.github.siddharthjaswal.logpose.desktop.advanced

import io.github.siddharthjaswal.logpose.desktop.app.AdvancedController
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The coexistence rule end to end over the real [io.github.siddharthjaswal.logpose.mock.MocksController]:
 * a read-only app never arms the device (so neither an edit, a sync retry nor a stop can broadcast),
 * the writer arms on start and disarms on end. No device has said hello here, so no adb runs.
 */
class AdvancedControllerTest {

    @TempDir lateinit var tmp: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = KeyValueStore.InMemory()
    private var running: String? = null
    private val notices = mutableListOf<String>()

    private fun controller() = AdvancedController(scope, settings, tmp, { running ?: "emu" }, { it == running }, { t, _ -> notices += t })

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < until) { if (check()) return; Thread.sleep(20) }
        error("never: $what")
    }

    @AfterEach fun stop() = scope.cancel()

    @Test fun `read-only by default - started never arms, writes are refused with the reason`() {
        val c = controller()
        c.setAdvanced(true)
        running = "emu"
        c.started("emu")
        Thread.sleep(150)
        assertEquals(MockWriter.Mode.READ_ONLY, c.mode())
        assertFalse(c.mocks.deviceState().capturing, "a read-only app must never arm the controller")
        c.disableAll()
        assertTrue(notices.single().contains("read-only"))
        c.ended("emu")
    }

    @Test fun `the writer arms on start, disarms on end, and the choice persists per device`() {
        val c = controller()
        c.setAdvanced(true)
        c.setWriter("emu", true)
        eventually("persisted") { settings.get(MockWriter.WRITERS_KEY) == "emu" }
        running = "emu"
        c.started("emu")
        eventually("armed") { c.mocks.deviceState().capturing }
        assertEquals(MockWriter.Mode.WRITER, c.mode())
        running = null
        c.ended("emu")
        eventually("disarmed") { !c.mocks.deviceState().capturing }
        // A new controller over the same settings remembers the hand-over.
        assertEquals(MockWriter.Mode.WRITER, controller().mode("emu"))
    }

    @Test fun `giving the device back mid-capture releases it at once`() {
        val c = controller()
        c.setAdvanced(true)
        c.setWriter("emu", true)
        running = "emu"
        c.started("emu")
        eventually("armed") { c.mocks.deviceState().capturing }
        c.setWriter("emu", false)
        eventually("released") { !c.mocks.deviceState().capturing }
        assertEquals(MockWriter.Mode.READ_ONLY, c.mode())
    }

    @Test fun `turning Advanced off hides the section and releases a writer`() {
        val c = controller()
        c.setAdvanced(true)
        c.setWriter("emu", true)
        running = "emu"
        c.started("emu")
        eventually("armed") { c.mocks.deviceState().capturing }
        c.setAdvanced(false)
        assertEquals(MockWriter.Mode.HIDDEN, c.mode())
        assertFalse(c.mocks.deviceState().capturing)
        eventually("persisted off") { settings.get(MockWriter.ADVANCED_KEY) == null }
    }

    @Test fun `a restart's arm waits for the previous session's release`() {
        val c = controller()
        c.setAdvanced(true)
        c.setWriter("emu", true)
        eventually("persisted") { settings.get(MockWriter.WRITERS_KEY) == "emu" }
        running = "emu"
        c.started("emu")
        eventually("armed") { c.mocks.deviceState().capturing }
        // Back to back, as attach_device restarts: both hops go to IO's pool. The interleaving that
        // loses (release after arm) depends on IO scheduling and won't reproduce on demand, so this
        // guards the end state rather than forcing the race.
        c.ended("emu")
        c.started("emu")
        eventually("re-armed") { c.mocks.deviceState().capturing }
        Thread.sleep(150)
        assertTrue(c.mocks.deviceState().capturing, "a late release must not disarm the new session")
        c.ended("emu")
    }
}
