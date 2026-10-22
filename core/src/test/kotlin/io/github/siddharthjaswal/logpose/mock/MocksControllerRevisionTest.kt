package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.logcat.ControlMessage
import io.github.siddharthjaswal.logpose.model.Hello
import io.github.siddharthjaswal.logpose.model.MockAck
import io.github.siddharthjaswal.logpose.model.MockRule
import io.github.siddharthjaswal.logpose.model.MockRuleSet
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections

/**
 * The two coexistence rules [MocksController] owns, asserted at the device boundary through a
 * recording transport:
 *
 *  - **Arming.** An unarmed controller is a reader — it ingests Hello/acks but never broadcasts.
 *  - **Revision.** The device ignores a rule set whose revision is lower than the one it holds,
 *    and every writer counts on its own, so a writer that learns the device's revision pushes
 *    strictly above `max(local, device)` next time — and persists that.
 */
class MocksControllerRevisionTest {

    private val sent: MutableList<MockRuleSet> = Collections.synchronizedList(mutableListOf())
    private val problems = mutableListOf<String>()
    private val store = KeyValueStore.InMemory()

    private fun newController() =
        MocksController(store, { _, _, set -> sent += set; null }) { it.run() }
            .also { c -> c.onProblem = { title, _ -> problems += title } }

    /** A writer holding [rules] persisted rules at [localRevision]. Seeding is unarmed: no push. */
    private fun controller(localRevision: Int, rules: Int = 1): MocksController {
        val seed = newController()
        repeat(rules) { seed.addOrUpdate(MockRule(id = "r$it", method = "GET", pathPattern = "/p/$it")) }
        store.setInt(REVISION_KEY, localRevision, 0)
        sent.clear()
        return newController()
    }

    private fun hello(rev: Int, count: Int, pid: String = "p1") =
        ControlMessage.DeviceHello(Hello(pkg = PKG, libVersion = "1.9.0", mockRevision = rev, ruleCount = count, processId = pid))

    private fun ack(rev: Int, count: Int) = ControlMessage.MockApplied(MockAck(pkg = PKG, revision = rev, ruleCount = count))

    private fun revisions() = sent.map { it.revision }

    // ---- Arming -------------------------------------------------------------------------------

    @Test
    fun `an unarmed controller never broadcasts — not on hello, ack, edit or stop`() {
        val c = controller(localRevision = 3)
        c.onControl(hello(rev = 50, count = 3))          // disagrees: an armed writer would re-push
        c.onControl(hello(rev = 50, count = 3, pid = "p2"))
        c.onControl(ack(rev = 60, count = 2))
        c.addOrUpdate(MockRule(id = "x", method = "GET", pathPattern = "/x"))
        c.pushNow()
        c.onCaptureStopped()

        assertEquals(emptyList<MockRuleSet>(), sent)
        assertEquals(emptyList<String>(), problems, "a reader has no mocks to be 'not live'")
        // ...but it read the device.
        assertTrue(c.deviceState().helloSeen)
        assertEquals(PKG, c.deviceState().pkg)
    }

    // ---- Revision -----------------------------------------------------------------------------

    @Test
    fun `a superseding push after a restart outranks the stop's empty set`() {
        val c = controller(localRevision = 3)
        c.onCaptureStarted()
        c.onControl(hello(rev = 3, count = 1)) // in sync: nothing to push
        sent.clear()

        // attach_device's restart: the fail-safe clear, then the rules again. The two broadcasts
        // travel on separate threads, so the rules must not share the empty set's revision.
        c.onCaptureStopped()
        c.onCaptureStarted()
        c.pushNow(supersede = true)

        val (empty, rules) = sent.toList()
        assertEquals(0, empty.rules.size)
        assertEquals(1, rules.rules.size)
        assertTrue(rules.revision > empty.revision, "revisions were ${revisions()}")

        // Unarmed, a superseding push is still no push — and burns no revision.
        val before = store.get(REVISION_KEY)
        c.onCaptureStopped()
        sent.clear()
        c.pushNow(supersede = true)
        assertEquals(emptyList<MockRuleSet>(), sent)
        assertEquals(before?.toInt()?.plus(1)?.toString(), store.get(REVISION_KEY), "only the stop's bump")
    }

    @Test
    fun `a writer behind the device pushes strictly above the device's revision and persists it`() {
        val c = controller(localRevision = 3)
        c.onCaptureStarted()
        c.onControl(hello(rev = 50, count = 2)) // someone else's two rules; we hold one

        assertEquals(listOf(51), revisions())
        assertEquals("51", store.get(REVISION_KEY))
    }

    @Test
    fun `an equal revision from another writer is still jumped past`() {
        // The library would re-apply an equal revision, but two writers sharing a number also
        // share the device's chunk-reassembly key — strictly greater is the only safe reading.
        val c = controller(localRevision = 7)
        c.onCaptureStarted()
        c.onControl(hello(rev = 7, count = 2))
        assertEquals(listOf(8), revisions())
    }

