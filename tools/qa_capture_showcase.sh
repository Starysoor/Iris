#!/usr/bin/env bash
# Capture REAL debug-build Compose screens on an Android emulator.
# All data are clearly labeled local demo fixtures, no cloud/API billing.
set -euo pipefail

PKG="com.comfyremote.app"
ACTIVITY="${PKG}/.MainActivity"
DEST="docs/verification/showcase-2026"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$DEST"
command -v ffmpeg >/dev/null || { echo "ffmpeg required" >&2; exit 1; }

ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i 'testsrc2=size=480x270:rate=24:duration=8' \
  -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=8' \
  -c:v libx264 -preset ultrafast -pix_fmt yuv420p \
  -c:a aac -b:a 96k -t 8 "$TMP/showcase-cut.mp4"
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i 'sine=frequency=880:sample_rate=44100:duration=8' \
  -ac 2 -ar 44100 -c:a pcm_s16le "$TMP/showcase-score.wav"

adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
adb shell run-as "$PKG" mkdir -p files/qa-showcase
for media in showcase-cut.mp4 showcase-score.wav; do
  adb push "$TMP/$media" "/data/local/tmp/$media" >/dev/null
  adb shell run-as "$PKG" cp "/data/local/tmp/$media" "files/qa-showcase/$media"
done

capture() {
    local theme="$1" screen="$2" out="$3"
    adb shell cmd uimode night "$theme" >/dev/null
    adb shell am force-stop "$PKG"
    adb shell am start -W -n "$ACTIVITY" \
        -a "${PKG}.QA_GALLERY" --es qa_screen "$screen" >/dev/null
    sleep 5
    test -n "$(adb shell pidof "$PKG" | tr -d '\r')" || {
      echo "App is not running on page $screen" >&2; exit 1;
    }
    adb exec-out screencap -p > "$DEST/$out"
    test "$(stat -c %s "$DEST/$out")" -gt 30000 || {
      echo "Screenshot unexpectedly small: $out" >&2; exit 1;
    }
    adb shell uiautomator dump /sdcard/showcase-ui.xml >/dev/null 2>&1
    adb exec-out cat /sdcard/showcase-ui.xml > "$DEST/${out%.png}.xml"
    echo "Captured real app UI: $out"
}

# Project lifecycle, navigation and media views; no made-up UI screenshots.
capture no projects 01-projects-light.png
capture no overview 02-overview-light.png
capture no shots 03-shots-light.png
capture no assets 04-assets-light.png
capture yes timeline 05-timeline-dark.png
capture yes director 06-director-dark.png
capture yes settings 07-settings-dark.png

adb logcat -d -s AndroidRuntime:E > "$DEST/android-runtime-errors.log"
if grep -q "Process: $PKG" "$DEST/android-runtime-errors.log"; then
    echo "Fatal error during screenshot capture" >&2; exit 1
fi
echo "Captured 7 authentic app screenshots (offline demo data) into $DEST"
