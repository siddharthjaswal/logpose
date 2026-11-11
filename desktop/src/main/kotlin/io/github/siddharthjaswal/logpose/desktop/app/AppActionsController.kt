package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.appcontrol.AppAction
import io.github.siddharthjaswal.logpose.appcontrol.AppCommands
import io.github.siddharthjaswal.logpose.appcontrol.AppControl
import io.github.siddharthjaswal.logpose.appcontrol.AppControlService
import io.github.siddharthjaswal.logpose.appcontrol.AppTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the App menu, its confirm sheet and its progress strip show. */
data class AppActionsUi(
    /** Who the actions hit: the announced package(s), else the remembered one. */
    val target: AppTarget.Choice = AppTarget.Choice(null, emptyList(), AppTarget.Source.NONE),
    /** The package the user picked among several, or typed when none announced. Wins over [target]. */
    val picked: String? = null,
    /** The text field's draft, when no app has announced itself. */
    val draft: String = "",
    /** The destructive confirm sheet, open for this package. */
    val confirm: String? = null,
    val regrant: Boolean = false,
    /** Non-null while an action runs: "Stopping…", "Clearing cache…", "Launching…". */
    val progress: String? = null,
    val running: AppAction? = null,
    /** The last action's outcome, until dismissed or replaced. */
    val result: AppResultUi? = null,
) {
    /** The package an action would hit right now. */
    val pkg: String? get() = picked ?: target.pkg
    val busy: Boolean get() = running != null
}

data class AppResultUi(
    val action: AppAction,
    val pkg: String,
    val text: String,
    val error: Boolean,
    /** A cache clear refused on a release build: the strip offers the full clear, which still confirms. */
    val offerClearAll: Boolean = false,
)

/**
 * The App menu's state machine (Restart / Clear cache / Clear all data), over core's
 * [AppControlService] — the same code path `restart_app` / `clear_app_data` run. All adb work happens
 * inside the service on its own thread; this only moves UI state, on [scope].
 */
class AppActionsController(
    private val scope: CoroutineScope,
    private val service: AppControlService,
) {
    private val _ui = MutableStateFlow(AppActionsUi())
    val ui: StateFlow<AppActionsUi> = _ui.asStateFlow()

    /** Re-reads the target from the capture's runs. Cheap — no adb. */
    fun refresh() {
        val target = service.target(null)
        _ui.update { ui ->
            // A pick that is no longer among the announced packages (new capture) is dropped, unless
            // it was typed because none announced.
            val picked = ui.picked?.takeIf { target.announced.isEmpty() || it in target.announced }
            if (ui.target == target && ui.picked == picked) ui else ui.copy(target = target, picked = picked)
        }
    }

    fun pick(pkg: String) {
        service.remember(pkg)
        _ui.update { it.copy(picked = pkg) }
    }

    fun editDraft(text: String) = _ui.update { it.copy(draft = text.trim()) }

    /** Commits the typed package (when valid), remembered for this device. */
    fun useDraft(): Boolean {
        val draft = _ui.value.draft
        if (!AppCommands.isValidPackage(draft)) return false
        pick(draft)
        return true
    }

    /** Runs [action] — or, for the destructive one, opens the confirm sheet first. */
    fun request(action: AppAction) {
        val ui = _ui.value
        if (ui.busy) return
        val pkg = ui.pkg ?: _ui.value.draft.takeIf { AppCommands.isValidPackage(it) }?.also { pick(it) }
        if (pkg == null) {
            _ui.update { it.copy(result = AppResultUi(action, "", "No app has announced itself on this device yet — type its package in the App menu.", error = true)) }
            return
        }
        if (action.destructive) _ui.update { it.copy(confirm = pkg, result = null) }
        else run(action, pkg, AppControl.Options())
    }

    fun setRegrant(on: Boolean) = _ui.update { it.copy(regrant = on) }

    fun cancelConfirm() = _ui.update { it.copy(confirm = null) }

    fun confirm() {
        val ui = _ui.value
        val pkg = ui.confirm ?: return
        _ui.update { it.copy(confirm = null) }
        run(AppAction.CLEAR_DATA, pkg, AppControl.Options(regrantPermissions = ui.regrant))
    }

    fun dismissResult() = _ui.update { it.copy(result = null) }

    private fun run(action: AppAction, pkg: String, options: AppControl.Options) {
        _ui.update { it.copy(running = action, progress = action.progress, result = null) }
        service.perform(
            action, pkg, options, awaitRunMillis = AWAIT_RUN_MILLIS,
            progress = { line -> scope.launch { _ui.update { if (it.running == action) it.copy(progress = line) else it } } },
        ) { outcome ->
            scope.launch {
                _ui.update { it.copy(running = null, progress = null, result = describe(outcome)) }
                refresh()
            }
        }
    }

    companion object {
        /** The UI shows the new run when it arrives; it doesn't hold the strip open longer than this. */
        const val AWAIT_RUN_MILLIS = 10_000L

        fun describe(outcome: AppControlService.Outcome): AppResultUi {
            val r = outcome.report
            return AppResultUi(r.action, r.pkg, outcome.summary, error = !outcome.ok, offerClearAll = r.offerClearAll)
        }
    }
}
