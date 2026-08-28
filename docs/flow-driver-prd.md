# PRD — LogPose Flow Driver

**Status:** Draft v1 · **Owner:** Sid · **Targets:** plugin **1.8.0** · `logpose-android` **v1.7.0**
**One-liner:** Let LogPose *start* flows, not just watch or mock them — inject the push that
triggers everything, await the result, and replay whole sessions as scenarios.

This PRD is written as an execution handover: every feature names the files it touches, the
wire changes it makes, and its definition of done. Read `CLAUDE.md` first; its conventions
(wire lockstep, no-op mirror, EDT discipline, Theme tokens, commit style) bind everything here.

---

## 0. Background

Mock & replay (shipped 1.7.x / v1.6.0, see `docs/mock-replay-prd.md`) made LogPose a test
harness for *responses*. But most mobile flows don't start with the app making a request —
they start with a **push**: order assigned, payment confirmed, config kicked. Today LogPose
can only watch FCM (`LogPose.logFcmMessage`); nothing on-device can deliver a synthetic push,
so the origin of every flow is still un-mockable.

Meanwhile the MCP surface lets an agent *read* the capture and *write* mocks, but the agent
loop is poll-and-hope: trigger something, then repeatedly `list_events` until the result shows
up. And mocks themselves are single-shot rules — no named suites, no "first call succeeds,
second fails", no way to bottle a captured session into an offline scenario.

Five features close this, in dependency order:

| # | Feature | Half | Wire change? |
|---|---------|------|--------------|
| A | FCM injection & replay | library + plugin + MCP | yes (command + ack + `injected` flag) |
| B | `await_event` MCP tool | plugin only | no |
| C | Scenario suites (session → mock set) | plugin + MCP | no (file format only) |
| D | Richer mock matching + sequential rules | library + plugin + MCP | yes (`MockRule` extensions) |
| E | Trace waterfall view | plugin only | no |

Non-goals for this cycle: WebSocket/SSE capture, capture diffing, request mutation, iOS,
release builds (no-op stays inert), background *notification*-tray simulation (see A-caveat).

---

## A. FCM injection & replay

### A1. User experience

**Plugin**
- Right-click any captured FCM row → **"Re-send this push"**. Ships the captured
  `FcmMessageInfo` fields back to the device verbatim (new `messageId`, `sentTimeMillis = now`).
- Toolbar / FCM context menu → **"Compose push…"**: dialog with `from`, `collapseKey`,
  optional notification title/body, and a JSON editor for the `data` map (reuse the raw-JSON
  editor pattern from `MockRuleDialog`). Buttons: Send, Send & repeat-on-shortcut.
- Injected pushes appear in the timeline as normal FCM rows with an **INJ** pill (accent
  color via `Theme` tokens, same trust model as the purple MOCK pill) — the timeline never lies.
- Delivery feedback: the push ack (see A3) reports `delivered: handler | service | none`.
  On `none`, show a notification: "No push handler — register `LogPose.onPushInject { }` or
  keep a FirebaseMessagingService in the manifest" (exact wording in A5).

**Library (app-facing API)**
```kotlin
// Tier 1 — reliable, Firebase-free. One line in app init:
LogPose.onPushInject { info: FcmMessageInfo ->
    // route into the app's own push handling, e.g.:
    MyPushRouter.handle(info.data, info.notificationTitle)
}
```
- Tier 2 — zero-config fallback: if no handler is registered, LogPose resolves the app's
  `FirebaseMessagingService` from the manifest and calls `onMessageReceived` reflectively
  (A4). Best effort; failure is reported in the ack, never thrown.

**MCP**
- New tool **`inject_fcm`**: args `data` (object, required unless `from_event_id`),
  `notification_title`, `notification_body`, `from`, `collapse_key`, `trace_id`,
  `from_event_id` (replay a captured push by id), `await` (bool, default false — when true,
  block until the delivery ack arrives, ≤ 10 s). Returns `{id, delivered, warning?}`.
