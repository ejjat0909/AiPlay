#!/usr/bin/env bash
# First-run smoke test on a real head unit over adb.
set -uo pipefail
APK="${1:-build/AiPlay-0.1.0.apk}"
PKG=com.ejjat.aiplay

echo "== device =="
adb devices -l

echo "== install =="
adb install -r "$APK"

echo "== launch =="
adb logcat -c
adb shell am start -W -n "$PKG/.ui.SplashActivity"

sleep 4
echo "== resumed activity =="
adb shell dumpsys activity activities | grep -iE "mResumedActivity|topResumedActivity" | head -3

echo "== log =="
adb logcat -d -v brief | grep -iE "aiplay|AndroidRuntime" | tail -40

echo "== receiver =="
adb shell am start-foreground-service -n "$PKG/.receiver.AaReceiverService" \
  -a com.ejjat.aiplay.action.START_RECEIVER
sleep 2
adb shell "cat /proc/net/tcp | grep -i 149D" | head -3
adb logcat -d -v brief | grep -iE "receiver|mdns" | tail -10

echo "== report =="
adb shell ls -l /sdcard/Android/data/$PKG/files/reports/ 2>/dev/null
echo "done"
