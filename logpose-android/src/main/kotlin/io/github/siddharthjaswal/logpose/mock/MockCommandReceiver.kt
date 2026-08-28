package io.github.siddharthjaswal.logpose.mock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import io.github.siddharthjaswal.logpose.emit.LogcatEmitter
import io.github.siddharthjaswal.logpose.wire.MockAck
import io.github.siddharthjaswal.logpose.wire.MockRuleSet
import io.github.siddharthjaswal.logpose.wire.PushInject
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
 * A `--es cmd <command>` extra selects what the payload is; absent (or `rules`) it is a rule set,
 * exactly as before this existed, so an older plugin keeps working unchanged:
 *
 * | cmd | payload | effect |
 * | --- | --- | --- |
 * | `rules` (default) | [MockRuleSet] | replace the active rules, ack with [MockAck] |
 * | `push` | [PushInject] | deliver a synthetic push, ack with `push_ack` (see [PushInjector]) |
 *
 * Each command reassembles its slices in its own pending map ([ChunkAssembly]), so a push landing
 * mid-rule-set can't corrupt either. `rev` is meaningless for `push` and ignored.
 *
 * Security: the receiver is exported (adb must reach it) but requires the sender to hold
 * `android.permission.DUMP` — a `signature|privileged|development` permission that the adb
 * shell has and third-party apps cannot obtain. It also ships only in the real (debug)
 * artifact; the no-op jar contains no receiver at all.
 */
internal class MockCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra(EXTRA_CMD)?.takeIf { it.isNotBlank() } ?: CMD_RULES
        val revision = intent.getIntExtra(EXTRA_REVISION, -1)
        val seq = intent.getIntExtra(EXTRA_SEQ, 0)
        val total = intent.getIntExtra(EXTRA_TOTAL, 1)
        val payload = intent.getStringExtra(EXTRA_PAYLOAD)
        // A rule set is ordered by revision; a push has none, so -1 is legal there.
        if (payload == null || total <= 0 || seq !in 0 until total) return
        if (cmd == CMD_RULES && revision < 0) return

        val assembled = ChunkAssembly.add(cmd, revision, seq, total, payload) ?: return

        try {
            val decoded = String(Base64.decode(assembled, Base64.NO_WRAP), Charsets.UTF_8)
            LogPoseRuntime.packageName = context.packageName // provider-less edge (tests, tools)
            when (cmd) {
                CMD_PUSH -> inject(context, parser.decodeFromString<PushInject>(decoded))
                else -> applyRules(context, parser.decodeFromString<MockRuleSet>(decoded))
            }
        } catch (t: Throwable) {
            // Never crash the host app over a malformed push; surface it for adb debugging.
            Log.w("LogPoseMock", "Ignoring malformed LogPose command '$cmd': $t")
        }
    }

    private fun applyRules(context: Context, ruleSet: MockRuleSet) {
        if (!MockRegistry.apply(ruleSet)) return
        // Acks go out on the config the app actually configured (see LogPoseRuntime.config), not
        // a hardcoded default — otherwise a custom-tag app never sees its own acks.
        LogcatEmitter(LogPoseRuntime.config).emit(
            MockAck(
                pkg = context.packageName,
                revision = MockRegistry.revision,
                ruleCount = MockRegistry.ruleCount,
                hits = MockRegistry.hits(),
            )
        )
    }

    /**
     * Hands the injection to [PushInjector], which delivers off the main thread. `goAsync` keeps
     * the process around for that hop — a broadcast that returns immediately is otherwise free to
     * be killed mid-delivery.
     */
    private fun inject(context: Context, injection: PushInject) {
        val pending = runCatching { goAsync() }.getOrNull()
        PushInjector.inject(context, injection, LogPoseRuntime.config) {
            runCatching { pending?.finish() }
        }
    }

    private companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_REVISION = "rev"
        const val EXTRA_SEQ = "seq"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_PAYLOAD = "payload"

        const val CMD_RULES = "rules"
        const val CMD_PUSH = "push"

        val parser = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
