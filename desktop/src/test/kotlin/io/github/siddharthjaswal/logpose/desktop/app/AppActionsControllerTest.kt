package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.appcontrol.AppAction
import io.github.siddharthjaswal.logpose.appcontrol.AppControl
import io.github.siddharthjaswal.logpose.appcontrol.AppControlService
import io.github.siddharthjaswal.logpose.appcontrol.AppMarker
import io.github.siddharthjaswal.logpose.appcontrol.AppShell
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import io.github.siddharthjaswal.logpose.store.EventStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Mac App menu's state machine over the real service, with a scripted device. */
class AppActionsControllerTest {

    private val pkg = "in.shadowfax.gandalf.debug"
    private val store = EventStore(completer = { it.run() })
    private val ran = mutableListOf<String>()
    private var debuggable = true

    private val shell = AppShell { _, line, _ ->
        ran += line
        when {
            line.startsWith("pm path") -> AppShell.Result(0, "package:/x")
            line.startsWith("run-as") && !debuggable -> AppShell.Result(1, "run-as: package not debuggable: $pkg")
            line.startsWith("cmd package resolve-activity") -> AppShell.Result(0, "$pkg/.Main")
            line.startsWith("am start") -> { store.noteHello("p${ran.size}", pkg, "1.7.4"); AppShell.Result(0, "Starting") }
            line.startsWith("pm clear") -> AppShell.Result(0, "Success")
            else -> AppShell.Result(0, "")
        }
    }
    private val settings = KeyValueStore.InMemory()
    private val service = AppControlService(store, { "emulator-5580" }, settings = settings, control = AppControl(shell), runAsync = { it.run() })
    private val controller = AppActionsController(CoroutineScope(Dispatchers.Unconfined), service)

    @Test
    fun `the announced app is the target`() {
        store.noteHello("p0", pkg, "1.7.4")
        controller.refresh()
        assertEquals(pkg, controller.ui.value.pkg)
    }

    @Test
    fun `restart runs at once and reports the new run`() {
        store.noteHello("p0", pkg, "1.7.4")
        controller.refresh()
        controller.request(AppAction.RESTART)
        val ui = controller.ui.value
        assertFalse(ui.busy)
        assertEquals("Restarted $pkg. New run #2.", ui.result!!.text)
        assertFalse(ui.result!!.error)
        assertTrue(store.snapshot().any { AppMarker.isMarker(it) })
    }

    @Test
    fun `clear all data asks first, and only the confirm runs it`() {
        store.noteHello("p0", pkg, "1.7.4")
        controller.refresh()
        controller.request(AppAction.CLEAR_DATA)
        assertEquals(pkg, controller.ui.value.confirm)
        assertTrue(ran.isEmpty(), "nothing runs before the confirm")
        controller.cancelConfirm()
        assertNull(controller.ui.value.confirm)
        assertTrue(ran.isEmpty())

        controller.request(AppAction.CLEAR_DATA)
        controller.setRegrant(true)
        controller.confirm()
        assertTrue(ran.any { it == "pm clear $pkg" })
        assertTrue(ran.any { it.startsWith("dumpsys package") }, "re-grant reads permissions first")
        assertEquals("Cleared all of $pkg's data and restarted it. New run #2.", controller.ui.value.result!!.text)
    }

    @Test
    fun `a release build's cache clear offers the full clear without running it`() {
        debuggable = false
        store.noteHello("p0", pkg, "1.7.4")
        controller.refresh()
        controller.request(AppAction.CLEAR_CACHE)
        val result = controller.ui.value.result!!
        assertTrue(result.error)
        assertTrue(result.offerClearAll)
        assertTrue(result.text.contains("isn't a debuggable build"))
        assertTrue(ran.none { it.startsWith("pm clear") || it.startsWith("am force-stop") })
        // The strip's button goes through the confirm, never straight to pm clear.
        controller.request(AppAction.CLEAR_DATA)
        assertEquals(pkg, controller.ui.value.confirm)
    }

    @Test
    fun `with nothing announced the typed package is used and remembered`() {
        controller.refresh()
        controller.request(AppAction.RESTART)
        assertTrue(controller.ui.value.result!!.error, "nothing to act on yet")
        controller.editDraft("not a package")
        assertFalse(controller.useDraft())
        controller.editDraft(" com.acme.app.debug ")
        assertTrue(controller.useDraft())
        assertEquals("com.acme.app.debug", controller.ui.value.pkg)
        assertEquals("com.acme.app.debug", settings.get("logpose.app.target.emulator-5580"))
    }

    @Test
    fun `several announced apps offer a pick`() {
        store.noteHello("p0", "com.a", "1.7.4")
        store.noteHello("p1", "com.b", "1.7.4")
        controller.refresh()
        assertTrue(controller.ui.value.target.ambiguous)
        assertEquals("com.b", controller.ui.value.pkg)
        controller.pick("com.a")
        controller.refresh()
        assertEquals("com.a", controller.ui.value.pkg)
    }
}
