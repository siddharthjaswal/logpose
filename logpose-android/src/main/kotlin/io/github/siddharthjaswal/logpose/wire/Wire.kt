package io.github.siddharthjaswal.logpose.wire

import kotlinx.serialization.Serializable

/**
 * The on-the-wire representation emitted by the LogPose interceptor and consumed
 * by the LogPose IDE plugin. This MUST stay structurally in sync with the
 * plugin's `model/Transaction.kt`.
 */
@Serializable
data class Transaction(
    val id: String,
    val startedAtMillis: Long = 0,
    val request: Request,
    val response: Response? = null,
    val durationMillis: Long? = null,
    val error: String? = null,
)

@Serializable
data class Request(
    val method: String,
    val url: String,
    val host: String = "",
    val path: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: Body? = null,
)

@Serializable
data class Response(
    val code: Int,
    val message: String = "",
    val headers: Map<String, String> = emptyMap(),
    val body: Body? = null,
)

@Serializable
data class Body(
    val contentType: String? = null,
    val sizeBytes: Long = 0,
    val text: String? = null,
    val truncated: Boolean = false,
    val parts: List<MultipartPart>? = null,
)

@Serializable
data class MultipartPart(
    val name: String? = null,
    val filename: String? = null,
    val contentType: String? = null,
    val sizeBytes: Long = 0,
)

@Serializable
data class Chunk(
    val id: String,
    val seq: Int,
    val total: Int,
    val payload: String,
)

/**
 * A Firebase Cloud Messaging event (an incoming push, or a registration-token refresh),
 * emitted on the same logcat tag as [Transaction] and told apart by [kind] = "fcm".
 *
 * FCM is push, not OkHttp traffic — the host app feeds these in explicitly via
 * `LogPose.logFcmMessage` / `LogPose.logFcmToken` (see `LogPoseFcm.kt`). Kept structurally
 * in sync with the plugin's `model/Transaction.kt`.
 */
@Serializable
data class FcmMessage(
    /** Discriminator: always "fcm", so the plugin can tell this from a [Transaction]. */
    val kind: String = "fcm",
    /** Correlation id — the FCM messageId when present, otherwise a generated short id. */
    val id: String,
    /** "message" (an incoming push) or "token" (onNewToken). */
    val event: String = "message",
    /** Host-device epoch millis when the app handed the event to LogPose. */
    val receivedAtMillis: Long = 0,
    // message events:
    val messageId: String? = null,
    val from: String? = null,
    val to: String? = null,
    val collapseKey: String? = null,
    val messageType: String? = null,
    val sentTimeMillis: Long? = null,
    val ttlSeconds: Int? = null,
    val priority: Int? = null,
    val notification: FcmNotification? = null,
    val data: Map<String, String> = emptyMap(),
    // token events:
    val token: String? = null,
)

@Serializable
data class FcmNotification(
    val title: String? = null,
    val body: String? = null,
    val channelId: String? = null,
    val clickAction: String? = null,
    val imageUrl: String? = null,
)
