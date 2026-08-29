package io.github.siddharthjaswal.logpose.ui

import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private typealias Id = FilterPresentation.FilterId

/**
 * What the filter bar *says*, pinned away from Swing.
 *
 * The redesign hides five filters behind one button, which is only honest if the bar can state
 * what it is hiding — so these are the assertions that keep the statement true: the badge counts
 * groups rather than clicks, every hidden filter echoes as a chip (and the visible ones never do),
 * the two invisible mode switches explain themselves in the exact conditions that make them
 * invisible, and the "nothing matches" state blames the filter that measurably caused it rather
 * than the first one it finds.
 */
class FilterPresentationTest {

    // ---- badge ---------------------------------------------------------------------------------

    @Test
    fun `a group of chips counts once however many of it is selected`() {
        val state = FilterState(statusClasses = setOf(4, 5), methods = setOf("POST", "PUT"))
        // Not 4. The badge answers "how many filters are hiding rows", and the popover holds four
        // status chips — a "4" beside it would be counting the wrong noun.
        assertEquals(2, FilterPresentation.badgeCount(state, correlationActive = false))
    }

    @Test
    fun `every hidden filter adds one, and the visible ones add none`() {
        val all = FilterState(
            urlQuery = "rider",
            methods = setOf("GET"),
            statusClasses = setOf(2),
            hideNoise = true,
            types = setOf(EventType.NET, EventType.DB),
            duplicatesOnly = true,
        )
        // Search and the TYPE chips are on the permanent row, in plain sight; a badge for them
        // would be the bar announcing something the user can already read.
        assertEquals(5, FilterPresentation.badgeCount(all, correlationActive = true))
        assertEquals(0, FilterPresentation.badgeCount(FilterState(urlQuery = "x", types = setOf(EventType.DB)), false))
    }

    // ---- echo chips ----------------------------------------------------------------------------

    @Test
    fun `every hidden filter echoes as a chip, and the search box never does`() {
        val state = FilterState(
            urlQuery = "rider",
            methods = setOf("POST"),
            statusClasses = setOf(4, 5),
            hideNoise = true,
            duplicatesOnly = true,
            types = setOf(EventType.NET),
        )
        val chips = FilterPresentation.echoChips(state, correlationLabel = "order_id 21053953")
        assertEquals(
            listOf(Id.STATUS, Id.METHOD, Id.CORRELATION, Id.HIDE_NOISE, Id.DUPES),
            chips.map { it.id },
        )
        assertEquals(
            listOf("status 4xx–5xx", "method POST", "order_id 21053953", "hide noise", "dupes"),
            chips.map { it.label },
        )
    }

    @Test
    fun `no hidden filter, no echo row`() {
        assertTrue(FilterPresentation.echoChips(FilterState(urlQuery = "rider"), null).isEmpty())
        assertTrue(FilterPresentation.echoChips(FilterState(types = setOf(EventType.DB)), null).isEmpty())
    }

    @Test
    fun `narrow chips drop the noun and keep the value`() {
        val state = FilterState(statusClasses = setOf(4, 5), methods = setOf("POST", "GET"))
        val chips = FilterPresentation.echoChips(state, null, narrow = true)
        // `4xx–5xx` still says what it filters; `status` alone would not.
        assertEquals(listOf("4xx–5xx", "GET/POST"), chips.map { it.label })
    }

    @Test
    fun `a long correlation value is trimmed harder on a narrow panel`() {
        val label = "order_id 21053953-0000-4444-8888-aaaaaaaaaaaa"
        val wide = FilterPresentation.echoChips(FilterState(), label).single().label
        val narrow = FilterPresentation.echoChips(FilterState(), label, narrow = true).single().label
        assertEquals(34, wide.length)
        assertEquals(18, narrow.length)
        assertTrue(wide.endsWith("…") && narrow.endsWith("…"))
    }

    @Test
    fun `contiguous status classes collapse into one range`() {
        assertEquals("4xx–5xx", FilterPresentation.statusLabel(setOf(5, 4)))
        assertEquals("2xx", FilterPresentation.statusLabel(setOf(2)))
        assertEquals("2xx, 4xx–5xx", FilterPresentation.statusLabel(setOf(5, 2, 4)))
        assertEquals("2xx–5xx", FilterPresentation.statusLabel(setOf(2, 3, 4, 5)))
        assertEquals("", FilterPresentation.statusLabel(emptySet()))
    }

