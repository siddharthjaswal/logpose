package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.logcat.TransactionParser
import io.github.siddharthjaswal.logpose.model.Badge
import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.ConfigChange
import io.github.siddharthjaswal.logpose.model.ConfigUpdate
import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.FcmNotification
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.model.WorkerEvent
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * A realistic mixed capture, built as **wire lines** and fed through core's real parser, so the
 * screenshots exercise the same decode path a device does.
 */
object Fixtures {

    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private const val T0 = 1_760_340_000_000L

    private fun <T> line(kind: String, id: String, at: Long, endedAt: Long?, s: KSerializer<T>, payload: T, traceId: String? = null): String =
        json.encodeToString(
            Envelope.serializer(),
            Envelope(kind = kind, id = id, at = at, endedAt = endedAt, traceId = traceId, payload = json.encodeToJsonElement(s, payload)),
        )

    private fun http(
        id: String, method: String, path: String, code: Int?, ms: Long?, size: Long = 0,
        body: String? = null, mocked: Boolean = false, error: String? = null, offset: Long,
        reqBody: String = """{"lat":28.61,"lng":77.20,"accuracy":12}""", respHeaders: Map<String, String> = emptyMap(), traceId: String? = null,
    ): String {
        val at = T0 + offset
        val tx = Transaction(
            id = id, startedAtMillis = at,
            request = Request(
                method, "https://api.example-delivery.com$path", "api.example-delivery.com", path,
                headers = mapOf("Accept" to "application/json", "Authorization" to "██"),
                body = if (method == "POST") Body("application/json", 64, reqBody) else null,
            ),
            response = code?.let {
                Response(it, if (it < 400) "OK" else "Error", mapOf("Content-Type" to "application/json") + respHeaders, Body("application/json", size, body))
            },
            durationMillis = ms, error = error, mocked = mocked,
        )
        val ended = if (code == null && error == null) null else at + (ms ?: 0)
        return line(Envelope.KIND_HTTP, id, at, ended, Transaction.serializer(), tx, traceId)
    }

