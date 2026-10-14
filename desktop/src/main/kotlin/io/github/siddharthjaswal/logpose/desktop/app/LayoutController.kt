package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The window's layout: pane sizes and collapsed states (persisted under [PaneLayout.KEY] in the
 * app's settings file) and which popover is open (not persisted). A splitter drag updates the state
 * every frame but writes the file once, [debounceMillis] after the last change, on [io].
 */
class LayoutController(
    private val scope: CoroutineScope,
    private val settings: KeyValueStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMillis: Long = 400,
) {
    private val _layout = MutableStateFlow(PaneLayout.decode(settings.get(PaneLayout.KEY)))
    val layout: StateFlow<PaneLayout> = _layout.asStateFlow()

    private val _popover = MutableStateFlow(Popover.NONE)
    val popover: StateFlow<Popover> = _popover.asStateFlow()

    private var saveJob: Job? = null

    fun set(next: PaneLayout) {
        if (next == _layout.value) return
        _layout.value = next
        saveJob?.cancel()
        saveJob = scope.launch(io) {
            if (debounceMillis > 0) delay(debounceMillis)
            settings.set(PaneLayout.KEY, _layout.value.encode())
        }
    }

    fun update(f: (PaneLayout) -> PaneLayout) = set(f(_layout.value))

    fun reset() = set(PaneLayout())

    fun toggleTimelineOnly() = update { it.toggleTimelineOnly() }
    fun toggleDetailOnly() = update { it.toggleDetailOnly() }
    fun toggleMocks() = update { it.toggleMocks() }

    /** Opens [p], or closes it when it's already the one open (a second click on its button). */
    fun toggle(p: Popover) = _popover.update { if (it == p) Popover.NONE else p }

    fun open(p: Popover) { _popover.value = p }

    fun close() { _popover.value = Popover.NONE }
}
