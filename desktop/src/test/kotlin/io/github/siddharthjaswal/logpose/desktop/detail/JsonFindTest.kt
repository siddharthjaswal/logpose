package io.github.siddharthjaswal.logpose.desktop.detail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class JsonFindTest {
    private val lines = listOf("{", "  \"order_id\": \"OR-881\",", "  \"ORDER\": \"orderorder\"", "}")

    @Test fun `case-insensitive, every match, non-overlapping`() {
        val m = JsonFind.matches(lines, "order")
        assertEquals(listOf(1 to 3, 2 to 3, 2 to 12, 2 to 17), m.map { it.line to it.start })
        assertEquals(2, JsonFind.matches(listOf("aaaa"), "aa").size)
        assertEquals(emptyList<JsonFind.Match>(), JsonFind.matches(lines, "  "))
    }

    @Test fun `counter and wrapping steps`() {
        assertEquals("", JsonFind.counter("", 0, -1))
        assertEquals("0/0", JsonFind.counter("zz", 0, -1))
        assertEquals("2/4", JsonFind.counter("o", 4, 1))
        assertEquals(0, JsonFind.step(3, 4, 1))
        assertEquals(3, JsonFind.step(0, 4, -1))
        assertEquals(-1, JsonFind.step(0, 0, 1))
    }
}
