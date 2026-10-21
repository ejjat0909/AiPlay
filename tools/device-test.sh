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
# bare TCP connect proves nothing (and shows up in the app log as a failed TLS session). Do a real
# TLS handshake instead: it succeeds only when the receiver is up, and it ends cleanly.
if ! python3 - "$PORT" <<'PYWAIT'
import socket, ssl, sys, time
port = int(sys.argv[1])
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE
for _ in range(40):
    try:
        raw = socket.create_connection(("127.0.0.1", port), timeout=2)
        with ctx.wrap_socket(raw) as tls:
            tls.getpeercert()
        sys.exit(0)
    except (OSError, ssl.SSLError):
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
  ( sleep 2; "${ADB[@]}" exec-out screencap -p > "$SHOT" 2>/dev/null ) &
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
