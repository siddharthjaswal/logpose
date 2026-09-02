package io.github.siddharthjaswal.logpose.logcat

import io.github.siddharthjaswal.logpose.model.LogEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TransactionParserTest {

    private val parser = TransactionParser()

    @Test
    fun `decodes an http transaction line as an Http event`() {
        val line = """{"id":"ab12","request":{"method":"GET","url":"https://x/y"},"response":{"code":200}}"""
        val event = parser.accept(line)
        assertTrue(event is LogEvent.Http)
        val http = event as LogEvent.Http
        assertEquals("ab12", http.tx.id)
        assertEquals("GET", http.tx.request.method)
        assertEquals(200, http.tx.response?.code)
    }

    @Test
    fun `decodes an fcm message line as an Fcm event`() {
        val line = """
            {"kind":"fcm","id":"m1","event":"message","from":"/topics/news",
             "notification":{"title":"Hi","body":"There"},"data":{"deep_link":"app://x"}}
        """.trimIndent().replace("\n", "")
        val event = parser.accept(line)
        assertTrue(event is LogEvent.Fcm)
        val fcm = event as LogEvent.Fcm
        assertEquals("m1", fcm.msg.id)
        assertEquals("message", fcm.msg.event)
        assertEquals("Hi", fcm.msg.notification?.title)
        assertEquals("app://x", fcm.msg.data["deep_link"])
    }

    @Test
    fun `decodes an fcm token line`() {
        val line = """{"kind":"fcm","id":"t1","event":"token","token":"fabc123"}"""
        val event = parser.accept(line)
        assertTrue(event is LogEvent.Fcm)
        assertEquals("token", (event as LogEvent.Fcm).msg.event)
        assertEquals("fabc123", event.msg.token)
    }

    @Test
    fun `reassembles a chunked fcm payload into an Fcm event`() {
        val payload = """{"kind":"fcm","id":"big","event":"message","data":{"k":"$LONG"}}"""
        val half = payload.length / 2
        val c0 = """{"id":"big","seq":0,"total":2,"payload":${quote(payload.substring(0, half))}}"""
        val c1 = """{"id":"big","seq":1,"total":2,"payload":${quote(payload.substring(half))}}"""

        assertNull(parser.accept(c0)) // incomplete → nothing yet
        val event = parser.accept(c1)
        assertTrue(event is LogEvent.Fcm)
        assertEquals(LONG, (event as LogEvent.Fcm).msg.data["k"])
    }

    @Test
    fun `non-json and unknown lines are ignored`() {
        assertNull(parser.accept("not json"))
        assertNull(parser.accept(""))
    }

    @Test
    fun `mocked transaction line carries the mocked flag`() {
        val line = """{"id":"m1","request":{"method":"GET","url":"https://x/y"},"response":{"code":503},"mocked":true}"""
        val event = parser.accept(line)
        assertTrue(event is LogEvent.Http)
        assertTrue((event as LogEvent.Http).tx.mocked)
    }

    @Test
    fun `hello line dispatches a control message and is not a row`() {
        val control = mutableListOf<ControlMessage>()
        parser.onControl = { control.add(it) }
        val event = parser.accept("""{"kind":"hello","pkg":"com.x","libVersion":"1.1.0","mockRevision":3}""")
        assertNull(event, "control messages are not timeline rows")
        assertTrue(control.single() is ControlMessage.DeviceHello)
        assertEquals("com.x", (control.single() as ControlMessage.DeviceHello).hello.pkg)
        assertEquals(3, (control.single() as ControlMessage.DeviceHello).hello.mockRevision)
    }

    @Test
    fun `mock_ack line dispatches a control message with hit counts`() {
        val control = mutableListOf<ControlMessage>()
        parser.onControl = { control.add(it) }
        val event = parser.accept("""{"kind":"mock_ack","pkg":"com.x","revision":5,"hits":{"a":2}}""")
        assertNull(event)
        val ack = (control.single() as ControlMessage.MockApplied).ack
        assertEquals(5, ack.revision)
        assertEquals(2, ack.hits["a"])
        assertEquals(0, ack.ruleCount, "absent ruleCount (pre-1.6.0 library) defaults to 0")
    }

    @Test
    fun `mock_ack carries the rule count the sync check compares against`() {
        val control = mutableListOf<ControlMessage>()
        parser.onControl = { control.add(it) }
        parser.accept("""{"kind":"mock_ack","pkg":"com.x","revision":5,"ruleCount":3,"hits":{}}""")
        assertEquals(3, (control.single() as ControlMessage.MockApplied).ack.ruleCount)
    }

    @Test
    fun `push_ack line dispatches a control message and is not a row`() {
        val control = mutableListOf<ControlMessage>()
        parser.onControl = { control.add(it) }
        val event = parser.accept(
            """{"kind":"push_ack","pkg":"com.x","id":"inj-1","delivered":"none","error":"no handler"}"""
        )
        assertNull(event, "control messages are not timeline rows")
        val ack = (control.single() as ControlMessage.PushDelivered).ack
        assertEquals("inj-1", ack.id)
        assertEquals("com.x", ack.pkg)
        assertEquals("none", ack.delivered)
        assertEquals("no handler", ack.error)
    }

    @Test
    fun `a chunked push_ack is reassembled before dispatch`() {
        val control = mutableListOf<ControlMessage>()
        parser.onControl = { control.add(it) }
        val payload =
            """{"kind":"push_ack","pkg":"com.x","id":"inj-2","delivered":"service","error":"$LONG"}"""
        val half = payload.length / 2
        assertNull(parser.accept("""{"id":"inj-2","seq":0,"total":2,"payload":${quote(payload.substring(0, half))}}"""))
        assertNull(parser.accept("""{"id":"inj-2","seq":1,"total":2,"payload":${quote(payload.substring(half))}}"""))
        val ack = (control.single() as ControlMessage.PushDelivered).ack
        assertEquals("service", ack.delivered)
        assertEquals(LONG, ack.error)
    }

    // ---- Envelopes (logpose-android >= 1.3.0) ---------------------------------------------

    @Test
    fun `decodes an enveloped http transaction`() {
        val line = """
            {"v":1,"kind":"http","id":"e1","at":1000,"endedAt":1120,
             "payload":{"id":"e1","request":{"method":"POST","url":"https://x/y"},"response":{"code":201}}}
        """.trimIndent().replace("\n", "")
        val event = parser.accept(line)
        assertTrue(event is LogEvent.Http)
        val http = event as LogEvent.Http
        assertEquals("POST", http.tx.request.method)
        assertEquals(201, http.tx.response?.code)
        assertEquals(120, http.durationMillis, "endedAt - at is the span duration")
    }

    @Test
    fun `an http envelope with no endedAt is still in flight`() {
        val line = """
            {"v":1,"kind":"http","id":"p1","at":1000,
             "payload":{"id":"p1","request":{"method":"GET","url":"https://x/y"}}}
        """.trimIndent().replace("\n", "")
        val event = parser.accept(line)!!
        assertTrue(event.isOpen, "a null endedAt means the span is still open")
        assertNull(event.durationMillis)
    }

    @Test
    fun `decodes a self-describing app event`() {
        val line = """
            {"v":1,"kind":"event","id":"g1","at":50,"endedAt":64,"traceId":"tr1",
             "payload":{"title":"UserDao.insert","subtitle":"users (3 rows)",
                        "badges":[{"text":"DB","tone":"info"}],
                        "sections":[{"label":"SQL","type":"code","body":"INSERT INTO users"}]}}
        """.trimIndent().replace("\n", "")
        val event = parser.accept(line)
        assertTrue(event is LogEvent.Generic)
        val generic = event as LogEvent.Generic
        assertEquals("UserDao.insert", generic.event?.title)
        assertEquals("DB", generic.event?.badges?.single()?.text)
        assertEquals("SQL", generic.event?.sections?.single()?.label)
        assertEquals("tr1", generic.traceId)
        assertEquals(14, generic.durationMillis)
    }

    @Test
    fun `an unknown kind still becomes a row instead of being dropped`() {
        // The whole point of the envelope: a kind this plugin has never heard of is renderable.
        val line = """{"v":1,"kind":"acme.telemetry","id":"u1","at":10,"payload":{"anything":[1,2,3]}}"""
        val event = parser.accept(line)
        assertTrue(event is LogEvent.Generic)
        val generic = event as LogEvent.Generic
        assertEquals("acme.telemetry", generic.kind)
        assertNull(generic.event, "payload wasn't self-describing, so there's no presentation")
        assertTrue(generic.envelope.payload.toString().contains("anything"), "raw payload is kept")
    }

    @Test
    fun `reassembles a chunked envelope`() {
        val payload = """{"v":1,"kind":"event","id":"big","at":1,"payload":{"title":"T","subtitle":"$LONG"}}"""
        val half = payload.length / 2
        val c0 = """{"id":"big","seq":0,"total":2,"payload":${quote(payload.substring(0, half))}}"""
        val c1 = """{"id":"big","seq":1,"total":2,"payload":${quote(payload.substring(half))}}"""

        assertNull(parser.accept(c0))
        val event = parser.accept(c1)
        assertTrue(event is LogEvent.Generic)
        assertEquals(LONG, (event as LogEvent.Generic).event?.subtitle)
    }

    @Test
    fun `legacy payloads are flagged so the panel can prompt for a library upgrade`() {
        assertFalse(parser.sawLegacyPayload)
        parser.accept("""{"v":1,"kind":"http","id":"e1","at":1,"payload":{"id":"e1","request":{"method":"GET","url":"u"}}}""")
        assertFalse(parser.sawLegacyPayload, "an envelope is not legacy")

        parser.accept("""{"id":"old","request":{"method":"GET","url":"https://x/y"}}""")
        assertTrue(parser.sawLegacyPayload)
    }

    @Test
    fun `a legacy transaction is wrapped in an envelope so downstream sees one shape`() {
        val event = parser.accept(
            """{"id":"old","startedAtMillis":500,"durationMillis":40,"request":{"method":"GET","url":"https://x/y"}}"""
        )!!
        assertEquals("http", event.kind)
        assertEquals(500, event.timestampMillis)
        assertEquals(40, event.durationMillis)
    }

    @Test
    fun `a worker payload carries the queue and run instants the library stamps on it`() {
        // Captured verbatim from library v1.7.2 — one workId through enqueued → running →
        // succeeded, terminal line. It is the emission that used to lose both instants to the
        // in-place row update, so it is the one worth pinning against a literal string.
        val event = parser.accept(
            """{"v":1,"kind":"worker","id":"3f2b9c14-work","at":1788019859821,"endedAt":1788019860080,""" +
                """"payload":{"worker":"SyncWorker","state":"succeeded","workId":"3f2b9c14-work",""" +
                """"uniqueName":"nightly-sync","runAttempt":0,"tags":["com.app.sync.SyncWorker","nightly"],""" +
                """"inputData":{"since":"2026-08-28"},"outputData":{"synced":"42"},""" +
                """"enqueuedAtMillis":1788019859821,"runStartedAtMillis":1788019859994,"replayedAtAttach":false}}"""
        )!!
        val work = (event as LogEvent.Worker).work
        assertEquals(1788019859821, work.enqueuedAtMillis)
        assertEquals(1788019859994, work.runStartedAtMillis)
        // Queue 173ms + run 86ms, and the span the row already showed is unchanged at 259ms.
        assertEquals(173, work.runStartedAtMillis!! - work.enqueuedAtMillis!!)
        assertEquals(86, event.envelope.endedAt!! - work.runStartedAtMillis!!)
        assertEquals(259, event.durationMillis)
    }

    @Test
    fun `a worker payload from an older library decodes to null rather than zero`() {
        // `explicitNulls = false` means the library omits the keys entirely when it observed no
        // transition — and every library before 1.7.2 omits them always. Null must survive as
        // null: a 0 here would render as `queued 0ms`, the exact fabrication §6 refused.
        val event = parser.accept(
            """{"v":1,"kind":"worker","id":"w9","at":1000,"endedAt":1400,""" +
                """"payload":{"worker":"SyncWorker","state":"succeeded","workId":"w9","runAttempt":1}}"""
        )!!
        val work = (event as LogEvent.Worker).work
        assertNull(work.enqueuedAtMillis)
        assertNull(work.runStartedAtMillis)
    }

    @Test
    fun `an unknown key in a worker payload does not lose the row`() {
        // The forward half of the compat rule: a newer library may add fields this plugin has
        // never heard of, and the row must still decode.
        val event = parser.accept(
            """{"v":1,"kind":"worker","id":"w10","at":1000,""" +
                """"payload":{"worker":"SyncWorker","state":"running","runStartedAtMillis":1200,""" +
                """"somethingNewerShipped":{"nested":true}}}"""
        )!!
        val work = (event as LogEvent.Worker).work
        assertEquals("SyncWorker", work.worker)
        assertEquals(1200, work.runStartedAtMillis)
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        val LONG = "x".repeat(400)
    }
}
