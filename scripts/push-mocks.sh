#!/usr/bin/env bash
# Push LogPose mock rules to a running app over adb — no IDE, no MCP, no coding agent.
#
# This is the CI / test-orchestration path: a Maestro (or any) pipeline can set up the mocked
# tier before a flow runs, using the exact same DUMP-gated broadcast channel the plugin uses.
#
# Usage:
#   scripts/push-mocks.sh <app-package> <rules.json> [adb-serial]
#   scripts/push-mocks.sh --clear <app-package> [adb-serial]     # remove all rules
#
# <rules.json> is a JSON array of MockRule objects, e.g.:
#   [
#     { "id": "orders-500", "method": "GET", "pathPattern": "/v1/orders", "status": 500,
#       "body": "{\"error\":\"down\"}", "enabled": true, "mode": "replace" }
#   ]
#
# The app must run a debug/staging build with logpose-android >= 1.1.0 (the receiver ships there).
set -euo pipefail

RECEIVER="io.github.siddharthjaswal.logpose.mock.MockCommandReceiver"
FLAG="0x00000020"          # FLAG_INCLUDE_STOPPED_PACKAGES — matches the plugin
SLICE=2000                 # SLICE_CHARS — keep in sync with MocksController

if [ "${1:-}" = "--clear" ]; then
  PKG="${2:?usage: push-mocks.sh --clear <app-package> [serial]}"
  SERIAL="${3:-}"; RULES="[]"
else
  PKG="${1:?usage: push-mocks.sh <app-package> <rules.json> [serial]}"
  FILE="${2:?rules.json required}"
  SERIAL="${3:-}"
  RULES="$(cat "$FILE")"
fi

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

# A monotonically-increasing revision; the device ignores a stale one, so seconds-since-epoch is
# safe between runs.
REV="$(date +%s)"
JSON="{\"kind\":\"mock_rules\",\"revision\":${REV},\"rules\":${RULES}}"
B64="$(printf '%s' "$JSON" | base64 | tr -d '\n')"

LEN=${#B64}
TOTAL=$(( (LEN + SLICE - 1) / SLICE ))
[ "$TOTAL" -eq 0 ] && TOTAL=1

echo "Pushing ${TOTAL} chunk(s), revision ${REV}, to ${PKG} ..."
seq=0
offset=0
while [ "$offset" -lt "$LEN" ] || [ "$seq" -eq 0 ]; do
  slice="${B64:offset:SLICE}"
  "${ADB[@]}" shell am broadcast \
    -n "${PKG}/${RECEIVER}" \
    -f "$FLAG" \
    --ei rev "$REV" --ei seq "$seq" --ei total "$TOTAL" \
    --es payload "$slice" >/dev/null
  offset=$(( offset + SLICE ))
  seq=$(( seq + 1 ))
done

echo "Done. The app now serves these rules (mocked responses are flagged in logcat)."
