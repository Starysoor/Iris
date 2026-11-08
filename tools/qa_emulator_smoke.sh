#!/usr/bin/env bash
set -euo pipefail

PKG="com.comfyremote.app"
ACTIVITY="${PKG}/.MainActivity"
APK="app/build/outputs/apk/debug/app-debug.apk"
EVIDENCE="tools/qa-screens"
mkdir -p "$EVIDENCE"

adb wait-for-device
adb install -r "$APK"
adb logcat -c

check_alive() {
  local description="$1"
  local pid
  pid=$(adb shell pidof "$PKG" | tr -d '\r' || true)
  if [[ -z "$pid" ]]; then
    echo "FAIL: $description: Iris process not running" >&2
    exit 1
  fi
  adb exec-out screencap -p > "$EVIDENCE/$description.png"
  test -s "$EVIDENCE/$description.png"
  echo "PASS: $description; PID=$pid"
}

echo "QA step 1: fresh launcher cold start"
adb shell am start -W -n "$ACTIVITY"
sleep 4
check_alive "cold-start"

echo "QA step 2: force-stop process and relaunch from launcher"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY"
sleep 4
check_alive "after-force-stop"

echo "QA step 3: submit safe debug-only local smoke task"
adb shell am start -a com.comfyremote.app.QA_SMOKE -n "$ACTIVITY"
sleep 3
check_alive "smoke-task-submitted"
adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY"
sleep 4
check_alive "after-smoke-task-restart"

echo "QA step 4: disabled system animations"
adb shell settings put global animator_duration_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global window_animation_scale 0
adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY"
sleep 3
check_alive "animations-disabled"

echo "QA step 5: dark mode and large font cold start"
adb shell cmd uimode night yes
adb shell settings put system font_scale 1.3
adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY"
sleep 4
check_alive "dark-large-font"

echo "QA step 6: locked landscape cold start"
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
adb shell am force-stop "$PKG"
adb shell am start -W -n "$ACTIVITY"
sleep 4
check_alive "landscape"
adb shell settings put system user_rotation 0

adb logcat -d -s AndroidRuntime:E > "$EVIDENCE/android-runtime-errors.log"
if grep -q "Process: $PKG" "$EVIDENCE/android-runtime-errors.log"; then
  echo "FAIL: app fatal crash found in AndroidRuntime" >&2
  cat "$EVIDENCE/android-runtime-errors.log" >&2
  exit 1
fi

echo "QA step 7: verify real MP4 export and parallel audio mixing"
bash tools/qa_audio_mix_fixture.sh "$EVIDENCE"

echo "PASS: emulator process lifecycle and media export checks"
