package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.model.MockRuleSet
import io.github.siddharthjaswal.logpose.model.PushInject
import io.github.siddharthjaswal.logpose.model.PushMessage
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * The broadcast framing is a contract with code that lives in the other half of the repo, so it
 * is pinned literally rather than described. The constants below are **copies** of the library's
 * `MockCommandReceiver` extras and `ChunkAssembly` behaviour: if either side renames one, this
 * fails here instead of on a device where the symptom is "nothing happens".
 */
class BroadcastCommandTest {

    // ---- Mirror of logpose-android's MockCommandReceiver ------------------------------------
    private val deviceExtraCmd = "cmd"
    private val deviceExtraRevision = "rev"
    private val deviceExtraSeq = "seq"
    private val deviceExtraTotal = "total"
    private val deviceExtraPayload = "payload"
    private val deviceCmdPush = "push"
    private val deviceReceiver = "io.github.siddharthjaswal.logpose.mock.MockCommandReceiver"
    private val deviceSliceChars = 2000

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Test
    fun `slice size matches the size the device reassembles against`() {
        assertEquals(deviceSliceChars, BroadcastCommand.SLICE_CHARS)
        assertEquals(deviceReceiver, BroadcastCommand.RECEIVER)
        assertEquals(deviceCmdPush, BroadcastCommand.CMD_PUSH)
    }

    @Test
    fun `a rule-set broadcast is exactly what the receiver read before cmd existed`() {
        val args = BroadcastCommand.args(
            target = "com.acme.app", cmd = null, revision = 7, seq = 0, total = 1, payload = "QUJD",
        )
        assertEquals(
            listOf(
                "shell", "am", "broadcast",
                "-n", "com.acme.app/io.github.siddharthjaswal.logpose.mock.MockCommandReceiver",
                "-f", "0x00000020",
                "--ei", deviceExtraRevision, "7",
                "--ei", deviceExtraSeq, "0",
                "--ei", deviceExtraTotal, "1",
                "--es", deviceExtraPayload, "QUJD",
            ),
            args,
        )
        assertTrue(deviceExtraCmd !in args, "an old library must see the command it always saw")
    }

    @Test
    fun `a push broadcast carries cmd and deliberately no revision`() {
        val args = BroadcastCommand.args(
            target = "com.acme.app", cmd = BroadcastCommand.CMD_PUSH, revision = null,
            seq = 1, total = 3, payload = "eyJ4Ijox",
        )
        assertEquals(
            listOf(
                "shell", "am", "broadcast",
                "-n", "com.acme.app/io.github.siddharthjaswal.logpose.mock.MockCommandReceiver",
                "-f", "0x00000020",
                "--es", deviceExtraCmd, "push",
                "--ei", deviceExtraSeq, "1",
                "--ei", deviceExtraTotal, "3",
                "--es", deviceExtraPayload, "eyJ4Ijox",
            ),
            args,
        )
        // The device keys a push stream on the rev extra's own default and tells consecutive
        // pushes apart by their second seq 0 — sending a revision would fight that.
        assertNull(args.zipWithNext().firstOrNull { it.first == deviceExtraRevision }?.second)
    }

    @Test
    fun `a large push slices into 2000-char pieces that rejoin into the original JSON`() {
        val inject = PushInject(
            id = "inj-1",
            traceId = "trc-1",
            message = PushMessage(
                messageId = "m1",
                from = "1234567890",
                collapseKey = "orders",
                notificationTitle = "Order assigned",
                data = (1..200).associate { "key$it" to "value-$it-${"x".repeat(20)}" },
            ),
        )
        val payload = json.encodeToString(PushInject.serializer(), inject)
        val slices = BroadcastCommand.slices(payload)

        assertTrue(slices.size > 1, "the fixture must actually exercise chunking")
        assertTrue(slices.all { it.length <= deviceSliceChars })
        assertTrue(slices.dropLast(1).all { it.length == deviceSliceChars }, "only the last slice is short")

        // What the device does: concatenate seq 0..total-1, base64-decode, parse.
        val rejoined = String(Base64.getDecoder().decode(slices.joinToString("")), Charsets.UTF_8)
        assertEquals(payload, rejoined)
        assertEquals(inject, json.decodeFromString(PushInject.serializer(), rejoined))
    }

    @Test
    fun `an empty rule set is still one broadcast, not none`() {
        val payload = json.encodeToString(MockRuleSet.serializer(), MockRuleSet(revision = 3))
        val slices = BroadcastCommand.slices(payload)
        assertEquals(1, slices.size)
        assertEquals(payload, String(Base64.getDecoder().decode(slices.single()), Charsets.UTF_8))

        // Degenerate input can't produce a zero-slice command either: `total = 0` is a message
        // the device drops outright.
        assertEquals(listOf(""), BroadcastCommand.slices(""))
    }

    @Test
    fun `base64 keeps the payload free of anything the shell would mangle`() {
        val slices = BroadcastCommand.slices("""{"body":"a b & c ' \" $(rm -rf) \n"}""")
        assertTrue(slices.single().matches(Regex("[A-Za-z0-9+/=]+")))
    }
}
