# LogPose 🧭

> A lightweight Android Studio / IntelliJ plugin for reading your app's network traffic — without the logcat pain.

LogPose is named after the navigational device from *One Piece* that reads an island's
"log" to point you the right way. This one reads your **logcat** and points you straight
at the HTTP request you care about.

---

## Features

- **Modern "Studio" tool window** — a master/detail view with color-coded method/status
  pill badges, a hero **Overview** card (status, URL, duration/size/started/host/id stat
  chips), and side-by-side **Request** / **Response** cards.
- **Collapsible JSON trees** — request/response bodies are parsed back into navigable,
  syntax-colored trees; bodies that are JSON nest directly under `body`. Toggle **Tree /
  Raw** (raw is syntax-highlighted too).
- **Find in body** — `⌘F` / `Ctrl+F` inside either card highlights all matches with
  next/prev navigation and an `n/total` counter.
- **Chip filter** — type a term, press Space/Enter to pin it as a removable chip;
  Backspace on an empty field removes the last. Grammar: `/orders status:5xx method:POST
  -heartbeat`.
- **Mute noisy endpoints** — right-click → mute; muted calls stay visible but fade into
  the background (numeric path segments are normalized, so one mute covers all ids).
  Persists across restarts.
- **Copy everything** — Copy as **cURL** (hover a row or right-click), Copy as **JSON**
  (per-section or the whole transaction), Copy URL, Copy response body.
- **First-class multipart uploads** — S3/GCS media uploads show per-part metadata, never
  raw bytes.
- **Atomic, ordered capture** — no interleaved or mismatched bodies, even under load;
  oversized payloads are chunked and reassembled.

## Why?

The usual setup — OkHttp's `HttpLoggingInterceptor` at `BODY` level dumped into logcat —
breaks down fast:

- **Bodies get mismatched.** `HttpLoggingInterceptor` emits many separate `Log` lines per
  call. Concurrent requests on different threads interleave, so request/response bodies
  get switched.
- **Too much noise**, mixed in with every other app log.
- **No expand/collapse** — it's a flat text stream.
- **Pretty JSON eats the screen** — it's all-or-nothing.
- **Large bodies get truncated** (logcat caps entries at ~4 KB) and **multipart media
  uploads (S3 / GCS) are unreadable** binary dumps.
- **Hard to filter** to "just the `/orders` calls" or "only 5xx".

The root cause is that **logcat is the wrong layer**. LogPose fixes it by emitting **one
structured transaction per HTTP exchange** and rendering it in a real UI.

## How it works

```
┌─────────────────────────┐                  ┌──────────────────────────────┐
│  Android app             │                  │  Android Studio / IntelliJ   │
│                          │   one JSON line  │  LogPose tool window         │
│  LogPose interceptor     │   per exchange   │  • list of transactions      │
│  builds ONE Transaction  │ ───(logcat)────▶ │  • expand / collapse         │
│  (request + response)    │   tag: LogPose   │  • pretty JSON               │
│                          │                  │  • filter / search           │
└─────────────────────────┘                  └──────────────────────────────┘
```

- The on-device interceptor serializes the **whole** request+response exchange into a
  single JSON object and logs it as **one line** under the `LogPose` tag. Atomic emission
  is what eliminates interleaving and mismatched bodies.
- The plugin runs `adb logcat -v raw -s LogPose:V` (only our tag, raw payloads — no
  noise), parses each line, and renders a filterable master/detail view.
- Payloads bigger than a logcat line are split into ordered **chunks** and reassembled by
  the plugin.
- Multipart uploads ship **per-part metadata** (name, filename, content-type, size) — not
  raw bytes — so media uploads stay readable and cheap.

> The plugin talks to `adb` directly and does **not** depend on the bundled Android
> plugin, so it works in any JetBrains IDE.

## The wire format

The contract between the device and the plugin is a single JSON object per line:

```jsonc
{
  "id": "a1b2c3",                 // correlates request + response
  "startedAtMillis": 1733500000000,
  "durationMillis": 142,
  "request": {
    "method": "POST",
    "url": "https://api.example.com/v1/orders",
    "host": "api.example.com",
    "path": "/v1/orders",
    "headers": { "Content-Type": "application/json" },
    "body": { "contentType": "application/json", "sizeBytes": 57, "text": "{...}" }
  },
  "response": {
    "code": 200,
    "message": "OK",
    "headers": { "Content-Type": "application/json" },
    "body": { "contentType": "application/json", "sizeBytes": 1203, "text": "{...}", "truncated": false }
  }
}
```

Multipart upload body example (no raw bytes):

```jsonc
"body": {
  "contentType": "multipart/form-data",
  "parts": [
    { "name": "file", "filename": "receipt.jpg", "contentType": "image/jpeg", "sizeBytes": 824123 },
    { "name": "meta", "contentType": "application/json", "sizeBytes": 64 }
  ]
}
```

Chunk envelope (for oversized payloads):

```jsonc
{ "id": "a1b2c3", "seq": 0, "total": 3, "payload": "<json-fragment>" }
```

See [`Transaction.kt`](src/main/kotlin/io/github/siddharthjaswal/logpose/model/Transaction.kt)
for the canonical schema.

## Filtering

The filter box is a chip input (Space/Enter pins a term, Backspace removes the last). It
accepts space-separated terms (AND-ed):

| Term | Matches |
|---|---|
| `/orders` | URL contains `/orders` (case-insensitive) |
| `status:5xx` | response code class (also `2xx`, `3xx`, `4xx`) |
| `status:404` | exact response code |
| `method:POST` | HTTP method |
| `-heartbeat` | **excludes** URLs containing `heartbeat` |

## Install (development)

```bash
git clone https://github.com/siddharthjaswal/logpose.git
cd logpose
./gradlew runIde      # launches a sandbox IDE with the plugin loaded
```

Then open the **LogPose** tool window (bottom), hit **Start Capture**, and run your app.

To build a distributable zip:

```bash
./gradlew buildPlugin   # output in build/distributions/
```

## Repository layout

```
logpose/
├── src/…                 # the IntelliJ / Android Studio plugin (this build)
└── logpose-android/      # the drop-in OkHttp interceptor (separate Gradle build)
```

The two halves talk over the [wire format](#the-wire-format) above — the interceptor
emits it, the plugin reads it. See [`logpose-android/README.md`](logpose-android/README.md)
for the device-side setup.

## Roadmap

- [x] Plugin: tool window, logcat capture, master/detail, filtering, chunk reassembly
- [x] **`logpose-android`**: drop-in OkHttp interceptor (atomic transaction, multipart
      metadata, gzip, chunking) — the device side of the contract
- [x] Collapsible JSON **tree** viewer (+ syntax-colored Raw mode)
- [x] **Copy as cURL** (hover + context menu), section/transaction JSON copy
- [x] Endpoint **muting** and a **chip-based** filter
- [x] **Find** within request/response bodies
- [x] Modern "Studio" card UI
- [ ] Per-device picker when multiple devices are attached
- [ ] Optional socket transport (`adb reverse`) to bypass logcat entirely
- [ ] Persist/replay captured sessions
- [ ] Publish to JetBrains Marketplace + Maven Central / JitPack

## Contributing

Issues and PRs welcome. This is early — the wire format may still change before 1.0.

## License

[Apache 2.0](LICENSE)
