plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "io.github.siddharthjaswal"
version = "1.9.1"

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

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// LogPose contributes no Settings page, so there is nothing for searchable-options indexing to
// index. The task launches a second headless IDE, which costs a minute per build and fails
// outright when a sandbox IDE (./gradlew runIde) is already holding the sandbox.
tasks.buildSearchableOptions {
    enabled = false
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
    // untilBuild is open, so we must also verify against a RECENT IDE — otherwise APIs that
    // were deprecated/removed after 2024.x only surface on the Marketplace, not locally.
    pluginVerification {
        ides {
            ide("2024.1")
            ide("2024.3")
            ide("2025.1")
            ide("2025.2") // newest release that resolves from the IDE repo for this platform

        }
    }
}

kotlin {
    jvmToolchain(17)
}
