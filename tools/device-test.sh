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
# Exit code 0 means the receiver completed version negotiation, service discovery and answered
# the channel open. A refused channel open is expected until the video channel exists.
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
if ! python3 - "$PORT" <<'PYWAIT'
import socket, sys, time
port = int(sys.argv[1])
for _ in range(40):
    probe = socket.socket()
    probe.settimeout(0.5)
    try:
        probe.connect(("127.0.0.1", port))
        sys.exit(0)
    except OSError:
        time.sleep(0.5)
    finally:
        probe.close()
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
if [ -f "$ROOT/tools/aa-probe.py" ]; then
  python3 "$ROOT/tools/aa-probe.py" 127.0.0.1 --port "$PORT"
  PROBE_STATUS=$?
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
echo "probe exit: $PROBE_STATUS  (0 = receiver speaks Android Auto)"
exit "$PROBE_STATUS"
