package io.github.siddharthjaswal.logpose.daemon

import io.github.siddharthjaswal.logpose.logcat.ControlMessage
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.mock.RuleSetTransport
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.model.MockAck
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.MockRuleSet
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Collections

/**
 * PRD §7's coexistence guarantee, asserted at the device boundary: a daemon without `--mocks`
 * never broadcasts a rule set — not when a device Hello disagrees with it, not on a sync retry,
 * not on shutdown — while still reading the handshake for `list_mocks`. With `--mocks` it is the
 * writer and pushes and clears as before.
 *
 * The transport records instead of running adb, and runs inline, so "zero broadcasts" is a count.
 */
class DaemonMockCoexistenceTest {

    @TempDir lateinit var dir: File

    private val log = Log(verboseStateChanges = false)
    private val sent: MutableList<MockRuleSet> = Collections.synchronizedList(mutableListOf())
    private val recorder = RuleSetTransport { _, _, ruleSet -> sent += ruleSet; null }

    /** What an IDE left on the device: three rules at the revision the M4b pass actually saw. */
    private val ideRevision = 1_791_889_168
    private val ideHello = ControlMessage.DeviceHello(
        Hello(pkg = "com.example.app", libVersion = "1.9.0", mockRevision = ideRevision, ruleCount = 3, processId = "p1"),
    )

    private fun capture(mocks: Boolean, localRules: Int = 1): Capture {
        val settings = KeyValueStore.InMemory()
        // Rules this daemon has persisted from an earlier --mocks run; seeded unarmed, so no push.
        val seed = MocksController(settings, recorder) { it.run() }
        repeat(localRules) { seed.addOrUpdate(MockRule(id = "r$it", method = "GET", pathPattern = "/riders/$it")) }
        assertTrue(sent.isEmpty(), "seeding must not broadcast")
        return Capture(
            options = Cli.ServeOptions(projectDir = dir, mocks = mocks),
            settings = settings,
            correlationSettings = KeyValueStore.InMemory(),
            log = log,
            mocks = MocksController(settings, recorder) { it.run() },
        )
    }

    @Test
    fun `read-only — a Hello reporting another writer's rules broadcasts nothing`() {
        val capture = capture(mocks = false)
        assertTrue(capture.begin())

        capture.onControl(ideHello)
        // A second app run and a foreign ack — the inputs that drive sync retries.
        capture.onControl(ControlMessage.DeviceHello(ideHello.hello.copy(processId = "p2")))
        capture.onControl(ControlMessage.MockApplied(MockAck(pkg = "com.example.app", revision = ideRevision + 1, ruleCount = 3)))

        assertEquals(emptyList<MockRuleSet>(), sent, "a read-only daemon must never write the device's rule set")
        // Still a reader: list_mocks can say what the device holds.
        val state = capture.mocks.deviceState()
        assertTrue(state.helloSeen)
        assertEquals("com.example.app", state.pkg)
        assertEquals(3, state.sync.deviceRules)
        assertEquals(false, state.capturing)
    }

    @Test
    fun `read-only — an empty local set against a device holding rules still broadcasts nothing`() {
        val capture = capture(mocks = false, localRules = 0)
        capture.begin()
        capture.onControl(ideHello)
        assertEquals(emptyList<MockRuleSet>(), sent)
    }

    @Test
    fun `read-only — stopping broadcasts nothing, whatever the caller asks`() {
        val capture = capture(mocks = false)
        capture.begin()
        capture.onControl(ideHello) // the device is known, so there is a target to (wrongly) clear
        capture.stop(clearDeviceRules = true)
        assertEquals(emptyList<MockRuleSet>(), sent, "the empty-set clear would delete the IDE's live mocks")
    }

    @Test
    fun `--mocks — a disagreeing Hello pushes our set, above the device's revision`() {
        val capture = capture(mocks = true)
        capture.begin()
        capture.onControl(ideHello)

        assertEquals(1, sent.size, "the writer re-pushes when the device holds someone else's set")
        assertEquals(listOf("r0"), sent.single().rules.map { it.id })
        assertTrue(
            sent.single().revision > ideRevision,
            "a push at or below the device's revision would be ignored (got ${sent.single().revision})",
        )
    }

    @Test
    fun `--mocks — stopping still clears the device`() {
        val capture = capture(mocks = true)
        capture.begin()
        capture.onControl(ideHello)
        sent.clear()

        capture.stop(clearDeviceRules = true)

        assertEquals(1, sent.size)
        assertEquals(emptyList<MockRule>(), sent.single().rules, "the fail-safe clear is an empty set")
        assertTrue(sent.single().revision > ideRevision)
    }
}
