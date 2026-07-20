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

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven("https://jitpack.io") }
}

// app/build.gradle.kts
dependencies {
    // Debug builds: the real interceptor.
    debugImplementation("com.github.siddharthjaswal.logpose:logpose-android:v1.3.0")
    // Release builds: a zero-overhead no-op with the same API (no logcat, no extra deps).
    releaseImplementation("com.github.siddharthjaswal.logpose:logpose-no-op:v1.3.0")
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
  `operation` / `table` yourself only for non-SQL stores). Pass `durationMillis` if you measure
  it — Room's callback has no timing, and unmeasured queries are excluded from slow-query
  reports rather than counted as instant.
- A worker event is keyed by `workId`, so a request's states collapse into one updating row.
  Durations derive from `WorkInfo` state changes and therefore include queue time.
- The first config snapshot in a process is a baseline; only later activations report changes.
- `dbEnabled` / `workersEnabled` on `LogPoseConfig` switch these off — a query callback on a
  busy screen can emit hundreds of events a minute.

Requires plugin 1.6.0+ to render as first-class rows (older plugins show them as generic rows).

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
    redactHeaders = setOf("Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization"),
    mocksEnabled = true,        // let the IDE plugin serve mock responses (see below)
)
```

## Mock & replay

The plugin can serve mock responses for matching requests (edit status/body/headers, add
latency, simulate timeouts). Rules travel IDE → device via `adb shell am broadcast` to a
receiver that ships in this artifact and is gated on the `DUMP` permission — only the adb
shell holds it, so no third-party app can push rules. The interceptor short-circuits matching
requests and emits them flagged `mocked = true`; the device confirms sync and reports hit
counts back over the normal logcat channel.

This lives **only in the real `logpose-android` artifact** — the release `no-op` jar contains
no receiver, provider, or `MockRegistry`. Set `mocksEnabled = false` to opt this build out
entirely.

Want a different transport (e.g. a socket via `adb reverse`)? Implement `EventEmitter` — one
method, taking the `Envelope` every timeline event travels in — and pass it to the interceptor:

```kotlin
LogPoseInterceptor(config, emitter = MySocketEmitter())
```

## License

[Apache 2.0](../LICENSE)
