#!/usr/bin/env bash
# Start the LogPose daemon, assert on what the app actually did, tear it down — one exit code.
#
# This is the CI shape of LogPose: no IDE, no tool window, no human. The daemon captures the
# device's traffic and answers the same MCP tools an agent uses, so a pipeline can gate on the
# wire ("the flow made calls", "none of them failed") instead of on a screenshot.
#
# Usage:
#   scripts/ci-capture-check.sh [seconds-to-capture]        # default 30
#
# Environment:
#   LOGPOSE_JAR     the daemon jar (default: daemon/build/dist/logpose-daemon-*.jar, newest)
#   LOGPOSE_PORT    port to bind on 127.0.0.1 (default 63343, the daemon's own default)
#   LOGPOSE_DEVICE  adb serial, when more than one device is attached
#   MIN_EVENTS      how many captured events count as "the app did something" (default 1)
#
# ---------------------------------------------------------------------------------------------
# THIS NEEDS A DEVICE, and it is honest about that.
#
# Two live things: a connected device or emulator, and a debug build of the app running on it
# with `logpose-android` >= 1.5.0 emitting to logcat. On a CI box that means an emulator started
# before this runs (and, realistically, a Maestro/Espresso flow driving the app while it captures
# — this script only watches). With no device, or with an app that emits nothing, it fails at the
# health gate rather than passing vacuously.
#
# It deliberately does NOT pass --mocks: the daemon then never writes the device's rule set, so
# this is safe to run beside anything else that does. To set up a mocked tier first, use
# `scripts/push-mocks.sh`, which speaks the same broadcast channel over plain adb.
#
# jq is not required — the assertions below are substring and integer checks on purpose, so this
# runs on a bare CI image with curl and nothing else.
# ---------------------------------------------------------------------------------------------
set -uo pipefail

CAPTURE_SECONDS="${1:-30}"
PORT="${LOGPOSE_PORT:-63343}"
MIN_EVENTS="${MIN_EVENTS:-1}"
HEALTH_TIMEOUT=30          # seconds to wait for capture=attached before giving up
URL="http://127.0.0.1:$PORT/api/logpose/mcp"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${LOGPOSE_JAR:-}"
if [ -z "$JAR" ]; then
  # Newest built jar. Built with: ./gradlew :daemon:distJar
  JAR=$(ls -t "$ROOT"/daemon/build/dist/logpose-daemon-*.jar 2>/dev/null | head -1)
fi
[ -n "$JAR" ] && [ -f "$JAR" ] || {
  echo "No daemon jar. Build one with: ./gradlew :daemon:distJar" >&2
  echo "(or point LOGPOSE_JAR at it)" >&2
  exit 2
}

WORKDIR=$(mktemp -d)
TOKEN="ci-$$-$(date +%s)"
PID=""

# One teardown for every exit path — a daemon left holding a logcat tail is the worst thing this
# script could leave behind on a shared CI box.
cleanup() {
  if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID" 2>/dev/null
    # SIGTERM runs the daemon's shutdown hook (stop the reader, close the port); give it a moment
    # before insisting.
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      kill -0 "$PID" 2>/dev/null || break
      sleep 0.5
    done
    kill -9 "$PID" 2>/dev/null
  fi
  rm -rf "$WORKDIR"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

# --------------------------------------------------------------------------------------------
# One MCP tools/call, unwrapped enough to grep.
#
# The tool's own JSON arrives as a *string* at .result.content[0].text, i.e. escaped inside the
# envelope. Rather than un-escape it without jq, the assertions below match against the escaped
# form — which is why they look for \"count\":0 with backslashes in it.
# --------------------------------------------------------------------------------------------
call() {
  curl -sS --max-time 60 -X POST "$URL" \
    -H 'Content-Type: application/json' \
    -H "X-LogPose-Token: $TOKEN" \
    --data "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":{}}}"
}

DEVICE_ARGS=""
[ -n "${LOGPOSE_DEVICE:-}" ] && DEVICE_ARGS="--device $LOGPOSE_DEVICE"

echo "== starting the daemon on port $PORT"
# shellcheck disable=SC2086
java -jar "$JAR" serve \
  --project-dir "$WORKDIR" --port "$PORT" --token "$TOKEN" $DEVICE_ARGS \
  > "$WORKDIR/daemon.out" 2> "$WORKDIR/daemon.err" &
PID=$!

# --------------------------------------------------------------------------------------------
# Gate 1 — /health says the capture is attached.
#
# Bounded, and it distinguishes the two failures worth telling apart: the port never opened (the
# daemon died — its stderr is printed), and the port opened but logcat never attached (no device).
# --------------------------------------------------------------------------------------------
attached=0
for _ in $(seq 1 $((HEALTH_TIMEOUT * 2))); do
  kill -0 "$PID" 2>/dev/null || { cat "$WORKDIR/daemon.err" >&2; fail "the daemon exited during startup"; }
  health=$(curl -sS --max-time 3 "http://127.0.0.1:$PORT/health" 2>/dev/null)
  case "$health" in
    *'"capture":"attached"'*) attached=1; break ;;
  esac
  sleep 0.5
done
[ "$attached" = 1 ] || {
  cat "$WORKDIR/daemon.err" >&2
  fail "capture never attached within ${HEALTH_TIMEOUT}s — is a device connected (adb devices)?"
}
echo "   health: $health"

echo "== capturing for ${CAPTURE_SECONDS}s (drive the app now, or let a flow run)"
sleep "$CAPTURE_SECONDS"

# --------------------------------------------------------------------------------------------
# Gate 2 — the app did something.
#
# /health's event count is the cheapest possible version of this and needs no token, so it is
# what the count assertion reads; session_summary is called too, because a summary that answers
# at all proves the authenticated MCP surface is up, not just the liveness endpoint.
# --------------------------------------------------------------------------------------------
health=$(curl -sS --max-time 5 "http://127.0.0.1:$PORT/health")
events=$(printf '%s' "$health" | sed -n 's/.*"events":\([0-9]*\).*/\1/p')
[ -n "$events" ] || fail "could not read an event count out of /health: $health"
echo "== captured $events events"
[ "$events" -ge "$MIN_EVENTS" ] || fail "only $events events captured (wanted >= $MIN_EVENTS) — is the app running and instrumented?"

summary=$(call session_summary)
case "$summary" in
  *'\"total_events\":'*) : ;;
  *) fail "session_summary did not answer: $summary" ;;
esac
echo "   session_summary answered over MCP"

# --------------------------------------------------------------------------------------------
# Gate 3 — nothing failed on the wire.
#
# Note what is asserted and what is not: that find_failures *answered*, and that its count is
# zero. A real pipeline usually wants the second half of that to be its own policy — some flows
# are supposed to produce a 500 — so this is the line to edit, not to work around.
# --------------------------------------------------------------------------------------------
failures=$(call find_failures)
case "$failures" in
  *'\"count\":'*) : ;;
  *) fail "find_failures did not answer: $failures" ;;
esac
echo "   find_failures answered over MCP"

case "$failures" in
  *'\"count\":0'*) echo "   no failed calls in the capture" ;;
  *) echo "FAIL: the capture contains failed calls:" >&2
     printf '%s\n' "$failures" >&2
     exit 1 ;;
esac

echo
echo "PASS — daemon started, captured $events events from a real device, and the wire is clean."
