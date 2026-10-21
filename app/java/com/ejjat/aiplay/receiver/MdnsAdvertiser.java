package com.ejjat.aiplay.receiver;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import com.ejjat.aiplay.device.Diag;

/** Advertises the receiver as an Android Auto head unit on the local network. */
public final class MdnsAdvertiser {

    public static final String SERVICE_TYPE = "_androidauto._tcp";

    private final Context context;
    private final String serviceName;
    private final int port;
    private NsdManager.RegistrationListener registration;
    private boolean registered;

    public MdnsAdvertiser(Context context, String serviceName, int port) {
        this.context = context.getApplicationContext();
        this.serviceName = serviceName;
        this.port = port;
    }

    public void start() {
        if (registered) {
            return;
        }
        NsdManager manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (manager == null) {
            Diag.log("mdns", "NsdManager unavailable on this ROM");
            return;
        }
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(serviceName);
        info.setServiceType(SERVICE_TYPE);
        info.setPort(port);

        registration = new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                registered = true;
                Diag.log("mdns", "registered " + serviceInfo.getServiceName()
                        + " " + SERVICE_TYPE + " on " + port);
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                registered = false;
                Diag.log("mdns", "registration failed (" + errorCode + ")");
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                registered = false;
                Diag.log("mdns", "unregistered");
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Diag.log("mdns", "unregistration failed (" + errorCode + ")");
            }
        };

        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (RuntimeException error) {
            Diag.log("mdns", "registerService threw", error);
        }
    }

    public void stop() {
        if (registration == null) {
            return;
        }
        try {
            NsdManager manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (manager != null) {
                manager.unregisterService(registration);
            }
        } catch (RuntimeException error) {
            Diag.log("mdns", "unregister threw", error);
        }
        registration = null;
        registered = false;
    }
}