- `inject_fcm` + `await_event` (feature B) is the agent story: mock the endpoints, inject
  the push, await the resulting HTTP call, assert.

### A2. Functional requirements

- **FR-A1** Injection delivers to the app process in-memory; no Play services, no network.
- **FR-A2** Every injected push emits a normal `kind:"fcm"` envelope with `injected = true`
  (and `event = "message"`), so capture, filters, and MCP all see it. `logFcmMessage` from
  the app's real service must never set it.
- **FR-A3** Replay reconstructs all `FcmMessageInfo` fields from the captured wire payload.
- **FR-A4** If `trace_id` is supplied (MCP) or generated (plugin, always), the delivery
  runs inside `LogPose.withTrace(traceId)`, so downstream library events correlate.
  *Caveat (documented, not fixed here):* ambient trace reaches HTTP rows only if the app
  already uses `LogPose.traceCalls` / `logPoseTrace()` request tags.
- **FR-A5** Debug-only by construction: receiver command ships in the real artifact only;
  the no-op mirrors `onPushInject` as an inert fun.
- **FR-A6** Documented caveat: injection simulates **foreground/data-message delivery**
  (`onMessageReceived`). It cannot reproduce the system-tray path of background
  notification messages. Data messages are the flow triggers, so this is acceptable.

### A3. Wire contract (lockstep: plugin `model/Transaction.kt` + library `wire/Wire.kt`)

- `FcmMessage` gains `injected: Boolean = false` (default keeps old payloads compatible).
- New control kind **`push_ack`**: `{kind:"push_ack", pkg, id, delivered, error?}` where
  `delivered ∈ handler | service | none`. Emitted bare (not enveloped) like `Hello`/`MockAck`;
  parser routes it via `ControlMessage` to `onControl` (extend
  `logcat/TransactionParser.kt` kind discrimination and `mock/MocksController.kt` — or a
  sibling `PushController` if cleaner; keep it off the EDT).
