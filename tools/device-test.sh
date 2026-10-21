#!/usr/bin/env bash
# One command: prove that AiPlay's receiver speaks Android Auto.
#
# Works with any of the three rigs, because everything goes through `adb forward` instead of
# mDNS: a USB phone, a Wi-Fi phone, or an emulator. The laptop plays the phone; the target
# plays the head unit.
#
#   ./tools/device-test.sh                       # build/AiPlay-<version>.apk on the only device
#   ./tools/device-test.sh -s emulator-5554      # pick a device
#   ./tools/device-test.sh --apk x.apk --no-install
#
# Exit code 0 means the receiver completed version negotiation and service discovery, accepted the
# video channel, acked every picture of the test clip, AND the device decoded and rendered a
# picture (logged as "video: first picture rendered"). A screenshot taken mid-stream is saved to
# build/device-test-video.png so the picture can be looked at, not just logged.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=com.ejjat.aiplay
PORT=5277
SERIAL=""
APK=""
INSTALL=1

while [ $# -gt 0 ]; do
  case "$1" in
    -s|--serial) SERIAL="$2"; shift 2 ;;
    --apk) APK="$2"; shift 2 ;;
    --no-install) INSTALL=0; shift ;;
    --port) PORT="$2"; shift 2 ;;
     -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$APK" ]; then
  APK="$(ls -1t "$ROOT"/build/AiPlay-*.apk 2>/dev/null | head -1)"
fi
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "no APK found — run ./tools/build.sh first, or pass --apk <file>" >&2
  exit 1
fi

ADB=(adb)
if [ -n "$SERIAL" ]; then
  ADB=(adb -s "$SERIAL")
fi

echo "== device =="
"${ADB[@]}" devices -l | sed '/^$/d'
if ! "${ADB[@]}" get-state >/dev/null 2>&1; then
  echo "no device: start the emulator, or plug the unit in and accept the USB debugging prompt" >&2
  exit 1
fi
echo "model: $("${ADB[@]}" shell getprop ro.product.model | tr -d '\r') · Android $("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')"

if [ "$INSTALL" = "1" ]; then
  echo "== install =="
  "${ADB[@]}" install -r "$APK" || exit 1
fi

echo "== launch + receiver =="
"${ADB[@]}" logcat -c
# The first full-screen app on a device shows Android's "Viewing full screen / Got it" box over the
# picture. It is a system dialog, not an AiPlay bug, but it ruins the screenshot: pre-confirm it.
"${ADB[@]}" shell settings put secure immersive_mode_confirmations confirmed >/dev/null 2>&1
# AaReceiverService is not exported on purpose, so `am start-foreground-service` is denied for the
# shell uid. The launcher activity is exported: ask the app to start its own receiver.
"${ADB[@]}" shell am start -W -n "$PKG/.ui.SplashActivity" \
  --ez aiplay.start_receiver true >/dev/null
sleep 2

echo "== forward =="
"${ADB[@]}" forward "tcp:$PORT" "tcp:$PORT" || exit 1
echo "laptop 127.0.0.1:$PORT -> device :$PORT"

echo "== waiting for the receiver to listen =="
# `adb forward` accepts the laptop-side connection even when nothing listens on the device, so a
# bare TCP connect proves nothing. The head unit speaks first on a real session (VERSION_REQUEST,
# channel 0, flags 0x03), so wait until the first two bytes arrive: that only happens when the
# receiver is really up. (0.1.5 did a TLS handshake here; the socket no longer carries TLS.)
if ! python3 - "$PORT" <<'PYWAIT'
import socket, sys, time
port = int(sys.argv[1])
for _ in range(40):
    try:
        raw = socket.create_connection(("127.0.0.1", port), timeout=2)
        raw.settimeout(2)
        head = raw.recv(2)
        raw.close()
        if len(head) == 2 and head[0] == 0x00 and head[1] == 0x03:
            sys.exit(0)
    except OSError:
        pass
    time.sleep(0.5)
sys.exit(1)
PYWAIT
then
  echo "receiver is not listening on $PORT." >&2
  echo "open AiPlay on the device and press \"Start receiver\", then rerun with --no-install." >&2
  "${ADB[@]}" logcat -d -v brief -s AiPlay:D | tail -15 >&2
  exit 3
fi
echo "listening"

echo "== probe =="
PROBE_STATUS=2
SHOT="$ROOT/build/device-test-video.png"
rm -f "$SHOT"
if [ -f "$ROOT/tools/aa-probe.py" ]; then
  # The clip is 3 s at 30 fps: grab the screen while it is playing.
  # Take the screenshot once the first picture has been rendered (a slow decoder can need several
  # seconds), not after a fixed delay, which usually caught the home screen.
  (
    for _ in $(seq 1 60); do
      if "${ADB[@]}" logcat -d -v brief -s AiPlay:D 2>/dev/null | grep -q "video: first picture rendered"; then
        sleep 0.6
        "${ADB[@]}" exec-out screencap -p > "$SHOT" 2>/dev/null
        break
      fi
      sleep 0.5
    done
  ) &
  SHOT_PID=$!
  python3 "$ROOT/tools/aa-probe.py" 127.0.0.1 --port "$PORT"
  PROBE_STATUS=$?
  wait "$SHOT_PID" 2>/dev/null
else
  echo "tools/aa-probe.py missing" >&2
fi

echo
echo "== session log =="
"${ADB[@]}" logcat -d -v brief -s AiPlay:D | tail -25

echo
echo "== report =="
"${ADB[@]}" shell ls -l "/sdcard/Android/data/$PKG/files/reports/" 2>/dev/null | tail -5
echo
echo "== render =="
RENDER_STATUS=0
if [ "$PROBE_STATUS" = "0" ]; then
  if "${ADB[@]}" logcat -d -v brief -s AiPlay:D | grep -q "video: first picture rendered"; then
    echo "OK: the device decoded and rendered a picture"
    [ -s "$SHOT" ] && echo "screenshot: $SHOT"
  else
    echo "FAIL: the probe streamed video but no picture was rendered on the device." >&2
    echo "  - did ProjectionActivity open? (background activity starts are blocked on Android 10+)" >&2
    echo "  - look for 'video:' lines above: decoder errors are logged there" >&2
    RENDER_STATUS=4
  fi
else
  echo "skipped (the probe did not complete)"
fi

echo
echo "probe exit: $PROBE_STATUS  render exit: $RENDER_STATUS  (0/0 = receiver speaks Android Auto and shows video)"
if [ "$PROBE_STATUS" != "0" ]; then
  exit "$PROBE_STATUS"
fi
exit "$RENDER_STATUS"
