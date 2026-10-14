package io.github.siddharthjaswal.logpose.store

import io.github.siddharthjaswal.logpose.mcp.McpTools
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which app run an event belongs to, and whether an open span can still finish — the three capture
 * bugs a live gandalf session exposed: requests from dead runs reported "in flight" forever, events
 * before a missed hello stranded in session 0, and a run's span stretched by rows a later run
 * re-emitted.
 */
class RunAttributionTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun open(id: String, at: Long = 1_000): LogEvent.Http {
        val tx = Transaction(id = id, startedAtMillis = at, request = Request("GET", "https://x/y", path = "/y"))
        return LogEvent.Http(tx, Envelope(kind = Envelope.KIND_HTTP, id = id, at = at, payload = json.encodeToJsonElement(tx)))
    }

    private fun done(id: String, at: Long = 1_000): LogEvent.Http {
        val tx = Transaction(
            id = id, startedAtMillis = at, request = Request("GET", "https://x/y", path = "/y"),
            response = Response(200), durationMillis = 10,
        )
        return LogEvent.Http(tx, Envelope(kind = Envelope.KIND_HTTP, id = id, at = at, endedAt = at + 10, payload = json.encodeToJsonElement(tx)))
    }

    private fun worker(workId: String, at: Long, state: String = "succeeded", replayed: Boolean = true): LogEvent.Worker {
        val w = WorkerEvent(worker = "DataSyncWorker", state = state, workId = workId, replayedAtAttach = replayed)
        val ended = if (state in WorkerEvent.TERMINAL) at else null
        return LogEvent.Worker(w, Envelope(kind = Envelope.KIND_WORKER, id = workId, at = at, endedAt = ended, payload = json.encodeToJsonElement(w)))
    }

    private fun EventStore.event(id: String) = snapshot().single { it.id == id }

    // ---- issue 2: session 0, the pid and the hello request --------------------------------------

    @Test fun `events from a pid whose hello was missed are claimed when it announces`() {
        val store = EventStore()
        store.add(done("a"), pid = 100)
        store.add(done("b"), pid = 100)
        assertEquals(0, store.sessionOf("a"))
        assertTrue(store.needsHello(100))

        // The 1.7.4 library's answer to the hello request: same processId it launched with.
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)

        assertEquals(1, store.sessionOf("a"))
        assertEquals(1, store.sessionOf("b"))
        assertFalse(store.needsHello(100))
        store.add(done("c"), pid = 100)
        assertEquals(1, store.sessionOf("c"))
    }

    @Test fun `another process's unattributed events are not claimed`() {
        val store = EventStore()
        store.add(done("theirs"), pid = 200)
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)
        assertEquals(0, store.sessionOf("theirs"))
        assertTrue(store.needsHello(200))
    }

    @Test fun `pid reuse never hands a dead run's rows to the new one`() {
        val store = EventStore()
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)
        store.add(done("old"), pid = 100)
        // The OS recycled the pid for the next launch — a different run (new processId).
        store.noteHello("run-2", "com.acme", "1.7.4", pid = 100)
        store.add(done("new"), pid = 100)

        assertEquals(2, store.sessions().size)
        assertEquals(1, store.sessionOf("old"))
        assertEquals(2, store.sessionOf("new"))
    }

    @Test fun `a hello re-sent by the same run is not a restart`() {
        val store = EventStore()
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)
        store.add(open("req"), pid = 100)
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100) // first intercept / hello request
        assertEquals(1, store.sessions().size)
        assertTrue(store.event("req").isInFlight)
    }

    @Test fun `a re-announcement can supply the pid a pid-less launch hello lacked`() {
        val store = EventStore()
        store.noteHello("run-1", "com.acme", "1.7.4") // e.g. replayed from a file, no prefix
        store.add(done("a"), pid = 100)
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)
        assertEquals(1, store.sessions().single().index)
        assertEquals(100, store.sessions().single().pid)
        assertEquals(1, store.sessionOf("a"))
    }

    @Test fun `without pids the store behaves exactly as before`() {
        val store = EventStore()
        store.add(done("pre"))
        store.noteHello("run-1", "com.acme", "1.7.0")
        store.add(done("a"))
        assertEquals(0, store.sessionOf("pre"))
        assertEquals(1, store.sessionOf("a"))
        assertFalse(store.needsHello(null))
    }

    // ---- issue 1: abandoned ---------------------------------------------------------------------

    @Test fun `a newer run of the same app abandons the old run's open requests`() {
        val store = EventStore()
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)
        store.add(open("stuck"), pid = 100)
        store.add(done("fine"), pid = 100)
        store.noteHello("run-2", "com.acme", "1.7.4", pid = 101)

        val stuck = store.event("stuck")
        assertEquals(LogEvent.REASON_APP_RESTARTED, stuck.abandoned)
        assertFalse(stuck.isInFlight)
        assertTrue(stuck.isOpen)
        assertNull(store.event("fine").abandoned)
        // The new run's own requests are in flight as normal.
        store.add(open("fresh"), pid = 101)
        assertTrue(store.event("fresh").isInFlight)
    }

    /** What `/proc/<pid>/cmdline` says right now; absent = the pid is gone. Answers synchronously. */
    private fun probe(running: MutableMap<Int, String>) =
        io.github.siddharthjaswal.logpose.logcat.ProcessNames { pids, answer -> answer(pids.associateWith { running[it] }) }

    @Test fun `a second process of the same app announcing does not abandon the main run`() {
        val running = mutableMapOf(100 to "com.acme")
        val store = EventStore().apply { processNames = probe(running) }
        store.noteHello("main", "com.acme", "1.7.4", pid = 100)
        store.add(open("req"), pid = 100)
        running[101] = "com.acme:service"
        store.noteHello("svc", "com.acme", "1.7.4", pid = 101)

        assertTrue(store.event("req").isInFlight)
        assertEquals(listOf("com.acme", "com.acme:service"), store.sessions().map { it.processName })
    }

    @Test fun `the same process name on a new pid is a restart`() {
        val running = mutableMapOf(100 to "com.acme")
        val store = EventStore().apply { processNames = probe(running) }
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)
        store.add(open("req"), pid = 100)
        running.remove(100); running[101] = "com.acme"
        store.noteHello("run-2", "com.acme", "1.7.4", pid = 101)
        assertEquals(LogEvent.REASON_APP_RESTARTED, store.event("req").abandoned)
    }

    @Test fun `a restarted service process abandons only the service run`() {
        val running = mutableMapOf(100 to "com.acme", 101 to "com.acme:service")
        val store = EventStore().apply { processNames = probe(running) }
        store.noteHello("main", "com.acme", "1.7.4", pid = 100)
        store.add(open("main-req"), pid = 100)
        store.noteHello("svc-1", "com.acme", "1.7.4", pid = 101)
        store.add(open("svc-req"), pid = 101)
        running.remove(101); running[102] = "com.acme:service"
        store.noteHello("svc-2", "com.acme", "1.7.4", pid = 102)

        assertTrue(store.event("main-req").isInFlight)
        assertEquals(LogEvent.REASON_APP_RESTARTED, store.event("svc-req").abandoned)
    }

    @Test fun `a run whose name was never read is abandoned once its pid is gone`() {
        val running = mutableMapOf<Int, String>()
        val store = EventStore().apply { processNames = probe(running) }
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100) // probe found nothing (exited, adb down)
        store.add(open("req"), pid = 100)
        running[101] = "com.acme"
        store.noteHello("run-2", "com.acme", "1.7.4", pid = 101)
        assertEquals(LogEvent.REASON_APP_RESTARTED, store.event("req").abandoned)
    }

    @Test fun `an unannounced process that is still alive keeps its open requests`() {
        val running = mutableMapOf(90 to "com.acme:sync", 100 to "com.acme")
        val store = EventStore().apply { processNames = probe(running) }
        store.add(open("sync-req"), pid = 90)
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)
        assertTrue(store.event("sync-req").isInFlight)
    }

    @Test fun `a probe answering later still settles the run`() {
        var pending: (() -> Unit)? = null
        val store = EventStore().apply {
            processNames = io.github.siddharthjaswal.logpose.logcat.ProcessNames { pids, answer ->
                pending = { answer(pids.associateWith { if (it == 101) "com.acme" else null }) }
            }
        }
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)
        store.add(open("req"), pid = 100)
        store.noteHello("run-2", "com.acme", "1.7.4", pid = 101)
        assertTrue(store.event("req").isInFlight) // not decided until the probe answers
        pending!!.invoke()
        assertEquals(LogEvent.REASON_APP_RESTARTED, store.event("req").abandoned)
    }

    @Test fun `abandonment works without pids too, from the hello alone`() {
        val store = EventStore()
        store.noteHello("run-1", "com.acme", "1.7.0")
        store.add(open("stuck"))
        store.noteHello("run-2", "com.acme", "1.7.0")
        assertEquals(LogEvent.REASON_APP_RESTARTED, store.event("stuck").abandoned)
    }

    @Test fun `a hello from a different app abandons nothing`() {
        val store = EventStore()
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)
        store.add(open("req"), pid = 100)
        store.noteHello("other", "com.other", "1.7.4", pid = 300)
        assertTrue(store.event("req").isInFlight)
    }

    @Test fun `an unannounced process's open request is abandoned once a newer process announces`() {
        val store = EventStore()
        store.add(open("orphan"), pid = 90)      // the previous launch; its hello predates capture
        store.add(open("mine"), pid = 100)       // this launch, before its hello arrived
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)

        assertEquals(LogEvent.REASON_APP_RESTARTED, store.event("orphan").abandoned)
        assertTrue(store.event("mine").isInFlight)
        assertEquals(1, store.sessionOf("mine"))
    }

    @Test fun `a sign of life from a written-off run revives it`() {
        // Two processes of one app (main + a :service process) announcing alternately: the second
        // hello looks like a restart until the first process speaks again.
        val store = EventStore()
        store.noteHello("main", "com.acme", "1.7.4", pid = 100)
        store.add(open("req"), pid = 100)
        store.noteHello("svc", "com.acme", "1.7.4", pid = 101)
        assertEquals(LogEvent.REASON_APP_RESTARTED, store.event("req").abandoned)

        store.add(done("later"), pid = 100)
        assertTrue(store.event("req").isInFlight)
        assertEquals(1, store.sessionOf("later"))
    }

    @Test fun `the response arriving clears the mark`() {
        val store = EventStore()
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)
        store.add(open("req"), pid = 100)
        store.noteDeviceGone()
        assertEquals(LogEvent.REASON_DEVICE_DISCONNECTED, store.event("req").abandoned)
        store.add(done("req"), pid = 100)
        assertNull(store.event("req").abandoned)
        assertFalse(store.event("req").isOpen)
    }

    @Test fun `a reconnect that hears from the same run clears device disconnected`() {
        val store = EventStore()
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)
        store.add(open("req"), pid = 100)
        store.noteDeviceGone()
        assertFalse(store.event("req").isInFlight)
        store.add(done("other"), pid = 100)
        assertTrue(store.event("req").isInFlight)
    }

    @Test fun `session_summary counts in_flight and abandoned apart`() {
        val store = EventStore()
        store.noteHello("run-1", "com.acme", "1.7.4", pid = 100)
        store.add(open("dead"), pid = 100)
        store.noteHello("run-2", "com.acme", "1.7.4", pid = 101)
        store.add(open("live"), pid = 101)

        val out = summary(store)
        assertEquals(1, out["in_flight"]!!.jsonPrimitive.int)
        assertEquals("live", out["in_flight_ids"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(1, out["abandoned"]!!.jsonPrimitive.int)
        assertEquals("dead", out["abandoned_ids"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(1, out["abandoned_by_reason"]!!.jsonObject[LogEvent.REASON_APP_RESTARTED]!!.jsonPrimitive.int)

        val dead = McpTools.call("get_event", buildJsonObject { put("id", kotlinx.serialization.json.JsonPrimitive("dead")) },
            store.snapshot(), { 0 }, true, sessionOf = store::sessionOf).jsonObject
        assertEquals(LogEvent.REASON_APP_RESTARTED, dead["abandoned_reason"]!!.jsonPrimitive.content)
        assertNull(dead["in_flight"])
    }

    // ---- issue 3: the run span ------------------------------------------------------------------

    @Test fun `a worker row a later run re-emits no longer stretches the earlier run's span`() {
        // The live gandalf shape: each launch replays WorkManager's persisted work on attach, under
        // the same workId, with `at` = now. Run 1 kept the row, run 2 rewrote its `at`, and run 1's
        // span ran to run 2's replay — "12 events over 42 minutes", ending 1ms from run 2.
        val store = EventStore()
        store.noteHello("run-1", "com.acme", "1.7.0")
        store.add(done("r1-a", at = 1_000))
        store.add(worker("work-1", at = 1_500))
        store.add(done("r1-b", at = 5_000))
        store.noteHello("run-2", "com.acme", "1.7.0")
        store.add(done("r2-a", at = 2_520_000))
        store.add(worker("work-1", at = 2_520_100)) // the replay on run 2's attach

        val runs = summary(store)["sessions"]!!.jsonArray.map { it.jsonObject }
        val run1 = runs.first { it["session"]!!.jsonPrimitive.int == 1 }
        assertEquals(2, run1["events"]!!.jsonPrimitive.int)
        assertEquals(5_000, run1["last_at"]!!.jsonPrimitive.long)
        assertEquals(4_000, run1["duration_ms"]!!.jsonPrimitive.long)
        val run2 = runs.first { it["session"]!!.jsonPrimitive.int == 2 }
        assertEquals(2, run2["events"]!!.jsonPrimitive.int)
    }

    private fun summary(store: EventStore): JsonObject = McpTools.call(
        "session_summary", JsonObject(emptyMap()), store.snapshot(), { 0 }, true,
        sessions = store.sessions(), sessionOf = store::sessionOf,
    ).jsonObject
}
