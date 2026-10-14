package io.github.siddharthjaswal.logpose.desktop.app

/** Which right-hand pane is asking for room: the event detail, or a flow's waterfall. */
enum class Inspector { NONE, DETAIL, WATERFALL }

/**
 * The window's pane sizes and collapsed states, in dp. Pure: [resolve] decides the actual widths for
 * a window, [encode]/[decode] are what the settings file keeps between launches.
 *
 *  - [detailWidth] / [waterfallWidth]: the right pane's width for each inspector (a waterfall wants
 *    more room than a detail card, so each remembers its own).
 *  - [mocksHeight]: the most the Advanced MOCKS pane may take above the list.
 *  - [detailCollapsed]: timeline only (⌘1) — a selection doesn't open the right pane.
 *  - [timelineCollapsed]: the right pane takes the whole width (⌘2) — only while it has something to show.
 *  - [mocksCollapsed]: the MOCKS pane shrinks to its one-line header (⌘3).
 */
data class PaneLayout(
    val detailWidth: Int = DEFAULT_DETAIL,
    val waterfallWidth: Int = DEFAULT_WATERFALL,
    val mocksHeight: Int = DEFAULT_MOCKS,
    val detailCollapsed: Boolean = false,
    val timelineCollapsed: Boolean = false,
    val mocksCollapsed: Boolean = false,
) {
    /** The actual layout for one frame. Widths are dp; [overlay] floats the inspector over the list. */
    data class Resolved(
        val showList: Boolean,
        val showInspector: Boolean,
        val listWidth: Int,
        val inspectorWidth: Int,
        val overlay: Boolean,
    )

    /** Sizes held to their minimums and maximums (what a hand-edited or old settings file gets). */
    fun clamped(): PaneLayout = copy(
        detailWidth = detailWidth.coerceIn(MIN_DETAIL, MAX_INSPECTOR),
        waterfallWidth = waterfallWidth.coerceIn(MIN_WATERFALL, MAX_INSPECTOR),
        mocksHeight = mocksHeight.coerceIn(MIN_MOCKS, MAX_MOCKS),
    )

    fun inspectorWidth(i: Inspector): Int = if (i == Inspector.WATERFALL) waterfallWidth else detailWidth

    /** A splitter drag: the inspector's new width, never squeezing the list below [MIN_LIST] in [available]. */
    fun withInspectorWidth(i: Inspector, width: Int, available: Int): PaneLayout {
        val min = if (i == Inspector.WATERFALL) MIN_WATERFALL else MIN_DETAIL
        val max = (available - MIN_LIST - SPLITTER).coerceAtLeast(min).coerceAtMost(MAX_INSPECTOR)
        val w = width.coerceIn(min, max)
        return if (i == Inspector.WATERFALL) copy(waterfallWidth = w) else copy(detailWidth = w)
    }

    fun withMocksHeight(h: Int): PaneLayout = copy(mocksHeight = h.coerceIn(MIN_MOCKS, MAX_MOCKS))

    /** Double-click on a splitter: that splitter's default. */
    fun resetInspector(i: Inspector): PaneLayout =
        if (i == Inspector.WATERFALL) copy(waterfallWidth = DEFAULT_WATERFALL) else copy(detailWidth = DEFAULT_DETAIL)

    fun resetMocks(): PaneLayout = copy(mocksHeight = DEFAULT_MOCKS)

    /** ⌘1 — timeline only, or back. Leaving ⌘2's detail-only state on the way. */
    fun toggleTimelineOnly(): PaneLayout = copy(detailCollapsed = !detailCollapsed, timelineCollapsed = false)

    /** ⌘2 — the detail across the whole window, or back. Shows the detail if it was collapsed. */
    fun toggleDetailOnly(): PaneLayout = copy(timelineCollapsed = !timelineCollapsed, detailCollapsed = false)

    /** ⌘3 — the MOCKS pane to its header line, or back. */
    fun toggleMocks(): PaneLayout = copy(mocksCollapsed = !mocksCollapsed)

    /**
     * The layout for [available] dp of width when [inspector] has something to show.
     *  - Collapsing never hides the only thing there is: with nothing selected the list shows even
     *    in detail-only mode.
     *  - The inspector keeps its remembered width, shrinking to leave the list [MIN_LIST]; when even
     *    its minimum doesn't fit beside the list (a narrow window) it **overlays** the list from the
     *    right instead, at most the window minus a [OVERLAY_GUTTER] strip of list.
     */
    fun resolve(available: Int, inspector: Inspector): Resolved {
        val has = inspector != Inspector.NONE
        if (!has || detailCollapsed) return Resolved(true, false, available, 0, false)
        if (timelineCollapsed) return Resolved(false, true, 0, available, false)
        val min = if (inspector == Inspector.WATERFALL) MIN_WATERFALL else MIN_DETAIL
        val room = available - MIN_LIST - SPLITTER
        if (room < min) {
            val w = inspectorWidth(inspector).coerceAtMost(available - OVERLAY_GUTTER).coerceAtLeast(minOf(min, available))
            return Resolved(true, true, available, w, overlay = true)
        }
        val w = inspectorWidth(inspector).coerceIn(min, room.coerceAtMost(MAX_INSPECTOR))
        return Resolved(true, true, available - w - SPLITTER, w, overlay = false)
    }

    /** `v1;d=470;w=540;m=168;c=` + collapsed flags (`d`, `t`, `m`) — compact, tolerant, versioned. */
    fun encode(): String {
        val flags = buildString {
            if (detailCollapsed) append('d')
            if (timelineCollapsed) append('t')
            if (mocksCollapsed) append('m')
        }
        return "v1;d=$detailWidth;w=$waterfallWidth;m=$mocksHeight;c=$flags"
    }

    companion object {
        const val KEY = "logpose.desktop.layout"
        const val DEFAULT_DETAIL = 470
        const val DEFAULT_WATERFALL = 540
        const val DEFAULT_MOCKS = 168
        const val MIN_LIST = 440
        const val MIN_DETAIL = 340
        const val MIN_WATERFALL = 400
        const val MAX_INSPECTOR = 1200
        const val MIN_MOCKS = 64
        const val MAX_MOCKS = 480
        /** The splitter's own width between list and inspector. */
        const val SPLITTER = 1
        /** In overlay mode, how much of the list stays visible on the left. */
        const val OVERLAY_GUTTER = 48

        /** Reads [encode]'s form; anything missing or unreadable falls back to the default, then clamps. */
        fun decode(s: String?): PaneLayout {
            if (s.isNullOrBlank() || !s.startsWith("v1;")) return PaneLayout()
            val m = s.split(';').drop(1).mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val d = PaneLayout()
            val flags = m["c"].orEmpty()
            return PaneLayout(
                detailWidth = m["d"]?.toIntOrNull() ?: d.detailWidth,
                waterfallWidth = m["w"]?.toIntOrNull() ?: d.waterfallWidth,
                mocksHeight = m["m"]?.toIntOrNull() ?: d.mocksHeight,
                detailCollapsed = 'd' in flags,
                // Both can't be on: timeline-only wins.
                timelineCollapsed = 't' in flags && 'd' !in flags,
                mocksCollapsed = 'm' in flags,
            ).clamped()
        }
    }
}

/** The window chrome's transient state: which popover is open. Never persisted. */
enum class Popover { NONE, SWITCHER, DEVELOPER, MCP }
