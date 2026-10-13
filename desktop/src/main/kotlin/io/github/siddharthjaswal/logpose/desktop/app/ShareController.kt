package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.capture.Bookmark
import io.github.siddharthjaswal.logpose.desktop.share.ShareIo
import io.github.siddharthjaswal.logpose.desktop.share.ShareOptions
import io.github.siddharthjaswal.logpose.desktop.share.SharePlanner
import io.github.siddharthjaswal.logpose.desktop.share.SharePreview
import io.github.siddharthjaswal.logpose.desktop.share.ShareScope
import io.github.siddharthjaswal.logpose.desktop.share.ShareSource
import io.github.siddharthjaswal.logpose.desktop.share.SlackSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The share sheet as plain values (PRD §5.3) — what [io.github.siddharthjaswal.logpose.desktop.ui.ShareSheet] paints. */
data class ShareUi(
    val loading: Boolean = true,
    val error: String? = null,
    /** What is being shared from: "this session" or the opened file's name. */
    val sourceLabel: String = "",
    val totalEvents: Int = 0,
    val bookmarks: List<Bookmark> = emptyList(),
    val selectedCount: Int = 0,
    val options: ShareOptions = ShareOptions(),
    val preview: SharePreview? = null,
    val folder: File = File(System.getProperty("user.home"), "Downloads"),
    val exporting: Boolean = false,
    val exported: File? = null,
    val summary: String? = null,
    val copied: String? = null,
)

/**
 * Drives the share sheet. The source (the full disk record) is loaded once per opening on
 * [Dispatchers.IO]; each option change re-plans on [Dispatchers.Default] — scrubbing a long session
 * is real work — and only the latest plan lands. Exports write on IO.
 */
class ShareController(
    private val scope: CoroutineScope,
    private val writtenBy: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _ui = MutableStateFlow<ShareUi?>(null)
    val ui: StateFlow<ShareUi?> = _ui.asStateFlow()

    private var source: ShareSource? = null
    private var selected: Set<String> = emptySet()
    private var planJob: Job? = null

    fun open(label: String, selectedIds: Set<String>, load: () -> ShareSource?) {
        selected = selectedIds
        source = null
        _ui.value = ShareUi(loading = true, sourceLabel = label, selectedCount = selectedIds.size, folder = ShareIo.downloadsDir())
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { load() } }
            val src = loaded.getOrNull()
            if (src == null) {
                _ui.update { it?.copy(loading = false, error = loaded.exceptionOrNull()?.message ?: "There is nothing recorded to share yet.") }
                return@launch
            }
            source = src
            val options = ShareOptions(scope = ShareScope.default(src.bookmarks))
            _ui.update { it?.copy(totalEvents = src.capture.events.size, bookmarks = src.bookmarks, options = options) }
            replan(options)
        }
    }

    /** Seeds the sheet with a ready source — the render harness and tests use this. */
    fun openWith(label: String, src: ShareSource, selectedIds: Set<String> = emptySet()) {
        selected = selectedIds
        source = src
        val options = ShareOptions(scope = ShareScope.default(src.bookmarks))
        _ui.value = ShareUi(
            loading = false, sourceLabel = label, totalEvents = src.capture.events.size, bookmarks = src.bookmarks,
            selectedCount = selectedIds.size, options = options, preview = SharePlanner.plan(src, options, selectedIds),
        )
    }

    fun setOptions(change: (ShareOptions) -> ShareOptions) {
        val cur = _ui.value ?: return
        val next = change(cur.options)
        _ui.update { it?.copy(options = next, exported = null, summary = null, copied = null) }
        replan(next)
    }

    fun setFolder(dir: File) = _ui.update { it?.copy(folder = dir) }

    private fun replan(options: ShareOptions) {
        val src = source ?: return
        planJob?.cancel()
        planJob = scope.launch {
            val preview = withContext(Dispatchers.Default) { SharePlanner.plan(src, options, selected) }
            _ui.update { if (it?.options == options) it.copy(preview = preview, loading = false, options = preview.options) else it }
        }
    }

    fun export() {
        val cur = _ui.value ?: return
        val src = source ?: return
        val preview = cur.preview ?: return
        if (cur.exporting) return
        _ui.update { it?.copy(exporting = true, error = null) }
        scope.launch {
            val now = clock()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = ShareIo.export(src, preview, cur.folder, writtenBy, now)
                    val manifest = SharePlanner.manifest(src, preview, writtenBy, now)
                    file to SlackSummary.format(manifest, preview.events, file.name)
                }
            }
            result.fold(
                onSuccess = { (file, summary) -> _ui.update { it?.copy(exporting = false, exported = file, summary = summary) } },
                onFailure = { e -> _ui.update { it?.copy(exporting = false, error = "Couldn't write the capture: ${e.message ?: e}") } },
            )
        }
    }

    fun copySummary() {
        val s = _ui.value?.summary ?: return
        ShareIo.copyText(s)
        _ui.update { it?.copy(copied = "Slack summary copied — paste it into the thread.") }
    }

    fun copyFile() {
        val f = _ui.value?.exported ?: return
        ShareIo.copyFile(f)
        _ui.update { it?.copy(copied = "File copied — paste it into Slack or Finder.") }
    }

    fun reveal() {
        val f = _ui.value?.exported ?: return
        scope.launch(Dispatchers.IO) { runCatching { ShareIo.revealInFinder(f) } }
    }

    fun close() {
        planJob?.cancel()
        source = null
        _ui.value = null
    }
}
