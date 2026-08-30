#!/usr/bin/env bash

set -u
set -o pipefail

readonly ADB_BIN="${ADB_BIN:-adb}"
readonly DEVICE_SERIAL="${ANDROID_SERIAL:-emulator-5554}"
readonly HOST_PORT="${OMNIAND_HOST_PORT:-18080}"
readonly DEVICE_PORT="${OMNIAND_DEVICE_PORT:-8080}"

echo "Keeping OmniAnd desktop access active for $DEVICE_SERIAL."
echo "Forward: localhost:$HOST_PORT -> Android:$DEVICE_PORT"
echo "Press Ctrl+C to stop monitoring."

has_forward() {
  "$ADB_BIN" -s "$DEVICE_SERIAL" forward --list 2>/dev/null |
    awk -v serial="$DEVICE_SERIAL" -v local="tcp:$HOST_PORT" -v remote="tcp:$DEVICE_PORT" \
      '$1 == serial && $2 == local && $3 == remote { found = 1 } END { exit !found }'
}

while true; do
  if "$ADB_BIN" -s "$DEVICE_SERIAL" get-state >/dev/null 2>&1 && ! has_forward; then
    if "$ADB_BIN" -s "$DEVICE_SERIAL" forward "tcp:$HOST_PORT" "tcp:$DEVICE_PORT"; then
      echo "Established $DEVICE_SERIAL tcp:$HOST_PORT -> tcp:$DEVICE_PORT"
    else
      echo "Could not establish OmniAnd ADB forwarding; retrying." >&2
    fi
  fi
  sleep 2
done
