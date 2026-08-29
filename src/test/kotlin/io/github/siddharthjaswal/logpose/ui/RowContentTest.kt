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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The per-kind row rules from §6, pinned away from Swing.
 *
 * Two of these assertions are really honesty tests rather than layout tests: a worker's fact cell
 * must stay **blank** rather than print a queue wait the wire cannot support, and an analytics
 * event's wire subtitle must survive unchanged, because MCP and search read it.
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
    ): LogEvent.Worker {
        val work = WorkerEvent(
            worker = worker, state = state, workId = "w-1", runAttempt = runAttempt,
            tags = tags, replayedAtAttach = replayed,
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

    @Test fun `the worker fact cell never fabricates a queue wait`() {
        // §6 asks for `queued 6.2s`; the wire does not carry the enqueued-to-running instant, so
        // the honest answer is nothing at all.
        assertEquals("", RowContent.workerFact(worker("running").work))
        assertEquals("", RowContent.workerFact(worker("succeeded").work))
        assertEquals("replayed", RowContent.workerFact(worker("succeeded", replayed = true).work))
    }

    @Test fun `the worker time cell counts up while running and reports a span when terminal`() {
        assertEquals(RowContent.TimeCell.LiveCountUp, RowContent.workerTime(worker("running")))
        assertEquals(
            RowContent.TimeCell.Duration(430),
            RowContent.workerTime(worker("succeeded", at = 1_000, endedAt = 1_430)),
        )
        assertEquals(
            RowContent.TimeCell.Timestamp(1_000),
            RowContent.workerTime(worker("enqueued", at = 1_000)),
        )
        // A terminal state with no closed span still has to say something honest.
        assertEquals(
            RowContent.TimeCell.Timestamp(1_000),
            RowContent.workerTime(worker("cancelled", at = 1_000)),
        )
    }

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
