package com.ejjat.aiplay.device;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.ejjat.aiplay.core.DeviceProfile;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Collects {@link DeviceProfile} facts. Every read is bounded: package manager lookups, Build
 * constants, one {@code su} probe and one loopback bind. Budget: 300 ms.
 */
public final class CapabilityProbe {

    public static final String GEARHEAD = "com.google.android.projection.gearhead";
    public static final String GMS = "com.google.android.gms";
    public static final String SHIZUKU = "moe.shizuku.privileged.api";

    private CapabilityProbe() {
    }

    public static DeviceProfile probe(Context context) {
        long started = System.currentTimeMillis();
        Context app = context.getApplicationContext();
        DeviceProfile profile = new DeviceProfile();

        profile.manufacturer = safe(Build.MANUFACTURER);
        profile.brand = safe(Build.BRAND);
        profile.model = safe(Build.MODEL);
        profile.device = safe(Build.DEVICE);
        profile.romFingerprint = safe(Build.FINGERPRINT);
        profile.romDisplay = safe(Build.DISPLAY);
        profile.sdkInt = Build.VERSION.SDK_INT;
        profile.previewSdk = Build.VERSION.PREVIEW_SDK_INT;
        String[] abis = Build.SUPPORTED_ABIS;
        profile.abi = abis != null && abis.length > 0 ? abis[0] : "";

        PackageManager pm = app.getPackageManager();

        ApplicationInfo gearhead = applicationInfo(pm, GEARHEAD);
        profile.gearheadInstalled = gearhead != null;
        profile.gearheadEnabled = gearhead != null && gearhead.enabled;
        if (gearhead != null) {
            PackageInfo info = packageInfo(pm, GEARHEAD);
            if (info != null) {
                profile.gearheadVersionCode = info.versionCode;
                profile.gearheadVersionName = info.versionName == null ? "" : info.versionName;
            }
            profile.gearheadHasLauncher = pm.getLaunchIntentForPackage(GEARHEAD) != null;
        }

        ApplicationInfo gms = applicationInfo(pm, GMS);
        profile.gmsInstalled = gms != null;
        profile.gmsEnabled = gms != null && gms.enabled;

        profile.shizukuInstalled = applicationInfo(pm, SHIZUKU) != null;
        profile.usbHost = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        profile.wifiDirect = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
        profile.bluetooth = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);

        profile.adbEnabled = globalInt(app, Settings.Global.ADB_ENABLED, 0) == 1;
        profile.adbWifiEnabled = globalInt(app, "adb_wifi_enabled", 0) == 1;

        DisplayMetrics metrics = displayMetrics(app);
        profile.densityDpi = metrics.densityDpi;
        profile.screenWidthDp = Math.round(metrics.widthPixels / metrics.density);
        profile.screenHeightDp = Math.round(metrics.heightPixels / metrics.density);

        profile.receiverPort = Prefs.receiverPort();
        profile.receiverPortFree = portFree(profile.receiverPort);

        profile.suAvailable = PrivilegedShell.hasRoot();
        profile.suDetail = profile.suAvailable ? "su granted" : "no su binary";
        profile.probedAtMillis = System.currentTimeMillis();

        Diag.log("probe", "audit finished in " + (profile.probedAtMillis - started) + " ms: "
                + profile.describe());
        return profile;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static ApplicationInfo applicationInfo(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException notFound) {
            return null;
        } catch (RuntimeException error) {
            Diag.log("probe", "getApplicationInfo(" + pkg + ") failed", error);
            return null;
        }
    }

    private static PackageInfo packageInfo(PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0);
        } catch (Exception notFound) {
            return null;
        }
    }

    private static int globalInt(Context context, String key, int fallback) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), key, fallback);
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static DisplayMetrics displayMetrics(Context context) {
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            WindowManager windowManager =
                    (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windowManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                windowManager.getDefaultDisplay().getRealMetrics(metrics);
            } else if (windowManager != null) {
                windowManager.getDefaultDisplay().getMetrics(metrics);
            }
        } catch (RuntimeException error) {
            Diag.log("probe", "display metrics failed", error);
        }
        if (metrics.density <= 0f) {
            metrics.density = context.getResources().getDisplayMetrics().density;
            metrics.densityDpi = context.getResources().getDisplayMetrics().densityDpi;
            metrics.widthPixels = context.getResources().getDisplayMetrics().widthPixels;
            metrics.heightPixels = context.getResources().getDisplayMetrics().heightPixels;
        }
        return metrics;
    }

    private static boolean portFree(int port) {
        ServerSocket socket = null;
        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            return true;
        } catch (IOException inUse) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
    }
}
