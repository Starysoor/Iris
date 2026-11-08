#!/usr/bin/env bash
# End-to-end codec/audio-mixing test for the Iris Studio release gate.
# Run after installing the debug app and building the androidTest APK.
# Uses only local 2-second generated fixtures; no paid APIs or cloud keys.
set -euo pipefail

PKG="com.comfyremote.app"
EVIDENCE="${1:-tools/qa-screens}"
mkdir -p "$EVIDENCE"
command -v ffmpeg >/dev/null || { echo "ffmpeg required for media E2E" >&2; exit 1; }
command -v python3 >/dev/null || { echo "Python3 required for audio check" >&2; exit 1; }

FIXTURES=$(mktemp -d)
trap 'rm -rf "$FIXTURES"' EXIT

# Audio at 440 Hz is in the video's original AAC track.
ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i 'color=c=red:s=320x240:r=15:d=2' \
    -f lavfi -i 'sine=frequency=440:sample_rate=44100:duration=2' \
    -c:v libx264 -preset ultrafast -profile:v baseline -pix_fmt yuv420p \
    -c:a aac -b:a 128k -ar 44100 -ac 2 -t 2 "$FIXTURES/qa-source.mp4"

# Audio at 880 Hz is a separate WAV overlay.
ffmpeg -hide_banner -loglevel error -y \
    -f lavfi -i 'sine=frequency=880:sample_rate=44100:duration=2' \
    -ar 44100 -ac 2 -c:a pcm_s16le "$FIXTURES/qa-overlay.wav"

adb push "$FIXTURES/qa-source.mp4" /data/local/tmp/qa-source.mp4 >/dev/null
adb push "$FIXTURES/qa-overlay.wav" /data/local/tmp/qa-overlay.wav >/dev/null
adb shell run-as "$PKG" cp /data/local/tmp/qa-source.mp4 files/qa-source.mp4
adb shell run-as "$PKG" cp /data/local/tmp/qa-overlay.wav files/qa-overlay.wav

TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
test -s "$TEST_APK"
adb install -r "$TEST_APK" >/dev/null
echo "Running instrumented Media3 codec export with original + overlay audio"
adb shell am instrument -w -r \
    -e class com.comfyremote.app.data.project.StudioAudioExportInstrumentedTest \
    "$PKG.test/androidx.test.runner.AndroidJUnitRunner" | tee "$EVIDENCE/media3-audio-e2e.txt"
grep -Eq '^OK \(1 test\)' "$EVIDENCE/media3-audio-e2e.txt" || {
    echo "FAIL: real Media3 composition test did not pass" >&2
    exit 1
}

adb exec-out run-as "$PKG" cat files/qa-export.mp4 > "$EVIDENCE/qa-export.mp4"
test -s "$EVIDENCE/qa-export.mp4"
ffmpeg -hide_banner -loglevel error -y -i "$EVIDENCE/qa-export.mp4" \
    -vn -ar 44100 -ac 1 -f s16le "$EVIDENCE/audio-mix.pcm"

# Numerically confirm both input frequencies are audible in the output.
# A metadata-only "has audio" check would miss silently dropped overlays.
python3 - "$EVIDENCE/audio-mix.pcm" <<'PY'
import array
import math
import sys

pcm = array.array('h')
with open(sys.argv[1], 'rb') as f:
    pcm.frombytes(f.read())
fs = 44100
part = pcm[int(0.25 * fs):int(1.25 * fs)]
n = len(part)
assert n >= fs // 2, f"Decoded PCM too short: {n}"

def amplitude(freq):
    k = round(n * freq / fs)
    omega = 2 * math.pi * k / n
    coeff = 2 * math.cos(omega)
    q1 = q2 = 0.0
    for sample in part:
        q0 = coeff * q1 - q2 + sample
        q2, q1 = q1, q0
    power = max(0.0, q1 * q1 + q2 * q2 - coeff * q1 * q2)
    return 2 * math.sqrt(power) / n

original = amplitude(440)
overlay = amplitude(880)
print(f"Audio mixing check: original 440 Hz={original:.1f}, overlay 880 Hz={overlay:.1f}")
assert original > 100, f"Original video audio inaudible: {original}"
assert overlay > 100, f"Independent audio overlay missing: {overlay}"
print("PASS: exported MP4 contains both distinguishable audio sources")
PY
