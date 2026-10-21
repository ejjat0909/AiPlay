package com.ejjat.aiplay.core.aa;

/**
 * One reassembled Android Auto message.
 *
 * <p>A message is what a peer sends: a channel, a message id and a payload. It travels as one or
 * more wire frames ({@link AaFrameCodec}) when the payload does not fit in
 * {@link AaChannel#MAX_FRAME_PAYLOAD} bytes.</p>
 *
 * <p>The {@code control} flag is the wire's {@code MessageType::CONTROL} bit and is easy to
 * misread. In aasdk it is set only on <em>channel-level</em> control messages sent on a
 * non-zero channel (channel open request/response). Messages on channel 0 — version, handshake,
 * auth, service discovery, ping, shutdown — are {@code SPECIFIC}, i.e. the flag is clear.
 * AiPlay 0.1.3–0.2.0-alpha3 set it on channel 0 and so did not match a real phone.</p>
 */
public final class AaFrame {

    /** {@link AaChannel} id. */
    public final int channel;
    /** The wire's CONTROL bit: channel-level open/close on a non-zero channel. */
    public final boolean control;
    /** Message id, big endian on the wire, first two bytes of the message payload. */
    public final int messageId;
    /** Message bytes after the message id (protobuf, or raw bytes for version/handshake). */
    public final byte[] payload;
    /** True when the frame(s) arrived encrypted (and were decrypted before reaching here). */
    public final boolean encrypted;

    public AaFrame(int channel, boolean control, int messageId, byte[] payload) {
        this(channel, control, messageId, payload, false);
    }

    public AaFrame(int channel, boolean control, int messageId, byte[] payload, boolean encrypted) {
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
        this.encrypted = encrypted;
    }

    /** A message on the control channel (channel 0), flag clear as aasdk sends it. */
    public static AaFrame control(int messageId, byte[] payload) {
        return new AaFrame(AaChannel.CONTROL, false, messageId, payload);
    }

    /** A channel-specific message (video, audio, input …). */
    public static AaFrame channel(int channel, int messageId, byte[] payload) {
        return new AaFrame(channel, false, messageId, payload);
    }

    /** A channel-level control message (channel open request/response) on a non-zero channel. */
    public static AaFrame channelControl(int channel, int messageId, byte[] payload) {
        return new AaFrame(channel, true, messageId, payload);
    }

    /** True when {@link #messageId} is a control id ({@link AaMessageType}) rather than channel-specific. */
    public boolean isControlId() {
        return channel == AaChannel.CONTROL || control;
    }

    public String typeName() {
        return AaChannel.messageName(channel, isControlId(), messageId);
    }

    @Override
    public String toString() {
        return AaChannel.name(channel) + "/" + typeName() + "(" + payload.length + "B"
                + (encrypted ? ", encrypted" : "") + ")";
    }
}
