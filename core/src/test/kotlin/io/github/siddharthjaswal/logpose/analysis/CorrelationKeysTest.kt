package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The stored vocabulary's rules, tested without a settings store or a dialog.
 *
 * Two of these matter more than the rest: a key set that survives a round trip through a settings
 * string (a corrupt one must cost the list, never the tool window), and the canonical-name rule,
 * which has to agree with [Correlation]'s or the dialog would let a user configure `orderId` and
 * `order_id` as two keys that then group identically.
 */
class CorrelationKeysTest {

    // ---- names ---------------------------------------------------------------------------------

    @Test fun `accepts identifier-shaped names`() {
        listOf("order_id", "orderId", "ORDER_ID", "order-id", "trip.id", "_internal", "a")
            .forEach { assertEquals(it, CorrelationKeys.sanitizeName(it), it) }
    }

    @Test fun `trims surrounding whitespace`() {
        assertEquals("order_id", CorrelationKeys.sanitizeName("  order_id \n"))
    }

    @Test fun `rejects names nothing could ever match`() {
        listOf(
            "",                       // blank
            "   ",
            "order id",               // a payload key is one token
            "order|id",               // the field separator; accepting it would corrupt storage
            "order\nid",              // ditto, the record separator
            "1id",                    // an identifier doesn't start with a digit
            "-id",
            "order:id",
            "ключ",                   // outside the identifier alphabet
            "a".repeat(65),           // longer than the wire allows
        ).forEach { assertNull(CorrelationKeys.sanitizeName(it), "should reject '$it'") }
    }

    // ---- canonical spelling ---------------------------------------------------------------------

    @Test fun `one key however it is spelled`() {
        val canonical = CorrelationKeys.canonical("order_id")
        listOf("orderId", "ORDER_ID", "order-id", "Order.Id")
            .forEach { assertEquals(canonical, CorrelationKeys.canonical(it), it) }
        assertFalse(canonical == CorrelationKeys.canonical("trip_id"))
    }

    /**
     * The dialog refuses a second spelling of a configured key *because* the extractor treats
     * them as one. If these two rules ever drifted apart, one of the two keys would silently
     * shadow the other — so the agreement is asserted, not assumed.
     */
    @Test fun `canonical agrees with what the extractor matches`() {
        val event = LogEvent.Generic(
            null,
            Envelope(
                kind = "app", id = "e1", at = 1, endedAt = 1,
                payload = buildJsonObject { put("orderId", "21053953") },
            ),
        )
        assertEquals(
            CorrelationKeys.canonical("order_id"),
            CorrelationKeys.canonical("orderId"),
        )
        assertEquals(
            mapOf("order_id" to "21053953"),
            Correlation.valuesFor(event, listOf(CorrelationKey("order_id"))),
        )
    }

    // ---- normalize ------------------------------------------------------------------------------

    @Test fun `normalize drops unusable names and keeps the rest in order`() {
        val normalized = CorrelationKeys.normalize(
            listOf(CorrelationKey("order_id"), CorrelationKey("bad name"), CorrelationKey("trip_id")),
        )
        assertEquals(listOf("order_id", "trip_id"), normalized.map { it.name })
    }

    @Test fun `normalize keeps the first of two spellings of one key`() {
        val normalized = CorrelationKeys.normalize(
            listOf(
                CorrelationKey("order_id", enabled = true),
                CorrelationKey("orderId", enabled = false, allowShortValues = true),
            ),
        )
        assertEquals(1, normalized.size)
        assertEquals("order_id", normalized[0].name)
        assertTrue(normalized[0].enabled)
        assertFalse(normalized[0].allowShortValues)
    }

    @Test fun `normalize caps the list`() {
        val many = (1..CorrelationKeys.MAX_KEYS + 5).map { CorrelationKey("key_$it") }
        assertEquals(CorrelationKeys.MAX_KEYS, CorrelationKeys.normalize(many).size)
    }

