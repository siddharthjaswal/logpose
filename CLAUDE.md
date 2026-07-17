# CLAUDE.md

Guidance for Claude Code (and any agent) working in the **LogPose** repository.

## What this is

LogPose is an open-source network inspector for Android. It reads an app's HTTP traffic out
of **logcat** as clean, structured, per-request transactions — fixing the interleaved lines,
mismatched bodies, and 4 KB truncation you get from ad-hoc network logging.

It has **two halves that ship through two different channels** — keep them straight:

| Half | Path | What it is | Distributed via |
| --- | --- | --- | --- |
| **IDE plugin** | repo root (`src/`) | Kotlin + IntelliJ Platform plugin: the tool window that renders transactions | **JetBrains Marketplace** (plugin id **32148**) as a built `.zip` |
| **On-device library** | `logpose-android/` | A drop-in OkHttp `Interceptor` that emits structured transactions to logcat | **JitPack** (a Gradle dependency) |
| **No-op library** | `logpose-android/no-op/` | A pure-JVM stub mirroring the interceptor's public API, for release builds | **JitPack** |

A change to plugin UX goes through the Marketplace zip; a change to capture/emit behavior
goes through the JitPack library. They version independently.

## Build & run

### IDE plugin (repo root)
```bash
./gradlew runIde         # launch a sandbox IDE with the plugin loaded
./gradlew buildPlugin    # → build/distributions/logpose-<version>.zip (Marketplace upload)
./gradlew test           # unit tests (e.g. DuplicateDetectorTest)
./gradlew verifyPlugin   # JetBrains Plugin Verifier against pinned IDEs (2024.1, 2024.3)
```
- Version is set in `build.gradle.kts` (`version = "..."`).
- `untilBuild` is intentionally open (`provider { null }`) so the plugin loads on newer IDEs.
- The plugin talks to `adb` directly and depends on `bundledPlugin("com.intellij.modules.json")`
  for the Raw-mode editor — it deliberately does **not** depend on the Android plugin, so it
  runs in any JetBrains IDE.

### Signing & publishing the plugin
`signPlugin` / `publishPlugin` read secrets from the environment only (never the repo):
`CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`, `PUBLISH_TOKEN`. Absent vars are
fine for a normal build. For a manual Marketplace upload you can upload the unsigned
`buildPlugin` zip — JetBrains signs it server-side. See `RELEASING.md`.

### On-device library (`logpose-android/`)
It's a separate Gradle build (own `settings.gradle.kts`, own wrapper). JitPack builds it via
`jitpack.yml` on each pushed git tag:
```bash
cd logpose-android && ./gradlew publishToMavenLocal -Pversion=<version>
```
This publishes two artifacts — `logpose-android` (real) and `:no-op` (stub). Current JitPack
coordinates:
```kotlin
debugImplementation("com.github.siddharthjaswal.logpose:logpose-android:<tag>")
releaseImplementation("com.github.siddharthjaswal.logpose:logpose-no-op:<tag>")
```
`okhttp` is `compileOnly` in both (the host app provides it); only the real lib pulls
`kotlinx-serialization`.

## Code map

### Plugin (`src/main/kotlin/io/github/siddharthjaswal/logpose/`)
- `toolwindow/` — `LogPosePanel` (master/detail UI), `TransactionListRenderer` (list rows),
  `LogPoseToolWindowFactory`.
- `ui/` — `Ui.kt` (`Theme` tokens as `JBColor` light/dark pairs, `TagLabel`, helpers),
  `OverviewPanel`, `TransactionDetailView` (HTTP), `FcmDetailView` (FCM),
  `JsonTreePanel` (Tree + Raw JSON editor), `FilterBar` (incl. the `NET`/`FCM` TYPE toggle),
  `CurlBuilder`, `MutedEndpoints`.
