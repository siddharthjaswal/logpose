package io.github.siddharthjaswal.logpose.appcontrol

import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.store.EventStore

/**
 * Which app the App actions act on: the one LogPose is capturing.
 *
 * The truth is the `Hello` handshake — each app run announces its package (the application id,
 * `.debug` suffix and all) and the store keeps one [EventStore.Session] per run. So:
 *  - an explicit package (the user typed or picked one, an agent passed one) wins;
 *  - else one announced package is the answer;
 *  - several announced packages (two apps with LogPose on one device) is a choice the host offers,
 *    with the newest run's package first — never a guess when the caller can't be asked;
 *  - none announced yet falls back to what the user typed for this device last time.
 */
object AppTarget {

    enum class Source { EXPLICIT, ANNOUNCED, REMEMBERED, NONE }

    data class Choice(
        /** The package to act on, or null when the host has to ask. */
        val pkg: String?,
        /** Every package this capture has seen announced, newest run first — the picker's options. */
        val announced: List<String>,
        val source: Source,
        /** True when several apps announced and nothing explicit picked one. [pkg] is then the newest. */
        val ambiguous: Boolean = false,
    )

    /** Distinct packages from the capture's runs, newest run first. */
    fun announced(sessions: List<EventStore.Session>): List<String> =
        sessions.sortedByDescending { it.index }.map { it.pkg }.filter { it.isNotBlank() }.distinct()

    fun choose(announced: List<String>, remembered: String?, explicit: String? = null): Choice {
        val typed = explicit?.trim()?.takeIf { it.isNotEmpty() }
        if (typed != null) return Choice(typed, announced, Source.EXPLICIT)
        return when {
            announced.size == 1 -> Choice(announced.single(), announced, Source.ANNOUNCED)
            announced.size > 1 -> Choice(announced.first(), announced, Source.ANNOUNCED, ambiguous = true)
            !remembered.isNullOrBlank() && AppCommands.isValidPackage(remembered) ->
                Choice(remembered, announced, Source.REMEMBERED)
            else -> Choice(null, announced, Source.NONE)
        }
    }

    /** Per device, because a phone and an emulator usually run different builds of the app. */
    fun rememberedKey(serial: String): String = "logpose.app.target.$serial"

    fun remembered(store: KeyValueStore?, serial: String?): String? =
        if (store == null || serial.isNullOrBlank()) null else store.get(rememberedKey(serial))

    fun remember(store: KeyValueStore?, serial: String?, pkg: String) {
        if (store == null || serial.isNullOrBlank() || !AppCommands.isValidPackage(pkg)) return
        store.set(rememberedKey(serial), pkg)
    }
}
