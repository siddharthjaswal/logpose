package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Collapsing is the one presentation change that can *remove* something from view, so the bar is
 * higher than "looks tidy": every event that goes in must come back out inside exactly one row,
 * and anything that a reader would want to see standing alone — a failed poll, an overlapping
 * double-submit, a transaction that never closed — must never be folded away.
 */
class RowCollapseTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    // ---- fixtures ---------------------------------------------------------------------------

    private fun http(
        id: String,
        at: Long,
        method: String = "PUT",
        path: String = "/app/v3/79096/location/",
        code: Int? = 200,
        durationMillis: Long? = 100,
        error: String? = null,
        mocked: Boolean = false,
    ): LogEvent.Http {
        val tx = Transaction(
            id = id,
            startedAtMillis = at,
            request = Request(method = method, url = "https://api.example.com$path", path = path),
            response = code?.let { Response(code = it) },
            durationMillis = durationMillis,
            error = error,
            mocked = mocked,
        )
        return LogEvent.Http(
            tx,
            Envelope(
                kind = Envelope.KIND_HTTP,
                id = id,
                at = at,
                endedAt = durationMillis?.let { at + it },
                payload = json.encodeToJsonElement(tx),
            ),
        )
    }

    private fun db(
        id: String,
        at: Long,
        sql: String,
        database: String? = "app.db",
        error: String? = null,
        endedAt: Long? = null,
    ): LogEvent.Db {
        val query = DbQuery(sql = sql, database = database, error = error)
        return LogEvent.Db(
            query,
            Envelope(
                kind = Envelope.KIND_DB,
                id = id,
                at = at,
                endedAt = endedAt,
                payload = json.encodeToJsonElement(query),
            ),
        )
    }

    private fun other(id: String, at: Long): LogEvent.Generic {
        val ev = GenericEvent(title = "something else")
        return LogEvent.Generic(
            ev,
            Envelope(kind = Envelope.KIND_ANALYTICS, id = id, at = at, payload = json.encodeToJsonElement(ev)),
        )
    }

    private fun poll(count: Int, from: Long = 0, everyMillis: Long = 30_000, idPrefix: String = "p") =
        (0 until count).map { http("$idPrefix$it", from + it * everyMillis, durationMillis = 100L + it) }

    /** The mechanical proof that collapsing never drops an event. */
    private fun assertNothingLost(input: List<LogEvent>, rows: List<RowCollapse.Row>) {
        val seen = rows.flatMap { it.memberIds }
        assertEquals(input.size, seen.size, "every event belongs to exactly one row")
        assertEquals(input.map { it.id }.toSet(), seen.toSet())
    }

    // ---- net polling ------------------------------------------------------------------------

    @Test fun `a run of identical successes collapses to one row that leads with the latest`() {
        val events = poll(30)
        val rows = RowCollapse.collapse(events)

        assertEquals(1, rows.size)
        val group = rows.single() as RowCollapse.Row.Group
        assertEquals(RowCollapse.GroupKind.NET_POLL, group.groupKind)
        assertEquals(30, group.facts.count)
        // The anchor is the FIRST member — stable while the run grows, so selection and expansion
        // survive the 150ms rebuild. The lead is the LATEST, which is what §6 shows.
        assertEquals("p0", group.anchorId)
        assertEquals("p29", group.lead.id)
        assertEquals(events.last().timestampMillis, group.facts.latestAtMillis)
        // Lower-middle of 100..129 — a duration some call really took, not an average of none.
        assertEquals(114L, group.facts.medianDurationMillis)
        assertNothingLost(events, rows)
    }

    @Test fun `median is the lower middle for both odd and even runs`() {
        val odd = listOf(
            http("a", 0, durationMillis = 50), http("b", 1, durationMillis = 10),
            http("c", 2, durationMillis = 30),
        )
        assertEquals(30L, (RowCollapse.collapse(odd).single() as RowCollapse.Row.Group).facts.medianDurationMillis)

        val even = odd + http("d", 3, durationMillis = 40)
        assertEquals(30L, (RowCollapse.collapse(even).single() as RowCollapse.Row.Group).facts.medianDurationMillis)
    }

    @Test fun `a failed poll stands alone and splits the run around it`() {
        val events = poll(3, from = 0) +
            http("boom", 90_000, code = 404) +
            poll(3, from = 120_000, idPrefix = "q")
        val rows = RowCollapse.collapse(events)

        assertEquals(3, rows.size)
        assertTrue(rows[0] is RowCollapse.Row.Group)
        assertEquals("boom", (rows[1] as RowCollapse.Row.Single).lead.id)
        val second = rows[2] as RowCollapse.Row.Group
        // A break is not a count reset: the second run is its own group with its own anchor.
        assertEquals("q0", second.anchorId)
        assertNothingLost(events, rows)
    }

    @Test fun `an errored or still-pending call of the same endpoint breaks the run`() {
        val pending = poll(3) + http("open", 90_000, code = null, durationMillis = null) + poll(3, 120_000, idPrefix = "q")
        assertEquals(3, RowCollapse.collapse(pending).size)

        val errored = poll(3) + http("dead", 90_000, code = null, durationMillis = null, error = "timeout") +
            poll(3, 120_000, idPrefix = "q")
        assertEquals(3, RowCollapse.collapse(errored).size)
    }

    @Test fun `an overlapping double-submit is never folded away`() {
        val events = poll(5, idPrefix = "p")
        val marks = mapOf(
            "p3" to DuplicateDetector.Mark(2, DuplicateDetector.Severity.STRONG, "p2")
        )
        val rows = RowCollapse.collapse(events, duplicateMarks = marks)

        // p0..p2 fold; p3 stands alone; p4 is left over below the minimum run.
        assertEquals(3, rows.size)
        assertEquals("p3", (rows[1] as RowCollapse.Row.Single).lead.id)
        assertEquals("p4", (rows[2] as RowCollapse.Row.Single).lead.id)
        assertNothingLost(events, rows)
    }

    @Test fun `a weaker duplicate mark still folds`() {
        val events = poll(4)
        val marks = mapOf("p2" to DuplicateDetector.Mark(2, DuplicateDetector.Severity.MEDIUM, "p1"))
        assertEquals(1, RowCollapse.collapse(events, duplicateMarks = marks).size)
    }

    @Test fun `a long quiet gap ends a run even when method and path match`() {
        val events = poll(3, from = 0, everyMillis = 1_000) + poll(3, from = 10_000_000, idPrefix = "q")
        val rows = RowCollapse.collapse(events)
        assertEquals(2, rows.size)
        assertEquals("p0", rows[0].anchorId)
        assertEquals("q0", rows[1].anchorId)
    }

    @Test fun `intervening unrelated events neither break a run nor lose their place`() {
        val polls = poll(3, everyMillis = 1_000)
        val events = listOf(
            polls[0], other("x", 100), db("d", 200, "SELECT * FROM users"),
            polls[1], http("elsewhere", 300, path = "/other/"), polls[2],
        )
        val rows = RowCollapse.collapse(events)

        assertEquals(4, rows.size)
        assertTrue(rows[0] is RowCollapse.Row.Group)
        assertEquals(listOf("x", "d", "elsewhere"), rows.drop(1).map { it.lead.id })
        assertNothingLost(events, rows)
    }

    @Test fun `two identical calls are information, three are a wall`() {
        assertEquals(2, RowCollapse.collapse(poll(2)).size)
        assertEquals(1, RowCollapse.collapse(poll(3)).size)
    }

    @Test fun `differing method, path, mock flag or session never merge`() {
        val mixedMethod = poll(3) + poll(3, from = 5_000, idPrefix = "q").map { it }
        assertEquals(1, RowCollapse.collapse(mixedMethod).size, "same signature still merges")

        val methods = listOf(
            http("a", 0, method = "GET"), http("b", 1, method = "GET"), http("c", 2, method = "GET"),
            http("d", 3, method = "PUT"), http("e", 4, method = "PUT"), http("f", 5, method = "PUT"),
        )
        assertEquals(2, RowCollapse.collapse(methods).size)

        val paths = listOf(
            http("a", 0, path = "/one/"), http("b", 1, path = "/one/"), http("c", 2, path = "/one/"),
            http("d", 3, path = "/two/"), http("e", 4, path = "/two/"), http("f", 5, path = "/two/"),
        )
        assertEquals(2, RowCollapse.collapse(paths).size)

        // A LogPose-served response must never hide inside a wall of real ones.
        val mocks = poll(3) + poll(3, from = 5_000, idPrefix = "m").map {
            http(it.id, it.timestampMillis, mocked = true)
        }
        assertEquals(2, RowCollapse.collapse(mocks).size)

        // A run must not straddle an app restart: two sessions, two medians.
        val sessions = poll(6)
        assertEquals(
            2,
            RowCollapse.collapse(sessions, sessionOf = { id -> if (id.removePrefix("p").toInt() < 3) 0 else 1 }).size,
        )
    }

    @Test fun `a cache-busting query string does not defeat a match`() {
        // No path on the wire, so the URL carries it — and a poll's `?_=<ts>` differs every call.
        val events = (0 until 3).map { i ->
            val e = http("c$i", i * 1_000L, path = "")
            LogEvent.Http(
                e.tx.copy(request = e.tx.request.copy(url = "https://api.example.com/poll/?_=$i")),
                e.envelope,
            )
        }
        assertEquals(1, RowCollapse.collapse(events).size)
    }

    // ---- db transactions --------------------------------------------------------------------

    @Test fun `a transaction ceremony folds and its wrapped statements stay normal rows`() {
        val events = listOf(
            db("t0", 1_000, "BEGIN EXCLUSIVE"),
            db("s1", 1_010, "INSERT INTO orders (id) VALUES (?)"),
            db("s2", 1_020, "UPDATE OR ABORT `riders` SET status = ?"),
            db("t1", 1_030, "SELECT changes()"),
            db("t2", 1_040, "TRANSACTION SUCCESSFUL"),
            db("t3", 1_050, "END TRANSACTION", endedAt = 1_128),
        )
        val rows = RowCollapse.collapse(events)

        assertEquals(3, rows.size)
        val fold = rows[0] as RowCollapse.Row.Group
        assertEquals(RowCollapse.GroupKind.DB_TXN, fold.groupKind)
        assertEquals(listOf("t0", "t1", "t2", "t3"), fold.memberIds)
        assertEquals(2, fold.facts.statements)
        assertTrue(fold.facts.succeeded)
        assertFalse(fold.facts.failed)
        assertEquals(128L, fold.facts.totalDurationMillis)
        // The fold leads with BEGIN and sits at its position, so the wrapped rows follow it.
        assertEquals("t0", fold.lead.id)
        assertEquals(listOf("s1", "s2"), rows.drop(1).map { it.lead.id })
        assertNothingLost(events, rows)
    }

    @Test fun `interleaved events of other kinds do not break a fold`() {
        val events = listOf(
            db("t0", 1, "BEGIN"),
            other("a", 2),
            db("s1", 3, "INSERT INTO x (id) VALUES (?)"),
            http("h", 4),
            db("t1", 5, "COMMIT"),
        )
        val rows = RowCollapse.collapse(events)
        assertTrue(rows.first() is RowCollapse.Row.Group)
        assertEquals(listOf("t0", "t1"), rows.first().memberIds)
        assertNothingLost(events, rows)
    }

    @Test fun `a rollback folds but is flagged failed`() {
        val events = listOf(
            db("t0", 1, "BEGIN"), db("s1", 2, "DELETE FROM sessions"), db("t1", 3, "ROLLBACK"),
        )
        val fold = RowCollapse.collapse(events).first() as RowCollapse.Row.Group
        assertTrue(fold.facts.failed)
        assertFalse(fold.facts.succeeded)
    }

    @Test fun `an errored wrapped statement fails the fold`() {
        val events = listOf(
            db("t0", 1, "BEGIN"),
            db("s1", 2, "INSERT INTO x (id) VALUES (?)", error = "constraint violation"),
            db("t2", 3, "TRANSACTION SUCCESSFUL"),
            db("t3", 4, "END"),
        )
        val fold = RowCollapse.collapse(events).first() as RowCollapse.Row.Group
        assertTrue(fold.facts.failed)
        assertFalse(fold.facts.succeeded, "a success marker does not survive an error inside")
    }

    @Test fun `an unterminated or nested transaction never folds`() {
        val unterminated = listOf(db("t0", 1, "BEGIN"), db("s1", 2, "SELECT * FROM x"))
        assertTrue(RowCollapse.collapse(unterminated).all { it is RowCollapse.Row.Single })

        // Two threads on one unnamed connection are indistinguishable on the wire — so fold
        // nothing rather than merge two transactions into one wrong row.
        val nested = listOf(
            db("a0", 1, "BEGIN", database = null), db("s1", 2, "SELECT * FROM x", database = null),
            db("b0", 3, "BEGIN", database = null), db("s2", 4, "SELECT * FROM y", database = null),
            db("b1", 5, "END", database = null),
        )
        val rows = RowCollapse.collapse(nested)
        assertEquals(1, rows.count { it is RowCollapse.Row.Group })
        assertEquals(listOf("b0", "b1"), rows.first { it is RowCollapse.Row.Group }.memberIds)
        assertNothingLost(nested, rows)
    }

    @Test fun `transactions on two named databases fold independently`() {
        val events = listOf(
            db("a0", 1, "BEGIN", database = "one.db"),
            db("b0", 2, "BEGIN", database = "two.db"),
            db("as", 3, "INSERT INTO x (id) VALUES (?)", database = "one.db"),
            db("bs", 4, "INSERT INTO y (id) VALUES (?)", database = "two.db"),
            db("a1", 5, "END", database = "one.db"),
            db("b1", 6, "END", database = "two.db"),
        )
        val groups = RowCollapse.collapse(events).filterIsInstance<RowCollapse.Row.Group>()
        assertEquals(2, groups.size)
        assertEquals(listOf("a0", "a1"), groups[0].memberIds)
        assertEquals(listOf("b0", "b1"), groups[1].memberIds)
    }

    @Test fun `an empty transaction is left alone - folding it would shorten nothing`() {
        val events = listOf(db("t0", 1, "BEGIN"), db("t1", 2, "END"))
        assertTrue(RowCollapse.collapse(events).all { it is RowCollapse.Row.Single })
    }

    // ---- expansion, identity and edges --------------------------------------------------------

    @Test fun `an expanded group emits its members verbatim and re-collapses identically`() {
        val events = poll(5)
        val collapsed = RowCollapse.collapse(events)
        val key = collapsed.single().key

        val expanded = RowCollapse.collapse(events, expanded = setOf(key))
        assertEquals(5, expanded.size)
        assertTrue(expanded.all { it is RowCollapse.Row.Single })
        assertEquals(events.map { it.id }, expanded.map { it.lead.id })

        assertEquals(collapsed, RowCollapse.collapse(events, expanded = emptySet()))
    }

    @Test fun `a group key survives a new member arriving, an expanded one resolves to its single`() {
        val before = RowCollapse.collapse(poll(3))
        val after = RowCollapse.collapse(poll(4))
        assertEquals(before.single().key, after.single().key, "the anchor never moves as a run grows")

        // A previously-single row absorbed into a group is still reachable by its own id.
        val absorbed = RowCollapse.memberIndex(after)["p2"]
        assertEquals(after.single(), absorbed)

        val expanded = RowCollapse.collapse(poll(4), expanded = setOf(after.single().key))
        assertEquals("p2", RowCollapse.memberIndex(expanded)["p2"]?.lead?.id)
    }

    @Test fun `collapsing can be switched off entirely`() {
        val events = poll(5) + listOf(db("t0", 9_000, "BEGIN"), db("s", 9_001, "SELECT * FROM x"), db("t1", 9_002, "END"))
        val rows = RowCollapse.collapse(events, options = RowCollapse.Options(collapsePolls = false, foldTransactions = false))
        assertEquals(events.size, rows.size)
        assertTrue(rows.all { it is RowCollapse.Row.Single })
    }

    @Test fun `empty, single and uncollapsible input come back unchanged`() {
        assertTrue(RowCollapse.collapse(emptyList()).isEmpty())

        val one = listOf(http("a", 0))
        assertEquals(listOf("a"), RowCollapse.collapse(one).map { it.lead.id })

        val varied = (0 until 50).map { http("v$it", it * 10L, path = "/p$it/") }
        val rows = RowCollapse.collapse(varied)
        assertEquals(varied.map { it.id }, rows.map { it.lead.id })
        assertNothingLost(varied, rows)
    }

    @Test fun `a single row reports itself as its own member and is not a group`() {
        val row = RowCollapse.collapse(listOf(http("a", 0))).single()
        assertEquals("a", row.anchorId)
        assertEquals(listOf("a"), row.memberIds)
        assertEquals("a", row.key)
        assertFalse(row.isGroup)
        assertNull((row as? RowCollapse.Row.Group)?.facts)
    }
}
