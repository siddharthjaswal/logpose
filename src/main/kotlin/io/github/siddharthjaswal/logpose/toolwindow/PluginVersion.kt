package io.github.siddharthjaswal.logpose.toolwindow

/**
 * The plugin's own version, written into a resource by the `generatePluginVersion` Gradle task.
 * Reading it via PluginManager.findEnabledPlugin is internal API per the Marketplace verifier.
 */
internal object PluginVersion {
    val value: String? by lazy {
        PluginVersion::class.java.getResourceAsStream("/io/github/siddharthjaswal/logpose/plugin-version.txt")
            ?.use { it.readBytes().toString(Charsets.UTF_8).trim() }
            ?.takeIf { it.isNotEmpty() }
    }
}
