#!/usr/bin/env bash

set -u
set -o pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
readonly ACTIVITY="dev.omniand.launcher/dev.omniand.hub.MainActivity"

for command in adb java inotifywait; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Missing required command: $command" >&2
    exit 1
  fi
done

if ! adb get-state >/dev/null 2>&1; then
  echo "No single authorized Android device is available through ADB." >&2
  echo "Connect one device, or set ANDROID_SERIAL when several are connected." >&2
  exit 1
fi

deploy() {
  echo
  echo "[$(date '+%H:%M:%S')] Building OmniAnd..."
  if ! "$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" assembleDebug; then
    echo "Build failed; waiting for another change." >&2
    return 1
  fi

  echo "Installing $APK..."
  if ! adb install -r "$APK"; then
    echo "Installation failed; waiting for another change." >&2
    return 1
  fi

  if ! adb shell am start -n "$ACTIVITY" >/dev/null; then
    echo "Installed, but OmniAnd could not be launched automatically." >&2
    return 1
  fi

  echo "OmniAnd deployed and launched. Watching for changes..."
}

echo "Watching OmniAnd sources. Press Ctrl+C to stop."
deploy || true

while true; do
  inotifywait -q -r \
    -e close_write,create,delete,move \
    "$PROJECT_DIR/app/src" \
    "$PROJECT_DIR/gradle" \
    "$PROJECT_DIR/build.gradle.kts" \
    "$PROJECT_DIR/settings.gradle.kts" \
    "$PROJECT_DIR/gradle.properties" \
    "$PROJECT_DIR/app/build.gradle.kts" >/dev/null

  # Editors often emit several filesystem events for a single save.
  sleep 0.2
  deploy || true
done
