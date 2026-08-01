# Changelog

All notable changes to LogPose are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); this project is pre-1.0 and the wire
format may still change.

## [Unreleased]

## [1.7.8] - 2026-08-01

Plugin **1.7.8**, library **v1.5.7**. Async HTTP rows can now join a trace.

### Added (library)
- **Trace-tagged HTTP rows.** The ambient `withTrace` is thread-local, but the interceptor emits
  on OkHttp's own thread — so an async request's row never carried the trace. Now a
  `LogPoseTrace` request tag (or the ambient trace, for a synchronous call) is resolved at
  intercept time and stamped onto the row. Attach it per call with `Request.Builder.logPoseTrace()`
  or once around a client with `LogPose.traceCalls(callFactory)` — which tags each request with
  the ambient trace **at call-creation time** (the coroutine frame, before the network hop).
- **`LogPose.traceContext()`** — a coroutine `ThreadContextElement` that keeps the ambient trace in
  scope across `launch`/`withContext` hops, so an async flow's HTTP row and its analytics/db events
  land in one `get_trace` group. Coroutines is a `compileOnly` dep; the no-op stubs it with
  `EmptyCoroutineContext`.

### Changed (plugin)
- **`session_summary`'s empty-traces note** now tells the agent how a flow opts in
  (`withTrace` / `traceContext`, HTTP rows via `traceCalls`) instead of pointing only at
  `newTraceId()`.

## [1.7.7] - 2026-08-01

Plugin **1.7.7**, library **v1.5.6**. A third coding-agent report, again ordered by pain. The
theme: trust the honest signals.

### Fixed
- **`list_mocks` now reports `served`** — counted from the captured `mocked: true` responses in
  the buffer, the trustworthy signal — instead of the device `hits` counter, which only rode back
  on a rule-set apply and so read 0 while a rule was demonstrably serving. The device counter is
  kept as a secondary `device_hits`.
- **Ambient trace now covers the FCM row itself** — `logFcmMessage` / `logFcmToken` bypassed the
  trace-injecting `emit()`, so a push handled inside `withTrace { }` correlated everything it
  triggered *except* the push. (library)

### Added
- **`create_mock` warning names the real precondition** — a mock activates once the app has
  announced itself to *this* capture; the warning now says to restart the app, not just "start
  capture".
- **`LogPose.continueTrace { }`** — carry the ambient trace across one async hop
  (`val work = continueTrace { … }; launch { work() }`), since the thread-local trace doesn't
  follow a `viewModelScope.launch`. (library)

## [1.7.6] - 2026-07-31

Acting on a second coding-agent report, ordered by the pain each caused. The theme: making
LogPose usable as test infrastructure, not just an interactive debugger.

