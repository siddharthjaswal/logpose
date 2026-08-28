#!/usr/bin/env bash
#
# agent-flow-check.sh — the LogPose agent loop, written out in curl.
#
# This is the walkthrough behind LogPose 1.8.0's headline claim: a coding agent can drive a whole
# mobile flow with zero human clicks — mock the endpoints, inject the push that starts the flow,
# await what the app does next, and assert on it. Everything below is a plain JSON-RPC call to the
# same MCP endpoint an agent uses, so it doubles as a reference for wiring anything else up
# (a shell script, a test harness, a different agent framework).
#
#   LOGPOSE_TOKEN=<token> ./scripts/agent-flow-check.sh
#
# ---------------------------------------------------------------------------------------------
# THIS CANNOT RUN IN CI, and it is not meant to.
#
# It needs four live things: an IDE with the LogPose tool window open and **capture running**, a
# connected device or emulator, a debug build of the app with `logpose-android` >= 1.7.0, and that
# app having registered `LogPose.onPushInject { }` (or having a FirebaseMessagingService in its
# manifest for the reflective fallback). Run it by hand against a running app.
#
# For the parts that *are* CI-runnable with no IDE, see `scripts/push-mocks.sh` (push mock rules
# over adb) and `scripts/export-capture.sh` (dump the capture as NDJSON).
# ---------------------------------------------------------------------------------------------
#
# Environment:
#   LOGPOSE_TOKEN   required. The per-project token — click "⚡ Connect Coding Agent" in the
#                   LogPose tool window; it copies a command containing it.
#   LOGPOSE_PORT    the IDE's built-in web server port (default 63342; a second open IDE gets the
#                   next free port, so check the copied command).
#   LOGPOSE_HOST    default 127.0.0.1. The endpoint is localhost-only by design.
#   PUSH_CHANNEL    the value put in the push's `data.channel` (default "order_assigned") — change
#                   it to whatever your app actually routes on.
#   MOCK_PATH       the endpoint to mock (default "/v1/orders/*").
#   AWAIT_MS        how long to wait for the app to react (default 20000).

set -euo pipefail

HOST="${LOGPOSE_HOST:-127.0.0.1}"
PORT="${LOGPOSE_PORT:-63342}"
TOKEN="${LOGPOSE_TOKEN:-}"
PUSH_CHANNEL="${PUSH_CHANNEL:-order_assigned}"
MOCK_PATH="${MOCK_PATH:-/v1/orders/*}"
AWAIT_MS="${AWAIT_MS:-20000}"

URL="http://$HOST:$PORT/api/logpose/mcp"

if [[ -z "$TOKEN" ]]; then
  echo "LOGPOSE_TOKEN is not set." >&2
  echo "Open the LogPose tool window → ⚡ Connect Coding Agent; the copied command contains it." >&2
  exit 2
fi
command -v jq >/dev/null || { echo "This script needs jq (brew install jq)." >&2; exit 2; }

step=0

# ---------------------------------------------------------------------------------------------
# call <tool> <arguments-json>
#
# One MCP `tools/call`. The plugin answers with the standard MCP envelope — the tool's own JSON is
# a *string* at .result.content[0].text — so this unwraps it and prints the tool's payload, which
# is what every assertion below reads.
#
# Note the timeout: await_event deliberately holds the HTTP request open until its event arrives
# (that's the whole point — no polling), so curl must be allowed to wait longer than the tool will.
# ---------------------------------------------------------------------------------------------
call() {
  local tool="$1" args="$2"
  step=$((step + 1))
  local body
  body=$(jq -nc --arg t "$tool" --argjson a "$args" --argjson i "$step" \
    '{jsonrpc:"2.0", id:$i, method:"tools/call", params:{name:$t, arguments:$a}}')

  local raw
  raw=$(curl -sS --max-time 180 -X POST "$URL" \
    -H 'Content-Type: application/json' \
    -H "X-LogPose-Token: $TOKEN" \
    --data "$body")

  # A transport-level JSON-RPC error (bad token, unknown method) never reaches the tool layer.
  if jq -e '.error' >/dev/null 2>&1 <<<"$raw"; then
    echo "MCP error on $tool:" >&2
    jq '.error' <<<"$raw" >&2
    exit 1
  fi
  jq -r '.result.content[0].text' <<<"$raw"
}

say() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

# ---------------------------------------------------------------------------------------------
# 0. Is anyone home?
#
# Every failure below is much easier to read once this has been checked: capture running, an app
# that has announced itself, a device library new enough to accept an injection.
# ---------------------------------------------------------------------------------------------
say "0. Capture health"
summary=$(call session_summary '{}')
jq '{capture, total_events, sessions}' <<<"$summary"

if [[ "$(jq -r '.capture.running' <<<"$summary")" != "true" ]]; then
  echo "Capture is not running — press ▶ in the LogPose tool window and rerun." >&2
  exit 1
fi

