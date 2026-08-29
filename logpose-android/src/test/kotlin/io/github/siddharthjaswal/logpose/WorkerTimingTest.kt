package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.export.ExportBuffer
import io.github.siddharthjaswal.logpose.wire.WorkerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A worker is **one row that updates in place** — every state re-emits the same envelope id — so
 * the instants the IDE needs (when the request started waiting, when it started running) are
 * overwritten long before the terminal state lands. LogPose therefore times the transitions it
 * observes itself and re-stamps them on every emission.
 *
 * These cases are the contract for that: what gets stamped, what deliberately stays null because
 * LogPose never saw it happen, and that the bookkeeping behind it cannot grow with the session.
 *
 * Emissions are read off the export ring — the same lines that go to logcat, minus Android (see
 * `PushInjectorTest`).
 */
class WorkerTimingTest {

    @Before fun setUp() = reset()
    @After fun tearDown() = reset()

    /** Attached "a minute ago", so first sightings count as live transitions, not replayed state.
     *  The attach-burst behaviour has its own cases below. */
    private fun reset() {
        LogPose.resetWorkerTracking(attachedAtMillis = System.currentTimeMillis() - 60_000)
        ExportBuffer.clear()
    }

    // ---- what a full life stamps ---------------------------------------------------------------

    @Test fun `the terminal emission still carries the enqueue and run instants`() {
        log("w1", WorkerEvent.STATE_ENQUEUED)
        log("w1", WorkerEvent.STATE_RUNNING)
        log("w1", WorkerEvent.STATE_SUCCEEDED)

        val rows = emitted()
        assertEquals("one workId is one row, three updates", listOf("w1", "w1", "w1"), rows.map { it.id })

        val terminal = rows.last()
        assertNotNull("the enqueue must survive the two overwrites after it", terminal.enqueuedAt)
        assertNotNull(terminal.runStartedAt)
        assertTrue(terminal.enqueuedAt!! <= terminal.runStartedAt!!)
        assertTrue("the run cannot start after the span closed", terminal.runStartedAt!! <= terminal.endedAt!!)
        // The whole point: the run leg is separable from the queue leg.
        assertEquals(rows.first().at, terminal.at)
    }

    @Test fun `the enqueued row reports its wait as open, not as a wait of zero`() {
        log("w1", WorkerEvent.STATE_ENQUEUED)

        val row = emitted().single()
        assertNotNull(row.enqueuedAt)
        assertNull("nothing has run yet, and a consumer must not guess", row.runStartedAt)
    }

    @Test fun `re-delivering the same state does not move either instant`() {
        // The documented WorkManager integration observes a LiveData of *all* WorkInfos, so every
        // request is re-logged on every change — unchanged ones included.
        log("w1", WorkerEvent.STATE_ENQUEUED)
        repeat(4) { log("w1", WorkerEvent.STATE_ENQUEUED) }
        log("w1", WorkerEvent.STATE_RUNNING)
        repeat(4) { log("w1", WorkerEvent.STATE_RUNNING) }

        val rows = emitted()
        assertEquals(
            "a repeat is not a transition; the enqueue instant is fixed",
            1, rows.mapNotNull { it.enqueuedAt }.distinct().size,
        )
        assertEquals(
            "re-logging `running` must not keep restarting the run",
            1, rows.mapNotNull { it.runStartedAt }.distinct().size,
        )
        assertEquals(rows.first().enqueuedAt, rows.last().enqueuedAt)
    }

    @Test fun `blocked then enqueued is one queue phase, timed from the block`() {
        log("w1", WorkerEvent.STATE_BLOCKED)
        val blockedAt = emitted().single().enqueuedAt
        log("w1", WorkerEvent.STATE_ENQUEUED)

        assertNotNull(blockedAt)
        assertEquals(
            "the request has been waiting since it was blocked, not since its prerequisites cleared",
            blockedAt, emitted().last().enqueuedAt,
        )
    }

    // ---- retries ------------------------------------------------------------------------------

    @Test fun `a retry re-times both instants against the attempt being reported`() {
        log("w1", WorkerEvent.STATE_ENQUEUED)
        log("w1", WorkerEvent.STATE_RUNNING)
        val firstAttempt = emitted().last()

        log("w1", WorkerEvent.STATE_ENQUEUED, attempt = 1)   // backoff
        val backoff = emitted().last()
        assertNull("the previous attempt's run is over; it must not describe this one", backoff.runStartedAt)
        assertTrue("the wait now being reported is the backoff", backoff.enqueuedAt!! >= firstAttempt.enqueuedAt!!)

        log("w1", WorkerEvent.STATE_RUNNING, attempt = 1)
        log("w1", WorkerEvent.STATE_SUCCEEDED, attempt = 1)
        val terminal = emitted().last()
        assertEquals("queue wait is the backoff, not the original enqueue", backoff.enqueuedAt, terminal.enqueuedAt)
        assertTrue(terminal.runStartedAt!! >= backoff.enqueuedAt!!)
    }

    // ---- what LogPose refuses to guess ---------------------------------------------------------

    @Test fun `a request first seen already running reports no timing at all`() {
        LogPose.resetWorkerTracking()   // the observer attaches now, and replays what it finds

        log("w1", WorkerEvent.STATE_RUNNING)

        val row = emitted().single()
        assertNull("LogPose never saw this request wait, so it has nothing to report", row.enqueuedAt)
        assertNull("nor did it see the run start — timing it from attach would invent a number", row.runStartedAt)
    }

