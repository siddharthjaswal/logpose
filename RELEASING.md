# Releasing LogPose

How to sign and publish the plugin to the JetBrains Marketplace — and, at the end, how the
library and the Mac app ([LogPose for Mac](#mac-app)) are released.

## 1. Generate a signing key (once)

The Marketplace serves plugins over a signed chain so users know the artifact wasn't
tampered with. Generate your own key + self-signed certificate ([docs](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)):

```bash
# 1. Encrypted RSA private key (you'll be asked for a password — remember it)
openssl genpkey -aes-256-cbc -algorithm RSA \
  -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096

# 2. Decrypted key used by the signer
openssl rsa -in private_encrypted.pem -out private.pem

# 3. Self-signed certificate chain (valid 365 days)
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

> Keep `private.pem` / `private_encrypted.pem` **out of git** (they're not tracked).
> Treat them like any other signing secret.

## 2. Provide secrets via environment variables

`build.gradle.kts` reads these (the Gradle plugin auto-decodes Base64):

| Variable | Value |
|---|---|
| `CERTIFICATE_CHAIN` | contents of `chain.crt` |
| `PRIVATE_KEY` | contents of `private.pem` |
| `PRIVATE_KEY_PASSWORD` | the password from step 1 |
| `PUBLISH_TOKEN` | a [Marketplace token](https://plugins.jetbrains.com/author/me/tokens) |

Locally you can export them; in CI add them as repository secrets. Example:

```bash
export CERTIFICATE_CHAIN="$(cat chain.crt)"
export PRIVATE_KEY="$(cat private.pem)"
export PRIVATE_KEY_PASSWORD="…"
export PUBLISH_TOKEN="…"
```

## 3. Build, sign, verify

```bash
./gradlew buildPlugin     # unsigned zip (for Install-from-Disk)
./gradlew signPlugin      # signed zip in build/distributions/
./gradlew verifyPlugin    # JetBrains Plugin Verifier (compatibility check)
```

## 4. Publish

**First release is a manual upload** (JetBrains reviews new plugins):

1. Create a vendor account at <https://plugins.jetbrains.com>.
2. Upload the **signed** zip, fill the listing (description, screenshots, the demo GIF,
   license), and submit for review (≈1–3 business days).

**Subsequent releases** can be automated:

```bash
./gradlew publishPlugin   # uses PUBLISH_TOKEN; uploads to the 'default' channel
```

## Interceptor library (logpose-android)

So consumers don't need `mavenLocal`, publish the interceptor too. Quickest path is
**JitPack**: add a `jitpack.yml` that builds the `logpose-android` subproject, then tag a
release — consumers add the JitPack repo and `com.github.siddharthjaswal:logpose:<tag>`.
(Maven Central is the more "official" route but needs Sonatype + GPG signing.)

## Mac app

LogPose for Mac (`desktop/`) is released on its own: its own version, its own tag, its own
workflow, a `.dmg` on GitHub Releases. Nothing here touches the plugin or the library.

### 1. Version and tag scheme

- The version lives in **one place**: `val desktopVersion = "…"` in `desktop/build.gradle.kts`.
  It becomes the bundle's `CFBundleShortVersionString`/`CFBundleVersion`, the jar's
  `Implementation-Version` (shown in the sidebar footer, written into shared captures, compared by
  the update check). macOS requires MAJOR > 0, so the first release is `1.0.0`.
- A release is the tag **`desktop-v<version>`**, e.g. `desktop-v1.0.0`. It is deliberately not
  `v*`:
  - `release.yml` (the plugin zip) triggers on `v*`; GitHub matches tag globs from the start of
    the name, so `desktop-v1.0.0` never triggers it.
  - JitPack builds only a tag someone requests as a dependency version. Nobody requests
    `desktop-v1.0.0`, and if someone did, `jitpack.yml` would just build the library at that
    commit under that name — harmless, and nothing points at it.
  - The workflow creates the release with `make_latest: false`, so GitHub's *Latest release*
    (and anything keyed on it, like a JitPack "latest" badge) stays on the library/plugin line.
  - The app's update check only considers `desktop-v*` tags (`UpdateCheck.TAG_PREFIX`).

```bash
# bump desktopVersion in desktop/build.gradle.kts, commit, then:
git tag desktop-v1.0.0 && git push origin desktop-v1.0.0
```

### 2. What the workflow does

`.github/workflows/desktop-release.yml` runs on a `desktop-v*` tag (or by hand, given an existing
tag) on an Apple-silicon runner (`macos-14`):

1. Fails if the tag doesn't equal `desktop-v` + `./gradlew -q :desktop:printDesktopVersion`.
2. Signs only if **all six** secrets below exist; otherwise warns and builds unsigned.
3. Signed: imports the `.p12` into a throwaway keychain, runs `:desktop:packageDmg` with
   `MACOS_SIGNING_IDENTITY`/`MACOS_SIGNING_KEYCHAIN` set (Compose signs every binary with the
   hardened runtime), then `xcrun notarytool submit --wait`, `xcrun stapler staple` and
   `stapler validate`. The keychain is deleted at the end.
4. Names the file `LogPose-<v>-arm64.dmg` (unsigned: `LogPose-<v>-arm64-unsigned.dmg`), writes its
   SHA-256, fills in the Homebrew cask, and creates the GitHub Release (title says *(unsigned)*
   and the notes carry the Gatekeeper instructions when it is). Assets: the dmg, its `.sha256`,
   and `logpose.rb`.

### 3. Secrets (repository → Settings → Secrets and variables → Actions)

| Secret | Value |
|---|---|
| `MACOS_CERTIFICATE` | `base64 -i DeveloperIDApplication.p12` — the *Developer ID Application* certificate **with its private key**, exported from Keychain Access |
| `MACOS_CERTIFICATE_PASSWORD` | the password chosen when exporting the `.p12` |
| `MACOS_SIGNING_IDENTITY` | `Developer ID Application: <Name> (<TEAMID>)` (`security find-identity -v -p codesigning`) |
| `APPLE_ID` | the Apple ID email of the developer account |
| `APPLE_APP_PASSWORD` | an **app-specific password** from <https://account.apple.com> (not the Apple ID password) |
| `APPLE_TEAM_ID` | the 10-character Team ID (developer.apple.com → Membership) |

The Gradle build reads the same names from the environment (never from the repo); with none set,
every build — local or CI — produces a working **unsigned** app.

### 4. Manual fallback (on a Mac)

```bash
./gradlew :desktop:packageDmg                 # unsigned → desktop/build/compose/binaries/main/dmg/
# signed + notarized, with the Developer ID certificate in your login keychain:
export MACOS_SIGNING_IDENTITY="Developer ID Application: <Name> (<TEAMID>)"
export APPLE_ID=… APPLE_APP_PASSWORD=… APPLE_TEAM_ID=…
./gradlew :desktop:packageDmg :desktop:notarizeDmg   # Compose runs notarytool --wait, then staples
```

Upload the dmg to a GitHub Release on the `desktop-v<version>` tag (untick *Set as the latest
release*).

### 5. Verify a build

```bash
APP=desktop/build/compose/binaries/main/app/LogPose.app
"$APP/Contents/MacOS/LogPose" --self-check   # the packaged runtime: Serve MCP, HTTPS, XML, a Compose frame
plutil -p "$APP/Contents/Info.plist"         # version, NSLocalNetworkUsageDescription, the .logpose type
grep MODULES "$APP/Contents/runtime/Contents/Home/release"

# a real capture file, then the Finder association
./gradlew :desktop:sampleCapture -PsampleDir=/tmp/logpose-sample
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$APP"
mdls -name kMDItemContentType /tmp/logpose-sample/*.logpose   # io.github.siddharthjaswal.logpose.capture

# signed + notarized builds only
codesign --verify --deep --strict --verbose=2 "$APP"
spctl -a -vv "$APP"                                    # accepted, source=Notarized Developer ID
xcrun stapler validate LogPose-<v>-arm64.dmg            # The validate action worked!
spctl -a -vv -t open --context context:primary-signature LogPose-<v>-arm64.dmg
```

`--self-check` exits non-zero if the jlink module list in `nativeDistributions { modules(...) }`
is missing something the app needs at runtime — run it after changing dependencies.

### 6. Unsigned builds and Gatekeeper

Without a Developer ID the app is ad-hoc signed only. macOS 15+ blocks the first launch with no
*Open* button: the user must go to *System Settings → Privacy & Security* and click **Open
Anyway** (then confirm). On macOS 14 and older, right-click → *Open* works. Fine for a pilot, not
for handing to QA teams — that needs the $99/year Apple Developer Program (PRD §9).

### 7. Homebrew

`packaging/homebrew/logpose.rb` is a cask template; each release attaches a filled-in copy.

- **Tap (works today, signed or not):** create `github.com/siddharthjaswal/homebrew-logpose`, put
  the release's `logpose.rb` at `Casks/logpose.rb`, and users run
  `brew install --cask siddharthjaswal/logpose/logpose`. Update it per release (or automate a PR
  from the workflow later).
- **homebrew/cask (`brew install --cask logpose`):** only once releases are signed and notarized —
  homebrew/cask rejects unsigned apps (and new casks need some notability). Submit with
  `brew bump-cask-pr` / a PR to Homebrew/homebrew-cask.

### 8. The app icon

`packaging/macos/LogPose.icns` is committed (a build input). It is the plugin's compass mark
(`src/main/resources/META-INF/pluginIcon.svg`) placed on Apple's 1024 grid in
`packaging/macos/icon.svg`, rasterized with Skia (already on the classpath through Compose —
this Mac has no CLI SVG rasterizer, and `qlmanage` renders at the SVG's intrinsic size):

```bash
./gradlew :desktop:renderIconset -PiconsetDir=/tmp/LogPose.iconset   # 16…512 @1x/@2x
iconutil -c icns /tmp/LogPose.iconset -o packaging/macos/LogPose.icns
```
