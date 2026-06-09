package io.github.siddharthjaswal.logpose.analysis

import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DuplicateDetectorTest {

    private var seq = 0

    // A realistic epoch base — production timestamps are always large; 0 means "unknown".
    private val base = 1_700_000_000_000L

    /** Builds a completed transaction; [start] is millis-from-base, [dur] the round-trip. */
    private fun tx(
        method: String,
        url: String,
        start: Long,
        dur: Long? = 50,
        code: Int? = 200,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ) = Transaction(
        id = "tx${seq++}",
        startedAtMillis = base + start,
        request = Request(method = method, url = url, headers = headers, body = body?.let { Body(text = it) }),
        response = code?.let { Response(code = it) },
        durationMillis = dur,
    )

    /** An in-flight (pending) transaction: no response, unknown duration. */
    private fun pending(method: String, url: String, start: Long, body: String? = null) = Transaction(
        id = "tx${seq++}",
        startedAtMillis = base + start,
        request = Request(method = method, url = url, body = body?.let { Body(text = it) }),
        response = null,
        durationMillis = null,
    )

    @Test
    fun `single request is never a duplicate`() {
        val marks = DuplicateDetector.analyze(listOf(tx("GET", "https://api/x", 0)))
        assertTrue(marks.isEmpty())
    }

    @Test
    fun `identical POST within window is flagged as duplicate`() {
        val a = tx("POST", "https://api/order", 0)
        val b = tx("POST", "https://api/order", 300)
        val marks = DuplicateDetector.analyze(listOf(a, b))
        assertNull(marks[a.id], "first call is the original, not a duplicate")
        assertEquals(2, marks[b.id]?.ordinal)
        assertEquals(a.id, marks[b.id]?.originalId)
    }

    @Test
    fun `gap beyond the window is not a duplicate`() {
        val a = tx("POST", "https://api/order", 0)
        val b = tx("POST", "https://api/order", 5_000)
        val marks = DuplicateDetector.analyze(listOf(a, b))
        assertNull(marks[b.id])
    }

    @Test
    fun `overlapping in-flight non-idempotent call is STRONG`() {
        // First is still pending; second fires 200ms later → double-submit.
        val a = pending("POST", "https://api/pay", 0)
        val b = tx("POST", "https://api/pay", 200)
        val marks = DuplicateDetector.analyze(listOf(a, b))
        assertEquals(DuplicateDetector.Severity.STRONG, marks[b.id]?.severity)
    }

    @Test
    fun `sequential completed non-idempotent duplicate is MEDIUM not STRONG`() {
        // First completes in 50ms; second starts at 300ms → no overlap → a retry-like MEDIUM.
        val a = tx("POST", "https://api/pay", 0, dur = 50)
        val b = tx("POST", "https://api/pay", 300, dur = 50)
        val marks = DuplicateDetector.analyze(listOf(a, b))
        assertEquals(DuplicateDetector.Severity.MEDIUM, marks[b.id]?.severity)
    }

    @Test
    fun `repeated GET is only INFO`() {
        val a = tx("GET", "https://api/feed", 0)
        val b = tx("GET", "https://api/feed", 200)
        val marks = DuplicateDetector.analyze(listOf(a, b))
        assertEquals(DuplicateDetector.Severity.INFO, marks[b.id]?.severity)
    }

    @Test
    fun `query param order does not defeat matching`() {
        val a = tx("GET", "https://api/x?a=1&b=2", 0)
        val b = tx("GET", "https://api/x?b=2&a=1", 200)
        val marks = DuplicateDetector.analyze(listOf(a, b))
        assertEquals(2, marks[b.id]?.ordinal)
    }

    @Test
    fun `volatile cache-buster params are ignored`() {
        val a = tx("GET", "https://api/x?_=1000", 0)
        val b = tx("GET", "https://api/x?_=2000", 200)
        val marks = DuplicateDetector.analyze(listOf(a, b))
        assertEquals(2, marks[b.id]?.ordinal, "differing cache-buster must not break the match")
    }

    @Test
    fun `different request body breaks the match for POST`() {
        val a = tx("POST", "https://api/order", 0, body = """{"qty":1}""")
        val b = tx("POST", "https://api/order", 200, body = """{"qty":2}""")
        val marks = DuplicateDetector.analyze(listOf(a, b))
        assertNull(marks[b.id], "distinct bodies are distinct operations")
    }

    @Test
    fun `distinct idempotency keys are treated as distinct operations`() {
        val a = tx("POST", "https://api/order", 0, headers = mapOf("Idempotency-Key" to "k1"))
        val b = tx("POST", "https://api/order", 200, headers = mapOf("Idempotency-Key" to "k2"))
        val marks = DuplicateDetector.analyze(listOf(a, b))
        assertNull(marks[b.id])
    }

    @Test
    fun `same idempotency key is a duplicate`() {
        val a = tx("POST", "https://api/order", 0, headers = mapOf("Idempotency-Key" to "k1"))
        val b = tx("POST", "https://api/order", 200, headers = mapOf("Idempotency-Key" to "k1"))
        val marks = DuplicateDetector.analyze(listOf(a, b))
        assertEquals(2, marks[b.id]?.ordinal)
    }

    @Test
    fun `three rapid taps increment the ordinal`() {
        val a = tx("POST", "https://api/order", 0)
        val b = tx("POST", "https://api/order", 200)
        val c = tx("POST", "https://api/order", 400)
        val marks = DuplicateDetector.analyze(listOf(a, b, c))
        assertEquals(2, marks[b.id]?.ordinal)
        assertEquals(3, marks[c.id]?.ordinal)
    }

    @Test
    fun `chain resets after a long gap`() {
        val a = tx("POST", "https://api/order", 0)
        val b = tx("POST", "https://api/order", 200)      // dup ×2
        val c = tx("POST", "https://api/order", 10_000)   // fresh original
        val d = tx("POST", "https://api/order", 10_200)   // dup ×2 again
        val marks = DuplicateDetector.analyze(listOf(a, b, c, d))
        assertEquals(2, marks[b.id]?.ordinal)
        assertNull(marks[c.id])
        assertEquals(2, marks[d.id]?.ordinal)
    }

    @Test
    fun `missing timestamps are not flagged`() {
        // Older interceptors may not stamp startedAtMillis (0) — never guess in that case.
        val a = tx("POST", "https://api/order", 0).copy(startedAtMillis = 0)
        val b = tx("POST", "https://api/order", 0).copy(startedAtMillis = 0)
        val marks = DuplicateDetector.analyze(listOf(a, b))
        assertNull(marks[b.id])
    }
}
