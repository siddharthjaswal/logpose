package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.IconUtil
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import javax.swing.Icon

/**
 * The per-kind glyph shown in the timeline's row gutter and the detail header chip — a single
 * source of the type, replacing the doubled type text (a kind label plus a truncated state pill).
 *
 * The SVGs ship monochrome; each is tinted to its [Theme.typeColor] at load, so the gutter icon
 * and the TYPE filter chip always agree. Cached per kind and per theme brightness, so a light/dark
 * switch re-tints without leaking a stale colour.
 */
object TypeIcons {

    private val paths = mapOf(
        Envelope.KIND_HTTP to "/icons/types/net.svg",
        Envelope.KIND_FCM to "/icons/types/fcm.svg",
        Envelope.KIND_DB to "/icons/types/db.svg",
        Envelope.KIND_WORKER to "/icons/types/work.svg",
        Envelope.KIND_CONFIG to "/icons/types/conf.svg",
        Envelope.KIND_ANALYTICS to "/icons/types/anly.svg",
        Envelope.KIND_EVENT to "/icons/types/app.svg",
    )

    private val cache = HashMap<Pair<String, Boolean>, Icon>()

    /** Tinted icon for a raw kind string; unknown (app-defined) kinds get the generic APP glyph. */
    @Synchronized
    fun forKind(kind: String): Icon {
        val path = paths[kind] ?: paths.getValue(Envelope.KIND_EVENT)
        return cache.getOrPut(kind to !JBColor.isBright()) {
            IconUtil.colorize(IconLoader.getIcon(path, TypeIcons::class.java), Theme.typeColor(kind))
        }
    }

    fun forEvent(event: LogEvent): Icon = forKind(event.kind)
}
