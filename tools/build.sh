#!/usr/bin/env bash
# AiPlay — Gradle-free APK build: aapt2 + javac + d8 + zipalign + apksigner.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/.android-sdk}}"
BUILD_TOOLS="${BUILD_TOOLS_VERSION:-34.0.0}"
PLATFORM="${PLATFORM_VERSION:-android-34}"
BT="$SDK/build-tools/$BUILD_TOOLS"
JAR="$SDK/platforms/$PLATFORM/android.jar"

if [ ! -x "$BT/aapt2" ]; then
  echo "Android build-tools $BUILD_TOOLS not found under $SDK" >&2
  echo "Install with: sdkmanager 'build-tools;$BUILD_TOOLS' 'platforms;$PLATFORM'" >&2
  exit 1
fi

OUT="$ROOT/build"
APP="$ROOT/app"
rm -rf "$OUT/apk" "$OUT/classes" "$OUT/dex" "$OUT/gen" "$OUT/res"
mkdir -p "$OUT/apk" "$OUT/classes" "$OUT/dex" "$OUT/gen" "$OUT/res"

VERSION_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)
VERSION_CODE=$(sed -n 's/.*android:versionCode="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)
APK="$OUT/AiPlay-$VERSION_NAME.apk"

echo "== 1/6 aapt2 compile =="
"$BT/aapt2" compile --dir "$APP/res" -o "$OUT/res/resources.zip"

echo "== 2/6 aapt2 link =="
"$BT/aapt2" link \
  -o "$OUT/apk/base.apk" \
  -I "$JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$OUT/res/resources.zip"

echo "== 3/6 javac =="
find "$APP/java" "$OUT/gen" -name '*.java' | sort > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn \
  -bootclasspath "$JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== 4/6 d8 =="
find "$OUT/classes" -name '*.class' | sort > "$OUT/classes.txt"
"$BT/d8" --release --min-api 24 --lib "$JAR" --output "$OUT/dex" @"$OUT/classes.txt"

echo "== 5/6 package =="
cp "$OUT/apk/base.apk" "$OUT/apk/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/apk/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"

echo "== 6/6 sign =="
KS="$ROOT/build/aiplay-debug.jks"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass aiplay -keypass aiplay \
    -alias aiplay -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=AiPlay Debug, O=AiPlay, C=MY" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:aiplay --key-pass pass:aiplay \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$APK" "$OUT/apk/aligned.apk"
"$BT/apksigner" verify --print-certs "$APK" | head -4

rm -f "$OUT/apk/unsigned.apk" "$OUT/apk/aligned.apk"
"$BT/aapt2" dump badging "$APK" > "$OUT/badging.txt"
SIZE=$(stat -c%s "$APK")
echo
echo "APK : $APK"
echo "size: $((SIZE / 1024)) KB"
echo "package: $(grep -m1 "^package:" "$OUT/badging.txt")"
echo "launchable: $(grep -m1 "launchable-activity" "$OUT/badging.txt")"
