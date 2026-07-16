# Changelog

All notable changes to LogPose are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); this project is pre-1.0 and the wire
format may still change.

## [Unreleased]

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
