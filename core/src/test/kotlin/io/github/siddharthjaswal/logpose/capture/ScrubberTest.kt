package io.github.siddharthjaswal.logpose.capture

import io.github.siddharthjaswal.logpose.capture.Scrubber.Category
import io.github.siddharthjaswal.logpose.logcat.TransactionParser
import io.github.siddharthjaswal.logpose.model.Body
import io.github.siddharthjaswal.logpose.model.DbQuery
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Request
import io.github.siddharthjaswal.logpose.model.Response
import io.github.siddharthjaswal.logpose.model.Transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScrubberTest {

    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private val scrubber = Scrubber()
    private val M = Scrubber.MARK

    private fun httpEnv(resBody: String?, url: String = "https://api.example.com/v1/orders/OR-881", reqBody: String? = null, headers: Map<String, String> = emptyMap()): Envelope {
        val tx = Transaction(
            "h1", 1, Request(if (reqBody != null) "POST" else "GET", url, "api.example.com", "/v1/orders/OR-881", headers, reqBody?.let { Body("application/json", it.length.toLong(), it) }),
            Response(200, "OK", mapOf("X-Agent-Phone" to "7022730639"), resBody?.let { Body("application/json", it.length.toLong(), it) }), 40,
        )
        return Envelope(kind = "http", id = "h1", at = 1, endedAt = 41, payload = json.encodeToJsonElement(Transaction.serializer(), tx))
    }

    private fun tx(e: Envelope) = (TransactionParser().accept(CaptureLines.of(e)) as LogEvent.Http).tx

    /** The shape gandalf really returns: names, a 10-digit mobile, a street address, coordinates. */
    private val gandalf = """{"order_id":"OR-881","status":"ASSIGNED","eta_min":12,"amount":420,"customer":{"id":5521,"name":"Ravi Kumar","phone":"7022730639"},"drop":{"address":"14, 3rd Cross, Koramangala, Bengaluru 560034","lat":12.9352,"lng":77.6245,"landmark":"Near Forum Mall"},"note":"Call +91 98450 12345 or mail ravi.k@example.com"}"""

    @Test fun `keys, patterns and json-in-a-string body, counted by category`() {
        val r = scrubber.scrub(httpEnv(gandalf))
        val body = json.parseToJsonElement(tx(r.envelope).response!!.body!!.text!!).jsonObject
        val customer = body["customer"]!!.jsonObject
        assertEquals(M, customer["name"]!!.jsonPrimitive.content)
        assertEquals(M, customer["phone"]!!.jsonPrimitive.content)
        assertEquals("5521", customer["id"]!!.jsonPrimitive.content) // metadata under a PII object stays
        val drop = body["drop"]!!.jsonObject
        assertEquals(M, drop["address"]!!.jsonPrimitive.content)
        assertEquals(M, drop["lat"]!!.jsonPrimitive.content)
        assertEquals(M, drop["landmark"]!!.jsonPrimitive.content)
        assertEquals("Call $M or mail $M", body["note"]!!.jsonPrimitive.content)
        // Not over-scrubbed: ids, statuses, durations, amounts.
        assertEquals("OR-881", body["order_id"]!!.jsonPrimitive.content)
        assertEquals("ASSIGNED", body["status"]!!.jsonPrimitive.content)
        assertEquals("12", body["eta_min"]!!.jsonPrimitive.content)
        assertEquals("420", body["amount"]!!.jsonPrimitive.content)
        assertEquals(mapOf(Category.NAME to 1, Category.PHONE to 2, Category.ADDRESS to 2, Category.LOCATION to 2, Category.EMAIL to 1), r.counts)
        assertEquals(200, tx(r.envelope).response!!.code)
    }

    @Test fun `headers are left alone — they are redacted on the device`() {
        val r = scrubber.scrub(httpEnv(null, headers = mapOf("X-Customer-Phone" to "7022730639")))
        val t = tx(r.envelope)
        assertEquals("7022730639", t.request.headers["X-Customer-Phone"])
        assertEquals("7022730639", t.response!!.headers["X-Agent-Phone"])
        assertEquals(0, r.scrubbed)
    }

    @Test fun `phone patterns — Indian with and without +91, international, and what does not match`() {
        fun s(text: String) = scrubber.scrub(httpEnv(text)).let { tx(it.envelope).response!!.body!!.text!! }
        assertEquals("ring $M now", s("ring 7022730639 now"))
        assertEquals("ring $M now", s("ring +917022730639 now"))
        assertEquals("ring $M now", s("ring +91 70227 30639 now"))
        assertEquals("ring $M now", s("ring 09845012345 now"))
        assertEquals("ring $M now", s("ring 98450-12345 now"))
        assertEquals("ring $M now", s("ring +1 (415) 555-2671 now"))
        assertEquals("ring $M now", s("ring +44 20 7946 0958 now"))
        // Not phones: a 13-digit epoch, a 10-digit number starting 1–5, a short code, coordinates,
        // a digit run inside a word, a version.
        for (keep in listOf("ts 1760340005600", "id 5123456789", "pin 560034", "at 28.6139391234", "ref ORD7022730639X", "v +1.2.3", "otp 482913")) {
            assertEquals(keep, s(keep), keep)
        }
    }

    @Test fun `emails anywhere in a string, including the url`() {
        val r = scrubber.scrub(httpEnv(null, url = "https://api.example.com/v1/users?email=ravi.k@example.co.in&page=2"))
        assertEquals("https://api.example.com/v1/users?email=$M&page=2", tx(r.envelope).request.url)
        assertEquals(mapOf(Category.EMAIL to 1), r.counts)
    }

    @Test fun `keys match by word, case and separator blind, with structural qualifiers left alone`() {
        for (k in listOf("name", "customerName", "customer_name", "CUSTOMER-NAME", "receiver_phone", "mobileNumber", "contact_number", "shipping_address", "dropLat", "LONGITUDE", "email", "customer", "delivery_contact", "delivery_otp")) {
            assertTrue(scrubber.matchKey(k) != null, k)
        }
        for (k in listOf("filename", "file_name", "channel_name", "worker_name", "ip_address", "customer_id", "customer_rating", "phone_verified", "address_type", "status", "order_id", "eta_min", "plate", "translation", "isLatest", "contact_preference", "uniqueName")) {
            assertNull(scrubber.matchKey(k), k)
        }
        assertEquals(Category.PHONE, scrubber.matchKey("contact_number"))
        assertEquals(Category.EMAIL, scrubber.matchKey("email_address"))
        assertEquals(Category.NAME, scrubber.matchKey("customerName"))
        assertEquals(Category.OTHER, scrubber.matchKey("delivery_otp"))
    }

    @Test fun `configurable keys replace the defaults`() {
        val custom = Scrubber(Scrubber.Options(keys = listOf("vehicle_number")))
        assertEquals(Category.OTHER, custom.matchKey("vehicleNumber"))
        assertNull(custom.matchKey("name"))
    }

    @Test fun `includeBodies false drops body text but keeps size and content type`() {
        val r = Scrubber(Scrubber.Options(includeBodies = false)).scrub(httpEnv(gandalf, reqBody = """{"lat":1}"""))
        val t = tx(r.envelope)
        assertNull(t.response!!.body!!.text)
        assertNull(t.request.body!!.text)
        assertEquals(gandalf.length.toLong(), t.response!!.body!!.sizeBytes)
        assertEquals("application/json", t.response!!.body!!.contentType)
        assertEquals(2, r.bodiesDropped)
    }

    @Test fun `scrubber off changes nothing, but still honours includeBodies`() {
        val e = httpEnv(gandalf)
        assertSame(e, Scrubber(Scrubber.Options(enabled = false)).scrub(e).envelope)
        val dropped = Scrubber(Scrubber.Options(enabled = false, includeBodies = false)).scrub(e)
        assertNull(tx(dropped.envelope).response!!.body!!.text)
        assertEquals(0, dropped.scrubbed)
    }

    @Test fun `idempotent — a scrubbed event scrubs to itself with zero counts`() {
        val once = scrubber.scrub(httpEnv(gandalf)).envelope
        val twice = scrubber.scrub(once)
        assertEquals(0, twice.scrubbed)
        assertEquals(once, twice.envelope)
    }

    @Test fun `an untouched body stays byte-identical — no reformatting`() {
        val pretty = "{\n  \"ok\": true,\n  \"count\": 3\n}"
        val r = scrubber.scrub(httpEnv(pretty))
        assertEquals(pretty, tx(r.envelope).response!!.body!!.text)
    }

    @Test fun `json nested inside a string inside a json string is scrubbed too`() {
        val inner = """{"phone":"9845012345"}"""
        val outer = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("payload" to kotlinx.serialization.json.JsonPrimitive(inner))))
        val r = scrubber.scrub(httpEnv(outer))
        val text = tx(r.envelope).response!!.body!!.text!!
        assertFalse(text.contains("9845012345"))
        assertEquals(mapOf(Category.PHONE to 1), r.counts)
    }

    @Test fun `non-http kinds — fcm data, db args, generic payloads — and the report`() {
        val fcm = Envelope(
            kind = "fcm", id = "f1", at = 1, endedAt = 1,
            payload = json.encodeToJsonElement(FcmMessage.serializer(), FcmMessage(id = "f1", data = mapOf("customer_name" to "Ravi", "order_id" to "OR-1", "msg" to "call 7022730639"))),
        )
        val db = Envelope(
            kind = "db", id = "d1", at = 1, endedAt = 2,
            payload = json.encodeToJsonElement(DbQuery.serializer(), DbQuery("SELECT * FROM orders WHERE phone = ?", listOf("7022730639", "ASSIGNED"), rows = 1)),
        )
        val app = Envelope(kind = "analytics", id = "a1", at = 1, endedAt = 1, payload = json.parseToJsonElement("""{"title":"order_accepted","sections":[{"label":"params","type":"kv","body":{"customer_name":"Ravi","eta":5}}]}"""))
        val (out, report) = scrubber.scrubAll(listOf(fcm, db, app, httpEnv("""{"ok":true}""")))
        assertEquals(mapOf("fcm" to 2, "db" to 1, "analytics" to 1), report.byKind)
        assertEquals(4, report.total)
        assertEquals(3, report.eventsTouched)
        assertEquals("scrubbed 4 fields: 2 phone numbers, 2 names", report.summary())
        val msg = (TransactionParser().accept(CaptureLines.of(out[0])) as LogEvent.Fcm).msg
        assertEquals(mapOf("customer_name" to M, "order_id" to "OR-1", "msg" to "call $M"), msg.data)
        val q = (TransactionParser().accept(CaptureLines.of(out[1])) as LogEvent.Db).query
        assertEquals(listOf(M, "ASSIGNED"), q.args)
        assertEquals("SELECT * FROM orders WHERE phone = ?", q.sql)
        assertEquals(1, q.rows)
    }

    @Test fun `report summary wording`() {
        assertEquals("nothing to scrub", ScrubReport().summary())
        val r = ScrubReport(mapOf(Category.PHONE to 9, Category.NAME to 6, Category.ADDRESS to 8))
        assertEquals("scrubbed 23 fields: 9 phone numbers, 8 addresses, 6 names", r.summary())
        assertEquals("scrubbed 1 field: 1 email", ScrubReport(mapOf(Category.EMAIL to 1)).summary())
    }

    @Test fun `tokenizer`() {
        assertEquals(listOf("customer", "name"), Scrubber.tokenize("customerName"))
        assertEquals(listOf("drop", "lat"), Scrubber.tokenize("DROP_LAT"))
        assertEquals(listOf("http", "url"), Scrubber.tokenize("HTTPUrl"))
        assertEquals(listOf("address", "line1"), Scrubber.tokenize("address-line1"))
    }
}
