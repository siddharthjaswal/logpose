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

    private fun <T> line(kind: String, id: String, at: Long, endedAt: Long?, s: KSerializer<T>, payload: T): String =
        json.encodeToString(
            Envelope.serializer(),
            Envelope(kind = kind, id = id, at = at, endedAt = endedAt, payload = json.encodeToJsonElement(s, payload)),
        )

    private fun http(
        id: String, method: String, path: String, code: Int?, ms: Long?, size: Long = 0,
        body: String? = null, mocked: Boolean = false, error: String? = null, offset: Long,
    ): String {
        val at = T0 + offset
        val tx = Transaction(
            id = id, startedAtMillis = at,
            request = Request(
                method, "https://api.example-delivery.com$path", "api.example-delivery.com", path,
                headers = mapOf("Accept" to "application/json", "Authorization" to "██"),
                body = if (method == "POST") Body("application/json", 64, """{"lat":28.61,"lng":77.20,"accuracy":12}""") else null,
            ),
            response = code?.let {
                Response(it, if (it < 400) "OK" else "Error", mapOf("Content-Type" to "application/json"), Body("application/json", size, body))
            },
            durationMillis = ms, error = error, mocked = mocked,
        )
        val ended = if (code == null && error == null) null else at + (ms ?: 0)
        return line(Envelope.KIND_HTTP, id, at, ended, Transaction.serializer(), tx)
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
