package io.github.siddharthjaswal.logpose.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What "Find by value…" says back, decided without a dialog (PRD §4.2.1).
 *
 * The three rules the PRD is explicit about: state the count *before* committing so a typo reads
 * as a typo; label a bare value with the key that holds it; and say "too short to match safely"
 * out loud rather than showing an empty result.
 */
class FindByValueTest {

    private fun parse(input: String) = Correlation.parseFindQuery(input)

    private fun preview(
        input: String,
        matches: Int = 0,
        keyLabel: String? = null,
    ) = FindByValue.preview(parse(input), { matches }, { keyLabel })

    // ---- nothing typed yet -------------------------------------------------------------------

    @Test fun `an empty box says nothing and offers nothing`() {
        val preview = preview("")
        assertEquals("", preview.message)
        assertNull(preview.grouping)
        assertEquals(FindByValue.Tone.NEUTRAL, preview.tone)
    }

    @Test fun `whitespace and quotes are not input`() {
        assertNull(preview("   ").grouping)
        assertNull(preview("\"\"").grouping)
    }

    // ---- the short-value guard ---------------------------------------------------------------

    @Test fun `a short value is refused out loud, not silently`() {
        val preview = preview("210", matches = 99)
        assertNull(preview.grouping)
        assertEquals(FindByValue.Tone.PROBLEM, preview.tone)
        assertTrue(preview.message.contains("too short to match safely"), preview.message)
        assertTrue(preview.message.contains("\"210\""), preview.message)
        assertTrue(preview.message.contains("4 characters"), preview.message)
    }

    @Test fun `a short value names the key whose opt-in would allow it`() {
        val preview = preview("pin=210")
        assertTrue(preview.message.contains("short values"), preview.message)
        assertTrue(preview.message.contains("pin"), preview.message)
    }

    @Test fun `nothing is counted for a query that cannot match`() {
        var counted = false
        val preview = FindByValue.preview(parse("210"), { counted = true; 7 }, { null })
        assertFalse(counted, "a refused value must not scan the capture")
        assertNull(preview.grouping)
    }

    // ---- a value that matches nothing --------------------------------------------------------

    @Test fun `a typo reads as a typo, not as an empty screen`() {
        val preview = preview("21053954", matches = 0)
        assertEquals("no events carry that value", preview.message)
        assertNull(preview.grouping)
        assertEquals(FindByValue.Tone.PROBLEM, preview.tone)
    }

    // ---- a value that matches ------------------------------------------------------------------

    @Test fun `the count is stated before committing`() {
        val preview = preview("21053953", matches = 7, keyLabel = "order_id")
        assertEquals("7 events  ·  order_id 21053953", preview.message)
        assertEquals(7, preview.matches)
        assertEquals(FindByValue.Tone.READY, preview.tone)
        assertEquals(Grouping(Grouping.Kind.KEY, "order_id", "21053953"), preview.grouping)
    }

    @Test fun `one event is one event`() {
        assertTrue(preview("21053953", matches = 1).message.startsWith("1 event  ·"))
    }

    @Test fun `a bare value no key claims reads as a value`() {
        val preview = preview("21053953", matches = 5, keyLabel = null)
        assertEquals("5 events  ·  value 21053953", preview.message)
        assertEquals(Grouping(Grouping.Kind.VALUE, null, "21053953"), preview.grouping)
    }

    @Test fun `a typed key wins over the one the capture would have guessed`() {
        var asked = false
        val preview = FindByValue.preview(
            parse("trip_id=21053953"),
            { 3 },
            { asked = true; "order_id" },
        )
        assertFalse(asked, "an explicit key=value pair needs no label lookup")
        assertEquals(Grouping(Grouping.Kind.KEY, "trip_id", "21053953"), preview.grouping)
    }

    @Test fun `pasted JSON punctuation is parsing's problem, not the preview's`() {
        // parseFindQuery owns trimming and unquoting; the preview simply reads what it produced.
        val preview = preview("  \"order_id=21053953\"  ", matches = 2)
        assertEquals(Grouping(Grouping.Kind.KEY, "order_id", "21053953"), preview.grouping)
    }

    @Test fun `the grouping is exactly what the count was taken for`() {
        var counted: Grouping? = null
        val preview = FindByValue.preview(parse("21053953"), { counted = it; 4 }, { "order_id" })
        assertEquals(counted, preview.grouping)
    }
}
