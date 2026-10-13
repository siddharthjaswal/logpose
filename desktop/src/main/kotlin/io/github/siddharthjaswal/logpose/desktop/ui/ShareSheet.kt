package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.siddharthjaswal.logpose.desktop.app.ShareUi
import io.github.siddharthjaswal.logpose.desktop.share.FileTransferable
import io.github.siddharthjaswal.logpose.desktop.share.ShareOptions
import io.github.siddharthjaswal.logpose.desktop.share.SharePreview
import io.github.siddharthjaswal.logpose.desktop.share.ShareScope
import io.github.siddharthjaswal.logpose.desktop.theme.LocalPalette
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The share verbs; no-ops by default so renders need none. */
class ShareActions(
    val onClose: () -> Unit = {},
    val onOptions: ((ShareOptions) -> ShareOptions) -> Unit = {},
    val onChooseFolder: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onReveal: () -> Unit = {},
    val onCopyFile: () -> Unit = {},
    val onCopySummary: () -> Unit = {},
)

/**
 * The share sheet (PRD §5.3): what to include, privacy with a live preview of exactly what leaves
 * the machine, and the output. The preview is on the sheet itself, above the button that writes the
 * file — so nothing can be exported without having been shown.
 */
@Composable
fun ShareSheet(ui: ShareUi?, actions: ShareActions) {
    ui ?: return
    Modal(actions.onClose, width = 780.dp) {
        Column(Modifier.heightIn(max = 760.dp)) {
            SheetHeader("Share capture", actions.onClose)
            val p = LocalPalette.current
            T(
                "From ${ui.sourceLabel}" + if (ui.totalEvents > 0) " · ${ui.totalEvents} events recorded" else "",
                p.textMuted, Modifier.padding(horizontal = 24.dp), style = Type.small,
            )
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                when {
                    ui.loading && ui.preview == null -> T("Reading the session record…", p.textDim)
                    ui.preview == null -> T(ui.error ?: "Nothing to share.", p.danger, maxLines = 3)
                    else -> {
                        ScopeSection(ui, ui.preview, actions)
                        PrivacySection(ui.preview, actions, compact = ui.exported != null)
                        OutputSection(ui, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(n: Int, text: String) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp).background(p.bg3, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            T("$n", p.textDim, style = Type.small, weight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(8.dp))
        T(text, p.text, size = 13.5.sp, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier.height(28.dp)
            .background(if (selected) p.accentTint else p.bg1, shape)
            .border(1.dp, if (selected) p.accent else p.borderSubtle, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, when { !enabled -> p.textMuted; selected -> p.text; else -> p.textDim }, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ScopeSection(ui: ShareUi, preview: SharePreview, actions: ShareActions) {
    val p = LocalPalette.current
    val scope = ui.options.scope
    val has = ui.bookmarks.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(1, "What to include")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Whole session", scope == ShareScope.Whole) { actions.onOptions { it.copy(scope = ShareScope.Whole) } }
            Chip("Since last bookmark", scope == ShareScope.SinceLastBookmark, enabled = has) {
                actions.onOptions { it.copy(scope = ShareScope.SinceLastBookmark) }
            }
            Chip("Around a bookmark", scope is ShareScope.AroundBookmark, enabled = has) {
                actions.onOptions { it.copy(scope = ShareScope.AroundBookmark(ui.bookmarks.lastIndex, ShareScope.DEFAULT_MINUTES)) }
            }
            Chip("Selected rows (${ui.selectedCount})", scope == ShareScope.Selected, enabled = ui.selectedCount > 0) {
                actions.onOptions { it.copy(scope = ShareScope.Selected) }
            }
        }
        if (!has) T("No bookmarks in this session — press ⌘B while testing to mark a moment.", p.textMuted, style = Type.small)
        if (scope is ShareScope.AroundBookmark) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                T("Minutes", p.textMuted, style = Type.small)
                ShareScope.MINUTE_CHOICES.forEach { m ->
                    Chip("±$m", scope.minutes == m) { actions.onOptions { it.copy(scope = scope.copy(minutes = m)) } }
                }
                Spacer(Modifier.width(10.dp))
                T("around", p.textMuted, style = Type.small)
                // The most recent few bookmarks; the last one is the default.
                ui.bookmarks.withIndex().toList().takeLast(4).forEach { (i, b) ->
                    Chip("${i + 1}" + if (b.note.isNotBlank()) " · ${b.note.take(18)}" else "", scope.index == i) {
                        actions.onOptions { it.copy(scope = scope.copy(index = i)) }
                    }
                }
            }
        }
        val span = preview.span
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        val extras = listOfNotNull(
            preview.bookmarks.size.takeIf { it > 0 }?.let { "$it bookmark${if (it == 1) "" else "s"}" },
            preview.screenshots.size.takeIf { it > 0 }?.let { "$it screenshot${if (it == 1) "" else "s"}" },
        )
        T(
            "${preview.events.size} of ${ui.totalEvents} events · ${fmt.format(Date(span.startMillis))}–${fmt.format(Date(span.endMillis))}" +
                (if (extras.isEmpty()) "" else " · " + extras.joinToString(" · ")),
            p.textDim, style = Type.small,
        )
    }
}

@Composable
private fun PrivacySection(preview: SharePreview, actions: ShareActions, compact: Boolean = false) {
    val p = LocalPalette.current
    val o = preview.options
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(2, "Privacy")
        Check("Scrub personal data — names, phone numbers, emails, addresses, locations", o.scrub) { v -> actions.onOptions { it.copy(scrub = v) } }
        Check("Include request and response bodies", o.includeBodies) { v -> actions.onOptions { it.copy(includeBodies = v) } }
        T("Headers and secret query parameters are already redacted on the phone.", p.textMuted, style = Type.small)
        // The preview: the counts, then a sample of exactly what leaves this Mac.
        val shape = RoundedCornerShape(8.dp)
        Column(
            Modifier.fillMaxWidth().background(p.bg0, shape).border(1.dp, p.borderSubtle, shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val r = preview.report
            val left = "${r.bodiesDropped} ${if (r.bodiesDropped == 1) "body" else "bodies"} left out"
            val headline = when {
                !o.scrub && !o.includeBodies -> "Scrubber off · $left"
                !o.scrub -> "Scrubber off — bodies leave exactly as captured"
                !o.includeBodies && r.total == 0 -> "$left · nothing else to scrub"
                !o.includeBodies -> r.summary().replaceFirstChar { it.uppercase() } + " · $left"
                r.total == 0 -> "Scrubber on — found nothing to scrub"
                else -> r.summary().replaceFirstChar { it.uppercase() }
            }
            T("Preview · $headline", if (o.scrub && r.total > 0) p.text else p.textDim, weight = FontWeight.SemiBold, maxLines = 2)
            if (o.scrub && r.byKind.isNotEmpty()) {
                T("by kind: " + r.byKind.entries.joinToString(" · ") { "${it.key} ${it.value}" } + " · ${r.eventsTouched} events touched", p.textMuted, style = Type.small)
            }
            if (preview.samples.isEmpty() && !compact) {
                T("No bodies in this selection.", p.textMuted, style = Type.small)
            }
            // Once the file is written the sample has done its job; keep the output in view.
            if (!compact) preview.samples.forEach { s ->
                T(s.title + if (s.scrubbed > 0) "  ·  ${s.scrubbed} scrubbed" else "", p.textDim, style = Type.monoSmall)
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 120.dp).background(p.bg2, RoundedCornerShape(6.dp))
                        .verticalScroll(rememberScrollState()).padding(10.dp),
                ) {
                    T(s.text, p.text, style = Type.monoSmall.copy(lineHeight = 15.sp), maxLines = 200)
                }
            }
        }
    }
}

@Composable
private fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = RoundedCornerShape(4.dp)
        Box(
            Modifier.size(16.dp).background(if (checked) p.accent else p.bg1, shape)
                .border(1.dp, if (checked) p.accent else p.borderStrong, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
                    val path = Path().apply { moveTo(size.width * 0.1f, size.height * 0.55f); lineTo(size.width * 0.4f, size.height * 0.85f); lineTo(size.width * 0.92f, size.height * 0.15f) }
                    drawPath(path, p.onAccent, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        T(label, p.text)
    }
}

@Composable
private fun OutputSection(ui: ShareUi, actions: ShareActions) {
    val p = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(3, "Output")
        Row(verticalAlignment = Alignment.CenterVertically) {
            T("Save to", p.textMuted, style = Type.small)
            Spacer(Modifier.width(8.dp))
            T(ui.folder.path.replace(System.getProperty("user.home"), "~"), p.text, Modifier.weight(1f, fill = false), style = Type.monoSmall)
            Spacer(Modifier.width(8.dp))
            Button("Change…", actions.onChooseFolder)
        }
        val f = ui.exported
        if (f == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(if (ui.exporting) "Writing…" else "Create capture file", actions.onExport, primary = true, enabled = !ui.exporting && ui.preview != null)
                Spacer(Modifier.width(10.dp))
                T("A .logpose file plus a Slack-ready summary.", p.textMuted, style = Type.small)
            }
        } else {
            FileTile(f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button("Copy Slack summary", actions.onCopySummary, primary = true)
                Button("Reveal in Finder", actions.onReveal)
                Button("Copy file", actions.onCopyFile)
            }
            ui.summary?.let { s ->
                Box(Modifier.fillMaxWidth().heightIn(max = 130.dp).background(p.bg2, RoundedCornerShape(6.dp)).verticalScroll(rememberScrollState()).padding(10.dp)) {
                    T(s, p.textDim, style = Type.monoSmall.copy(lineHeight = 15.sp), maxLines = 60)
                }
            }
        }
        ui.copied?.let { T(it, p.ok, style = Type.small) }
        ui.error?.let { T(it, p.danger, style = Type.small, maxLines = 3) }
    }
}

/**
 * The exported file as a thing you can pick up: drag it into Slack or Finder. A real AWT drag
 * (a `java.io.File` list), via Compose's drag-and-drop source; *Copy file* is the same payload on
 * the clipboard, for when dragging is awkward.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun FileTile(file: File) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().background(p.bg2, shape).border(1.dp, p.borderStrong, shape)
            .dragAndDropSource(drawDragDecoration = {
                drawRoundRect(p.accent, size = size.copy(width = 34.dp.toPx(), height = 42.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            }) {
                detectDragGestures(
                    onDragStart = {
                        startTransfer(
                            DragAndDropTransferData(
                                transferable = DragAndDropTransferable(FileTransferable(listOf(file))),
                                supportedActions = listOf(DragAndDropTransferAction.Copy),
                                dragDecorationOffset = Offset(17f, 21f),
                            ),
                        )
                    },
                    onDrag = { _, _ -> },
                )
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileGlyph()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(file.name, p.text, weight = FontWeight.SemiBold)
            T("${sizeText(file.length())} · drag into Slack or Finder", p.textMuted, style = Type.small)
        }
    }
}

@Composable
private fun FileGlyph() {
    val p = LocalPalette.current
    androidx.compose.foundation.Canvas(Modifier.size(width = 30.dp, height = 38.dp)) {
        val w = size.width; val h = size.height; val fold = w * 0.32f
        val body = Path().apply { moveTo(0f, 0f); lineTo(w - fold, 0f); lineTo(w, fold); lineTo(w, h); lineTo(0f, h); close() }
        drawPath(body, p.accent)
        val ear = Path().apply { moveTo(w - fold, 0f); lineTo(w - fold, fold); lineTo(w, fold); close() }
        drawPath(ear, p.accentHover)
        for (i in 0..2) drawLine(p.onAccent, Offset(w * 0.18f, h * (0.5f + i * 0.13f)), Offset(w * 0.78f, h * (0.5f + i * 0.13f)), strokeWidth = 1.5.dp.toPx())
    }
}

fun sizeText(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
