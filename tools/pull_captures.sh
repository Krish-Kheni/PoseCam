#!/usr/bin/env bash
# Copy all PoseCam capture sessions from the connected phone into ./data/.
# Usage: tools/pull_captures.sh [-s SERIAL]   (sessions already present locally are skipped)
set -euo pipefail

ADB=(adb "$@")
REMOTE=/sdcard/Android/data/com.posecam/files/captures
LOCAL="$(cd "$(dirname "$0")/.." && pwd)/data"
mkdir -p "$LOCAL"
incomplete=0

for session in $("${ADB[@]}" shell ls "$REMOTE" 2>/dev/null | tr -d '\r'); do
    if [[ -d "$LOCAL/$session" ]]; then
        echo "skip  $session (already pulled)"
    elif ! "${ADB[@]}" shell cat "$REMOTE/$session/manifest.json" 2>/dev/null | grep -q '"complete": true'; then
        # Never silently drop these: a take that did not stop cleanly is still evidence,
        # and the one that is still recording will be complete on the next run.
        mkdir -p "$LOCAL/incomplete"
        "${ADB[@]}" pull "$REMOTE/$session" "$LOCAL/incomplete/" >/dev/null 2>&1 || true
        echo "INCOMPLETE  $session -> data/incomplete/ (still recording, or stopped badly)"
        incomplete=$((incomplete + 1))
    else
        "${ADB[@]}" pull "$REMOTE/$session" "$LOCAL/" >/dev/null
        echo "pull  $session"
    fi
done

if [[ $incomplete -gt 0 ]]; then
    echo
    echo "WARNING: $incomplete recording(s) did not stop cleanly and are in data/incomplete/."
    echo "They may still be usable; do not delete them from the phone without checking."
fi
