package com.ejjat.aiplay.core.aa;

/** One Android Auto frame: a 16-bit message id and its protobuf payload. */
public final class AaFrame {

    public final int type;
    public final byte[] payload;

    public AaFrame(int type, byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload == null");
        }
        this.type = type;
        this.payload = payload;
    }

    public String typeName() {
        return AaMessageType.name(type);
    }

    public int totalBytes() {
        return AaFrameCodec.HEADER_SIZE + payload.length;
    }

    @Override
    public String toString() {
        return typeName() + "(" + payload.length + "B)";
    }
}
