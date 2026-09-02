plugins {
    // No versions: the root project's `plugins` block puts both on the shared buildscript
    // classpath, so the version is declared in exactly one place.
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "io.github.siddharthjaswal"
version = rootProject.version

// Deliberately NOT `org.jetbrains.intellij.platform`: `:core` must stay a plain JVM library so
// the same classes can run inside the IDE plugin and inside a headless process.
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}
