package io.github.siddharthjaswal.logpose.presentation

import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.JsonNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The whole-filter predicate and the measured loosener — moved from the plugin's panel so the Mac
 * app's filtered-to-nothing state offers the same button the tool window does.
 */
class FilterRelaxationsTest {

    private fun http(id: String, code: Int, path: String = "/a") = LogEvent.Http(
        Transaction(id = id, startedAtMillis = 1, request = Request("GET", "https://h$path", path = path), response = Response(code)),
        Envelope(kind = Envelope.KIND_HTTP, id = id, at = 1, endedAt = 2, payload = JsonNull),
    )

    private fun db(id: String) = LogEvent.Db(
        DbQuery("SELECT * FROM orders"),
        Envelope(kind = Envelope.KIND_DB, id = id, at = 1, endedAt = 2, payload = JsonNull),
    )

    private val all = listOf(http("h1", 200), http("h2", 200), http("h3", 404), db("d1"))

    private fun passes(dups: Set<String> = emptySet(), corr: Set<String>? = null) =
        { e: LogEvent, s: FilterState, withCorrelation: Boolean ->
            FilterPresentation.passes(e, s, { it in dups }, corr?.takeIf { withCorrelation }?.let { c -> { ev: LogEvent -> ev.id in c } })
        }

    @Test fun `passes composes state, duplicates and correlation`() {
        val p = passes(dups = setOf("h2"), corr = setOf("h1", "h2"))
        assertTrue(p(all[1], FilterState(duplicatesOnly = true), true))
        assertFalse(p(all[0], FilterState(duplicatesOnly = true), true))
        assertFalse(p(all[2], FilterState(), true))   // not in the correlation group
        assertTrue(p(all[2], FilterState(), false))
    }

    @Test fun `each active narrowing is measured by dropping it alone`() {
        val state = FilterState(statusClasses = setOf(5), urlQuery = "zzz")
        val r = FilterPresentation.relaxations(all, state, null, passes())
        assertEquals(listOf(FilterPresentation.FilterId.STATUS, FilterPresentation.FilterId.SEARCH), r.map { it.id })
        assertEquals(listOf(0, 0), r.map { it.wouldShow })
    }

    @Test fun `correlation is relaxed by switching its predicate off`() {
        val r = FilterPresentation.relaxations(all, FilterState(), "order_id 42", passes(corr = emptySet()))
        val c = r.first { it.id == FilterPresentation.FilterId.CORRELATION }
        assertEquals(3, c.wouldShow)
        assertEquals("Clear order_id", c.label)
    }

    @Test fun `db opt-in is offered only when granting db brings rows back`() {
        val dbOnly = listOf(db("d1"), db("d2"))
        val r = FilterPresentation.relaxations(dbOnly, FilterState(), null, passes())
        assertEquals(listOf(FilterPresentation.FilterId.DB_OPT_IN), r.map { it.id })
        assertEquals(2, r.single().wouldShow)
        // A status filter hides db anyway, so granting it shows nothing and it is not offered.
        val withStatus = FilterPresentation.relaxations(dbOnly, FilterState(statusClasses = setOf(2)), null, passes())
        assertEquals(listOf(FilterPresentation.FilterId.STATUS), withStatus.map { it.id })
    }
}
