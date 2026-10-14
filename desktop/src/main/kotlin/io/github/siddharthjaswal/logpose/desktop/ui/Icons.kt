package io.github.siddharthjaswal.logpose.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The window chrome's icons: one stroke family drawn on a 16-unit grid (1.4-unit lines, round caps),
 * tinted by the caller from the palette — so they follow light/dark like the text beside them and
 * never mix with a font's glyph weights.
 */
enum class Ico {
    USB, WIFI, EMULATOR, PHONE, CHEVRON_DOWN, CHECK, SEARCH, GEAR, BOOKMARK, CAMERA, FOLDER, SHARE, TRASH,
    PLUS, STOP, PLAY, PANE_LIST, PANE_DETAIL, PANE_MOCKS, POWER, UNLINK, CLOSE, PULSE, HELP, COPY, BOLT, REFRESH, MORE, FILE,
}

@Composable
fun Icon(ico: Ico, tint: Color, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) { drawIco(ico, tint) }
}

private fun DrawScope.drawIco(ico: Ico, c: Color) {
    val u = size.minDimension / 16f
    val st = Stroke(width = 1.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun o(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, o(x1, y1), o(x2, y2), st.width, StrokeCap.Round)
    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 1.5f) =
        drawRoundRect(c, o(x, y), Size(w * u, h * u), CornerRadius(r * u), style = st)
    fun fillRect(x: Float, y: Float, w: Float, h: Float, r: Float = 1f) =
        drawRoundRect(c, o(x, y), Size(w * u, h * u), CornerRadius(r * u))
    fun path(closed: Boolean = false, vararg pts: Pair<Float, Float>) {
        val p = Path()
        pts.forEachIndexed { i, (x, y) -> if (i == 0) p.moveTo(x * u, y * u) else p.lineTo(x * u, y * u) }
        if (closed) p.close()
        drawPath(p, c, style = st)
    }
    fun circle(x: Float, y: Float, r: Float) = drawCircle(c, r * u, o(x, y), style = st)
    fun dot(x: Float, y: Float, r: Float) = drawCircle(c, r * u, o(x, y))
    fun arc(x: Float, y: Float, r: Float, start: Float, sweep: Float) =
        drawArc(c, start, sweep, false, o(x - r, y - r), Size(2 * r * u, 2 * r * u), style = st)

    when (ico) {
        Ico.USB -> {
            line(8f, 2.5f, 8f, 13f)
            path(false, 6.2f to 4.4f, 8f to 2.4f, 9.8f to 4.4f)
            path(false, 8f to 9.5f, 4.5f to 7.5f, 4.5f to 5.5f)
            path(false, 8f to 11f, 11.5f to 9f, 11.5f to 7f)
            fillRect(3.6f, 4.4f, 1.8f, 1.8f, 0.3f)
            dot(11.5f, 6.4f, 1f)
            dot(8f, 13.2f, 1.4f)
        }
        Ico.WIFI -> {
            arc(8f, 13f, 10f, 225f, 90f)
            arc(8f, 13f, 6.6f, 225f, 90f)
            arc(8f, 13f, 3.3f, 225f, 90f)
            dot(8f, 13f, 1.1f)
        }
        Ico.EMULATOR -> {
            rect(4f, 1.8f, 8f, 12.4f, 2f)
            path(true, 7f to 6f, 10f to 8f, 7f to 10f)
        }
        Ico.PHONE -> {
            rect(4f, 1.8f, 8f, 12.4f, 2f)
            line(7f, 12f, 9f, 12f)
        }
        Ico.CHEVRON_DOWN -> path(false, 4.5f to 6.5f, 8f to 10f, 11.5f to 6.5f)
        Ico.CHECK -> path(false, 3.5f to 8.5f, 6.5f to 11.5f, 12.5f to 4.5f)
        Ico.SEARCH -> { circle(7f, 7f, 4.2f); line(10.2f, 10.2f, 13.5f, 13.5f) }
        Ico.GEAR -> {
            circle(8f, 8f, 2.2f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val (cx, cy) = 8f to 8f
                line(cx + 4.6f * Math.cos(a).toFloat(), cy + 4.6f * Math.sin(a).toFloat(), cx + 6.3f * Math.cos(a).toFloat(), cy + 6.3f * Math.sin(a).toFloat())
            }
            circle(8f, 8f, 4.6f)
        }
        Ico.BOOKMARK -> path(true, 4.5f to 2.5f, 11.5f to 2.5f, 11.5f to 13.5f, 8f to 10.8f, 4.5f to 13.5f)
        Ico.CAMERA -> {
            path(true, 2f to 5f, 5f to 5f, 6.2f to 3.2f, 9.8f to 3.2f, 11f to 5f, 14f to 5f, 14f to 12.8f, 2f to 12.8f)
            circle(8f, 8.8f, 2.4f)
        }
        Ico.FOLDER -> path(true, 2f to 4f, 6f to 4f, 7.5f to 5.5f, 14f to 5.5f, 14f to 12.5f, 2f to 12.5f)
        Ico.FILE -> { path(true, 4f to 2f, 9.5f to 2f, 12.5f to 5f, 12.5f to 14f, 4f to 14f); path(false, 9.5f to 2f, 9.5f to 5f, 12.5f to 5f) }
        Ico.SHARE -> {
            path(false, 5.5f to 6f, 3.5f to 6f, 3.5f to 14f, 12.5f to 14f, 12.5f to 6f, 10.5f to 6f)
            line(8f, 1.8f, 8f, 9.5f)
            path(false, 5.5f to 4.3f, 8f to 1.8f, 10.5f to 4.3f)
        }
        Ico.TRASH -> {
            line(2.5f, 4.2f, 13.5f, 4.2f)
            path(false, 6f to 4.2f, 6f to 2.3f, 10f to 2.3f, 10f to 4.2f)
            path(false, 3.8f to 4.2f, 4.6f to 14f, 11.4f to 14f, 12.2f to 4.2f)
            line(6.7f, 7f, 6.9f, 11.4f); line(9.3f, 7f, 9.1f, 11.4f)
        }
        Ico.PLUS -> { line(8f, 3f, 8f, 13f); line(3f, 8f, 13f, 8f) }
        Ico.STOP -> fillRect(4f, 4f, 8f, 8f, 1.5f)
        Ico.PLAY -> {
            val p = Path().apply { moveTo(5f * u, 3.2f * u); lineTo(13f * u, 8f * u); lineTo(5f * u, 12.8f * u); close() }
            drawPath(p, c)
        }
        Ico.PANE_LIST -> { rect(1.8f, 2.8f, 12.4f, 10.4f); line(4.5f, 6f, 11.5f, 6f); line(4.5f, 8.5f, 11.5f, 8.5f); line(4.5f, 11f, 9f, 11f) }
        Ico.PANE_DETAIL -> { rect(1.8f, 2.8f, 12.4f, 10.4f); line(9.5f, 2.8f, 9.5f, 13.2f); fillRect(9.5f, 2.8f, 4.7f, 10.4f, 1.5f) }
        Ico.PANE_MOCKS -> { rect(1.8f, 2.8f, 12.4f, 10.4f); line(1.8f, 7f, 14.2f, 7f); fillRect(1.8f, 2.8f, 12.4f, 4.2f, 1.5f) }
        Ico.POWER -> { arc(8f, 8.8f, 5f, -50f, 280f); line(8f, 2f, 8f, 7.5f) }
        Ico.UNLINK -> {
            path(false, 6.5f to 4.5f, 8f to 3f, 10.2f to 3f, 13f to 5.8f, 13f to 8f, 11.5f to 9.5f)
            path(false, 9.5f to 11.5f, 8f to 13f, 5.8f to 13f, 3f to 10.2f, 3f to 8f, 4.5f to 6.5f)
            line(2.5f, 2.5f, 13.5f, 13.5f)
        }
        Ico.CLOSE -> { line(4f, 4f, 12f, 12f); line(12f, 4f, 4f, 12f) }
        Ico.PULSE -> path(false, 1.5f to 8.5f, 4.5f to 8.5f, 6.2f to 4f, 9.3f to 13f, 11f to 8.5f, 14.5f to 8.5f)
        Ico.HELP -> {
            circle(8f, 8f, 6.2f)
            arc(8f, 6.4f, 2f, 180f, 230f)
            line(8f, 8.6f, 8f, 9.6f)
            dot(8f, 11.8f, 0.9f)
        }
        Ico.COPY -> { rect(5f, 5f, 9f, 9f); path(false, 3.5f to 11f, 2f to 11f, 2f to 2f, 11f to 2f, 11f to 3.5f) }
        Ico.BOLT -> path(true, 9f to 1.5f, 3.5f to 9f, 7.5f to 9f, 6.8f to 14.5f, 12.5f to 7f, 8.5f to 7f)
        Ico.REFRESH -> { arc(8f, 8f, 5.2f, -60f, 300f); path(false, 10.6f to 1.8f, 10.8f to 4.6f, 13.6f to 4.4f) }
        Ico.MORE -> { dot(3.5f, 8f, 1.2f); dot(8f, 8f, 1.2f); dot(12.5f, 8f, 1.2f) }
    }
}