    @Test fun `a request already queued at attach does not report the wait as starting now`() {
        LogPose.resetWorkerTracking()

        log("w1", WorkerEvent.STATE_ENQUEUED)
        assertNull(
            "this request may have been queued for hours; `queued 0s` would be a lie",
            emitted().single().enqueuedAt,
        )

        // Transitions after the burst are observed live, so the run start is real.
        log("w1", WorkerEvent.STATE_RUNNING)
        val running = emitted().last()
        assertNull("still no observed wait", running.enqueuedAt)
        assertNotNull("but the start of the run was seen", running.runStartedAt)
    }

    @Test fun `work replayed from WorkManager's store carries no timing`() {
        LogPose.resetWorkerTracking()

        log("w1", WorkerEvent.STATE_SUCCEEDED)

        val row = emitted().single()
        assertTrue("terminal on first sight means it ran before we were watching", row.replayed)
        assertNull(row.enqueuedAt)
        assertNull(row.runStartedAt)
    }

    @Test fun `an unobserved instant costs nothing on the wire`() {
        LogPose.resetWorkerTracking()
        log("w1", WorkerEvent.STATE_SUCCEEDED)

        val payload = ExportBuffer.snapshot().single()
            .let { json.parseToJsonElement(it).jsonObject.getValue("payload").jsonObject }
        assertFalse("explicitNulls = false — a null field is an absent key", "enqueuedAtMillis" in payload)
        assertFalse("runStartedAtMillis" in payload)
    }

    // ---- the bookkeeping behind it --------------------------------------------------------------

    @Test fun `a terminal state forgets the request immediately`() {
        log("w1", WorkerEvent.STATE_ENQUEUED)
        assertEquals(1, LogPose.trackedWorkerCount())

        log("w1", WorkerEvent.STATE_SUCCEEDED)
        assertEquals("a completed request is not worth remembering", 0, LogPose.trackedWorkerCount())
    }

    @Test fun `requests that never terminate cannot grow the map without bound`() {
        // Periodic work cycles enqueued → running → enqueued forever and never reaches a terminal
        // state, so terminal eviction alone would leak one entry per request for the process's life.
        repeat(MAX_TRACKED + 50) { log("w-$it", WorkerEvent.STATE_ENQUEUED) }

        assertEquals(MAX_TRACKED, LogPose.trackedWorkerCount())
    }

    @Test fun `an actively updating request survives eviction pressure`() {
        log("periodic", WorkerEvent.STATE_ENQUEUED)
        repeat(MAX_TRACKED) {
            log("w-$it", WorkerEvent.STATE_ENQUEUED)
            log("periodic", WorkerEvent.STATE_ENQUEUED)   // touched, so it is never the eldest
        }
        ExportBuffer.clear()

        log("periodic", WorkerEvent.STATE_RUNNING)
        assertNotNull(
            "the request still reporting is the one worth keeping",
            emitted().single().enqueuedAt,
        )
    }

    // ---- compatibility with a payload that predates the fields ----------------------------------

    @Test fun `a worker payload round-trips both instants`() {
        val work = WorkerEvent(
            worker = "SyncWorker", state = WorkerEvent.STATE_SUCCEEDED, workId = "w1",
            enqueuedAtMillis = 1_000, runStartedAtMillis = 7_200,
        )
        val back = wireJson.decodeFromString(
            WorkerEvent.serializer(),
            wireJson.encodeToString(WorkerEvent.serializer(), work),
        )
        assertEquals(work, back)
    }

    @Test fun `a payload written before the fields existed decodes to null, not to zero`() {
        // The plugin ships ahead of the library through a different channel, so a new plugin reads
        // old captures for weeks. Absent must mean "unknown", so the UI degrades to showing nothing.
        val old = """{"worker":"SyncWorker","state":"succeeded","workId":"w1","runAttempt":0}"""
        val back = wireJson.decodeFromString(WorkerEvent.serializer(), old)

        assertNull(back.enqueuedAtMillis)
        assertNull(back.runStartedAtMillis)
        assertEquals("SyncWorker", back.worker)
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private val capture = LogPoseConfig(exportEnabled = true)

    private fun log(workId: String, state: String, attempt: Int = 0) = LogPose.logWorker(
        WorkerEventInfo(worker = "SyncWorker", state = state, workId = workId, runAttempt = attempt),
        capture,
    )

    private data class Row(
        val id: String,
        val at: Long,
        val endedAt: Long?,
        val enqueuedAt: Long?,
        val runStartedAt: Long?,
        val replayed: Boolean,
    )

    private fun emitted(): List<Row> = ExportBuffer.snapshot().map { line ->
        val envelope = json.parseToJsonElement(line).jsonObject
        val payload = envelope.getValue("payload").jsonObject
        Row(
            id = envelope.getValue("id").jsonPrimitive.content,
            at = envelope.getValue("at").jsonPrimitive.long,
            endedAt = envelope["endedAt"]?.jsonPrimitive?.long,
            enqueuedAt = payload["enqueuedAtMillis"]?.jsonPrimitive?.long,
            runStartedAt = payload["runStartedAtMillis"]?.jsonPrimitive?.long,
            replayed = payload["replayedAtAttach"]?.jsonPrimitive?.content == "true",
        )
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val wireJson = Json { encodeDefaults = true; explicitNulls = false }

    private companion object {
        /** Mirrors `LogPose.MAX_TRACKED_WORKERS`; the cap is the point of the test above. */
        const val MAX_TRACKED = 256
    }
}
