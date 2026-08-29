package io.github.siddharthjaswal.logpose.ui

import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The matching rules the redesign moved but did not change.
 *
 * M3 restructures where these controls live — method and status now sit in a popover, and their
 * choices echo on a second row — so the rules they drive are worth pinning: DB is opt-in, an
 * HTTP-only choice hides every other kind (the behaviour the new echo-row explainer exists to
 * announce), and search reads the words the row shows rather than the payload behind it.
 */
class FilterStateTest {

    // ---- the DB opt-in ---------------------------------------------------------------------------

    @Test
    fun `db events stay hidden until the DB chip asks for them`() {
        val query = db("SELECT * FROM riders", "riders")
        // A Room callback outproduces every other source by an order of magnitude, so "show all"
        // deliberately does not mean "show db".
        assertFalse(FilterState().matches(query))
        assertTrue(FilterState(types = setOf(EventType.DB)).matches(query))
    }

    @Test
    fun `every other kind is visible with no type chip selected`() {
        val state = FilterState()
        assertTrue(state.matches(http()))
        assertTrue(state.matches(fcm()))
        assertTrue(state.matches(worker()))
        assertTrue(state.matches(generic(Envelope.KIND_ANALYTICS, "checkout_viewed")))
        assertTrue(state.matches(generic("payments", "settled")))
    }

    @Test
    fun `analytics has its own chip and app-defined kinds share one`() {
        val analytics = generic(Envelope.KIND_ANALYTICS, "checkout_viewed")
        val appDefined = generic("payments", "settled")
        assertTrue(FilterState(types = setOf(EventType.ANALYTICS)).matches(analytics))
        assertFalse(FilterState(types = setOf(EventType.ANALYTICS)).matches(appDefined))
        assertTrue(FilterState(types = setOf(EventType.APP)).matches(appDefined))
        assertFalse(FilterState(types = setOf(EventType.APP)).matches(analytics))
    }

    // ---- the HTTP-only mode ------------------------------------------------------------------------

    @Test
    fun `a method or status choice hides every non-HTTP kind`() {
        // This is the switch the echo row now explains: nothing in the popover says it, and a
        // capture that silently loses its db, worker and analytics rows reads as a bug.
        for (state in listOf(FilterState(methods = setOf("GET")), FilterState(statusClasses = setOf(2)))) {
            assertFalse(state.matches(fcm()), "$state kept an FCM row")
            assertFalse(state.matches(worker()), "$state kept a worker row")
            assertFalse(state.matches(generic(Envelope.KIND_ANALYTICS, "checkout_viewed")))
            assertFalse(FilterState(types = setOf(EventType.DB), methods = setOf("GET")).matches(db("SELECT 1")))
        }
    }

    @Test
    fun `method matching is case-insensitive on the wire value`() {
        assertTrue(FilterState(methods = setOf("POST")).matches(http(method = "post")))
        assertFalse(FilterState(methods = setOf("POST")).matches(http(method = "GET")))
    }

    @Test
    fun `status classes match by hundreds, and a pending call has no class`() {
        assertTrue(FilterState(statusClasses = setOf(4)).matches(http(code = 404)))
        assertFalse(FilterState(statusClasses = setOf(4)).matches(http(code = 204)))
        assertTrue(FilterState(statusClasses = setOf(5)).matches(http(code = 503)))
        assertFalse(FilterState(statusClasses = setOf(2)).matches(http(code = null)))
    }

    // ---- search ------------------------------------------------------------------------------------

    @Test
    fun `search reads the URL for HTTP and the row's own words for everything else`() {
        assertTrue(FilterState(urlQuery = "order").matches(http(path = "/v4/order/21053953/")))
        assertFalse(FilterState(urlQuery = "trip").matches(http(path = "/v4/order/21053953/")))
        // The db row shows its table and its SQL, so those are what a query searches — not the
        // raw payload JSON behind them.
        val state = FilterState(urlQuery = "riders", types = setOf(EventType.DB))
        assertTrue(state.matches(db("SELECT * FROM riders WHERE id = ?", "riders")))
        assertFalse(state.matches(db("SELECT * FROM orders", "orders")))
    }

    @Test
    fun `search looks inside an FCM data map, keys and values alike`() {
        assertTrue(FilterState(urlQuery = "order-assigned").matches(fcm(mapOf("channel" to "order-assigned"))))
        assertTrue(FilterState(urlQuery = "channel").matches(fcm(mapOf("channel" to "order-assigned"))))
        assertFalse(FilterState(urlQuery = "cancelled").matches(fcm(mapOf("channel" to "order-assigned"))))
    }

    @Test
    fun `a blank query narrows nothing`() {
        assertTrue(FilterState(urlQuery = "   ").matches(http()))
    }

    // ---- fixtures -----------------------------------------------------------------------------------

    private fun envelope(kind: String, id: String = "e1") =
        Envelope(kind = kind, id = id, at = 1_000, payload = JsonObject(emptyMap()))

    private fun http(
        method: String = "GET",
        path: String = "/v4/order/21053953/",
        code: Int? = 200,
    ): LogEvent.Http {
        val tx = Transaction(
            id = "h1",
            request = Request(method = method, url = "https://api.example.com$path", path = path),
            response = code?.let { Response(code = it) },
        )
        return LogEvent.Http(tx, envelope(Envelope.KIND_HTTP, "h1"))
    }

    private fun fcm(data: Map<String, String> = mapOf("channel" to "order-assigned")) =
        LogEvent.Fcm(FcmMessage(id = "f1", data = data), envelope(Envelope.KIND_FCM, "f1"))

    private fun db(sql: String, table: String? = null) =
        LogEvent.Db(DbQuery(sql = sql, table = table), envelope(Envelope.KIND_DB, "d1"))

    private fun worker() =
        LogEvent.Worker(WorkerEvent(worker = "DataSyncWorker", state = "running"), envelope(Envelope.KIND_WORKER, "w1"))

    private fun generic(kind: String, title: String) =
        LogEvent.Generic(GenericEvent(title = title), envelope(kind, "g1"))
}
