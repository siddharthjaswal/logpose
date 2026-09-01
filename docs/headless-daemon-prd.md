# PRD — LogPose Headless Daemon (`logpose serve`)

**Status:** Draft v1 · **Owner:** Sid · **Targets:** a new `:core` module + a new `:daemon` binary · plugin unchanged in behavior
**One-liner:** Run LogPose's capture and all 21 MCP tools with no IDE — a single JVM binary a
coding agent, a QA laptop, or a CI box can point at a device.

Companion to the standalone-client discussion: the daemon is the foundation. A future Mac GUI
(Compose Desktop) becomes a *client of this daemon*, not a second implementation — and whether
that GUI is worth building is a question the daemon will answer by existing.

---

## 1. Why

1. **MCP requires the IDE today.** `McpSessions` registers a session only while the tool window
   is open; close it and every agent gets a 401 whose hint says "open the LogPose tool window."
   An agent-driven workflow should not depend on a 2 GB IDE being open to the right project.
2. **CI and QA have no entry point.** The library's headless export (v1.6.0) gets wire-level
   verdicts out of a device, but mocks, push injection, `await_event` and correlation — the whole
   *drive the app* surface — exist only behind the IDE.
3. **The code is already there.** 7,975 of 19,077 plugin lines (41.8%) have zero IntelliJ/Swing
   imports, verified transitively: the parser, store, all 21 MCP tools, adb, mock sync,
   push injection, correlation, and every presentation-model class. This PRD is mostly a
   packaging exercise plus one transport rewrite.

## 2. Goals

1. `logpose serve` captures from a device and serves the full MCP surface over HTTP, with parity:
   an agent cannot tell whether it is talking to the plugin or the daemon except by the port.
2. One shared `:core` module — the plugin consumes it unchanged in behavior, so there is exactly
   one implementation of the wire model, parser, store, tools and analysis forever.
3. MCP-over-stdio as a second transport (`logpose serve --stdio`), so `claude mcp add` needs no
   port or token at all.
4. Safe coexistence with a running IDE plugin, with the sharp edges specified, not discovered.

### Non-goals (v1)

- Any GUI. No TUI either — `logpose tail` pretty-printing is a v2 candidate, not this.
- Windows/Linux polish beyond "plain JVM + adb on PATH works" (it should, incidentally).
- Replacing the scripts (`export-capture.sh` etc.) — they keep working; the daemon supersedes
  them naturally, later.
- Multi-device capture in one daemon process (run two daemons).

## 3. Module structure — one build, three modules

**Decision: `:core` and `:daemon` live inside the existing repo-root Gradle build**, not a third
build root. Evidence-based reasons:

- IntelliJ Platform Gradle Plugin 2.2.1 supports plain-JVM submodules first-class via the
  `intellijPlatformPluginModule` configuration — `:core`'s classes merge into the plugin's
  composed jar. Do **not** apply the platform plugin to `:core`; that is what would make it painful.
