#!/usr/bin/env bash
# Run after pairing/connecting and granting permissions once on the phone.
set -euo pipefail
if [[ $# -lt 1 || $# -gt 2 ]]; then
    echo "Usage: $0 ADB_SERIAL [HEALTH_URL]" >&2
    echo "Example: $0 192.168.1.50:37891 http://192.168.1.50:8080" >&2
    exit 2
fi
pixel_serial="$1"
health_url="${2:-}"
adb -s "$pixel_serial" get-state >/dev/null
booted="$(adb -s "$pixel_serial" shell getprop sys.boot_completed | tr -d '\r')"
[[ "$booted" == 1 ]] || { echo "Android has not completed boot; retry later." >&2; exit 1; }
# Waking the display does not unlock a secure keyguard. BugCam never dismisses authentication.
adb -s "$pixel_serial" shell input keyevent KEYCODE_WAKEUP
adb -s "$pixel_serial" shell am start -W -n com.splarg.bugcam/.MainActivity --ez auto_start true
if [[ -n "$health_url" ]]; then
    echo "Waiting for fresh camera frames…"
    for attempt in {1..30}; do
        if curl --fail --silent --max-time 2 "${health_url%/}/health" |
            python3 -c 'import json,sys; h=json.load(sys.stdin); sys.exit(0 if h.get("camera_active") else 1)' 2>/dev/null; then
            adb -s "$pixel_serial" shell input keyevent KEYCODE_SLEEP
            echo "BugCam is capturing; display turned off."
            exit 0
        fi
        sleep 1
    done
    echo "No fresh frames. Check the lock screen, permissions, Wi-Fi address and BugCam Logcat." >&2
    exit 1
fi
echo "Check /health for camera_active=true, then turn off the display with KEYCODE_SLEEP."