### Added
- **`clear_capture` MCP tool** — reset the buffer for "start clean, run, read only my events".
- **`exclude` on `list_events`** — drop a chatty feed (`exclude: "SFX_GEOFENCE"`) server-side.
- **Headless mock push** — `scripts/push-mocks.sh` pushes a rules file over adb with no IDE or
  agent, so the mocked tier is CI-runnable (Maestro can't call MCP).

### Changed
- **`create_mock` leads with `active: true/false`** and a loud warning when no device is synced —
  it used to report `created` as success even when the mock never served.
- **Capture health** in `session_summary` / `list_events` (running, last-event age) so an empty
  result is never mistaken for "nothing happened".
- **Capture auto-reattaches** (up to 5×, 2s apart) after an adb stream drop — it used to die
  silently on an app reinstall.
- **Per-kind buffer quotas** — the chatty kinds (analytics, DB, capped at 400 each) can no longer
  evict the HTTP calls and the one accept/reject worth seeing.
- **Stale disabled mocks are pruned on a new app run**, so a leftover from a previous session
  can't poison the next.

### Library — logpose-android 1.5.5 - 2026-07-31
- **`LogPose.withTrace { }`** — an ambient thread-local trace id that every event emitted inside
  auto-inherits, so a flow (mint one at the FCM/screen entry) collapses into a single `get_trace`
  call. Mirrored in the no-op.

## [1.7.5] - 2026-07-31

UI upgrade — no behaviour change, no library dependency.

### Changed
- **Typed rows.** Every row now leads with a colour-coded type glyph in the gutter, one hue per
  kind. Non-network rows leave the method/status columns empty so the centre aligns with HTTP, and
  the type is stated exactly once — the old doubled type text (a kind label plus a truncated pill
  like `ANAL…` / `SELE…` / `SUCC…` / `ENQ…`) is gone.
- **TYPE filter chips** are lit in their kind's hue, matching the row gutters — so filter state
  reads from the rows alone, and analytics no longer borrows GET's blue.
- **Detail pane** states the type once (a type glyph + kind chip in the matching hue, dropping the
  duplicate badge), and renders flat key/value data (analytics params, DB bound args, config
  values) as a 2-column table instead of a JSON blob.

## [1.7.4] - 2026-07-30

### Added
- **Analytics events.** `LogPose.logAnalytics(AnalyticsEventInfo(name, params, screen, provider,
  traceId))` — one line in your analytics facade puts every event on the timeline next to the API
  call and screen that fired it. New `ANLY` filter chip; renders on older plugins as a generic row.
- **`analytics_events` MCP tool** — verify contracts ("did `purchase_complete` fire once with the
  right value?") via per-event params and `by_name` counts, and read `screen_flow`: the observed
  screen-to-screen transitions (the seed for a flow graph).

### Library — logpose-android 1.5.4 - 2026-07-30
- `LogPose.logAnalytics(...)` (see above). Emitted under an `analytics` kind carrying a
  self-describing payload; `analyticsEnabled` switches it off. Param masking is **off by default**
  (staging/test data) — opt in with `redactAnalyticsParams = LogPoseConfig.DEFAULT_REDACT_PARAMS`.
  Mirrored in the no-op.

## [1.7.3] - 2026-07-30

### Added
- **Quick-diff button on each mock row** — original captured response vs what the rule serves,
  in the native side-by-side diff, without opening the editor. Shown only when the rule has a body.

## [1.7.2] - 2026-07-30

### Worker events: tell replays from live runs
- WorkManager replays its persisted store to a freshly-attached observer, so terminal work from
  previous runs used to land on the timeline stamped "now" — indistinguishable from work that ran
  this session, and the reason `FetchDeliveryRecipientsWorker` read as "20 runs on startup" when
  most were history. Such events are now flagged `replayedAtAttach`: a workId first seen already
  terminal ran before capture was watching. The row shows a muted **replayed** badge, and
  `worker_history` splits `ran_this_session` from `replayed_at_attach`.
- Needs `logpose-android` ≥ 1.5.3 to populate the flag; the muted badge and the `worker_history`
  split are the plugin side. Wire: `WorkerEvent.replayedAtAttach` (plugin + library, in lockstep).

### Library — logpose-android 1.5.3 - 2026-07-30
- Emits `replayedAtAttach` on worker events (see above). No other change; a straight companion to
  the plugin's 1.7.2 badge.

### Library — logpose-android 1.5.2 - 2026-07-22
- **Custom body decoding** ([#4](https://github.com/siddharthjaswal/logpose/issues/4)) — register
  a `BodyDecoder` on `LogPoseConfig.bodyDecoders` to turn encrypted or custom-binary payloads into
  readable text for the inspector. Decoders are tried in order (first non-null wins), fall back to
  the raw body, and a throwing decoder is skipped rather than fatal. Decoded bodies are flagged
  `decoded` on the wire. Mirrored in the no-op.
  - *Wire:* `Body` gains a `decoded` flag (plugin + library, in lockstep). A future plugin release
    will show a "decoded" marker; older plugins ignore the field. No plugin update needed — the
    decoded text renders in the existing inspector.

## [1.7.1] - 2026-07-22

Docs only — no behaviour change.

### Changed
- The Marketplace description and READMEs now **lead with example prompts** for the coding
  agent ("what failed in the last 2 minutes?", "mock `/v1/orders` to return a 500…") instead of
  opening with the tool list. Showing what you can ask lands faster than describing the transport.

### Library
- **`logpose-android` 1.5.1** — a clean republish of 1.5.0 under a fresh tag. 1.5.0's JitPack
  build got wedged by a tag that was force-recreated during release; the code is identical.
  Use `v1.5.1`.

## [1.7.0] - 2026-07-21

Acting on a coding agent's report from a real gandalf capture. Three of its findings were
things LogPose got quietly wrong rather than features it lacked.

Needs `logpose-android` ≥ 1.5.1 for session boundaries and the wider redaction defaults.

### Security
- **Redaction defaulted to four header names**, so `API-KEY` came through in full beside a
  correctly-masked `Authorization`. The default list now covers the credential headers in wide
  use, and a second list matches on **name substrings** (`token`, `secret`, `apikey`, `auth`, …)
  to catch the vendor headers no fixed list can enumerate. Exposed as
  `LogPoseConfig.DEFAULT_REDACT_HEADERS` / `DEFAULT_REDACT_PATTERNS` so custom names *extend*
  the defaults instead of silently replacing them — the shape of the original bug.
  Redaction still covers headers only; a secret in a body is captured as-is.

### Added
- **Sessions.** A capture that spans an app restart is no longer reported as one timeline — two
  50-second bursts either side of a relaunch used to read as six hours of steady traffic, and
  every aggregate over it inherited the error. The library now stamps each run with a process id
  on its `Hello`, the store splits on it, `session_summary` reports per-run spans and counts, and
  `list_events` takes a `session` filter. Events captured before the app announced itself are
  reported as unattributed rather than folded into a run.

### Changed
- **`session_summary` is a jump table, not a scoreboard.** `failures` and `duplicate_calls` were
  bare counts, so finding what they referred to meant paging `list_events` by hand. Both now
  carry event ids, grouped so repeats of one problem don't crowd out the rest.
- **`find_failures` collapses identical failures** with a count and the ids, instead of returning
  the same request four times.
- **`by_kind` enumerates every known kind, including zeros.** An absent key read as "not
  counted" and gave no way to tell that from "never fired".
- **An empty `traces` array now explains itself** — it means the app never set a trace id, since
  LogPose never infers causality, and a bare `[]` read as "nothing wrong".

### Removed
- **`find_slow_queries` is gone**, replaced by **`query_hotspots`**. It promised a ranking it
  could never produce: Room's `setQueryCallback` fires *before* execution and carries no
  duration, so on every real capture the tool reported nothing and taught callers to stop asking.
  `query_hotspots` reports statements that ran repeatedly, most-repeated first — which needs no
  timing and catches the failure mode that actually hurts, an N+1 running one query per row.
  Durations are still reported per group when the app passes `durationMillis` itself.

### Changed
- **DB events no longer appear in the timeline by default.** A Room callback outproduces every
  other source by an order of magnitude — a real capture held 75 queries against 12 requests —
  and burying the traffic people opened LogPose to see is the wrong default. Nothing is
  discarded: the events are captured, one click on the **DB** chip shows them, and they remain
  fully readable over MCP.

### Notes
- Worker and config events showing zero in a capture is usually not a broken integration:
  **Start Capture clears the log buffer**, and both are emitted once at launch. Start the
  capture before launching the app, or relaunch it after starting. `Hello` already works around
  this by re-announcing on first intercept; the same treatment for config/worker is not yet done.
- LogPose still has no true query timings. `query_hotspots` deliberately answers a question the
  data supports rather than approximating one it doesn't; a delegating
  `SupportSQLiteOpenHelper.Factory` is the only way to measure real execution and is not shipped.

## [1.6.1] - 2026-07-21

No functional change — a Marketplace listing that had fallen two releases behind the plugin.

### Changed
- The Marketplace description now covers the **MCP server** (all 12 tools, grouped by read /
  diagnose / mock), the **db, worker and config** kinds, and **app-defined events**. It
  previously described only capture, inspect and mock, and still listed the type filter as
  "NET/FCM". The description is embedded in the plugin descriptor, so correcting it needs a
  release.
- README: install now points at the Marketplace rather than a sideloaded zip; the plugin ↔
  library version pairing is stated up front in Getting started; the filter table documents the
  TYPE toggle; the MCP tool list includes `find_slow_queries`, `worker_history` and
  `config_changes`.
- Documented coordinates were `v1.3.0`, a version that was never tagged and 404s on JitPack —
  now `v1.4.0`.

## [1.6.0] - 2026-07-21

Database access, background work, and remote-config flags become first-class kinds — the three
things essentially every Android app has, now understood rather than merely displayed.

Needs `logpose-android` ≥ 1.4.0 to emit them.

### Added
- **DB events.** `LogPose.logDbQuery(DbQueryInfo(…))`, a one-line integration with Room's
  `setQueryCallback`. Rows read `users · SELECT id FROM users` — the operation and table are
  parsed from the statement by the plugin, so the device only sends what it knows. Reads are
  toned quietly and mutations stand out, because a busy screen is mostly SELECTs.
- **Worker events.** `LogPose.logWorker(WorkerEventInfo(…))`, typically from a single
  `WorkManager.getWorkInfosLiveData` observer that covers every worker without touching any of
  them. A request is emitted under its own `workId`, so enqueued → running → succeeded update
  **one row** rather than adding three. Retries are badged.
- **Config events.** `LogPose.logConfigSnapshot(values, …)` hands LogPose the whole snapshot and
  it reports the diff — Firebase Remote Config won't tell you what changed. One row per
  activation listing the changed flags with before → after values, not one row per key. The
  first snapshot of a process is recorded as a baseline count.
- **Three MCP tools** for the questions these kinds exist to answer: `find_slow_queries`,
  `worker_history`, `config_changes`.
- `DB` / `WORK` / `CONF` filter chips.

### Changed
- The row layouts for db, worker, config and app-defined kinds collapsed into one
  presenter-driven row; `KindPresenter` maps every structured payload onto the same
  title/badges/sections model that a self-describing event supplies for itself, so one detail
  view serves them all.
- Long durations read as `1.2s` / `2m 5s` rather than `94210ms`.

### Notes
- `find_slow_queries` **excludes** queries the app didn't measure rather than reporting them as
  0ms — Room's query callback carries no timing, and calling them instant would sort them to the
  fast end of a slowness ranking.
- Worker durations are derived from `WorkInfo` state changes, so they include queue time. The
  detail says so rather than implying precision.

## [1.5.0] - 2026-07-20

The release where LogPose stops being an HTTP tool. Two changes: the timeline is now open to
any kind of event, and the capture is readable by a coding agent.

Needs `logpose-android` ≥ 1.4.0 for the new capabilities; the plugin still reads the older
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

### Breaking (library `v1.4.0`)
- `TransactionEmitter` → **`EventEmitter`**, now taking the `Envelope` rather than a
  `Transaction`. Only affects apps using the custom-transport escape hatch
  (`LogPoseInterceptor(config, emitter = …)`); the ordinary interceptor and FCM call sites
  compile unchanged.
- An app on library `v1.4.0` **must** run plugin ≥ 1.5.0. An older plugin can't parse an
  envelope: HTTP rows are dropped silently and FCM rows decode into near-empty junk. The
  reverse direction is safe — plugin 1.6.0 still reads pre-envelope libraries.

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
