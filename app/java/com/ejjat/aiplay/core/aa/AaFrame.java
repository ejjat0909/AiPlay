package com.ejjat.aiplay.core.aa;

/**
 * One reassembled Android Auto message.
 *
 * <p>A message is what a peer sends: a channel, a message id and a protobuf payload. It travels as
 * one or more wire frames ({@link AaFrameCodec}) when the payload does not fit in
 * {@link AaChannel#MAX_FRAME_PAYLOAD} bytes.</p>
 */
public final class AaFrame {

    /** {@link AaChannel} id. */
    public final int channel;
    /** True when the frame carries a control-channel message ({@link AaMessageType}). */
    public final boolean control;
    /** Message id, big endian on the wire, first two bytes of the message payload. */
    public final int messageId;
    /** Protobuf bytes, without the message id. */
    public final byte[] payload;

    public AaFrame(int channel, boolean control, int messageId, byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload == null");
        }
        if (messageId < 0 || messageId > 0xFFFF) {
            throw new IllegalArgumentException("messageId out of range: " + messageId);
        }
        this.channel = channel;
        this.control = control;
        this.messageId = messageId;
        this.payload = payload;
    }

    /** A control-channel message. */
    public static AaFrame control(int messageId, byte[] payload) {
        return new AaFrame(AaChannel.CONTROL, true, messageId, payload);
    }

    /** A channel-specific message (video, audio, input …). */
    public static AaFrame channel(int channel, int messageId, byte[] payload) {
        return new AaFrame(channel, false, messageId, payload);
    }

    public String typeName() {
        return control ? AaMessageType.name(messageId) : String.format("0x%04X", messageId);
    }

    @Override
    public String toString() {
        return AaChannel.name(channel) + "/" + typeName() + "(" + payload.length + "B)";
    }
}
