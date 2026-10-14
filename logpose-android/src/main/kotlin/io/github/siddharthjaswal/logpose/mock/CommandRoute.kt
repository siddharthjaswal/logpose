package io.github.siddharthjaswal.logpose.mock

/**
 * Which command a [MockCommandReceiver] broadcast carries, decided from its extras alone — pure, so
 * the routing every host depends on is unit-tested without a `Context`.
 *
 * | cmd | payload | route |
 * | --- | --- | --- |
 * | `hello` (1.7.4+) | none | [Route.HELLO] — re-emit this run's `Hello` |
 * | `rules` / absent | [io.github.siddharthjaswal.logpose.wire.MockRuleSet] slice, `rev ≥ 0` | [Route.RULES] |
 * | `push` | [io.github.siddharthjaswal.logpose.wire.PushInject] slice | [Route.PUSH] |
 * | anything else, or a malformed slice | — | [Route.IGNORE] |
 *
 * `hello` is the one command with no payload, and it is checked first: the host sends it when it
 * sees events from a process whose launch hello it missed (capture attached after the app
 * started). A library older than 1.7.4 drops a payload-less broadcast before reading `cmd`, which
 * is exactly why the host sends none — the request is invisible to it rather than misread.
 */
internal object CommandRoute {

    const val CMD_RULES = "rules"
    const val CMD_PUSH = "push"
    const val CMD_HELLO = "hello"

    enum class Route { HELLO, RULES, PUSH, IGNORE }

    fun of(cmd: String?, hasPayload: Boolean, revision: Int, seq: Int, total: Int): Route {
        val command = cmd?.takeIf { it.isNotBlank() } ?: CMD_RULES
        if (command == CMD_HELLO) return Route.HELLO
        if (!hasPayload || total <= 0 || seq !in 0 until total) return Route.IGNORE
        return when (command) {
            // A rule set is ordered by revision; a push has none, so -1 is legal there.
            CMD_RULES -> if (revision < 0) Route.IGNORE else Route.RULES
            CMD_PUSH -> Route.PUSH
            // An unknown command with a payload: a newer host talking to this library. Ignoring it
            // beats decoding it as a rule set and wiping the active rules on a parse error.
            else -> Route.IGNORE
        }
    }
}
