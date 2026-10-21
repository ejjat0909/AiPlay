#!/usr/bin/env bash
# AiPlay — JVM test suite for the pure core (no Android SDK required).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$ROOT/build/test"
LIB="$ROOT/build/lib"
JUNIT_VERSION="4.13.2"
HAMCREST_VERSION="1.3"
JUNIT="$LIB/junit-$JUNIT_VERSION.jar"
HAMCREST="$LIB/hamcrest-core-$HAMCREST_VERSION.jar"

mkdir -p "$BUILD/classes" "$LIB"

fetch() {
  local url="$1" dest="$2"
  if [ -f "$dest" ]; then return 0; fi
  echo "  fetching $(basename "$dest")"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$dest" "$url"
  else
    wget -qO "$dest" "$url"
  fi
}

fetch "https://repo1.maven.org/maven2/junit/junit/$JUNIT_VERSION/junit-$JUNIT_VERSION.jar" "$JUNIT"
fetch "https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/$HAMCREST_VERSION/hamcrest-core-$HAMCREST_VERSION.jar" "$HAMCREST"

echo "== L0: core must not depend on Android =="
if grep -rn "^import android\." "$ROOT/app/java/com/ejjat/aiplay/core" >/dev/null 2>&1; then
  echo "FAIL: core/ imports android.* — keep the decision logic pure" >&2
  grep -rn "^import android\." "$ROOT/app/java/com/ejjat/aiplay/core" >&2
  exit 1
fi
echo "  ok"

echo "== compiling core + tests (JDK only) =="
rm -rf "$BUILD/classes"
mkdir -p "$BUILD/classes"
find "$ROOT/app/java/com/ejjat/aiplay/core" "$ROOT/tests/java" -name '*.java' | sort > "$BUILD/sources.txt"
javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options \
  -cp "$JUNIT:$HAMCREST" -d "$BUILD/classes" @"$BUILD/sources.txt"

KEYSTORE="$BUILD/keystore.p12"
if [ ! -f "$KEYSTORE" ]; then
  echo "== generating throwaway TLS keystore =="
  keytool -genkeypair -keystore "$KEYSTORE" -storepass aiplay -keypass aiplay \
    -alias aiplay -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=AiPlay Test, O=AiPlay, C=MY" >/dev/null 2>&1
fi

CLASSES=$(cd "$ROOT/tests/java" && find . -name '*Test.java' | sed 's|^\./||; s|\.java$||; s|/|.|g' | sort)
echo "== running $(echo "$CLASSES" | wc -l | tr -d ' ') test classes =="
java -Daiplay.test.keystore="$KEYSTORE" -cp "$BUILD/classes:$JUNIT:$HAMCREST" \
  org.junit.runner.JUnitCore $CLASSES
