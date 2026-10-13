package io.github.siddharthjaswal.logpose.desktop

import io.github.siddharthjaswal.logpose.analysis.CorrelationKey
import io.github.siddharthjaswal.logpose.desktop.adb.AdbLocator
import io.github.siddharthjaswal.logpose.desktop.app.AppModel
import io.github.siddharthjaswal.logpose.desktop.session.CaptureHooks
import io.github.siddharthjaswal.logpose.desktop.session.CaptureSession
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.logcat.ControlMessage
import io.github.siddharthjaswal.logpose.model.LogEvent
import io.github.siddharthjaswal.logpose.model.Transaction
import io.github.siddharthjaswal.logpose.settings.CorrelationSettings
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import kotlin.system.exitProcess

/**
 * The M4b live gate, headless, through the app's own [AppModel]:
 *
 *  1. Serve MCP on (port printed — curl it during [serveSeconds]), Advanced on, the app made the
 *     mock writer for the device, a session started.
 *  2. *Mock this endpoint…* on the location poll via the model's editor path; waits for
 *     `mocked:true` rows to land in the store.
 *  3. Stops the session (the writer's fail-safe clear), then tails the device again read-only and
 *     reports the device's ack for the empty rule set and whether any later poll was still mocked.
 *
 *   ./gradlew :desktop:m4bGate -PgateSerial=emulator-5554
 */
fun main(args: Array<String>) {
    val serial = args.getOrElse(0) { "emulator-5554" }
    val serveSeconds = args.getOrElse(1) { "45" }.toLong()
    val pathPart = "/location/"
    val adb = AdbLocator.locate() ?: fail("no adb")
    AdbLocator.install(adb)
    val device = Adb.devices().firstOrNull { it.serial == serial && it.ready }
    if (device == null) { fixtureMode(serial, serveSeconds); return }
    println("adb: $adb  device: ${device.label}")

    val tmp = File(System.getProperty("java.io.tmpdir"), "logpose-m4b-gate-${System.currentTimeMillis()}").apply { mkdirs() }
    val settings = KeyValueStore.InMemory()
    // The app announced itself before this capture started (no restart: another writer is attached),
    // so the package the rules go to is seeded as a previous run would have left it.
    settings.set("logpose.mock.pkg", "in.shadowfax.gandalf.debug")
    // The device ignores a rule set older than the one it holds, and another writer on this phone
    // has left it at a high revision; start above it (args[2]) as a long-lived writer would be.
    args.getOrNull(2)?.toIntOrNull()?.let { settings.setInt("logpose.mock.revision", it, 0) }
    CorrelationSettings.setKeys(settings, listOf(CorrelationKey("order_id")))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val model = AppModel(scope, sessionsDir = File(tmp, "sessions"), settings = settings, appSupport = tmp)
    model.start()
    model.selectDevice(serial)
    model.setAdvanced(true)
    model.setMockWriter(serial, true)
    model.setServing(true)
    Thread.sleep(1_500)
    val ui = model.mcp.ui.value
    println("MCP: serving=${ui.serving} port=${ui.port} note=${ui.portNote} readOnly=${ui.readOnly}")
    println("TOKEN=${ui.token}")
    println("CONNECT: ${ui.command}")
    model.startSession()
    println("session: ${model.sessionState.value} · mode=${model.advanced.mode()}")
    println("PHASE1 serving for ${serveSeconds}s — curl now")
    Thread.sleep(serveSeconds * 1000)
    println("store events=${model.session.store.snapshot().size}")

    // ---- phase 2: a mock on the location poll, through the editor the row menu opens ----
    val poll = model.session.store.snapshot().lastOrNull { it is LogEvent.Http && it.tx.request.path.contains(pathPart) } as LogEvent.Http?
        ?: fail("no location poll captured")
    println("mocking ${poll.tx.request.method} ${poll.tx.request.path}")
    model.advanced.openMockEditor(poll.tx)
    val draft = model.advanced.ui.value.editor!!.draft
    model.advanced.updateEditor(draft.copy(body = """{"mocked_by":"logpose-for-mac"}"""))
    model.advanced.saveEditor()
    val createdAt = System.currentTimeMillis()
    var mocked = emptyList<Transaction>()
    while (System.currentTimeMillis() - createdAt < 40_000) {
        Thread.sleep(1_000)
        mocked = model.session.store.snapshot().filterIsInstance<LogEvent.Http>().map { it.tx }
            .filter { it.mocked && it.request.path.contains(pathPart) }
        if (mocked.size >= 2) break
    }
    val dev = model.advanced.mocks.deviceState()
    println("PHASE2 mocked rows=${mocked.size} first=${mocked.firstOrNull()?.let { "${it.request.method} ${it.request.path} → ${it.response?.code} ${it.response?.body?.text}" }}")
    println("device: pkg=${dev.pkg} synced=${dev.syncedRevision} sync=${dev.sync.phase} hits=${dev.hits}")

    // ---- phase 3: stop — the writer clears the phone — then watch read-only ----
    model.stopSession()
    val stoppedAt = System.currentTimeMillis()
    println("PHASE3 session stopped at $stoppedAt; watching the device read-only for 25s")
    Thread.sleep(1_500)
    val watcher = CaptureSession()
    val acks = mutableListOf<String>()
    watcher.hooks = object : CaptureHooks {
        override fun control(msg: ControlMessage) {
            if (msg is ControlMessage.MockApplied) synchronized(acks) { acks += "rev=${msg.ack.revision} rules=${msg.ack.ruleCount}" }
        }
    }
    watcher.start(serial)
    Thread.sleep(25_000)
    watcher.stop()
    val after = watcher.store.snapshot().filterIsInstance<LogEvent.Http>().filter { it.tx.request.path.contains(pathPart) && it.tx.startedAtMillis > 0 }
    val lastAck = synchronized(acks) { acks.toList() }
    println("acks seen while watching (backlog included): $lastAck")
    println("location polls seen: ${after.size} · mocked among the newest 2: ${after.takeLast(2).map { it.tx.mocked }}")
    model.setServing(false)
    model.shutdown()
    val cleared = lastAck.lastOrNull()?.endsWith("rules=0") == true && after.takeLast(2).none { it.tx.mocked }
    println(if (mocked.isNotEmpty() && cleared) "M4B GATE PASSED" else "M4B GATE: mocked=${mocked.size} cleared=$cleared")
    exitProcess(0)
}

