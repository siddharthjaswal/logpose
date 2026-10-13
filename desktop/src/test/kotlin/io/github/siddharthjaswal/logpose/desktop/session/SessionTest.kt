package io.github.siddharthjaswal.logpose.desktop.session

import io.github.siddharthjaswal.logpose.desktop.Fixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionReducerTest {
    private val r = SessionState.Running("A", 10)

    @Test fun `start from idle or ended runs, from running is a no-op`() {
        assertEquals(r, SessionReducer.reduce(SessionState.Idle, SessionEvent.Start("A", 10)))
        assertEquals(r, SessionReducer.reduce(SessionState.Ended("B", "x"), SessionEvent.Start("A", 10)))
        assertSame(r, SessionReducer.reduce(r, SessionEvent.Start("B", 99)))
    }

    @Test fun `stop is idle, a stream that ends on its own is ended with a reason`() {
        assertEquals(SessionState.Idle, SessionReducer.reduce(r, SessionEvent.Stop))
        assertEquals(SessionState.Ended("A", SessionReducer.DISCONNECTED), SessionReducer.reduce(r, SessionEvent.StreamEnded("A")))
        assertEquals(SessionState.Ended("A", "boom"), SessionReducer.reduce(r, SessionEvent.Failed("A", "boom")))
    }

    @Test fun `late events from another device or after stop are ignored`() {
        assertSame(r, SessionReducer.reduce(r, SessionEvent.StreamEnded("B")))
        assertSame(SessionState.Idle, SessionReducer.reduce(SessionState.Idle, SessionEvent.StreamEnded("A")))
    }
}

class CaptureSessionTest {
    private class FakeSource : LineSource {
        var onLine: (String) -> Unit = {}
        var onStopped: () -> Unit = {}
        var stopped = false
        override fun start(onLine: (String) -> Unit, onError: (String) -> Unit, onStopped: () -> Unit) {
            this.onLine = onLine; this.onStopped = onStopped
        }
        override fun stop() { stopped = true }
    }

    @Test fun `lines flow through the real parser into the store`() {
        val src = FakeSource()
        var now = 1_000L
        val s = CaptureSession(sourceFor = { src }, clock = { now })
        s.start("A")
        assertEquals(SessionState.Running("A", 1_000), s.state.value)
        src.onLine("--------- beginning of main")
        assertNull(s.firstLogPoseAtMillis)
        now = 1_500
        Fixtures.lines().forEach(src.onLine)
        assertEquals(1_500L, s.firstLogPoseAtMillis)
        assertEquals(16, s.store.snapshot().size)
    }

    @Test fun `stop stops the source and its late onStopped does not end a new session`() {
        val sources = ArrayList<FakeSource>()
        val s = CaptureSession(sourceFor = { FakeSource().also(sources::add) })
        s.start("A"); s.stop()
        assertTrue(sources[0].stopped)
        s.start("A")
        sources[0].onStopped()   // the first reader reaping late
        assertTrue(s.state.value is SessionState.Running)
        sources[1].onStopped()   // the live one ending: a real disconnect
        assertEquals(SessionState.Ended("A", SessionReducer.DISCONNECTED), s.state.value)
    }

    @Test fun `a new session starts from an empty timeline`() {
        val src = FakeSource()
        val s = CaptureSession(sourceFor = { src })
        s.start("A"); Fixtures.lines().forEach(src.onLine); s.stop()
        s.start("A")
        assertEquals(0, s.store.snapshot().size)
    }

    @Test fun `the feed publishes once per burst, not once per event`() {
        val src = FakeSource()
        val s = CaptureSession(sourceFor = { src })
        val feed = TimelineFeed(s.store)
        feed.publishIfDirty()
        s.start("A")
        Fixtures.lines().forEach(src.onLine)
        assertTrue(feed.publishIfDirty())
        assertEquals(16, feed.snapshots.value.events.size)
        val v = feed.snapshots.value.version
        assertTrue(!feed.publishIfDirty())
        assertEquals(v, feed.snapshots.value.version)
    }
}

class QuietDetectorTest {
    @Test fun `listening, then no-logpose at 20s, receiving as soon as anything arrives`() {
        assertEquals(QuietDetector.Verdict.LISTENING, QuietDetector.verdict(0, 19_999, null))
        assertEquals(QuietDetector.Verdict.NO_LOGPOSE, QuietDetector.verdict(0, 20_000, null))
        assertEquals(QuietDetector.Verdict.RECEIVING, QuietDetector.verdict(0, 60_000, 59_000))
    }

    @Test fun `logcat banners are not LogPose, JSON lines are`() {
        assertTrue(!QuietDetector.isLogPoseLine("--------- beginning of main"))
        assertTrue(QuietDetector.isLogPoseLine("  {\"kind\":\"hello\"}"))
    }
}