    @Test
    fun `verbs read in lifecycle order, never in click order`() {
        assertEquals("GET/POST/DELETE", FilterPresentation.methodLabel(linkedSetOf("DELETE", "POST", "GET")))
    }

    // ---- mode-switch explainers ------------------------------------------------------------------

    @Test
    fun `a status filter says out loud that it is showing HTTP only`() {
        val e = FilterPresentation.explainer(FilterState(statusClasses = setOf(4)), wouldMatchHiddenDb = false)
        assertNotNull(e)
        assertEquals("status filter → showing HTTP only ·", e!!.text)
        assertEquals("show all kinds", e.link)
        assertEquals(FilterPresentation.Explainer.Action.SHOW_ALL_KINDS, e.action)
    }

    @Test
    fun `a method filter names itself rather than borrowing the status wording`() {
        val e = FilterPresentation.explainer(FilterState(methods = setOf("POST")), false)
        assertEquals("method filter → showing HTTP only ·", e?.text)
    }

    @Test
    fun `the narrow explainer keeps the meaning and loses the words`() {
        val e = FilterPresentation.explainer(FilterState(statusClasses = setOf(4)), false, narrow = true)
        assertEquals("HTTP only ·", e?.text)
        assertEquals("all kinds", e?.link)
    }

    @Test
    fun `db opt-in explains itself only when a search would have matched db events`() {
        assertNull(FilterPresentation.explainer(FilterState(urlQuery = "riders"), wouldMatchHiddenDb = false))
        val e = FilterPresentation.explainer(FilterState(urlQuery = "riders"), wouldMatchHiddenDb = true)
        assertEquals("db hidden by default ·", e?.text)
        assertEquals(FilterPresentation.Explainer.Action.SHOW_DB, e?.action)
    }

    @Test
    fun `the HTTP-only switch wins over the db one, because it causes it`() {
        // A status filter already excludes every db event, so "show db" under it would be a link
        // that changes nothing.
        val e = FilterPresentation.explainer(
            FilterState(urlQuery = "riders", statusClasses = setOf(4)), wouldMatchHiddenDb = true,
        )
        assertEquals(FilterPresentation.Explainer.Action.SHOW_ALL_KINDS, e?.action)
    }

    @Test
    fun `no filter, no explainer`() {
        assertNull(FilterPresentation.explainer(FilterState(), false))
    }

    // ---- the hidden-db probe ---------------------------------------------------------------------

    @Test
    fun `a search that only db events answer is detected`() {
        val events = listOf(db("SELECT * FROM riders WHERE id = ?", "riders"))
        assertTrue(FilterPresentation.wouldMatchHiddenDb(FilterState(urlQuery = "riders"), events))
    }

    @Test
    fun `the probe stays quiet when db is already asked for, or nothing would match`() {
        val events = listOf(db("SELECT * FROM riders", "riders"))
        assertFalse(
            FilterPresentation.wouldMatchHiddenDb(
                FilterState(urlQuery = "riders", types = setOf(EventType.DB)), events,
            )
        )
        assertFalse(FilterPresentation.wouldMatchHiddenDb(FilterState(urlQuery = "orders"), events))
        assertFalse(FilterPresentation.wouldMatchHiddenDb(FilterState(urlQuery = "  "), events))
    }

    @Test
    fun `the probe answers with the same rules the chip would apply`() {
        // A status filter narrows to HTTP, so selecting DB would still show nothing — and the
        // explainer must not promise otherwise.
        val events = listOf(db("SELECT * FROM riders", "riders"))
        assertFalse(
            FilterPresentation.wouldMatchHiddenDb(
                FilterState(urlQuery = "riders", statusClasses = setOf(2)), events,
            )
        )
    }

    // ---- narrow layout ---------------------------------------------------------------------------

    @Test
    fun `the narrow threshold has hysteresis, so a drag across it cannot strobe`() {
        assertTrue(FilterPresentation.isNarrow(440, currentlyNarrow = false))
        assertFalse(FilterPresentation.isNarrow(441, currentlyNarrow = false))
        // Once narrow it stays narrow past the entry width — the compact layout fits in less space
        // than it needs to leave, and a single threshold would flip between two stable states.
        assertTrue(FilterPresentation.isNarrow(460, currentlyNarrow = true))
        assertFalse(FilterPresentation.isNarrow(470, currentlyNarrow = true))
        // The thresholds are logical px, so a 200% display scales them rather than the width.
        assertTrue(FilterPresentation.isNarrow(880, currentlyNarrow = false) { it * 2 })
    }

