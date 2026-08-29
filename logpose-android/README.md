# logpose-android

The device side of [LogPose](../README.md): a drop-in OkHttp `Interceptor` that emits
one structured transaction per HTTP exchange for the LogPose IDE plugin to read.

## Why not `HttpLoggingInterceptor`?

`HttpLoggingInterceptor` writes many separate log lines per call, so concurrent
requests interleave and bodies get mismatched. `LogPoseInterceptor` builds the **entire**
request+response exchange in memory and emits it as **one atomic line** (chunked only if
it exceeds logcat's limit). It also:

- ships **multipart upload metadata** (part name, filename, content-type, size) instead of
  dumping raw bytes — readable S3/GCS media uploads;
- detects and summarizes **binary** bodies;
- transparently **gunzips** gzip-encoded responses;
- **redacts** sensitive headers (`Authorization`, `Cookie`, …);
- never disturbs the real request/response stream.

## Install

> Not yet published to Maven Central. For now, pull it from [JitPack](https://jitpack.io).

> **Update the IDE plugin too.** `v1.5.x` sends every event wrapped in an envelope, which
> plugin **1.5.0+** is the first version able to read — on an older plugin the timeline simply
> stays empty, with no error to tell you why. Plugin **1.6.0+** additionally renders db, worker
> and config as first-class rows, **1.7.0+** splits a capture that spans an app restart into
> separate sessions, **1.8.0+** is required for push injection, scenarios and the new mock
> matchers/steps, and **1.9.0+** sends an injection id this version can collapse an injected
> push's re-log onto (below). On a newer plugin an older library still works: legacy payloads are
> recognised and wrapped.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven("https://jitpack.io") }
}

// app/build.gradle.kts
dependencies {
    // Debug builds: the real interceptor.
    debugImplementation("com.github.siddharthjaswal.logpose:logpose-android:v1.7.1")
    // Release builds: a zero-overhead no-op with the same API (no logcat, no extra deps).
    releaseImplementation("com.github.siddharthjaswal.logpose:logpose-no-op:v1.7.1")
}
```

The no-op ([`no-op/`](no-op)) exposes the same `LogPoseInterceptor` / `LogPoseConfig` **and
the same `LogPose` FCM API**, so your `addInterceptor(...)` and `LogPose.logFcmMessage(...)`
calls compile unchanged — release builds link the stub and LogPose vanishes from production.
Prefer one artifact everywhere? Use only the `debugImplementation` line and rely on
`enabled = BuildConfig.DEBUG`.

## Usage

```kotlin
val client = OkHttpClient.Builder()
    // Add LAST so it sees the final request and the decoded response.
    .addInterceptor(
        LogPoseInterceptor(
            LogPoseConfig(
                enabled = BuildConfig.DEBUG,   // never runs in release
            )
        )
    )
    .build()
```

That's it. Run the app, open the **LogPose** tool window in Android Studio, and hit
**Start Capture**.

## FCM messages (optional)

To also see Firebase Cloud Messaging pushes and token refreshes in the same timeline, hand
them to LogPose from your `FirebaseMessagingService`. LogPose stays Firebase-free — you copy
the fields you need into a plain `FcmMessageInfo` (the no-op mirrors this API, so it compiles
and disappears in release):

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(m: RemoteMessage) {
        LogPose.logFcmMessage(
            FcmMessageInfo(
                messageId = m.messageId,
                from = m.from,
                collapseKey = m.collapseKey,
                sentTimeMillis = m.sentTime,
                ttlSeconds = m.ttl,
                priority = m.priority,
                notificationTitle = m.notification?.title,
                notificationBody = m.notification?.body,
                data = m.data,
            ),
            LogPoseConfig(enabled = BuildConfig.DEBUG),
        )
        super.onMessageReceived(m)
    }

    override fun onNewToken(token: String) {
        LogPose.logFcmToken(token, LogPoseConfig(enabled = BuildConfig.DEBUG))
    }
}
```

### Let the IDE send a push *in* (optional, plugin 1.8.0+)

The above is one direction: your app tells LogPose about a push it received. The other direction
lets the IDE **start a flow** — deliver a synthetic push into this process, with no Play services
and no network — so an order-assigned or payment-confirmed journey can be reproduced on demand
instead of waiting for a real one. Give LogPose the app's push entry point, once, at init:

```kotlin
// Application.onCreate(), debug builds. Compiles unchanged in release: the no-op ignores it.
LogPose.onPushInject { info ->
    // Route it exactly as your FirebaseMessagingService would.
    MyPushRouter.handle(info.data, info.notificationTitle)
}
```

