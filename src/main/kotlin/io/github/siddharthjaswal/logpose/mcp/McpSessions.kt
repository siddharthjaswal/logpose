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
