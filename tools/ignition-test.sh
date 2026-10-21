#!/usr/bin/env bash
# Does AiPlay start (and stop) its receiver on the signals a head unit gives around ignition?
#
#   ./tools/ignition-test.sh -s emulator-5554             # app NOT exempt from battery optimization
#   ./tools/ignition-test.sh -s emulator-5554 --exempt    # app exempt (what the Settings row asks for)
#
# On Android 12+ the two runs behave differently on purpose: a start from the background is refused
# unless the app is exempt. Without --exempt the test passes when the refusal is reported clearly.
#
# It drives the real Settings screen to turn the feature on and enter two made-up ACC actions, then:
#   1. custom ACC-on / ACC-off broadcast, app in the background
#   2. car mode on / off
#   3. screen sleep / wake
#   4. QUICKBOOT_POWERON and BOOT_COMPLETED sent to a process that was killed
# and reads what the app logged. It states what it saw; it cannot say what a particular unit sends.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.ejjat.aiplay
SERIAL=""
APK=""
EXEMPT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --exempt) EXEMPT=1; shift ;;
    -s|--serial) SERIAL="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
[ -z "$APK" ] && APK="$(ls -1t "$ROOT"/build/AiPlay-*.apk 2>/dev/null | head -1)"
OUT="$ROOT/build/ignition"; rm -rf "$OUT"; mkdir -p "$OUT"
ON=com.ejjat.test.ACC_ON
OFF=com.ejjat.test.ACC_OFF
PASS=0; FAIL=0

