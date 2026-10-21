# Compatibility

## 1. The five conditions

| Code | Condition | Detected by | AiPlay's action |
| --- | --- | --- | --- |
| **P1** | Android Auto installed and enabled (a launcher entry is **not** required) | `getApplicationInfo().enabled` | Open it, or jump to its App info page |
| **P2** | Installed but disabled for user 0 | `enabled == false` | `pm enable --user 0 …` + `pm grant` |
| **P3** | *Legacy in 0.1.0 only:* enabled but no launcher entry | enabled but no launch intent | — 0.1.1 no longer produces this, see below |
| **P4** | No package, Play services present | package lookup | Wireless receiver (experimental) |
| **P5** | No package, no Play services | package lookup | Wireless receiver (experimental) |

**Why P3 was retired (0.1.1).** Measured on an OPPO CPH2739 (Android 16, Android Auto
15.6.654484-release): gearhead is installed and enabled but exposes **no launcher
activity** — that is normal for modern Android Auto, which is reached through
Settings › Connected devices. 0.1.0 read the missing icon as "hidden" and pushed a
healthy install into *Repair* (which needs root and changes nothing). The launcher
entry is now reported as an informational capability row (`Android Auto icon`), never
as a verdict driver.

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
| Units where the launcher hides Gearhead | P1 (was P3) | Open; the icon is not needed |
| Genuine AA head units | P1 | Open |

## 4. Known limits

* **No GMS, no Android Auto.** If the ROM has neither Gearhead nor Play services, the
  enable path is meaningless. Only the receiver can help, and the receiver is experimental.
* **System-app requirement.** On many builds the projection role needs a privileged
  install. AiPlay will say "a system install is required" rather than pretending a
  user-space install is enough.
* **Package renaming.** Some vendors rename Gearhead. AiPlay reports "not installed"
  rather than guessing at a variant it has not verified.
* **Multi-user.** Commands target `--user 0`, except the on-device inspect, which uses the
  user AiPlay itself runs as. Secondary profiles are out of scope.
* **`--user` is mandatory.** `pm`/`cmd package` default to `--user current` (`-2`), which
  needs `INTERACT_ACROSS_USERS`; an app uid gets
  `Permission Denial: … uid u0a663 … requires INTERACT_ACROSS_USERS`. Every command
  AiPlay builds therefore carries an explicit user id.

## 5. ADB cookbook

```sh
adb devices                                   # confirm the unit is visible
adb shell pm list packages -d | grep -i gearhead
adb shell pm enable --user 0 com.google.android.projection.gearhead
adb shell pm grant com.google.android.projection.gearhead android.permission.ACCESS_FINE_LOCATION
adb shell cmd package resolve-activity --brief --user 0 \
  -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
  -p com.google.android.projection.gearhead
adb reboot
```

Step 4 answers "No activity found" on most modern ROMs and that is **fine** — Android
Auto 15.x ships no launcher entry. Use it to confirm the package resolves at all, not to
decide whether AA works.

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