    @Test fun `normalize keeps a nonsensical length floor inside the usable range`() {
        val normalized = CorrelationKeys.normalize(
            listOf(CorrelationKey("order_id", minLength = 0), CorrelationKey("trip_id", minLength = 900)),
        )
        assertEquals(listOf(1, 32), normalized.map { it.minLength })
    }

    @Test fun `normalize preserves the flags that change matching`() {
        val normalized = CorrelationKeys.normalize(
            listOf(CorrelationKey("pin", enabled = false, allowShortValues = true)),
        )
        assertEquals(CorrelationKey("pin", enabled = false, allowShortValues = true), normalized.single())
    }

    // ---- round trip ------------------------------------------------------------------------------

    @Test fun `a key set survives storage`() {
        val keys = listOf(
            CorrelationKey("order_id", enabled = true, minLength = 4, allowShortValues = false),
            CorrelationKey("trip_id", enabled = false, minLength = 6, allowShortValues = true),
        )
        assertEquals(keys, CorrelationKeys.parse(CorrelationKeys.serialize(keys)))
    }

    @Test fun `nothing stored is no keys — LogPose ships none`() {
        assertEquals(emptyList<CorrelationKey>(), CorrelationKeys.parse(null))
        assertEquals(emptyList<CorrelationKey>(), CorrelationKeys.parse(""))
        assertEquals(emptyList<CorrelationKey>(), CorrelationKeys.parse("   "))
        assertEquals("", CorrelationKeys.serialize(emptyList()))
    }

    @Test fun `a corrupt settings string costs only the lines that are corrupt`() {
        val parsed = CorrelationKeys.parse(
            listOf(
                "order_id|1|4|0",
                "not a key|1|4|0",   // invalid name
                "",                   // stray blank
                "trip_id",            // truncated: every field but the name is optional
                "pin|1|x|1",          // unreadable length floor
            ).joinToString("\n")
        )
        assertEquals(listOf("order_id", "trip_id", "pin"), parsed.map { it.name })
        // A truncated line reads as an enabled key with the default floor — the common case, not
        // a disabled one nobody would understand.
        assertTrue(parsed[1].enabled)
        assertEquals(Correlation.DEFAULT_MIN_LENGTH, parsed[1].minLength)
        assertEquals(Correlation.DEFAULT_MIN_LENGTH, parsed[2].minLength)
        assertTrue(parsed[2].allowShortValues)
    }

    @Test fun `parse dedupes what an older build may have written`() {
        assertEquals(
            listOf("order_id"),
            CorrelationKeys.parse("order_id|1|4|0\norderId|0|4|0").map { it.name },
        )
    }

    // ---- adding ----------------------------------------------------------------------------------

    @Test fun `withAdded refuses a duplicate under any spelling`() {
        val keys = listOf(CorrelationKey("order_id"))
        assertSame(keys, CorrelationKeys.withAdded(keys, "orderId"))
        assertSame(keys, CorrelationKeys.withAdded(keys, "ORDER_ID"))
    }

    @Test fun `withAdded refuses a name that is not a key`() {
        val keys = listOf(CorrelationKey("order_id"))
        assertSame(keys, CorrelationKeys.withAdded(keys, "order id"))
    }

    @Test fun `withAdded appends inert by default — a suggestion is not a decision`() {
        val added = CorrelationKeys.withAdded(emptyList(), "order_id")
        assertEquals(1, added.size)
        assertFalse(added.single().enabled)
    }

    @Test fun `withAdded stops at the cap`() {
        val full = (1..CorrelationKeys.MAX_KEYS).map { CorrelationKey("key_$it") }
        assertSame(full, CorrelationKeys.withAdded(full, "one_more_id"))
    }

    @Test fun `contains reads through spelling`() {
        val keys = listOf(CorrelationKey("order_id"))
        assertTrue(CorrelationKeys.contains(keys, "orderId"))
        assertFalse(CorrelationKeys.contains(keys, "trip_id"))
        assertFalse(CorrelationKeys.contains(keys, "not a key"))
    }
}
