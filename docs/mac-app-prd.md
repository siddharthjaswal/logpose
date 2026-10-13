# PRD — LogPose for Mac (Compose Desktop)

**Status:** Draft v1 · **Owner:** Sid · **Targets:** a new `:desktop` module, distributed as a signed,
notarized `.dmg` · plugin and library unchanged except one plugin addition (open a capture file)
**One-liner:** A QA tester plugs in a phone — by cable or over Wi-Fi — tests the app, sees every
request, push and event as it happens, and drops the capture into Slack in two clicks. No Android
Studio, no SDK, no terminal.

Builds on [`headless-daemon-prd.md`](headless-daemon-prd.md): the daemon proved LogPose runs without
an IDE; this gives that capture a face. Everything below the paint layer already exists in `:core`.

---

## 1. Who it's for

**Primary — the QA tester (this is the main use case).** Tests staging builds on real phones every
day. Does not have Android Studio, does not have the Android SDK, will not open a terminal. Today,
when something breaks, the bug report is a screenshot and "it didn't load" — and a developer
spends the next hour trying to reproduce what the network actually did. They need three things:

1. **Connect** a phone — cable or wireless — without knowing what `adb` is.
2. **See** what the app did while they tested, in a timeline they can read.
3. **Share** exactly that, into the Slack thread where the bug is being discussed.

**Secondary — the developer who already has Android Studio.** They have the plugin. They use the
Mac app to (a) **open the capture a QA tester shared** — with the full timeline, not a paste;
(b) get a full-window timeline on a second screen; (c) capture without opening the project.

The developer's other path to a shared capture is the plugin itself: §6 adds *Open capture file*
to the plugin, so a `.logpose` file from Slack opens in the IDE too. **The real loop is QA captures →
Slack → developer opens it anywhere.**

### What the QA tester needs on the device side

LogPose only sees apps built with the `logpose-android` library. QA testers already install
staging/debug builds, so the requirement is on the build team, not the tester: **staging builds
include the library** (the debug/staging `debugImplementation` split the README documents). The app
says so explicitly when it sees a device but no LogPose traffic (§5.4).

## 2. Goals and non-goals

### Goals
1. Download → first captured event in **under 5 minutes** for a tester with nothing installed.
2. Wireless connection that a non-developer can do: **scan a QR code** on the phone.
3. Share to Slack in **two clicks**, producing something a developer can open, not just read.
4. Long test sessions don't silently lose history (the in-memory store caps at 2,000 events).
5. One implementation: the Mac app reuses `:core` for capture, parsing, filtering, correlation,
   row content and waterfall layout. It owns only the paint layer and the Mac-specific glue.

### Non-goals (v1)
- Mac App Store listing (§9 — deliberately out).
- Windows/Linux builds. Compose Desktop makes them nearly free; ship when someone asks.
- Feature parity with the plugin. No "right-click your code → mock this" — there is no code here.
- Screen recording (screenshots are in; recording is v2).
- A hosted viewer / share-by-link service. Files only; no LogPose backend.

## 3. Architecture

A new **`:desktop`** module in the existing Gradle build, beside `:core` and `:daemon`:

```
:core      capture · store · MCP · presentation models   (exists, 9.5k lines, IntelliJ-free)
:daemon    headless host                                 (exists)
:desktop   Compose Desktop host  ← new
plugin     IntelliJ host                                 (exists)
```

**The app embeds `:core` in-process.** It does not need a running daemon: it constructs
`LogcatReader` → `TransactionParser` → `EventStore` itself, warms `CorrelationIndex` per event on the
reader thread, and wires `MocksController`/`PushController` exactly as the daemon's `Capture.kt`
does — that file is the reference implementation. Optionally it serves MCP too (§7), reusing the
daemon's `HttpTransport`.

**What it reuses vs. rebuilds**

| Reused from `:core` unchanged | Rebuilt in Compose |
|---|---|
| `RowContent`, `RowGeometry` math, `RowCollapse` (polling/transaction folds) | Timeline rows (`LazyColumn`, virtualized) |
| `FilterState`, `FilterPresentation` (badge, echo chips, explainers, filtered-to-nothing) | Filter bar, Filters popover, echo row |
| `WaterfallLayout`, `WaterfallPresentation` | Waterfall canvas |
| `KindPresenter`, `Correlation`, `Grouping`, `FindByValue` | Detail cards, find-by-value |
| `MockRuleForm`, `ScenarioStore`, `PushReplay` | Mock/compose dialogs (advanced, §5.5) |
| The token *values* of the "one axis owns hue" system | A Compose `Color` theme pair (light/dark following macOS) |

