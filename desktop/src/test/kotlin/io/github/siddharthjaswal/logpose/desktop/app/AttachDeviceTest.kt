package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.desktop.Fixtures
import io.github.siddharthjaswal.logpose.desktop.advanced.MockWriter
import io.github.siddharthjaswal.logpose.desktop.session.CaptureSession
import io.github.siddharthjaswal.logpose.desktop.session.LineSource
import io.github.siddharthjaswal.logpose.desktop.session.SessionState
import io.github.siddharthjaswal.logpose.logcat.Adb
import io.github.siddharthjaswal.logpose.mcp.McpTools
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * `attach_device` through the real [AppModel]: the window's own select-and-Start path, with a fake
 * line source per device and a fixed `adb devices` answer, so no adb runs. No device says hello
 * and no package is known, so the mock controller has nowhere to broadcast either.
 */
class AttachDeviceTest {

    @TempDir lateinit var tmp: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach fun stop() = scope.cancel()

    private class FakeSource(val serial: String) : LineSource {
        var onLine: (String) -> Unit = {}
        var stopped = false
        override fun start(onLine: (String) -> Unit, onError: (String) -> Unit, onStopped: () -> Unit) { this.onLine = onLine }
        override fun stop() { stopped = true }
    }

    private val sources = mutableListOf<FakeSource>()
    private val settings = KeyValueStore.InMemory()
    private val model by lazy {
        AppModel(
            scope,
            sessionsDir = File(tmp, "sessions"),
            sessionOverride = CaptureSession(sourceFor = { FakeSource(it).also(sources::add) }, requestHellos = false),
            settings = settings,
            appSupport = tmp,
            adbDevices = { listOf(Adb.DeviceInfo("emulator-5554", "device"), Adb.DeviceInfo("emulator-5556", "device")) },
        )
    }

    private fun attach(serial: String? = null): JsonObject {
        val answer = CompletableFuture<JsonObject>()
        McpTools.callAsync(
            "attach_device",
            buildJsonObject { serial?.let { put("serial", it) } },
            emptyList(),
            devices = model.mcp.currentSession().devices,
        ) { answer.complete(it.jsonObject) }
        return answer.get(5, TimeUnit.SECONDS)
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < until) { if (check()) return; Thread.sleep(20) }
        error("never: $what")
    }

    @Test fun `attach starts a session on the device and the window follows`() {
        val out = attach("emulator-5556")
        assertTrue(out["attached"]!!.jsonPrimitive.content.toBoolean(), "was: $out")
        assertEquals(SessionState.Running::class, model.sessionState.value::class)
        assertEquals("emulator-5556", (model.sessionState.value as SessionState.Running).serial)
        assertEquals("emulator-5556", model.selectedSerial.value, "the device menu shows the switch")
        assertEquals(listOf("emulator-5556"), sources.map { it.serial })
        assertEquals("no", out["mocks"]!!.jsonObject["re_pushed"]!!.jsonPrimitive.content, "read-only by default")
    }

    @Test fun `with no serial it restarts the current device, and says the timeline was cleared`() {
        attach("emulator-5556")
        Fixtures.lines().forEach(sources.last().onLine)
        assertTrue(model.session.store.snapshot().isNotEmpty())

        // Two devices are connected; the current one wins rather than a refusal.
        val out = attach()
        assertTrue(out["restarted"]!!.jsonPrimitive.content.toBoolean(), "was: $out")
        assertEquals(listOf("emulator-5556", "emulator-5556"), sources.map { it.serial })
        assertTrue(sources.first().stopped, "the old stream is stopped, not left tailing beside the new one")
        assertTrue(out["timeline_cleared"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(model.session.store.snapshot().isEmpty())
    }

    @Test fun `the mock writer re-arms across a restart`() {
        model.setAdvanced(true)
        model.setMockWriter("emulator-5554", true)
        eventually("persisted") { settings.get(MockWriter.WRITERS_KEY) == "emulator-5554" }

        val first = attach("emulator-5554")
        assertEquals("on_next_hello", first["mocks"]!!.jsonObject["re_pushed"]!!.jsonPrimitive.content, "was: $first")
        eventually("armed") { model.advanced.mocks.deviceState().capturing }

        // Stop and Start back to back: the old session's release must not land after the new arm.
        attach()
        eventually("re-armed") { model.advanced.mocks.deviceState().capturing }
        Thread.sleep(150)
        assertTrue(model.advanced.mocks.deviceState().capturing, "still armed once both IO hops have run")
    }
}
