# Build

AiPlay deliberately does not use Gradle. The whole app has no dependencies, so Gradle
would add ~1 GB of downloads, a 2 GB daemon and minutes of build time for nothing.

## 1. Requirements

| Tool | Version | Why |
| --- | --- | --- |
| JDK | 17+ | `javac`, `keytool` |
| Android SDK build-tools | 34.0.0 | `aapt2`, `d8`, `zipalign`, `apksigner` |
| Android SDK platform | android-34 | `android.jar` |
| Python 3 + Pillow | optional | regenerating launcher icons |

```sh
sdkmanager "build-tools;34.0.0" "platforms;android-34"
```

## 2. Build

```sh
export ANDROID_HOME=$HOME/Android/Sdk      # or /opt/android-sdk
./tools/build.sh
```

Output:

```
build/AiPlay-0.1.0.apk     ~217 KB
build/badging.txt          aapt2 badging dump
build/sources.txt          exact javac inputs
```

The script runs six steps: `aapt2 compile`, `aapt2 link`, `javac`, `d8`, package +
`zipalign`, `apksigner`. `BuildConfig.java` is generated into `build/gen/` from the
manifest version — it is deliberately not committed, because a stale copy is how 0.1.1
shipped a report header that still said `0.1.0 (1)`. Signing uses `build/aiplay-debug.jks`, generated on first run
(password `aiplay`) — **replace it for distribution**.

## 3. Release signing

```sh
export ANDROID_KEYSTORE=/secure/aiplay-release.jks
export ANDROID_KEYSTORE_PASSWORD=…
export ANDROID_KEY_ALIAS=aiplay
export ANDROID_KEY_PASSWORD=…
```

Wire those into `tools/build.sh`'s `apksigner` call (or pass them yourself). AiPlay ships
no release key: a keystore in a public repository is not a secret.

## 4. Tests

```sh
./tools/test.sh
```

Generates a throwaway TLS keystore with `keytool`, compiles `core/` and `tests/` against
the plain JDK, and runs 62 JUnit 4 tests. No Android SDK required beyond the JDK.

## 5. Icons

```sh
pip install pillow
python3 tools/gen-icons.py
```

Writes `app/res/mipmap-*/ic_launcher{,_round}.png` and `docs/icon.png`.

## 6. Size budget

| Component | Budget | 0.1.0 |
| --- | --- | --- |
| DEX | ≤ 260 KB | 98 KB |
| Resources (`resources.arsc`) | ≤ 40 KB | 9 KB |
| Icons | ≤ 120 KB | 79 KB |
| Native libs | 0 | 0 |
| **APK** | **≤ 1.5 MB** | **217 KB** |
