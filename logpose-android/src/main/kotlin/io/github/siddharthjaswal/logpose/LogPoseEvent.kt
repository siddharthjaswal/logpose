package io.github.siddharthjaswal.logpose

import io.github.siddharthjaswal.logpose.wire.Badge
import io.github.siddharthjaswal.logpose.wire.GenericEvent
import io.github.siddharthjaswal.logpose.wire.Section
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Builds a self-describing timeline event. Obtained from [LogPose.event].
 *
 * Everything here is expressed in plain Kotlin types — strings and maps, never serialization
 * types. That keeps the public API stable and, more importantly, lets the release `no-op`
 * artifact mirror it without depending on kotlinx-serialization.
 *
 * ```kotlin
 * LogPose.event("UserDao.insert") {
 *     subtitle = "users (3 rows)"
 *     badge("DB", Tone.INFO)
 *     took(14)
 *     code("SQL", "INSERT INTO users ...")
 *     json("Params", paramsJson)
 * }
 * ```
 */
class EventBuilder internal constructor(private val title: String) {

    /** Secondary line on the row — the title is the "who", this is the "what". */
    var subtitle: String? = null

    /** Correlation id. Reuse an id to update a row you already emitted. */
    var id: String = UUID.randomUUID().toString().substring(0, 8)

    /** Groups this event with others in the same flow. Set explicitly; never inferred. */
    var traceId: String? = null

    /** The event this one happened inside, within [traceId]. */
    var parentId: String? = null

    internal var at: Long = System.currentTimeMillis()
    internal var endedAt: Long? = null
    internal val badges = mutableListOf<Badge>()
    internal val sections = mutableListOf<Section>()

    /** Mark this event as having taken [millis] — renders as a span rather than a point. */
    fun took(millis: Long) { endedAt = at + millis }

    /** Mark this event as still running; close it later by re-emitting with the same [id]. */
    fun open() { endedAt = null }

    /** A short pill on the row. [tone] is semantic — see [Tone]. */
    fun badge(text: String, tone: String = Tone.MUTED) {
        badges += Badge(text, tone)
    }

    /** A plain-text block in the detail pane. */
    fun text(label: String, body: String) {
        sections += Section(label, Section.TYPE_TEXT, JsonPrimitive(body))
    }

    /** A monospaced block — SQL, a stack trace, a shell command. */
    fun code(label: String, body: String) {
        sections += Section(label, Section.TYPE_CODE, JsonPrimitive(body))
    }

    /**
     * A JSON block, rendered as a collapsible tree. [body] should be a JSON document; if it
     * doesn't parse it is shown as plain text rather than being dropped.
     */
    fun json(label: String, body: String) {
        val element = runCatching { Json.parseToJsonElement(body) }.getOrElse { JsonPrimitive(body) }
        sections += Section(label, Section.TYPE_JSON, element)
    }

    /** A key/value table — headers, attributes, tags. */
    fun kv(label: String, values: Map<String, String>) {
        sections += Section(label, Section.TYPE_KV, Json.encodeToJsonElement(values))
    }

    internal fun build(): GenericEvent =
        GenericEvent(title = title, subtitle = subtitle, badges = badges, sections = sections)
}

/** Semantic badge tones. The plugin maps these onto the active IDE theme. */
object Tone {
    const val INFO = Badge.TONE_INFO
    const val WARN = Badge.TONE_WARN
    const val ERROR = Badge.TONE_ERROR
    const val MUTED = Badge.TONE_MUTED
}
