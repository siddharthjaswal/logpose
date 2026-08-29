package io.github.siddharthjaswal.logpose.ui

import io.github.siddharthjaswal.logpose.model.Badge
import io.github.siddharthjaswal.logpose.model.ConfigChange
import io.github.siddharthjaswal.logpose.model.ConfigUpdate
import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The per-kind row rules from §6, pinned away from Swing.
 *
 * Several of these assertions are really honesty tests rather than layout tests: a worker's fact
 * cell must print a queue wait **only** when the device measured one and stay blank otherwise, and
 * an analytics event's wire subtitle must survive unchanged, because MCP and search read it.
 *
 * The worker fixture defaults both timing instants to null on purpose — that is a pre-1.7.2
 * library's payload, and every cell must render for it exactly as it did before the fields existed.
 */
class RowContentTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    private fun worker(
        state: String,
        worker: String = "DataSyncWorker",
        runAttempt: Int = 1,
        tags: List<String> = emptyList(),
        replayed: Boolean = false,
        at: Long = 1_000,
        endedAt: Long? = null,
        enqueuedAt: Long? = null,
        runStartedAt: Long? = null,
    ): LogEvent.Worker {
        val work = WorkerEvent(
            worker = worker, state = state, workId = "w-1", runAttempt = runAttempt,
            tags = tags, replayedAtAttach = replayed,
            enqueuedAtMillis = enqueuedAt, runStartedAtMillis = runStartedAt,
        )
        return LogEvent.Worker(
            work,
            Envelope(
                kind = Envelope.KIND_WORKER, id = "w-1", at = at, endedAt = endedAt,
                payload = json.encodeToJsonElement(work),
            ),
        )
    }

    private fun analytics(name: String, screen: String?, provider: String? = "firebase"): LogEvent.Generic {
        val ev = GenericEvent(
            title = name,
            subtitle = screen,
            badges = buildList {
                add(Badge("ANALYTICS", Badge.TONE_INFO))
                provider?.let { add(Badge(it.uppercase(), Badge.TONE_MUTED)) }
            },
        )
        return LogEvent.Generic(
            ev,
            Envelope(kind = Envelope.KIND_ANALYTICS, id = "a1", at = 1, payload = json.encodeToJsonElement(ev)),
        )
    }

    private fun dbEvent(query: DbQuery) = LogEvent.Db(
        query,
        Envelope(kind = Envelope.KIND_DB, id = "d1", at = 1, payload = json.encodeToJsonElement(query)),
    )

    // ---- worker -------------------------------------------------------------------------------

    @Test fun `each worker state gets its own glyph, weight and token - and no new hue`() {
        assertEquals(
            RowContent.StateToken("✓ succeeded", RowContent.Token.TEXT_DIM, RowContent.Weight.REGULAR),
            RowContent.workerState("succeeded"),
        )
        assertEquals(
            RowContent.StateToken(
                "● running", RowContent.Token.ACCENT, RowContent.Weight.BOLD,
                RowContent.Decoration.PULSING_DOT,
            ),
            RowContent.workerState("running"),
        )
        assertEquals(
            RowContent.StateToken("◦ enqueued", RowContent.Token.TEXT_MUTED, RowContent.Weight.REGULAR),
            RowContent.workerState("enqueued"),
        )
        assertEquals(
            RowContent.StateToken("✕ failed", RowContent.Token.DANGER, RowContent.Weight.BOLD),
            RowContent.workerState("failed"),
        )
        assertEquals(
            RowContent.Decoration.STRIKETHROUGH, RowContent.workerState("cancelled").decoration,
        )
        // Success is the common case, so it stays neutral — a wall of green would be noise.
        assertFalse(
            RowContent.workerState("succeeded").token == RowContent.Token.WARN ||
                RowContent.workerState("succeeded").token == RowContent.Token.DANGER
        )
    }

    @Test fun `an unrecognised state is still said plainly`() {
        assertEquals("weird", RowContent.workerState("WEIRD").text)
        assertEquals(RowContent.Token.TEXT_MUTED, RowContent.workerState("WEIRD").token)
    }

    @Test fun `only a genuine retry gets the pill`() {
        assertNull(RowContent.worker(worker("running", runAttempt = 1)).retry)
        assertEquals("RETRY ×3", RowContent.worker(worker("running", runAttempt = 3)).retry)
        // A first-attempt blocked worker has not retried anything yet; `RETRY ×1` would be false.
        assertNull(RowContent.worker(worker("blocked", runAttempt = 1)).retry)
        assertEquals("◦ blocked", RowContent.worker(worker("blocked", runAttempt = 1)).state.text)
    }

    @Test fun `the surfaced tag is the first one that is not the worker class`() {
        assertEquals(
            "nightly",
            RowContent.workerTag(WorkerEvent("SyncWorker", "running", tags = listOf("com.app.SyncWorker", "nightly"))),
        )
        assertNull(RowContent.workerTag(WorkerEvent("SyncWorker", "running", tags = listOf("com.app.SyncWorker"))))
        assertEquals(
            "nightly",
            RowContent.workerTag(
                WorkerEvent("SyncWorker", "running", tags = listOf("androidx.work.impl.foreground", "nightly"))
            ),
        )
        assertNull(RowContent.workerTag(WorkerEvent("SyncWorker", "running", tags = listOf("syncworker"))))
        assertNull(RowContent.workerTag(WorkerEvent("SyncWorker", "running")))
    }

    @Test fun `the queue wait is read off the wire or not reported at all`() {
        val both = worker("running", enqueuedAt = 1_000, runStartedAt = 7_200).work
        assertEquals(6_200, RowContent.workerQueueMillis(both))
        // Either instant missing means the transition was never observed — not that it took zero.
        assertNull(RowContent.workerQueueMillis(worker("running", enqueuedAt = 1_000).work))
        assertNull(RowContent.workerQueueMillis(worker("running", runStartedAt = 7_200).work))
        assertNull(RowContent.workerQueueMillis(worker("running").work))
        // A device clock corrected between the two reads is unknown, never a negative duration.
        assertNull(RowContent.workerQueueMillis(worker("running", enqueuedAt = 7_200, runStartedAt = 1_000).work))
        assertEquals(0, RowContent.workerQueueMillis(worker("running", enqueuedAt = 500, runStartedAt = 500).work))
    }

    @Test fun `the worker fact cell prints the measured queue wait and nothing else`() {
        // §6's own sample, now that the device reports both ends of the wait.
        assertEquals(
            "queued 6.2s",
            RowContent.workerFact(worker("running", enqueuedAt = 1_000, runStartedAt = 7_200).work),
        )
        assertEquals(
            "queued 6.2s",
            RowContent.workerFact(worker("succeeded", enqueuedAt = 1_000, runStartedAt = 7_200).work),
        )
        // A row still *in* the queue has an open wait; a finished-looking number would be a lie.
        assertEquals(
            "",
            RowContent.workerFact(worker("enqueued", enqueuedAt = 1_000, runStartedAt = 7_200).work),
        )
        assertEquals(
            "",
            RowContent.workerFact(worker("blocked", enqueuedAt = 1_000, runStartedAt = 7_200).work),
        )
        // Pre-1.7.2 payload: exactly the old blank cell, gated on the data and never on a version.
        assertEquals("", RowContent.workerFact(worker("running").work))
        assertEquals("", RowContent.workerFact(worker("succeeded").work))
        // Attached mid-run: the start is known but the wait was never seen, so it stays unsaid.
        assertEquals("", RowContent.workerFact(worker("succeeded", runStartedAt = 7_200).work))
        // Replayed wins over any timing — such a row carries none anyway.
        assertEquals("replayed", RowContent.workerFact(worker("succeeded", replayed = true).work))
        assertEquals(
            "replayed",
            RowContent.workerFact(
                worker("succeeded", replayed = true, enqueuedAt = 1_000, runStartedAt = 7_200).work
            ),
        )
    }

    @Test fun `the worker time cell reports the run, not the queue plus the run`() {
        // §6 asked for run duration: 1_000 → 7_200 queued, 7_200 → 7_640 run. The cell shows 440.
        assertEquals(
            RowContent.TimeCell.Duration(440),
            RowContent.workerTime(
                worker("succeeded", at = 1_000, endedAt = 7_640, enqueuedAt = 1_000, runStartedAt = 7_200)
            ),
        )
        // Same fixture without the instants — the old queue+run span, byte for byte. This pairing
        // is the regression guard for the compat gate.
        assertEquals(
            RowContent.TimeCell.Duration(6_640),
            RowContent.workerTime(worker("succeeded", at = 1_000, endedAt = 7_640)),
        )
        // A run that closed on the same millisecond it started is not a measurement of zero — the
        // whole-span fallback answers instead of a `0ms` that would read as an instant worker.
        assertEquals(
            RowContent.TimeCell.Duration(430),
            RowContent.workerTime(
                worker("succeeded", at = 1_000, endedAt = 1_430, enqueuedAt = 1_000, runStartedAt = 1_430)
            ),
        )
        assertEquals(
            RowContent.TimeCell.Timestamp(1_000),
            RowContent.workerTime(worker("enqueued", at = 1_000, enqueuedAt = 1_000)),
        )
        // A terminal state with no closed span still has to say something honest.
        assertEquals(
            RowContent.TimeCell.Timestamp(1_000),
            RowContent.workerTime(worker("cancelled", at = 1_000)),
        )
        // Cancelled before it ever ran: no run start, so the fallback span is all there is.
        assertEquals(
            RowContent.TimeCell.Duration(400),
            RowContent.workerTime(worker("cancelled", at = 1_000, endedAt = 1_400, enqueuedAt = 1_000)),
        )
    }

    @Test fun `a running worker counts up from its run start when the device reported one`() {
        assertEquals(
            RowContent.TimeCell.LiveCountUp(6_200),
            RowContent.workerTime(worker("running", at = 1_000, enqueuedAt = 1_000, runStartedAt = 7_200)),
        )
        // No run start: an un-offset count-up, which is what the painter did before 1.7.2.
        assertEquals(RowContent.TimeCell.LiveCountUp(0), RowContent.workerTime(worker("running", at = 1_000)))
        // A run start earlier than the row's own start (a corrected device clock) cannot mean a
        // negative offset — the painter would subtract time the count-up never had.
        assertEquals(
            RowContent.TimeCell.LiveCountUp(0),
            RowContent.workerTime(worker("running", at = 5_000, runStartedAt = 1_000)),
        )
    }

    @Test fun `the row's durations all read in one format`() {
        assertEquals("0ms", RowContent.shortDuration(0))
        assertEquals("40ms", RowContent.shortDuration(40))
        assertEquals("6.2s", RowContent.shortDuration(6_200))
        assertEquals("4m 0s", RowContent.shortDuration(240_000))
        // A periodic worker's queue wait is exactly where hours turn up; "180m 0s" is not an answer.
        assertEquals("3h 0m", RowContent.shortDuration(10_800_000))
    }

    @Test fun `the worker card breaks the span down only when the device measured it`() {
        val split = kv(worker("succeeded", at = 1_000, endedAt = 7_640, enqueuedAt = 1_000, runStartedAt = 7_200), "Timing")!!
        assertEquals("6.2s", split["queued"])
        assertEquals("440ms", split["ran"])
        assertEquals("6.6s", split["total"], "the whole span stays visible beside its parts")
        // The old blanket disclaimer is now false — the time column no longer includes the queue.
        assertNull(kv(worker("succeeded", at = 1_000, endedAt = 7_640, enqueuedAt = 1_000, runStartedAt = 7_200), "Request")!!["timing"])

        // Pre-1.7.2 capture: no breakdown to give, and the disclaimer stands verbatim so an old
        // capture still explains its own number.
        val old = worker("succeeded", at = 1_000, endedAt = 7_640)
        assertNull(kv(old, "Timing"))
        assertEquals("includes queue time (from WorkInfo state changes)", kv(old, "Request")!!["timing"])
    }

    @Test fun `the worker card says which attempt it is describing, and what it never saw`() {
        // A retry's numbers are the backoff and the attempt after it — not the original enqueue.
        val retried = kv(
            worker("succeeded", runAttempt = 3, at = 1_000, endedAt = 7_640, enqueuedAt = 1_000, runStartedAt = 7_200),
            "Timing",
        )!!
        assertTrue(retried["note"]!!.contains("attempt 3"))

        // Attached mid-flight: the run is known, the wait never was — so it is absent and said so.
        val partial = kv(worker("running", at = 1_000, runStartedAt = 1_400), "Timing")!!
        assertNull(partial["queued"])
        assertEquals("still running", partial["ran"])
        assertTrue(partial["note"]!!.contains("not observed"))
    }

    /** A worker card's named KV section, flattened to strings — null when it has no such section. */
    private fun kv(event: LogEvent.Worker, label: String): Map<String, String>? =
        KindPresenter.present(event)?.sections?.firstOrNull { it.label == label }
            ?.body?.jsonObject?.mapValues { (_, v) -> v.jsonPrimitive.content }

    // ---- db -----------------------------------------------------------------------------------

    @Test fun `the verb tag and table come from the parser, not from the row text`() {
        val row = RowContent.db(DbQuery(sql = "UPDATE OR ABORT `battery_saver_info` SET x = ?"))
        assertEquals("UPDATE", row.verb)
        assertEquals("battery_saver_info", row.table)
        assertEquals(RowContent.Weight.BOLD, row.weight)
        assertTrue(row.sql.startsWith("UPDATE OR ABORT"))
    }

    @Test fun `reads stay light and everything else carries weight`() {
        assertEquals(RowContent.Weight.REGULAR, RowContent.db(DbQuery(sql = "SELECT * FROM users")).weight)
        assertEquals(RowContent.Weight.BOLD, RowContent.db(DbQuery(sql = "DELETE FROM users")).weight)
        assertEquals(RowContent.Weight.BOLD, RowContent.db(DbQuery(sql = "gibberish")).weight)
    }

    @Test fun `verb tags cover the set section 6 names`() {
        assertEquals("SELECT", RowContent.db(DbQuery(sql = "SELECT 1 FROM x")).verb)
        assertEquals("INSERT", RowContent.db(DbQuery(sql = "INSERT INTO x (a) VALUES (?)")).verb)
        assertEquals("DELETE", RowContent.db(DbQuery(sql = "DELETE FROM x")).verb)
        assertEquals("TXN", RowContent.db(DbQuery(sql = "TRANSACTION SUCCESSFUL")).verb)
        assertEquals("PRAGMA", RowContent.db(DbQuery(sql = "PRAGMA journal_mode = WAL")).verb)
        assertEquals("CREATE", RowContent.db(DbQuery(sql = "CREATE TABLE x (id INTEGER)")).verb)
        assertEquals("SQL", RowContent.db(DbQuery(sql = "")).verb)
        // A non-SQL store supplies its own operation, and it still gets a tag.
        assertEquals("FETCH", RowContent.db(DbQuery(sql = "key", operation = "fetch")).verb)
    }

    @Test fun `the db fact is rows when reported and the database otherwise`() {
        assertEquals("3 rows", RowContent.dbFact(DbQuery(sql = "x", rows = 3)))
        assertEquals("1 row", RowContent.dbFact(DbQuery(sql = "x", rows = 1)))
        assertEquals("0 rows", RowContent.dbFact(DbQuery(sql = "x", rows = 0)))
        assertEquals("app.db", RowContent.dbFact(DbQuery(sql = "x", database = "app.db")))
        assertEquals("", RowContent.dbFact(DbQuery(sql = "x")))
    }

    @Test fun `an unreadable statement carries the row alone rather than naming a wrong table`() {
        val row = RowContent.db(DbQuery(sql = "not sql at all"))
        assertNull(row.table)
        assertEquals("not sql at all", row.sql)
    }

    // ---- analytics ------------------------------------------------------------------------------

    @Test fun `an analytics row never repeats its own kind as a subtitle`() {
        // With a screen: the screen moves to the fact column, so the subtitle slot is free.
        assertNull(RowContent.rowSubtitle(analytics("checkout_viewed", "NavDrawerFragment")))
        // Without one: the ANALYTICS badge must not fall through into the subtitle slot.
        assertNull(RowContent.rowSubtitle(analytics("checkout_viewed", null)))
    }

    @Test fun `the analytics screen lands in the fact column and nowhere else`() {
        assertEquals("NavDrawerFragment", RowContent.rowFact(analytics("checkout_viewed", "NavDrawerFragment")))
        assertEquals("", RowContent.rowFact(analytics("checkout_viewed", null)))
    }

    @Test fun `the wire subtitle is untouched - MCP and search still read the screen there`() {
        val event = analytics("checkout_viewed", "NavDrawerFragment")
        assertEquals("NavDrawerFragment", KindPresenter.present(event)?.subtitle)
    }

    @Test fun `the kind echo rule matches the kind and its short label, and nothing else`() {
        val event = analytics("checkout_viewed", null)
        assertTrue(KindPresenter.isKindEcho(Badge("ANALYTICS"), event))
        assertTrue(KindPresenter.isKindEcho(Badge("ANLY"), event))
        assertFalse(KindPresenter.isKindEcho(Badge("FIREBASE"), event))
        assertFalse(KindPresenter.isKindEcho(Badge("3 rows"), event))
    }

    @Test fun `a filtered badge list is what the fact column indexes into`() {
        // The provider used to be shifted out of the fact cell by the kind-echo badge ahead of it.
        val badges = KindPresenter.rowBadges(analytics("checkout_viewed", null))
        assertEquals(listOf("FIREBASE"), badges.map { it.text })
    }

    // ---- other kinds keep their subtitles --------------------------------------------------------

    @Test fun `non-analytics kinds keep their subtitles verbatim`() {
        val db = dbEvent(DbQuery(sql = "SELECT * FROM users WHERE id = ?"))
        assertEquals("SELECT * FROM users WHERE id = ?", RowContent.rowSubtitle(db))
        assertEquals("users", RowContent.generic(db).title)

        val work = worker("running", worker = "SyncWorker")
        assertEquals("running", RowContent.rowSubtitle(work))

        val update = ConfigUpdate(totalKeys = 4, changes = listOf(ConfigChange("flag", "on", previous = "off")))
        val config = LogEvent.Config(
            update,
            Envelope(kind = Envelope.KIND_CONFIG, id = "c1", at = 1, payload = json.encodeToJsonElement(update)),
        )
        assertEquals("off → on", RowContent.rowSubtitle(config))
    }

    @Test fun `a generic row falls back to its first non-echo badge when it has no subtitle`() {
        val ev = GenericEvent(title = "cache warmed", badges = listOf(Badge("APP"), Badge("hit")))
        val event = LogEvent.Generic(
            ev,
            Envelope(kind = "cache", id = "g1", at = 1, payload = json.encodeToJsonElement(ev)),
        )
        assertEquals("hit", RowContent.rowSubtitle(event))
    }

    // ---- geometry -------------------------------------------------------------------------------

    @Test fun `only analytics widens the fact column`() {
        assertEquals(150, RowGeometry.fact(Envelope.KIND_ANALYTICS))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_DB))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_WORKER))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_CONFIG))
        assertEquals(120, RowGeometry.fact(Envelope.KIND_EVENT))
    }
}