- `logcat/` — `LogcatReader` (tails `adb logcat`, all adb work **off the EDT**),
  `TransactionParser` (reassembles chunked JSON; returns a `LogEvent`, and routes reverse-channel
  `ControlMessage`s — hello / mock-ack — to `onControl`), `Adb` (shared adb resolve + cmd prefix).
- `mock/` — `MocksController`: owns the rule set, persists it per project, and pushes rules to
  the device via `adb shell am broadcast` (**off the EDT**); consumes hello/ack control messages
  to track sync + hit counts. Clears device rules on Stop Capture.
- `store/` — `TransactionStore` (capped, insertion-ordered, id-keyed; holds `LogEvent`s).
- `analysis/` — `DuplicateDetector` (flags repeated requests; HTTP-only, pure + unit-tested).
- `model/Transaction.kt` — the wire contract shared (by structure) with the library; carries
  `FcmMessage` (FCM events) and the mock/reverse-channel types (`MockRule`, `MockRuleSet`,
  `Hello`, `MockAck`; `Transaction.mocked`). `model/LogEvent.kt` is the sealed `Http`/`Fcm`
  union the whole UI switches on for the **unified** timeline.
- `src/main/resources/META-INF/plugin.xml` — plugin descriptor + `<change-notes>`.

### Library (`logpose-android/src/main/kotlin/io/github/siddharthjaswal/logpose/`)
- `LogPoseConfig`, `LogPoseInterceptor` — the HTTP public API.
- `LogPoseFcm.kt` — the FCM public API: the `LogPose` object
  (`logFcmMessage` / `logFcmToken`) plus the Firebase-free `FcmMessageInfo` holder the app
  fills from a `RemoteMessage`. LogPose references no Firebase types, so the no-op stays
  pure-JVM. Both are mirrored in `no-op/`.
- `emit/` — `TransactionEmitter` + `LogcatEmitter` (chunked logcat output; `emit(Transaction)`,
  `emit(FcmMessage)`, `emit(Hello)`, `emit(MockAck)` share one chunker).
- `mock/` — the device end of mock/replay: `MockRegistry` (process-wide active rules, pure +
  unit-tested), `MockCommandReceiver` (DUMP-gated broadcast receiver that ingests chunked
  rule pushes and acks), `LogPoseInitProvider`/`LogPoseRuntime` (zero-config auto-init that
  learns the package name and emits the `Hello` handshake). Registered in `AndroidManifest.xml`
  — real artifact only; the no-op ships none of it. `LogPoseInterceptor` consults
  `MockRegistry` before `chain.proceed()` and serves matches with `mocked = true`.
- `internal/BodyCapture` — body/header capture, gzip, multipart metadata, redaction.
- `wire/Wire.kt` — the serialized transaction + `FcmMessage` + mock/reverse-channel model
  (`MockRule`/`MockRuleSet`/`Hello`/`MockAck`; must stay in sync with the plugin's
  `model/Transaction.kt`).
- `no-op/` — the release stub; mirrors `LogPoseConfig`/`LogPoseInterceptor` exactly so call
  sites compile unchanged when swapping `debugImplementation` → `releaseImplementation`.

## Conventions

- **Commits:** author as **Sid / Siddharth Jaswal**, **no `Co-Authored-By` trailers**.
- **Don't commit** unless asked. Never commit secrets or signing keys (`*.pem`, `chain.crt`
  are gitignored).
- **Wire compatibility:** the plugin's `model/Transaction.kt` and the library's `wire/Wire.kt`
  describe the same JSON. Change them together, and treat the format as still pre-1.0.
- **EDT discipline:** never block the EDT on `adb`/process I/O in the plugin — that caused an
  IDE-freeze bug; all reader/process work runs on background threads.
- **Theme:** use `Theme.*` tokens (`JBColor` light/dark pairs), never hard-coded colors, so
  the UI adapts to the active IDE theme.
- Keep `CHANGELOG.md` and `plugin.xml` `<change-notes>` updated when shipping plugin changes.
