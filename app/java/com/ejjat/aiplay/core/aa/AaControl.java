package com.ejjat.aiplay.core.aa;

import java.nio.charset.Charset;

/**
 * Builders for the Android Auto control-plane messages AiPlay speaks.
 *
 * <p>Field numbers are taken from the public aasdk protobuf definitions (f1xpl/aasdk, GPL-3.0)
 * and are cited next to each builder. Anything not listed here is <em>not</em> implemented, and
 * the receiver says so in its session log instead of pretending otherwise.</p>
 */
public final class AaControl {

    /** Protocol version AiPlay announces. Matches what modern aasdk-based receivers request. */
    public static final int PROTOCOL_MAJOR = 1;
    public static final int PROTOCOL_MINOR = 6;

    /** {@code Status.Enum}: OK = 0, FAIL = 1. */
    public static final int STATUS_OK = 0;
    public static final int STATUS_FAIL = 1;

    /** {@code AVStreamType.Enum}. */
    public static final int STREAM_NONE = 0;
    public static final int STREAM_AUDIO = 1;
    public static final int STREAM_VIDEO = 3;

    /** {@code AudioType.Enum}. */
    public static final int AUDIO_SPEECH = 1;
    public static final int AUDIO_SYSTEM = 2;
    public static final int AUDIO_MEDIA = 3;

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private AaControl() {
    }

    /**
     * {@code VersionRequest}: {@code major = 1}, {@code minor = 2}.
     * Sent by the head unit immediately after the TLS session is established.
     */
    public static byte[] versionRequest() {
        return versionRequest(PROTOCOL_MAJOR, PROTOCOL_MINOR);
    }

    public static byte[] versionRequest(int major, int minor) {
        return new ProtoWriter().varint(1, major).varint(2, minor).toByteArray();
    }

    /** {@code AuthCompleteIndication}: {@code status = 1} ({@code Status.Enum}). */
    public static byte[] authComplete(int status) {
        return new ProtoWriter().varint(1, status).toByteArray();
    }

    /** {@code PingRequest}: {@code timestamp = 1} (int64, microseconds). */
    public static byte[] pingRequest(long timestampMicros) {
        return new ProtoWriter().varint(1, timestampMicros).toByteArray();
    }

    /** {@code PingResponse}: {@code timestamp = 1}. */
    public static byte[] pingResponse(long timestampMicros) {
        return new ProtoWriter().varint(1, timestampMicros).toByteArray();
    }

    /** {@code ChannelOpenResponse}: {@code status = 1}. */
    public static byte[] channelOpenResponse(int status) {
        return new ProtoWriter().varint(1, status).toByteArray();
    }

    /**
     * {@code ServiceDiscoveryResponse} — the head unit's channel offer.
     *
     * <p>Field numbers: {@code channels = 1}, {@code head_unit_name = 2}, {@code car_model = 3},
     * {@code car_year = 4}, {@code car_serial = 5}, {@code left_hand_drive_vehicle = 6},
     * {@code headunit_manufacturer = 7}, {@code headunit_model = 8}, {@code sw_build = 9},
     * {@code sw_version = 10}, {@code can_play_native_media_during_vr = 11},
     * {@code hide_clock = 12}.</p>
     */
    public static byte[] serviceDiscoveryResponse(ServiceDiscoveryConfig config) {
        ProtoWriter response = new ProtoWriter();
        for (ProtoWriter channel : config.channels()) {
            response.message(1, channel);
        }
        response.string(2, config.headUnitName);
        response.string(3, config.carModel);
        response.string(4, config.carYear);
        response.string(5, config.carSerial);
        response.bool(6, config.leftHandDrive);
        response.string(7, config.manufacturer);
        response.string(8, config.model);
        response.string(9, config.swBuild);
        response.string(10, config.swVersion);
        response.bool(11, true);
        response.bool(12, false);
        return response.toByteArray();
    }

    /** Reads a {@code VersionResponse} defensively; missing fields fall back to {@code -1}. */
    public static VersionResponse parseVersionResponse(byte[] payload) {
        VersionResponse result = new VersionResponse();
        ProtoReader reader = new ProtoReader(payload);
        try {
            while (reader.hasMore()) {
                int tag = reader.readTag();
                switch (ProtoReader.field(tag)) {
                    case 1:
                        result.major = (int) reader.readVarint();
                        break;
                    case 2:
                        result.minor = (int) reader.readVarint();
                        break;
                    case 3:
                        result.status = (int) reader.readVarint();
                        break;
                    default:
                        reader.skip(ProtoReader.wireType(tag));
                        break;
                }
            }
        } catch (AaProtocolException ignored) {
            // A malformed version response is reported by the caller through result.complete.
        }
        result.complete = result.major >= 0 && result.minor >= 0;
        return result;
    }

    /** Parsed {@code VersionResponse}. */
    public static final class VersionResponse {
        public int major = -1;
        public int minor = -1;
        public int status = -1;
        public boolean complete;

        @Override
        public String toString() {
            return "version " + major + "." + minor + " status=" + status;
        }
    }

    /**
     * Reads a {@code ChannelOpenRequest}: {@code priority = 1, channel_id = 2}. Returns the
     * channel id, or -1 when it is missing.
     */
    public static int parseChannelOpenRequest(byte[] payload) {
        int channelId = -1;
        ProtoReader reader = new ProtoReader(payload);
        try {
            while (reader.hasMore()) {
                int tag = reader.readTag();
                if (ProtoReader.field(tag) == 2 && ProtoReader.wireType(tag) == 0) {
                    channelId = (int) reader.readVarint();
                } else {
                    reader.skip(ProtoReader.wireType(tag));
                }
            }
        } catch (AaProtocolException ignored) {
            // Truncated: report what was read so far.
        }
        return channelId;
    }