    @Test
    fun `a writer ahead of the device keeps its plain monotonic counter`() {
        val c = controller(localRevision = 100)
        c.onCaptureStarted()
        c.onControl(hello(rev = 5, count = 0)) // a restarted app lost our rules: re-push as-is
        c.addOrUpdate(MockRule(id = "y", method = "GET", pathPattern = "/y"))
        assertEquals(listOf(100, 101), revisions())
    }

    @Test
    fun `hello then edit — the edit's push uses the bumped revision`() {
        val c = controller(localRevision = 3)
        c.onCaptureStarted()
        c.onControl(hello(rev = 50, count = 1)) // count matches: nothing to push yet
        assertEquals(emptyList<Int>(), revisions())

        c.addOrUpdate(MockRule(id = "y", method = "GET", pathPattern = "/y"))
        assertEquals(listOf(51), revisions())
        assertEquals(2, sent.single().rules.size)
    }

    @Test
    fun `the bumped counter survives a restart of the writer`() {
        val c = controller(localRevision = 3)
        c.onCaptureStarted()
        c.onControl(hello(rev = 50, count = 2))
        assertEquals(listOf(51), revisions())

        newController().addOrUpdate(MockRule(id = "z", method = "GET", pathPattern = "/z"))
        assertEquals("52", store.get(REVISION_KEY))
    }

    @Test
    fun `an ack for a set this session never pushed is recorded, not corrected — the bump waits for our next push`() {
        val c = controller(localRevision = 3)
        c.onCaptureStarted()
        c.onControl(ack(rev = 40, count = 2)) // e.g. scripts/push-mocks.sh
        assertEquals(emptyList<Int>(), revisions(), "learning the device's revision must not push")

        c.setEnabled("r0", false)
        assertEquals(listOf(41), revisions())
    }

    @Test
    fun `our own ack is not a floor — a retry keeps its revision and the budget stays bounded`() {
        val c = controller(localRevision = 3)
        c.onCaptureStarted()
        c.onControl(hello(rev = 0, count = 0)) // reinstall: re-push (retry 1)
        c.onControl(ack(rev = 3, count = 0))   // applied, but the count is wrong (retry 2)
        c.onControl(ack(rev = 3, count = 0))   // still wrong: give up

        assertEquals(listOf(3, 3), revisions())
        assertEquals(1, problems.size)
    }

    @Test
    fun `two armed writers cannot ping-pong forever — the bump does not refill the budget`() {
        val c = controller(localRevision = 3)
        c.onCaptureStarted()
        c.onControl(hello(rev = 3, count = 1)) // in sync
        c.addOrUpdate(MockRule(id = "y", method = "GET", pathPattern = "/y"))
        c.onControl(ack(rev = 4, count = 2))   // our own push, applied
        // Another writer keeps replacing our set with ever-higher revisions.
        c.onControl(ack(rev = 10, count = 4))
        c.onControl(ack(rev = 20, count = 4))
        c.onControl(ack(rev = 30, count = 4))
        c.onControl(ack(rev = 40, count = 4))

        assertEquals(listOf(4, 11, 21), revisions(), "two retries (SyncState.DEFAULT_MAX_RETRIES), then stop")
        assertEquals(1, problems.size, "the takeover is reported, not silent")
    }

    @Test
    fun `stopping clears above the device's revision`() {
        val c = controller(localRevision = 3)
        c.onCaptureStarted()
        c.onControl(hello(rev = 50, count = 1))
        c.onCaptureStopped()
        assertEquals(listOf(51), revisions())
        assertEquals(emptyList<MockRule>(), sent.single().rules)
    }

    // ---- Overflow -----------------------------------------------------------------------------

    @Test
    fun `a device revision at Int MAX saturates instead of wrapping negative`() {
        val c = controller(localRevision = 3)
        c.onCaptureStarted()
        c.onControl(hello(rev = Int.MAX_VALUE, count = 2))
        c.addOrUpdate(MockRule(id = "y", method = "GET", pathPattern = "/y"))
        c.onCaptureStopped()

        // The library re-applies an equal revision, so the ceiling still delivers.
        assertEquals(listOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE), revisions())
        assertEquals(Int.MAX_VALUE.toString(), store.get(REVISION_KEY))
    }

    @Test
    fun `revision arithmetic saturates and never goes negative`() {
        assertEquals(1, Revisions.after(0))
        assertEquals(0, Revisions.after(-1))
        assertEquals(Int.MAX_VALUE, Revisions.after(Int.MAX_VALUE - 1))
        assertEquals(Int.MAX_VALUE, Revisions.after(Int.MAX_VALUE))
    }

    @Test
    fun `a negative persisted counter (an old wrap) restarts from zero`() {
        store.set(REVISION_KEY, "-5")
        newController().addOrUpdate(MockRule(id = "y", method = "GET", pathPattern = "/y"))
        assertEquals("1", store.get(REVISION_KEY))
    }

    private companion object {
        const val PKG = "com.example.app"
        const val REVISION_KEY = "logpose.mock.revision"
    }
}
