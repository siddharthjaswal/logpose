# Changelog

All notable changes to LogPose are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); this project is pre-1.0 and the wire
format may still change.

## [Unreleased]

### Added — headless daemon (`logpose serve`)

LogPose's capture and all 21 MCP tools, with no IDE in the process. A plain JVM binary an agent,
a QA laptop or a CI box can point at a device: `java -jar logpose-daemon-<version>.jar serve`
tails logcat, serves MCP, and prints the exact `claude mcp add` line to paste. Nothing about the
plugin changes — it is the same code, now reachable two more ways.

- **`:core` and `:daemon`, one build.** 42% of the plugin already had zero IntelliJ imports: the
  wire model, parser, store, all 21 tools, adb, mock sync, push injection, correlation and every
  presentation model moved into a shared `:core` module, which the plugin consumes through
  `intellijPlatformPluginModule` and the daemon consumes as a plain jar. The IntelliJ-only seam
  turned out to be one interface (`KeyValueStore` — `PropertiesComponent` in the plugin, a
  properties file in the daemon). The JSON-RPC envelope came out of the Netty handler into
  `McpRpc`, which both transports and the plugin now share. **All of it is invisible from the
  plugin side**: same responses, same behavior, the existing tests moved houses unchanged.
- **Two transports.** HTTP on `127.0.0.1:63343` (JDK's own server, no framework), or
  `serve --stdio` for a client that launches the daemon itself — newline-delimited JSON-RPC on
  stdin/stdout, no port and no token, since the pipe is the authentication. Under `--stdio`
  stdout carries JSON-RPC and nothing else; every log line, banner included, goes to stderr.
- **Safe beside a running IDE, by default.** The sharp edges are specified rather than
  discovered: the daemon does **not** run `adb logcat -c` (the clear is global and would wipe the
  IDE's backlog — `--clear` opts in), and it starts **read-only on mocks**, because the device
  holds one wholesale rule set and two writers overwrite each other. `--mocks` makes it the
  writer, and says so in `--help`. Read-only is per tool, not per surface: `list_mocks`,
  `list_scenarios` and `save_scenario` still answer; the four write tools decline with a message
  naming `--mocks` instead of the IDE's "open the tool window".
- **Scenarios and correlation keys are shared with the IDE** (same `--project-dir`). Scenarios are
  plain files under `.logpose/scenarios`; the correlation vocabulary lives in
  `.logpose/correlation.properties`, which both halves read and write, so a key configured in the
  tool window is the one the daemon's `get_related`/`list_correlation_keys` group on. A vocabulary
  from an older plugin (IDE settings) or a hand-seeded `daemon.properties` is migrated into the
  shared file once, automatically, on first use.
- `GET /health` answers a token-free `{"status":"ok","events":N,"capture":"attached"}` for CI
  liveness, and `--no-bodies`, `--device`, `--token`, `--name` mirror the plugin's controls.
- **`scripts/ci-capture-check.sh`** — the CI shape of all of it, in one exit code: start the
  daemon, wait on `/health` until the capture attaches, assert the app produced events and that
  `session_summary` and `find_failures` answer over MCP, tear it down. curl only, no jq, and
  honest in its header that it needs a device and an instrumented app. It does **not** pass
  `--mocks`, so it is safe to run beside anything that writes rules.
- `scripts/agent-flow-check.sh` now names both endpoints it can drive — the IDE's port and the
  daemon's — instead of assuming a tool window. No mechanical change: it was already only
  `LOGPOSE_PORT` away from working against either.

### Fixed

- **An injected push no longer loses its trace, or its INJ marking, moments after injection.**
  LogPose injects a push; the app's own `FirebaseMessagingService` re-logs it milliseconds later
  under the same envelope id — deliberately, so the two land on one row — but from the app's call
  site, carrying the app's ambient trace. The store took that verbatim, so `get_trace` on the
  trace `inject_fcm` had just handed back came up empty, and `get_event` and `await_event`
  disagreed about the same row. An update to an injected FCM row now keeps the injection's trace
  and flag (and re-serializes the payload to agree), while taking everything else the re-log
  brings. On device libraries older than 1.7.1 this restores the `injected` flag too. Found by
  running the agent loop against a real app in the M5 dogfood; it affects the IDE identically.
- **`logpose serve --device SERIAL` no longer captures a different device in silence.** The
  device choice is written for a serial *remembered* from a previous session, where falling back
  to whatever is attached is right. A serial typed on the command line is not that, and quietly
  tailing another device would hand a CI job the wrong verdicts — so the daemon now refuses the
  mismatch, names what `adb` actually reports, and waits for the device it was told about.
- **A taken port is explained rather than reported.** `Address already in use` named neither the
  port nor the fix; the message now does both, and says that one-writer-per-device is a rule
  about `--mocks`, not about reading.

## [1.9.2] - 2026-09-02

Plugin **1.9.2**, library **v1.7.3**. Three of the oldest items on the backlog: secrets stop
leaking through query strings, two devices stop breaking capture, and search finally reads bodies.

### Added (library — v1.7.3)
- **Query-parameter redaction.** Redaction previously existed only for headers, so
  `?api_key=…`, `?access_token=…` went to logcat in the clear on every request. The URL's
  sensitive query values are now masked with the same `██` marker headers use — the parameter
  *name* stays visible, the value goes. Config mirrors the header surface exactly:
  `redactQueryParams` (exact names, case-insensitive) + `redactQueryParamPatterns` (name
  substrings), both defaulted to the query-string twins of the header lists, with the same
  documented over-redaction tradeoff (`sort_key` gets masked; a wrongly-redacted value costs a
  re-run, a wrongly-emitted one costs a rotated secret). No wire change — the `url` field is
  still a string; only its content is safer.
- **Mock matching is unaffected by design**: `matchQuery` rules match against the *real* OkHttp
  query values, before redaction — pinned by a test that serves a mock keyed on the actual
  secret while the emitted line shows `██`.
- **FCM registration tokens truncate to 12 chars + `…` by default** (`redactFcmToken = false`
  restores the full token for the copy-from-detail workflow).

### Added (plugin)
- **Device picker** — the oldest open gap. `LogcatReader`, `PushController` and the mock
  broadcasts have accepted a device serial all along; nothing ever assigned one, so a second
  attached device broke capture with an error in the detail pane. A quiet `device: auto ▾` link
  in the toolbar now lists `adb devices -l` (refreshed on open, off the EDT) and persists the
  choice per project. With 0–1 devices everything behaves exactly as before; with several and no
  choice, capture picks the first and says so instead of failing. Switching devices mid-capture
  clears rules on the old device, restarts, and notifies.
- **Search reads bodies.** The list search matched only `request.url` for HTTP rows. It now also
  matches status-code text (`404` finds the failures) and — for queries of 3+ characters —
  request/response bodies, through the same per-event cache the correlation chip uses, so typing
  never scans a payload. The counter and the filtered-to-nothing state stay consistent because
  everything routes through the one filter predicate.
- **The `exposeBodies` privacy control has a UI at last.** The Connect Coding Agent button now
  opens a small panel: copy the MCP command, plus a switch for "Expose response bodies to
  agents" — advertised in the plugin description since 1.5.0, previously settable only by
  hand-editing project properties. Withheld bodies return `payload_withheld` over MCP.


## [1.9.1] - 2026-08-29

Plugin **1.9.1**, library **v1.7.2**. A worker row can finally say how long it *waited* as opposed
to how long it *ran*.

1.9.0 shipped the worker row with a blank fact column and an approximate time column, because the
numbers weren't on the wire: `logWorker` reuses `workId` as the envelope id so a worker is one row
that mutates in place, and each state's payload overwrote the last — by the time the terminal state
arrived, the enqueue and run-start instants were gone.

### Added (library — v1.7.2)
- **`enqueuedAtMillis` and `runStartedAtMillis` on the worker payload.** The library already watches
  every state transition, so it now remembers those two instants per `workId` and stamps them onto
  every later emission for that id. **No app-facing API change**: `WorkerEventInfo` is untouched and
  the no-op needs nothing, so upgrading the dependency is the whole integration.
- Absolute instants rather than a precomputed duration, for three reasons: the spec needs *two*
  derived values (queue wait and run duration) and one number can't give both; a `running` row can
  now count up **run** time instead of time-since-first-sighting; and an agent can line a worker's
  execution up against the HTTP and db rows in that window, which a bare duration can't express.
- **Null means "not observed", never a guess.** Attach mid-flight, restart the process, or replay a
  row from WorkManager's store and the fields stay null — the UI shows nothing rather than a
  plausible-looking `queued 4h`.
- The transition bookkeeping now tracks the last observed **state**, not just a first sighting. The
  recommended `getWorkInfosLiveData` integration re-delivers every request on every emission, so
  "first time I see RUNNING" had to mean a real transition — otherwise an unrelated LiveData tick
  would reset the run start and collapse run duration toward zero. It is also properly bounded now;
  previously it only evicted on a terminal state, so periodic and never-finishing work leaked.

### Changed (plugin)
- Worker rows show **`queued 6.2s`** in the fact column, and the time column is **run duration**
  rather than queue + run. Both gate on the data being present, never on a version string, so an
  older library degrades to exactly 1.9.0's behaviour.
- The detail pane's `timing: includes queue time` note — true in 1.9.0, wrong now — is replaced by
  the real split, and `worker_history` over MCP reports the queue and run phases separately, so an
  agent asking "why was this slow" can tell waiting from running.

### Note
Plugin 1.9.0 was already submitted to the JetBrains Marketplace when this landed, so this ships as
1.9.1 rather than changing what 1.9.0 means.

## [1.9.0] - 2026-08-29

Plugin **1.9.0**, library **v1.7.1**. Group a flow by the id a human actually knows —
`order_id 21053953` — instead of a trace hash the app may never have propagated. See
[`docs/correlation-prd.md`](docs/correlation-prd.md) for the whole plan.

This came out of dogfooding 1.8.0 on a real app, and the evidence is worth stating because it's
what the design is built on. One re-sent push, one order: searching the capture for `21053953`
returned **5 events across 4 traces — one of them null**, including the
`GET /app/v4/79096/order/21053953/` that carried no trace at all. Traces fragment there for
structural reasons, not sloppy ones: the app mints its own trace when it handles a push, so the
IDE's never propagates, and an HTTP row joins a trace only when the client was built with
`LogPose.traceCalls(...)`. The waterfall for that flow therefore showed *one event*. The
`order_id` sitting in those payloads grouped all five, and needed nothing from the app.

### Added (plugin) — correlation keys
- **Tell LogPose the ids you think in.** Filter bar `⋯` → **Correlation keys…** (also linked from
  the waterfall header) takes a per-project list of key names — `order_id`, `trip_id` — with
  enable/disable and a per-key "short values" opt-in. The dialog opens **seeded with suggestions**
  the first time: id-ish names actually present in the capture, ranked by how many events each
  would group and shown with their evidence (`groups 5 events · largest 5 · latest 21053953`);
  **Suggest from capture** re-offers them later, always unticked.
  Suggestions are **inert** — nothing groups until a human ticks one — because silent
  auto-grouping is the failure this design exists to avoid. LogPose ships **no** built-in keys:
  `order_id` is your app's vocabulary, not LogPose's.
- **The key names the value; the value does the matching.** That sentence is the whole mechanism,
  and it's why this reaches what a trace can't:
  - **Extract** — the configured key is looked up in an event's payload by name, recursively,
    case- and snake/camel-insensitively (`order_id` = `orderId` = `ORDER_ID`), **including JSON
    that arrives nested inside a string value**. Real payloads hide the meaningful JSON in a
    string (an FCM `data["body"]`, an HTTP body), and a shallow scan finds nothing on exactly the
    payloads this exists for.
  - **Match** — from then on the *value* does the work: an event joins the group if `21053953`
    appears anywhere in its searchable text (url and path, request/response bodies, FCM data, db
    sql and args, worker fields, event section bodies). That's what catches
    `/app/v4/79096/order/21053953/`, where the id is a bare path segment with no key near it.
  - **Guard** — a value must be ≥ 4 characters and every match is **delimiter-bounded**, so
    `2105` never matches `21053953` and `21053953` never matches `210539531`. Shorter values are
    still extracted and shown, but they don't group unless you tick "short" for that key and
    accept the over-grouping risk.
  Request and response **headers are deliberately excluded** from the haystack: folding in auth
  tokens, cookies and trace headers would add matches on values you never see on the row.
- **Entry points read as the key, not a hash.** The row context menu leads with
  `Show waterfall — order_id 21053953` and `Filter by order_id 21053953` (one pair per configured
  key the row carries, in the order you configured them), with the trace actions below and now
  explicitly labelled `Show waterfall — trace d107086f`. The hover band that reveals `⧉ cURL` on
  an HTTP row also reveals a **`⇉ flow`** glyph on any row with a key or a trace — one click opens
  the best grouping available, key first. Filtering by a key shows a removable chip in the filter
  bar, the way filtering by trace has always read, except it can say what it grouped by.
- **The waterfall groups by more than a trace.** Its header states the grouping
  (`order_id 21053953 · 7 events · 4.2s`) and, when the originating row belongs to several, offers
  a segmented switcher (`order_id` / `trip_id` / `trace`) so widening or narrowing doesn't mean
  going back to the list. An empty group now says *"no events carry that value"* rather than
  blaming the capture buffer.
- **Find by value…** (filter bar `⋯`, and the waterfall header) needs no row at all — the common
  case is arriving with an id from a ticket, a backend log or a QA report. Paste `21053953` or
  `order_id=21053953`; it trims whitespace and strips surrounding quotes (ids arrive copied out of
  JSON as often as not), labels a bare value with the configured key that holds it, and **states
  the count before you commit** (`7 events · order_id 21053953`) so a typo is obvious rather than
  an empty screen. A too-short value says *"too short to match safely"* out loud instead of
  quietly returning nothing.
- **Correlation never runs on a paint.** Both scans are O(payload), so each event's haystack and
  key values are computed **once, on the logcat reader thread as the event arrives**, and cached
  (bounded by entry count and total characters, invalidated when the key set changes or an event
  updates in place). The renderer uses a cache-only read: a row whose values aren't extracted yet
  simply doesn't paint the glyph. One frame's worth of missing affordance is the right price for
  never scanning a body inside `getListCellRendererComponent` — the repaint-cost lesson from 1.8.0.
- The header now shows the **plugin version, and the device library's** once a device has said
  hello. Version skew between the two halves is what actually bites, so both are on screen.

### Added (MCP — 2 new tools, 21 total)
- **`get_related`** — everything carrying one business id, as a timeline in `get_trace`'s shape,
  plus a `grouped_by` field naming the key. Takes `key` + `value`, or `value` alone (the pasted-id
  case), or `event_id` to group by what that row itself carries. It reads the **same cache and the
  same vocabulary the tool window uses**, so an agent's `get_related` and a click on the row's
  `⇉ flow` glyph open an identical set.
- **`list_correlation_keys`** — the configured keys with their most recent values, plus
  suggestions from the capture, kept in two separate lists on purpose: the tool enables nothing
  and nothing groups by a suggestion.
- **`get_trace` now explains an empty result** instead of stating it. A trace that holds nothing —
  or holds only the row it was read off — is the *normal* shape of a flow the app never propagated
  a trace through, so the note names the two structural reasons and points at
  `get_related(event_id=…)`, which is what the agent should have called.

### Added (library — v1.7.1)
- **An injected push is one row again.** Re-sending a push used to produce two: LogPose's own
  `injected: true` row, and 24 ms later an unmarked twin from the app's `FirebaseMessagingService`
  re-logging the same message through `logFcmMessage`. The library now emits the injected row
  under the **message id** as its envelope id (the same trick worker events use to keep one
  updating row) and remembers a bounded set of 32 injected message ids, so the app's re-log lands
  on that row and **keeps `injected = true`**. The IDE cooperates by sending the injection id, the
  ack correlation id and `PushMessage.messageId` as **one value** — any daylight between them is
  what let the twin exist. No wire change: the fix is entirely in how ids are chosen. The no-op is
  untouched (no public API change).

### Changed
- **The collapsed injected row carries the app's trace, not the IDE's.** The app's re-log arrives
  second and updates the row, bringing whatever trace the app was in — usually its own, sometimes
  none — so the `trc-…` that `inject_fcm` reports is no longer guaranteed to be the trace *on that
  row* a moment later. This is the honest outcome (the row now reflects the app's own handling of
  the push), and it is exactly why `get_related` is the better tool here: the push, the
  `GET /order/<id>/` and the `PUT /order/accept/` group by `order_id` whatever the app did with
  traces. `inject_fcm`'s returned trace still works for events the app emits inside the injection.
- **Trace menu items say "trace" out loud** — `Show waterfall — trace d107086f`. They used to read
  `Show waterfall d107086f`, which names a hash a human has never seen; beside an
  `order_id 21053953` item it has to be obvious which of the two you're picking.

### Changed (rows) — each kind says the useful thing
A second design round, aimed at what each row actually *says* rather than how it's coloured. Same
encoding rules as above: semantics and shape, never a new hue. Success stays neutral — in a healthy
capture almost everything succeeds, and a wall of green is noise.

- **A polling wall collapses to one line.** Three or more consecutive same-method, same-path 2xx
  calls fold into a single row carrying a neutral outlined `×N` pill, the `~median` duration and
  the latest occurrence's size; double-click (or the context menu) expands it, and the detail card
  gains an `occurrence ‹ n/N ›` stepper. It breaks on any non-2xx — a failed poll must stand alone —
  and on a gap over two minutes, since a poll every 30s for ten minutes and three back-to-back
  calls mean different things. The path is matched **verbatim**: normalising numeric segments would
  merge two riders' `/79096/location/` and `/79097/location/` into one row, which is a lie `×N`
  cannot express. **A strong duplicate is never folded** — an overlapping double-submit is the bug
  LogPose exists to show.
- **DB rows lead with the verb and the table.** `[SELECT] orders  SELECT * FROM orders WHERE …` —
  a neutral verb tag (reads regular, writes bold, no hue), the table as the primary text, and the
  full statement greyed behind it. This also fixes a real mis-parse: statements like
  `UPDATE OR ABORT \`battery_saver_info\` SET …` used to render with `OR` as the operation.
- **Transaction ceremony folds.** BEGIN / `SELECT changes()` / TRANSACTION SUCCESSFUL / END collapse
  into one muted 26px row (`transaction ✓ · 4 statements · 128ms`) while the wrapped statements stay
  as normal rows. The `✓` is painted only on positive evidence, and a second interleaved `BEGIN`
  **abandons the fold rather than guessing** — the wire carries no thread id, so a wrong fold is
  worse than none. The duration is the wall span, not a sum: Room reports no per-statement timing,
  so a true `Σ` would print `0ms` on nearly every capture.
- **Worker rows state their state**: `✓ succeeded` neutral · `● running` accent with a pulsing dot
  and a live count-up · `◦ enqueued` muted · `✕ failed` red · `RETRY ×N` an outlined amber pill
  (only above attempt 1 — a `RETRY ×1` on a first-attempt block would be wrong) · `cancelled` struck
  through. The glyph stays worker-teal in every state, and the first tag that isn't the class name
  is surfaced, since that's what distinguishes one `DataSyncWorker` from another.
- **Analytics rows drop the echo.** The `· ANALYTICS` subtitle was the kind badge repeating itself;
  the screen moves to a right-aligned fact column instead, so every analytics row answers *what
  fired, where, when* in three fixed columns. In the detail card, an event with no sections and no
  non-redundant badges collapses its empty hero body instead of leaving blank space.

Collapsing is **presentation only**. The store keeps every event, and MCP, `get_trace`, the
waterfall, correlation and export still see each one individually — ⌘C on a folded row copies all N
lines, and a waterfall lane click expands the group to reveal its member.

Two honest gaps, both because the data isn't on the wire: **`queued 6.2s` is not implemented** (each
worker state overwrites the same envelope id, so the enqueue time is gone by the time the terminal
state lands — it needs `runStartedAt` from the library), and **"Show state transitions"** reads from
a small side index of what LogPose *observed*, worded to say so rather than claiming to be
WorkManager's own history. MCP's `worker_history` still sees only the surviving state.

### Changed (visual) — one axis owns hue
The tool window got its first systematic visual pass, against an external design spec written in
response to `docs/design-handoff.md`. The problem it solves: amber used to mean PUT, 4xx, db,
warning, timeout *and* pending-sync, so a single row could show three different ambers meaning
three different things.

- **Kind keeps its 7 hues; nothing else gets one.** A hue may now appear in exactly four places —
  the row gutter glyph, the TYPE filter chip, the detail-header kind pill, and waterfall lane
  marks. **Status carries only semantics**: 2xx and 3xx are neutral (success is the default; it
  doesn't need green), amber means warning, red means failure, everywhere. **Method carries no
  colour at all** — reads render dim-regular, writes bold-primary, so safety is encoded by weight
  instead of five hues. Seven hues left the system; one (`ok`) stayed, restricted to the capture
  dot and the mock sync dot, which report device state rather than an event's attributes.
- **Anything LogPose itself caused is the only solid-accent fill in a row.** `MOCK`, `INJ` and
  `MERGE` are solid accent with white text; selection stays a 15% tint, so intervention and
  selection can never blur. `DUP ×N` went outlined — filled means a fact about the response,
  outlined means advice about it.
- **Rows reclaimed 46–92px each.** FCM and generic rows no longer reserve empty method and status
  columns to align with a grid only HTTP uses; they start at the shared content edge, and the
  generic fact column widened to 120px. The time column is identical across all three kinds, so
  timestamps still line up.
- **The hover affordances became real buttons.** `⧉ cURL` and `⇉ flow` paint as bordered buttons
  and, on the selected row, stay visible instead of hiding until you happen to hover. Paint and
  hit-testing now derive from one geometry class — the old hand-tuned pixel bands were subtly
  wrong, with the flow band running into the row's right inset.
- **The filter bar earns its space.** Search, the 7 type chips and the count stay; method, status,
  hide-noise and duplicates moved into a **Filters** popover with a badge. Every active choice
  echoes as a removable chip on a second row that exists only while filters are on — which is
  also where the two previously-silent mode switches finally explain themselves (*"status filter →
  showing HTTP only"*, *"db hidden by default"*). Below ~440px the type chips become glyphs.
- **"Filtered to nothing" is its own state.** Showing the setup guide to someone with 218 captured
  events was telling them to install a tool they were already using. It now names the count, the
  mechanism actually narrowing the list, and offers a loosener chosen by *measuring* which filter
  would reveal the most events.
- **The waterfall got its first design pass**: axis labels moved below the canvas, durations
  right-align in a fixed column instead of trailing each bar (removing a text measurement from
  paint), lanes share the list's selection model, and open spans pulse on a smooth 1.6s ease that
  only runs while a waterfall with in-flight work is actually on screen. It also fixed a real bug —
  span bars were painting at 27% alpha, not the intended 70%.
- **Two components got names**: `LinkLabel` and `IconButton` replace 7 and 5 hand-rolled copies.
  Chips and switches gained the hover states they never had; the switch gained a disabled state.

Known contrast shortfalls, all inherited from spec-pinned token values and reported upstream:
white-on-accent (the MOCK/INJ pills) is 4.28:1 in both themes, and `textDim` clears 4.5:1 on the
window background but not on `bg2` (4.37) or `rowHover` (4.17).

### Notes and caveats
- A key groups **within the current capture** — there is no cross-session correlation, and a value
  that scrolled out of the buffer is gone.
- An id that is constant for the whole capture (a device or install id) ranks high in suggestions
  by design — it *would* group a lot. The human tick is the filter; that's the role suggestions
  are given rather than a shortcoming of the ranking.
- Grouping ignores the key when matching, so a value that legitimately appears under two different
  keys will group both. The ≥4-character floor and the delimiter bound are the whole defence, and
  they are deliberately blunt.

### Deferred
- README screenshots of the keys dialog, the `⇉ flow` hover glyph and the waterfall switcher —
  they need a running device with a real multi-trace flow to be worth photographing (the 1.8.0
  waterfall shots are still outstanding for the same reason).

## [1.8.0] - 2026-08-28

Plugin **1.8.0**, library **v1.7.0**. LogPose can now *start* a flow, not only watch or mock one.
Most mobile journeys begin with a push — order assigned, payment confirmed — so the origin of
every flow used to be the one thing that couldn't be reproduced. See
[`docs/flow-driver-prd.md`](docs/flow-driver-prd.md) for the whole plan.

### Added (library — v1.7.0)
- **Push injection.** The IDE can deliver a synthetic FCM message into the running app in-process
  — no Play services, no network. Register the app's entry point once and it flows through exactly
  as a real data message does:
  ```kotlin
  LogPose.onPushInject { info -> MyPushRouter.handle(info.data, info.notificationTitle) }
  ```
  Without a handler LogPose falls back to calling the manifest's `FirebaseMessagingService.
  onMessageReceived` reflectively (Firebase stays off the dependency list entirely, not even
  `compileOnly`). The ack reports which tier took it — `handler` / `service` / `none` — so
  "nothing is listening" is a stated outcome rather than a click that silently did nothing.
  **Caveat:** this simulates *foreground data-message delivery*. It cannot reproduce the
  system-tray path a background notification message takes; data messages are what trigger flows,
  so that's the tradeoff.
- **Richer mock matching** — `matchQuery`, `matchHeaders` (both accept `*` for "present, any
  value") and `matchBodyContains` narrow a rule beyond method + path. Body matching **fails
  closed**: a body the device couldn't buffer never matches, so a streaming upload goes to the
  network rather than being mocked on a guess.
- **Sequential mock responses** — `responses: [{status:500}, {status:200, body:…}]` serves a
  different response per hit, with the last step sticking. Retry logic is testable with one rule.
  Step selection is best-effort under concurrent identical calls (match and serve-count aren't
  atomic) — documented, not locked, because locking would wrap the network call.
- **An injected push is flagged on the wire** (`injected = true`), never inferred. `logFcmMessage`
  from the app's real service can't set it.

### Added (plugin)
- **Re-send / compose a push.** Right-click any captured FCM row → **Re-send this push** replays
  it verbatim (fresh message id, send time and trace); **Compose push…** (also on the toolbar,
  so it works with an empty timeline) opens a dialog for `from`, collapse key, notification
  title/body and a JSON editor for the data map. Injected pushes appear as ordinary FCM rows
  with an **INJ** pill and a banner in the detail — the timeline never lies about where a push
  came from.
- **Scenarios** — named, committable rule sets under `<project>/.logpose/scenarios/<name>.json`.
  The **Scenarios ▾** menu saves the current rules, *snapshots the whole session* into an offline
  demo (one replace-rule per endpoint, built from the latest real response), and loads either by
  merging or replacing. A snapshot never invents data: endpoints with no completed response are
  skipped **and counted**, and rows LogPose itself mocked are always skipped rather than laundered
  back in as "what the backend said". Sharing a repro with a teammate is now committing a file —
  and those files contain captured response bodies, so review before committing.
- **Trace waterfall.** A fourth detail card lays one trace on a shared time axis: one lane per
  event in arrival order, spans as bars, point events as dots, in-flight spans drawn out to "now"
  with the same breathing treatment the rows use. The header states the event count, the wall
  span and the slowest event; clicking a lane opens that event's row. Reachable from the
  structured-row and FCM-row context menus ("Show waterfall", beside "Filter by trace") and by
  clicking the **trace** chip on any detail card. Events with no trace id simply don't offer it.
- **Mock dialog: "Only when…" and "Then respond…"** — the new matchers and a reorderable step
  list, hidden until wanted, in the dialog's existing style. The mocks strip shows a `×N steps`
  chip and names the constraints in its tooltip.
- **FCM rows joined the rest of the UI** — they finally have *Filter by trace*, and the detail
  pane renders the at/trace/parent chips off the envelope like every other kind.

### Added (MCP — 5 new tools, 19 total)
- **`await_event`** — block until a matching event *arrives after the call starts*, instead of
  polling `list_events` and hoping. This is what turns the agent loop into trigger → await →
  assert. A timeout is a normal result (`matched: false`), not an error. Waiters are bounded (8
  per capture), time out on a shared scheduler, and complete off the store's lock — the MCP
  handler runs on a Netty IO thread and must never block it.
- **`inject_fcm`** — send a push, or replay a captured one by `from_event_id`. Returns the trace
  id it delivered inside, which is exactly what to hand `await_event` next. `await: true` waits
  ≤ 10s for the delivery ack.
- **`list_scenarios` / `load_scenario` / `save_scenario`** — the scenario files over MCP, with the
  same skip-mocked rule the UI applies.
- **`create_mock` extended** with `match_query`, `match_headers`, `match_body_contains` and
  `responses`. Unknown keys and bad enums are rejected loudly rather than coerced.
- **`contains` now searches FCM `data` keys and values** (MCP and the filter bar both) — a
  data-only push carries its meaning in the payload (`"channel": "order-assigned"`), and the
  first live agent run missed one because only title/body/from were searched.
- `scripts/agent-flow-check.sh` walks the whole loop (create_mock → inject_fcm → await_event →
  assert) with curl, as a readable reference for wiring an agent or a pipeline up by hand.

### Changed
- **The mocks strip tells the truth about sync.** It compares the pushed revision against the one
  the device acknowledged and shows pending/failed instead of implying "live"; a failed
  `am broadcast` is caught (its exit code *and* its output — `am` prints `Error: …` and still
  exits 0); an ack that never arrives raises a notification; and the `ruleCount` the device
  reports (added in v1.6.0 and until now unread) surfaces the reinstall-reset case — 0 rules
  active when N were pushed. Scenarios would have been dishonest without this: "loaded" is not
  "live".
- **Rules using a new matcher are withheld from an old device** rather than pushed and silently
  ignored. A rule that matches *too broadly* on an old library is exactly the trust failure
  LogPose exists to prevent, so the row says "needs device lib ≥ 1.7.0" and stays out of the push.
  The push actions are gated the same way, off `Hello.libVersion`.
- **New failure surfaces use IDE notifications, never the detail pane** — a push that wasn't
  acknowledged or a scenario that wouldn't load is about the session, not about whatever row you
  were reading.
- **Acks respect a custom tag.** `mock_ack` (and the new `push_ack`) were emitted through a
  hardcoded `LogPoseConfig()`, so an app with a custom `tag` never got its acks read.

### Fixed
- **The no-op had already drifted** — it was missing the two-arg
  `LogPoseInterceptor(config, emitter)` constructor, so a call site that used it compiled in debug
  and broke in release. That's fixed, and a reflection-based **API parity test** now compares the
  real and no-op public surfaces in the library build, so the next drift fails CI instead of a
  release build.

### Deferred
- README screenshots of the waterfall card (it needs a running device and a real trace to be worth
  photographing).

## [1.7.9] - 2026-08-03

Plugin **1.7.9**, library **v1.6.0**. A third coding-agent report, this time from building a
gandalf CI release gate — the theme is making LogPose trustworthy as headless test infrastructure.

### Added (library)
- **Headless capture export.** A CI gate can now get wire-level verdicts with no IDE or MCP session:
  set `LogPoseConfig(exportEnabled = true)` and the library retains events in a bounded on-device
  ring; a new DUMP-gated `LogPoseExportReceiver` dumps them as NDJSON into the app's external-files
  dir. `scripts/export-capture.sh <pkg>` broadcasts the dump and `adb pull`s the file.
- **`ruleCount` on the `Hello`/`MockAck` handshake** — how many mock rules are actually active, so a
  pusher can tell "0 rules, expected N" (the silent reset an app reinstall causes) from a healthy sync.

### Changed
- **A mocked request now shows an in-flight row during its latency** (library) — a slow mock, the way
  you reproduce a timeout-during-X race, was previously invisible until it finished.
- **`create_mock` warns when a `timeout`/`connection_failure` rule has `latency_ms = 0`** (plugin) — it
  throws almost instantly, testing the failure *path* but never an in-flight *window*; the doc and
  schema now say so. (A failure with latency already delayed correctly; this closes the footgun that
  let a race verification pass while testing the wrong thing.)
- **`push-mocks.sh --verify`** reads the device ack back and asserts the pushed revision/rule-count
  actually applied, turning a silent reinstall-reset into a one-line CI error.

### Docs
- **The no-op swap is documented sharply**: `logpose-android` / `logpose-no-op` share class names on
  purpose, so use the `debug`/`release` split the *same way in every module* and never a repo
  aggregator — either path puts both jars on one classpath and fails with duplicate classes.

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
