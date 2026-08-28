package io.github.siddharthjaswal.logpose.mock

/**
 * The "does the device actually hold what the IDE holds?" state machine, extracted from
 * [MocksController] so it stays pure (no IntelliJ, no adb, no threads) and unit-testable.
 *
 * The honest answer needs three inputs, not one:
 *  - **revision** — the IDE's monotonic rule-set revision vs the last one the device confirmed.
 *  - **rule count** — a device that acks revision 7 while holding 0 rules is *not* synced; that's
 *    the reinstall/process-death case the [Hello] handshake exists to catch.
 *  - **transport** — an `am broadcast` that exits non-zero never reached the app at all, so
 *    "pending" would be a lie; the failure text is retained for the UI tooltip.
 *
 * Rather than acting on those itself (it can't — pushing is adb work), each input returns an
 * [Effect] the caller performs: re-push (bounded, so a device that keeps disagreeing can't put
 * the IDE in a broadcast loop) or surface a failure once.
 *
 * States are derived, never assigned, so they can't drift from the counters:
 *  - `IDLE`    — nothing pushed and nothing to push.
 *  - `PENDING` — the device hasn't confirmed the current revision yet.
 *  - `SYNCED`  — the device confirmed this revision *and* the expected rule count.
 *  - `FAILED`  — a broadcast failed, an ack never came, or the device kept disagreeing.
 *
 * Every mutator is synchronized: pushes originate on the EDT, acks/hellos on the logcat reader
 * thread, ack deadlines on a scheduler thread.
 */
class SyncState(private val maxRetries: Int = DEFAULT_MAX_RETRIES) {

    enum class Phase { IDLE, PENDING, SYNCED, FAILED }

    /** Immutable view for the UI. [message] is the retained failure text (tooltip). */
    data class Snapshot(
        val phase: Phase,
        val revision: Int,
        val syncedRevision: Int,
        val expectedRules: Int,
        val deviceRules: Int,
        val message: String?,
        val attempt: Int,
    )

    /** What the caller must do after feeding an event in. */
    sealed interface Effect {
        data object None : Effect

        /** Re-push the current rule set; [attempt] is 1-based and bounded by `maxRetries`. */
        data class Repush(val reason: String, val attempt: Int) : Effect

        /** Sync just moved to [Phase.FAILED]; surface [message] once (a notification, never the
         *  detail pane). */
        data class Fail(val message: String) : Effect
    }

    private var localRevision = 0
    private var pushedRevision = -1
    private var syncedRevision = -1
    private var expectedRules = 0
    private var deviceRules = 0
    private var failure: String? = null
    private var attempt = 0

