#!/usr/bin/env bash
# The emulator step of .github/workflows/smoke.yml. Writes everything to <out>/smoke.log and never
# fails early: the emulator action stops at the first failing command, and a half-written result
# would hide what happened. The verdict is the last line of <out>/report.txt (RESULT: PASS / FAIL).
#
# Usage: tools/ci-smoke.sh <directory with the APK> <out directory>
# Environment: SMOKE_PROFILE (phone / tablet), SMOKE_SCENARIOS (empty: the driver's default).
APK_DIR="$1"
OUT="$2"
mkdir -p "$OUT"
exec > "$OUT/smoke.log" 2>&1
set -x
APK=$(ls "$APK_DIR"/*.apk | head -1)
adb devices
# Diagnostics: tells a slow emulator from a slow app.
ls -l /dev/kvm || true
nproc; uptime; free -m
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.model
adb shell wm size
adb shell wm density
adb shell getprop persist.sys.locale
adb shell getprop sys.boot_completed
adb shell top -b -n 1 -m 15 || true
# Root before anything holds a connection open: adbd restarts. It lets the driver change the
# device's own language; without it the driver falls back to the app's language and says so.
adb root || true
sleep 3
adb wait-for-device
adb shell id
adb logcat -G 16M || true
python3 tools/smoke.py "$APK" "$OUT" --profile "${SMOKE_PROFILE:-phone}" ${SMOKE_SCENARIOS:+--scenarios "$SMOKE_SCENARIOS"} \
  || echo "SMOKE_FAILED"
uptime
adb shell top -b -n 1 -m 15 || true
exit 0
