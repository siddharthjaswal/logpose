plugins {
    // No version: the root project's `plugins` block puts Kotlin on the shared buildscript
    // classpath, so the version is declared in exactly one place.
    kotlin("jvm")
    application
}

group = "io.github.siddharthjaswal"
version = rootProject.version

// Deliberately NOT `org.jetbrains.intellij.platform`: the point of the daemon is that no part of
// the IntelliJ Platform is on its classpath. If this module ever needs an IDE type, something
// belongs in :core instead.
dependencies {
    implementation(project(":core"))
    // Transitive from :core in practice, but the daemon parses and writes JSON-RPC itself, so it
    // declares what it uses.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.github.siddharthjaswal.logpose.daemon.MainKt")
    applicationName = "logpose"
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

/**
 * The distributable: one `java -jar`-able archive.
 *
 * A plain `Jar` rather than the Shadow plugin, because the whole runtime classpath is :core +
 * kotlinx-serialization + the Kotlin stdlib — no service-loader files to merge, no relocation to
 * do, no signed jars whose signatures would need stripping (the excludes below are belt and
 * braces). Adding a plugin to save six lines would be a worse trade.
 */
val fatJar by tasks.registering(Jar::class) {
    group = "distribution"
    description = "Self-contained runnable jar: java -jar logpose-daemon-<version>.jar serve"
    archiveBaseName.set("logpose-daemon")
    archiveClassifier.set("")

    manifest {
        attributes(
            "Main-Class" to "io.github.siddharthjaswal.logpose.daemon.MainKt",
            // What `logpose version` reads back: the package's implementation version comes from
            // these two, so a bug report can name the exact build.
            "Implementation-Title" to "logpose-daemon",
            "Implementation-Version" to project.version.toString(),
        )
    }

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("assemble") { dependsOn(fatJar) }
