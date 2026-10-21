package com.ejjat.aiplay.core.aa;

/**
 * Android Auto audio: the focus negotiation on the control channel, and the per-channel PCM formats.
 *
 * <p>Audio data travels on the AV channels exactly like video (open, setup, start, media with
 * timestamp, one ack per message, stop; see {@link AaAv}); what differs is the payload (raw 16-bit
 * little-endian PCM) and that the phone also negotiates <em>audio focus</em> on channel 0. Facts
 * were read from aasdk ({@code AudioFocusRequestMessage.proto}, {@code AudioFocusResponseMessage.proto},
 * {@code AudioFocusTypeEnum.proto}, {@code AudioFocusStateEnum.proto}, {@code AudioConfigData.proto},
 * {@code AudioTypeEnum.proto}, {@code NavigationFocus*.proto}) and OpenAuto
 * ({@code ServiceFactory.cpp}: media 48000/16/2, speech 16000/16/1, system 16000/16/1;
 * {@code AndroidAutoEntity.cpp}: focus handling).</p>
 */
public final class AaAudio {

    /** {@code AudioFocusType.Enum}. */
    public static final int FOCUS_NONE = 0;
    public static final int FOCUS_GAIN = 1;
    public static final int FOCUS_GAIN_TRANSIENT = 2;
    public static final int FOCUS_GAIN_NAVI = 3;
    public static final int FOCUS_RELEASE = 4;

    /** {@code AudioFocusState.Enum} values used here. */
    public static final int STATE_GAIN = 1;
    public static final int STATE_LOSS = 3;

    /** Format of one audio channel. */
    public static final class Format {
        public final int sampleRate;
        public final int bitDepth;
        public final int channels;

        public Format(int sampleRate, int bitDepth, int channels) {
            this.sampleRate = sampleRate;
            this.bitDepth = bitDepth;
            this.channels = channels;
        }

        public int bytesPerSecond() {
            return sampleRate * (bitDepth / 8) * channels;
        }
    }

    /** Media: 48 kHz stereo. */
    public static final Format MEDIA = new Format(48000, 16, 2);
    /** Navigation guidance and assistant speech: 16 kHz mono. */
    public static final Format SPEECH = new Format(16000, 16, 1);
    /** System sounds: 16 kHz mono. */
    public static final Format SYSTEM = new Format(16000, 16, 1);

    private AaAudio() {
    }

    /** The format served on an audio channel, or null for a channel that is not audio output. */
    public static Format formatFor(int channel) {
        switch (channel) {
            case AaChannel.MEDIA_AUDIO: return MEDIA;
            case AaChannel.SPEECH_AUDIO: return SPEECH;
            case AaChannel.SYSTEM_AUDIO: return SYSTEM;
            default: return null;
        }
    }

    /** {@code AudioFocusRequest.audio_focus_type = 1}; {@link #FOCUS_NONE} when absent. */
    public static int parseFocusType(byte[] payload) {
        int type = FOCUS_NONE;
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

    /**
     * The state AiPlay answers with, as OpenAuto does: RELEASE becomes LOSS and every other request
     * is granted as GAIN. (aasdk also defines finer states, e.g. GAIN_TRANSIENT_GUIDANCE_ONLY;
     * which a phone prefers for navigation prompts is not known.)
     */
    public static int stateFor(int requestedType) {
        return requestedType == FOCUS_RELEASE ? STATE_LOSS : STATE_GAIN;
    }

    /** {@code AudioFocusResponse.audio_focus_state = 1}. */
    public static byte[] focusResponse(int state) {
        return new ProtoWriter().varint(1, state).toByteArray();
    }

    /** {@code NavigationFocusResponse.type = 1}; OpenAuto always answers 2. */
    public static byte[] navigationFocusResponse() {
        return new ProtoWriter().varint(1, 2).toByteArray();
    }
}
