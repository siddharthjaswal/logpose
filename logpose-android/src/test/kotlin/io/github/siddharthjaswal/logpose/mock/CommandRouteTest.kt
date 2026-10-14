package io.github.siddharthjaswal.logpose.mock

import io.github.siddharthjaswal.logpose.mock.CommandRoute.Route
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The receiver's routing, apart from the receiver (which needs a real `Context`). The hello request
 * is the case that has to be exactly right on both sides of a version gap: a host that sends it to
 * an old library, and a new library that receives a rules/push command from an old host.
 */
class CommandRouteTest {

    @Test fun `a payload-less hello request routes to HELLO`() {
        assertEquals(Route.HELLO, CommandRoute.of("hello", hasPayload = false, revision = -1, seq = 0, total = 1))
    }

    @Test fun `hello wins even if a payload rides along`() {
        assertEquals(Route.HELLO, CommandRoute.of("hello", hasPayload = true, revision = 3, seq = 0, total = 1))
    }

    @Test fun `a rule set without a cmd extra still routes as rules, as before 1_6`() {
        assertEquals(Route.RULES, CommandRoute.of(null, hasPayload = true, revision = 4, seq = 0, total = 2))
        assertEquals(Route.RULES, CommandRoute.of("", hasPayload = true, revision = 4, seq = 1, total = 2))
    }

    @Test fun `a rule set needs a revision, a push does not`() {
        assertEquals(Route.IGNORE, CommandRoute.of("rules", hasPayload = true, revision = -1, seq = 0, total = 1))
        assertEquals(Route.PUSH, CommandRoute.of("push", hasPayload = true, revision = -1, seq = 0, total = 1))
    }

    @Test fun `a payload-less rules or push broadcast is ignored`() {
        // What an older library does with the hello request is this same rule: no payload, no-op.
        assertEquals(Route.IGNORE, CommandRoute.of("rules", hasPayload = false, revision = 1, seq = 0, total = 1))
        assertEquals(Route.IGNORE, CommandRoute.of("push", hasPayload = false, revision = -1, seq = 0, total = 1))
    }

    @Test fun `an unknown command is ignored rather than decoded as a rule set`() {
        assertEquals(Route.IGNORE, CommandRoute.of("future_cmd", hasPayload = true, revision = 1, seq = 0, total = 1))
    }

    @Test fun `a malformed slice is ignored`() {
        assertEquals(Route.IGNORE, CommandRoute.of("rules", hasPayload = true, revision = 1, seq = 2, total = 2))
        assertEquals(Route.IGNORE, CommandRoute.of("push", hasPayload = true, revision = -1, seq = 0, total = 0))
    }
}