/**
 * No device: the MCP half of the gate only, over the app's real *Open capture file* path — the M4b
 * fixture written as a library-export NDJSON and opened read-only — so curl sees real counts.
 */
private fun fixtureMode(serial: String, serveSeconds: Long) {
    println("NO DEVICE: $serial is not attached — MCP over a capture file only; the mock half needs a device")
    val tmp = File(System.getProperty("java.io.tmpdir"), "logpose-m4b-gate-${System.currentTimeMillis()}").apply { mkdirs() }
    val file = File(tmp, "m4b-fixture.ndjson").apply { writeText((Fixtures.m4aLines() + Fixtures.flowLines()).joinToString("\n") + "\n") }
    val settings = KeyValueStore.InMemory()
    CorrelationSettings.setKeys(settings, listOf(CorrelationKey("order_id")))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val model = AppModel(scope, sessionsDir = File(tmp, "sessions"), settings = settings, appSupport = tmp)
    model.start()
    model.openCaptureFile(file)
    model.setServing(true)
    Thread.sleep(2_000)
    val ui = model.mcp.ui.value
    println("opened ${model.viewing.value?.name}: ${model.session.store.snapshot().size} events")
    println("MCP: serving=${ui.serving} port=${ui.port} note=${ui.portNote} readOnly=${ui.readOnly}")
    println("TOKEN=${ui.token}")
    println("CONNECT: ${ui.command}")
    println("PHASE1 serving for ${serveSeconds}s — curl now")
    Thread.sleep(serveSeconds * 1000)
    model.setServing(false)
    Thread.sleep(500)
    println("M4B GATE (fixture, MCP only) done")
    exitProcess(0)
}

private fun fail(msg: String): Nothing {
    System.err.println("M4B GATE FAILED: $msg")
    exitProcess(1)
}
