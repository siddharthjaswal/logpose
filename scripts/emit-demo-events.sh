#!/usr/bin/env bash
# Emit synthetic LogPose events to a connected device's logcat.
#
# Useful for exercising the plugin's rendering without an instrumented app — every line below
# is exactly what `logpose-android` >= 1.3.0 puts on the wire. Start capture in the LogPose
# tool window first, then run this.
#
#   ./scripts/emit-demo-events.sh
#
# Note the nested quoting: `adb shell` hands the command to the device's sh, which strips one
# level of quotes, so the JSON has to survive two passes.

set -euo pipefail

TAG="${LOGPOSE_TAG:-LogPose}"

emit() {
  local flat escaped
  # One JSON object per logcat line is the contract — the literals below are wrapped for
  # readability, so collapse them back onto a single line before sending.
  flat=$(printf '%s' "$1" | tr -d '\n')
  escaped=$(printf '%s' "$flat" | sed 's/"/\\"/g')
  adb shell "log -t $TAG \"$escaped\""
}

now_ms() { echo $(( $(date +%s) * 1000 )); }

TS=$(now_ms)
TRACE="demo$(date +%s)"

# A database query: a completed span with a code section and a key/value section.
emit '{"v":1,"kind":"event","id":"demo-db","at":'"$TS"',"endedAt":'"$((TS + 14))"',"traceId":"'"$TRACE"'",
"payload":{"title":"UserDao.insert","subtitle":"users (3 rows)",
"badges":[{"text":"DB","tone":"info"},{"text":"users","tone":"muted"}],
"sections":[{"label":"SQL","type":"code","body":"INSERT INTO users (id, name) VALUES (?, ?)"},
{"label":"Params","type":"kv","body":{"id":"7","name":"Vikram"}}]}}'

# A background job that failed: an error-toned badge and a stack trace.
emit '{"v":1,"kind":"event","id":"demo-job","at":'"$((TS + 20))"',"endedAt":'"$((TS + 1260))"',"traceId":"'"$TRACE"'",
"payload":{"title":"SyncWorker","subtitle":"failed after 2 attempts",
"badges":[{"text":"JOB","tone":"error"},{"text":"retry 2","tone":"warn"}],
"sections":[{"label":"Cause","type":"code","body":"java.net.SocketTimeoutException: timeout\n\tat okhttp3..."},
{"label":"Tags","type":"kv","body":{"unique":"sync","network":"connected"}}]}}'

# A feature-flag evaluation: a point-in-time event with a JSON section.
emit '{"v":1,"kind":"event","id":"demo-flag","at":'"$((TS + 30))"',"endedAt":'"$((TS + 30))"',
"payload":{"title":"FeatureFlags","subtitle":"new_checkout = true",
"badges":[{"text":"FLAG","tone":"muted"}],
"sections":[{"label":"Evaluation","type":"json","body":{"key":"new_checkout","value":true,"source":"remote","bucket":42}}]}}'

# A kind the plugin knows nothing about: still gets a row, rendered from its raw payload.
emit '{"v":1,"kind":"acme.telemetry","id":"demo-unknown","at":'"$((TS + 40))"',
"payload":{"metric":"frame_time_p99","value":18.4,"screen":"Feed"}}'

echo "Emitted 4 events on tag '$TAG' (trace $TRACE)."

# ---- First-class kinds (logpose-android >= 1.4.0) -------------------------------------------

# A slow database query: the plugin parses "SELECT … FROM orders" into [SELECT] orders.
emit '{"v":1,"kind":"db","id":"demo-db-slow","at":'"$((TS + 50))"',"endedAt":'"$((TS + 290))"',
"payload":{"sql":"SELECT o.id, o.status FROM orders o JOIN items i ON i.order_id = o.id WHERE o.rider_id = ?",
"args":["79096"],"database":"gandalf-room-db","rows":42}}'

# A fast write, so the timeline shows reads staying quiet and mutations standing out.
emit '{"v":1,"kind":"db","id":"demo-db-write","at":'"$((TS + 300))"',"endedAt":'"$((TS + 304))"',
"payload":{"sql":"UPDATE riders SET last_seen = ? WHERE id = ?","args":["1784570000","79096"],
"database":"gandalf-room-db","rows":1}}'

