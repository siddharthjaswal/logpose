package io.github.siddharthjaswal.logpose.mock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import io.github.siddharthjaswal.logpose.LogPoseConfig
import io.github.siddharthjaswal.logpose.emit.LogcatEmitter
import io.github.siddharthjaswal.logpose.wire.MockAck
import io.github.siddharthjaswal.logpose.wire.MockRuleSet
import kotlinx.serialization.json.Json

/**
 * Device end of the IDE → device reverse channel. The LogPose plugin pushes mock rules with:
 *
 * ```
 * adb shell am broadcast \
 *   -n <pkg>/io.github.siddharthjaswal.logpose.mock.MockCommandReceiver \
 *   --ei rev <revision> --ei seq <i> --ei total <n> --es payload <base64-json-slice>
 * ```
 *
 * The payload is a [MockRuleSet] as JSON, base64-encoded (immune to shell quoting) and split
 * across broadcasts when large. Once all slices of a revision arrive, the set is applied to
 * [MockRegistry] and a [MockAck] (revision + per-rule hit counts) is emitted on the LogPose
 * logcat tag — closing the loop over the channel the IDE already reads.
 *
 * Security: the receiver is exported (adb must reach it) but requires the sender to hold
 * `android.permission.DUMP` — a `signature|privileged|development` permission that the adb
 * shell has and third-party apps cannot obtain. It also ships only in the real (debug)
 * artifact; the no-op jar contains no receiver at all.
 */
internal class MockCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val revision = intent.getIntExtra(EXTRA_REVISION, -1)
        val seq = intent.getIntExtra(EXTRA_SEQ, 0)
        val total = intent.getIntExtra(EXTRA_TOTAL, 1)
        val payload = intent.getStringExtra(EXTRA_PAYLOAD)
        if (revision < 0 || payload == null || total <= 0 || seq !in 0 until total) return

        val json = synchronized(pending) {
            // A new revision supersedes any half-assembled older one.
            if (revision != pendingRevision) {
                pending.clear()
                pendingRevision = revision
            }
            pending[seq] = payload
            if (pending.size < total) return
            val joined = buildString { for (i in 0 until total) append(pending[i] ?: return) }
            pending.clear()
            joined
        }

        try {
            val decoded = String(Base64.decode(json, Base64.NO_WRAP), Charsets.UTF_8)
            val ruleSet = parser.decodeFromString<MockRuleSet>(decoded)
            LogPoseRuntime.packageName = context.packageName // provider-less edge (tests, tools)
            if (MockRegistry.apply(ruleSet)) {
                LogcatEmitter(LogPoseConfig()).emit(
                    MockAck(
                        pkg = context.packageName,
                        revision = MockRegistry.revision,
                        hits = MockRegistry.hits(),
                    )
                )
            }
        } catch (t: Throwable) {
            // Never crash the host app over a malformed push; surface it for adb debugging.
            Log.w("LogPoseMock", "Ignoring malformed mock rule push: $t")
        }
    }

    private companion object {
        const val EXTRA_REVISION = "rev"
        const val EXTRA_SEQ = "seq"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_PAYLOAD = "payload"

        val parser = Json { ignoreUnknownKeys = true; isLenient = true }

        // seq → base64 slice for the revision currently being assembled.
        val pending = HashMap<Int, String>()
        var pendingRevision = -1
    }
}
