package io.github.siddharthjaswal.logpose.logcat

import io.github.siddharthjaswal.logpose.model.LogEvent
import org.junit.jupiter.api.Assertions.assertEquals
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
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        val LONG = "x".repeat(400)
    }
}