The JSON panel is the one piece with no equivalent: the plugin uses the IDE's editor. The app ships
a Compose JSON tree (collapsible, syntax-coloured with the existing `jsonKey/jsonString/…` tokens,
find-in-body) and a plain monospace raw view. No folding editor in v1.

**Build risk to retire first (M1):** applying `org.jetbrains.compose` + the Kotlin Compose compiler
plugin inside a build whose root applies the IntelliJ Platform plugin. Expected to be fine (`:daemon`
already proved plain-JVM submodules coexist), Compose Multiplatform 1.7.x supports Kotlin 2.1.0.
Fallback if plugin classpaths collide: `:desktop` becomes a separate build root depending on `:core`
through an included build, the way `logpose-android` already lives separately.

## 4. Getting `adb` without an SDK

A QA tester has no Android SDK, so the app must provide `adb` itself. Resolution order:

1. An `adb` already on the machine (core's `Adb.resolve()` already checks `ANDROID_HOME`, the default
   SDK path, and `PATH`) — the developer case, and it shares their running adb server.
2. Otherwise, **first-run download** of Google's official platform-tools
   (`dl.google.com/android/repository/platform-tools-latest-darwin.zip`) into
   `~/Library/Application Support/LogPose/platform-tools/`, with a consent screen naming the source.
   Checksum-verified, updated on request.

**Why not bundle `adb` inside the app:** platform-tools ships under the Android SDK license, whose
redistribution terms need a legal read before we embed Google's binary in our artifact (§10, open
question 1). Downloading the official zip on first run sidesteps it, keeps the `.dmg` smaller, and
lets testers update adb without an app release. If legal clears bundling, it becomes a build switch.

## 5. The QA flow, screen by screen

### 5.1 Connect — the device panel

