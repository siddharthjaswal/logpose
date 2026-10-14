package io.github.siddharthjaswal.logpose.mock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class HelloRequesterTest {

    /** Runs a scheduled task immediately, on the caller's thread. */
    private class Immediate : ScheduledThreadPoolExecutor(1) {
        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            command.run()
            return super.schedule({}, 0, TimeUnit.MILLISECONDS)
        }
    }

    @Test fun `the package comes from argv0, minus a process suffix`() {
        assertEquals("in.shadowfax.gandalf.debug", HelloRequester.packageFromCmdline("in.shadowfax.gandalf.debug\u0000"))
        assertEquals("com.acme", HelloRequester.packageFromCmdline("com.acme:sync\u0000--flag\u0000"))
        assertNull(HelloRequester.packageFromCmdline(""))                    // the process has exited
        assertNull(HelloRequester.packageFromCmdline("/system/bin/surfaceflinger\u0000"))
        assertNull(HelloRequester.packageFromCmdline("cat: /proc/1/cmdline: Permission denied"))
    }

    @Test fun `an unclaimed pid gets one hello request, addressed to its package`() {
        val ran = ArrayList<List<String>>()
        val requester = HelloRequester(
            deviceSerial = { "SER1" },
            stillNeeded = { true },
            run = { cmd -> ran += cmd; if ("cmdline" in cmd.last()) "in.shadowfax.gandalf.debug\u0000" else "Broadcast completed" },
            scheduler = Immediate(),
        )
        requester.onUnclaimedPid(27651)
        requester.onUnclaimedPid(27651)

        assertEquals(2, ran.size)
        assertTrue(ran[0].containsAll(listOf("-s", "SER1", "cat", "/proc/27651/cmdline")))
        val broadcast = ran[1]
        assertTrue(broadcast.containsAll(listOf("am", "broadcast", "--es", "cmd", "hello")))
        assertTrue("in.shadowfax.gandalf.debug/${BroadcastCommand.RECEIVER}" in broadcast)
        // No payload: what keeps the request invisible to libraries older than 1.7.4.
        assertTrue("payload" !in broadcast)
    }

    @Test fun `a hello that arrives during the grace period cancels the request`() {
        val ran = ArrayList<List<String>>()
        val requester = HelloRequester({ null }, stillNeeded = { false }, run = { ran += it; "" }, scheduler = Immediate())
        requester.onUnclaimedPid(5)
        assertTrue(ran.isEmpty())
    }

    @Test fun `a pid that is not an app process is never broadcast to`() {
        val ran = ArrayList<List<String>>()
        val requester = HelloRequester({ null }, { true }, run = { ran += it; "" }, scheduler = Immediate())
        requester.onUnclaimedPid(5)
        assertEquals(1, ran.size) // the cmdline read, nothing else
    }

    @Test fun `reset lets a new capture ask again`() {
        var count = 0
        val requester = HelloRequester({ null }, { true }, run = { count++; null }, scheduler = Immediate())
        requester.onUnclaimedPid(5)
        requester.reset()
        requester.onUnclaimedPid(5)
        assertEquals(2, count)
    }
}
