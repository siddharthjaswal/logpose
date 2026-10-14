package io.github.siddharthjaswal.logpose.desktop.wireless

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A scripted dns-sd: lines are fed in; readLine blocks like a real pipe until a line or a kill. */
class FakeProcess(val command: List<String>) : LiveProcess {
    private val queue = LinkedBlockingQueue<String>()
    @Volatile var killed = false
    fun emit(vararg lines: String) = lines.forEach { queue.put(it) }
    override fun readLine(): String? {
        while (!killed) {
            val l = queue.poll(20, TimeUnit.MILLISECONDS)
            if (l != null) return l
        }
        return null
    }
    override fun isAlive() = !killed
    override fun kill() { killed = true }
}

class FakeRunner(private val script: (FakeProcess) -> Unit = {}) : ProcessRunner {
    val started = mutableListOf<FakeProcess>()
    override fun start(command: List<String>): LiveProcess = FakeProcess(command).also { synchronized(started) { started += it }; script(it) }
    fun all() = synchronized(started) { started.toList() }
}

class BonjourBrowserTest {

    private fun browser(runner: FakeRunner) = BonjourBrowser(runner, "/usr/bin/dns-sd", exists = { true })

    private fun waitFor(timeoutMs: Long = 2_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(cond(), "condition not met in ${timeoutMs}ms")
    }

    @Test fun `browse tracks Add and Rmv, and close kills the process`() {
        val runner = FakeRunner()
        val b = browser(runner)
        val browse = b.browse(AdbOutput.ServiceType.PAIRING)
        val proc = runner.all().single()
        assertEquals(listOf("/usr/bin/dns-sd", "-B", "_adb-tls-pairing._tcp", "local"), proc.command)
        proc.emit(
            "Browsing for _adb-tls-pairing._tcp.local", "DATE: ---Wed 14 Oct 2026---", "11:03:06.300  ...STARTING...",
            "Timestamp     A/R    Flags  if Domain               Service Type         Instance Name",
            "11:03:06.358  Add        2  14 local.               _adb-tls-pairing._tcp. logpose-KECwVKEjnL",
        )
        waitFor { browse.instances() == setOf("logpose-KECwVKEjnL") }
        proc.emit("11:03:09.001  Rmv        0  14 local.               _adb-tls-pairing._tcp. logpose-KECwVKEjnL")
        waitFor { browse.instances().isEmpty() }
        assertEquals(setOf("logpose-KECwVKEjnL"), browse.everSeen())
        assertEquals(1, b.live.size)
        browse.close()
        assertTrue(proc.killed)
        assertTrue(b.live.isEmpty())
    }

    @Test fun `resolve kills dns-sd as soon as the answer and TXT arrive`() {
        val runner = FakeRunner { p ->
            p.emit(
                "Lookup adb-00173359D000017-zABNgD._adb-tls-connect._tcp.local", "DATE: ---Wed 14 Oct 2026---", "11:08:26.921  ...STARTING...",
                "11:08:26.922  adb-00173359D000017-zABNgD._adb-tls-connect._tcp.local. can be reached at Android.local.:41983 (interface 14)",
                " api=36.0 name=A059P v=1",
            )
        }
        val b = browser(runner)
        val r = b.resolve("adb-00173359D000017-zABNgD", AdbOutput.ServiceType.CONNECT, 2_000)
        assertEquals("Android.local.", r!!.host)
        assertEquals(41983, r.port)
        assertEquals("A059P", r.txt["name"])
        assertEquals(listOf("/usr/bin/dns-sd", "-L", "adb-00173359D000017-zABNgD", "_adb-tls-connect._tcp", "local"), runner.all().single().command)
        assertTrue(runner.all().single().killed)
        assertTrue(b.live.isEmpty())
    }

    @Test fun `resolve that never answers times out and still kills dns-sd`() {
        val runner = FakeRunner { p -> p.emit("Lookup x._adb-tls-pairing._tcp.local") }
        val b = browser(runner)
        val started = System.currentTimeMillis()
        assertNull(b.resolve("x", AdbOutput.ServiceType.PAIRING, 300))
        assertTrue(System.currentTimeMillis() - started < 2_000)
        assertTrue(runner.all().single().killed)
        assertTrue(b.live.isEmpty())
    }