- A third build root would create a second hand-maintained sync contract for `model/Transaction.kt`
  (we already carry one against the library's `Wire.kt` and it has bitten before).
- `logpose-android` is a separate build for a real reason (AGP + JitPack + google()); `:core` has
  none — same Kotlin 2.1.0, same JVM 17 toolchain, same Gradle 8.14.4.
- One `./gradlew test` keeps covering everything, including the ~4,900 lines of pure tests
  (`McpToolsTest` alone is 1,803 lines of fakes that double as the daemon's implementation spec).

Concrete build changes: `settings.gradle.kts` gains `include(":core", ":daemon")` plus proper
`dependencyResolutionManagement`; `:core` applies `kotlin("jvm")` + `plugin.serialization`;
`:daemon` applies `application` (fat jar via a `Main-Class` jar — the runtime classpath is
kotlinx-serialization-json + stdlib, so no shadow plugin needed); the root plugin adds
`intellijPlatformPluginModule(implementation(project(":core")))`.

## 4. The extraction

### 4.1 Moves verbatim (zero edits beyond package lines)

`mcp/McpTools.kt` · `model/{Transaction,LogEvent}.kt` · `store/EventStore.kt` ·
`logcat/{LogcatReader,Adb,TransactionParser}.kt` · all nine `analysis/` files ·
`mock/{SyncState,PushController,ScenarioSnapshot,DeviceBroadcast,ScenarioStore,DeviceCapability,PushReplay}.kt` ·
and the seven pure presentation-model files currently misfiled under `ui/`:
`KindPresenter, RowContent, FilterPresentation, MockRuleForm, WaterfallLayout,
WaterfallPresentation, CurlBuilder` → these move to `core`'s `presentation/` package (the rename
touches only plugin-side imports). Their tests move with them unchanged.

### 4.2 One shim: `KeyValueStore`

`McpSessions`, `MocksController`, `CorrelationSettings` and `MutedEndpoints` touch IntelliJ only
through `PropertiesComponent` (and a `Project` used solely to obtain it). Define in core:

```kotlin
interface KeyValueStore { fun get(key: String): String?; fun set(key: String, value: String?)
                          fun getInt(key: String, default: Int): Int }
```

Plugin impl wraps `PropertiesComponent`; daemon impl is a properties file under
`~/.logpose/` (global) or `.logpose/daemon.json` beside the existing `scenarios/` dir
(per-project). `MocksController(project)` becomes `MocksController(store: KeyValueStore)`.
`LogPoseNotifications` needs nothing — the controllers already emit problems through
`(String, String) -> Unit` lambdas; the daemon logs them to stderr.

### 4.3 Re-authored in the daemon (~250 lines of glue)

The four `McpTools` fun-interface implementations currently living in `LogPosePanel`
(`McpMocks`, `McpPush`, `McpCorrelations`, `McpScenarios`, ≈190 lines) — their only platform
coupling is `executeOnPooledThread`, which becomes an `ExecutorService`. Plus session
registration and the capture lifecycle (start reader → parser → store, the reattach loop).

## 5. Transport

### 5.1 Prerequisite: extract `McpRpc` from the Netty handler

The JSON-RPC envelope (initialize payload, result/error builders, the `content:[{type:"text"}]`
wrapper, the sync/async dispatch split, the 401 hint) is ~140 of `LogPoseMcpHandler`'s 296 lines
and is transport-free in nature. Extract it into core as
`McpRpc.dispatch(request: JsonObject, session, respond: (JsonObject) -> Unit)`. The plugin's
Netty handler and both daemon transports become thin adapters over it. **The plugin's behavior
must not change** — same responses byte-for-byte, existing `McpToolsTest` untouched.

### 5.2 HTTP: JDK `com.sun.net.httpserver`, no framework

Verified sufficient. The protocol is plain request/response HTTP/1.1: POST-only on one path,
token header gating only `tools/call`, HTTP 202 empty-body for `notifications/*`, hand-written
`Content-Length` and keep-alive. The one subtlety is the seven **deferred** tools (`await_event`
can hold ~2 minutes): the JDK server closes the exchange when the handler returns, so the daemon
uses the opposite idiom from Netty — **block the handler thread** on the tool's
`CompletableFuture` with timeout, then write. Safe because `EventStore.MAX_WAITERS` already caps
concurrent waiters and the server gets a generous bounded executor. Net dependency cost: zero.

- Default bind: `127.0.0.1:63343` (one above the IDE's 63342), `--port` to override.
  Localhost-only, always — matching the plugin's existing security posture.
- The 401 hint text is reworded for the daemon (no tool window to open).

### 5.3 stdio: `--stdio`

After the `McpRpc` extraction this is ~50 lines: newline-delimited JSON on stdin, responses on
stdout under a single write lock (out-of-order completion is legal — responses carry `id`).
Two deltas: auth is skipped entirely (the parent process is the client), and `notifications/*`
produce **no output** rather than a 202.

## 6. Session, token, config

One process = one capture = one session; the token reverts to pure auth (in the plugin it also
selects the project). `McpSessions.Session` already takes lambdas, not a `Project` — reused as-is.

- Token priority: `--token` → `LOGPOSE_TOKEN` env → config file → auto-generate and **print the
  ready-made `claude mcp add …` line on startup** (the daemon's ConnectAgentAction).
- `--no-bodies` maps to the existing `exposeBodies` (`payload_withheld` over MCP).
- Correlation keys: read from `.logpose/correlation.json` (same `CorrelationKeys.parse/serialize`
  the dialog uses) — the vocabulary is shared with the plugin when run in the same project dir.
- `--project-dir` (default cwd) anchors `.logpose/` — scenarios made in the IDE load in the
  daemon and vice versa, because `ScenarioStore` is already plain `java.io.File`.

## 7. Coexistence with the IDE plugin — the sharp edges, specified

| Surface | Problem found | Rule |
|---|---|---|
| logcat reads | Concurrent tails are safe (adbd streams per client) | none needed |
| **logcat clear** | `adb logcat -c` runs at every capture start and is global: the second process wipes the first's backlog and truncates mid-reassembly chunks | daemon defaults to **--no-clear**; `--clear` opt-in. Documented: one clearer per device |
| **Mock rules** | Single wholesale rule set on device, keyed by a per-writer revision counter → two writers collide, `SyncState` fights (bounded: 2 retries then FAILED), every app restart re-triggers via Hello, and `onCaptureStopped` broadcasts an **empty set** — a daemon shutdown would silently delete the IDE's live mocks | **single-writer, enforced**: daemon starts in `--read-only-mocks` by default; `create_mock`/`load_scenario`/`inject_fcm` return the existing "not available" error shape unless `--mocks` is passed, and the flag's help text names the conflict |
| push acks / mock acks | Both processes ingest both streams; foreign push acks find no pending entry (benign); foreign mock acks feed the wrong `SyncState` | covered by single-writer above |
| MCP port | Plugin rides the IDE built-in server (63342) | daemon defaults 63343, prints resolved port |

## 8. What the daemon honestly does not have

UI-only, absent by design: the timeline, filter bar, waterfall *rendering* (the layout math is in
core — a JSON waterfall over MCP is a v2 candidate), the mock dialog and field-tree editor
(`create_mock` over MCP already does everything the dialog does), diff view, mute/unmute (not
even exposed over MCP today), notifications-as-balloons. Nothing else: scenarios, mock
management, correlation, injection and all 21 tools work headless.

## 9. CLI surface (v1)

```
logpose serve [--port 63343 | --stdio] [--project-dir DIR] [--device SERIAL]
              [--token T | env LOGPOSE_TOKEN] [--no-bodies] [--mocks] [--clear] [--name N]
logpose version
```

`--device` flows to `LogcatReader`/broadcasts exactly as the plugin's new device picker does.
Everything else (tail/export/assert subcommands) is deliberately deferred.

## 10. Phasing

- **M1 — `:core` extraction.** Modules created, verbatim set moved, `KeyValueStore` shim,
  presentation package rename, plugin depends via `intellijPlatformPluginModule`. Gate: plugin
  builds, all existing tests green in their new homes, `verifyPlugin` clean, zip byte-equivalent
  in behavior.
- **M2 — `McpRpc` extraction.** Netty handler becomes an adapter. Gate: `McpToolsTest` untouched
  and green; a new `McpRpcTest` covers the envelope; manual MCP smoke against the IDE.
- **M3 — daemon HTTP.** `:daemon` binary, capture lifecycle, glue impls, token/config, ports,
  coexistence flags. Gate: `scripts/agent-flow-check.sh` passes against the daemon with no IDE
  running.
- **M4 — stdio + packaging + docs.** `--stdio`, fat jar via `application`, README section,
  `claude mcp add` recipes for both transports.
- **M5 — dogfood.** gandalf capture via daemon only; a CI-shaped run (start daemon, run the
  mocked Maestro tier, export verdicts) to prove the original 1.6.0 promise end-to-end.

## 11. Risks

| Risk | Mitigation |
|---|---|
| Package rename churn breaks the plugin subtly | M1 gate is behavioral equivalence + full verifyPlugin; rename is imports-only |
| Blocked-handler-thread exhaustion under many `await_event`s | `MAX_WAITERS` already caps; bounded executor sized above it; document |
| Two-writer mock war in the field | read-only-mocks default; loud error naming the other writer |
| Marketplace zip accidentally grows/loses classes in the composed-jar switch | diff the zip's `lib/` contents before/after M1 |
| stdio client buffering quirks | newline-delimited, flush per message, no logging on stdout ever |

## 12. Open questions

1. Distribution: GitHub Releases fat jar first; Homebrew tap when someone asks? (Lean: yes.)
2. Should the daemon expose a read-only `/health` GET for CI liveness checks? (Lean: yes, trivial.)
3. Mac GUI timing: revisit only after M5 dogfood tells us who actually runs the daemon.

## Handover notes for the executing agent

Read `CLAUDE.md`, then this file, then the investigation evidence it is built on (the module
structure, extraction set, transport semantics and coexistence findings are all verified against
the code as of commit 91b14b3 — re-verify file:line claims before relying on them if the tree has
moved). Work milestone-by-milestone; each gate green before the next. The `McpToolsTest` fakes
are the spec for the daemon's glue implementations. Never let the plugin's MCP responses change
shape — parity is the product.
