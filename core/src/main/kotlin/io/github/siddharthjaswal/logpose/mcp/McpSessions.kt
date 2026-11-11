package io.github.siddharthjaswal.logpose.mcp

import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.store.EventStore
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Connects the MCP endpoint (application-wide, one built-in web server per IDE) to the
 * per-project capture it should answer for.
 *
 * The token does double duty: it authenticates the caller *and* identifies which open project's
 * capture to read. That's why it's per-project and persisted — the `claude mcp add` command a
 * developer saves in their client keeps working across IDE restarts, and pointing two projects
 * at one agent can't cross the streams.
 *
 * Authentication is not optional here. A capture holds auth tokens and user data, and the
 * built-in server is reachable by any process on the machine.
 */
object McpSessions {

    /**
     * One open project's capture, as the MCP layer sees it. Behavior is passed as lambdas
     * rather than a project handle so a stale entry can't keep a disposed project alive — which
     * is also why a headless host can register a session without inventing a project at all.
     */
    class Session(
        val projectName: String,
        val store: EventStore,
        /** Host-clock age of an event, since device timestamps can't be diffed against ours. */
        val hostAgeMillis: (String) -> Long,
        val exposeBodies: () -> Boolean,
        /** Whether logcat is being tailed right now — lets a query distinguish "no matching
         *  events" from "capture isn't running", which otherwise both read as empty. */
        val captureRunning: () -> Boolean = { true },
        /** Reset the capture (clear the event buffer) — for an agent orchestrating "start clean,
         *  run, read only my events". */
        val clearCapture: () -> Unit = {},
        /** Write surface for mock rules; null when this project can't serve mocks. */
        val mocks: McpTools.Mocks? = null,
        /** Push injection; null when this project can't reach a device. */
        val push: McpTools.Push? = null,
        /** Committable scenario files; null for a project with no directory on disk. */
        val scenarios: McpTools.Scenarios? = null,
        /** The project's correlation keys and their cache; null without a tool window. */
        val correlations: McpTools.Correlations? = null,
        /**
         * "Tell me when the app does X", backed by [EventStore.addWaiter]. Passed as a lambda like
         * everything else here so a stale session can't keep a store alive by identity alone.
         */
        val waits: McpTools.Waits? = null,
        /**
         * The observed worker state sequence, read from the host's `WorkerLifecycle` side index —
         * how `worker_history` recovers the enqueued → running → terminal transitions the store
         * collapses into one mutating row. Defaults to empty, so a host that keeps no lifecycle
         * simply reports the single surviving state.
         */
        val workerTransitions: McpTools.WorkerTransitions = McpTools.WorkerTransitions { emptyList() },
        /** Device listing and capture (re)attach for `attach_device`; null where the host can't. */
        val devices: McpTools.Devices? = null,
        /**
         * `restart_app` / `clear_app_data`; null where the host has no device to act on. Present but
         * not [McpTools.Apps.writable] on a host that must not change the device.
         */
        val apps: McpTools.Apps? = null,
    )

    private const val TOKEN_KEY = "logpose.mcp.token"
    private const val BODIES_KEY = "logpose.mcp.exposeBodies"

    private val byToken = ConcurrentHashMap<String, Session>()

    /** Stable per-project token, generated once and persisted in [store]. */
    fun tokenFor(store: KeyValueStore): String {
        store.get(TOKEN_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
        return rotateToken(store)
    }

    /**
     * Replaces the persisted token with a fresh one and returns it. The caller re-registers its
     * session under the new token (see [replace]) — the old one stops working the moment it does.
     */
    fun rotateToken(store: KeyValueStore): String {
        val token = newToken()
        store.set(TOKEN_KEY, token)
        return token
    }

    /** 128 random bits as 32 hex chars — `UUID.randomUUID` draws from `SecureRandom`. */
    fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

    /**
     * Whether response bodies may leave the IDE over MCP. Defaults to on — the bodies are the
     * point — but a capture can contain secrets, so it stays switchable per project.
     */
    fun exposeBodies(store: KeyValueStore): Boolean = store.getBoolean(BODIES_KEY, true)

    fun setExposeBodies(store: KeyValueStore, expose: Boolean) {
        store.setBoolean(BODIES_KEY, expose, true)
    }

    fun register(token: String, session: Session) {
        byToken[token] = session
    }

    fun unregister(token: String) {
        byToken.remove(token)
    }

    /** Moves [session] from [old] to [new] in one step: [old] is refused from the next request on. */
    fun replace(old: String, new: String, session: Session) {
        byToken[new] = session
        byToken.remove(old)
    }

    /**
     * The session [token] selects. Compared in constant time against every registered token (there
     * are as many as open projects) rather than by hash lookup, so response timing says nothing
     * about how close a guess was.
     */
    fun byToken(token: String): Session? {
        var found: Session? = null
        for ((candidate, session) in byToken) {
            if (McpAuth.constantTimeEquals(candidate, token)) found = session
        }
        return found
    }

    /** True once any project has registered — used to explain a 401 to a misconfigured client. */
    fun hasSessions(): Boolean = byToken.isNotEmpty()
}
