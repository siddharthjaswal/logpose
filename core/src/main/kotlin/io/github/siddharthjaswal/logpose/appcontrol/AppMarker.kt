package io.github.siddharthjaswal.logpose.appcontrol

import io.github.siddharthjaswal.logpose.model.Badge
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.GenericEvent
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Section
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The timeline row an App action leaves behind — "Restarted by LogPose", "Cache cleared", "App data
 * cleared" — so a capture shows what was done to the app and when, next to the run boundary it caused.
 *
 * **Host-side only; the wire is untouched.** It is an ordinary [Envelope] carrying a self-describing
 * [GenericEvent] payload, the shape an app's own `LogPose.event { }` uses, under the kind [KIND] that
 * the device library never emits. So every renderer, filter (APP), MCP read and `.logpose` export
 * already handles it, and a capture file replays it as the same row: the parser decodes any kind it
 * has no special support for as a [GenericEvent].
 *
 * A point event (`endedAt == at`), so it is never "in flight" and never abandoned.
 */
object AppMarker {

    /** The marker's kind. Not a wire kind: only hosts write it, never `logpose-android`. */
    const val KIND = "logpose"

    private val json = Json { encodeDefaults = true; explicitNulls = false }

    fun isMarker(event: LogEvent): Boolean = event.kind == KIND

    fun event(
        title: String,
        action: AppAction,
        pkg: String,
        serial: String,
        atMillis: Long,
        /** Extra facts worth a line each in the detail pane, e.g. "external cache" → "cleared". */
        details: Map<String, String> = emptyMap(),
        id: String = "logpose-app-${UUID.randomUUID()}",
    ): LogEvent.Generic {
        val generic = GenericEvent(
            title = title,
            subtitle = pkg,
            badges = listOf(
                Badge("LogPose", Badge.TONE_INFO),
                Badge(action.label, if (action.destructive) Badge.TONE_WARN else Badge.TONE_MUTED),
            ),
            sections = listOf(
                Section(
                    label = "What LogPose did",
                    type = Section.TYPE_KV,
                    body = buildJsonObject {
                        put("action", action.label)
                        put("package", pkg)
                        put("device", serial)
                        details.forEach { (k, v) -> put(k, v) }
                    },
                ),
            ),
        )
        val envelope = Envelope(
            kind = KIND,
            id = id,
            at = atMillis,
            endedAt = atMillis,
            payload = json.encodeToJsonElement(GenericEvent.serializer(), generic),
        )
        return LogEvent.Generic(generic, envelope)
    }

    /** The marker for a finished [report]: its title follows what actually happened. */
    fun of(report: AppControl.Report, atMillis: Long): LogEvent.Generic = event(
        title = report.markerTitle,
        action = report.action,
        pkg = report.pkg,
        serial = report.serial,
        atMillis = atMillis,
        details = buildMap {
            if (report.action == AppAction.CLEAR_CACHE && report.cleared) {
                put("internal cache", "cache/ and code_cache/ emptied")
                report.externalCache?.let { put("external cache", it.name.lowercase()) }
            }
            if (report.action != AppAction.RESTART && !report.cleared) put("clear", "did not complete — restarted only")
            if (report.regranted.isNotEmpty()) put("permissions re-granted", report.regranted.joinToString())
            if (report.regrantFailed.isNotEmpty()) put("permissions NOT re-granted", report.regrantFailed.joinToString())
        },
    )
}
