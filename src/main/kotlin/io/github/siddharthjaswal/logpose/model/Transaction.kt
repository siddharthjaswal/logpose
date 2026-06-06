package io.github.siddharthjaswal.logpose.model

import kotlinx.serialization.Serializable

/**
 * The wire contract between the on-device LogPose interceptor and this plugin.
 *
 * The interceptor builds ONE [Transaction] per HTTP exchange (request + response
 * together) and emits it as a single line of JSON. Emitting the whole exchange
 * atomically is what fixes the classic logcat problem where concurrent requests
 * interleave their lines and bodies get mismatched.
 */
@Serializable
data class Transaction(
    /** Stable id correlating request and response. Generated on the device. */
    val id: String,
    /** Epoch millis when the request left the client. */
    val startedAtMillis: Long = 0,
    val request: Request,
    /** Null until the response arrives (or if the call failed before responding). */
    val response: Response? = null,
    /** Total round-trip time in millis, if known. */
    val durationMillis: Long? = null,
    /** Populated when the call threw (timeout, connection reset, etc.). */
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

/**
 * A request or response payload. For textual payloads [text] holds the content.
 * For multipart uploads (S3 / GCS media) we deliberately do NOT ship raw bytes —
 * [parts] carries per-part metadata instead, so large binary uploads stay cheap
 * and readable.
 */
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

/**
 * Envelope used when a [Transaction] is too large for a single logcat line
 * (logcat truncates entries at ~4 KB). The interceptor splits the JSON payload
 * into ordered chunks sharing one [id]; the plugin reassembles them.
 */
@Serializable
data class Chunk(
    val id: String,
    val seq: Int,
    val total: Int,
    val payload: String,
)
