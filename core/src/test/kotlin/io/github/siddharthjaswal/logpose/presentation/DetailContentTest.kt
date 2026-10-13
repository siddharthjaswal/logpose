package io.github.siddharthjaswal.logpose.presentation

import io.github.siddharthjaswal.logpose.analysis.DuplicateDetector
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.FcmNotification
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The detail cards' words, moved out of the plugin's Swing cards so the Mac app says the same. */
class DetailContentTest {

    private fun tx(code: Int? = 200, message: String = "", error: String? = null, reqHeaders: Map<String, String> = emptyMap(), respHeaders: Map<String, String> = emptyMap()) =
        Transaction(
            id = "t", startedAtMillis = 1,
            request = Request("POST", "https://x/y", headers = reqHeaders),
            response = code?.let { Response(it, message, respHeaders) },
            error = error,
        )

    @Test fun `status label prefers the server's message and fills in a reason`() {
        assertEquals("200 OK", DetailContent.statusLabel(tx()))
        assertEquals("200 All good", DetailContent.statusLabel(tx(message = "All good")))
        assertEquals("418", DetailContent.statusLabel(tx(code = 418)))
        assertEquals("ERR", DetailContent.statusLabel(tx(code = null, error = "boom")))
        assertEquals("pending", DetailContent.statusLabel(tx(code = null)))
    }

    @Test fun `server trace id prefers response headers, labels trace vs request ids`() {
        assertEquals("req id" to "abc", DetailContent.serverTraceId(tx(respHeaders = mapOf("X-Request-Id" to "abc"))))
        assertEquals("trace id" to "00-1", DetailContent.serverTraceId(tx(reqHeaders = mapOf("traceparent" to "00-1"))))
        assertNull(DetailContent.serverTraceId(tx()))
    }

    @Test fun `duplicate banner and hint by severity`() {
        val strong = DetailContent.duplicateBanner(tx(), DuplicateDetector.Mark(2, DuplicateDetector.Severity.STRONG, "o"))
        assertEquals(
            "⚠  DUPLICATE ×2  ·  Possible double-submit — fired before the previous POST responded; check for a missing debounce or disabled-button guard.",
            strong.text,
        )
        val medium = DetailContent.duplicateBanner(tx(), DuplicateDetector.Mark(3, DuplicateDetector.Severity.MEDIUM, "o"))
        assertEquals("⚠  DUPLICATE ×3  ·  Redundant duplicate request — 3rd identical POST within a short burst.", medium.text)
        val hint = DetailContent.duplicateHint(tx(), DuplicateDetector.Mark(5, DuplicateDetector.Severity.INFO, "o"))
        assertEquals("Repeated request", hint.bold)
        assertEquals(listOf("5th identical POST call in a quick burst."), hint.lines)
    }

    @Test fun `fcm hero words`() {
        val notif = FcmMessage(id = "f", notification = FcmNotification("Title", "Body"))
        assertEquals("Push message", DetailContent.fcmEventLabel(notif))
        assertEquals("Title\nBody", DetailContent.fcmSummary(notif))
        assertEquals("tok-1", DetailContent.fcmSummary(FcmMessage(id = "t", event = "token", token = "tok-1")))
        assertEquals("from 123", DetailContent.fcmSummary(FcmMessage(id = "d", from = "123")))
        assertEquals("high", DetailContent.fcmPriority(1))
        assertEquals("7", DetailContent.fcmPriority(7))
    }
}