log() { "${ADB[@]}" logcat -d -v brief -s AiPlay:D 2>/dev/null | sed 's/^D\/AiPlay *([0-9]*): //'; }
check() { # name, ok(0/1), detail
  if [ "$2" = "0" ]; then PASS=$((PASS+1)); echo "IGNITION CHECK ok   $1"; else FAIL=$((FAIL+1)); echo "IGNITION CHECK FAIL $1 — $3"; fi
}
state() { log | grep "receiver: state" | tail -1 | sed 's/.*-> *//' | cut -d: -f1; }
wait_state() { # state, seconds: waits for a "receiver: state X -> <state>" line in the (freshly cleared) log
  for _ in $(seq 1 $(( $2 * 2 ))); do
    log | grep "receiver: state" | grep -q -- "-> $1"  && return 0
    sleep 0.5
  done
  return 1
}
dump_ui() { "${ADB[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; "${ADB[@]}" pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; }
# centre of the first node whose text contains $1 ; with "switch" as $2, the Switch on the same row
find_xy() {
  python3 - "$OUT/ui.xml" "$1" "${2:-}" "${EXACT:-}" <<'PY'
import re, sys
x = open(sys.argv[1], errors="replace").read()
# uiautomator writes text before class before bounds
nodes = [(c, t, l, tp, r, b) for t, c, l, tp, r, b in re.findall(
    r'<node[^>]*?text="([^"]*)"[^>]*?class="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)]
target = sys.argv[2].lower()
want_switch = sys.argv[3] == "switch"
exact = sys.argv[4] == "1"
label = None
for cls, text, l, t, r, b in nodes:
    if (text.lower() == target) if exact else (target in text.lower()):
        label = (int(l), int(t), int(r), int(b)); break
if not label:
    sys.exit(1)
cy = (label[1] + label[3]) // 2
if want_switch:
    best = None
    for m in re.finditer(r'<node[^>]*?class="android.widget.Switch"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
        l, t, r, b = map(int, m.groups())
        if abs((t + b) // 2 - cy) < 120 and (best is None or abs((t + b) // 2 - cy) < best[0]):
            best = (abs((t + b) // 2 - cy), (l + r) // 2, (t + b) // 2)
    if not best:
        sys.exit(1)
    print(best[1], best[2])
else:
    print((label[0] + label[2]) // 2, cy)
PY
}
tap_text() { # text [switch] ; scrolls down up to 6 times looking for it
  for _ in 1 2 3 4 5 6; do
    dump_ui
    if XY=$(find_xy "$1" "${2:-}"); then "${ADB[@]}" shell input tap $XY; sleep 1; return 0; fi
    "${ADB[@]}" shell input swipe 540 1500 540 500 300; sleep 1
  done
  return 1
}

echo "== device =="; "${ADB[@]}" get-state >/dev/null 2>&1 || { echo "no device" >&2; exit 1; }
echo "model: $("${ADB[@]}" shell getprop ro.product.model | tr -d '\r') · Android $("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r') (API $("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r'))"
"${ADB[@]}" install -r "$APK" >/dev/null 2>&1 || { echo "install failed" >&2; exit 1; }
"${ADB[@]}" shell pm clear $PKG >/dev/null
for p in POST_NOTIFICATIONS BLUETOOTH_CONNECT ACCESS_FINE_LOCATION; do "${ADB[@]}" shell pm grant $PKG android.permission.$p 2>/dev/null; done
"${ADB[@]}" shell dumpsys deviceidle whitelist $([ "$EXEMPT" = 1 ] && echo "+$PKG" || echo "-$PKG") >/dev/null 2>&1
"${ADB[@]}" shell dumpsys deviceidle whitelist | grep -q "$PKG"
echo "battery-optimization exemption for $PKG: $([ $? = 0 ] && echo yes || echo no)"
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am start -W -n $PKG/.ui.SplashActivity >/dev/null
sleep 6

echo "== configure through the real Settings screen =="
tap_text "Settings" || { echo "could not open Settings" >&2; exit 1; }
sleep 2
tap_text "Start when the ignition turns on" switch; check "enabled 'Start when the ignition turns on' in Settings" $? "switch not found"
tap_text "Stop when the ignition turns off" switch; check "enabled 'Stop when the ignition turns off' in Settings" $? "switch not found"
tap_text "ACC signals from this unit"; check "opened the ACC signals dialog" $? "row not found"
type_into() { # which EditText (1-based), text: re-measure first, because the keyboard moves the dialog
  dump_ui
  XY=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys
x = open(sys.argv[1], errors="replace").read()
boxes = [tuple(map(int, m.groups())) for m in re.finditer(
    r'<node[^>]*?class="android.widget.EditText"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)]
l, t, r, b = boxes[int(sys.argv[2]) - 1]
print((l + r) // 2, (t + b) // 2)
PY
)
  "${ADB[@]}" shell input tap $XY; sleep 1
  "${ADB[@]}" shell input text "$2"; sleep 1
}
sleep 1
type_into 1 "$ON"
type_into 2 "$OFF"
EXACT=1 tap_text "OK"; sleep 1
log | grep "ignition: listening" | tail -1
log | grep -q "ignition: listening for .*$ON.*$OFF"; check "the typed ACC actions are being listened for" $? "no 'ignition: listening' line with both actions"
"${ADB[@]}" shell input keyevent KEYCODE_HOME; sleep 2
# Android keeps an app that was just on screen "foreground enough" to start a foreground service for a
# while after it leaves; a start inside that grace period succeeds without any exemption. Wait it out,
# so what follows tests a genuinely background app.
echo "waiting 30 s so the app is really in the background..."
sleep 30
check "receiver is stopped before the signals start" "$([ "$(state)" = "" ] || [ "$(state)" = "STOPPED" ]; echo $?)" "state=$(state)"

API=$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')
RESTRICTED=0; [ "$API" -ge 31 ] && RESTRICTED=1

# A start from the background: allowed on Android < 12 and for an exempt app, refused otherwise.
expect_start() { # label
  if [ "$EXEMPT" = 1 ] || [ "$RESTRICTED" = 0 ]; then
    wait_state RUNNING 15
    check "$1 started the receiver" $? "state=$(state): $(log | grep 'receiver: state' | tail -1)"
  else
    wait_state FAILED 15
    log | grep "state STARTING -> FAILED" | tail -1 | cut -c1-200
    log | grep "state STARTING -> FAILED" | tail -1 | grep -q "background"
    check "$1 was decided on; Android refused the background start and the message says why" $? "$(log | grep 'receiver: state' | tail -2 | tr '\n' ' ')"
  fi
}
expect_stopped() { # label
  if [ "$EXEMPT" = 1 ] || [ "$RESTRICTED" = 0 ]; then
    wait_state STOPPED 10
    check "$1 stopped the receiver" $? "state=$(state)"
  else
    echo "(skipped $1: nothing was running, the start had been refused)"
  fi
}
quiet() { sleep 11; "${ADB[@]}" logcat -c; }    # outlast the policy's wake-up debounce, start a fresh log

echo "== 1. the unit's own ACC broadcast, app in the background =="
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am broadcast -a $ON >/dev/null
sleep 1
log | grep "ignition:" | tail -1
expect_start "ACC-on broadcast"
sleep 1
"${ADB[@]}" shell am broadcast -a $OFF >/dev/null
expect_stopped "ACC-off broadcast"

echo "== 2. car mode =="
quiet
CAR_OUT=$("${ADB[@]}" shell cmd uimode car yes 2>&1)
if echo "$CAR_OUT" | grep -q "Unknown command"; then
  # `cmd uimode car` arrived in Android 12; on older Android there is no shell way to enter car mode.
  echo "(skipped car mode: this Android has no 'cmd uimode car' to trigger it from a shell)"
else
  expect_start "entering car mode"
  "${ADB[@]}" shell cmd uimode car no >/dev/null 2>&1
  expect_stopped "leaving car mode"
fi

echo "== 3. screen sleep and wake =="
quiet
"${ADB[@]}" shell input keyevent KEYCODE_SLEEP; sleep 2
"${ADB[@]}" shell input keyevent KEYCODE_WAKEUP; sleep 1
expect_start "waking the screen"
"${ADB[@]}" shell am broadcast -a $OFF >/dev/null; sleep 2

echo "== 4. fast-boot resume and boot sent to a killed process =="
quiet
"${ADB[@]}" shell am kill $PKG; sleep 2
"${ADB[@]}" shell pidof $PKG >/dev/null 2>&1; echo "process alive before broadcast: $([ $? = 0 ] && echo yes || echo no)"
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am broadcast -a android.intent.action.QUICKBOOT_POWERON -p $PKG >/dev/null
sleep 3
log | grep -E "app:|ignition:|boot:" | head -4
check "QUICKBOOT_POWERON woke a dead process and was decided on" "$(log | grep -q 'ignition: android.intent.action.QUICKBOOT_POWERON -> START'; echo $?)" "no decision logged"
expect_start "QUICKBOOT_POWERON"
"${ADB[@]}" shell am broadcast -a $OFF >/dev/null; sleep 2

echo "== 5. a real reboot (BOOT_COMPLETED from the system) =="
# `am broadcast -a BOOT_COMPLETED` is not a boot: Android only treats the system's own boot broadcast as
# exempt from the background-start restriction, so test the real thing.
"${ADB[@]}" reboot
"${ADB[@]}" wait-for-device
for _ in $(seq 1 90); do
  [ "$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 3
done
echo "booted; waiting for the app to react"
sleep 8
log | grep -E "app:|ignition:|boot:|receiver: state" | head -6
wait_state RUNNING 30
check "a real BOOT_COMPLETED started the receiver" $? "$(log | grep -E 'boot:|ignition:|receiver: state' | tail -3 | tr '\n' ' ')"

"${ADB[@]}" shell am broadcast -a $OFF >/dev/null
echo
echo "mode: $([ "$EXEMPT" = 1 ] && echo 'app exempt from battery optimization' || echo 'app NOT exempt') on API $API"
echo "ignition checks: $PASS ok, $FAIL failed"
[ "$FAIL" = "0" ]