    fun lines(): List<String> = listOf(
        http("h1", "GET", "/v2/orders/active", 200, 142, 1240,
            """{"orders":[{"order_id":"OR-881","status":"ASSIGNED","eta_min":12,"cod":false,"items":3},{"order_id":"OR-882","status":"PICKED","eta_min":4,"cod":true,"items":1}],"page":1,"has_more":false}""",
            offset = 0),
        http("h2", "POST", "/v1/rider/location", 200, 61, 18, """{"ok":true}""", offset = 900),
        line(Envelope.KIND_DB, "d1", T0 + 1_100, T0 + 1_104, DbQuery.serializer(),
            DbQuery("SELECT * FROM orders WHERE status = ? ORDER BY created_at DESC", listOf("ASSIGNED"), "gandalf.db", rows = 12)),
        http("h3", "GET", "/v1/profile/settings", 404, 88, 52, """{"error":"not_found"}""", offset = 2_000),
        line(Envelope.KIND_WORKER, "w1", T0 + 2_400, T0 + 3_900, WorkerEvent.serializer(),
            WorkerEvent("LocationSyncWorker", WorkerEvent.STATE_SUCCEEDED, "w1", runAttempt = 1, tags = listOf("location-sync"),
                enqueuedAtMillis = T0 + 2_400, runStartedAtMillis = T0 + 2_700)),
        http("h4", "POST", "/v1/orders/OR-881/accept", 500, 2_310, 96,
            """{"error":"internal","message":"order service unavailable","retry_after":5}""", offset = 4_000),
        line(Envelope.KIND_FCM, "f1", T0 + 4_800, T0 + 4_800, FcmMessage.serializer(),
            FcmMessage(id = "f1", receivedAtMillis = T0 + 4_800, notification = FcmNotification("New order nearby", "Pickup in 400 m"),
                data = mapOf("channel" to "orders", "order_id" to "OR-883"), injected = true)),
        http("h5", "GET", "/v1/orders/OR-881", 200, 3, 512,
            """{"order_id":"OR-881","status":"ACCEPTED","customer":{"name":"(scrubbed)"},"slot":"18:00-18:30"}""", mocked = true, offset = 5_200),
        line(Envelope.KIND_ANALYTICS, "a1", T0 + 5_400, T0 + 5_400, GenericEvent.serializer(),
            GenericEvent("order_accepted", "OrderDetailFragment", listOf(Badge("ANALYTICS"), Badge("firebase")))),
        line(Envelope.KIND_DB, "d2", T0 + 5_600, T0 + 5_609, DbQuery.serializer(),
            DbQuery("INSERT INTO rider_location (lat, lng, ts) VALUES (?, ?, ?)", listOf("28.61", "77.20", "1760340005600"), "gandalf.db", rows = 1)),
        line(Envelope.KIND_WORKER, "w2", T0 + 6_000, null, WorkerEvent.serializer(),
            WorkerEvent("OrderUploadWorker", WorkerEvent.STATE_RUNNING, "w2", runAttempt = 2, tags = listOf("upload-pod"))),
        line(Envelope.KIND_CONFIG, "c1", T0 + 6_500, T0 + 6_500, ConfigUpdate.serializer(),
            ConfigUpdate("firebase", "success", false, 84, listOf(ConfigChange("enable_batching", "true", "false"), ConfigChange("max_radius_km", "6", "4")))),
        line(Envelope.KIND_FCM, "f2", T0 + 7_000, T0 + 7_000, FcmMessage.serializer(),
            FcmMessage(id = "f2", receivedAtMillis = T0 + 7_000, data = mapOf("channel" to "order_updates", "order_id" to "OR-882", "status" to "CANCELLED"))),
        line(Envelope.KIND_EVENT, "e1", T0 + 7_400, T0 + 7_520, GenericEvent.serializer(),
            GenericEvent("Payment sheet opened", "COD · ₹420", listOf(Badge("checkout", Badge.TONE_INFO), Badge("cod")))),
        http("h6", "GET", "/v1/earnings/today", null, null, error = "SocketTimeoutException: timeout", offset = 8_000),
        http("h7", "GET", "/v1/config/app", null, null, offset = 9_000),
    )

    const val START = T0

    // ---- M4a: parity fixtures ------------------------------------------------------------------

    /** The muted endpoint in [m4aLines] (`MutedEndpoints.keyOf` of `/v1/rider/79096/location/`). */
    const val MUTED_KEY = "/v1/rider/#/location/"

    /** The anchor of the poll run [m4aLines] renders expanded, and the one it renders collapsed. */
    const val POLL_EXPANDED_ANCHOR = "s1"
    const val POLL_COLLAPSED_ANCHOR = "p1"

    private fun db(id: String, sql: String, offset: Long, ms: Long = 1, rows: Int? = null): String =
        line(Envelope.KIND_DB, id, T0 + offset, T0 + offset + ms, DbQuery.serializer(), DbQuery(sql, database = "gandalf.db", rows = rows))

    private fun work(id: String, state: String, offset: Long, ended: Long?, attempt: Int = 1, enq: Long? = null, run: Long? = null, worker: String = "OrderSyncWorker") =
        line(
            Envelope.KIND_WORKER, id, T0 + offset, ended?.let { T0 + it }, WorkerEvent.serializer(),
            WorkerEvent(worker, state, id, runAttempt = attempt, tags = listOf("sync-orders"),
                enqueuedAtMillis = enq?.let { T0 + it }, runStartedAtMillis = run?.let { T0 + it },
                inputData = mapOf("orderId" to "OR-881", "reason" to "accept")),
        )

