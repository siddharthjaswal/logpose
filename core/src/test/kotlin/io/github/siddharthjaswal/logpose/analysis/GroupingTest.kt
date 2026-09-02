package io.github.siddharthjaswal.logpose.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which grouping a row opens, and how it reads.
 *
 * The precedence is the feature's whole point: a business key correlates what tracing
 * structurally cannot — it spans traces, and it reaches events with no trace — so a row carrying
 * both must never open the weaker one from a single click.
 */
class GroupingTest {

    private val values = linkedMapOf("order_id" to "21053953", "trip_id" to "TRP-99881")

    @Test fun `keys come first, in configured order, then the trace`() {
        val groupings = Groupings.forEvent(values, "d107086f")
        assertEquals(
            listOf("order_id" to "21053953", "trip_id" to "TRP-99881", null to "d107086f"),
            groupings.map { it.key to it.value },
        )
        assertEquals(
            listOf(Grouping.Kind.KEY, Grouping.Kind.KEY, Grouping.Kind.TRACE),
            groupings.map { it.kind },
        )
    }

    @Test fun `one click opens the first configured key, not the trace`() {
        val best = Groupings.best(values, "d107086f")
        assertEquals(Grouping(Grouping.Kind.KEY, "order_id", "21053953"), best)
    }

    @Test fun `the trace is the fallback, not the default`() {
        assertEquals(
            Grouping(Grouping.Kind.TRACE, null, "d107086f"),
            Groupings.best(emptyMap(), "d107086f"),
        )
    }

    @Test fun `a row with a key and no trace still groups`() {
        assertEquals(
            Grouping(Grouping.Kind.KEY, "order_id", "21053953"),
            Groupings.best(mapOf("order_id" to "21053953"), null),
        )
    }

    @Test fun `a row with neither offers nothing — no entry point beats an empty one`() {
        assertNull(Groupings.best(emptyMap(), null))
        assertTrue(Groupings.forEvent(emptyMap(), null).isEmpty())
    }

    @Test fun `a blank trace is no trace`() {
        assertTrue(Groupings.forEvent(emptyMap(), "   ").isEmpty())
        assertTrue(Groupings.forEvent(emptyMap(), "").isEmpty())
    }

    @Test fun `a blank value never becomes a grouping`() {
        assertTrue(Groupings.forEvent(mapOf("order_id" to " "), null).isEmpty())
    }

    // ---- pasted values ---------------------------------------------------------------------------

    @Test fun `a pasted value is labelled by the key that holds it`() {
        assertEquals(
            Grouping(Grouping.Kind.KEY, "order_id", "21053953"),
            Groupings.forValue("21053953", "order_id"),
        )
    }

    @Test fun `a pasted value no key claims stays a value`() {
        assertEquals(
            Grouping(Grouping.Kind.VALUE, null, "21053953"),
            Groupings.forValue("21053953", null),
        )
        assertEquals(Grouping.Kind.VALUE, Groupings.forValue("21053953", "  ").kind)
    }

    // ---- how it reads -----------------------------------------------------------------------------

    @Test fun `every grouping says what it is grouping by`() {
        assertEquals("order_id 21053953", Grouping(Grouping.Kind.KEY, "order_id", "21053953").shortLabel)
        assertEquals("value 21053953", Grouping(Grouping.Kind.VALUE, null, "21053953").shortLabel)
        // The label a trace used to lack: "Show waterfall d107086f" names a hash and nothing else.
        assertEquals("trace d107086f", Grouping(Grouping.Kind.TRACE, null, "d107086f").shortLabel)
    }

    @Test fun `a switcher tab is the name alone`() {
        assertEquals("order_id", Grouping(Grouping.Kind.KEY, "order_id", "21053953").tab)
        assertEquals("trace", Grouping(Grouping.Kind.TRACE, null, "d107086f").tab)
        assertEquals("value", Grouping(Grouping.Kind.VALUE, null, "21053953").tab)
    }

    @Test fun `isTrace separates the fallback from the rest`() {
        assertTrue(Grouping(Grouping.Kind.TRACE, null, "d1").isTrace)
        assertTrue(!Grouping(Grouping.Kind.KEY, "order_id", "2105").isTrace)
        assertTrue(!Grouping(Grouping.Kind.VALUE, null, "2105").isTrace)
    }
}
