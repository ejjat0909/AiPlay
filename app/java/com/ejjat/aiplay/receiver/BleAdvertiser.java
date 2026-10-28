package com.ejjat.aiplay.receiver;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;

import com.ejjat.aiplay.core.bt.WifiBootstrap;
import com.ejjat.aiplay.device.Diag;

/**
 * Advertises the Android Auto wireless service UUID over Bluetooth LE, the way
 * WirelessAndroidAutoDongle does in its "dongle" mode, so a phone that is scanning can recognise the
 * unit as a wireless Android Auto head unit.
 *
 * <p><b>Best effort.</b> Many aftermarket head units have a Bluetooth chip with no LE peripheral mode
 * (the unit this was written for reports only {@code android.hardware.bluetooth}, not
 * {@code bluetooth_le}). Then {@link BluetoothAdapter#getBluetoothLeAdvertiser()} is null; this class
 * says so in the log and the receiver relies on the paired link and the RFCOMM service record.
 * Nothing here is allowed to break the receiver.</p>
 */
final class BleAdvertiser {

    private final Context context;
    private final BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback callback;

    BleAdvertiser(Context context, BluetoothAdapter adapter) {
        this.context = context.getApplicationContext();
        this.adapter = adapter;
    }

    /** True when advertising actually started (reported asynchronously in the log). */
    boolean start() {
        if (callback != null) {
            return true;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31
                    && context.checkSelfPermission("android.permission.BLUETOOTH_ADVERTISE") != 0) {
                Diag.log("bluetooth", "BLE advertising skipped: BLUETOOTH_ADVERTISE not granted");
                return false;
            }
            BluetoothLeAdvertiser le = adapter.getBluetoothLeAdvertiser();
            if (le == null) {
                Diag.log("bluetooth", "BLE advertising unavailable: this unit's Bluetooth has no "
                        + "peripheral mode. Relying on the paired link and the RFCOMM service record.");
                return false;
            }
            AdvertiseSettings settings = new AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build();
            AdvertiseData data = new AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(ParcelUuid.fromString(WifiBootstrap.SERVICE_UUID))
                    .build();
            AdvertiseData scanResponse = new AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .build();
            AdvertiseCallback cb = new AdvertiseCallback() {
                @Override
                public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                    Diag.log("bluetooth", "BLE advertising started for service "
                            + WifiBootstrap.SERVICE_UUID);
                }

                @Override
                public void onStartFailure(int errorCode) {
                    Diag.log("bluetooth", "BLE advertising failed: " + describe(errorCode));
                }
            };
            callback = cb;
            advertiser = le;
            le.startAdvertising(settings, data, scanResponse, cb);
            return true;
        } catch (RuntimeException error) {
            callback = null;
            advertiser = null;
            if (error instanceof NullPointerException) {
                // The framework hands out an advertiser, but the chip's GATT service is missing.
                Diag.log("bluetooth", "BLE advertising unavailable: this unit's Bluetooth has no "
                        + "GATT service behind the advertiser (no LE peripheral mode). Relying on "
                        + "the paired link and the RFCOMM service record.");
            } else {
                Diag.log("bluetooth", "BLE advertising could not start", error);
            }
            return false;
        }
    }

    void stop() {
        BluetoothLeAdvertiser le = advertiser;
        AdvertiseCallback cb = callback;
        advertiser = null;
        callback = null;
        if (le == null || cb == null) {
            return;
        }
        try {
            le.stopAdvertising(cb);
            Diag.log("bluetooth", "BLE advertising stopped");
        } catch (RuntimeException error) {
            Diag.log("bluetooth", "BLE advertising stop failed", error);
        }
    }

    static String describe(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:
                return "data too large";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                return "too many advertisers";
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:
                return "already started";
            case AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR:
                return "internal error";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                return "feature unsupported by this Bluetooth chip";
            default:
                return "code " + code;
        }
    }
}
