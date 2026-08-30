#!/usr/bin/env bash

set -u
set -o pipefail

readonly PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
readonly ACTIVITY="dev.omniand.launcher/dev.omniand.hub.MainActivity"
readonly ENV_FILE="$PROJECT_DIR/.env"

load_env() {
  if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
  fi
}

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

WATCH_PATHS=(
  "$PROJECT_DIR/app/src"
  "$PROJECT_DIR/gradle"
  "$PROJECT_DIR/build.gradle.kts"
  "$PROJECT_DIR/settings.gradle.kts"
  "$PROJECT_DIR/gradle.properties"
  "$PROJECT_DIR/app/build.gradle.kts"
)
if [[ -f "$ENV_FILE" ]]; then
  WATCH_PATHS+=("$ENV_FILE")
fi

deploy() {
  echo
  echo "[$(date '+%H:%M:%S')] Building OmniAnd..."
  load_env
  if ! "$PROJECT_DIR/gradlew" -p "$PROJECT_DIR" clean assembleDebug; then
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
    "${WATCH_PATHS[@]}" >/dev/null

  # Editors often emit several filesystem events for a single save.
  sleep 0.2
  deploy || true
done
