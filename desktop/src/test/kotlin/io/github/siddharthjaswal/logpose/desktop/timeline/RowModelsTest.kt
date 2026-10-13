package io.github.siddharthjaswal.logpose.desktop.timeline

import io.github.siddharthjaswal.logpose.desktop.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RowModelsTest {
    private val rows = Fixtures.store().snapshot().associate { it.id to RowModels.of(it) }

    @Test fun `http rows carry status semantics, pending and mock`() {
        val notFound = rows.getValue("h3") as RowModel.Http
        assertEquals("404", notFound.status)
        val pending = rows.getValue("h7") as RowModel.Http
        assertTrue(pending.pending)
        assertEquals("…", pending.duration)
        assertEquals("ERR", (rows.getValue("h6") as RowModel.Http).status)
        assertTrue((rows.getValue("h5") as RowModel.Http).mocked)
        assertTrue((rows.getValue("h1") as RowModel.Http).read)
        assertTrue(!(rows.getValue("h2") as RowModel.Http).read)
    }

    @Test fun `fcm rows label and summarize like the plugin`() {
        val inj = rows.getValue("f1") as RowModel.Fcm
        assertEquals("NOTIF", inj.label); assertEquals("New order nearby", inj.summary); assertTrue(inj.injected)
        assertEquals("order_updates", (rows.getValue("f2") as RowModel.Fcm).summary)
    }

    @Test fun `other kinds come from core's RowContent`() {
        assertTrue(rows.getValue("d1") is RowModel.Db)
        assertTrue(rows.getValue("w2") is RowModel.Worker)
        assertEquals("OrderDetailFragment", (rows.getValue("a1") as RowModel.Generic).content.fact)
    }
}
