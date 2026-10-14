package io.github.siddharthjaswal.logpose.presentation

import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What an abandoned span says in each host — decided here once, so plugin and Mac app agree. */
class AbandonedPresentationTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val restarted = LogEvent.REASON_APP_RESTARTED

    private fun pending(abandoned: String? = null): LogEvent.Http {
        val tx = Transaction(id = "r", startedAtMillis = 1_000, request = Request("GET", "https://x/app/v4/slots/options/", path = "/app/v4/slots/options/"))
        return LogEvent.Http(tx, Envelope(kind = Envelope.KIND_HTTP, id = "r", at = 1_000, payload = json.encodeToJsonElement(tx)), abandoned)
    }

    @Test fun `an abandoned call is not pending and says why`() {
        val row = RowContent.http(pending(restarted))
        assertFalse(row.pending)
        assertEquals(RowContent.ABANDONED_STATUS, row.status)
        assertEquals("abandoned · app restarted", row.abandoned)
        assertEquals("", row.duration)
    }

    @Test fun `a live pending call is unchanged`() {
        val row = RowContent.http(pending())
        assertTrue(row.pending)
        assertEquals("···", row.status)
        assertNull(row.abandoned)
    }

    @Test fun `an abandoned worker shows the reason instead of a live state`() {
        val w = WorkerEvent(worker = "DataSyncWorker", state = "running", workId = "w")
        val ev = LogEvent.Worker(w, Envelope(kind = Envelope.KIND_WORKER, id = "w", at = 5, payload = json.encodeToJsonElement(w)), restarted)
        val row = RowContent.worker(ev)
        // The same words the HTTP row prints, from the same function.
        assertEquals(RowContent.http(pending(restarted)).abandoned, row.abandoned)
        assertEquals("abandoned · app restarted", row.abandoned)
        assertEquals(RowContent.TimeCell.Timestamp(5), row.time)
    }

    @Test fun `the detail card's status and chips say abandoned, not pending or running`() {
        val ev = pending(restarted)
        assertEquals("abandoned · app restarted", DetailContent.statusLabel(ev.tx, ev.abandoned))
        assertEquals("pending", DetailContent.statusLabel(ev.tx))
        val chip = DetailContent.genericChips(ev).single { it.caption == "state" }
        assertEquals("abandoned · app restarted", chip.value)
        assertTrue(DetailContent.abandonedBanner(restarted).contains("app restarted"))
    }

    @Test fun `an abandoned span is drawn as a point, not a bar growing to now`() {
        val layout = WaterfallLayout.of("t", listOf(pending(restarted)), nowMillis = 99_000)
        assertFalse(layout.lanes.single().isOpen)
        assertTrue(WaterfallLayout.of("t", listOf(pending()), nowMillis = 99_000).lanes.single().isOpen)
    }
}
