# PRD — LogPose Mock & Replay

**Status:** Draft v1 · **Owner:** Sid · **Targets:** plugin 2.0 · `logpose-android` 1.1
**One-liner:** Turn LogPose from an observer into a test harness — right-click any captured
response and serve it (edited, delayed, or failed) back to the app on the next call.

---

## 1. Background

LogPose captures an app's HTTP + FCM traffic out of logcat with zero setup — no proxy, no
certificates. But it can only *watch*. The most common debugging dead-ends are about
*control*, not visibility:

- "I can't reproduce the bug — the backend won't return that state anymore." (An order
  that's already delivered, a promo that expired, a rider already suspended.)
- "I want to test the error UI — 500, empty list, malformed field — without asking backend
  or hacking code."
- "QA saw it on staging; by the time a dev looks, the data changed."

`LogPoseInterceptor` already sits inside the OkHttp chain. It is one `return` statement away
from *serving* a response instead of recording one. No proxy tool can match that setup cost.

## 2. Goals

1. **Replay:** captured response → editable mock rule → served on device, in ≤ 3 clicks.
2. **Edge-case injection:** per-endpoint status override (500/404/…), body edits, added
   latency, timeout, and connection-failure simulation.
3. **Trust:** mocked traffic is loudly visible (badges, toolbar indicator), debug-only by
   construction, and one click to disable globally.

### Non-goals (v1)

- Request mutation (only responses are mocked).
- Conditional / scripted mocks ("return X after 3 calls of Y").
- Mocking FCM events (candidate for v2 — fake-push injection).
- Release builds (no-op stays inert), non-OkHttp stacks, iOS.

## 3. Core use cases

| # | Scenario | Today | With mock/replay |
|---|----------|-------|------------------|
| UC1 | Reproduce a state-dependent bug (gandalf: order already accepted) | Impossible; data moved on | Replay the captured response forever |
| UC2 | Build/test error & empty states | Backend favor, or code hacks | Override status/body per endpoint |
| UC3 | Resilience: slow network, timeouts mid-flow | Charles + certs, flaky | Latency/timeout injection per rule |
| UC4 | Frontend before backend exists | Hardcoded fakes in code | Mock the unbuilt endpoint from its spec |

## 4. User experience

### Plugin

- **Create:** right-click a captured row → **"Mock this endpoint…"** → editor pre-filled
  with method, path, status, headers, body (reuses `JsonTreePanel` for editing), plus
  latency ms, behavior (normal / timeout / connection-fail), and serve policy
  (always / once / N times) → **Activate**.
- **Manage:** a **Mocks** panel (toggleable next to the filter bar): rule list with on/off
  switches, edit, delete, hit counts, and **Disable all**. Rules persist per project.
- **See:** rows served from a mock get a purple **MOCK** pill in the list and a banner in
  the detail view. While any rule is active, the toolbar shows a persistent indicator.
- **Fail gracefully:** if the on-device library predates mocks, show "device library
  ≥ 1.1 required" instead of silently doing nothing (see handshake, §6).

### Library

- Zero new *required* API. Optional: `LogPoseConfig(mocksEnabled = true)` gate.
- The no-op mirrors any new public surface, as always.

## 5. Functional requirements

- **FR1 — Matching:** rule = method + path. v1 creates exact-path rules from a capture;
  editable to a glob (`/app/v4/*/order/*`). First match wins, most-recently-created first.
- **FR2 — Response:** status code, headers, text body, latency ms, behavior
  `NORMAL | TIMEOUT | CONNECTION_FAILURE`.
- **FR3 — Serve policy:** `always | once | N times`; expired rules auto-deactivate but stay
  listed for reactivation.
- **FR4 — Honest timeline:** every mocked serve emits a normal `Transaction` with
  `mocked = true`, so the capture stream never lies about what the app experienced.
- **FR5 — Kill switches:** per-rule toggle, Disable all, and (fail-safe) device-side rules
  are cleared when capture stops or the plugin disconnects.
- **FR6 — Offline:** matched rules serve with no network at all (that's the point of UC3).

## 6. Technical design (summary)

**The new problem is transport.** Today's channel is device → IDE (logcat). Mocks need
IDE → device. Chosen approach:

- **Command path:** `adb shell am broadcast` to an **explicit, non-exported receiver**
  registered by the real library artifact (debug builds only). Payload = versioned
  `MockRuleSet` JSON; oversized payloads reuse the existing `Chunk` envelope across
  multiple broadcasts (or `adb push` a temp file and broadcast a pointer, if limits bite).
- **Ack path (no new transport!):** on applying revision N, the device emits
  `{"kind":"mock_ack","revision":N,"hits":{…}}` on the normal LogPose logcat tag. The
  plugin marks rules "synced" and surfaces per-rule hit counts. This closes the loop by
  reusing the existing one-way channel — the IDE always reads logcat anyway.
- **Handshake:** same mechanism doubles as capability detection; no ack after push + retry
  ⇒ old library ⇒ show the upgrade message (§4).
- **Serving:** `LogPoseInterceptor.intercept()` consults a thread-safe `MockRegistry`
  before `chain.proceed()`. On match: apply latency, then either build an
  `okhttp3.Response` from the rule (body as buffer, standard headers) or throw the
  appropriate `IOException` for timeout/failure behaviors — and emit the
  `mocked = true` transaction.
- **Wire contract additions** (plugin `model/` + library `wire/`, in lockstep as usual):
  `MockRule`, `MockRuleSet(revision, rules)`, `mocked: Boolean = false` on `Transaction`
  (default keeps old payloads compatible), and the `mock_ack` kind.

**Alternatives considered:** `adb reverse` socket (most robust, but adds a host server +
connection lifecycle; revisit in v2 if broadcast limits hurt), `content insert` (size
limits), pushed-file watching (storage permission mess).

**Safety:** receiver is not exported and ships only in the real artifact; no-op ships
nothing; mocks are impossible in release by construction (no-op + `enabled` flag). Rule
bodies never leave the dev machine + device.

## 7. Success metrics

- **Activation:** ≥ 30% of active plugin users create ≥ 1 mock rule within 30 days of release.
- **Dogfood:** weekly mock usage by the gandalf team; ≥ 3 real bugs reproduced via replay
  in the first month (ask in Slack, count anecdotes honestly).
- **Trust proxy:** zero reports of "mock left on silently broke my testing" (the badges/
  indicator did their job).

## 8. Risks & mitigations

| Risk | Mitigation |
|------|------------|
| Broadcast payload limits on big bodies | Chunked broadcasts; `adb push` file fallback |
| Stale mock left active, confusing tests | Toolbar indicator, MOCK badges, clear-on-stop (FR5) |
| Plugin/library version skew | Ack-based handshake + explicit upgrade message |
| Multi-device ambiguity | Rules push to the selected device only — **device picker ships first** (already on the roadmap) |
| OkHttp caching interplay | App-interceptor-level short-circuit precedes cache/network; document placement (already "add LAST") |

## 9. Phasing

- **M1 — Library (1–2 wk):** `MockRegistry`, interceptor short-circuit, receiver + chunked
  ruleset ingestion, acks, no-op mirror, unit tests.
- **M2 — Plugin (2 wk):** rule editor + Mocks panel, adb push/sync + handshake, MOCK
  badges/banner/indicator, per-project persistence.
- **M3 — Polish (1 wk):** latency/timeout/failure behaviors, serve counts, glob matching,
  clear-on-stop, docs + README/demo GIF.
- **v2 candidates:** scenario suites (save/load rule sets with sessions), conditional
  mocks, FCM injection (fake pushes via the same command channel), `adb reverse` socket
  transport, request assertions for CI.

## 10. Open questions

1. Clear device rules on Stop Capture (lean **yes**, fail-safe) — or persist across
   sessions for "long-lived local mocking" workflows?
2. Binary bodies: v1 is text-only — is that acceptable for gandalf flows? (Believed yes.)
3. Should serve-count hits surface as a live badge on the rule row (ack already carries
   them) — v1 or polish?
