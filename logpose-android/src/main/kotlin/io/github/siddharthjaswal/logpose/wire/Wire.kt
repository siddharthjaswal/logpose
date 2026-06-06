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
