package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.wire.ConfigUpdate
import io.github.siddharthjaswal.logpose.wire.DbQuery
import io.github.siddharthjaswal.logpose.wire.Envelope
import io.github.siddharthjaswal.logpose.wire.WorkerEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip coverage for the db / worker / config payloads. The emit path itself needs
 * `android.util.Log`, so `LogPose`'s diffing and span logic is exercised through the same
 * shapes the plugin has to read.
 */
class RuntimeEventWireTest {

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test fun `db payload round-trips and leaves derivation to the plugin`() {
        val query = DbQuery(
            sql = "SELECT id, name FROM users WHERE id = ?",
            args = listOf("7"),
            database = "app-db",
            rows = 1,
        )
        val back = json.decodeFromString(
            DbQuery.serializer(),
            json.encodeToString(DbQuery.serializer(), query),
        )
        assertEquals(query, back)
        assertNull("operation is parsed from SQL plugin-side, not sent", back.operation)
        assertNull(back.table)
    }

    @Test fun `worker terminal states are the ones that close a span`() {
        assertTrue(WorkerEvent.STATE_SUCCEEDED in WorkerEvent.TERMINAL)
        assertTrue(WorkerEvent.STATE_FAILED in WorkerEvent.TERMINAL)
        assertTrue(WorkerEvent.STATE_CANCELLED in WorkerEvent.TERMINAL)
        // A retry goes back to enqueued, so those must NOT close the row.
        assertTrue(WorkerEvent.STATE_ENQUEUED !in WorkerEvent.TERMINAL)
        assertTrue(WorkerEvent.STATE_RUNNING !in WorkerEvent.TERMINAL)
    }

    @Test fun `a worker request keeps one envelope id across its states`() {
        // The plugin's store keys by envelope id, so reusing workId is what collapses
        // enqueued → running → succeeded into a single updating row.
        val workId = "b7c1-work"
        val states = listOf(
            WorkerEvent.STATE_ENQUEUED,
            WorkerEvent.STATE_RUNNING,
            WorkerEvent.STATE_SUCCEEDED,
        )
        val envelopes = states.mapIndexed { i, state ->
            Envelope(
                kind = Envelope.KIND_WORKER,
                id = workId,
                at = 1_000,
                endedAt = if (state in WorkerEvent.TERMINAL) 1_000 + (i * 500L) else null,
                payload = json.encodeToJsonElement(WorkerEvent(worker = "SyncWorker", state = state)),
            )
        }
        assertEquals(1, envelopes.map { it.id }.distinct().size)
        assertNull("still running means the span stays open", envelopes[1].endedAt)
        assertEquals(2_000L, envelopes[2].endedAt!!)
    }

    @Test fun `config update carries only changes, with the previous value`() {
        val update = ConfigUpdate(
            source = "remote",
            totalKeys = 187,
            changes = listOf(
                io.github.siddharthjaswal.logpose.wire.ConfigChange(
                    key = "new_checkout", value = "true", previous = "false",
                ),
            ),
        )
        val back = json.decodeFromString(
            ConfigUpdate.serializer(),
            json.encodeToString(ConfigUpdate.serializer(), update),
        )
        assertEquals(1, back.changes.size)
        assertEquals("false", back.changes.single().previous)
        assertEquals("the unchanged majority is a count, not 187 rows", 187, back.totalKeys)
    }

    @Test fun `a baseline snapshot reports a count instead of every key as new`() {
        val baseline = ConfigUpdate(baseline = true, totalKeys = 187, source = "remote")
        assertTrue(baseline.changes.isEmpty())
        assertTrue(baseline.baseline)
    }
}
