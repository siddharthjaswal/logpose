pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Repositories for modules that declare none of their own. `:core` is a plain-JVM module and
// resolves everything from Maven Central; the root project keeps its own `repositories` block
// because it additionally needs the IntelliJ Platform repositories, and Gradle's default
// PREFER_PROJECT mode lets a project-level declaration win where one exists.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "logpose"

// The IDE-free half of LogPose: wire model, parser, store, analysis, MCP tools, mock sync and
// the presentation models. Merged into the plugin's composed jar via `intellijPlatformPluginModule`
// (see the root build), and consumable by a headless daemon without the IntelliJ Platform.
include(":core")
