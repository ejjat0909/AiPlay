package com.ejjat.aiplay.core.aa;

/**
 * Messages of an Android Auto audio/video channel (video in 0.2.0).
 *
 * <p>Every id and field number below was read from the aasdk protobuf sources
 * (f1xpl/aasdk, GPL-3.0) rather than recalled: {@code AVChannelMessageIdsEnum.proto},
 * {@code AVChannelSetupRequestMessage.proto}, {@code AVChannelSetupResponseMessage.proto},
 * {@code AVChannelSetupStatusEnum.proto}, {@code AVChannelStartIndicationMessage.proto},
 * {@code AVMediaAckIndicationMessage.proto}, {@code VideoFocusRequestMessage.proto},
 * {@code VideoFocusIndicationMessage.proto} and {@code VideoFocusModeEnum.proto}.</p>
 *
 * <p>These are <em>channel-specific</em> messages: they travel on the video channel with the
 * control flag clear, so their ids never collide with {@link AaMessageType}.</p>
 */
public final class AaAv {

    // AVChannelMessage.Enum
    public static final int MEDIA_WITH_TIMESTAMP = 0x0000;
    public static final int MEDIA = 0x0001;
    public static final int SETUP_REQUEST = 0x8000;
    public static final int START_INDICATION = 0x8001;
    public static final int STOP_INDICATION = 0x8002;
    public static final int SETUP_RESPONSE = 0x8003;
    public static final int MEDIA_ACK = 0x8004;
    public static final int AV_INPUT_OPEN_REQUEST = 0x8005;
    public static final int AV_INPUT_OPEN_RESPONSE = 0x8006;
    public static final int VIDEO_FOCUS_REQUEST = 0x8007;
    public static final int VIDEO_FOCUS_INDICATION = 0x8008;

    /** {@code AVChannelSetupStatus.Enum}: NONE = 0, FAIL = 1, OK = 2 (note: OK is 2, not 1). */
    public static final int SETUP_FAIL = 1;
    public static final int SETUP_OK = 2;

    /** {@code VideoFocusMode.Enum}: NONE = 0, FOCUSED = 1, UNFOCUSED = 2. */
    public static final int FOCUS_FOCUSED = 1;
    public static final int FOCUS_UNFOCUSED = 2;

    private AaAv() {
    }

    public static String name(int id) {
        switch (id) {
            case MEDIA_WITH_TIMESTAMP: return "MEDIA_WITH_TIMESTAMP";
            case MEDIA: return "MEDIA";
            case SETUP_REQUEST: return "SETUP_REQUEST";
            case START_INDICATION: return "START_INDICATION";
            case STOP_INDICATION: return "STOP_INDICATION";
            case SETUP_RESPONSE: return "SETUP_RESPONSE";
            case MEDIA_ACK: return "MEDIA_ACK";
            case AV_INPUT_OPEN_REQUEST: return "AV_INPUT_OPEN_REQUEST";
            case AV_INPUT_OPEN_RESPONSE: return "AV_INPUT_OPEN_RESPONSE";
            case VIDEO_FOCUS_REQUEST: return "VIDEO_FOCUS_REQUEST";
            case VIDEO_FOCUS_INDICATION: return "VIDEO_FOCUS_INDICATION";
            default: return String.format("0x%04X", id);
        }
    }

    /** {@code AVChannelSetupResponse}: {@code media_status = 1, max_unacked = 2, configs = 3*}. */
    public static byte[] setupResponse(int status, int maxUnacked, int... configs) {
        ProtoWriter writer = new ProtoWriter().varint(1, status).varint(2, maxUnacked);
        for (int config : configs) {
            writer.varint(3, config);
        }
        return writer.toByteArray();
    }

    /** {@code AVMediaAckIndication}: {@code session = 1, value = 2}. */
    public static byte[] mediaAck(int session, int value) {
        return new ProtoWriter().varint(1, session).varint(2, value).toByteArray();
    }

    /** {@code VideoFocusIndication}: {@code focus_mode = 1, unrequested = 2}. */
    public static byte[] videoFocusIndication(int focusMode, boolean unrequested) {
        return new ProtoWriter().varint(1, focusMode).bool(2, unrequested).toByteArray();
    }

    /** {@code AVChannelSetupRequest.config_index = 1}; returns -1 when the field is absent. */
    public static int parseSetupRequest(byte[] payload) {
        long[] values = readVarints(payload, 1);
        return (int) values[0];
    }

    /** {@code AVChannelStartIndication}: {@code session = 1, config = 2}. */
    public static int[] parseStartIndication(byte[] payload) {
        long[] values = readVarints(payload, 2);
        return new int[]{(int) values[0], (int) values[1]};
    }

    /** {@code VideoFocusRequest.focus_mode = 2}; returns -1 when absent. */
    public static int parseVideoFocusMode(byte[] payload) {
        long[] values = readVarints(payload, 3);
        return (int) values[1];
    }

    /**
     * Splits a media message into its timestamp and the offset where the H.264 data starts.
     * {@link #MEDIA_WITH_TIMESTAMP} carries an 8 byte big endian microsecond timestamp first;
     * {@link #MEDIA} is data only (timestamp reported as -1).
     */
    public static long mediaTimestamp(int messageId, byte[] payload) {
        if (messageId != MEDIA_WITH_TIMESTAMP || payload.length < 8) {
            return -1L;
        }
        long value = 0L;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (payload[i] & 0xFFL);
        }
        return value;
    }

    public static int mediaDataOffset(int messageId, byte[] payload) {
        return messageId == MEDIA_WITH_TIMESTAMP && payload.length >= 8 ? 8 : 0;
    }

    /** Reads the first {@code count} varint fields (numbered 1..count); missing ones are -1. */
    private static long[] readVarints(byte[] payload, int count) {
        long[] values = new long[count];
        for (int i = 0; i < count; i++) {
            values[i] = -1L;
        }
        ProtoReader reader = new ProtoReader(payload);
        try {
            while (reader.hasMore()) {
                int tag = reader.readTag();
                int field = ProtoReader.field(tag);
                if (ProtoReader.wireType(tag) == 0 && field >= 1 && field <= count) {
                    values[field - 1] = reader.readVarint();
                } else {
                    reader.skip(ProtoReader.wireType(tag));
                }
            }
        } catch (AaProtocolException ignored) {
            // A truncated message yields -1 for the fields it never reached.
        }
        return values;
    }
}
