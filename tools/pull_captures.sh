#!/usr/bin/env bash
# Copy all PoseCam capture sessions from the connected phone into ./data/.
# Usage: tools/pull_captures.sh [-s SERIAL]   (sessions already present locally are skipped)
set -euo pipefail

ADB=(adb "$@")
REMOTE=/sdcard/Android/data/com.posecam/files/captures
LOCAL="$(cd "$(dirname "$0")/.." && pwd)/data"
mkdir -p "$LOCAL"

for session in $("${ADB[@]}" shell ls "$REMOTE" 2>/dev/null | tr -d '\r'); do
    if [[ -d "$LOCAL/$session" ]]; then
        echo "skip  $session (already pulled)"
    else
        "${ADB[@]}" pull "$REMOTE/$session" "$LOCAL/" >/dev/null
        echo "pull  $session"
    fi
done