- **Command transport** reuses `MockCommandReceiver` broadcasts: add extra `--es cmd push`
  (absent/`rules` = today's behavior, fully backward compatible). Payload = base64 JSON of a
  new `PushInject` wire type `{id, traceId?, message: <FcmMessageInfo fields>}`, chunked with
  the existing `seq`/`total` scheme but reassembled in a **separate pending map keyed by cmd**
  so a push mid-rules-push can't corrupt either. `rev` is ignored for `cmd=push`.
- Handshake gating: plugin reads `Hello.libVersion`; if `< 1.7.0`, the Re-send/Compose
  actions show "device library ≥ 1.7.0 required" instead of silently doing nothing (same
  pattern the mock PRD §6 used). While here, **start actually reading `ruleCount`** from
  Hello/MockAck (added in v1.6.0, currently unread) to surface the reinstall-reset case.

### A4. Library implementation

- `LogPose.onPushInject(handler: (FcmMessageInfo) -> Unit)` — stored in an atomic ref on the
  `LogPose` object; no-op mirrors the signature exactly (takes the lambda, does nothing).
- `MockCommandReceiver.onReceive` branches on `cmd`. For `push`:
  1. Reassemble + decode `PushInject`.
  2. Emit the `injected = true` FCM envelope first (capture must show it even if delivery fails).
  3. Deliver **off the main thread** (single shared executor): wrap in
     `withTrace(traceId)`; try the registered handler; else Tier 2 reflection:
     `PackageManager.queryIntentServices(Intent("com.google.firebase.MESSAGING_EVENT"), pkg)`
     → load class → no-arg instantiate → `attachBaseContext(appContext)` via reflection →
     build `RemoteMessage` from a `Bundle` (`google.message_id`, `from`, `collapse_key`,
     data entries as String extras) via its `Bundle` constructor (reflective; firebase stays
     **off the dependency list entirely** — not even `compileOnly`) → `onMessageReceived`.
     Any throwable → `delivered = none`, `error = t.toString()`.
  4. Emit `push_ack`. Use the receiver's config-tag fix below.
- **Fix while touching this file:** `MockAck` (and the new `push_ack`) are emitted with a
  hardcoded `LogPoseConfig()` — they ignore a custom `tag`. Route acks through the last
  config seen by `LogPoseInitProvider`/interceptor so custom-tag apps work.

### A5. Plugin implementation

- `mock/MocksController.kt` (or sibling) gains `injectPush(info, traceId, onAck)` doing the
  chunked `cmd=push` broadcast on the existing `logpose-mock-push` daemon thread; consume
  `push_ack` control messages; 10 s ack timeout → notification (never the detail pane —
  see the error-surfacing rule in E/cross-cutting).
- UI: context-menu action on FCM rows in `LogPosePanel`; `ComposePushDialog` under `ui/`;
  INJ pill in `TransactionListRenderer` FCM row + banner line in `FcmDetailView`.
- MCP: add `inject_fcm` to `mcp/McpTools.kt` behind the existing `Mocks`-style interface
  (extend it or add a `Push` fun-interface) so `McpTools` stays IntelliJ-free and testable
  with a fake.

### A6. Definition of done

- Unit: `PushInject`/`push_ack` wire round-trip on both halves; `McpTools.inject_fcm`
  against a fake (args, replay-by-id, missing-handler warning path); receiver chunk
  reassembly for `cmd=push` interleaved with `cmd=rules`.
- Manual: demo app (`scripts/emit-demo-events.sh` gains a push-handler sample) — compose,
  replay, handler tier, reflection tier, no-handler ack, custom-tag app.
- Docs: README section + `logpose-android/README.md` + change-notes; caveat FR-A6 stated.

---

## B. `await_event` MCP tool

### B1. Behavior

`await_event` blocks (bounded) until an event matching a predicate **arrives after the call
starts**, then returns it. Turns the agent loop into *trigger → await → assert*.

Args (all optional except none): `kind`, `method`, `status_class`, `contains`
(same haystack semantics as `list_events`), `trace_id`, `failed_only`,
`timeout_ms` (default 30 000, clamp 1 000–120 000).
Returns on match: `{matched: true, waited_ms, event: <same summary shape as list_events>, id}`.
On timeout: `{matched: false, waited_ms, note}` — a *result*, not an error, with a note
suggesting `list_events` in case the event landed before the call. If capture isn't running,
return immediately with the existing `capture_stopped` note.

### B2. Implementation constraints (the one sharp edge)

`LogPoseMcpHandler.process` runs on a **Netty IO thread — it must not block**. Design:
- `EventStore` gains `addWaiter(predicate, timeoutMs): CompletableFuture<LogEvent>` —
  waiters checked inside `add` *after* the existing listener dispatch, completed off the
  store lock (hand the event to an executor; never complete a future while holding the
  monitor). Timeouts via a shared `ScheduledExecutorService`. Waiters removed on completion.
- `McpTools` exposes the tool as returning a future/callback; `LogPoseMcpHandler` keeps the
  Netty `ctx` and writes the JSON-RPC response from the completion thread
  (`ctx.writeAndFlush` is thread-safe). Everything stays off the EDT.
- Cap concurrent waiters per session (8); reject beyond with a clear error.
- `McpToolsTest` covers: match-after-call, timeout, predicate combinations, waiter cap,
  capture-stopped short-circuit. A small fake clock/executor keeps tests deterministic.

### B3. Definition of done

Tool listed in the catalogue (order test updated), README MCP section updated, and an
end-to-end happy path in `McpToolsTest`: create waiter → `store.add` matching event →
future completes with the right summary.

---

## C. Scenario suites (session → mock set)

### C1. User experience

- **Snapshot:** Mocks strip gains a **Scenarios ▾** menu: *Save current rules as…*,
  *Snapshot session into scenario…*, then the saved list with *Load* / *Load (replace)* /
  *Delete*. Snapshot walks the capture: group HTTP rows by `method` + normalized path
  (numeric segments → `*`, same normalization idea as `MutedEndpoints`), take the **latest**
  response per endpoint, skip rows that were themselves mocked, build replace-mode rules
  (status, body, content-type). A checkbox filters to 2xx-only (default off — error states
  are often the point).
- **Load** merges (or replaces) the active rule set and pushes to device — instant offline
  demo mode for a whole app.
- **Storage: committable files**, not `PropertiesComponent`:
  `<project>/.logpose/scenarios/<name>.json` = `{name, createdAt, note?, rules: [MockRule]}`
  (wire `MockRule` shape, so plugin and library versions stay honest). Sharing a repro with
  a teammate = committing a file. Add `.logpose/` mention to README; never auto-gitignore.
- **MCP:** `list_scenarios` (names + rule counts), `load_scenario(name, replace: bool)`,
  `save_scenario(name, from: "rules" | "session")`. All through the injectable interface;
  file I/O stays in the plugin layer (`MocksController` or a small `ScenarioStore` beside it,
  reading/writing via `java.io` — still IntelliJ-light, keep it unit-testable with a temp dir).

### C2. Functional requirements

- **FR-C1** Loading a scenario is one action and ends with rules pushed + ack-verified
  (surface the pending/failed state — this rides on the sync-truth work in cross-cutting).
- **FR-C2** Snapshot never invents data: bodies come verbatim from capture; endpoints with
  no completed response are skipped and reported ("skipped 3 in-flight/bodyless endpoints").
- **FR-C3** Scenario files round-trip: load(save(x)) == x; unknown JSON keys ignored
  (forward compat).
- **FR-C4** Name validation: `[a-z0-9-_]`, ≤ 64 chars; path traversal impossible by
  construction.

### C3. Definition of done

`ScenarioStore` unit tests (round-trip, snapshot grouping/normalization/latest-wins,
name validation); MCP tools tested with fakes; README "Scenarios" section with the
offline-demo pitch; change-notes.

---

## D. Richer mock matching + sequential rules

### D1. Matching extensions

`MockRule` (wire, both halves, all optional/defaulted for compat) gains:
- `matchQuery: Map<String, String>` — every entry must match the request's query
  (`*` value = key present with any value). Empty map = no constraint.
- `matchHeaders: Map<String, String>` — case-insensitive header name, exact value or `*`.
- `matchBodyContains: String?` — case-insensitive substring of the request body text.

`MockRegistry.match` gains the request's query pairs, headers, and (lazily read) body text —
signature grows; `LogPoseInterceptor` supplies them. **Body peek must not consume a one-shot
body:** only read when some active rule has `matchBodyContains`, and reuse the existing
`BodyCapture` buffered copy (never `body.writeTo` twice on a streaming body — skip body
matching for streaming/duplex bodies, matching fails closed).

### D2. Sequential rules

- `MockRule` gains `responses: List<MockStep>` where
  `MockStep = {status, body?, headers?, contentType?, latencyMillis?, behavior?}`.
- When non-empty, hit *N* (0-based, from the registry's existing per-rule hit count) serves
  step `min(N, responses.lastIndex)` — last step sticks. The rule-level single fields are
  ignored when `responses` is present; `serveLimit`/`mode` still apply at rule level
  (patch-mode + steps: each step's body is the patch).
- Canonical use: `[{status:500}, {status:200, body:…}]` — retry-logic testing in one rule.
- Known limitation, accept as-is: `match`→`recordServe` isn't atomic, so step selection is
  best-effort under concurrent identical calls (document in KDoc; don't add locking around
  the network call).

### D3. Plugin surface

- `MockRuleDialog`: collapsible **"Only when…"** section (query/header/body-contains rows)
  and a **"Then respond…"** step list (add/remove/reorder steps; step 1 pre-filled from the
  capture). Hidden-not-disabled, matching the dialog's existing style.
- `MocksBar` row: a small `×N steps` chip and a match-constraint hint in the tooltip.
- `list_mocks`/`create_mock` MCP: new args `match_query` (object), `match_headers` (object),
  `match_body_contains`, `responses` (array of step objects). Validate enums/statuses like
  the existing `mode`/`behavior` checks; unknown keys rejected loudly, not silently coerced.
- Skew gating: rules using any new field push only when `Hello.libVersion ≥ 1.7.0`;
  otherwise the rule row shows "needs device lib ≥ 1.7.0" and is excluded from the push
  (excluding beats sending fields the old lib ignores — an old device *silently matching
  too broadly* is exactly the trust failure LogPose exists to avoid).

### D4. Definition of done

`MockRegistryTest`: query/header/body matchers (hit + miss + `*` + case), step progression,
last-step-sticks, patch+steps, streaming-body fail-closed. `MockServeTest`: end-to-end
sequential serve. Plugin: `McpToolsTest` arg validation; dialog manual pass. Wire lockstep
verified by the round-trip tests on both halves. README mock section updated.

---

## E. Trace waterfall view

### E1. User experience

- Entry points: the structured-row context menu's *Filter by trace* gains a sibling
  **"Show waterfall"**; trace chips in `GenericDetailView`/`OverviewPanel` become clickable.
  (While here: give FCM rows the *Filter by trace* action they're currently missing, and
  pass the envelope to `FcmDetailView` so at/trace/parent chips render like the generic view.)
- The waterfall is a fourth detail card (`CardLayout` already routes by type): one row per
  event in the trace, laid on a shared time axis — FCM/analytics/db as points, HTTP/worker
  spans as bars, open spans (in-flight) drawn to "now" with the existing spinner treatment.
  Kind glyphs from `TypeIcons`, colors from `Theme` tokens only. Click a bar → select that
  row in the list (existing selection plumbing). A header line shows trace id, event count,
  wall span, and the slowest event.

### E2. Implementation

- Pure Swing custom-painted panel (`ui/TraceWaterfallPanel.kt`); data = an immutable
  snapshot of `store` events filtered by traceId taken on the EDT *once* per refresh tick —
  no store locks held during paint (this is the same discipline the renderer currently
  violates via `elapsedProvider`; don't add a second offender).
- No wire change, no library change, no MCP change (agents already have `get_trace`).
- Degrade gracefully: single-event traces render fine; events with `at == endedAt` are
  points; missing traceId → the entry points simply don't appear.

### E3. Definition of done

Layout math (time→x mapping, lane assignment, open-span handling) extracted into a pure,
unit-tested class (`analysis/` or `ui/` mirror of the `KindPresenter` pattern); manual pass
across light/dark themes; screenshot in README's trace section.

---

## Cross-cutting requirements

1. **Wire lockstep + no-op parity.** Every wire change lands in `model/Transaction.kt` and
   `wire/Wire.kt` in the same commit. Every new public library symbol lands in `no-op/` in
   the same commit. **M1 includes an API-parity test** (reflection-compare public surface of
   real vs no-op, run in the library build) — the drift already shipped once (the no-op is
   missing the two-arg `LogPoseInterceptor(config, emitter)` constructor; fix that as part
   of adding the test).
2. **Sync truth (prerequisite for C, feeds A).** Compare `syncedRevision` vs `revision` and
   show pending/failed; read `am broadcast` exit codes; ack timeout → notification; read
   `ruleCount`. Small, but scenarios ("load and it's live") are dishonest without it.
3. **Errors never hijack the detail pane.** New failure surfaces (push ack timeout, scenario
   load, broadcast failure) use IntelliJ notifications — do not extend the existing
   `showError` card-switch pattern.
4. **EDT/Netty discipline** as in CLAUDE.md: adb + file I/O off the EDT; nothing Swing on
   the Netty thread; `await_event` must not block the event loop (B2).
5. **Versioning.** Library tag `v1.7.0` (lightweight tag; never delete/re-push),
   `LogPoseRuntime.VERSION = "1.7.0"` bumped in the same commit (it's hand-maintained),
   plugin `1.8.0` with `CHANGELOG.md` + `plugin.xml` change-notes. `Envelope.v` stays 1 —
   all additions are default-valued.
6. **Docs.** Root README, `logpose-android/README.md` (also fix its stale `v1.5.1` install
   snippet), and the MCP tool table wherever it lives.

## Phasing (dependency-ordered)

- **M1 — Library v1.7.0** (~1 wk): `cmd` dispatch + `PushInject`/`push_ack` + `onPushInject`
  + reflection delivery + `injected` flag; `MockRule` matchers + steps + registry/interceptor;
  ack-tag fix; no-op mirror + parity test; all library DoD tests. One release tag.
- **M2 — Plugin wire + sync truth** (~3 d): lockstep model changes, `ruleCount`/revision
  reconciliation, notification-based error surfaces, `libVersion` gating.
- **M3 — Plugin features** (~1.5 wk): push replay/compose UI + INJ pill; mock dialog
  matchers/steps; scenarios (store, strip menu, snapshot); FCM detail/context-menu fixes.
- **M4 — MCP** (~4 d): `inject_fcm`, `await_event` (+ handler async completion),
  `list/load/save_scenario`, extended `create_mock`; catalogue/order tests.
- **M5 — Waterfall + polish** (~4 d): waterfall card, docs, demo script update, screenshots.

Plugin 1.8.0 ships after M5; M1's library tag ships first so M2+ can integration-test
against a real device.

## Success metrics

- A coding agent completes *mock endpoints → `inject_fcm` → `await_event` → assert* against
  the demo app with zero human clicks (this becomes a scripted check in `scripts/`).
- ≥ 1 real gandalf flow (order-assignment push) reproduced end-to-end offline via a
  committed scenario file within a month of release.
- Zero "injected push looked like a real one" confusion reports (the INJ pill did its job).

## Risks

| Risk | Mitigation |
|------|------------|
| Tier-2 reflection breaks on a firebase-messaging update | Tier 1 handler is the contract; ack reports `none` + guidance; reflection failure is silent-to-app, loud-to-IDE |
| `await_event` waiter leaks / Netty stall | Bounded waiters, scheduled timeouts, completion off-lock; covered by unit tests |
| Old device lib + new matcher rules match too broadly | Version-gated push exclusion (D3), not device-side leniency |
| Scenario bodies commit secrets to git | Snapshot runs bodies through nothing new — but README warns: scenarios contain captured bodies; review before committing |
| Step selection race under concurrency | Documented best-effort (D2); acceptable for a debug tool |

## Open questions (decide during execution, defaults given)

1. Push compose dialog: offer a saved-drafts list? **Default: no — scenarios cover reuse.**
2. `save_scenario(from: "session")` over MCP: same skip-mocked rule as the UI? **Default: yes.**
3. Waterfall as detail card vs popup? **Default: card** (keeps one navigation model).

---

## Handover notes for the executing agent

- Read `CLAUDE.md`, then `docs/mock-replay-prd.md` (the pattern this PRD extends), then this
  file. The two halves build separately; library work happens in `logpose-android/` with its
  own wrapper.
- Work milestone-by-milestone in the order above; each milestone ends green
  (`./gradlew test` at the right root) before the next starts. Commit per coherent change,
  authored as Sid, **no Co-Authored-By trailers**, and don't push tags — the human tags
  releases (JitPack builds from tags; a tag is immutable once pushed).
- Wire changes: edit `src/main/kotlin/.../model/Transaction.kt` and
  `logpose-android/src/main/kotlin/.../wire/Wire.kt` together, defaults on every new field.
- Anything touching `LogPose`/`LogPoseConfig`/`LogPoseInterceptor` public surface: mirror in
  `no-op/` in the same commit; the M1 parity test enforces this from then on.
- `McpTools` stays free of IntelliJ and HTTP types — new capabilities enter through
  fun-interfaces injected by `LogPosePanel`, tested with fakes in `McpToolsTest`.
- UI colors only via `Theme` tokens; all adb/process/file I/O off the EDT; MCP handler work
  off the Netty event loop.
