package io.github.siddharthjaswal.logpose.toolwindow

import io.github.siddharthjaswal.logpose.capture.Capture

/**
 * The words and rules of the tool window's read-only *Open Capture File…* view, kept free of Swing
 * so they are unit-tested. The panel owns the state; this owns what it says and what it allows.
 */
object CaptureFileView {

    /** `Viewing bug.logpose (read-only) · 42 events · in.shadowfax.gandalf · Pixel 9a · ±2 min around bookmark 1`. */
    fun bannerText(fileName: String, capture: Capture): String {
        val m = capture.manifest
        val facts = listOfNotNull(
            "${capture.events.size} ${if (capture.events.size == 1) "event" else "events"}",
            (m?.app?.pkg ?: capture.replay.hellos.lastOrNull()?.pkg),
            m?.device?.model,
            m?.scope?.takeIf { it != "whole session" },
            m?.bookmarks?.size?.takeIf { it > 0 }?.let { "$it bookmark${if (it == 1) "" else "s"}" },
            m?.privacy?.takeIf { it.scrubbed }?.let { "PII scrubbed" },
        )
        return "Viewing $fileName (read-only) · " + facts.joinToString(" · ")
    }

    fun confirmMessage(fileName: String): String =
        "Opening $fileName stops the live capture and replaces the timeline with the file's events " +
            "(read-only). Use \"Back to live capture\" on the banner to resume."

    /** Said once the file is in, when some of it couldn't be read. */
    fun malformedNotice(capture: Capture): String? =
        capture.malformedLines.takeIf { it > 0 }?.let { "$it unreadable line${if (it == 1) "" else "s"} skipped" }

    /** Capture, clear, push and mock writes all act on a live device — none apply to a file. */
    fun liveActionsEnabled(viewingFile: String?): Boolean = viewingFile == null

    const val READ_ONLY_HINT = "Read-only capture file — use \"Back to live capture\" first"
}
