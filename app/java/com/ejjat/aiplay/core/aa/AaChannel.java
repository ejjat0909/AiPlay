package com.ejjat.aiplay.core.aa;

/**
 * Android Auto channel ids and frame flags.
 *
 * <p>Values are taken from the public aasdk definitions (f1xpl/aasdk, GPL-3.0):
 * {@code ChannelId.hpp}, {@code FrameType.hpp}, {@code EncryptionType.hpp},
 * {@code MessageType.hpp} and {@code cMaxFramePayloadSize} in {@code MessageOutStream.hpp}.</p>
 */
public final class AaChannel {

    // ChannelId.hpp
    public static final int CONTROL = 0;
    public static final int INPUT = 1;
    public static final int SENSOR = 2;
    public static final int VIDEO = 3;
    public static final int MEDIA_AUDIO = 4;
    public static final int SPEECH_AUDIO = 5;
    public static final int SYSTEM_AUDIO = 6;
    public static final int AV_INPUT = 7;
    public static final int BLUETOOTH = 8;

    // FrameType.hpp — a whole message in one frame is FIRST|LAST.
    public static final int FRAME_MIDDLE = 0;
    public static final int FRAME_FIRST = 1;
    public static final int FRAME_LAST = 2;
    public static final int FRAME_BULK = FRAME_FIRST | FRAME_LAST;

    // EncryptionType.hpp / MessageType.hpp — both live in the same flags byte.
    public static final int FLAG_ENCRYPTED = 1 << 3;
    public static final int FLAG_CONTROL = 1 << 2;

    /** {@code cMaxFramePayloadSize}: the largest payload one frame may carry. */
    public static final int MAX_FRAME_PAYLOAD = 0x4000;

    private AaChannel() {
    }

    public static String name(int channel) {
        switch (channel) {
            case CONTROL: return "control";
            case INPUT: return "input";
            case SENSOR: return "sensor";
            case VIDEO: return "video";
            case MEDIA_AUDIO: return "media-audio";
            case SPEECH_AUDIO: return "speech-audio";
            case SYSTEM_AUDIO: return "system-audio";
            case AV_INPUT: return "av-input";
            case BLUETOOTH: return "bluetooth";
            default: return "channel-" + channel;
        }
    }
}
