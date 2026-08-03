package io.github.siddharthjaswal.logpose.export

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The bounded on-device ring behind headless export: order preserved, oldest dropped past cap. */
class ExportBufferTest {

    @After fun tearDown() {
        ExportBuffer.capacity = 2000
        ExportBuffer.clear()
    }

    @Test fun `keeps lines oldest-first`() {
        ExportBuffer.clear()
        ExportBuffer.record("a")
        ExportBuffer.record("b")
        assertEquals(listOf("a", "b"), ExportBuffer.snapshot())
    }

    @Test fun `drops the oldest once over capacity`() {
        ExportBuffer.clear()
        ExportBuffer.capacity = 2
        ExportBuffer.record("a")
        ExportBuffer.record("b")
        ExportBuffer.record("c")
        assertEquals(listOf("b", "c"), ExportBuffer.snapshot())
        assertEquals(2, ExportBuffer.size())
    }

    @Test fun `clear empties the buffer`() {
        ExportBuffer.clear()
        ExportBuffer.record("x")
        ExportBuffer.clear()
        assertEquals(emptyList<String>(), ExportBuffer.snapshot())
    }
}
