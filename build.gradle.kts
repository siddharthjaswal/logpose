plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "io.github.siddharthjaswal"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // LogPose targets the IntelliJ Platform, so it runs in both IntelliJ IDEA
        // and Android Studio (which is built on the same platform). It deliberately
        // does NOT depend on the bundled Android plugin — it talks to `adb` directly,
        // which keeps it usable in any JetBrains IDE.
        intellijIdeaCommunity("2024.3")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "233"
            // Leave untilBuild unset so the plugin keeps working with future IDE builds.
        }
    }
}

kotlin {
    jvmToolchain(17)
}
