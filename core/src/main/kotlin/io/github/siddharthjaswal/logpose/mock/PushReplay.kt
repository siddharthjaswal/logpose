package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.model.FcmMessage
import io.github.siddharthjaswal.logpose.model.PushInject
import io.github.siddharthjaswal.logpose.model.PushMessage
import java.util.UUID

/**
 * Turns a **captured** push back into one the IDE can ask the device to deliver again.
 *
 * The two shapes differ on purpose: [FcmMessage] is what the timeline shows (nested
 * notification), [PushMessage] is field-for-field the app-facing `FcmMessageInfo` the device
 * rebuilds a `RemoteMessage` from (flat). Replay therefore has to carry every field across —
 * dropping one would mean re-sending something the app never received (PRD FR-A3).
 *
 * Two fields are deliberately *not* copied:
 *  - `messageId` — a replay is a new message, and reusing the id would make the two
 *    indistinguishable in the app's own dedup;
 *  - `sentTimeMillis` — "now", not when the original was sent, so TTL/staleness logic behaves.
 *
 * Both are parameters rather than reads of the clock/UUID, which keeps this pure and testable.
 */
object PushReplay {

    /** A token refresh isn't a message, so there is nothing to deliver for one. */
    fun canReplay(msg: FcmMessage): Boolean = msg.event != "token"

    /** The wire form of a captured push, ready to be injected again. */
    fun toMessage(msg: FcmMessage, messageId: String, sentTimeMillis: Long): PushMessage =
        PushMessage(
            messageId = messageId,
            from = msg.from,
            to = msg.to,
            collapseKey = msg.collapseKey,
            messageType = msg.messageType,
            sentTimeMillis = sentTimeMillis,
            ttlSeconds = msg.ttlSeconds,
            priority = msg.priority,
            notificationTitle = msg.notification?.title,
            notificationBody = msg.notification?.body,
            notificationChannelId = msg.notification?.channelId,
            notificationClickAction = msg.notification?.clickAction,
            notificationImageUrl = msg.notification?.imageUrl,
            data = msg.data,
        )

    /**
     * Wraps [message] in the command the device receives. The trace is **always** fresh (PRD
     * FR-A4): a replay starts its own flow, so grouping it with the original push's trace would
     * merge two runs of the same journey into one.
     *
     * The injection id and the message id are deliberately **one value** (a message with no id of
     * its own is stamped with [id]). The device emits the injected row under the message id and
     * acks under the injection id, and the app's own messaging service re-logs the same push under
     * its message id moments later — so any daylight between the two ids means an injected push
     * shows up twice, the second time unmarked (correlation PRD §1a).
     */
    fun inject(
        message: PushMessage,
        id: String = message.messageId?.takeIf { it.isNotBlank() } ?: newId(),
        traceId: String = newTraceId(),
    ): PushInject = PushInject(id = id, traceId = traceId, message = message.copy(messageId = id))

    /**
     * One id for an injection: the message id, the injected row's envelope id and the ack's
     * correlation id are all this value. Short, like the ids the device generates.
     */
    fun newId(): String = "inj-" + UUID.randomUUID().toString().substring(0, 8)

    fun newTraceId(): String = "trc-" + UUID.randomUUID().toString().substring(0, 8)
}