    /**
     * Everything M4a draws: a polling wall (collapsed), a second one (rendered expanded), a db
     * transaction whose ceremony folds, an overlapping double-submit that LogPose also mocked, a
     * plain retry duplicate, a muted endpoint, a call still in flight, a running worker, a worker
     * whose transitions were observed, an injected push, a bare analytics event and a config change.
     */
    fun m4aLines(): List<String> = buildList {
        add(http("h1", "GET", "/v2/orders/active", 200, 142, 1240,
            """{"orders":[{"order_id":"OR-881","status":"ASSIGNED","eta_min":12},{"order_id":"OR-882","status":"PICKED","eta_min":4}],"page":1,"has_more":false}""",
            offset = 0, respHeaders = mapOf("X-Request-Id" to "req-5c1e0f9a77b2"), traceId = "trc-7f3a9c21"))
        // A polling wall: six heartbeats, five seconds apart — folds to one `×6` row.
        listOf(40L, 52, 38, 61, 44, 47).forEachIndexed { i, ms ->
            add(http("p${i + 1}", "GET", "/v1/rider/heartbeat", 200, ms, 18, """{"ok":true}""", offset = 1_000L + i * 5_000))
        }
        // A db transaction: the ceremony folds; the INSERT and UPDATE it wrapped stay rows.
        add(db("t1", "BEGIN TRANSACTION", 1_500))
        add(db("t2", "INSERT INTO rider_location (lat, lng, ts) VALUES (?, ?, ?)", 1_520, 3, rows = 1))
        add(db("t3", "UPDATE OR ABORT `orders` SET status = ? WHERE order_id = ?", 1_540, 2, rows = 1))
        add(db("t4", "SELECT changes()", 1_560))
        add(db("t5", "TRANSACTION SUCCESSFUL", 1_580))
        add(db("t6", "END TRANSACTION", 1_627))
        // A second poll, four status checks — rendered expanded.
        listOf(90L, 84, 101, 95).forEachIndexed { i, ms ->
            add(http("s${i + 1}", "GET", "/v1/orders/OR-881/status", 200, ms, 64, """{"status":"ASSIGNED"}""", offset = 2_000L + i * 4_000))
        }
        // A double-submit: the second accept fired 300ms into the first's 900ms flight (STRONG),
        // and LogPose's mock served it.
        add(http("d1", "POST", "/v1/orders/OR-881/accept", 500, 900, 96, """{"error":"internal"}""", offset = 30_000, reqBody = """{"order_id":"OR-881"}"""))
        add(http("d2", "POST", "/v1/orders/OR-881/accept", 200, 4, 41, """{"order_id":"OR-881","status":"ACCEPTED"}""", mocked = true, offset = 30_300, reqBody = """{"order_id":"OR-881"}""", traceId = "trc-7f3a9c21"))
        // A retry after the first one finished — a redundant duplicate (MEDIUM).
        add(http("r1", "POST", "/v1/feedback", 201, 120, 12, """{"ok":true}""", offset = 32_000, reqBody = """{"stars":5}"""))
        add(http("r2", "POST", "/v1/feedback", 201, 118, 12, """{"ok":true}""", offset = 32_600, reqBody = """{"stars":5}"""))
        // A muted location ping (faded, 26px).
        add(http("m1", "PUT", "/v1/rider/79096/location/", 200, 31, 2, """{}""", offset = 33_000))
        add(line(Envelope.KIND_FCM, "f1", T0 + 34_000, T0 + 34_000, FcmMessage.serializer(),
            FcmMessage(id = "f1", receivedAtMillis = T0 + 34_000, notification = FcmNotification("New order nearby", "Pickup in 400 m · ₹62"),
                data = mapOf("channel" to "orders", "order_id" to "OR-883"), injected = true, from = "1049981234", priority = 1, ttlSeconds = 60),
            traceId = "trc-91be004d"))
        // Observed transitions: enqueued → running → succeeded, all under one workId.
        add(work("w-sync", WorkerEvent.STATE_ENQUEUED, 35_000, null, enq = 35_000))
        add(work("w-sync", WorkerEvent.STATE_RUNNING, 35_000, null, enq = 35_000, run = 36_200))
        add(work("w-sync", WorkerEvent.STATE_SUCCEEDED, 35_000, 39_100, enq = 35_000, run = 36_200))
        add(line(Envelope.KIND_ANALYTICS, "a1", T0 + 40_000, T0 + 40_000, GenericEvent.serializer(),
            GenericEvent("app_open", null, listOf(Badge("ANALYTICS")))))
        add(line(Envelope.KIND_CONFIG, "c1", T0 + 41_000, T0 + 41_000, ConfigUpdate.serializer(),
            ConfigUpdate("firebase", "success", false, 84, listOf(ConfigChange("enable_batching", "true", "false"), ConfigChange("max_radius_km", "6", "4")))))
        add(http("e1", "GET", "/v1/profile/settings", 404, 88, 52, """{"error":"not_found"}""", offset = 42_000))
        // Still in flight, and still running: the two live count-ups.
        add(work("w-up", WorkerEvent.STATE_RUNNING, 43_000, null, attempt = 2, enq = 43_000, run = 44_100, worker = "OrderUploadWorker"))
        add(http("x1", "GET", "/v1/config/app", null, null, offset = 45_000))
    }.sortedBy { AT.find(it)!!.groupValues[1].toLong() }   // arrival order = time order (stable for one workId)

