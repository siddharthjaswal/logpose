package io.github.siddharthjaswal.logpose.store

import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Session boundaries: the store's job is to keep two app runs from being reported as one
 * timeline. The library announces itself more than once per run, so "a hello arrived" is not
 * the same question as "the app restarted" — that distinction is what these pin.
 */
class EventStoreTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun event(id: String, at: Long = 1_000): LogEvent.Http {
        val tx = Transaction(
            id = id,
            startedAtMillis = at,
            request = Request(method = "GET", url = "https://api.example.com/x", path = "/x"),
        )
        return LogEvent.Http(
            tx,
            Envelope(kind = Envelope.KIND_HTTP, id = id, at = at, payload = json.encodeToJsonElement(tx)),
        )
    }

    private fun analytics(id: String): LogEvent.Generic {
        val ev = io.github.siddharthjaswal.logpose.model.GenericEvent(title = "SFX_GEOFENCE_EVALUATE_LOCATION")
        return LogEvent.Generic(ev, Envelope(kind = Envelope.KIND_ANALYTICS, id = id, at = 1, payload = json.encodeToJsonElement(ev)))
    }

    private fun store() = EventStore()

    @Test fun `a flood of analytics can't evict other kinds`() {
        // The real capture: 365 geofence analytics events in 9 minutes drowning the one accept.
        val store = store()
        store.add(event("the-accept"))
        repeat(1_000) { store.add(analytics("a$it")) }

        val snapshot = store.snapshot()
        assertTrue(snapshot.any { it.id == "the-accept" }, "the HTTP event must survive the analytics flood")
        val analyticsCount = snapshot.count { it.kind == Envelope.KIND_ANALYTICS }
        assertTrue(analyticsCount <= 400, "analytics is capped per-kind, not left to fill the buffer")
    }

    @Test fun `a second hello from the same process does not split the session`() {
        // The provider says hello at startup and the interceptor re-announces on its first call.
        // Treating that as a restart would cut every capture in two at the first request.
        val store = store()
        store.noteHello("pid-1", "com.acme", "1.5.0")
        store.add(event("a"))
        store.noteHello("pid-1", "com.acme", "1.5.0")
        store.add(event("b"))

        assertEquals(1, store.sessions().size)
        assertEquals(1, store.sessionOf("a"))
        assertEquals(1, store.sessionOf("b"))
    }

    @Test fun `a hello from a new process starts a session`() {
        val store = store()
        store.noteHello("pid-1", "com.acme", "1.5.0")
        store.add(event("a"))
        store.noteHello("pid-2", "com.acme", "1.5.0")
        store.add(event("b"))

        assertEquals(listOf(1, 2), store.sessions().map { it.index })
        assertEquals(1, store.sessionOf("a"))
        assertEquals(2, store.sessionOf("b"))
    }

    @Test fun `events captured before any handshake are unattributed`() {
        // Capture started mid-run: the launch hello was already cleared from the log buffer.
        val store = store()
        store.add(event("orphan"))
        assertEquals(0, store.sessionOf("orphan"))
        assertEquals(0, store.sessions().size)
    }

    @Test fun `an event updated in place keeps the session it arrived in`() {
        // A response landing on its request's row must not migrate the row into a later run,
        // or a call that straddles a restart gets counted against the wrong session.
        val store = store()
        store.noteHello("pid-1", "com.acme", "1.5.0")
        store.add(event("a"))
        store.noteHello("pid-2", "com.acme", "1.5.0")
        store.add(event("a", at = 2_000))

        assertEquals(1, store.sessionOf("a"))
    }

    @Test fun `an old library without a process id still splits on relaunch`() {
        // Pre-1.5.0 libraries send no process id. The fallback assumes a hello arriving after
        // events have landed marks a restart — right for the relaunch case that matters.
        val store = store()
        store.noteHello("", "com.acme", "1.4.0")
        store.add(event("a"))
        store.noteHello("", "com.acme", "1.4.0")
        store.add(event("b"))

        assertEquals(listOf(1, 2), store.sessions().map { it.index })
        assertEquals(1, store.sessionOf("a"))
        assertEquals(2, store.sessionOf("b"))
    }

    // ---- waiters ----------------------------------------------------------------------------

    /** Completions run inline so a wait's outcome is observable the moment it's decided. */
    private fun waitingStore() = EventStore(completer = { it.run() })

    @Test fun `a waiter completes with the event that arrives after it parks`() {
        val store = waitingStore()
        store.add(event("before"))

        val future = store.addWaiter(5_000) { it.id == "after" }!!
        assertTrue(!future.isDone, "an event captured before the wait must not satisfy it")

        store.add(event("noise"))
        assertTrue(!future.isDone)

        store.add(event("after"))
        assertEquals("after", future.get(2, TimeUnit.SECONDS)?.id)
        assertEquals(0, store.waiterCount(), "a completed waiter is removed, not left to leak")
    }

    @Test fun `a waiter completes with null when its timeout elapses`() {
        // A timeout is an answer ("nothing happened"), which is why it completes rather than fails.
        val store = waitingStore()
        val future = store.addWaiter(20) { true }!!
        assertEquals(null, future.get(2, TimeUnit.SECONDS))
        assertEquals(0, store.waiterCount())
    }

    @Test fun `a timed-out waiter can't be completed twice by a later event`() {
        val store = waitingStore()
        val future = store.addWaiter(20) { true }!!
        future.get(2, TimeUnit.SECONDS)
        store.add(event("late"))
        assertEquals(null, future.get(), "the timeout already claimed this waiter")
    }

    @Test fun `waiters are capped so a client can't park an unbounded queue`() {
        val store = waitingStore()
        val futures = (1..EventStore.MAX_WAITERS).map { store.addWaiter(5_000) { false } }
        assertTrue(futures.all { it != null })
        assertEquals(null, store.addWaiter(5_000) { true }, "beyond the cap the store refuses, clearly")
        assertEquals(EventStore.MAX_WAITERS, store.waiterCount())
    }

    @Test fun `a throwing predicate is not a match and does not break the capture`() {
        val store = waitingStore()
        val future = store.addWaiter(5_000) { error("bad predicate") }!!
        store.add(event("a"))
        assertTrue(!future.isDone)
        assertEquals(1, store.snapshot().size, "the event is still recorded")
    }

    @Test fun `clear drops sessions along with events`() {
        val store = store()
        store.noteHello("pid-1", "com.acme", "1.5.0")
        store.add(event("a"))
        store.clear()

        assertEquals(0, store.sessions().size)
        assertEquals(0, store.sessionOf("a"))
        assertEquals(0, store.snapshot().size)
    }
}
