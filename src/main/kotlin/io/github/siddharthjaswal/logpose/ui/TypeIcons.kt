package io.github.siddharthjaswal.logpose.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.EmptyIcon
import io.github.siddharthjaswal.logpose.model.Envelope
import io.github.siddharthjaswal.logpose.model.LogEvent
import javax.swing.Icon

/**
 * The per-kind glyph shown in the timeline's row gutter and the detail header — a single source of
 * the type, replacing the doubled type text (a kind label plus a truncated state pill).
 *
 * Each SVG ships with its type hue baked in (the same hue as that kind's TYPE filter chip), so
 * there is no runtime tinting to fail. Loading is wrapped so a missing/unparseable icon degrades
 * to an empty slot rather than taking down the whole tool window — this is built at row-render
 * time, on the EDT, where a throw would blank the panel.
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

    private val empty: Icon = EmptyIcon.create(16)
    private val cache = HashMap<String, Icon>()

    /** Tinted icon for a raw kind string; unknown (app-defined) kinds get the generic APP glyph. */
    @Synchronized
    fun forKind(kind: String): Icon = cache.getOrPut(kind) {
        val path = paths[kind] ?: paths.getValue(Envelope.KIND_EVENT)
        runCatching { IconLoader.getIcon(path, TypeIcons::class.java) }.getOrDefault(empty)
    }

    fun forEvent(event: LogEvent): Icon = forKind(event.kind)
}
