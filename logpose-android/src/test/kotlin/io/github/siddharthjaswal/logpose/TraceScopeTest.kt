package io.github.siddharthjaswal.logpose

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ambient trace scope: mint a trace at a flow's entry point and every event emitted inside it
 * shares the id, so `get_trace` becomes one call. These pin the scoping — set inside, restored
 * after, and nestable — the emit side needs android.util.Log and is covered by the plugin's tools.
 */
class TraceScopeTest {

    @Test fun `the trace is in scope inside the block and cleared after`() {
        assertNull(LogPose.currentTraceId())
        LogPose.withTrace("trace-1") {
            assertEquals("trace-1", LogPose.currentTraceId())
        }
        assertNull("the trace must not leak past the block", LogPose.currentTraceId())
    }

    @Test fun `withTrace mints an id when none is given`() {
        val seen = LogPose.withTrace { LogPose.currentTraceId() }
        assertEquals(8, seen?.length) // newId() is an 8-char id
    }

    @Test fun `nested scopes restore the outer trace`() {
        LogPose.withTrace("outer") {
            LogPose.withTrace("inner") {
                assertEquals("inner", LogPose.currentTraceId())
            }
            assertEquals("outer", LogPose.currentTraceId())
        }
    }

    @Test fun `traceContext carries the trace across a dispatcher hop`() = runBlocking {
        assertNull(LogPose.currentTraceId())
        withContext(LogPose.traceContext("flow-1") + Dispatchers.Default) {
            assertEquals("flow-1", LogPose.currentTraceId())
            // A further hop to another dispatcher must keep the trace in scope.
            withContext(Dispatchers.IO) {
                assertEquals("flow-1", LogPose.currentTraceId())
            }
        }
        assertNull("the trace must not leak onto the caller thread", LogPose.currentTraceId())
    }
}
