# Starting with the car (ACC / ignition)

**Android has no standard ignition signal.** A head unit that sees the ignition turn on may cold-boot,
resume from a fast-boot hibernation, wake from sleep, or do nothing visible at all, and what an app can
hear of that depends on the unit's firmware. This page says what AiPlay listens for, what was
verified, and what only your unit can answer.

## What AiPlay reacts to

| Signal | Kind | Counts as |
| --- | --- | --- |
| `BOOT_COMPLETED` | manifest, works with the process dead | on |
| `QUICKBOOT_POWERON` (Android's and HTC's spelling) | manifest, works with the process dead | on |
| `SCREEN_ON`, `USER_PRESENT` (unlock) | runtime, process must be alive | on |
| `ENTER_CAR_MODE` | runtime, process must be alive | on |
| `EXIT_CAR_MODE` | runtime, process must be alive | **off** |
| your unit's own ACC-on / ACC-off action (Settings) | runtime, process must be alive | on / **off** |

`SCREEN_OFF` is deliberately **not** an off signal: a screen timeout is not the car being switched off.

Settings:

* **Start when the ignition turns on**: any "on" signal starts the receiver (and a cold boot counts too).
* **Stop when the ignition turns off**: an "off" signal stops it. Off by default.
* **This unit's ACC signals**: the two broadcast actions your unit sends, if it sends its own.
* **Background start**: see below; on Android 12+ this is what makes it work.

The older pair, *Start with the car* and *Start the receiver at boot*, still start the receiver at boot
on their own, as before.

### Debouncing

A waking unit usually fires several of these within seconds (fast-boot, screen on, unlock). After a
start, further "on" signals for 10 s are treated as the same wake-up. An explicit "off" ends that
wake-up, so an ACC that drops while the engine cranks and returns *does* start the receiver again.
(`IgnitionPolicy`, unit-tested.)

## Android 12+: background start must be allowed

Since Android 12 an app in the background may not start a foreground service, and a start triggered
by the ignition is exactly that. On an **Android 14 emulator**:

| Signal | Not exempt from battery optimization | Exempt |
| --- | --- | --- |
| a real reboot (`BOOT_COMPLETED`) | starts | starts |
| `QUICKBOOT_POWERON` to a dead process | **refused** | starts |
| unit's own ACC action, car mode, screen wake | **refused** | starts |

A refusal is not hidden: the receiver line reads "Android does not let an app in the background start
this. In AiPlay's Settings, allow background start (battery optimization), or open AiPlay and press
Start." The Settings row **Background start** shows the state and opens the system prompt ("Let app
always run in background?") that grants the exemption. Android 11 and older have no such limit: on an **Android 11 emulator** every signal below started the
receiver with no exemption.

Also seen on the emulator: an app that was on screen a moment ago can still start a service for a short
grace period without the exemption, so a test right after using the app can pass without it.

## Limits you should expect

* **Which signal your unit sends is unknown to me.** I do not know your unit's model or Android version.
  A unit that wires ACC to nothing an app can see cannot be helped by any app.
* **Runtime signals need a live process.** If the unit kills background apps while parked, screen-on,
  unlock, car mode and a custom action are never delivered; `BOOT_COMPLETED` and `QUICKBOOT_POWERON`
  still are. Exclude AiPlay from the unit's task killer or autostart blocker if it has one. Keeping the
  receiver running (instead of stopping it on ignition-off) also keeps the process alive.
* **A custom action is exported**: any app can send it and so start or stop the receiver. That is all it
  can do.
* **No unit was tested.** Everything here comes from an Android 14 and an Android 11 emulator. Car mode could
  only be triggered on Android 14 (`cmd uimode car` does not exist on 11), so on Android 11 it is untested.

## Finding your unit's own ACC action

1. Connect `adb` to the unit.
2. With the ignition on: `adb shell dumpsys activity broadcasts | grep -o "act=[A-Za-z0-9_.]*" | sort | uniq -c > before.txt`
3. Switch the ignition off and on again (or whatever your unit does), then repeat into `after.txt`.
4. `diff before.txt after.txt`: ignore the noise (`BATTERY_CHANGED`, `CONNECTIVITY_CHANGE`, ...) and look for
   something with `acc`, `ignition`, `power`, `sleep`, `wake` in its name. Type it into Settings.

That command was checked to list recent broadcasts (it shows the car-mode ones on an emulator). The
history is limited, and a vendor may send its ACC signal only to specific apps, in which case it will not
appear there at all.
