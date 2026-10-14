import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // No versions: declared once in the root `plugins` block (with `apply false` for the two
    // Compose plugins), so Kotlin, the Compose compiler and Compose Multiplatform stay in lockstep.
    kotlin("jvm")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}

group = "io.github.siddharthjaswal"

/**
 * The Mac app's own version — the ONE place it is set. It moves independently of the IDE plugin
 * (the root `version`, 1.9.x) and of the JitPack library (`v1.7.x` tags), and is released by a
 * `desktop-v<version>` tag (RELEASING.md → *Mac app*). It becomes CFBundleShortVersionString /
 * CFBundleVersion via `packageVersion`, the jar's Implementation-Version (what the window, the
 * share sheet and the update check read at runtime), and the release workflow refuses a tag that
 * disagrees with it. macOS wants MAJOR > 0 here, hence 1.0.0 for the first release, not 0.x.
 */
val desktopVersion = "1.0.0"
version = desktopVersion

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

/**
 * Signing and notarization read their secrets from the environment only — never from the repo —
 * the same way the plugin's signPlugin/publishPlugin read CERTIFICATE_CHAIN & co. With none set
 * (every local build, and CI without the secrets) the build produces a working **unsigned** app.
 *
 *   MACOS_SIGNING_IDENTITY  "Developer ID Application: Name (TEAMID)" (the prefix is optional)
 *   MACOS_SIGNING_KEYCHAIN  optional: the keychain holding that identity (CI uses a temporary one)
 *   APPLE_ID / APPLE_APP_PASSWORD / APPLE_TEAM_ID   notarization (an app-specific password)
 */
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
val signingIdentity = env("MACOS_SIGNING_IDENTITY")

compose.desktop {
    application {
        mainClass = "io.github.siddharthjaswal.logpose.desktop.MainKt"
        nativeDistributions {
            // arm64 only for now (PRD §10 Q6): the bundled runtime is the JDK the build runs on,
            // so an Apple-silicon runner produces an Apple-silicon app.
            targetFormats(TargetFormat.Dmg)
            packageName = "LogPose"
            packageVersion = desktopVersion
            description = "Read an Android app's network traffic and events, record a test session, share it."
            vendor = "Siddharth Jaswal"
            copyright = "Copyright © 2026 Siddharth Jaswal. Apache License 2.0."

            // The bundled runtime, trimmed by jlink. Compose always adds java.base, java.desktop
            // (which pulls java.xml, java.prefs, java.datatransfer), java.logging and jdk.crypto.ec.
            // On top, exactly what `./gradlew :desktop:suggestRuntimeModules` (jdeps) reports:
            //   java.instrument  referenced by kotlinx-coroutines' debug probes
            //   jdk.httpserver   com.sun.net.httpserver — Serve MCP (core's McpHttpTransport)
            //   jdk.unsupported  sun.misc.Unsafe — Compose, Skiko, coroutines
            // jdk.crypto.ec is repeated on purpose: without its ECDHE suites TLS to dl.google.com
            // and api.github.com fails, and jdeps can't see a security provider.
            // Proven against the PACKAGED app: `LogPose.app/Contents/MacOS/LogPose --self-check`
            // (SelfCheck.kt — a Serve MCP round-trip, HTTPS to Google and GitHub, an off-screen frame).
            modules("java.instrument", "jdk.httpserver", "jdk.unsupported", "jdk.crypto.ec")

            macOS {
                bundleID = "io.github.siddharthjaswal.logpose.desktop"
                dockName = "LogPose"
                iconFile.set(rootProject.file("packaging/macos/LogPose.icns"))
                appCategory = "public.app-category.developer-tools"
                // The floor is what the bundled binaries were linked for: the JBR 17 runtime and
                // Skiko's arm64 natives both declare minos 11.0 (`vtool -show-build`), and arm64
                // Macs never ran anything older than Big Sur.
                minimumSystemVersion = "11.0"
                // M3: double-clicking a `.logpose` in Finder opens it here (Main.kt receives it via
                // `Desktop.setOpenFileHandler`). Declared by hand in `infoPlist` below rather than with
                // Compose's `fileAssociation(...)`: jpackage 17 writes that as a document type with
                // CFBundleTypeOSTypes `****`, which LaunchServices reads as a legacy wildcard — the
                // app then claims public.data, i.e. offers to open *every* file — and it declares no
                // UTI. (Verified with `lsregister -dump` on the packaged app.)
                infoPlist {
                    // macOS 15+ asks before an app reaches devices on the LAN. Wireless debugging is
                    // exactly that — adb (our child process, so macOS attributes it to LogPose)
                    // browses mDNS for the phone's pairing/connect services and dials it. Without
                    // the usage string the prompt can't be shown and pairing fails silently.
                    // NSBonjourServices names the service types browsed (adb's own mDNS client).
                    //
                    // The `.logpose` association: a Viewer (captures open read-only) that owns the
                    // exported UTI below. Without an exported UTI `.logpose` would be a dynamic
                    // `dyn.*` type; with it the file has a real identity (Finder's Kind, Spotlight's
                    // kMDItemContentType) owned by this app. It conforms to public.data,
                    // not public.zip-archive, so Archive Utility never offers to "unzip" a capture.
                    extraKeysRawXml = """
                        |    <key>CFBundleDocumentTypes</key>
                        |    <array>
                        |      <dict>
                        |        <key>CFBundleTypeName</key>
                        |        <string>LogPose capture</string>
                        |        <key>CFBundleTypeRole</key>
                        |        <string>Viewer</string>
                        |        <key>LSHandlerRank</key>
                        |        <string>Owner</string>
                        |        <key>CFBundleTypeIconFile</key>
                        |        <string>LogPose.icns</string>
                        |        <key>LSItemContentTypes</key>
                        |        <array>
                        |          <string>io.github.siddharthjaswal.logpose.capture</string>
                        |        </array>
                        |      </dict>
                        |    </array>
                        |    <key>UTExportedTypeDeclarations</key>
                        |    <array>
                        |      <dict>
                        |        <key>UTTypeIdentifier</key>
                        |        <string>io.github.siddharthjaswal.logpose.capture</string>
                        |        <key>UTTypeDescription</key>
                        |        <string>LogPose capture</string>
                        |        <key>UTTypeIconFile</key>
                        |        <string>LogPose.icns</string>
                        |        <key>UTTypeConformsTo</key>
                        |        <array>
                        |          <string>public.data</string>
                        |          <string>public.content</string>
                        |        </array>
                        |        <key>UTTypeTagSpecification</key>
                        |        <dict>
                        |          <key>public.filename-extension</key>
                        |          <array>
                        |            <string>logpose</string>
                        |          </array>
                        |          <key>public.mime-type</key>
                        |          <array>
                        |            <string>application/vnd.logpose.capture+zip</string>
                        |          </array>
                        |        </dict>
                        |      </dict>
                        |    </array>
                        |    <key>NSLocalNetworkUsageDescription</key>
                        |    <string>LogPose finds and connects to Android phones on your Wi-Fi for wireless debugging (adb pairing and connect).</string>
                        |    <key>NSBonjourServices</key>
                        |    <array>
                        |      <string>_adb-tls-pairing._tcp</string>
                        |      <string>_adb-tls-connect._tcp</string>
                        |      <string>_adb._tcp</string>
                        |    </array>
                    """.trimMargin()
                }
                signing {
                    sign.set(signingIdentity != null)
                    signingIdentity?.let { identity.set(it) }
                    env("MACOS_SIGNING_KEYCHAIN")?.let { keychain.set(it) }
                }
                notarization {
                    env("APPLE_ID")?.let { appleID.set(it) }
                    env("APPLE_APP_PASSWORD")?.let { password.set(it) }
                    env("APPLE_TEAM_ID")?.let { teamID.set(it) }
                }
            }
        }
        // `appearance=system` makes the native title bar follow macOS light/dark with the content.
        jvmArgs += listOf("-Dapple.awt.application.name=LogPose", "-Dapple.awt.application.appearance=system")
    }
}

