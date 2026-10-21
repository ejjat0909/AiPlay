package com.ejjat.aiplay.device;

import android.content.Context;
import android.content.SharedPreferences;

/** Typed access to the handful of preferences AiPlay keeps. No accounts, no sync. */
public final class Prefs {

    private static final String FILE = "aiplay";
    private static final String KEY_SPLASH = "show_splash";
    private static final String KEY_START_ON_BOOT = "start_on_boot";
    private static final String KEY_KEEP_SCREEN_ON = "keep_screen_on";
    private static final String KEY_RECEIVER_PORT = "receiver_port";
    private static final String KEY_AUTO_START_RECEIVER = "auto_start_receiver";
    private static final String KEY_VERBOSE = "verbose_log";
    private static final String KEY_SPLASH_SEEN = "splash_seen";
    private static final String KEY_WIFI_SSID = "wifi_ssid";
    private static final String KEY_WIFI_KEY = "wifi_key";
    private static final String KEY_START_ON_IGNITION = "start_on_ignition";
    private static final String KEY_STOP_ON_IGNITION_OFF = "stop_on_ignition_off";
    private static final String KEY_ACC_ON_ACTION = "acc_on_action";
    private static final String KEY_ACC_OFF_ACTION = "acc_off_action";

    private static SharedPreferences prefs;

    private Prefs() {
    }

    public static void init(Context context) {
        if (prefs == null) {
            prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    private static SharedPreferences p() {
        if (prefs == null) {
            throw new IllegalStateException("Prefs.init() was not called");
        }
        return prefs;
    }

    public static boolean showSplash() {
        return p().getBoolean(KEY_SPLASH, true);
    }

    public static void setShowSplash(boolean value) {
        p().edit().putBoolean(KEY_SPLASH, value).apply();
    }

    public static boolean splashSeen() {
        return p().getBoolean(KEY_SPLASH_SEEN, false);
    }

    public static void markSplashSeen() {
        p().edit().putBoolean(KEY_SPLASH_SEEN, true).apply();
    }

    public static boolean startOnBoot() {
        return p().getBoolean(KEY_START_ON_BOOT, true);
    }

    public static void setStartOnBoot(boolean value) {
        p().edit().putBoolean(KEY_START_ON_BOOT, value).apply();
    }

    public static boolean keepScreenOn() {
        return p().getBoolean(KEY_KEEP_SCREEN_ON, true);
    }

    public static void setKeepScreenOn(boolean value) {
        p().edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply();
    }

    public static int receiverPort() {
        return p().getInt(KEY_RECEIVER_PORT, 5277);
    }

    public static void setReceiverPort(int port) {
        p().edit().putInt(KEY_RECEIVER_PORT, port).apply();
    }

    public static boolean autoStartReceiver() {
        return p().getBoolean(KEY_AUTO_START_RECEIVER, false);
    }

    public static void setAutoStartReceiver(boolean value) {
        p().edit().putBoolean(KEY_AUTO_START_RECEIVER, value).apply();
    }

    public static boolean verbose() {
        return p().getBoolean(KEY_VERBOSE, false);
    }

    public static void setVerbose(boolean value) {
        p().edit().putBoolean(KEY_VERBOSE, value).apply();
    }

    /** Name of the Wi-Fi network the phone is told to join over Bluetooth ("" = use the current one). */
    public static String wifiSsid() {
        return p().getString(KEY_WIFI_SSID, "");
    }

    /**
     * Password of that network. Android will not reveal the password of the network the unit is on,
     * so it is typed in once and kept in this app's private preferences (not encrypted).
     */
    public static String wifiKey() {
        return p().getString(KEY_WIFI_KEY, "");
    }

    public static void setWifi(String ssid, String key) {
        p().edit().putString(KEY_WIFI_SSID, ssid == null ? "" : ssid.trim())
                .putString(KEY_WIFI_KEY, key == null ? "" : key).apply();
    }

    /** Start the receiver when the car's ignition turns on, as far as the unit lets the app know. */
    public static boolean startOnIgnition() {
        return p().getBoolean(KEY_START_ON_IGNITION, false);
    }

    public static void setStartOnIgnition(boolean value) {
        p().edit().putBoolean(KEY_START_ON_IGNITION, value).apply();
    }

    /** Stop the receiver on an explicit ignition-off signal (car mode exit or the unit's own action). */
    public static boolean stopOnIgnitionOff() {
        return p().getBoolean(KEY_STOP_ON_IGNITION_OFF, false);
    }

    public static void setStopOnIgnitionOff(boolean value) {
        p().edit().putBoolean(KEY_STOP_ON_IGNITION_OFF, value).apply();
    }

    /** The head unit's own ACC-on broadcast action, typed in by the user ("" = none). */
    public static String accOnAction() {
        return p().getString(KEY_ACC_ON_ACTION, "");
    }

    public static String accOffAction() {
        return p().getString(KEY_ACC_OFF_ACTION, "");
    }

    public static void setAccActions(String on, String off) {
        p().edit().putString(KEY_ACC_ON_ACTION, on == null ? "" : on.trim())
                .putString(KEY_ACC_OFF_ACTION, off == null ? "" : off.trim()).apply();
    }
}
