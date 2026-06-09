# Changelog

All notable changes to LogPose are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); this project is pre-1.0 and the wire
format may still change.

## [Unreleased]

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
