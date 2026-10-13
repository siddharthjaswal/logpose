package io.github.siddharthjaswal.logpose.desktop.wireless

/** Scripted adb: [respond] maps an argv to its output (null = adb didn't answer); every call is recorded. */
class FakeAdb(private val respond: (List<String>) -> String?) : AdbCommands {
    val calls = mutableListOf<List<String>>()
    override fun run(args: List<String>, timeoutSeconds: Long): String? {
        calls += args
        return respond(args)
    }
    fun count(vararg prefix: String) = calls.count { it.take(prefix.size) == prefix.toList() }
}

/** A clock that only moves when the code under test sleeps. */
class FakeTime(var now: Long = 1_000_000) {
    val clock: () -> Long = { now }
    val sleep: suspend (Long) -> Unit = { now += it }
}

const val MDNS_HEADER = "List of discovered mdns services\n"
