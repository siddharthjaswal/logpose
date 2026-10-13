plugins {
    // No versions: declared once in the root `plugins` block (with `apply false` for the two
    // Compose plugins), so Kotlin, the Compose compiler and Compose Multiplatform stay in lockstep.
    kotlin("jvm")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

group = "io.github.siddharthjaswal"
version = rootProject.version

// Deliberately NOT `org.jetbrains.intellij.platform`: like :daemon, the Mac app is a host for
// :core, and nothing from the IDE may reach its classpath.
dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
    // QR pairing (M2): Nayuki's encoder — one small MIT jar, no transitive dependencies.
    implementation("io.nayuki:qrcodegen:1.8.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    // Test-only: ZXing's *decoder* proves the QR we draw actually scans (tests + renderScreens).
    testImplementation("com.google.zxing:core:3.5.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

compose.desktop {
    application {
        mainClass = "io.github.siddharthjaswal.logpose.desktop.MainKt"
        // Packaging (signing, notarization, .dmg) is M5. Only the name is set now so `run`
        // shows "LogPose" in the macOS menu bar instead of "MainKt".
        nativeDistributions {
            packageName = "LogPose"
            packageVersion = "1.0.0"
            macOS {
                bundleID = "io.github.siddharthjaswal.logpose.desktop"
                // M3: double-clicking a `.logpose` in Finder opens it here. jpackage turns this into
                // the bundle's CFBundleDocumentTypes + an exported UTI; Main.kt receives the file via
                // `Desktop.setOpenFileHandler`. Only provable on a packaged .app (M5).
                fileAssociation(
                    mimeType = "application/vnd.logpose.capture+zip",
                    extension = "logpose",
                    description = "LogPose capture",
                )
            }
        }
        // `appearance=system` makes the native title bar follow macOS light/dark with the content.
        jvmArgs += listOf("-Dapple.awt.application.name=LogPose", "-Dapple.awt.application.appearance=system")
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

/**
 * Off-screen screenshots of the real composables (no display, no device): timeline in dark and
 * light, the detail pane, and the three empty states. Lives in the test source set so the
 * fixtures never ship; not part of `test`, so the unit suite stays fast and hermetic.
 *
 *   ./gradlew :desktop:renderScreens -PshotsDir=/some/dir
 */
val renderScreens by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Renders the desktop UI off-screen to PNGs."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.siddharthjaswal.logpose.desktop.RenderScreensKt")
    args(providers.gradleProperty("shotsDir").orElse(layout.buildDirectory.dir("screens").map { it.asFile.path }).get())
    jvmArgs("-Djava.awt.headless=true")
}

/**
 * The M1 live gate without a display: runs the session layer against a real attached device for
 * a while, asserts events landed, and renders the populated timeline to a PNG.
 *
 *   ./gradlew :desktop:liveGate -PshotsDir=/some/dir -PgateSeconds=20
 */
val liveGate by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Captures from a real device headlessly and renders the result."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.siddharthjaswal.logpose.desktop.LiveGateKt")
    args(
        providers.gradleProperty("shotsDir").orElse(layout.buildDirectory.dir("screens").map { it.asFile.path }).get(),
        providers.gradleProperty("gateSeconds").orElse("20").get(),
    )
    jvmArgs("-Djava.awt.headless=true")
}
