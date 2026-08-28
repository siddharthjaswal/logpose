package io.github.siddharthjaswal.logpose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarFile

/**
 * Guards the promise the two artifacts make together: `logpose-android` and `logpose-no-op` share
 * fully-qualified class names on purpose, so a call site compiles unchanged when a release build
 * swaps one for the other. That only holds while their public surfaces are identical — and it has
 * already drifted once in the wild (the no-op was missing `LogPoseInterceptor(config, emitter)`,
 * so anyone passing a custom sink got a red release build).
 *
 * This reflects over both and fails the build on any difference: a member added to one half and
 * not the other is caught here rather than in a consumer's release build.
 *
 * The two halves cannot share a classpath (that's the duplicate-class trap), so the no-op is
 * loaded from its built jar in a **child-first** classloader — `logpose.noop.jar` is handed over
 * by the build (see `build.gradle.kts`).
 */
class ApiParityTest {

    @Test
    fun `the no-op mirrors the real public API`() {
        val noOp = noOpLoader()
        val drift = PARITY_CLASSES.flatMap { compare(it, noOp) }
        assertTrue(
            "logpose-no-op has drifted from logpose-android:\n" + drift.joinToString("\n") { "  - $it" },
            drift.isEmpty(),
        )
    }

    @Test
    fun `the no-op stays a dependency-free, code-only jar`() {
        // Nothing on the resolved classpath beyond the Kotlin stdlib every consumer already has.
        // A dependency sneaking in — kotlinx-serialization above all — is the thing that would
        // stop the no-op being free to ship in a release build.
        val paths = noOpPaths()
        val unexpected = paths.filterNot { path -> ALLOWED_ON_CLASSPATH.any { path.name.startsWith(it) } }
        assertTrue("the no-op must pull nothing but the Kotlin stdlib: $unexpected", unexpected.isEmpty())

        JarFile(paths.first { it.name.startsWith("no-op") }).use { jar ->
            val entries = jar.entries().asSequence().map { it.name }.filterNot { it.endsWith("/") }.toList()
            val forbidden = entries.filter {
                it.startsWith("kotlinx/") ||
                    it.startsWith("io/github/siddharthjaswal/logpose/mock/") ||
                    it.endsWith("AndroidManifest.xml")
            }
            // The mock/push receivers and the manifest that registers them are debug-only by
            // construction: a release build must not carry the machinery at all, not merely
            // decline to use it.
            assertTrue("the no-op jar must not ship $forbidden", forbidden.isEmpty())
        }
    }

    // ---- comparison ---------------------------------------------------------------------------

    private fun compare(className: String, noOp: ClassLoader): List<String> {
        val real = Class.forName(className)
        val stub = try {
            Class.forName(className, false, noOp)
        } catch (_: ClassNotFoundException) {
            return listOf("$className is missing from the no-op entirely")
        }
        val realSurface = surface(real)
        val stubSurface = surface(stub)
        return (realSurface - stubSurface).map { "$className: the no-op is missing `$it`" } +
            (stubSurface - realSurface).map { "$className: the no-op declares an extra `$it`" }
    }

    /**
     * Every public member of [type], as comparable signature strings. Types are compared by
     * **name** because the two halves are loaded by different classloaders, so the same
     * `LogPoseConfig` is two distinct `Class` objects.
     *
     * Synthetic members and anything with a `$` in its name are skipped: those are Kotlin's own
     * plumbing (`foo$default` for default arguments, `bar$logpose_android_debug` for `internal`
     * members), not surface a call site can reach.
     */
    private fun surface(type: Class<*>): Set<String> {
        val members = mutableSetOf<String>()
        type.declaredConstructors.filter { it.isVisible() }
            .forEach { members += "constructor(${it.parameterTypes.names()})" }
        type.declaredMethods.filter { it.isVisible() && it.name.isReachable() }
            .forEach { members += "fun ${it.name}(${it.parameterTypes.names()}): ${it.returnType.simpleName}" }
        type.declaredFields.filter { it.isVisible() && it.name.isReachable() }
            .forEach { members += "val ${it.name}: ${it.type.simpleName}" }
        return members
    }

    private fun Member.isVisible(): Boolean = Modifier.isPublic(modifiers) && !isSynthetic
    private fun String.isReachable(): Boolean = '$' !in this
    private fun Array<Class<*>>.names(): String = joinToString(", ") { it.simpleName }

    // ---- loading the no-op --------------------------------------------------------------------

    private fun noOpPaths(): List<File> {
        val property = System.getProperty(NO_OP_JAR)
        assertTrue(
            "system property `$NO_OP_JAR` is unset — the build must hand the tests the built " +
                "no-op jar (see build.gradle.kts)",
            !property.isNullOrBlank(),
        )
        return property!!.split(File.pathSeparator).map(::File).onEach {
            assertTrue("no-op artifact not found at $it", it.isFile)
        }
    }

    private fun noOpLoader(): ClassLoader =
        NoOpClassLoader(noOpPaths().map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader!!)

    /**
     * Child-first for LogPose's own package, parent-first for everything else. Parent-first
     * everywhere would silently hand back the *real* classes (they're on the test classpath under
     * the very same names) and the comparison would pass by tautology; child-first everywhere
     * would load a second copy of okhttp and kotlin-stdlib, and the no-op's `Interceptor` would
     * no longer be the one this test can see.
     */
    private class NoOpClassLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith(PACKAGE)) {
                synchronized(this) {
                    findLoadedClass(name)?.let { return it }
                    try {
                        return findClass(name).also { if (resolve) resolveClass(it) }
                    } catch (_: ClassNotFoundException) {
                        // Not in the no-op (a real-only type named in a signature) — let the
                        // parent answer, so reflection over that signature still resolves.
                    }
                }
            }
            return super.loadClass(name, resolve)
        }
    }

    private companion object {
        const val NO_OP_JAR = "logpose.noop.jar"
        const val PACKAGE = "io.github.siddharthjaswal.logpose"

        /** The no-op jar plus the Kotlin stdlib (and its annotations) — nothing else may appear. */
        val ALLOWED_ON_CLASSPATH = listOf("no-op", "kotlin-stdlib", "annotations-")

        /**
         * The app-facing surface: everything a host app writes against, plus the JVM facade
         * classes Kotlin generates for the top-level trace functions (named after their files,
         * which is why the file layout has to match too).
         *
         * `wire.Envelope` is deliberately absent. Its `payload` is a kotlinx-serialization
         * `JsonElement`, and the no-op is a pure-JVM jar with no dependencies at all — so that
         * one type cannot be mirrored exactly, and its stub says so in KDoc. Everything that
         * *names* it — `emit.EventEmitter`, and through it the two-argument interceptor
         * constructor — is checked here.
         */
        val PARITY_CLASSES = listOf(
            "$PACKAGE.LogPose",
            "$PACKAGE.LogPoseConfig",
            "$PACKAGE.LogPoseConfig\$Companion",
            "$PACKAGE.LogPoseInterceptor",
            "$PACKAGE.EventBuilder",
            "$PACKAGE.Tone",
            "$PACKAGE.BodyDecoder",
            "$PACKAGE.FcmMessageInfo",
            "$PACKAGE.DbQueryInfo",
            "$PACKAGE.WorkerEventInfo",
            "$PACKAGE.AnalyticsEventInfo",
            "$PACKAGE.LogPoseTrace",
            "$PACKAGE.LogPoseTraceKt",
            "$PACKAGE.LogPoseTraceContextKt",
            "$PACKAGE.emit.EventEmitter",
        )
    }
}
