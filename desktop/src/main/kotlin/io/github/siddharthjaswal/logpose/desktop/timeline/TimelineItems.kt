package io.github.siddharthjaswal.logpose.desktop.timeline

import io.github.siddharthjaswal.logpose.capture.Bookmark
import io.github.siddharthjaswal.logpose.capture.ScreenshotRef
import java.io.File

/** A screenshot on the timeline: from the session folder (live) or from an opened capture's bytes. */
data class ShotMark(val ref: ScreenshotRef, val file: File? = null, val bytes: ByteArray? = null) {
    fun load(): ByteArray? = bytes ?: file?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
    override fun equals(other: Any?) = other is ShotMark && other.ref == ref && other.file == file && (other.bytes === bytes)
    override fun hashCode() = ref.hashCode()
}

/** The tester's own marks on a session: bookmarks and screenshots, in the order they were made. */
data class SessionMarks(val bookmarks: List<Bookmark> = emptyList(), val shots: List<ShotMark> = emptyList()) {
    companion object { val EMPTY = SessionMarks() }
}

/** One line of the timeline: an event row, or a full-width marker row (never mistaken for an event). */
sealed interface TimelineItem {
    val key: String

    data class Event(val row: RowModel) : TimelineItem {
        override val key: String get() = row.event.id
    }

    /** [number] is 1-based in the order bookmarks were made — what the share sheet calls them. */
    data class Mark(val bookmark: Bookmark, val number: Int) : TimelineItem {
        override val key: String get() = "bookmark:$number:${bookmark.atMillis}"
    }

    data class Shot(val shot: ShotMark) : TimelineItem {
        override val key: String get() = "shot:${shot.ref.file}"
    }
}

object TimelineItems {

    /**
     * Rows stay in arrival order (the store's order); each marker goes in front of the first row
     * that started after it. Arrival order is not time order (a slow response lands late), so this
     * is a single forward pass rather than a sort — a late row with an early timestamp never drags
     * a marker backwards. Markers after every row trail the list.
     *
     * Marker times are the Mac's clock and event times the phone's; they are compared directly.
     */
    fun merge(rows: List<RowModel>, marks: SessionMarks): List<TimelineItem> {
        if (marks.bookmarks.isEmpty() && marks.shots.isEmpty()) return rows.map { TimelineItem.Event(it) }
        val pending = ArrayDeque(
            (marks.bookmarks.mapIndexed { i, b -> b.atMillis to TimelineItem.Mark(b, i + 1) } +
                marks.shots.map { it.ref.atMillis to TimelineItem.Shot(it) })
                .sortedBy { it.first },
        )
        val out = ArrayList<TimelineItem>(rows.size + pending.size)
        for (row in rows) {
            while (pending.isNotEmpty() && pending.first().first < row.event.timestampMillis) out.add(pending.removeFirst().second)
            out.add(TimelineItem.Event(row))
        }
        pending.forEach { out.add(it.second) }
        return out
    }
}
