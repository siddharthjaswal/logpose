plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "io.github.siddharthjaswal"
version = "0.9.7"

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
        // Bundled JSON support — powers the Raw view's editor (highlighting + folding).
        bundledPlugin("com.intellij.modules.json")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "233"
            // Remove the upper bound. The Gradle plugin otherwise auto-caps until-build
            // to the platform we compile against (243.*), which would refuse to load on
            // newer IDEs like Android Studio 2026.1 (build 261).
            untilBuild = provider { null }
        }
    }

    // Plugin signing — required for JetBrains Marketplace. Secrets come from the
    // environment (CI secrets / local env), never the repo. See RELEASING.md for how to
    // generate the key/cert and run `./gradlew signPlugin`. Absent vars are fine for a
    // normal build; only signPlugin/publishPlugin need them.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    // `verifyPlugin` runs the JetBrains Plugin Verifier against specific, released IDEs.
    // (recommended() can resolve to an unreleased build that 404s on download.)
    pluginVerification {
        ides {
            ide("2024.1")
            ide("2024.3")
        }
    }
}

kotlin {
    jvmToolchain(17)
}
