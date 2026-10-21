package com.ejjat.aiplay.core.aa;

/**
 * Android Auto control message identifiers.
 *
 * <p>Values are taken from the public aasdk definition
 * {@code aasdk_proto/ControlMessageIdsEnum.proto} (f1xpl/aasdk, GPL-3.0). Keeping them in one
 * place makes the wire contract reviewable: every number here is a citation, not a guess.</p>
 */
public final class AaMessageType {

    public static final int NONE = 0x0000;
    public static final int VERSION_REQUEST = 0x0001;
    public static final int VERSION_RESPONSE = 0x0002;
    public static final int SSL_HANDSHAKE = 0x0003;
    public static final int AUTH_COMPLETE = 0x0004;
    public static final int SERVICE_DISCOVERY_REQUEST = 0x0005;
    public static final int SERVICE_DISCOVERY_RESPONSE = 0x0006;
    public static final int CHANNEL_OPEN_REQUEST = 0x0007;
    public static final int CHANNEL_OPEN_RESPONSE = 0x0008;
    public static final int PING_REQUEST = 0x000b;
    public static final int PING_RESPONSE = 0x000c;
    public static final int NAVIGATION_FOCUS_REQUEST = 0x000d;
    public static final int NAVIGATION_FOCUS_RESPONSE = 0x000e;
    public static final int SHUTDOWN_REQUEST = 0x000f;
    public static final int SHUTDOWN_RESPONSE = 0x0010;
    public static final int VOICE_SESSION_REQUEST = 0x0011;
    public static final int AUDIO_FOCUS_REQUEST = 0x0012;
    public static final int AUDIO_FOCUS_RESPONSE = 0x0013;

    private AaMessageType() {
    }

    /** Human readable name for diagnostics. Unknown ids are reported as hex, never invented. */
    public static String name(int type) {
        switch (type) {
            case NONE: return "NONE";
            case VERSION_REQUEST: return "VERSION_REQUEST";
            case VERSION_RESPONSE: return "VERSION_RESPONSE";
            case SSL_HANDSHAKE: return "SSL_HANDSHAKE";
            case AUTH_COMPLETE: return "AUTH_COMPLETE";
            case SERVICE_DISCOVERY_REQUEST: return "SERVICE_DISCOVERY_REQUEST";
            case SERVICE_DISCOVERY_RESPONSE: return "SERVICE_DISCOVERY_RESPONSE";
            case CHANNEL_OPEN_REQUEST: return "CHANNEL_OPEN_REQUEST";
            case CHANNEL_OPEN_RESPONSE: return "CHANNEL_OPEN_RESPONSE";
            case PING_REQUEST: return "PING_REQUEST";
            case PING_RESPONSE: return "PING_RESPONSE";
            case NAVIGATION_FOCUS_REQUEST: return "NAVIGATION_FOCUS_REQUEST";
            case NAVIGATION_FOCUS_RESPONSE: return "NAVIGATION_FOCUS_RESPONSE";
            case SHUTDOWN_REQUEST: return "SHUTDOWN_REQUEST";
            case SHUTDOWN_RESPONSE: return "SHUTDOWN_RESPONSE";
            case VOICE_SESSION_REQUEST: return "VOICE_SESSION_REQUEST";
            case AUDIO_FOCUS_REQUEST: return "AUDIO_FOCUS_REQUEST";
            case AUDIO_FOCUS_RESPONSE: return "AUDIO_FOCUS_RESPONSE";
            default: return String.format("UNKNOWN_0x%04X", type);
        }
    }

    /** True when the id is a control-channel message that AiPlay knows how to name. */
    public static boolean isKnown(int type) {
        return !name(type).startsWith("UNKNOWN_");
    }
}
