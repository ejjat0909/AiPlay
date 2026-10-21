package com.ejjat.aiplay.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable facts about the head unit, collected by {@code device.CapabilityProbe}.
 *
 * <p>This class is the boundary between Android and the pure evaluation logic: the probe fills it
 * in, {@link ReadinessEngine} reads it. Tests build one directly, which is why the whole decision
 * matrix can be verified on a desktop JVM.</p>
 */
public final class DeviceProfile {

    public String manufacturer = "";
    public String brand = "";
    public String model = "";
    public String device = "";
    public String romFingerprint = "";
    public String romDisplay = "";

    public int sdkInt;
    public int previewSdk;
    public String abi = "";

    public boolean gearheadInstalled;
    public boolean gearheadEnabled;
    public boolean gearheadHasLauncher;
    public int gearheadVersionCode;
    public String gearheadVersionName = "";

    public boolean gmsInstalled;
    public boolean gmsEnabled;

    public boolean suAvailable;
    public String suDetail = "";
    public boolean shizukuInstalled;

    public boolean adbEnabled;
    public boolean adbWifiEnabled;

    public boolean usbHost;
    public boolean wifiDirect;
    public boolean bluetooth;

    public int receiverPort = 5277;
    public boolean receiverPortFree;
    /** True when AiPlay's own receiver is holding the port — not a conflict, that is the goal. */
    public boolean receiverRunning;

    public int screenWidthDp;
    public int screenHeightDp;
    public int densityDpi;

    public long probedAtMillis;

    public List<Capability> capabilities() {
        List<Capability> list = new ArrayList<Capability>(10);
        // The emulator system images ship gearhead as a stub ("1.2.531830-stub"): installed and
        // enabled, but it cannot project anything. Saying "OK" there would send a rig chasing a
        // feature the image does not have.
        boolean stub = gearheadVersionName.contains("-stub");
        list.add(new Capability(Capability.Id.GEARHEAD_PACKAGE,
                !gearheadInstalled ? Capability.State.MISSING
                        : (stub ? Capability.State.WARN : Capability.State.OK),
                !gearheadInstalled ? "not installed"
                        : "installed"
                        + (gearheadVersionName.isEmpty() ? "" : " " + gearheadVersionName)
                        + (stub ? " (stub: cannot project)" : "")));
        list.add(new Capability(Capability.Id.GEARHEAD_ENABLED,
                !gearheadInstalled ? Capability.State.UNKNOWN
                        : (gearheadEnabled ? Capability.State.OK : Capability.State.MISSING),
                !gearheadInstalled ? "n/a" : (gearheadEnabled ? "enabled" : "disabled for user 0")));
        list.add(new Capability(Capability.Id.GEARHEAD_LAUNCHER,
                !gearheadInstalled ? Capability.State.UNKNOWN
                        : (gearheadHasLauncher ? Capability.State.OK : Capability.State.WARN),
                !gearheadInstalled ? "n/a"
                        : (gearheadHasLauncher ? "launcher entry"
                                : "no icon; open from Settings > Connected devices")));
        list.add(new Capability(Capability.Id.PLAY_SERVICES,
                gmsInstalled && gmsEnabled ? Capability.State.OK
                        : (gmsInstalled ? Capability.State.WARN : Capability.State.MISSING),
                gmsInstalled ? (gmsEnabled ? "present" : "present but disabled") : "not installed"));
        list.add(new Capability(Capability.Id.ROOT_SHELL,
                suAvailable ? Capability.State.OK : Capability.State.MISSING,
                suAvailable ? "su granted" : (suDetail.isEmpty() ? "no su binary" : suDetail)));
        list.add(new Capability(Capability.Id.SHIZUKU,
                shizukuInstalled ? Capability.State.WARN : Capability.State.UNKNOWN,
                shizukuInstalled ? "installed, binding in 0.2" : "not installed"));
        list.add(new Capability(Capability.Id.ADB_WIRELESS,
                adbWifiEnabled ? Capability.State.OK : Capability.State.UNKNOWN,
                adbWifiEnabled ? "enabled" : (adbEnabled ? "usb adb only" : "disabled")));
        list.add(new Capability(Capability.Id.USB_HOST,
                usbHost ? Capability.State.OK : Capability.State.MISSING,
                usbHost ? "supported" : "not reported"));
        list.add(new Capability(Capability.Id.WIFI_DIRECT,
                wifiDirect ? Capability.State.OK : Capability.State.WARN,
                wifiDirect ? "supported" : "not reported"));
        list.add(new Capability(Capability.Id.BLUETOOTH,
                bluetooth ? Capability.State.OK : Capability.State.WARN,
                bluetooth ? "supported" : "not reported"));
        list.add(new Capability(Capability.Id.RECEIVER_PORT,
                (receiverRunning || receiverPortFree) ? Capability.State.OK : Capability.State.WARN,
                receiverRunning ? "listening (own receiver) tcp " + receiverPort
                        : "tcp " + receiverPort + (receiverPortFree ? " free" : " in use")));
        return list;
    }

    public String describe() {
        return manufacturer + " " + model + " (" + device + ") · Android " + sdkInt
                + " · " + screenWidthDp + "x" + screenHeightDp + "dp";
    }
}
