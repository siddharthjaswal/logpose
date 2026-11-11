package io.github.siddharthjaswal.logpose.appcontrol

import io.github.siddharthjaswal.logpose.capture.Capture
import io.github.siddharthjaswal.logpose.capture.CaptureLines
import io.github.siddharthjaswal.logpose.logcat.TransactionParser
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.presentation.KindPresenter
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.store.EventStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppTargetAndMarkerTest {

    // ---- package selection ---------------------------------------------------------------------

    private fun session(index: Int, pkg: String) = EventStore.Session(index, 0, "p$index", pkg, "1.7.4")

    @Test
    fun `announced packages are distinct, newest run first`() {
        val sessions = listOf(session(1, "com.a"), session(2, "com.b.debug"), session(3, "com.a"))
        assertEquals(listOf("com.a", "com.b.debug"), AppTarget.announced(sessions))
    }

    @Test
    fun `one announced app is the target`() {
        val c = AppTarget.choose(listOf("in.shadowfax.gandalf.debug"), remembered = "com.other")
        assertEquals("in.shadowfax.gandalf.debug", c.pkg)
        assertEquals(AppTarget.Source.ANNOUNCED, c.source)
        assertFalse(c.ambiguous)
    }

    @Test
    fun `several announced apps are a choice, defaulting to the newest`() {
        val c = AppTarget.choose(listOf("com.b", "com.a"), remembered = null)
        assertTrue(c.ambiguous)
        assertEquals("com.b", c.pkg)
        assertEquals(listOf("com.b", "com.a"), c.announced)
    }

    @Test
    fun `an explicit package wins over everything`() {
        val c = AppTarget.choose(listOf("com.b", "com.a"), remembered = "com.r", explicit = " com.a ")
        assertEquals("com.a", c.pkg)
        assertEquals(AppTarget.Source.EXPLICIT, c.source)
        assertFalse(c.ambiguous)
    }

    @Test
    fun `nothing announced falls back to the package remembered for this device`() {
        val settings = KeyValueStore.InMemory()
        AppTarget.remember(settings, "emulator-5580", "com.acme.debug")
        AppTarget.remember(settings, "emulator-5580", "not a package") // ignored
        assertEquals("com.acme.debug", AppTarget.remembered(settings, "emulator-5580"))
        assertNull(AppTarget.remembered(settings, "R5CT"))
        val c = AppTarget.choose(emptyList(), AppTarget.remembered(settings, "emulator-5580"))
        assertEquals(AppTarget.Source.REMEMBERED, c.source)
        assertEquals("com.acme.debug", c.pkg)
        val none = AppTarget.choose(emptyList(), null)
        assertNull(none.pkg)
        assertEquals(AppTarget.Source.NONE, none.source)
    }

    // ---- the marker -----------------------------------------------------------------------------

    private fun report(action: AppAction, cleared: Boolean = true) = AppControl.Report(
        action, "emulator-5580", "com.acme.debug", emptyList(), stopped = true, cleared = cleared,
        launchedVia = "monkey", externalCache = AppOutput.ExternalCache.ABSENT.takeIf { action == AppAction.CLEAR_CACHE },
    )

    @Test
    fun `the marker is a closed host-side row titled after what happened`() {
        val m = AppMarker.of(report(AppAction.CLEAR_CACHE), atMillis = 1_000)
        assertEquals(AppMarker.KIND, m.kind)
        assertEquals("Cache cleared", m.event!!.title)
        assertEquals("com.acme.debug", m.event!!.subtitle)
        assertFalse(m.isOpen, "a point event is never in flight")
        assertEquals(1_000L, m.timestampMillis)
        assertEquals("MARK", KindPresenter.kindLabel(m))
        assertEquals("Cache cleared", KindPresenter.rowLabel(m))
        assertTrue(AppMarker.isMarker(m))

        assertEquals("Restarted by LogPose", AppMarker.of(report(AppAction.RESTART), 1).event!!.title)
        assertEquals("App data cleared", AppMarker.of(report(AppAction.CLEAR_DATA), 1).event!!.title)
        assertEquals("Restarted by LogPose", AppMarker.of(report(AppAction.CLEAR_DATA, cleared = false), 1).event!!.title)
    }

    @Test
    fun `the marker survives a capture file round trip as the same row`() {
        val m = AppMarker.of(report(AppAction.CLEAR_DATA), atMillis = 42)
        val line = CaptureLines.of(m)
        val back = TransactionParser().accept(line)
        assertNotNull(back)
        back as LogEvent.Generic
        assertEquals(m.id, back.id)
        assertEquals(AppMarker.KIND, back.kind)
        assertEquals("App data cleared", back.event!!.title)
        assertEquals(m.event, back.event)
        // And through the replay a share/export uses.
        assertEquals(listOf(m.id), Capture.replay(listOf(line)).events.map { it.id })
    }

    @Test
    fun `the marker never counts as in flight or abandoned in the store`() {
        val store = EventStore()
        store.noteHello("p1", "com.acme.debug", "1.7.4")
        store.add(AppMarker.of(report(AppAction.RESTART), 5))
        store.noteHello("p2", "com.acme.debug", "1.7.4")
        val stored = store.snapshot().single()
        assertFalse(stored.isInFlight)
        assertNull(stored.abandoned)
    }
}