// The version the running app reports (AppModel.appVersion → Package.implementationVersion).
tasks.jar {
    manifest {
        attributes("Implementation-Title" to "LogPose for Mac", "Implementation-Version" to desktopVersion)
    }
}

/** For the release workflow: `./gradlew -q :desktop:printDesktopVersion` → `1.0.0`. */
val printDesktopVersion by tasks.registering {
    group = "help"
    description = "Prints the Mac app's version (the desktop-v<version> tag must match it)."
    val v = desktopVersion
    doLast { println(v) }
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
    args(
        providers.gradleProperty("shotsDir").orElse(layout.buildDirectory.dir("screens").map { it.asFile.path }).get(),
        providers.gradleProperty("shotsOnly").orElse("all").get(),
    )
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

/**
 * The M4b live gate: Serve MCP + the app as mock writer against a real device, headless.
 *
 *   ./gradlew :desktop:m4bGate -PgateSerial=emulator-5554 -PserveSeconds=45
 */
val m4bGate by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Serves MCP and mocks a real device through the app model, then checks the clear."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.siddharthjaswal.logpose.desktop.M4bGateKt")
    args(
        providers.gradleProperty("gateSerial").orElse("emulator-5554").get(),
        providers.gradleProperty("serveSeconds").orElse("45").get(),
        providers.gradleProperty("startRevision").orElse("0").get(),
    )
    jvmArgs("-Djava.awt.headless=true")
}

/**
 * Rasterizes `packaging/macos/icon.svg` into a `.iconset` (all sizes 16–1024, @1x/@2x) for
 * `iconutil`. The committed `packaging/macos/LogPose.icns` came from this; see RenderIconset.kt.
 *
 *   ./gradlew :desktop:renderIconset -PiconsetDir=/tmp/LogPose.iconset
 */
val renderIconset by tasks.registering(JavaExec::class) {
    group = "distribution"
    description = "Renders the app icon SVG into a macOS .iconset."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.siddharthjaswal.logpose.desktop.RenderIconsetKt")
    args(
        providers.gradleProperty("iconSvg").orElse(rootProject.file("packaging/macos/icon.svg").path).get(),
        providers.gradleProperty("iconsetDir").orElse(layout.buildDirectory.dir("LogPose.iconset").map { it.asFile.path }).get(),
    )
    jvmArgs("-Djava.awt.headless=true")
}

/** A real `.logpose` from the M3 gate, for checking the packaged app's file association. */
val sampleCapture by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Writes a sample .logpose capture file."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.siddharthjaswal.logpose.desktop.SampleCaptureKt")
    args(providers.gradleProperty("sampleDir").orElse(layout.buildDirectory.dir("sample").map { it.asFile.path }).get())
    jvmArgs("-Djava.awt.headless=true")
}