`info` is the same plain `FcmMessageInfo` you fill in above, so a handler that shares a routing
function with `onMessageReceived` is a one-liner. Then, in the IDE: right-click any captured FCM
row → **Re-send this push**, or **Compose push…** for a new one.

- The handler runs **off the main thread**, inside the injection's trace, and may be called
  concurrently with real pushes — treat it exactly like `onMessageReceived`.
- **Without a handler**, LogPose resolves your `FirebaseMessagingService` from the manifest and
  calls `onMessageReceived` reflectively. Firebase stays off the dependency list entirely (not
  even `compileOnly`), so this is best-effort — and the device reports which tier actually took
  the push (`handler` / `service` / `none`) back to the IDE, so "nothing consumed it" is an
  answer rather than a silence.
- **What it can't do:** injection simulates **foreground data-message delivery** — the
  `onMessageReceived` path. It cannot reproduce the system-tray path of a *background
  notification* message, because that's the OS posting a notification, not your app receiving
  one. Data messages are what trigger flows, so this is the useful half.
- Injected rows carry `injected = true` on the wire and show an **INJ** pill in the timeline. You
  never set that flag yourself — it comes from the injection, so the capture can't confuse a real
  push with one the IDE sent.
- **One row, not two** (v1.7.1+, with plugin 1.9.0+). Your service receives an injected push like
  any other and normally re-logs it through `logFcmMessage`, which used to add a second,
  *unmarked* row 20-odd ms after the first. LogPose now emits the injected row under the **message
  id** as its envelope id and remembers the last 32 injected message ids, so your re-log lands on
  the same row and keeps the `injected` flag. Nothing to change in your code — keep calling
  `logFcmMessage` from `onMessageReceived` exactly as above.
  - Because the re-log arrives second, it is what the surviving row reflects — including **your**
    ambient trace (or none) in place of the injection's. That's the honest reading of what
    happened, and it's why the IDE's `order_id`-style correlation keys, not traces, are the
    reliable way to group an injected flow.

Injection arrives over the same DUMP-gated adb receiver mocks use — it ships **only in the real
artifact**, and the release no-op mirrors `onPushInject` as an inert function.

## Database, workers and config (optional)

Three kinds the IDE understands structurally — you send facts, it decides how they read.

```kotlin
// Room: one line, every query.
Room.databaseBuilder(app, AppDb::class.java, "app-db")
    .apply {
        if (BuildConfig.DEBUG) setQueryCallback({ sql, args ->
            LogPose.logDbQuery(DbQueryInfo(sql = sql, args = args.map { it.toString() },
                                           database = "app-db"))
        }, Executors.newSingleThreadExecutor())
    }
    .build()

// WorkManager: one observer, every worker — no Worker class is touched.
WorkManager.getInstance(context)
    .getWorkInfosLiveData(WorkQuery.fromStates(WorkInfo.State.values().toList()))
    .observeForever { infos ->
        infos.forEach {
            LogPose.logWorker(WorkerEventInfo(
                worker = it.tags.firstOrNull { t -> t.contains('.') }?.substringAfterLast('.') ?: "Worker",
                state = it.state.name.lowercase(),
                workId = it.id.toString(),
                runAttempt = it.runAttemptCount,
            ))
        }
    }

// Remote config: hand over the snapshot, LogPose reports the diff.
firebaseRemoteConfig.fetchAndActivate().addOnCompleteListener {
    LogPose.logConfigSnapshot(firebaseRemoteConfig.all.mapValues { v -> v.value.asString() },
                              source = "remote")
}
```

Notes worth knowing:

- Operation and table are parsed from the SQL **by the plugin**, so you don't pass them (set
  `operation` / `table` yourself only for non-SQL stores).
- **Room's callback carries no duration** — it fires before the query executes. LogPose therefore
  reports *repetition* rather than pretending to time anything: `query_hotspots` ranks the
  statements that ran most often, which is what catches an N+1. Pass `durationMillis` yourself
  if you measure execution and it will be reported alongside.
- **DB rows are hidden in the timeline by default** (one click on the **DB** chip shows them) —
  a busy screen can outproduce every other kind combined. They're always captured and always
  visible to a coding agent over MCP.
- A worker event is keyed by `workId`, so a request's states collapse into one updating row.
  Durations derive from `WorkInfo` state changes and therefore include queue time.
- The first config snapshot in a process is a baseline; only later activations report changes.
- `dbEnabled` / `workersEnabled` on `LogPoseConfig` switch these off — a query callback on a
  busy screen can emit hundreds of events a minute.

Requires plugin 1.6.0+ to render as first-class rows (older plugins show them as generic rows).

## Analytics events (optional)

See every analytics event on the timeline, next to the API call and screen that triggered it —
the fast answer to "did `purchase_complete` fire with the right params?". One line in your
analytics facade (the one `Analytics.log(name, params)` chokepoint most apps already have):