    /** Millis the last push went out, for the ack deadline. */
    private var pushedAt = 0L

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        phase = phase(),
        revision = localRevision,
        syncedRevision = syncedRevision,
        expectedRules = expectedRules,
        deviceRules = deviceRules,
        message = failure,
        attempt = attempt,
    )

    private fun phase(): Phase = when {
        failure != null -> Phase.FAILED
        syncedRevision >= localRevision && syncedRevision >= 0 -> Phase.SYNCED
        pushedRevision >= 0 || localRevision > 0 -> Phase.PENDING
        else -> Phase.IDLE
    }

    /**
     * A local rule edit bumped the revision. Recorded even when capture is off (nothing is
     * pushed then) so the bar reads "pending" rather than claiming a stale revision is live.
     */
    @Synchronized
    fun onLocalRevision(revision: Int) {
        localRevision = revision
    }

    /**
     * A rule set of [ruleCount] rules is going out at [revision]. A push of a *new* revision
     * clears the retry budget and any retained failure; a retry of the same revision keeps the
     * budget it has already spent.
     */
    @Synchronized
    fun onPush(revision: Int, ruleCount: Int, atMillis: Long) {
        if (revision != pushedRevision) attempt = 0
        localRevision = maxOf(localRevision, revision)
        pushedRevision = revision
        expectedRules = ruleCount
        pushedAt = atMillis
        failure = null
    }

    /** The `am broadcast` for [revision] never landed (non-zero exit, timeout, adb missing). */
    @Synchronized
    fun onBroadcastFailure(revision: Int, message: String): Effect {
        if (revision < pushedRevision) return Effect.None // superseded by a newer push
        return fail(message)
    }

    /**
     * The device applied a rule set. A count that disagrees with what we pushed means the device
     * is holding something else (a stale set, a partially-decoded push) — re-push rather than
     * report a sync that isn't one.
     */
    @Synchronized
    fun onAck(revision: Int, ruleCount: Int): Effect {
        if (revision < pushedRevision) return Effect.None // stale ack for a superseded push
        deviceRules = ruleCount
        if (pushedRevision < 0) {
            // An ack for a set this session never pushed (a script, a previous IDE run). Record it
            // rather than "correcting" a rule set we know nothing about.
            syncedRevision = maxOf(syncedRevision, revision)
            return Effect.None
        }
        if (ruleCount != expectedRules) {
            return retryOrFail(
                "device applied $ruleCount rule(s) at revision $revision, expected $expectedRules",
            )
        }
        syncedRevision = revision
        localRevision = maxOf(localRevision, revision)
        attempt = 0
        failure = null
        return Effect.None
    }

    /**
     * A device handshake. [freshProcess] resets the retry budget: a restarted app is a new chance,
     * not a continuation of the disagreement that exhausted the previous budget.
     *
     * Re-push when the device is behind our revision, or when it reports a different rule count
     * than it should hold — `ruleCount == 0` with rules on our side is the reinstall/process-death
     * reset, the case this whole handshake exists for.
     */
    @Synchronized
    fun onHello(
        deviceRevision: Int,
        deviceRuleCount: Int,
        expectedRuleCount: Int,
        freshProcess: Boolean,
    ): Effect {
        if (freshProcess) attempt = 0
        deviceRules = deviceRuleCount
        val behind = deviceRevision < localRevision
        val wrongCount = deviceRuleCount != expectedRuleCount
        if (!behind && !wrongCount) {
            // The device is holding exactly our current set: a hello is proof enough.
            syncedRevision = maxOf(syncedRevision, deviceRevision)
            expectedRules = expectedRuleCount
            failure = null
            attempt = 0
            return Effect.None
        }
        if (expectedRuleCount == 0 && deviceRuleCount == 0) {
            // Nothing to sync — an empty device holding an empty set is fine whatever the revision.
            failure = null
            return Effect.None
        }
        val reason = if (wrongCount)
            "device holds $deviceRuleCount rule(s), expected $expectedRuleCount"
        else
            "device is at revision $deviceRevision, expected $localRevision"
        return retryOrFail(reason)
    }

    /**
     * No ack arrived within the deadline armed for ([revision], [forAttempt]). Ignored when a
     * newer push or a retry has since moved the state on, so a late timer can't demote a
     * healthy sync.
     */
    @Synchronized
    fun onAckDeadline(revision: Int, forAttempt: Int): Effect {
        if (phase() != Phase.PENDING) return Effect.None
        if (revision != pushedRevision || forAttempt != attempt) return Effect.None
        if (syncedRevision >= revision) return Effect.None
        return retryOrFail("device did not acknowledge revision $revision")
    }

    /** Capture stopped / rules cleared: back to a clean slate so the next session starts honest. */
    @Synchronized
    fun reset() {
        localRevision = 0
        pushedRevision = -1
        syncedRevision = -1
        expectedRules = 0
        deviceRules = 0
        failure = null
        attempt = 0
    }

    private fun retryOrFail(reason: String): Effect {
        if (attempt < maxRetries) {
            attempt++
            failure = null
            return Effect.Repush(reason, attempt)
        }
        return fail("$reason — gave up after $attempt retry attempt(s)")
    }

    private fun fail(message: String): Effect {
        val alreadyFailed = failure != null
        failure = message
        return if (alreadyFailed) Effect.None else Effect.Fail(message)
    }

    companion object {
        /** Two extra attempts, then stop: a device that keeps disagreeing needs a human. */
        const val DEFAULT_MAX_RETRIES = 2
    }
}
