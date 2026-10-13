package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import io.github.siddharthjaswal.logpose.desktop.timeline.ShotMark
import io.github.siddharthjaswal.logpose.desktop.timeline.TimelineItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun clock(at: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(at))

/**
 * A bookmark on the timeline: a full-width marker, not an event row — an accent rail, an outlined
 * label, the time and the tester's note. Outlined (never filled) so it can't be read as a selected
 * row (accent tint) or an intervention (solid accent).
 */
@Composable
fun BookmarkRow(item: TimelineItem.Mark) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(28.dp).background(p.bg1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(p.accent))
        Spacer(Modifier.width(9.dp))
        Pill("BOOKMARK ${item.number}", p.accent, stroke = p.accent)
        Spacer(Modifier.width(10.dp))
        T(clock(item.bookmark.atMillis), p.textDim, style = Type.monoSmall)
        Spacer(Modifier.width(10.dp))
        T(item.bookmark.note.ifBlank { "No note" }, if (item.bookmark.note.isBlank()) p.textMuted else p.text, Modifier.weight(1f), weight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
}

/** A device screenshot on the timeline: a thumbnail at the moment it was taken. Click opens it. */
@Composable
fun ScreenshotRow(shot: ShotMark) {
    val p = LocalPalette.current
    val image by produceState<ImageBitmap?>(null, shot) {
        value = withContext(Dispatchers.IO) {
            shot.load()?.let { bytes -> runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() }
        }
    }
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(p.bg1)
            .clickable(enabled = shot.file != null) { shot.file?.let { f -> runCatching { java.awt.Desktop.getDesktop().open(f) } } }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(52.dp).background(p.textMuted))
        Spacer(Modifier.width(9.dp))
        val shape = RoundedCornerShape(4.dp)
        Box(Modifier.size(width = 26.dp, height = 52.dp).clip(shape).background(p.bg3, shape).border(1.dp, p.borderStrong, shape)) {
            image?.let { Image(it, contentDescription = "Screenshot", Modifier.fillMaxWidth().fillMaxHeight(), contentScale = ContentScale.Crop) }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("SCREENSHOT", p.textDim, stroke = p.borderStrong)
                Spacer(Modifier.width(10.dp))
                T(clock(shot.ref.atMillis), p.textDim, style = Type.monoSmall)
            }
            Spacer(Modifier.height(4.dp))
            T(if (shot.file != null) "Click to open" else shot.ref.file, p.textMuted, style = Type.small)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.borderSubtle))
}