    // ---- filtered to nothing ---------------------------------------------------------------------

    @Test
    fun `the headline states both counts`() {
        assertEquals("218 events captured · 0 match the current filter", FilterPresentation.headline(218))
    }

    @Test
    fun `the loosener offered is the one that measurably brings back the most rows`() {
        val state = FilterState(statusClasses = setOf(4), methods = setOf("POST"))
        val empty = FilterPresentation.emptyState(
            total = 218,
            kinds = mapOf(Envelope.KIND_ANALYTICS to 120, Envelope.KIND_DB to 90, Envelope.KIND_HTTP to 8),
            state = state,
            relaxations = listOf(
                FilterPresentation.Relaxation(Id.STATUS, "Loosen status", 3),
                FilterPresentation.Relaxation(Id.METHOD, "Loosen method", 41),
            ),
        )
        assertEquals(Id.METHOD, empty.loosener?.id)
        assertEquals("Loosen method", empty.loosener?.label)
        // The sentence names the same mechanism the button does — one choice, stated twice.
        assertTrue(empty.explanation.contains("POST"), empty.explanation)
    }

    @Test
    fun `a loosening that would still show nothing is not offered at all`() {
        val empty = FilterPresentation.emptyState(
            total = 12,
            kinds = mapOf(Envelope.KIND_HTTP to 12),
            state = FilterState(statusClasses = setOf(4), urlQuery = "nope"),
            relaxations = listOf(
                FilterPresentation.Relaxation(Id.STATUS, "Loosen status", 0),
                FilterPresentation.Relaxation(Id.SEARCH, "Clear search", 0),
            ),
        )
        assertNull(empty.loosener)
        // Two filters are narrowing at once; blaming one of them would be a guess.
        assertTrue(empty.explanation.startsWith("Several filters"), empty.explanation)
    }

    @Test
    fun `the mechanism sentence is built from the capture, not from a template`() {
        val empty = FilterPresentation.emptyState(
            total = 218,
            kinds = mapOf(Envelope.KIND_ANALYTICS to 120, Envelope.KIND_DB to 90),
            state = FilterState(statusClasses = setOf(4, 5)),
            relaxations = listOf(FilterPresentation.Relaxation(Id.STATUS, "Loosen status", 218)),
        )
        assertEquals(
            "The status filter limits results to HTTP; your capture is mostly analytics and db.",
            empty.explanation,
        )
    }

    @Test
    fun `a capture that does have HTTP is told the truth about its statuses instead`() {
        val empty = FilterPresentation.emptyState(
            total = 40,
            kinds = mapOf(Envelope.KIND_HTTP to 40),
            state = FilterState(statusClasses = setOf(5)),
            relaxations = listOf(FilterPresentation.Relaxation(Id.STATUS, "Loosen status", 40)),
        )
        assertEquals("No HTTP call in this capture came back 5xx.", empty.explanation)
    }

    @Test
    fun `a search that only db events answer says so`() {
        val empty = FilterPresentation.emptyState(
            total = 75,
            kinds = mapOf(Envelope.KIND_DB to 75),
            state = FilterState(urlQuery = "riders"),
            wouldMatchHiddenDb = true,
            relaxations = listOf(FilterPresentation.Relaxation(Id.SEARCH, "Clear search", 75)),
        )
        assertTrue(empty.explanation.contains("db stays hidden"), empty.explanation)
    }

    @Test
    fun `each filter has a loosening button that names what it drops`() {
        assertEquals("Loosen status", FilterPresentation.looseningLabel(Id.STATUS))
        assertEquals("Show all kinds", FilterPresentation.looseningLabel(Id.TYPES))
        assertEquals("Show noise", FilterPresentation.looseningLabel(Id.HIDE_NOISE))
        assertEquals("Clear order_id", FilterPresentation.looseningLabel(Id.CORRELATION, "order_id 21053953"))
    }

    // ---- fixtures ---------------------------------------------------------------------------------

    private fun db(sql: String, table: String? = null) = LogEvent.Db(
        DbQuery(sql = sql, table = table),
        Envelope(kind = Envelope.KIND_DB, id = "db-1", at = 1_000, payload = JsonObject(emptyMap())),
    )
}