    /** Parsed {@code ServiceDiscoveryRequest}. */
    public static final class ServiceDiscoveryRequest {
        public String deviceName = "";
        public String deviceBrand = "";
    }

    /** Reads a {@code ServiceDiscoveryRequest}: {@code device_name = 4}, {@code device_brand = 5}. */
    public static ServiceDiscoveryRequest parseServiceDiscoveryRequest(byte[] payload) {
        ServiceDiscoveryRequest result = new ServiceDiscoveryRequest();
        ProtoReader reader = new ProtoReader(payload);
        try {
            while (reader.hasMore()) {
                int tag = reader.readTag();
                switch (ProtoReader.field(tag)) {
                    case 4:
                        result.deviceName = new String(reader.readBytes(), UTF_8);
                        break;
                    case 5:
                        result.deviceBrand = new String(reader.readBytes(), UTF_8);
                        break;
                    default:
                        reader.skip(ProtoReader.wireType(tag));
                        break;
                }
            }
        } catch (AaProtocolException ignored) {
            // Partial data is still useful for the session log.
        }
        return result;
    }

    /** Everything the head unit tells the phone about itself and its channels. */
    public static final class ServiceDiscoveryConfig {

        public String headUnitName = "AiPlay";
        public String carModel = "Android head unit";
        public String carYear = "";
        public String carSerial = "aiplay-0001";
        public boolean leftHandDrive = true;
        public String manufacturer = "AiPlay";
        public String model = "AiPlay receiver";
        public String swBuild = "aiplay";
        public String swVersion = "0.1.0";

        // Only what the receiver can actually serve is advertised: a channel that is offered and
        // then refused on open is worse for a phone than a channel that was never offered.
        // 0.2.0 serves video; audio and input switch on as their channels land.
        public boolean advertiseVideo = true;
        public boolean advertiseMediaAudio = false;
        public boolean advertiseSpeechAudio = false;
        public boolean advertiseInput = false;

        public int videoWidth = 1280;
        public int videoHeight = 720;
        public int videoDpi = 160;
        public int videoFps = 30;

        public int sampleRate = 48000;
        public int bitDepth = 16;
        public int channelCount = 2;

        public int touchWidth = 1280;
        public int touchHeight = 720;

        java.util.List<ProtoWriter> channels() {
            java.util.List<ProtoWriter> channels = new java.util.ArrayList<ProtoWriter>(4);
            if (advertiseVideo) {
                channels.add(descriptor(AaChannel.VIDEO, avChannel(STREAM_VIDEO, 0)));
            }
            if (advertiseMediaAudio) {
                channels.add(descriptor(AaChannel.MEDIA_AUDIO, avChannel(STREAM_AUDIO, AUDIO_MEDIA)));
            }
            if (advertiseSpeechAudio) {
                channels.add(descriptor(AaChannel.SPEECH_AUDIO, avChannel(STREAM_AUDIO, AUDIO_SPEECH)));
            }
            if (advertiseInput) {
                channels.add(inputDescriptor(AaChannel.INPUT));
            }
            return channels;
        }

        private ProtoWriter descriptor(int channelId, ProtoWriter avChannel) {
            ProtoWriter descriptor = new ProtoWriter();
            descriptor.varint(1, channelId);
            descriptor.message(3, avChannel);
            return descriptor;
        }

        private ProtoWriter inputDescriptor(int channelId) {
            ProtoWriter touch = new ProtoWriter().varint(1, touchWidth).varint(2, touchHeight);
            ProtoWriter input = new ProtoWriter();
            // A minimal, documented key set: home, back, dpad, enter, media transport.
            int[] keys = {3, 4, 19, 20, 21, 22, 23, 85, 87, 88, 126, 127};
            for (int key : keys) {
                input.varint(1, key);
            }
            input.message(2, touch);
            ProtoWriter descriptor = new ProtoWriter();
            descriptor.varint(1, channelId);
            descriptor.message(4, input);
            return descriptor;
        }

        /**
         * {@code AVChannel}: {@code stream_type = 1}, {@code audio_type = 2},
         * {@code audio_configs = 3}, {@code video_configs = 4}, {@code available_while_in_call = 5}.
         */
        private ProtoWriter avChannel(int streamType, int audioType) {
            ProtoWriter channel = new ProtoWriter();
            channel.varint(1, streamType);
            if (streamType == STREAM_AUDIO) {
                channel.varint(2, audioType);
                ProtoWriter audio = new ProtoWriter()
                        .varint(1, sampleRate)
                        .varint(2, bitDepth)
                        .varint(3, channelCount);
                channel.message(3, audio);
            } else {
                ProtoWriter video = new ProtoWriter()
                        .varint(1, resolutionEnum())
                        .varint(2, videoFps >= 60 ? 2 : 1)
                        .varint(3, 0)
                        .varint(4, 0)
                        .varint(5, videoDpi)
                        .varint(6, 0);
                channel.message(4, video);
            }
            channel.bool(5, false);
            return channel;
        }

        private int resolutionEnum() {
            if (videoHeight >= 1080) {
                return 3;
            }
            if (videoHeight >= 720) {
                return 2;
            }
            return 1;
        }
    }
}
