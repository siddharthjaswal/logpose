package io.github.siddharthjaswal.logpose.desktop.advanced

/**
 * Who may write a device's mock rules — the **single-writer** rule from the daemon PRD §7, applied
 * to the Mac app.
 *
 * The device holds one wholesale rule set, versioned by the writer's own revision counter. Two
 * writers (this app and an IDE with the LogPose tool window open, or a `logpose serve --mocks`)
 * overwrite each other on every edit, every app restart re-triggers the fight through the Hello
 * handshake, and a writer that stops capture broadcasts an *empty* set — deleting the other one's
 * live mocks. So the app is **read-only on mocks by default** and becomes the writer only for a
 * device the user explicitly hands it ("This app controls mocks"), remembered per serial.
 *
 * Read-only is not blind: the app still ingests the device's hello and acks, so the strip can say
 * what the device holds and how often each rule was hit, and `list_mocks` still answers. It just
 * never broadcasts — the controller is never armed, so not even a sync retry can push.
 */
object MockWriter {

    /** The *Advanced* toggle (PRD §5.5): off by default, so the default app stays a recorder. */
    const val ADVANCED_KEY = "logpose.desktop.advanced"

    /** Serials this app is the mock writer for, comma-separated. */
    const val WRITERS_KEY = "logpose.desktop.mockWriters"

    enum class Mode {
        /** Advanced is off: no mocks UI at all, and nothing is ever written. */
        HIDDEN,

        /** Advanced is on, but another process may own this device's rules: reads only. */
        READ_ONLY,

        /** The user handed this device's rule set to this app. */
        WRITER,
    }

    fun mode(advanced: Boolean, writers: Set<String>, serial: String?): Mode = when {
        !advanced -> Mode.HIDDEN
        serial != null && serial in writers -> Mode.WRITER
        else -> Mode.READ_ONLY
    }

    /** Only the writer arms the controller during capture (pushes on hello, retries, edits). */
    fun armsDevice(mode: Mode): Boolean = mode == Mode.WRITER

    /**
     * Whether ending a capture must broadcast the empty set — the plugin's and the daemon's
     * fail-safe that keeps a forgotten mock from outliving the session. Only when this app was the
     * writer: from a read-only app that broadcast would delete the real writer's live rules.
     */
    fun clearsOnStop(mode: Mode): Boolean = mode == Mode.WRITER

    fun parse(raw: String?): Set<String> =
        raw.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()

    fun serialize(writers: Set<String>): String? = writers.sorted().joinToString(",").ifEmpty { null }

    /** The explanation the read-only strip and the toggle show — the conflict, said plainly. */
    const val CONFLICT =
        "Read-only: LogPose for Mac shows this device's mocks but doesn't change them. A phone holds " +
            "one set of mock rules, and only one program may write it — if Android Studio's LogPose " +
            "tool window (or logpose serve --mocks) is also capturing this phone, the two would " +
            "overwrite each other, and stopping one would delete the other's mocks."

    const val TAKE_OVER =
        "Turn on only when nothing else is writing mocks to this phone. While on, stopping the " +
            "session clears the app's rules from the phone; turning it off clears them too."
}

/**
 * What an MCP session built over the Mac app exposes, following the mock-writer state — the
 * daemon's per-tool read-only shape (`DaemonSession`), decided by [MockWriter.Mode] instead of a
 * `--mocks` flag:
 *
 *  - `list_mocks`, `list_scenarios`, `save_scenario`, correlation and every capture read: always —
 *    none of them touches the device's rule set.
 *  - `create_mock` / `set_mock_enabled` / `delete_mock` / `load_scenario`: only as the writer.
 *  - `inject_fcm`: only as the writer — a push is a write to the device too, and the surface is
 *    absent (not merely refusing) otherwise, exactly as a read-only daemon's.
 */
data class McpSurfaces(val mocksWritable: Boolean, val scenariosLoadable: Boolean, val push: Boolean) {
    companion object {
        fun of(mode: MockWriter.Mode): McpSurfaces {
            val writer = mode == MockWriter.Mode.WRITER
            return McpSurfaces(mocksWritable = writer, scenariosLoadable = writer, push = writer)
        }
    }
}
