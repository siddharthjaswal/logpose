package io.github.siddharthjaswal.logpose.internal

import io.github.siddharthjaswal.logpose.LogPoseConfig
import io.github.siddharthjaswal.logpose.wire.Body
import io.github.siddharthjaswal.logpose.wire.MultipartPart
import okhttp3.Headers
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.GzipSource
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Reads request/response bodies without disturbing the actual HTTP exchange, and
 * converts them to the LogPose [Body] wire shape. Binary and multipart payloads
 * are summarized rather than dumped.
 */
internal object BodyCapture {

    private val UTF8 = StandardCharsets.UTF_8

    fun captureRequest(request: Request, config: LogPoseConfig): Body? {
        val body = request.body ?: return null
        val contentType = body.contentType()?.toString()

        if (body is MultipartBody) {
            val parts = body.parts.map { part -> describePart(part) }
            return Body(
                contentType = contentType,
                sizeBytes = body.contentLengthOrUnknown(),
                parts = parts,
            )
        }

        // Bodies that can only be consumed once (or stream both ways) must not be
        // read here — doing so would break the request.
        if (body.isDuplex() || body.isOneShot()) {
            return Body(contentType, sizeBytes = -1, text = "(streaming body not captured)")
        }

        val buffer = Buffer().also { body.writeTo(it) }
        val size = buffer.size
        if (!isProbablyText(contentType, buffer)) {
            return Body(contentType, size, text = "(binary body, $size bytes)")
        }
        val charset = body.contentType()?.charset(UTF8) ?: UTF8
        return buffer.toBody(contentType, size, config, charset)
    }

    fun captureResponse(response: Response, config: LogPoseConfig): Body? {
        val body = response.body ?: return null
        val contentType = body.contentType()?.toString()
        val declaredLength = body.contentLength() // -1 if unknown

        // Buffer at most maxBodyBytes+1 so memory stays bounded even for big media.
        val source = body.source()
        source.request(config.maxBodyBytes + 1)
        var peek = source.buffer.clone()

        if (peek.size > 0 && "gzip".equals(response.headers["Content-Encoding"], ignoreCase = true)) {
            peek = GzipSource(peek).use { gz -> Buffer().apply { writeAll(gz) } }
        }

        val bufferedBytes = peek.size
        val reportedSize = if (declaredLength >= 0) declaredLength else bufferedBytes
        if (!isProbablyText(contentType, peek)) {
            return Body(contentType, reportedSize, text = "(binary body, $reportedSize bytes)")
        }
        val charset = body.contentType()?.charset(UTF8) ?: UTF8
        return peek.toBody(contentType, reportedSize, config, charset, alreadyTruncated = bufferedBytes > config.maxBodyBytes)
    }

    fun headersToMap(headers: Headers, config: LogPoseConfig): Map<String, String> {
        val redact = config.redactHeaders.map { it.lowercase() }.toHashSet()
        val out = LinkedHashMap<String, String>(headers.size)
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            out[name] = if (name.lowercase() in redact) "██" else headers.value(i)
        }
        return out
    }

    private fun Buffer.toBody(
        contentType: String?,
        reportedSize: Long,
        config: LogPoseConfig,
        charset: Charset,
        alreadyTruncated: Boolean = false,
    ): Body {
        val available = size
        val take = minOf(available, config.maxBodyBytes)
        val text = readString(take, charset)
        val truncated = alreadyTruncated || available > config.maxBodyBytes || reportedSize > config.maxBodyBytes
        return Body(contentType, reportedSize, text, truncated = truncated)
    }

    private fun describePart(part: MultipartBody.Part): MultipartPart {
        val disposition = part.headers?.get("Content-Disposition")
        return MultipartPart(
            name = disposition?.let { extractDisposition(it, "name") },
            filename = disposition?.let { extractDisposition(it, "filename") },
            contentType = part.body.contentType()?.toString(),
            sizeBytes = runCatching { part.body.contentLength() }.getOrDefault(-1L),
        )
    }

    private fun extractDisposition(header: String, key: String): String? {
        val regex = Regex("$key=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        return regex.find(header)?.groupValues?.getOrNull(1)
    }

    private fun okhttp3.RequestBody.contentLengthOrUnknown(): Long =
        runCatching { contentLength() }.getOrDefault(-1L)

    /**
     * Heuristic: treat as text if the content-type looks textual AND the first
     * bytes contain no unexpected control characters (catches mislabeled binaries).
     */
    private fun isProbablyText(contentType: String?, buffer: Buffer): Boolean {
        val ct = contentType?.lowercase().orEmpty()
        val looksBinary = ct.startsWith("image/") || ct.startsWith("video/") ||
            ct.startsWith("audio/") || ct.contains("octet-stream") || ct.contains("pdf") ||
            ct.contains("zip") || ct.contains("protobuf") || ct.contains("grpc")
        if (looksBinary) return false

        val looksText = ct.contains("json") || ct.contains("xml") || ct.contains("text") ||
            ct.contains("html") || ct.contains("x-www-form-urlencoded") || ct.contains("javascript")

        return looksText || isProbablyUtf8(buffer)
    }

    /** Adapted from OkHttp's own logging interceptor: scan a prefix for control chars. */
    private fun isProbablyUtf8(buffer: Buffer): Boolean {
        return try {
            val prefix = Buffer()
            val byteCount = if (buffer.size < 64) buffer.size else 64
            buffer.copyTo(prefix, 0, byteCount)
            repeat(16) {
                if (prefix.exhausted()) return true
                val codePoint = prefix.readUtf8CodePoint()
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) return false
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