    @Test fun `address prefers IPv4, keeps IPv6 when that's all there is`() {
        val v4 = FakeRunner { p ->
            p.emit(
                "Timestamp     A/R  Flags         IF  Hostname                               Address                                      TTL",
                "11:08:41.130  Add  40000003      14  Android_63CXP52F.local.                FE80:0000:0000:0000:7896:B4FF:FE10:4B94%en0  120",
                "11:08:41.130  Add  40000002      14  Android_63CXP52F.local.                192.168.1.5                                  120",
            )
        }
        assertEquals("192.168.1.5", browser(v4).address("Android_63CXP52F.local.", 2_000))
        assertEquals(listOf("/usr/bin/dns-sd", "-G", "v4v6", "Android_63CXP52F.local."), v4.all().single().command)

        val v6only = FakeRunner { p -> p.emit("11:08:44.139  Add  40000002      14  Android.local.                         FE80:0000:0000:0000:3478:DBFF:FE83:C15A%en0  120") }
        assertEquals("fe80:0000:0000:0000:3478:dbff:fe83:c15a%en0", browser(v6only).address("Android.local.", 1_500))
        assertTrue(v6only.all().single().killed)
    }

    @Test fun `session resolves a seen instance in the background, close kills browses`() {
        val runner = FakeRunner { p ->
            when (p.command[1]) {
                "-B" -> if (p.command[2] == "_adb-tls-pairing._tcp") p.emit("11:03:06.358  Add        2  14 local.               _adb-tls-pairing._tcp. logpose-KECwVKEjnL")
                "-L" -> p.emit(
                    "11:03:06.400  logpose-KECwVKEjnL._adb-tls-pairing._tcp.local. can be reached at Android_10IY7ZSZ.local.:42667 (interface 14)",
                    " v=1",
                )
                "-G" -> p.emit("11:03:06.450  Add  40000002      14  Android_10IY7ZSZ.local.                192.168.1.10                                 120")
            }
        }
        val b = browser(runner)
        val s = DnsSdSession(b, setOf(AdbOutput.ServiceType.PAIRING, AdbOutput.ServiceType.CONNECT))
        waitFor { "logpose-KECwVKEjnL" in s.seen(AdbOutput.ServiceType.PAIRING) }
        assertNull(s.endpoint("someone-else", AdbOutput.ServiceType.PAIRING)) // never seen: not resolved at all
        waitFor { s.endpoint("logpose-KECwVKEjnL", AdbOutput.ServiceType.PAIRING) != null }
        val e = s.endpoint("logpose-KECwVKEjnL", AdbOutput.ServiceType.PAIRING)!!
        assertEquals("192.168.1.10:42667", e.address)
        assertEquals("Android_10IY7ZSZ.local.", e.host)
        // One-shots are gone already; only the two browses are alive until close.
        waitFor { b.live.size == 2 }
        s.close()
        assertTrue(runner.all().all { it.killed }, "every dns-sd killed")
        assertTrue(b.live.isEmpty())
        assertFalse(s.available)
    }

    @Test fun `closeAll kills whatever is still running`() {
        val runner = FakeRunner()
        val b = browser(runner)
        b.browse(AdbOutput.ServiceType.PAIRING)
        b.browse(AdbOutput.ServiceType.CONNECT)
        assertEquals(2, b.live.size)
        b.closeAll()
        assertTrue(runner.all().all { it.killed })
        assertTrue(b.live.isEmpty())
    }

    @Test fun `no dns-sd means no processes and an unavailable session`() {
        val runner = FakeRunner()
        val b = BonjourBrowser(runner, "/nope/dns-sd", exists = { false })
        val s = DnsSdSession(b, setOf(AdbOutput.ServiceType.PAIRING))
        assertFalse(s.available)
        assertNull(b.resolve("x", AdbOutput.ServiceType.PAIRING, 100))
        assertTrue(runner.all().isEmpty())
        s.close()
    }
}
