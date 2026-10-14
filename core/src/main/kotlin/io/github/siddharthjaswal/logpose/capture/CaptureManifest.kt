package io.github.siddharthjaswal.logpose.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `manifest.json` inside a `.logpose` file (docs/mac-app-prd.md §6): everything about a capture
 * that is not itself a timeline event.
 *
 * **Forward-compatible by construction.** Every field has a default and the reader decodes with
 * `ignoreUnknownKeys`, so a file written by a newer host (with keys this build has never heard of)
 * still opens; only a [formatVersion] above [CaptureFormat.VERSION] is refused, and that number
 * is bumped only for a change an old reader would get *wrong*, never for an addition.
 *
 * Host clock vs device clock: event times are the device's (`Envelope.at`); bookmark and
 * screenshot times are the capturing Mac's. They are compared directly — fine for an NTP-synced
 * phone, off by the skew otherwise. Nothing here pretends to correct for it.
 */
@Serializable
data class CaptureManifest(
    val formatVersion: Int = CaptureFormat.VERSION,
    /** "LogPose for Mac 1.0.0", "LogPose plugin 1.9.3" — which host wrote the file. */
    val writtenBy: String = "",
    val writtenAtMillis: Long = 0,
    val device: DeviceInfo? = null,
    /** From the device's Hello: the app under test and its LogPose library version. */
    val app: AppInfo? = null,
    val session: Span = Span(),
    val privacy: Privacy = Privacy(),
    val bookmarks: List<Bookmark> = emptyList(),
    val screenshots: List<ScreenshotRef> = emptyList(),
    /** Distinct timeline events in `events.ndjson` (after in-place updates collapse). */
    val eventCount: Int = 0,
    /** What part of the recorded session this file holds, in words ("±2 min around bookmark 3"). */
    val scope: String? = null,
) {
    @Serializable
    data class DeviceInfo(
        val model: String? = null,
        val androidVersion: String? = null,
        val serial: String? = null,
        /**
         * Anything that makes this device not stock, in words — today the Mac app's *Also slim
         * Android itself* on an emulator ("Android slimmed by LogPose: 41 background packages
         * disabled, …"), which changes how the app behaves under memory pressure. Additive: older
         * readers ignore it.
         */
        val note: String? = null,
    )

    @Serializable
    data class AppInfo(val pkg: String, val libVersion: String? = null)

    /** Host epoch millis the session (or the exported slice of it) started and ended. */
    @Serializable
    data class Span(val startMillis: Long = 0, val endMillis: Long = 0)

    /**
     * What was done to the data before it left the machine, so a reader can tell a scrubbed
     * capture from a raw one. Device-side redaction (headers, secret query params) always applies —
     * it happens in the library, before anything reaches logcat.
     */
    @Serializable
    data class Privacy(
        val deviceRedaction: Boolean = true,
        val scrubbed: Boolean = false,
        val scrubKeys: List<String> = emptyList(),
        val scrubPatterns: Boolean = false,
        val includeBodies: Boolean = true,
        /** Total values the scrubber replaced — the number the share sheet's preview showed. */
        val scrubbedFields: Int = 0,
    )
}

/** A tester's "here": a host-clock instant with an optional note. */
@Serializable
data class Bookmark(val atMillis: Long, val note: String = "")

/** A device screenshot, stored as `screenshots/<file>` in the bundle. */
@Serializable
data class ScreenshotRef(val atMillis: Long, val file: String)

object CaptureFormat {
    const val VERSION = 1
    const val EXTENSION = "logpose"
    const val MANIFEST = "manifest.json"
    const val EVENTS = "events.ndjson"
    const val SCREENSHOTS_DIR = "screenshots/"

    /** Lenient on read (unknown keys are a newer writer, not an error), explicit on write. */
    internal val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
    }

    /** The one-line encoder for event and hello lines: the library's shape, defaults included
     *  (a Hello's `kind:"hello"` is a default, and without it the line stops being a hello). */
    val lineJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** A screenshot file name we are willing to read or write: flat, no path, PNG. */
    fun isSafeScreenshotName(name: String): Boolean =
        name.matches(Regex("[A-Za-z0-9._-]{1,128}\\.png")) && !name.startsWith(".")
}

class CaptureFormatException(message: String) : Exception(message)
