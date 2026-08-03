#!/usr/bin/env bash
# Pull LogPose's retained capture buffer off a running app as NDJSON — no IDE, no MCP, no agent.
#
# The read-back twin of push-mocks.sh: with the app on a build that sets
# LogPoseConfig(exportEnabled = true), this DUMPs every captured event to a file the app writes
# into its external-files dir, then adb-pulls it so a CI gate can assert on the wire with jq/python
# ("exactly one PUT order/accept", "EXPIRY_DEFERRED fired and REJECTED_WITH_TIME did not").
#
# Usage:
#   scripts/export-capture.sh <app-package> [out.ndjson] [adb-serial]
#   scripts/export-capture.sh --clear <app-package> [adb-serial]    # empty the buffer (start clean)
#
# The app must run a debug/staging build with logpose-android >= 1.6.0 (the receiver ships there)
# AND LogPoseConfig.exportEnabled = true — otherwise nothing is retained and you get a 0-byte file.
set -euo pipefail

RECEIVER="io.github.siddharthjaswal.logpose.mock.LogPoseExportReceiver"
FLAG="0x00000020"          # FLAG_INCLUDE_STOPPED_PACKAGES — matches the plugin/push-mocks
MARKER="LogPose export:"   # the single confirmation line the device logs when the dump is written

if [ "${1:-}" = "--clear" ]; then
  PKG="${2:?usage: export-capture.sh --clear <app-package> [serial]}"
  SERIAL="${3:-}"
  ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
  "${ADB[@]}" shell am broadcast -n "${PKG}/${RECEIVER}" -f "$FLAG" --es cmd clear >/dev/null
  echo "Cleared the export buffer on ${PKG}."
  exit 0
fi

PKG="${1:?usage: export-capture.sh <app-package> [out.ndjson] [serial]}"
OUT="${2:-capture.ndjson}"
SERIAL="${3:-}"
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

REMOTE_NAME="$(basename "$OUT")"

# Drop past log lines so we wait on THIS dump's confirmation, then ask the app to write the file.
"${ADB[@]}" logcat -c >/dev/null 2>&1 || true
"${ADB[@]}" shell am broadcast -n "${PKG}/${RECEIVER}" -f "$FLAG" \
  --es cmd dump --es out "$REMOTE_NAME" >/dev/null

# The device logs one marker line ("... wrote N events to <abs-path>") when the file is on disk.
line=""
for _ in $(seq 1 20); do
  line="$("${ADB[@]}" logcat -d -s LogPose:I 2>/dev/null | grep -F "$MARKER" | grep -F "$REMOTE_NAME" | tail -1 || true)"
  [ -n "$line" ] && break
  sleep 0.3
done
if [ -z "$line" ]; then
  echo "ERROR: no export confirmation from ${PKG}. Is it a debug build with logpose-android >= 1.6.0?" >&2
  exit 1
fi

# Pull from the exact absolute path the device reported (don't second-guess the storage layout).
REMOTE="$(printf '%s' "$line" | sed -n 's/.*wrote [0-9]* events to \(.*\)$/\1/p')"
"${ADB[@]}" pull "$REMOTE" "$OUT" >/dev/null
COUNT="$(wc -l < "$OUT" | tr -d ' ')"
echo "Pulled ${COUNT} events to ${OUT}."
