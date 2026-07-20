# Changelog

All notable changes to LogPose are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); this project is pre-1.0 and the wire
format may still change.

## [Unreleased]

## [1.5.0] - 2026-07-20

The release where LogPose stops being an HTTP tool. Two changes: the timeline is now open to
any kind of event, and the capture is readable by a coding agent.

Needs `logpose-android` ≥ 1.3.0 for the new capabilities; the plugin still reads the older
un-enveloped format, so existing apps keep working while you upgrade.

### Added
- **MCP server over the live capture.** An agent in your editor can read what the running app
  actually did — `list_events`, `get_event`, `get_trace`, `find_failures`, `session_summary` —
  and close the loop with `create_mock`, `set_mock_enabled`, `delete_mock`. Click **⚡ Connect
  Coding Agent** in the tool window to copy the `claude mcp add …` command. Served on the IDE's
  built-in web server (localhost); every call is authenticated with a per-project token that
  also selects which project's capture to serve. `create_mock` prefers `from_event_id`, copying
  a real captured response so only the difference has to be stated.
- **Log your own events.** `LogPose.event("UserDao.insert") { badge("DB"); took(14); code(…) }`
  puts any subsystem — database, jobs, analytics, feature flags, navigation — on the same
  timeline. Events carry their own presentation (title, subtitle, semantic badges, typed
  sections), so they render with no plugin release. `LogPose.log(kind, payloadJson)` is the raw
  escape hatch.
- `scripts/emit-demo-events.sh` emits synthetic events to a device, to exercise rendering
  without an instrumented app.

### Changed
- **Wire format: every timeline event now travels in an envelope** (`kind`, `id`, `at`,
  `endedAt`, `traceId`, `parentId`, opaque `payload`). Timing follows a span convention —
  `endedAt` null means open, `== at` means a point in time, `> at` means a completed span.
  `traceId`/`parentId` are carried but always set explicitly; there's no implicit propagation.
- An event whose `kind` the plugin doesn't recognise now gets a row rendered from its payload
  instead of being force-decoded as a transaction and silently dropped.
- The TYPE filter gains an **APP** chip covering all app-defined kinds; search now also matches
  event titles, subtitles, kinds and trace ids.
- `TransactionStore` → `EventStore`, and `LogEvent` is now an interface (`Http` / `Fcm` /
  `Generic`) exposing id, kind, timing and trace without knowing the kind.
- `buildSearchableOptions` is disabled — LogPose has no Settings page, so it indexed nothing
  while launching a second headless IDE on every build.

## [1.4.8] - 2026-07-20

### Fixed
- Cleared the last two JetBrains Marketplace compatibility warnings, so LogPose now verifies
  clean on every checked IDE (2024.1 → 2026.2):
  - the mock dialog's method dropdown subclasses `SimpleListCellRenderer` instead of the
    `create(Customizer)` overload, which is **scheduled for removal**;
  - the mock field tree reads `myCheckbox` instead of the **deprecated**
    `CheckboxTreeCellRendererBase.getCheckbox()`.

### Changed
- `verifyPlugin` also runs against 2025.2 (the newest IDE the repo resolves for this platform).

## [1.4.7] - 2026-07-20

