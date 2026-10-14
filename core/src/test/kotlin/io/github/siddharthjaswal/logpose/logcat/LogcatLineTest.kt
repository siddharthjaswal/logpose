package io.github.siddharthjaswal.logpose.logcat

import io.github.siddharthjaswal.logpose.model.LogEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** `-v brief` lines, exactly as the gandalf device printed them on the live capture. */
class LogcatLineTest {

    @Test fun `a brief line splits into pid and payload`() {
        val line = LogcatLine.parse("""I/LogPose (27651): {"v":1,"kind":"analytics"}""")
        assertEquals(27651, line.pid)
        assertEquals("""{"v":1,"kind":"analytics"}""", line.message)
    }

    @Test fun `a five-digit-padded pid and a short tag still parse`() {
        assertEquals(812, LogcatLine.parse("D/LP      (  812): {}").pid)
    }

    @Test fun `a raw payload passes through with no pid`() {
        val line = LogcatLine.parse("""{"kind":"hello"}""")
        assertNull(line.pid)
        assertEquals("""{"kind":"hello"}""", line.message)
    }

    @Test fun `logcat's own banner is left alone`() {
        val banner = "--------- beginning of main"
        assertEquals(LogcatLine(null, banner), LogcatLine.parse(banner))
    }

    @Test fun `the parser strips the prefix and reports the pid`() {
        val parser = TransactionParser()
        var hello: ControlMessage.DeviceHello? = null
        parser.onControl = { if (it is ControlMessage.DeviceHello) hello = it }

        val event = parser.accept(
            """I/LogPose (27651): {"v":1,"kind":"analytics","id":"e1","at":5,"endedAt":5,"payload":{"title":"BTH_HIDDEN"}}"""
        )
        assertTrue(event is LogEvent.Generic)
        assertEquals(27651, parser.lastPid)

        parser.accept("""I/LogPose (27651): {"kind":"hello","pkg":"in.shadowfax.gandalf.debug","libVersion":"1.7.4","processId":"ab12cd34"}""")
        assertEquals(27651, hello?.pid)
        assertEquals("ab12cd34", hello?.hello?.processId)

        parser.accept("""{"v":1,"kind":"analytics","id":"e2","at":5,"payload":{"title":"x"}}""")
        assertNull(parser.lastPid)
    }

    @Test fun `chunks carry their prefix too and reassemble`() {
        val parser = TransactionParser()
        val full = """{"v":1,"kind":"analytics","id":"e1","at":5,"endedAt":5,"payload":{"title":"BTH_HIDDEN"}}"""
        val a = full.substring(0, 30).replace("\"", "\\\"")
        val b = full.substring(30).replace("\"", "\\\"")
        assertNull(parser.accept("""I/LogPose (42): {"id":"c1","seq":0,"total":2,"payload":"$a"}"""))
        val event = parser.accept("""I/LogPose (42): {"id":"c1","seq":1,"total":2,"payload":"$b"}""")
        assertEquals("e1", event?.id)
        assertEquals(42, parser.lastPid)
    }

    @Test fun `a process name keeps its suffix, errors and exited processes are null`() {
        assertEquals("com.acme:service", ProcessNames.fromCmdline("com.acme:service\u0000"))
        assertEquals("in.shadowfax.gandalf.debug", ProcessNames.fromCmdline("in.shadowfax.gandalf.debug\u0000"))
        assertNull(ProcessNames.fromCmdline(""))
        assertNull(ProcessNames.fromCmdline("cat: /proc/9/cmdline: No such file or directory"))
        assertNull(ProcessNames.fromCmdline(null))
    }
}
