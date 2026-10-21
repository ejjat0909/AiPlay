package com.ejjat.aiplay.core.aa;

/**
 * The Android Auto sensor channel: what the head unit reports about the car.
 *
 * <p>The phone opens the channel and sends {@code SENSOR_START_REQUEST} for each sensor it wants;
 * the head unit answers {@code SENSOR_START_RESPONSE} and then pushes
 * {@code SENSOR_EVENT_INDICATION} messages. AiPlay offers the same two sensors OpenAuto does,
 * <b>driving status</b> and <b>night mode</b>; location is not offered (OpenAuto comments it out).
 * Ids and fields were read from aasdk: {@code SensorChannelMessageIdsEnum.proto},
 * {@code SensorStartRequestMessage.proto}, {@code SensorStartResponseMessage.proto},
 * {@code SensorEventIndicationMessage.proto}, {@code SensorTypeEnum.proto},
 * {@code DrivingStatusData.proto}, {@code DrivingStatusEnum.proto}, {@code NightModeData.proto}.</p>
 *
 * <p>Whether a given navigation app refuses to run without these is not known; they are sent
 * because a phone asks for them as soon as the channel opens.</p>
 */
public final class AaSensor {

    // SensorChannelMessage.Enum
    public static final int START_REQUEST = 0x8001;
    public static final int START_RESPONSE = 0x8002;
    public static final int EVENT_INDICATION = 0x8003;

    /** {@code SensorType.Enum} values used here. */
    public static final int TYPE_DRIVING_STATUS = 13;
    public static final int TYPE_NIGHT_DATA = 10;

    /** {@code DrivingStatus.Enum}: UNRESTRICTED = 0 ... FULLY_RESTRICTED = 31. */
    public static final int DRIVING_UNRESTRICTED = 0;

    private AaSensor() {
    }

    public static String name(int id) {
        switch (id) {
            case START_REQUEST: return "SENSOR_START_REQUEST";
            case START_RESPONSE: return "SENSOR_START_RESPONSE";
            case EVENT_INDICATION: return "SENSOR_EVENT_INDICATION";
            default: return String.format("0x%04X", id);
        }
    }

    /** {@code SensorStartRequest.sensor_type = 1}; -1 when absent. */
    public static int parseStartRequest(byte[] payload) {
        int type = -1;
        ProtoReader reader = new ProtoReader(payload);
        try {
            while (reader.hasMore()) {
                int tag = reader.readTag();
                if (ProtoReader.field(tag) == 1 && ProtoReader.wireType(tag) == 0) {
                    type = (int) reader.readVarint();
                } else {
                    reader.skip(ProtoReader.wireType(tag));
                }
            }
        } catch (AaProtocolException ignored) {
            // truncated
        }
        return type;
    }

    /** {@code SensorStartResponse}: {@code status = 1}. */
    public static byte[] startResponse(boolean ok) {
        return AaControl.channelOpenResponse(ok ? AaControl.STATUS_OK : AaControl.STATUS_FAIL);
    }

    /** {@code SensorEventIndication.driving_status = 13 { status = 1 }}. */
    public static byte[] drivingStatus(int status) {
        return new ProtoWriter().message(13, new ProtoWriter().varint(1, status)).toByteArray();
    }

    /** {@code SensorEventIndication.night_mode = 10 { is_night = 1 }}. */
    public static byte[] nightMode(boolean night) {
        return new ProtoWriter().message(10, new ProtoWriter().bool(1, night)).toByteArray();
    }

    /** Reads {@code is_night} from a night-mode indication; null when the message has none. */
    public static Boolean parseNightMode(byte[] payload) {
        ProtoReader reader = new ProtoReader(payload);
        try {
            while (reader.hasMore()) {
                int tag = reader.readTag();
                if (ProtoReader.field(tag) == 10) {
                    ProtoReader inner = new ProtoReader(reader.readBytes());
                    while (inner.hasMore()) {
                        int innerTag = inner.readTag();
                        if (ProtoReader.field(innerTag) == 1) {
                            return inner.readVarint() != 0;
                        }
                        inner.skip(ProtoReader.wireType(innerTag));
                    }
                } else {
                    reader.skip(ProtoReader.wireType(tag));
                }
            }
        } catch (AaProtocolException ignored) {
            // truncated
        }
        return null;
    }

    /** Reads {@code driving_status.status}; -1 when the message has none. */
    public static int parseDrivingStatus(byte[] payload) {
        ProtoReader reader = new ProtoReader(payload);
        try {
            while (reader.hasMore()) {
                int tag = reader.readTag();
                if (ProtoReader.field(tag) == 13) {
                    ProtoReader inner = new ProtoReader(reader.readBytes());
                    while (inner.hasMore()) {
                        int innerTag = inner.readTag();
                        if (ProtoReader.field(innerTag) == 1) {
                            return (int) inner.readVarint();
                        }
                        inner.skip(ProtoReader.wireType(innerTag));
                    }
                } else {
                    reader.skip(ProtoReader.wireType(tag));
                }
            }
        } catch (AaProtocolException ignored) {
            // truncated
        }
        return -1;
    }
}
