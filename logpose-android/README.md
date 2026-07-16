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
    debugImplementation("com.github.siddharthjaswal.logpose:logpose-android:v0.9.10")
    // Release builds: a zero-overhead no-op with the same API (no logcat, no extra deps).
    releaseImplementation("com.github.siddharthjaswal.logpose:logpose-no-op:v0.9.10")
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

## Configuration

```kotlin
LogPoseConfig(
    tag = "LogPose",            // must match the plugin's tag
    enabled = BuildConfig.DEBUG,
    maxBodyBytes = 250_000,     // textual bodies larger than this are truncated
    maxLineChars = 3500,        // payloads larger than this are chunked
    redactHeaders = setOf("Authorization", "Cookie", "Set-Cookie", "Proxy-Authorization"),
)
```

Want a different transport (e.g. a socket via `adb reverse`)? Implement
`TransactionEmitter` and pass it to the interceptor:

```kotlin
LogPoseInterceptor(config, emitter = MySocketEmitter())
```

## License

[Apache 2.0](../LICENSE)
