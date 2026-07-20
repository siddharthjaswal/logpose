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
