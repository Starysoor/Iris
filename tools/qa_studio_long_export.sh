#!/usr/bin/env bash
# Run against a test device after building :app:assembleDebug :app:assembleDebugAndroidTest.
set -euo pipefail

pkg=com.comfyremote.app
evidence=${1:?Supply an evidence directory}
mkdir -p "$evidence"
fixtures=$(mktemp -d)
trap 'rm -r -- "$fixtures"' EXIT

ffmpeg -v error -y -f lavfi -i 'color=red:s=160x96:r=8:d=10' \
    -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=10' \
    -c:v libx264 -threads 1 -profile:v baseline -pix_fmt yuv420p -c:a aac -ac 2 \
    "$fixtures/qa-studio-a.mp4"
ffmpeg -v error -y -f lavfi -i 'color=blue:s=160x96:r=8:d=10' \
    -f lavfi -i 'sine=frequency=660:sample_rate=44100:duration=10' \
    -c:v libx264 -threads 1 -profile:v baseline -pix_fmt yuv420p -c:a aac -ac 2 \
    "$fixtures/qa-studio-b.mp4"
ffmpeg -v error -y -f lavfi -i 'sine=frequency=880:sample_rate=44100:duration=120' \
    -c:a pcm_s16le "$fixtures/qa-studio-audio.wav"

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
for file in qa-studio-a.mp4 qa-studio-b.mp4 qa-studio-audio.wav; do
    adb shell "run-as $pkg mkdir -p files"
    adb shell -T "run-as $pkg sh -c 'cat > files/$file'" < "$fixtures/$file"
done
adb shell am instrument -w -r \
    -e class com.comfyremote.app.data.project.StudioForegroundExportInstrumentedTest \
    -e studioExportSmoke "${IRIS_STUDIO_EXPORT_SMOKE:-false}" \
    -e studioExportTimeoutSeconds "${IRIS_STUDIO_EXPORT_TIMEOUT_SECONDS:-300}" \
    "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "$evidence/studio-foreground-export.txt"
grep -Eq '^OK \(1 test\)' "$evidence/studio-foreground-export.txt"
adb exec-out run-as "$pkg" cat files/qa-studio-export.mp4 > "$evidence/qa-studio-export.mp4"
ffmpeg -v error -xerror -i "$evidence/qa-studio-export.mp4" -f null -
ffprobe -v error -show_entries format=duration:stream=codec_type -of json \
    "$evidence/qa-studio-export.mp4" | tee "$evidence/qa-studio-export.json"
