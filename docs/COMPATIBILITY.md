# Compatibility

## 1. The five conditions

| Code | Condition | Detected by | AiPlay's action |
| --- | --- | --- | --- |
| **P1** | Android Auto installed, enabled, has a launcher entry | `getApplicationInfo().enabled` + `getLaunchIntentForPackage` | Open it |
| **P2** | Installed but disabled for user 0 | `enabled == false` | `pm enable --user 0 …` + `pm grant` |
| **P3** | Enabled but unreachable | enabled but no launch intent | `pm install-existing --user 0 …`, enable, grant |
| **P4** | No package, Play services present | package lookup | Wireless receiver (experimental) |
| **P5** | No package, no Play services | package lookup | Wireless receiver (experimental) |

The audit is re-run every time the home screen resumes, so a fix applied from a laptop is
visible immediately.

## 2. Android version support

| Android | API | Status | Notes |
| --- | --- | --- | --- |
| 7.0 – 7.1 | 24–25 | supported | minSdk; TLS 1.2, NsdManager, vector drawables |
| 8.0 – 8.1 | 26–27 | supported | notification channel, `startForegroundService` |
| 9 | 28 | supported | foreground service types |
| 10 | 29 | supported | `connectedDevice` FGS type |
| 11 – 12 | 30–31 | supported | package visibility needs `<queries>` (declared) |
| 13 – 14 | 33–34 | supported | `POST_NOTIFICATIONS` runtime prompt |

## 3. What each ROM family usually looks like

| ROM family | Typical state | What works |
| --- | --- | --- |
| Chinese aftermarket (MTK/Allwinner, no GMS) | P5 | Receiver only; ADB cannot help |
| Chinese aftermarket with GMS pre-installed | P4 | Receiver; sometimes P2 if Gearhead is present but disabled |
| "Android 10/11/12" units sold as "AA-ready" | P2 | One `pm enable` |
| Units where the launcher hides Gearhead | P3 | Repair |
| Genuine AA head units | P1 | Open |

## 4. Known limits

* **No GMS, no Android Auto.** If the ROM has neither Gearhead nor Play services, the
  enable path is meaningless. Only the receiver can help, and the receiver is experimental.
* **System-app requirement.** On many builds the projection role needs a privileged
  install. AiPlay will say "a system install is required" rather than pretending a
  user-space install is enough.
* **Package renaming.** Some vendors rename Gearhead. AiPlay reports "not installed"
  rather than guessing at a variant it has not verified.
* **Multi-user.** Commands target `--user 0`. Secondary profiles are out of scope.

## 5. ADB cookbook

```sh
adb devices                                   # confirm the unit is visible
adb shell pm list packages -d | grep -i gearhead
adb shell pm enable --user 0 com.google.android.projection.gearhead
adb shell pm grant com.google.android.projection.gearhead android.permission.ACCESS_FINE_LOCATION
adb shell cmd package resolve-activity --brief com.google.android.projection.gearhead
adb reboot
```

If step 2 prints nothing and `pm list packages | grep -i gearhead` also prints nothing,
the ROM has no Android Auto at all — go to the receiver.

### Enabling wireless ADB on the head unit (Android 11+)

```sh
# On the unit: Settings → System → Developer options → Wireless debugging → pair with code
adb pair 192.168.1.50:37xxx
adb connect 192.168.1.50:5555
```

## 6. Reporting a problem

Home → Diagnostics → **Save & share**. The report contains the device fingerprint, the
full audit, the verdict and the last 512 log lines. It never contains passwords, SSIDs,
phone names or MAC addresses.