```kotlin
LogPose.logAnalytics(
    AnalyticsEventInfo(
        name = "purchase_complete",
        params = mapOf("value" to "499", "currency" to "INR"),
        screen = "cart",           // shown as the subtitle; also the flow-graph node key
        provider = "firebase",     // optional, to tell sinks apart
        traceId = trace,           // optional, to correlate with the API/DB around it
    ),
    LogPoseConfig(enabled = BuildConfig.DEBUG),
)
```

- **`analyticsEnabled`** switches it off (analytics can be chatty — screen views, impressions).
- **Param masking is off by default** — analytics params are usually staging/test data and reading
  them is the point. If a build carries real PII, opt in:
  `redactAnalyticsParams = LogPoseConfig.DEFAULT_REDACT_PARAMS` masks `email`/`phone`/`token`/… by
  key substring.
- A connected coding agent can query these over MCP (`analytics_events`) to verify contracts and
  read the observed **screen-to-screen flow**.

Renders in any plugin; a dedicated `ANLY` filter chip needs plugin 1.7.4+.

## Your own events (optional)

HTTP and FCM are just two *kinds* on the timeline. Any subsystem can put a row there, and the
plugin needs no knowledge of it — the event carries its own presentation:

```kotlin
LogPose.event("UserDao.insert", LogPoseConfig(enabled = BuildConfig.DEBUG)) {
    subtitle = "users (3 rows)"
    badge("DB", Tone.INFO)          // tones are semantic: INFO / WARN / ERROR / MUTED
    took(14)                        // renders as a span; omit for a point in time
    code("SQL", "INSERT INTO users (id, name) VALUES (?, ?)")
    kv("Params", mapOf("id" to "7"))
}
```

Sections are `text`, `code`, `json` or `kv`. Group related events into one flow with a trace:

```kotlin
val trace = LogPose.newTraceId()
LogPose.event("Push received") { traceId = trace }
LogPose.event("Feed refresh")  { traceId = trace }
```

`LogPose.log(kind = "acme.telemetry", payloadJson = "…")` is the raw escape hatch for a payload
something else already understands; unrecognised kinds still get a row in the plugin.