# ---------------------------------------------------------------------------------------------
# 1. Mock the endpoint the flow will hit.
#
# `responses` makes this one rule a complete retry test: the first call gets a 500, every call
# after that gets the 200. The last step sticks once the list runs out.
# ---------------------------------------------------------------------------------------------
say "1. create_mock — $MOCK_PATH fails once, then succeeds"
mock=$(call create_mock "$(jq -nc --arg p "$MOCK_PATH" '{
  method: "GET",
  path_pattern: $p,
  responses: [
    { status: 500, body: "{\"error\":\"upstream\"}" },
    { status: 200, body: "{\"id\":91,\"status\":\"assigned\"}" }
  ]
}')")
jq '{active, warning, created: .created.id}' <<<"$mock"

mock_id=$(jq -r '.created.id // empty' <<<"$mock")
[[ -n "$mock_id" ]] || { echo "create_mock did not return a rule id:"; jq '.' <<<"$mock"; exit 1; }

# The rule exists locally whether or not the device took it. `active: false` means it is NOT
# serving — usually because the app hasn't announced itself to this capture (restart the app).
if [[ "$(jq -r '.active' <<<"$mock")" != "true" ]]; then
  echo "WARNING: the mock is not active on the device — see .warning above. Continuing anyway." >&2
fi

# ---------------------------------------------------------------------------------------------
# 2. Start the flow: inject the push.
#
# `await: true` waits (<= 10s) for the device to say which tier consumed it:
#   handler  — the app's own LogPose.onPushInject { } ran. This is the contract.
#   service  — the manifest's FirebaseMessagingService got onMessageReceived reflectively.
#   none     — nothing consumed it. The push still appears on the timeline (marked INJ), because
#              it really was injected; it just had nowhere to go.
#
# The returned trace_id is the handle on everything the push sets off.
# ---------------------------------------------------------------------------------------------
say "2. inject_fcm — deliver a '$PUSH_CHANNEL' push into the running app"
push=$(call inject_fcm "$(jq -nc --arg c "$PUSH_CHANNEL" '{
  data: { channel: $c, orderId: "91" },
  await: true
}')")
jq '{sent, delivered, trace_id, warning, error}' <<<"$push"

[[ "$(jq -r '.sent' <<<"$push")" == "true" ]] || { echo "Push was not sent. See .warning above." >&2; exit 1; }
trace=$(jq -r '.trace_id // empty' <<<"$push")
[[ -n "$trace" ]] || { echo "No trace_id came back from inject_fcm." >&2; exit 1; }

if [[ "$(jq -r '.delivered' <<<"$push")" == "none" ]]; then
  cat >&2 <<'EOF'
Nothing in the app consumed the push. Add this to your app's init (debug builds):

    LogPose.onPushInject { info -> MyPushRouter.handle(info.data, info.notificationTitle) }

...or keep a FirebaseMessagingService in the manifest for the reflective fallback.
EOF
  exit 1
fi

# ---------------------------------------------------------------------------------------------
# 3. Await what the push triggered.
#
# This is the step that replaces poll-and-hope. It blocks until an HTTP event carrying the push's
# trace arrives — and only matches events that arrive AFTER the call starts, so it can never
# report something that had already happened. A timeout is a normal result (matched: false), not
# an error: it means the app did nothing.
# ---------------------------------------------------------------------------------------------
say "3. await_event — what did the app call next?"
awaited=$(call await_event "$(jq -nc --arg t "$trace" --argjson ms "$AWAIT_MS" '{
  kind: "http",
  trace_id: $t,
  timeout_ms: $ms
}')")
jq '{matched, waited_ms, id, event, note}' <<<"$awaited"

# ---------------------------------------------------------------------------------------------
# 4. Assert.
#
# Two things are being checked, and they're different claims: that the push made the app do
# something at all, and that what it did was the call we mocked.
# ---------------------------------------------------------------------------------------------
say "4. assert"
if [[ "$(jq -r '.matched' <<<"$awaited")" != "true" ]]; then
  echo "FAIL: nothing HTTP happened in trace $trace within ${AWAIT_MS}ms." >&2
  echo "      The push was delivered, so either the app doesn't act on '$PUSH_CHANNEL'," >&2
  echo "      or its request didn't carry the trace — an async call needs" >&2
  echo "      LogPose.traceCalls(...) / logPoseTrace() for the row to join the trace." >&2
  exit 1
fi

event_id=$(jq -r '.id' <<<"$awaited")
# The full event carries the whole exchange under .payload — the wire Transaction, verbatim.
full=$(call get_event "$(jq -nc --arg i "$event_id" '{id: $i}')")
status=$(jq -r '.payload.response.code // "(no response)"' <<<"$full")
# `mocked` is set by the device on the response it served, so it's evidence, not a guess.
mocked=$(jq -r '.payload.mocked // false' <<<"$full")

echo "The app called: $(jq -r '.event.summary' <<<"$awaited")"
echo "  status : $status"
echo "  mocked : $mocked   (true = LogPose served it, so the rule matched)"

if [[ "$mocked" != "true" ]]; then
  echo "FAIL: the call went to the network — the mock's path pattern didn't match it." >&2
  echo "      Compare MOCK_PATH ('$MOCK_PATH') against the path in the event above." >&2
  exit 1
fi

say "PASS — mock → inject → await → assert, with no clicks."
echo "Trace $trace is on the timeline; open any of its rows → \"Show waterfall\" to read it."
echo
echo "Clean up when you're done:"
echo "  delete_mock  id=$mock_id      (or just stop capture — rules clear from the device)"
