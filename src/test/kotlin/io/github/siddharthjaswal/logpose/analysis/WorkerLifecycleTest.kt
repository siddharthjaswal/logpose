package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The observed-transition index behind §6's "Show state transitions".
 *
 * The deduplication case is the load-bearing one: the documented WorkManager integration re-emits
 * every `WorkInfo` on every change, so the same state arrives many times and an un-deduped log
 * would be almost entirely noise.
 */
class WorkerLifecycleTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun event(
        state: String,
        workId: String = "w-1",
        runAttempt: Int = 1,
        at: Long = 1_000,
    ): LogEvent.Worker {
        val work = WorkerEvent(worker = "SyncWorker", state = state, workId = workId, runAttempt = runAttempt)
        return LogEvent.Worker(
            work,
            Envelope(
                kind = Envelope.KIND_WORKER, id = workId, at = at,
                payload = json.encodeToJsonElement(work),
            ),
        )
    }

    @Test
    fun `repeated identical emissions collapse to one transition`() {
        val index = WorkerLifecycle()
        repeat(20) { index.note(event(WorkerEvent.STATE_RUNNING), hostMillis = 100L + it) }
        assertEquals(1, index.transitions("w-1").size)
        assertFalse(index.hasTransitions("w-1"))
    }

    @Test
    fun `the lifecycle is recorded in order`() {
        val index = WorkerLifecycle()
        index.note(event(WorkerEvent.STATE_ENQUEUED, at = 1_000), hostMillis = 10)
        index.note(event(WorkerEvent.STATE_RUNNING, at = 2_000), hostMillis = 20)
        index.note(event(WorkerEvent.STATE_RUNNING, at = 2_500), hostMillis = 25)
        index.note(event(WorkerEvent.STATE_SUCCEEDED, at = 3_000), hostMillis = 30)

        val states = index.transitions("w-1").map { it.state }
        assertEquals(listOf("enqueued", "running", "succeeded"), states)
        assertEquals(listOf(1_000L, 2_000L, 3_000L), index.transitions("w-1").map { it.atMillis })
        assertEquals(listOf(10L, 20L, 30L), index.transitions("w-1").map { it.hostMillis })
        assertTrue(index.hasTransitions("w-1"))
    }

    @Test
    fun `a retry is a transition even when the state word does not change`() {
        val index = WorkerLifecycle()
        index.note(event(WorkerEvent.STATE_RUNNING, runAttempt = 1), hostMillis = 10)
        index.note(event(WorkerEvent.STATE_RUNNING, runAttempt = 2), hostMillis = 20)
        assertEquals(listOf(1, 2), index.transitions("w-1").map { it.runAttempt })
    }

    @Test
    fun `a single sighting is not a sequence`() {
        val index = WorkerLifecycle()
        // The replayed-at-attach case: WorkManager's persisted store hands over one terminal state.
        index.note(event(WorkerEvent.STATE_SUCCEEDED), hostMillis = 10)
        assertFalse(index.hasTransitions("w-1"))
    }

    @Test
    fun `the per-request cap keeps the first sighting and drops from the middle`() {
        val index = WorkerLifecycle(perIdCap = 4)
        index.note(event(WorkerEvent.STATE_ENQUEUED, runAttempt = 0), hostMillis = 0)
        repeat(10) { index.note(event(WorkerEvent.STATE_RUNNING, runAttempt = it + 1), hostMillis = it.toLong()) }

        val kept = index.transitions("w-1")
        assertEquals(4, kept.size)
        assertEquals("enqueued", kept.first().state)
        assertEquals(10, kept.last().runAttempt)
    }

    @Test
    fun `the request cap evicts the oldest request`() {
        val index = WorkerLifecycle(idCap = 2)
        index.note(event(WorkerEvent.STATE_ENQUEUED, workId = "a"), hostMillis = 1)
        index.note(event(WorkerEvent.STATE_ENQUEUED, workId = "b"), hostMillis = 2)
        index.note(event(WorkerEvent.STATE_ENQUEUED, workId = "c"), hostMillis = 3)

        assertTrue(index.transitions("a").isEmpty())
        assertEquals(1, index.transitions("b").size)
        assertEquals(1, index.transitions("c").size)
    }

    @Test
    fun `a worker with no workId is filed under its envelope id`() {
        val index = WorkerLifecycle()
        val work = WorkerEvent(worker = "SyncWorker", state = WorkerEvent.STATE_RUNNING, workId = null)
        val ev = LogEvent.Worker(
            work,
            Envelope(kind = Envelope.KIND_WORKER, id = "env-9", at = 1, payload = json.encodeToJsonElement(work)),
        )
        assertEquals("env-9", index.keyOf(ev))
        index.note(ev, hostMillis = 1)
        assertEquals(1, index.transitions("env-9").size)
    }

    @Test
    fun `clear drops everything`() {
        val index = WorkerLifecycle()
        index.note(event(WorkerEvent.STATE_ENQUEUED), hostMillis = 1)
        index.note(event(WorkerEvent.STATE_RUNNING), hostMillis = 2)
        index.clear()
        assertTrue(index.transitions("w-1").isEmpty())
    }
}
