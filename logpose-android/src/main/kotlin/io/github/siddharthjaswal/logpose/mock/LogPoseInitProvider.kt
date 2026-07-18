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
    const val VERSION = "1.2.0"

    @Volatile var packageName: String? = null

    private val helloFromIntercept = AtomicBoolean(false)

    fun emitHello(config: LogPoseConfig) {
        val pkg = packageName ?: return
        LogcatEmitter(config).emit(
            Hello(pkg = pkg, libVersion = VERSION, mockRevision = MockRegistry.revision)
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
