package io.github.siddharthjaswal.logpose.appcontrol

import io.github.siddharthjaswal.logpose.logcat.ControlMessage
import io.github.siddharthjaswal.logpose.mock.MocksController
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.model.MockAck
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.MockRuleSet
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.store.EventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.Executors

/**
 * The host-side path every UI and `restart_app` share: marker at the run boundary, the new run
 * picked up from the relaunched app's hello, and the mock rules re-pushed to that run.
 */
class AppControlServiceTest {

    private val pkg = "com.acme.app.debug"
    private val serial = "emulator-5580"

    /** A device whose `am start` makes the app say hello as a new process — the real sequence. */
    private class Device(private val onLaunch: () -> Unit) : AppShell {
        val ran = Collections.synchronizedList(mutableListOf<String>())
        override fun run(serial: String, line: String, timeoutMillis: Long): AppShell.Result {
            ran += line
            return when {
                line.startsWith("pm path") -> AppShell.Result(0, "package:/data/app/base.apk")
                line.startsWith("getprop") -> AppShell.Result(0, "34")
                line.startsWith("cmd package resolve-activity") -> AppShell.Result(0, "com.acme.app.debug/.Main")
                line.startsWith("am start") -> { onLaunch(); AppShell.Result(0, "Starting: Intent { }") }
                else -> AppShell.Result(0, "")
            }
        }
    }

    private val store = EventStore(completer = { it.run() }, scheduler = Executors.newSingleThreadScheduledExecutor())

    private fun service(shell: AppShell, mocks: MocksController? = null, settings: KeyValueStore? = null) = AppControlService(
        store = store,
        serial = { serial },
        mocks = mocks,
        settings = settings,
        control = AppControl(shell),
        runAsync = { it.run() },
        mockAckMillis = 2_000,
    )

    private fun hello(processId: String, ruleCount: Int = 0, rev: Int = 0) =
        ControlMessage.DeviceHello(Hello(pkg = pkg, libVersion = "1.7.4", processId = processId, ruleCount = ruleCount, mockRevision = rev))

    @Test
    fun `a restart records the marker in the old run and reports the new run`() {
        store.noteHello("old", pkg, "1.7.4")
        val device = Device { store.noteHello("new", pkg, "1.7.4") }
        var outcome: AppControlService.Outcome? = null
        val settings = KeyValueStore.InMemory()
        service(device, settings = settings).perform(AppAction.RESTART, pkg, AppControl.Options(), 5_000, {}) { outcome = it }

        val o = assertNotNull(outcome).let { outcome!! }
        assertTrue(o.ok, o.summary)
        assertEquals(2, o.newRun?.index)
        val marker = store.snapshot().single { AppMarker.isMarker(it) }
        assertEquals(o.markerId, marker.id)
        assertEquals(1, store.sessionOf(marker.id), "the marker closes the old run; the new run starts after it")
        assertEquals("Restarted $pkg. New run #2.", o.summary)
        assertEquals(pkg, AppTarget.remembered(settings, serial), "a package that worked is remembered for the device")
    }

    @Test
    fun `no hello within the wait is an answer, not a hang`() {
        var outcome: AppControlService.Outcome? = null
        service(Device {}).perform(AppAction.RESTART, pkg, AppControl.Options(), 200, {}) { outcome = it }
        assertTrue(outcome!!.report.launched)
        assertNull(outcome!!.newRun)
        assertTrue(outcome!!.summary.contains("hasn't announced a new run"))
    }

    @Test
    fun `a failure before launch leaves no marker`() {
        val shell = AppShell { _, line, _ -> if (line.startsWith("run-as")) AppShell.Result(1, "run-as: package not debuggable: $pkg") else AppShell.Result(0, "package:/x") }
        var outcome: AppControlService.Outcome? = null
        service(shell).perform(AppAction.CLEAR_CACHE, pkg, AppControl.Options(), 100, {}) { outcome = it }
        assertTrue(outcome!!.report.offerClearAll)
        assertNull(outcome!!.markerId)
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun `one action at a time`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val blocking = AppShell { _, _, _ -> gate.await(); AppShell.Result(0, "package:/x") }
        val s = AppControlService(store, { serial }, control = AppControl(blocking), runAsync = { Thread(it).start() })
        s.perform(AppAction.RESTART, pkg, AppControl.Options(), 0, {}) {}
        var second: AppControlService.Outcome? = null
        s.perform(AppAction.RESTART, pkg, AppControl.Options(), 0, {}) { second = it }
        assertTrue(second!!.busy)
        assertTrue(s.busy)
        gate.countDown()
    }

    @Test
    fun `no device is said plainly`() {
        var outcome: AppControlService.Outcome? = null
        AppControlService(store, { null }, runAsync = { it.run() }).perform(AppAction.RESTART, pkg, AppControl.Options(), 0, {}) { outcome = it }
        assertEquals(AppControl.Failure.DEVICE_NOT_FOUND, outcome!!.report.failure)
    }

    @Test
    fun `the writer's rules are re-pushed to the new run and the ack is reported`() {
        val sent = Collections.synchronizedList(mutableListOf<MockRuleSet>())
        lateinit var mocks: MocksController
        mocks = MocksController(KeyValueStore.InMemory(), { _, _, set ->
            sent += set
            // The device applies it and acks, as MockCommandReceiver does.
            mocks.onControl(ControlMessage.MockApplied(MockAck(pkg = pkg, revision = set.revision, ruleCount = set.rules.size)))
            null
        }) { it.run() }
        mocks.addOrUpdate(MockRule(id = "r1", method = "GET", pathPattern = "/ping"))
        mocks.onCaptureStarted()
        // The running app holds the rules.
        mocks.onControl(hello("old"))
        store.noteHello("old", pkg, "1.7.4")
        assertEquals(1, sent.size)

        // force-stop wipes MockRegistry: the relaunched process says hello with zero rules.
        val device = Device {
            store.noteHello("new", pkg, "1.7.4")
            mocks.onControl(hello("new", ruleCount = 0, rev = 0))
        }
        var outcome: AppControlService.Outcome? = null
        service(device, mocks = mocks).perform(AppAction.RESTART, pkg, AppControl.Options(), 5_000, {}) { outcome = it }

        val m = outcome!!.mocks!!
        assertTrue(m.writer)
        assertEquals(1, m.activeRules)
        assertTrue(m.rePushed)
        assertTrue(m.acknowledged)
        assertEquals(2, sent.size, "the new run's hello triggered exactly one re-push")
        assertEquals(1, sent.last().rules.size)
    }

    @Test
    fun `a read-only host reports that it pushed nothing`() {
        val sent = mutableListOf<MockRuleSet>()
        val reader = MocksController(KeyValueStore.InMemory(), { _, _, set -> sent += set; null }) { it.run() }
        reader.addOrUpdate(MockRule(id = "r1", method = "GET", pathPattern = "/ping"))
        val device = Device { store.noteHello("new", pkg, "1.7.4"); reader.onControl(hello("new")) }
        var outcome: AppControlService.Outcome? = null
        service(device, mocks = reader).perform(AppAction.RESTART, pkg, AppControl.Options(), 5_000, {}) { outcome = it }
        val m = outcome!!.mocks!!
        assertFalse(m.writer)
        assertFalse(m.rePushed)
        assertTrue(sent.isEmpty())
    }
}
