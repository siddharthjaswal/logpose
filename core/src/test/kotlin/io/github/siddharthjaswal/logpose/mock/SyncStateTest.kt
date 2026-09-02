package io.github.siddharthjaswal.logpose.mock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncStateTest {

    private val state = SyncState()

    @Test
    fun `starts idle and goes pending as soon as rules exist locally`() {
        assertEquals(SyncState.Phase.IDLE, state.snapshot().phase)
        state.onLocalRevision(1)
        assertEquals(SyncState.Phase.PENDING, state.snapshot().phase)
    }

    @Test
    fun `an ack for the pushed revision with the pushed rule count is synced`() {
        state.onLocalRevision(3)
        state.onPush(revision = 3, ruleCount = 2, atMillis = 0)
        assertEquals(SyncState.Phase.PENDING, state.snapshot().phase)

        assertEquals(SyncState.Effect.None, state.onAck(revision = 3, ruleCount = 2))
        val snap = state.snapshot()
        assertEquals(SyncState.Phase.SYNCED, snap.phase)
        assertEquals(3, snap.syncedRevision)
        assertNull(snap.message)
    }

    @Test
    fun `an ack whose rule count disagrees triggers a bounded re-push then fails`() {
        state.onPush(revision = 1, ruleCount = 2, atMillis = 0)

        val first = state.onAck(revision = 1, ruleCount = 1)
        assertTrue(first is SyncState.Effect.Repush, "expected a re-push, got $first")
        assertEquals(1, (first as SyncState.Effect.Repush).attempt)
        assertTrue(first.reason.contains("expected 2"))

        // The caller re-pushes the same revision: the retry budget is not refilled.
        state.onPush(revision = 1, ruleCount = 2, atMillis = 10)
        val second = state.onAck(revision = 1, ruleCount = 1)
        assertEquals(2, (second as SyncState.Effect.Repush).attempt)

        state.onPush(revision = 1, ruleCount = 2, atMillis = 20)
        val third = state.onAck(revision = 1, ruleCount = 1)
        assertTrue(third is SyncState.Effect.Fail, "budget spent, expected a failure, got $third")
        assertEquals(SyncState.Phase.FAILED, state.snapshot().phase)
        assertNotNull(state.snapshot().message)
    }

    @Test
    fun `a new revision refills the retry budget`() {
        state.onPush(revision = 1, ruleCount = 2, atMillis = 0)
        repeat(2) {
            state.onAck(revision = 1, ruleCount = 0)
            state.onPush(revision = 1, ruleCount = 2, atMillis = 0)
        }
        assertEquals(2, state.snapshot().attempt)

        state.onPush(revision = 2, ruleCount = 2, atMillis = 0)
        assertEquals(0, state.snapshot().attempt)
        assertEquals(1, (state.onAck(revision = 2, ruleCount = 0) as SyncState.Effect.Repush).attempt)
    }

    @Test
    fun `a broadcast failure fails immediately and retains the message`() {
        state.onPush(revision = 1, ruleCount = 1, atMillis = 0)
        val effect = state.onBroadcastFailure(1, "adb exited 1: device offline")
        assertTrue(effect is SyncState.Effect.Fail)
        assertEquals("adb exited 1: device offline", (effect as SyncState.Effect.Fail).message)
        assertEquals(SyncState.Phase.FAILED, state.snapshot().phase)
        assertEquals("adb exited 1: device offline", state.snapshot().message)

        // Reported once: the same failure on a later slice/push must not re-notify.
        assertEquals(SyncState.Effect.None, state.onBroadcastFailure(1, "adb exited 1: device offline"))
    }

    @Test
    fun `a failure from a superseded push is ignored`() {
        state.onPush(revision = 1, ruleCount = 1, atMillis = 0)
        state.onPush(revision = 2, ruleCount = 1, atMillis = 0)
        assertEquals(SyncState.Effect.None, state.onBroadcastFailure(1, "stale"))
        assertEquals(SyncState.Phase.PENDING, state.snapshot().phase)
    }

    @Test
    fun `a missed ack deadline re-pushes, and only for the push it was armed for`() {
        state.onPush(revision = 5, ruleCount = 1, atMillis = 0)
        // A deadline armed for another revision/attempt can never demote the current state.
        assertEquals(SyncState.Effect.None, state.onAckDeadline(revision = 4, forAttempt = 0))
        assertEquals(SyncState.Effect.None, state.onAckDeadline(revision = 5, forAttempt = 3))

        val effect = state.onAckDeadline(revision = 5, forAttempt = 0)
        assertTrue(effect is SyncState.Effect.Repush)
        assertTrue((effect as SyncState.Effect.Repush).reason.contains("did not acknowledge"))
    }

    @Test
    fun `a deadline that fires after the ack landed changes nothing`() {
        state.onPush(revision = 5, ruleCount = 1, atMillis = 0)
        state.onAck(revision = 5, ruleCount = 1)
        assertEquals(SyncState.Effect.None, state.onAckDeadline(revision = 5, forAttempt = 0))
        assertEquals(SyncState.Phase.SYNCED, state.snapshot().phase)
    }

    @Test
    fun `a stale ack for a superseded revision is ignored`() {
        state.onPush(revision = 1, ruleCount = 1, atMillis = 0)
        state.onPush(revision = 2, ruleCount = 1, atMillis = 0)
        assertEquals(SyncState.Effect.None, state.onAck(revision = 1, ruleCount = 1))
        assertEquals(SyncState.Phase.PENDING, state.snapshot().phase)
    }

    @Test
    fun `an ack for a set this session never pushed is recorded, not corrected`() {
        // e.g. scripts_push-mocks.sh pushed while the IDE was only watching.
        assertEquals(SyncState.Effect.None, state.onAck(revision = 9, ruleCount = 3))
        assertEquals(3, state.snapshot().deviceRules)
        assertEquals(9, state.snapshot().syncedRevision)
    }

    // ---- Hello: the reinstall-reset case -----------------------------------------------------

    @Test
    fun `a hello reporting zero rules while we hold some triggers a re-push`() {
        state.onLocalRevision(4)
        state.onPush(revision = 4, ruleCount = 2, atMillis = 0)
        state.onAck(revision = 4, ruleCount = 2)
        assertEquals(SyncState.Phase.SYNCED, state.snapshot().phase)

        // App reinstalled: the registry is empty and the revision is back to 0.
        val effect = state.onHello(
            deviceRevision = 0, deviceRuleCount = 0, expectedRuleCount = 2, freshProcess = true,
        )
        assertTrue(effect is SyncState.Effect.Repush, "expected a re-push, got $effect")
        assertTrue((effect as SyncState.Effect.Repush).reason.contains("0 rule"))
    }

    @Test
    fun `a hello confirming our revision and count counts as synced`() {
        state.onLocalRevision(4)
        state.onPush(revision = 4, ruleCount = 2, atMillis = 0)
        val effect = state.onHello(
            deviceRevision = 4, deviceRuleCount = 2, expectedRuleCount = 2, freshProcess = false,
        )
        assertEquals(SyncState.Effect.None, effect)
        assertEquals(SyncState.Phase.SYNCED, state.snapshot().phase)
    }

    @Test
    fun `an empty device with nothing to push is not a problem`() {
        state.onLocalRevision(7)
        val effect = state.onHello(
            deviceRevision = 0, deviceRuleCount = 0, expectedRuleCount = 0, freshProcess = true,
        )
        assertEquals(SyncState.Effect.None, effect)
        assertNull(state.snapshot().message)
    }

    @Test
    fun `a restarted app gets a fresh retry budget`() {
        state.onPush(revision = 1, ruleCount = 2, atMillis = 0)
        repeat(3) {
            state.onAck(revision = 1, ruleCount = 0)
            state.onPush(revision = 1, ruleCount = 2, atMillis = 0)
        }
        assertEquals(2, state.snapshot().attempt)

        val effect = state.onHello(
            deviceRevision = 0, deviceRuleCount = 0, expectedRuleCount = 2, freshProcess = true,
        )
        assertEquals(1, (effect as SyncState.Effect.Repush).attempt)
    }

    @Test
    fun `reset clears everything so the next capture proves sync again`() {
        state.onLocalRevision(2)
        state.onPush(revision = 2, ruleCount = 1, atMillis = 0)
        state.onBroadcastFailure(2, "boom")
        state.reset()
        val snap = state.snapshot()
        assertEquals(SyncState.Phase.IDLE, snap.phase)
        assertNull(snap.message)
        assertEquals(0, snap.attempt)
    }
}
