package com.ejjat.aiplay.core.bt;

import com.ejjat.aiplay.core.aa.ProtoWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The Bluetooth half of wireless Android Auto: how a phone learns where to connect.
 *
 * <p>The head unit exposes an RFCOMM service with UUID {@link #SERVICE_UUID}. The phone connects to
 * it and the two exchange a handful of small messages over that byte stream:</p>
 *
 * <pre>
 * head unit                                   phone
 *   | WifiStartRequest  {ip, port} ----------->|   where the head unit's TCP server is
 *   |<-------------------- WifiInfoRequest ----|
 *   | WifiInfoResponse  {ssid, key, bssid,..} >|   which Wi-Fi network to join
 *   |<-- two more messages (version / connect status): read and logged, not answered
 *   |      ... phone joins Wi-Fi and opens TCP to ip:port (the AA transport) ...
 * </pre>
 *
 * <p>Sources: the sequence, message ids, framing and the two protos were read from
 * nisargjhaveri/WirelessAndroidAutoDongle ({@code bluetoothProfiles.cpp},
 * {@code proto/WifiStartRequest.proto}, {@code proto/WifiInfoResponse.proto}) and the UUID and
 * RFCOMM server role from its {@code bluetoothHandler.cpp}. aasdk and OpenAuto do not implement
 * this exchange (OpenAuto's {@code AndroidBluetoothServer} only accepts the connection). One
 * independent implementation is a thinner basis than the aasdk-based work elsewhere; this is
 * documented in {@code docs/PROTOCOL.md}.</p>
 *
 * <p>Frame: {@code [payload size u16 BE][message id u16 BE][protobuf]}; the size does not count the
 * four header bytes. Pure Java: it runs on a desktop JVM in tests and over a
 * {@code BluetoothSocket}'s streams on Android.</p>
 */
public final class WifiBootstrap {

    /** The Android Auto wireless RFCOMM service. */
    public static final String SERVICE_UUID = "4de17a00-52cb-11e6-bdf4-0800200c9a66";

    // MessageId in the dongle's AAWirelessLauncher.
    public static final int WIFI_START_REQUEST = 1;
    public static final int WIFI_INFO_REQUEST = 2;
    public static final int WIFI_INFO_RESPONSE = 3;
    public static final int WIFI_VERSION_REQUEST = 4;
    public static final int WIFI_VERSION_RESPONSE = 5;
    public static final int WIFI_CONNECT_STATUS = 6;
    public static final int WIFI_START_RESPONSE = 7;

    /** {@code SecurityMode} in WifiInfoResponse.proto. */
    public static final int SECURITY_OPEN = 1;
    public static final int SECURITY_WPA2_PERSONAL = 8;
    public static final int SECURITY_WPA_WPA2_PERSONAL = 12;

    /** {@code AccessPointType}: STATIC = 0, DYNAMIC = 1. */
    public static final int AP_STATIC = 0;
    public static final int AP_DYNAMIC = 1;

    private static final int MAX_PAYLOAD = 4096;

    private WifiBootstrap() {
    }

    /** What the head unit tells the phone. */
    public static final class Config {
        /** IPv4 address of the head unit's AA TCP server. */
        public String ipAddress = "";
        public int port = 5277;
        public String ssid = "";
        public String key = "";
        public String bssid = "";
        public int securityMode = SECURITY_WPA2_PERSONAL;
        public int accessPointType = AP_STATIC;

        /** Null when usable, otherwise why the phone probably cannot act on this configuration. */
        public String problem() {
            if (ipAddress.length() == 0) {
                return "no IP address for the receiver (is the unit on Wi-Fi?)";
            }
            if (port <= 0 || port > 65535) {
                return "receiver port out of range";
            }
            if (ssid.length() == 0) {
                return "no Wi-Fi network name configured (Settings > Wi-Fi for the phone)";
            }
            if (securityMode != SECURITY_OPEN && key.length() < 8) {
                return "Wi-Fi password shorter than 8 characters";
            }
            return null;
        }
    }

    public static String name(int id) {
        switch (id) {
            case WIFI_START_REQUEST: return "WifiStartRequest";
            case WIFI_INFO_REQUEST: return "WifiInfoRequest";
            case WIFI_INFO_RESPONSE: return "WifiInfoResponse";
            case WIFI_VERSION_REQUEST: return "WifiVersionRequest";
            case WIFI_VERSION_RESPONSE: return "WifiVersionResponse";
            case WIFI_CONNECT_STATUS: return "WifiConnectStatus";
            case WIFI_START_RESPONSE: return "WifiStartResponse";
            default: return "UNKNOWN_" + id;
        }
    }

    /** {@code WifiStartRequest}: {@code ip_address = 1} (string), {@code port = 2} (int32). */
    public static byte[] startRequest(String ipAddress, int port) {
        return new ProtoWriter().string(1, ipAddress).varint(2, port).toByteArray();
    }

    /**
     * {@code WifiInfoResponse}: {@code ssid = 1, key = 2, bssid = 3} (strings),
     * {@code security_mode = 4, access_point_type = 5} (enums). All five are {@code required} in
     * proto2, so zero values (STATIC = 0) are still written.
     */
    public static byte[] infoResponse(Config config) {
        return new ProtoWriter()
                .string(1, config.ssid)
                .string(2, config.key)
                .string(3, config.bssid)
                .varint(4, config.securityMode)
                .varint(5, config.accessPointType)
                .toByteArray();
    }

    /** {@code [size u16 BE][id u16 BE][payload]}. */
    public static byte[] frame(int messageId, byte[] payload) {
        if (payload.length > 0xFFFF) {
            throw new IllegalArgumentException("payload too large: " + payload.length);
        }
        byte[] out = new byte[4 + payload.length];
        out[0] = (byte) (payload.length >>> 8);
        out[1] = (byte) payload.length;
        out[2] = (byte) (messageId >>> 8);
        out[3] = (byte) messageId;
        System.arraycopy(payload, 0, out, 4, payload.length);
        return out;
    }

    /** One message from the phone. */
    public static final class Message {
        public final int id;
        public final byte[] payload;

        Message(int id, byte[] payload) {
            this.id = id;
            this.payload = payload;
        }

        @Override
        public String toString() {
            return name(id) + "(" + payload.length + "B)";
        }
    }

    /** Incremental parser for the RFCOMM byte stream. */
    public static final class Reader {
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        public void feed(byte[] data, int offset, int length, List<Message> out)
                throws IOException {
            pending.write(data, offset, length);
            byte[] all = pending.toByteArray();
            int position = 0;
            while (all.length - position >= 4) {
                int size = ((all[position] & 0xFF) << 8) | (all[position + 1] & 0xFF);
                int id = ((all[position + 2] & 0xFF) << 8) | (all[position + 3] & 0xFF);
                if (size > MAX_PAYLOAD) {
                    throw new IOException("implausible bootstrap message size: " + size);
                }
                if (all.length - position < 4 + size) {
                    break;
                }
                byte[] payload = new byte[size];
                System.arraycopy(all, position + 4, payload, 0, size);
                out.add(new Message(id, payload));
                position += 4 + size;
            }
            pending.reset();
            if (position < all.length) {
                pending.write(all, position, all.length - position);
            }
        }
    }

    /** Receives human-readable events for diagnostics. */
    public interface Sink {
        void onEvent(String message);
    }

    /** What happened in one bootstrap session. */
    public static final class Result {
        public boolean startRequestSent;
        public boolean infoRequested;
        public boolean infoSent;
        public final List<String> received = new ArrayList<String>();
        /** Why the phone was not given Wi-Fi details, or null. */
        public String refused;

        /** True when the phone has been told where to connect and which network to join. */
        public boolean complete() {
            return startRequestSent && infoSent;
        }
    }

    /**
     * Runs the head-unit side over an established RFCOMM connection. Returns when the phone closes
     * the stream, when it has sent two messages after the Wi-Fi details (the dongle's stopping
     * point), or when the stream throws (a socket timeout ends a silent phone).
     *
     * <p>With no Wi-Fi network name configured the details are <em>not</em> sent: an empty SSID
     * would only make the phone fail later and less visibly. Any other configuration problem is
     * logged and the details are sent anyway, since the phone may already be on the network.</p>
     */
    public static Result run(InputStream in, OutputStream out, Config config, Sink sink)
            throws IOException {
        Result result = new Result();
        Reader reader = new Reader();
        byte[] buffer = new byte[1024];
        List<Message> batch = new ArrayList<Message>();
        int afterInfo = 0;

        out.write(frame(WIFI_START_REQUEST, startRequest(config.ipAddress, config.port)));
        out.flush();
        result.startRequestSent = true;
        event(sink, "sent WifiStartRequest (ip " + config.ipAddress + ", port " + config.port + ")");

        while (true) {
            int read = in.read(buffer);
            if (read < 0) {
                event(sink, "phone closed the Bluetooth connection");
                return result;
            }
            batch.clear();
            reader.feed(buffer, 0, read, batch);
            for (Message message : batch) {
                result.received.add(message.toString());
                event(sink, "received " + message);
                if (message.id == WIFI_INFO_REQUEST && !result.infoSent) {
                    result.infoRequested = true;
                    if (config.ssid.length() == 0) {
                        result.refused = config.problem();
                        event(sink, "NOT sending Wi-Fi details: " + result.refused);
                        return result;
                    }
                    String problem = config.problem();
                    if (problem != null) {
                        event(sink, "warning: " + problem + " (sending anyway)");
                    }
                    out.write(frame(WIFI_INFO_RESPONSE, infoResponse(config)));
                    out.flush();
                    result.infoSent = true;
                    event(sink, "sent WifiInfoResponse (ssid " + config.ssid + ", bssid "
                            + (config.bssid.length() == 0 ? "-" : config.bssid) + ", security "
                            + config.securityMode + ")");
                } else if (result.infoSent && ++afterInfo >= 2) {
                    event(sink, "bootstrap finished; the phone should now join Wi-Fi and "
                            + "connect to " + config.ipAddress + ":" + config.port);
                    return result;
                }
            }
        }
    }

    private static void event(Sink sink, String message) {
        if (sink != null) {
            sink.onEvent(message);
        }
    }
}
