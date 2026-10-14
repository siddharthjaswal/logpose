package io.github.siddharthjaswal.logpose.desktop.app

import io.github.siddharthjaswal.logpose.desktop.update.UpdateCheck
import io.github.siddharthjaswal.logpose.settings.KeyValueStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

/** The update state: the status bar's *Update available* and the Developer popover's switch. */
data class UpdateUi(
    val version: String = "dev",
    val enabled: Boolean = true,
    /** A newer Mac app release, when one is known; the footer shows *Update available — Download*. */
    val available: UpdateCheck.Release? = null,
)

/**
 * Runs [UpdateCheck] at launch: never on the UI thread (the fetch hops to [io]), never blocking
 * startup (it is fire-and-forget in [scope]), at most once a day ([UpdateCheck.due]), and never for
 * a `dev` build (`./gradlew run`, the tests), which has no version to compare. A failed check is
 * silent and not recorded, so the next launch simply tries again.
 */
class UpdateController(
    private val scope: CoroutineScope,
    private val settings: KeyValueStore,
    private val currentVersion: String,
    private val fetch: (String) -> String = { UpdateCheck.fetch(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val browse: (String) -> Unit = { url -> Desktop.getDesktop().browse(URI(url)) },
) {
    private val _ui = MutableStateFlow(
        UpdateUi(currentVersion, settings.getBoolean(UpdateCheck.ENABLED_KEY, true), cachedNewer()),
    )
    val ui: StateFlow<UpdateUi> = _ui.asStateFlow()

    /** Called once from AppModel.start(). */
    fun start() {
        scope.launch { checkIfDue() }
    }

    /** One check if enabled and due; returns the release now shown (also when nothing was fetched). */
    suspend fun checkIfDue(): UpdateCheck.Release? {
        if (!_ui.value.enabled || UpdateCheck.Version.parse(currentVersion) == null) return null
        val last = settings.get(UpdateCheck.LAST_CHECK_KEY)?.toLongOrNull()
        val now = clock()
        if (!UpdateCheck.due(last, now)) return _ui.value.available
        val releases = withContext(io) { runCatching { UpdateCheck.parseReleases(fetch(currentVersion)) }.getOrNull() }
            ?: return _ui.value.available
        val newest = UpdateCheck.newest(releases, currentVersion)
        settings.set(UpdateCheck.LAST_CHECK_KEY, now.toString())
        settings.set(UpdateCheck.AVAILABLE_KEY, newest?.let(UpdateCheck::encodeAvailable))
        // The user may have switched checks off while the request was in flight.
        if (_ui.value.enabled) _ui.value = _ui.value.copy(available = newest)
        return newest
    }

    fun setEnabled(on: Boolean) {
        settings.setBoolean(UpdateCheck.ENABLED_KEY, on, true)
        _ui.value = _ui.value.copy(enabled = on, available = if (on) cachedNewer() else null)
        if (on) start()
    }

    /** Opens the release page in the browser — only ever a page of this repository's releases. */
    fun openDownload() {
        val url = _ui.value.available?.url?.takeIf { it.startsWith(UpdateCheck.RELEASE_PAGE_PREFIX) } ?: return
        scope.launch(io) { runCatching { browse(url) } }
    }

    /** The release remembered from the last check, if it is still newer than this build. */
    private fun cachedNewer(): UpdateCheck.Release? {
        if (!settings.getBoolean(UpdateCheck.ENABLED_KEY, true)) return null
        val cached = UpdateCheck.decodeAvailable(settings.get(UpdateCheck.AVAILABLE_KEY)) ?: return null
        return UpdateCheck.newest(listOf(cached), currentVersion)
    }
}
