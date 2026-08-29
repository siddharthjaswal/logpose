package io.github.siddharthjaswal.logpose.mcp

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
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
     * rather than a [Project] handle so a stale entry can't keep a disposed project alive.
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
    )

    private const val TOKEN_KEY = "logpose.mcp.token"
    private const val BODIES_KEY = "logpose.mcp.exposeBodies"

    private val byToken = ConcurrentHashMap<String, Session>()

    /** Stable per-project token, generated once and persisted with the project's settings. */
    fun tokenFor(project: Project): String {
        val props = PropertiesComponent.getInstance(project)
        props.getValue(TOKEN_KEY)?.takeIf { it.isNotBlank() }?.let { return it }
        val token = UUID.randomUUID().toString().replace("-", "")
        props.setValue(TOKEN_KEY, token)
        return token
    }

    /**
     * Whether response bodies may leave the IDE over MCP. Defaults to on — the bodies are the
     * point — but a capture can contain secrets, so it stays switchable per project.
     */
    fun exposeBodies(project: Project): Boolean =
        PropertiesComponent.getInstance(project).getBoolean(BODIES_KEY, true)

    fun setExposeBodies(project: Project, expose: Boolean) {
        PropertiesComponent.getInstance(project).setValue(BODIES_KEY, expose, true)
    }

    fun register(token: String, session: Session) {
        byToken[token] = session
    }

    fun unregister(token: String) {
        byToken.remove(token)
    }

    fun byToken(token: String): Session? = byToken[token]

    /** True once any project has registered — used to explain a 401 to a misconfigured client. */
    fun hasSessions(): Boolean = byToken.isNotEmpty()
}