Every entry point takes only strings and maps — no serialization types — so the release
`logpose-no-op` artifact mirrors this API exactly and your call sites compile unchanged. The
builder lambda still *runs* in release (it's ordinary Kotlin), so keep expensive work out of it
or guard it with `BuildConfig.DEBUG`.

Requires plugin 1.5.0+ to render.

## Configuration

```kotlin
LogPoseConfig(
    tag = "LogPose",            // must match the plugin's tag
    enabled = BuildConfig.DEBUG,
    maxBodyBytes = 250_000,     // textual bodies larger than this are truncated
    maxLineChars = 3500,        // payloads larger than this are chunked
    mocksEnabled = true,        // let the IDE plugin serve mock responses (see below)
)
```

### Redaction

Credential-bearing headers are masked as `██` before anything leaves the device. Two lists do
it, and both default to something broad:

- **`redactHeaders`** — exact names: `Authorization`, `Proxy-Authorization`, `Cookie`,
  `Set-Cookie`, `API-Key`, `X-API-Key`, `X-Auth-Token`, `X-Access-Token`, `X-Amz-Security-Token`,
  `Private-Token`, … (see `LogPoseConfig.DEFAULT_REDACT_HEADERS`).
- **`redactHeaderPatterns`** — name substrings (`token`, `secret`, `password`, `credential`,
  `apikey`, `api-key`, `auth`) that catch the vendor headers no exact list can enumerate, like
  `X-Shopify-Access-Token`.

**Extend, don't replace** — passing your own set discards the defaults:

```kotlin
LogPoseConfig(redactHeaders = LogPoseConfig.DEFAULT_REDACT_HEADERS + "X-Tenant-Key")
```

Redaction covers **headers only**. A token or PII in a request/response *body* is captured as-is
— treat a capture as sensitive, and use `logpose.mcp.exposeBodies = false` to keep bodies away
from a coding agent while still exposing shape, status and timing.

### Custom body decoding

If your backend sends **encrypted or custom-binary bodies**, LogPose shows ciphertext by default.
Register a `BodyDecoder` to turn it into readable text for the inspector — the same idea as
Chucker's decoder hook:

```kotlin
class EncryptedJsonDecoder(
    private val decrypt: (ByteArray) -> ByteArray,
) : BodyDecoder {
    override fun decodeResponse(response: Response, body: ByteArray): String? {
        // Scope it: only touch the endpoints that are actually encrypted.
        if (!response.request.url.encodedPath.startsWith("/secure/")) return null
        return runCatching { decrypt(body).toString(Charsets.UTF_8) }.getOrNull()
    }
}

LogPoseInterceptor(
    LogPoseConfig(
        enabled = BuildConfig.DEBUG,
        bodyDecoders = listOf(EncryptedJsonDecoder(::decryptAes)),
    )
)
```

- Both `decodeRequest` / `decodeResponse` default to `null` — override only the side you need.
- Decoders are tried in order; the **first non-null** result wins. `null` means "not mine", so
  LogPose falls back to the next decoder and finally the raw body — a decoder can't break the
  calls it doesn't handle, and a decoder that throws is skipped rather than fatal.
- Decoded bodies are flagged `decoded` on the wire, so the inspector can mark them as transformed.
- `body` is the raw bytes as captured (already gunzipped for responses).

Same security note as above, doubly: a decoded body is **plaintext leaving the device**. Keep the
decoder and its keys on the debug path — the release `no-op` ships no decoder, so production is
untouched by construction.

## Mock & replay

The plugin can serve mock responses for matching requests — reproduce a bug state without
touching the backend or rebuilding the app. Once a coding agent is connected (see the root
README), you can just ask:

```text
"Using logpose, mock /v1/orders to return a 500 so I can see the error UI."
"Using logpose, make /v1/orders return an empty list to check the empty screen."
"Using logpose, take the real /v1/profile response but flip is_premium to true."
"Using logpose, add a 5s delay to /v1/feed, then remove it."
"Using logpose, make /v1/orders time out so I can test the retry flow."
```

Under the hood it edits status/body/headers, adds latency, or simulates timeouts. Rules travel
IDE → device via `adb shell am broadcast` to a receiver that ships in this artifact and is gated
on the `DUMP` permission — only the adb shell holds it, so no third-party app can push rules. The
interceptor short-circuits matching requests and emits them flagged `mocked = true`; the device
confirms sync and reports hit counts back over the normal logcat channel.

### Narrower matches, and a different response per hit (v1.7.0+)

A rule matches on method and path pattern, and since v1.7.0 it can narrow further — every
constraint present must hold:

| Field | Meaning |
|---|---|
| `matchQuery` | query pairs the request must carry; value `"*"` means "present, any value" |
| `matchHeaders` | headers it must carry (name case-insensitive); `"*"` again means any value |
| `matchBodyContains` | case-insensitive substring of the request body |

Body matching **fails closed**: the registry only peeks at a body when some active rule actually
asks for it, reuses the buffered copy the capture already made, and never touches a streaming or
duplex body — one it can't read simply doesn't match, so the call goes to the network instead of
being mocked on a guess.

`responses` turns one rule into a sequence — hit *N* serves step `min(N, last)`, so the last step
sticks:

```jsonc
{ "method": "GET", "pathPattern": "/v1/orders/*",
  "responses": [ { "status": 500 }, { "status": 200, "body": "{\"orders\":[]}" } ] }
```

That's a complete retry test in one rule. The rule-level `status`/`body`/`headers`/`contentType`/
`latencyMillis`/`behavior` are ignored while `responses` is present; `serveLimit` and `mode` still
apply (in patch mode each step's body is the patch). Step selection is **best-effort** under
concurrent identical calls: matching a rule and counting the serve aren't atomic, and locking
around the network call would be the wrong trade for a debug tool.

Rules using any of these are withheld by plugin 1.8.0+ from a device running an older library,
rather than pushed and silently ignored — a rule that matches *too broadly* on an old library is
exactly the quiet failure this tool exists to prevent.

This lives **only in the real `logpose-android` artifact** — the release `no-op` jar contains
no receiver, provider, or `MockRegistry`. Set `mocksEnabled = false` to opt this build out
entirely.

### Mocks in CI (no IDE)

The mock push is just an `adb` broadcast, so a pipeline can set up the mocked tier before a
Maestro (or any) flow runs — no IDE, no coding agent:

```bash
# push a rules file
scripts/push-mocks.sh <app-package> mocks/orders-500.json
# ...run your Maestro flow against the mocked responses...
scripts/push-mocks.sh --clear <app-package>
```

`mocks/orders-500.json` is a JSON array of `MockRule` objects (method, `pathPattern`, `status`,
`body`, `mode`, …). The same DUMP-gated channel the plugin uses, so it needs a debug/staging build
with `logpose-android` ≥ 1.1.0 running on the connected device.

Want a different transport (e.g. a socket via `adb reverse`)? Implement `EventEmitter` — one
method, taking the `Envelope` every timeline event travels in — and pass it to the interceptor:

```kotlin
LogPoseInterceptor(config, emitter = MySocketEmitter())
```

## License

[Apache 2.0](../LICENSE)