### Fixed
- Stop using internal platform API: the tool-window version label now reads the plugin
  descriptor via `PluginManager.findEnabledPlugin` instead of `PluginManagerCore.getPlugin`,
  which the JetBrains Marketplace verifier flags as internal ("must not be used outside the
  IntelliJ Platform").

### Changed
- `verifyPlugin` now also runs against a recent IDE (2025.1), not just 2024.x. `untilBuild` is
  open, so APIs deprecated or removed after 2024.x were previously only caught by the
  Marketplace, never locally.

## [1.4.6] - 2026-07-19

### Changed
- Revised brand mark — the chevron now points **north** ("a prompt that points north") with an
  enclosing ring on the 13/16px glyphs, so the tool-window stripe icon reads distinctly from
  the Terminal icon.
- Rewrote the JetBrains Marketplace description to cover mock & replay, FCM capture, the
  field-by-field editor, and the timeline copy — it previously only described HTTP capture.
- README: new brand mark in the header, and the feature list now leads with mocking and FCM.

## [1.4.5] - 2026-07-18

### Changed
- New brand icon set: a terminal-prompt mark (`>` + cursor) inside a radial "log-data" tick
  ring. Monochrome tool-window / action glyphs per the IntelliJ icon spec, and a full-color
  tile (lavender + amber on `#161222`) for the Marketplace logo, in light and dark. Master
  marks archived under `docs/brand/`.

## [1.4.3] - 2026-07-18

### Changed
- Mock editor breathing room: taller body area, roomier tree rows and padding, and the
  response **Headers** field is now collapsed behind a "▸ Response headers" toggle (expanded
  only when the rule already has headers) since it's rarely edited.

## [1.4.2] - 2026-07-18

### Changed
- Polished the mock editor: the response-body header (label + "Edit as text" / "Compare with
  original") now aligns cleanly left/right instead of centering, section separators span the
  full width, and an edited field reads `new  was old` with the old value smaller + struck.

## [1.4.1] - 2026-07-18

### Fixed
- The mock editor dialog failed to open ("Mock this endpoint…" and edit did nothing) — a
  property init-order bug in the new field-tree editor threw during construction. Fixed.

## [1.4.0] - 2026-07-18

### Added
- **Field-by-field mock editor.** The mock dialog now edits a captured response as a JSON
  **tree**: fold the parts you don't care about, edit any value in place, and in merge mode tick
  exactly the fields to override (a changed value shows the original beside it). Add new keys,
  or drop to a raw-text view. Everything unticked stays backend-generated.
- **Compare with original** — a native side-by-side diff of the captured response vs. what the
  app will actually receive (the merged result in merge mode).
- **Library (1.2.1):** the merge now recurses into arrays element-wise by index, so you can
  override one field inside `data[0]` without replacing the whole array. Requires
  `logpose-android` ≥ 1.2.1 on the device for array merges.

### Changed
- The mock dialog reads top-to-bottom as a sentence (When a request matches → Then → Send) and
  hides fields that don't apply to the chosen mode/behavior instead of graying them out.

## [1.3.0] - 2026-07-17

### Changed
- **Row context menus are now native IDE popups** (`JBPopupFactory` action groups) instead of
  raw Swing menus — rounded corners, proper drop shadow, themed hover, keyboard navigation, and
  type-to-filter, matching the IDE's own right-click menus.
- Polished spacing throughout — roomier tool window header, filter bar, and detail padding.

### Added
- The tool window header shows the installed plugin version (e.g. `LogPose  v1.3.0`).

## [1.2.0] - 2026-07-17

### Added
- **Merge (patch) mocks** — a rule can now keep the real backend response and deep-merge your
  JSON on top of it, instead of replacing the whole body: override a single field, add new
  keys, and leave everything else backend-generated. Pick **"Merge into the real response"** in
  the rule dialog's Mode selector; merge rules show a purple **MERGE** pill in the strip.
  Requires `logpose-android` ≥ 1.2.0 on the device.

### Fixed
- The Mocks strip no longer reads "waiting for device" while mocks are actually syncing — an
  ack now counts as device confirmation (a `Hello` only fires once per process and can predate
  capture, so it isn't a reliable signal on its own).

### Changed
- The mock rule editor pretty-prints the response body (captured bodies arrive as one long
  line), so values are easy to find and edit. Non-JSON bodies are shown as-is.
- Restyled the Mocks strip to match the timeline: carded rows with a colored method badge, a
  status / `MERGE` / `TIMEOUT` / `FAILED` pill, latency and hit chips, an on/off switch, and a
  clearer device-sync indicator. Disabled rules read as dimmed.
- Redesigned the mock editor dialog: grouped into Match / Behavior / Response sections with
  titled separators, a colored method dropdown, properly sized fields (no more clipped
  Content-Type), a bordered headers area, body line numbers + folding, and fields that gray out
  when they don't apply (merge mode, timeout / connection failure).
- Row context menus now carry icons, with **"Mock this endpoint…"** emphasized.

## [1.1.0] - 2026-07-17

### Added
- **Mock & replay.** Right-click any captured request → **"Mock this endpoint…"** to serve a
  response back to the app instead of hitting the network — edit the status, body, headers,
  add latency, or simulate a timeout / connection failure. Rules appear in a **Mocks** strip
  under the filter bar (enable/disable, edit, delete, live hit counts, "Disable all"), persist
  per project, and mocked rows are flagged with a purple **MOCK** pill so the timeline never
  lies about what the app received.
- **Library (1.1.0):** rules travel IDE → device over a new reverse channel
  (`adb shell am broadcast` → a DUMP-permission-gated receiver that ships only in the real
  debug artifact); the device confirms sync and reports hit counts back over the existing
  logcat channel. `LogPoseInterceptor` short-circuits matching requests via a new
  `MockRegistry`, gated by `LogPoseConfig(mocksEnabled = …)`. Rules clear automatically when
  capture stops. Requires `logpose-android` ≥ 1.1.0 on the device.

## [1.0.3] - 2026-07-16

### Added
- **Shift+↑/↓ keyboard range-select** in the timeline, so a run of rows can be selected (and
  then copied with ⌘/Ctrl+C) without the mouse. Plugin-only change.

## [1.0.2] - 2026-07-16

### Added
- **Multi-select + copy timeline.** Select a range of rows (click, ⇧-click, ⌘/Ctrl-click) and
  copy them as a compact, paste-ready list — one `METHOD /path` (or `FCM channel`) per line, in
  order — via ⌘/Ctrl+C or right-click → "Copy timeline (N rows)". Great for sharing the
  sequence of calls behind a flow without any request/response detail. Plugin-only change.

## [1.0.1] - 2026-07-16

### Changed
- FCM data-message rows now label themselves by their **channel** (read from a `channel` key
  in the data map) instead of the raw FCM sender/project number, which was meaningless. The
  channel also shows as a stat chip in the FCM detail view. Falls back to collapse key / from
  when no channel is present. Plugin-only change — the library is unchanged.

## [1.0.0] - 2026-07-16

### Added
- **FCM support.** LogPose now shows Firebase Cloud Messaging events — incoming pushes and
  registration-token refreshes — inline in the same timeline as HTTP traffic. FCM rows carry
  an `FCM` tag with a `NOTIF` / `DATA` / `TOKEN` badge; selecting one opens a detail view with
  the notification, metadata (from, priority, ttl, collapse key), and the data payload as a
  JSON tree. A new **TYPE** filter (`NET` / `FCM`) narrows the unified stream.
- **Library:** a new Firebase-free entry point — `LogPose.logFcmMessage(FcmMessageInfo(…))`
  and `LogPose.logFcmToken(token)` — that the host app calls from its
  `FirebaseMessagingService`. LogPose depends on no Firebase types, so the `logpose-no-op`
  artifact stays a pure-JVM jar and the same call site compiles in release. Ships via JitPack
  on the next tag.

## [0.9.11]

Version-alignment release: the plugin version now matches the `logpose-android` /
`logpose-no-op` JitPack tag `v0.9.11`. No functional plugin changes since 0.9.10 — the
failed-request fix below shipped in 0.9.10 and is included here unchanged.

## [0.9.10]

### Fixed
- Failed requests no longer render an opaque `"(failed)"` in the Response card. When a call
  throws before a response arrives (connection reset, timeout, cleartext-not-permitted, or a
  downstream interceptor that converts errors into exceptions), the Response card and the
  Overview card now show the captured exception text, so the failure is actually diagnosable.
  ([#1](https://github.com/siddharthjaswal/logpose/issues/1))
- **Library:** the interceptor now captures *any* throwable from the chain, not just
  `IOException`. A downstream interceptor throwing a `RuntimeException` previously left the
  transaction stuck "pending" forever; it now emits an error transaction (and rethrows
  unchanged). Ships via JitPack on the next tag.

## [0.9.9]

### Added
- **Duplicate-call detection** — repeated identical requests fired within a short window are
  flagged with a `DUP ×N` tag in the list. Severity is tiered: overlapping in-flight,
  non-idempotent calls (the classic double-tap double-submit) show red; redundant completed
  writes show amber; repeated GETs are a muted "info". The detail Overview shows a warning
  banner explaining the likely cause, hovering a row gives a tooltip, and a "Dupes" filter
  isolates them. Matching strips cache-buster query params, sorts params, and honours
  `Idempotency-Key` headers to avoid false positives; genuine retries are not flagged as
  double-submits.
- Unit tests for the duplicate detector (window, severity, idempotency-key, retry, chains).
- Raw JSON view: key/value color coding (purple keys, matching the tree), line numbers,
  indent guides, and a default fold depth that pre-collapses deeply-nested nodes on large
  payloads (short responses still open flat).
- "Headers" show/hide toggle on the Request and Response cards. Response headers (CSP,
  security, caching) are hidden by default to cut noise; request headers stay shown.

### Added (earlier, previously unreleased)
- Light-theme support — the tool window now adapts to the active IDE theme (was dark-only).
- Real IntelliJ JSON editor in Raw mode: native code folding + IDE syntax highlighting.
- One-click filter bar: URL search, Method/Status toggles, and a Hide-noise switch.
- Overview "ID" chip prefers a server trace/request id (x-request-id, traceparent, …).
- Find (⌘F) within Request/Response with match navigation.
- Endpoint muting (right-click), Copy as cURL / JSON, copy response body.
- Compass tool-window + plugin icon (light/dark).
- Helpful empty state that reminds first-timers to add the interceptor.
- Plugin signing + publishing config, CI, and a release/publish workflow.

## [0.1.0]

### Added
- Initial release: tool window that reads structured HTTP transactions from logcat.
- `logpose-android`: drop-in OkHttp interceptor emitting atomic per-exchange JSON,
  with multipart metadata, gzip handling, header redaction, and chunking.
- Master/detail UI, collapsible JSON tree, filtering, chunk reassembly.
