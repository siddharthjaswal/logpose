package io.github.siddharthjaswal.logpose.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileKeyValueStoreTest {

    @TempDir lateinit var dir: File

    private fun store() = FileKeyValueStore.forProject(dir)

    @Test
    fun `values survive a reopen`() {
        store().set("logpose.mcp.token", "deadbeef")
        assertEquals("deadbeef", store().get("logpose.mcp.token"))
    }

    @Test
    fun `it writes where the IDE's scenarios already live`() {
        store().set("k", "v")
        assertTrue(File(dir, ".logpose/daemon.properties").isFile)
    }

    @Test
    fun `a null value removes the key`() {
        val s = store()
        s.set("k", "v")
        s.set("k", null)
        assertNull(s.get("k"))
        assertNull(store().get("k"))
    }

    @Test
    fun `ints and booleans round-trip, and a default stores nothing`() {
        val s: KeyValueStore = store()
        s.setInt("rev", 7, 0)
        s.setBoolean("on", false, true)
        assertEquals(7, store().getInt("rev", 0))
        assertFalse(store().getBoolean("on", true))

        // The PropertiesComponent rule core's controllers were written against: a value equal to
        // the default is unset, not written — so a later change of default takes effect.
        s.setInt("rev", 0, 0)
        assertNull(store().get("rev"))
    }

    @Test
    fun `a missing file reads as empty rather than throwing`() {
        assertNull(FileKeyValueStore(File(dir, "nope/never.properties")).get("k"))
    }

    @Test
    fun `a garbage file does not stop the daemon from starting`() {
        val file = File(dir, ".logpose/daemon.properties")
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(0, -1, 0, -1))
        // Constructing must not throw; the store simply starts from whatever loaded.
        val s = FileKeyValueStore(file)
        s.set("k", "v")
        assertEquals("v", FileKeyValueStore(file).get("k"))
    }

    @Test
    fun `the shared correlation store is its own file, separate from daemon settings`() {
        FileKeyValueStore.sharedCorrelation(dir).set("logpose.correlation.keys", "order_id|1|4|0")
        assertTrue(File(dir, ".logpose/correlation.properties").isFile)
        // It must not be the daemon's own settings file — those stay private.
        assertNull(FileKeyValueStore.forProject(dir).get("logpose.correlation.keys"))
    }

    @Test
    fun `two halves reading the same project dir agree on the correlation vocabulary`() {
        // What the plugin writes (one FileKeyValueStore.sharedCorrelation over the project dir)…
        val ide = FileKeyValueStore.sharedCorrelation(dir)
        CorrelationSettings.setKeys(ide, listOf(io.github.siddharthjaswal.logpose.analysis.CorrelationKey("order_id")))
        // …is what a daemon opening the same dir reads back — the whole point of sharing.
        val daemon = FileKeyValueStore.sharedCorrelation(dir)
        assertEquals(listOf("order_id"), CorrelationSettings.keys(daemon).map { it.name })
        assertTrue(CorrelationSettings.configured(daemon))
    }

    @Test
    fun `concurrent writers never leave a truncated or unreadable file`() {
        // The atomicity claim: writes go via a temp file and a rename, so a reader (or the next
        // process) always sees a whole properties file, never half of one.
        val s = store()
        val threads = 8
        val perThread = 40
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> s.set("key-$t-$i", "value-$t-$i") }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        val reread = store()
        for (t in 0 until threads) for (i in 0 until perThread) {
            assertEquals("value-$t-$i", reread.get("key-$t-$i"))
        }
        // No temp files left behind by the rename.
        assertEquals(
            emptyList<String>(),
            File(dir, ".logpose").listFiles()!!.map { it.name }.filter { it != "daemon.properties" },
        )
    }
}