    private val AT = Regex("\"at\":(\\d+)")

    /** [m4aLines] through the real parser, noting worker transitions the way the session does. */
    fun m4aStore(lifecycle: io.github.siddharthjaswal.logpose.analysis.WorkerLifecycle? = null): EventStore {
        val store = EventStore()
        val parser = TransactionParser()
        m4aLines().forEach { l ->
            parser.accept(l)?.let { e ->
                if (e is io.github.siddharthjaswal.logpose.model.LogEvent.Worker) lifecycle?.note(e, e.timestampMillis)
                store.add(e)
            }
        }
        return store
    }

    /** The app's handshake, as the device sends it at process start. */
    val hello = io.github.siddharthjaswal.logpose.model.Hello(pkg = "in.shadowfax.gandalf", libVersion = "1.7.3", processId = "run-1")

    /**
     * [lines] plus what a delivery app's responses really carry — a customer's name, a 10-digit
     * mobile, a street address and coordinates — so the share sheet has something to scrub.
     */
    fun shareLines(): List<String> = listOf(io.github.siddharthjaswal.logpose.capture.CaptureLines.of(hello)) + lines().take(5) +
        http(
            "h8", "GET", "/v1/orders/OR-881/customer", 200, 118, 402,
            """{"order_id":"OR-881","customer":{"id":5521,"name":"Ravi Kumar","phone":"7022730639","email":"ravi.k@example.com"},"drop":{"address":"14, 3rd Cross, Koramangala, Bengaluru 560034","landmark":"Near Forum Mall","lat":12.9352,"lng":77.6245},"slot":"18:00-18:30","note":"Call +91 98450 12345 at the gate"}""",
            offset = 3_300,
        ) + lines().drop(5)

    /** A plausible phone screenshot (status bar, an order card, a button) as PNG bytes. */
    fun phonePng(): ByteArray {
        val w = 270; val h = 585
        val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = java.awt.Color(0xF4F5F7); g.fillRect(0, 0, w, h)
        g.color = java.awt.Color(0x1F6FEB); g.fillRect(0, 0, w, 64)
        g.color = java.awt.Color.WHITE; g.fillRoundRect(16, 84, w - 32, 150, 18, 18)
        g.color = java.awt.Color(0xD0D4DC); for (i in 0..3) g.fillRoundRect(32, 104 + i * 30, (w - 64) - i * 30, 12, 6, 6)
        g.color = java.awt.Color.WHITE; g.fillRoundRect(16, 250, w - 32, 220, 18, 18)
        g.color = java.awt.Color(0xE6E8EC); for (i in 0..4) g.fillRoundRect(32, 270 + i * 36, w - 64, 18, 8, 8)
        g.color = java.awt.Color(0x1F6FEB); g.fillRoundRect(24, h - 80, w - 48, 48, 24, 24)
        g.dispose()
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
    }

    fun store(): EventStore {
        val store = EventStore()
        val parser = TransactionParser()
        lines().forEach { l -> parser.accept(l)?.let(store::add) }
        return store
    }
}
