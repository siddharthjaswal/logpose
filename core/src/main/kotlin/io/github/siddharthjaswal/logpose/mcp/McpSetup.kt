package io.github.siddharthjaswal.logpose.mcp

/**
 * The setup snippets every host shows — the IDE's Connect Coding Agent popup, the Mac app's MCP
 * popover and the daemon's startup banner — built in one place so the three can't drift.
 *
 * The Codex forms are taken from Codex's own source (openai/codex, `codex-rs/cli/src/mcp_cmd.rs`
 * and `codex-rs/config/src/mcp_types.rs`, October 2026): `codex mcp add <NAME> --url <URL>
 * --bearer-token-env-var <ENV_VAR>` writes a `[mcp_servers.<NAME>]` table with `url` and
 * `bearer_token_env_var`, and Codex then sends `Authorization: Bearer $ENV_VAR` on every request.
 * No `experimental_use_rmcp_client` flag is needed any more — the streamable HTTP client is the
 * default. The env-var form is preferred so the token never sits in a config file. Codex also
 * reads a project-local `.codex/config.toml` (`codex-rs/config/src/loader/mod.rs`,
 * `discover_project_layers`) once the project is trusted, and `mcp_servers` is not on its
 * project-config denylist — so the same table can be committed to the app's repo.
 *
 * URLs use `127.0.0.1`, not `localhost`: the daemon and the Mac app bind IPv4 loopback only, and a
 * client that resolves `localhost` to `::1` first would otherwise be refused before falling back.
 */
object McpSetup {

    const val SERVER_NAME = "logpose"

    /** The environment variable the Codex snippets read the token from. Also read by the daemon. */
    const val TOKEN_ENV = "LOGPOSE_TOKEN"

    enum class Client(val label: String) {
        CLAUDE_CODE("Claude Code"),
        CODEX("Codex"),
        OTHER("Other (JSON)"),
    }

    /** One copyable block: a short [title], the [text] to copy verbatim, and an optional [note]. */
    data class Snippet(val title: String, val text: String, val note: String? = null)

    fun url(port: Int, path: String = McpHttpTransport.PATH): String = "http://127.0.0.1:$port$path"

    fun claudeCommand(url: String, token: String): String =
        "claude mcp add --transport http $SERVER_NAME $url --header \"Authorization: Bearer $token\""

    fun codexExport(token: String): String = "export $TOKEN_ENV=$token"

    fun codexCommand(url: String): String =
        "codex mcp add $SERVER_NAME --url $url --bearer-token-env-var $TOKEN_ENV"

    fun codexToml(url: String): String =
        "[mcp_servers.$SERVER_NAME]\nurl = \"$url\"\nbearer_token_env_var = \"$TOKEN_ENV\""

    /** The `mcpServers` shape most JSON-configured clients (Cursor, Windsurf, Cline, …) read. */
    fun genericJson(url: String, token: String): String = """
        {
          "mcpServers": {
            "$SERVER_NAME": {
              "type": "http",
              "url": "$url",
              "headers": { "Authorization": "Bearer $token" }
            }
          }
        }
    """.trimIndent()

    fun snippets(client: Client, url: String, token: String): List<Snippet> = when (client) {
        Client.CLAUDE_CODE -> listOf(
            Snippet("Run in your terminal", claudeCommand(url, token), "Then `claude mcp list` should show logpose ✓ connected."),
        )
        Client.CODEX -> listOf(
            Snippet(
                "1 · Put the token in your environment",
                codexExport(token),
                "Add it to ~/.zshrc (or your shell's profile) so every Codex session sees it.",
            ),
            Snippet("2 · Register the server", codexCommand(url), "Check with `codex mcp list` and `codex mcp get logpose`."),
            Snippet(
                "…or in ~/.codex/config.toml (or the repo's .codex/config.toml)",
                codexToml(url),
                "A repo's .codex/config.toml is read once Codex trusts the project — safe to commit: no token in it.",
            ),
        )
        Client.OTHER -> listOf(
            Snippet(
                "Streamable HTTP · Bearer token",
                genericJson(url, token),
                "Any MCP client: POST $url with Authorization: Bearer <token>.",
            ),
        )
    }

    /**
     * The daemon's startup banner: every client's lines, plain text. Under `--stdio` the daemon
     * prints its own recipe instead (no URL, no token).
     */
    fun banner(url: String, token: String): List<String> = buildList {
        add("Connect a coding agent:")
        add("")
        add("  Claude Code:")
        add("    ${claudeCommand(url, token)}")
        add("")
        add("  Codex:")
        add("    ${codexExport(token)}")
        add("    ${codexCommand(url)}")
        add("    # or in ~/.codex/config.toml (or the repo's .codex/config.toml, once Codex trusts it):")
        codexToml(url).lines().forEach { add("    $it") }
        add("")
        add("  Any MCP client: POST $url with \"Authorization: Bearer $token\"")
        add("  Health: ${url.removeSuffix(McpHttpTransport.PATH)}${McpHttpTransport.HEALTH_PATH}")
    }
}
