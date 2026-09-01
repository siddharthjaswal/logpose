package io.github.siddharthjaswal.logpose.mock

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import io.github.siddharthjaswal.logpose.LogPoseConfig
import io.github.siddharthjaswal.logpose.emit.LogcatEmitter
import io.github.siddharthjaswal.logpose.wire.Hello
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide runtime facts the mock machinery needs: the host app's package name (learned
 * at process start by [LogPoseInitProvider]) and the library version, plus hello emission.
 *
 * The `Hello` handshake tells the IDE (a) which package to target the mock broadcast at and
 * (b) the current mock revision — 0 right after process start, which is the IDE's signal
 * that the in-memory [MockRegistry] was wiped and rules must be re-pushed.
 */
internal object LogPoseRuntime {
    /** Kept in sync with the published library version (used by the IDE's handshake). */
    const val VERSION = "1.7.3"

    @Volatile var packageName: String? = null

    /**
     * The most specific [LogPoseConfig] seen so far — the interceptor's once the app has made a
     * call, otherwise the default the init provider has to assume.
     *
     * Receiver-side emissions (mock acks, push acks) have no config of their own: they are
     * triggered by an adb broadcast, not by app code. Emitting them through a hardcoded default
     * put them on the default logcat tag, so an app with a custom [LogPoseConfig.tag] never saw
     * its own acks. They go through this instead.
     */
    @Volatile var config: LogPoseConfig = LogPoseConfig()
        private set

    /**
     * Identifies this app run. Generated once per process, so every hello from the same launch
     * carries the same value and the IDE can tell "the interceptor re-announced itself" from
     * "the app restarted" — the boundary it draws sessions on.
     */
    private val processId: String = java.util.UUID.randomUUID().toString().take(8)

    private val helloFromIntercept = AtomicBoolean(false)

    fun emitHello(config: LogPoseConfig) {
        this.config = config
        val pkg = packageName ?: return
        LogcatEmitter(config).emit(
            Hello(
                pkg = pkg,
                libVersion = VERSION,
                mockRevision = MockRegistry.revision,
                ruleCount = MockRegistry.ruleCount,
                processId = processId,
            )
        )
    }

    /**
     * Hello on the first intercept of the process (with the interceptor's own config, so a
     * custom tag is honored). Covers "capture started after app launch", where the
     * provider-time hello predates the capture and was cleared with the log buffer.
     */
    fun emitHelloOnFirstIntercept(config: LogPoseConfig) {
        if (helloFromIntercept.compareAndSet(false, true)) emitHello(config)
    }
}

/**
 * Auto-init hook (the standard manifest-merged `ContentProvider` trick — no dependencies,
 * no code needed in the host app). Runs at process start, before `Application.onCreate`:
 * records the package name and emits the first `Hello`.
 */
internal class LogPoseInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        LogPoseRuntime.packageName = ctx.packageName
        // Default config: the provider can't know a custom tag. Apps using a custom tag still
        // get hellos from the interceptor path (which carries their config).
        runCatching { LogPoseRuntime.emitHello(LogPoseConfig()) }
        return true
    }

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
