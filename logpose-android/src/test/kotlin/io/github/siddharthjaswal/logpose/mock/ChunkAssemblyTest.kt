package io.github.siddharthjaswal.logpose.mock

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Reassembly is where a two-command channel goes wrong, so it's tested apart from the receiver:
 * a manifest `BroadcastReceiver` needs a real `Context`, while the joining logic needs nothing.
 */
class ChunkAssemblyTest {

    @Before fun setUp() = ChunkAssembly.reset()
    @After fun tearDown() = ChunkAssembly.reset()

    @Test fun `a single-slice command assembles immediately`() {
        assertEquals("whole", ChunkAssembly.add("rules", key = 1, seq = 0, total = 1, payload = "whole"))
    }

    @Test fun `slices join in seq order, not arrival order`() {
        assertNull(ChunkAssembly.add("rules", 1, seq = 2, total = 3, payload = "c"))
        assertNull(ChunkAssembly.add("rules", 1, seq = 0, total = 3, payload = "a"))
        assertEquals("abc", ChunkAssembly.add("rules", 1, seq = 1, total = 3, payload = "b"))
    }

    @Test fun `a push landing mid-rule-set corrupts neither`() {
        // The whole reason each command gets its own pending map: before that, an injected push
        // arriving between two rule-set slices overwrote them.
        assertNull(ChunkAssembly.add("rules", 7, seq = 0, total = 3, payload = "r0"))
        assertNull(ChunkAssembly.add("push", -1, seq = 0, total = 2, payload = "p0"))
        assertNull(ChunkAssembly.add("rules", 7, seq = 1, total = 3, payload = "r1"))
        assertEquals("p0p1", ChunkAssembly.add("push", -1, seq = 1, total = 2, payload = "p1"))
        assertEquals("r0r1r2", ChunkAssembly.add("rules", 7, seq = 2, total = 3, payload = "r2"))
    }

    @Test fun `a newer revision supersedes a half-assembled older one`() {
        assertNull(ChunkAssembly.add("rules", 1, seq = 0, total = 2, payload = "old0"))
        assertNull(ChunkAssembly.add("rules", 2, seq = 0, total = 2, payload = "new0"))
        assertEquals("new0new1", ChunkAssembly.add("rules", 2, seq = 1, total = 2, payload = "new1"))
    }

    @Test fun `a fresh seq 0 opens a new message even when the key repeats`() {
        // Pushes carry no revision, so back-to-back injections share a key; a new `seq 0` is what
        // tells them apart, and an abandoned half-message must not bleed into the next one.
        assertNull(ChunkAssembly.add("push", -1, seq = 0, total = 3, payload = "abandoned"))
        assertEquals("only", ChunkAssembly.add("push", -1, seq = 0, total = 1, payload = "only"))
    }

    @Test fun `a completed message leaves nothing behind for the next one`() {
        assertEquals("first", ChunkAssembly.add("push", -1, 0, 1, "first"))
        assertEquals("second", ChunkAssembly.add("push", -1, 0, 1, "second"))
    }
}
