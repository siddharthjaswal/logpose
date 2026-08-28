package io.github.siddharthjaswal.logpose.mock

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import io.github.siddharthjaswal.logpose.FcmMessageInfo
import io.github.siddharthjaswal.logpose.LogPose
import io.github.siddharthjaswal.logpose.LogPoseConfig
import io.github.siddharthjaswal.logpose.emit.LogcatEmitter
import io.github.siddharthjaswal.logpose.wire.PushAck
import io.github.siddharthjaswal.logpose.wire.PushInject
import io.github.siddharthjaswal.logpose.wire.PushMessage
import java.util.concurrent.Executors

/**
 * Delivers a push the IDE injected ([PushInject]) into this process, so a flow that really starts
 * with a push can be started from the IDE — no Play services, no network, no backend.
 *
 * Two tiers, in order:
 *  1. the app's own [LogPose.onPushInject] handler — the reliable contract;
 *  2. failing that, the `FirebaseMessagingService` declared in the manifest, called reflectively.
 *     LogPose references no Firebase type anywhere (not even `compileOnly`), so the class,
 *     the `RemoteMessage` and its `Bundle` constructor are all resolved by name at runtime; if
 *     any of that has moved, the injection reports [PushAck.DELIVERED_NONE] rather than failing.
 *
 * Nothing here ever throws at the app: a delivery problem is an ack, not a crash.
 *
 * **Caveat:** this reproduces *foreground data-message* delivery (`onMessageReceived`). It cannot
 * reproduce the system-tray path a background notification message takes — data messages are what
 * trigger flows, which is what this is for.
 */
internal object PushInjector {

    /** Delivery runs off the broadcast thread (which is the main thread) — see [inject]. */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "logpose-push-inject").apply { isDaemon = true }
    }

    /**
     * Emits the injected push's timeline row, then delivers it **off the main thread** and acks
     * the outcome. The row goes out first and unconditionally: an injection that reaches no
     * handler must still be visible in the capture, or the timeline would be lying about what
     * the IDE did.
     *
     * [onFinished] runs after the ack, for the receiver's `goAsync()` bookkeeping.
     */
    fun inject(
        context: Context,
        injection: PushInject,
        config: LogPoseConfig = LogPoseRuntime.config,
        onFinished: () -> Unit = {},
    ) {
        val info = injection.message.toInfo()
        val pkg = context.packageName
        val appContext = context.applicationContext ?: context
        runCatching { LogPose.logInjectedFcm(info, injection.id, injection.traceId, config) }

        executor.execute {
            val outcome = deliver(info, injection.traceId) { deliverToService(appContext, info) }
            runCatching {
                LogcatEmitter(config).emit(
                    PushAck(
                        pkg = pkg,
                        id = injection.id,
                        delivered = outcome.delivered,
                        error = outcome.error,
                    )
                )
            }
            runCatching { onFinished() }
        }
    }

    /**
     * Runs the delivery tiers inside [traceId] (when the injection carries one), so every event
     * the push triggers on this thread lands in the same `get_trace` group as the push itself.
     * [viaService] is the Tier-2 fallback, injected so the tiering is testable without Android.
     */
    fun deliver(info: FcmMessageInfo, traceId: String?, viaService: () -> Boolean): Delivery {
        val attempt = {
            when {
                deliverToHandler(info) -> Delivery(PushAck.DELIVERED_HANDLER)
                viaService() -> Delivery(PushAck.DELIVERED_SERVICE)
                else -> Delivery(PushAck.DELIVERED_NONE)
            }
        }
        return try {
            if (traceId == null) attempt() else LogPose.withTrace(traceId, attempt)
        } catch (t: Throwable) {
            // Tier 2 is reflection against a library LogPose doesn't depend on, and Tier 1 is
            // app code: either can throw, and neither may take the app down over a debug tool.
            Delivery(PushAck.DELIVERED_NONE, t.toString())
        }
    }

    /** Tier 1: the handler the app registered with [LogPose.onPushInject]. */
    private fun deliverToHandler(info: FcmMessageInfo): Boolean {
        val handler = LogPose.pushInjectHandler() ?: return false
        handler(info)
        return true
    }

    /**
     * Tier 2: resolve the app's `FirebaseMessagingService` from the manifest, instantiate it,
     * give it a base context, and hand it a `RemoteMessage` built from a [Bundle] — all by
     * reflection, so no Firebase type is ever named at compile time.
     */
    private fun deliverToService(context: Context, info: FcmMessageInfo): Boolean {
        val intent = Intent(MESSAGING_EVENT).setPackage(context.packageName)
        val serviceName = context.packageManager
            ?.queryIntentServices(intent, 0)
            ?.firstOrNull { it.serviceInfo?.name != null }
            ?.serviceInfo?.name
            ?: return false

        val loader = context.classLoader
        val serviceClass = Class.forName(serviceName, false, loader)
        val service = serviceClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

        // A Service built by hand has no base context; onMessageReceived implementations reach for
        // one constantly (resources, notification manager), so attach the application context.
        ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }
            .invoke(service, context)

        val remoteMessageClass = Class.forName(REMOTE_MESSAGE, false, loader)
        val remoteMessage = remoteMessageClass
            .getDeclaredConstructor(Bundle::class.java).apply { isAccessible = true }
            .newInstance(info.toBundle())

        serviceClass
            .getMethod("onMessageReceived", remoteMessageClass)
            .apply { isAccessible = true }
            .invoke(service, remoteMessage)
        return true
    }

    /**
     * The `Bundle` shape `RemoteMessage` parses: reserved `google.`/`gcm.` keys for the envelope
     * and notification, everything else read back as the data map.
     */
    private fun FcmMessageInfo.toBundle(): Bundle = Bundle().apply {
        // Data first, so a payload key can never overwrite the envelope keys below.
        data.forEach { (key, value) -> putString(key, value) }
        messageId?.let { putString("google.message_id", it) }
        from?.let { putString("from", it) }
        to?.let { putString("google.to", it) }
        collapseKey?.let { putString("collapse_key", it) }
        messageType?.let { putString("message_type", it) }
        sentTimeMillis?.let { putString("google.sent_time", it.toString()) }
        ttlSeconds?.let { putString("google.ttl", it.toString()) }
        priority?.let { putString("google.original_priority", it.toString()) }
        notificationTitle?.let { putString("gcm.notification.title", it) }
        notificationBody?.let { putString("gcm.notification.body", it) }
        notificationChannelId?.let { putString("gcm.notification.android_channel_id", it) }
        notificationClickAction?.let { putString("gcm.notification.click_action", it) }
        notificationImageUrl?.let { putString("gcm.notification.image", it) }
    }

    /** Which tier took the push, and why it didn't if none did. */
    data class Delivery(val delivered: String, val error: String? = null)

    private const val MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT"
    private const val REMOTE_MESSAGE = "com.google.firebase.messaging.RemoteMessage"
}

/** The app-facing form of an injected push, so both delivery tiers speak the same holder. */
internal fun PushMessage.toInfo(): FcmMessageInfo = FcmMessageInfo(
    messageId = messageId,
    from = from,
    to = to,
    collapseKey = collapseKey,
    messageType = messageType,
    sentTimeMillis = sentTimeMillis,
    ttlSeconds = ttlSeconds,
    priority = priority,
    notificationTitle = notificationTitle,
    notificationBody = notificationBody,
    notificationChannelId = notificationChannelId,
    notificationClickAction = notificationClickAction,
    notificationImageUrl = notificationImageUrl,
    data = data,
)
