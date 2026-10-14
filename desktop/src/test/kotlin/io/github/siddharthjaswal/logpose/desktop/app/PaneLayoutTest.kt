package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaneLayoutTest {

    @Test fun `encode and decode round-trip every field`() {
        val l = PaneLayout(detailWidth = 612, waterfallWidth = 700, mocksHeight = 120, detailCollapsed = true, mocksCollapsed = true)
        assertEquals("v1;d=612;w=700;m=120;c=dm", l.encode())
        assertEquals(l, PaneLayout.decode(l.encode()))
        assertEquals(PaneLayout(timelineCollapsed = true), PaneLayout.decode(PaneLayout(timelineCollapsed = true).encode()))
    }

    @Test fun `decode tolerates missing, garbage and out-of-range values`() {
        assertEquals(PaneLayout(), PaneLayout.decode(null))
        assertEquals(PaneLayout(), PaneLayout.decode("v2;whatever"))
        assertEquals(PaneLayout(), PaneLayout.decode("nonsense"))
        assertEquals(PaneLayout(detailWidth = 500), PaneLayout.decode("v1;d=500;w=abc"))
        val clamped = PaneLayout.decode("v1;d=10;w=99999;m=-4;c=")
        assertEquals(PaneLayout.MIN_DETAIL, clamped.detailWidth)
        assertEquals(PaneLayout.MAX_INSPECTOR, clamped.waterfallWidth)
        assertEquals(PaneLayout.MIN_MOCKS, clamped.mocksHeight)
        // Both collapse flags can't hold at once: timeline-only wins.
        val both = PaneLayout.decode("v1;c=dt")
        assertTrue(both.detailCollapsed)
        assertFalse(both.timelineCollapsed)
    }

    @Test fun `the collapse toggles are exclusive`() {
        val only = PaneLayout().toggleTimelineOnly()
        assertTrue(only.detailCollapsed)
        val detail = only.toggleDetailOnly()
        assertTrue(detail.timelineCollapsed)
        assertFalse(detail.detailCollapsed)
        assertEquals(PaneLayout(), detail.toggleDetailOnly())
        assertTrue(PaneLayout().toggleMocks().mocksCollapsed)
    }

    @Test fun `resolve shares the width, never squeezing the list below its minimum`() {
        val r = PaneLayout().resolve(1280, Inspector.DETAIL)
        assertEquals(PaneLayout.DEFAULT_DETAIL, r.inspectorWidth)
        assertEquals(1280 - PaneLayout.DEFAULT_DETAIL - PaneLayout.SPLITTER, r.listWidth)
        assertFalse(r.overlay)
        // 900 px: the detail keeps its width while the list keeps its minimum.
        val r900 = PaneLayout(detailWidth = 700).resolve(900, Inspector.DETAIL)
        assertEquals(900 - PaneLayout.MIN_LIST - PaneLayout.SPLITTER, r900.inspectorWidth)
        assertEquals(PaneLayout.MIN_LIST, r900.listWidth)
        // The waterfall remembers its own width.
        assertEquals(PaneLayout.DEFAULT_WATERFALL, PaneLayout().resolve(1600, Inspector.WATERFALL).inspectorWidth)
    }

    @Test fun `a window too narrow for both overlays the inspector`() {
        val r = PaneLayout().resolve(760, Inspector.DETAIL)
        assertTrue(r.overlay)
        assertTrue(r.showList && r.showInspector)
        assertEquals(PaneLayout.DEFAULT_DETAIL, r.inspectorWidth)
        assertEquals(760 - PaneLayout.OVERLAY_GUTTER, PaneLayout(detailWidth = 1000).resolve(760, Inspector.DETAIL).inspectorWidth)
    }

    @Test fun `collapsing never hides the only thing there is`() {
        assertEquals(PaneLayout.Resolved(true, false, 1000, 0, false), PaneLayout(detailCollapsed = true).resolve(1000, Inspector.DETAIL))
        assertEquals(PaneLayout.Resolved(false, true, 0, 1000, false), PaneLayout(timelineCollapsed = true).resolve(1000, Inspector.DETAIL))
        // Detail-only with nothing selected shows the list.
        assertEquals(PaneLayout.Resolved(true, false, 1000, 0, false), PaneLayout(timelineCollapsed = true).resolve(1000, Inspector.NONE))
    }

    @Test fun `dragging clamps to the pane's minimum and the list's minimum, double-click resets`() {
        val l = PaneLayout()
        assertEquals(PaneLayout.MIN_DETAIL, l.withInspectorWidth(Inspector.DETAIL, 100, 1400).detailWidth)
        assertEquals(1400 - PaneLayout.MIN_LIST - PaneLayout.SPLITTER, l.withInspectorWidth(Inspector.DETAIL, 2000, 1400).detailWidth)
        assertEquals(650, l.withInspectorWidth(Inspector.WATERFALL, 650, 1400).waterfallWidth)
        assertEquals(PaneLayout.DEFAULT_DETAIL, l.copy(detailWidth = 800).resetInspector(Inspector.DETAIL).detailWidth)
        assertEquals(PaneLayout.MAX_MOCKS, l.withMocksHeight(5000).mocksHeight)
        assertEquals(PaneLayout.DEFAULT_MOCKS, l.withMocksHeight(300).resetMocks().mocksHeight)
    }

    @Test fun `the controller persists sizes and collapsed state across launches`() {
        val store = KeyValueStore.InMemory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val first = LayoutController(scope, store, io = Dispatchers.Unconfined, debounceMillis = 0)
            first.update { it.copy(detailWidth = 640) }
            first.toggleTimelineOnly()
            first.toggleMocks()
            val next = LayoutController(scope, store, io = Dispatchers.Unconfined, debounceMillis = 0)
            assertEquals(PaneLayout(detailWidth = 640, detailCollapsed = true, mocksCollapsed = true), next.layout.value)
            next.reset()
            assertEquals(PaneLayout().encode(), store.get(PaneLayout.KEY))
        } finally {
            scope.cancel()
        }
    }

    @Test fun `popovers toggle and replace each other, and are never persisted`() {
        val store = KeyValueStore.InMemory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val c = LayoutController(scope, store, io = Dispatchers.Unconfined, debounceMillis = 0)
            c.toggle(Popover.SWITCHER)
            assertEquals(Popover.SWITCHER, c.popover.value)
            c.toggle(Popover.MCP)
            assertEquals(Popover.MCP, c.popover.value)
            c.toggle(Popover.MCP)
            assertEquals(Popover.NONE, c.popover.value)
            assertNull(store.get(PaneLayout.KEY))
        } finally {
            scope.cancel()
        }
    }
}