# One worker, three states, ONE id — proving the row updates in place rather than stacking up.
# Run this script twice to watch it move from running to succeeded.
emit '{"v":1,"kind":"worker","id":"demo-work-1","at":'"$((TS + 400))"',
"payload":{"worker":"SyncWorker","state":"running","workId":"demo-work-1","uniqueName":"sync",
"runAttempt":2,"tags":["sync","periodic"],"inputData":{"since":"1784560000"}}}'

# A worker that gave up, with the reason.
emit '{"v":1,"kind":"worker","id":"demo-work-2","at":'"$((TS + 410))"',"endedAt":'"$((TS + 1650))"',
"payload":{"worker":"EcomCallTelemetrySyncWorker","state":"failed","workId":"demo-work-2",
"runAttempt":3,"tags":["telemetry"],"error":"java.net.SocketTimeoutException: timeout"}}'

# A remote-config activation: one row for the whole fetch, listing what actually changed.
emit '{"v":1,"kind":"config","id":"demo-config","at":'"$((TS + 500))"',"endedAt":'"$((TS + 500))"',
"payload":{"source":"remote","fetchStatus":"LAST_FETCH_STATUS_SUCCESS","totalKeys":187,
"changes":[{"key":"IS_CAMERAX_ENABLED","value":"true","previous":"false"},
{"key":"HIGH_FREQ_API_REFRESH_THRESHOLD","value":"45","previous":"30"},
{"key":"NEW_ROUTING_ENABLED","value":"true","isNew":true}]}}'

echo "Emitted 5 more: db, worker and config events."

# ---- A whole flow, for the trace waterfall (plugin 1.8.0+) ----------------------------------
#
# Everything below shares $FLOW, so right-clicking any of these rows offers "Show waterfall" and
# the card renders all three shapes at once: a point (the push), a completed span (the API call)
# and an OPEN span that keeps growing towards "now" (the second call never gets a response line).
#
# The push row carries "injected":true, so it renders with the INJ pill — that's what a push
# LogPose delivered looks like on the wire. To let the IDE actually deliver one into YOUR app
# (right-click an FCM row → "Re-send this push", or the toolbar's Compose push…), give LogPose
# the app's push entry point once at init — needs logpose-android >= 1.7.0:
#
#   LogPose.onPushInject { info ->
#       MyPushRouter.handle(info.data, info.notificationTitle)
#   }
#
# Without it LogPose falls back to your manifest's FirebaseMessagingService reflectively, and
# reports back which tier took the push (handler | service | none). Injection simulates
# foreground data-message delivery; it can't reproduce the system notification tray.

FLOW="flow$(date +%s)"

# The push that starts the flow — a point in time, marked as LogPose-injected.
emit '{"v":1,"kind":"fcm","id":"demo-push","at":'"$((TS + 600))"',"endedAt":'"$((TS + 600))"',"traceId":"'"$FLOW"'",
"payload":{"kind":"fcm","id":"demo-push","event":"message","receivedAtMillis":'"$((TS + 600))"',
"messageId":"demo-push","from":"/topics/orders","collapseKey":"order_assigned","injected":true,
"data":{"channel":"order_assigned","orderId":"91"}}}'

# The call the push triggered: a completed span.
emit '{"v":1,"kind":"http","id":"demo-http-1","at":'"$((TS + 640))"',"endedAt":'"$((TS + 940))"',"traceId":"'"$FLOW"'",
"payload":{"id":"demo-http-1","startedAtMillis":'"$((TS + 640))"',"durationMillis":300,
"request":{"method":"GET","url":"https://api.example.com/v1/orders/91","host":"api.example.com","path":"/v1/orders/91","headers":{}},
"response":{"code":200,"message":"OK","headers":{"Content-Type":"application/json"},
"body":{"contentType":"application/json","sizeBytes":42,"text":"{\"id\":91,\"status\":\"assigned\"}"}}}}'

# A call with no response line: still open, so the waterfall draws it out to now, forever.
emit '{"v":1,"kind":"http","id":"demo-http-open","at":'"$((TS + 960))"',"traceId":"'"$FLOW"'",
"payload":{"id":"demo-http-open","startedAtMillis":'"$((TS + 960))"',
"request":{"method":"POST","url":"https://api.example.com/v1/orders/91/accept","host":"api.example.com","path":"/v1/orders/91/accept","headers":{},
"body":{"contentType":"application/json","sizeBytes":18,"text":"{\"accepted\":true}"}}}}'

echo "Emitted 3 more in trace '$FLOW' — right-click one → \"Show waterfall\"."