A left sidebar lists devices with live state (from `adb devices -l`, polled while the panel is
visible, never on the UI thread — core's `Adb.devices()` + `parseDevices()` already exist):

- **Cable:** plug in, it appears. If it shows `unauthorized`, the app says *"Tap **Allow** on the
  phone's USB-debugging prompt"* with a picture — not the adb state word.
- **Wireless (Android 11+) — QR pairing, the headline feature.** *Add device → Wi-Fi* shows a QR
  code. The tester opens **Developer options → Wireless debugging → Pair device with QR code** on
  the phone and scans it. Under the hood this is the same protocol Android Studio uses: the QR
  encodes `WIFI:T:ADB;S:<service-name>;P:<password>;;`, the phone advertises itself over mDNS
  (`_adb-tls-pairing._tcp`), the app finds it with `adb mdns services`, runs `adb pair <ip:port>
  <password>`, then connects via the `_adb-tls-connect._tcp` service. No IP addresses, no codes.
- **Wireless — pairing-code fallback:** for when the QR camera flow fails, enter the 6-digit code
  and the `ip:port` the phone shows.
- **Wireless — Android 10 and older:** plug in once, *Switch to wireless* runs `adb tcpip 5555` then
  `adb connect <device-ip>:5555` (IP read from the device over the cable), then the cable can go.
- **Remembered devices** reconnect automatically when they reappear on the network.
- **Onboarding:** a first-run checklist for *Developer options* and *USB/Wireless debugging*, with
  per-manufacturer notes (Samsung/Xiaomi hide it differently). This is most of the QA support load.

Honest limits, stated in-app: wireless needs phone and Mac on the same network, and **corporate
Wi-Fi with client isolation blocks it** (the app detects a failed mDNS discovery and suggests the
cable).

### 5.2 Test — the session

One click **Start session** on a device: capture attaches (the device picker's serial flows to the
reader, exactly as plugin 1.9.2 wired it), and the timeline fills live.

- **Bookmark** (⌘B or a toolbar button): drops a marker into the timeline with an optional note —
  *"tapped Accept here, spinner never stopped."* Bookmarks are the tester's way of saying *where*.
- **Recorded to disk, not just memory.** `EventStore` caps at 2,000 events (400 each for db and
  analytics) — fine for a developer glancing at live traffic, wrong for an hour-long test pass.
  The app appends every envelope line to a session file as it arrives (NDJSON, the same line format
  the library's export already writes), so the in-memory store stays the fast view and nothing is
  ever lost from the shareable record.
- **Device screenshot** button: `adb exec-out screencap -p`, attached to the session with a
  timestamp, placed on the timeline next to whatever was happening.
- The timeline itself is the redesigned one: per-kind rows, polling collapse, transaction folds,
  INJ/MOCK marking, filter bar with echo row, waterfall, correlation by `order_id`.

### 5.3 Share — to Slack

This is the step that turns a capture into a fixed bug. **Share** opens one sheet with three parts:

1. **What to include:** whole session · since the last bookmark · ±N minutes around a bookmark ·
   only the selected rows. Defaults to "around the last bookmark" when one exists.
2. **Privacy:** headers and secret query parameters are already redacted on the device (library
   v1.7.3). Bodies are the risk — a delivery app's responses carry customer names, phone numbers
   and addresses. The sheet has a **PII scrubber** (phone numbers, emails, configurable key names
   like `customer`, `address`) **on by default**, an *Include bodies* toggle, and a **preview** of
   exactly what leaves the machine. Nothing is shared unpreviewed.
3. **Output — two things at once:**
   - a **`.logpose` capture file** (§6) — the full, openable record;
   - a **Slack-ready summary** copied to the clipboard: device, app version, build, session span,
     failures, the bookmark notes, and the first failing call — formatted in Slack markdown.

The tester pastes the summary into the thread and drags the file in (the sheet offers a draggable
file icon and *Reveal in Finder*). That's v1: zero Slack setup, works in every workspace.

**v2 — direct to Slack** (§8, M6): sign in to a Slack app once (token in the macOS Keychain), pick a
channel or paste a thread link, and the app posts the summary and uploads the file itself
(Slack's `files.getUploadURLExternal` + `files.completeUploadExternal`). It needs a Slack app
approved in the workspace, which is an admin decision, hence v2.

### 5.4 When nothing shows up

The most common QA failure is a build without LogPose in it. If a device is attached and capturing
but no LogPose events arrive within ~20s, the timeline's empty state says so plainly: *"This device
is connected, but no app on it is sending LogPose events. Ask for a staging build that includes
LogPose."* — distinct from "no device" and from "filtered to nothing".

### 5.5 Advanced (hidden until enabled)

Mocks, scenarios and push injection come free from `:core` and are genuinely useful to a QA tester
reproducing a state ("make the order API return already-accepted"). They sit behind an *Advanced*
toggle so the default app stays a recorder. Coexistence rules from the daemon PRD apply unchanged:
no `adb logcat -c` by default, and **one mock writer per device** — if the plugin is the writer, the
app is read-only on mocks and says so.

## 6. The `.logpose` capture file (shared by all three hosts)

Today a capture can't leave the IDE except as clipboard text; the library's headless export writes
raw NDJSON nobody can open. Define one file format in `:core`, readable everywhere:

```
session.logpose   (a zip)
├── manifest.json     format version, device model/OS, app package + lib version, span,
│                     redaction + scrubber settings applied, bookmarks with notes
├── events.ndjson     one Envelope per line — the exact line format the library and logcat use
└── screenshots/      PNGs referenced by bookmark/timestamp
```

- **Loading is already solved:** each line goes through `TransactionParser.accept()` — the same
  parser the live capture uses — so a file replays into an `EventStore` exactly like a live session.
- A library export (`capture.ndjson`) opens too: it's an `events.ndjson` with no manifest.
- **The plugin gains *Open capture file…*** (the one plugin change in this PRD): opens a `.logpose`
  into a read-only timeline tab, so the developer can stay in Android Studio.
- **The daemon gains `logpose open <file>`** for free (it's a store load), so agents can read a
  shared capture over MCP.
- Double-click a `.logpose` in Finder → opens in the Mac app (file association in the bundle).
- Optional **HAR export** for people who live in browser devtools (HTTP rows only).

## 7. For the developer

- Opening shared captures is the developer's main use: the app and the plugin both open `.logpose`.
- A **Serve MCP** toggle exposes the app's live capture to a coding agent on `127.0.0.1:63343`,
  reusing the daemon's transport and printing the `claude mcp add` line — so a developer can point
  Claude Code at a QA tester's session *while they test*, without the IDE.
- If Android Studio is running, the app uses the developer's existing adb (§4) and leaves the
  logcat backlog alone, so the plugin and the app capture side by side.

## 8. Phasing

The QA loop — connect, see, share — lands first and is shippable on its own after M3.

- **M1 — Shell + cable capture.** `:desktop` module, window, device sidebar (cable), session
  start/stop, timeline rows from `RowContent`, a basic detail pane and JSON tree, adb resolution with
  first-run download. *Gate:* a fresh Mac user account with no SDK sees live gandalf rows.
- **M2 — Wireless.** QR pairing over mDNS, pairing-code fallback, legacy `tcpip`, remembered devices
  and auto-reconnect, the onboarding checklist. *Gate:* pair a phone by QR with no cable ever
  attached; reconnect after a Wi-Fi drop.
- **M3 — Record and share.** Disk-backed session recording, bookmarks, screenshots, the `.logpose`
  format in `:core`, the share sheet with scrubber + preview + Slack summary, Finder file
  association, and **plugin *Open capture file***. *Gate:* a QA tester shares a session in Slack; a
  developer opens it in the plugin and in the app and sees the same timeline. **← QA MVP**
- **M4 — Parity.** Filter bar + Filters popover + echo row, waterfall, correlation / find by value,
  per-kind detail cards, and the Advanced section (mocks, scenarios, push), Serve MCP.
- **M5 — Distribution.** Developer ID signing, notarization, `.dmg`, auto-update, Homebrew cask,
  arm64 build (Intel optional — every Mac sold since 2020 is Apple silicon). Evaluate **Conveyor**
  (Hydraulic): it packages, signs, notarizes and auto-updates Compose Desktop apps and is free for
  open-source projects; the alternative is Compose's own `nativeDistributions` signing config plus a
  hand-rolled update check.
- **M6 — Direct Slack upload + Shadowfax QA dogfood.** Slack app + Keychain token + channel/thread
  picker; then a week with real QA testers, counting bug reports filed with a capture attached.

## 9. Distribution — and why not the Mac App Store

**Ship outside the App Store**, signed with a **Developer ID** certificate and **notarized** by Apple,
as a `.dmg` on GitHub Releases plus a Homebrew cask. Notarization is what makes Gatekeeper open the
app without warnings; it is an automated scan, not a review, and takes minutes.

The App Store is the wrong channel for this app specifically:

- **Mandatory sandbox vs. what the app does.** The core of the product is spawning `adb`, whose
  server forks into the background and listens on port 5037, and talking to USB devices. Each of
  those runs into App Sandbox restrictions; some can be entitled, the combination is fragile.
- **Review on every release.** A dev tool that tracks Android platform changes needs to ship fast.
- **Precedent:** Android Studio, Charles, Proxyman and most adb tooling ship outside the store.

**Cost:** an **Apple Developer Program membership, $99/year** — required for the Developer ID
certificate (the App Store would need the same membership anyway). Without it the app is unsigned:
Gatekeeper blocks it and, since macOS 15, users must approve it in *System Settings → Privacy &
Security*. Acceptable for an internal pilot; not for handing to QA teams.

## 10. Open questions

1. **adb redistribution** — does the Android SDK license allow bundling platform-tools in our `.dmg`?
   Until answered, first-run download (§4). Building adb from AOSP source (Apache 2.0) is the other
   route if bundling matters.
2. **Whose Apple Developer account** — Sid's personal account (the project is open source under his
   name) or a company account? Determines the name Gatekeeper shows and who renews it.
3. **Shadowfax data policy for sharing captures in Slack** — the scrubber defaults on, but does policy
   require bodies off by default for certain apps or channels? Needs an answer before M6's dogfood.
4. **Slack app approval** — who approves a Slack app in the Shadowfax workspace (M6 only; v1 needs
   nothing).
5. **Capture file privacy at rest** — `.logpose` files linger in Downloads and Slack. Encrypt the
   bundle with a shareable passphrase? Lean no for v1 (scrubber + preview), revisit after dogfood.
6. **Intel Macs** — ship an x64 build, or arm64 only? Lean arm64 only until someone asks.

## 11. Risks

| Risk | Mitigation |
|---|---|
| QA testers can't get developer options / debugging enabled | Per-manufacturer onboarding with pictures; it's the top support cost, so it ships in M2 |
| Corporate Wi-Fi blocks wireless pairing | Detect failed mDNS, say so, offer cable; document client isolation |
| Shared captures leak customer PII | Device-side redaction (exists) + scrubber on by default + mandatory preview |
| Compose plugin conflicts with the IntelliJ Platform plugin in one build | Retire in M1; fallback to a separate build root |
| Two capture hosts fighting over one device | Daemon PRD's rules unchanged: no `logcat -c`, single mock writer |
| Store cap loses history in long sessions | Disk-backed session file from M3; the 2,000-event store is only the live view |
| Paint parity drift between plugin and app | Both render from `:core`'s presentation models; only pixels are host-specific |

## 12. Success metrics

- A tester with no SDK goes from download to a capture shared in Slack in **under 10 minutes** on
  first use, **under 1 minute** after that.
- At Shadowfax: within a month, a measurable share of app bugs arrive **with a `.logpose` attached**
  — the number that actually means fewer "can't reproduce" threads.
- Developers open shared captures in both the app and the plugin (the loop works end to end).
