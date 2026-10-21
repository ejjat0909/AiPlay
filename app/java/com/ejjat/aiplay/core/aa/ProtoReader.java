package com.ejjat.aiplay.core.aa;

import java.nio.charset.Charset;

/**
 * Minimal protobuf wire-format reader with strict bounds checking.
 *
 * <p>Every read validates the remaining length before allocating, so a truncated or hostile
 * payload surfaces as {@link AaProtocolException} instead of an {@link OutOfMemoryError}.</p>
 */
public final class ProtoReader {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final byte[] buffer;
    private int position;
    private final int limit;

    public ProtoReader(byte[] buffer) {
        this(buffer, 0, buffer.length);
    }

    public ProtoReader(byte[] buffer, int offset, int length) {
        this.buffer = buffer;
        this.position = offset;
        this.limit = offset + length;
    }

    public boolean hasMore() {
        return position < limit;
    }

    /** Reads a tag; returns {@code (field << 3) | wireType}. */
    public int readTag() throws AaProtocolException {
        long tag = readVarint();
        if (tag <= 0 || tag > 0xFFFFFFFFL) {
            throw new AaProtocolException("invalid tag: " + tag);
        }
        return (int) tag;
    }

    public static int field(int tag) {
        return tag >>> 3;
    }

    public static int wireType(int tag) {
        return tag & 0x7;
    }

    public long readVarint() throws AaProtocolException {
        long result = 0;
        int shift = 0;
        while (true) {
            if (position >= limit) {
                throw new AaProtocolException("truncated varint");
            }
            int b = buffer[position++] & 0xFF;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift > 63) {
                throw new AaProtocolException("varint too long");
            }
        }
    }

    public byte[] readBytes() throws AaProtocolException {
        int length = (int) readVarint();
        if (length < 0 || length > limit - position) {
            throw new AaProtocolException("length-delimited field overruns payload: " + length);
        }
        byte[] value = new byte[length];
        System.arraycopy(buffer, position, value, 0, length);
        position += length;
        return value;
    }

    public String readString() throws AaProtocolException {
        return new String(readBytes(), UTF_8);
    }

    public void skip(int wireType) throws AaProtocolException {
        switch (wireType) {
            case 0:
                readVarint();
                return;
            case 1:
                advance(8);
                return;
            case 2:
                int length = (int) readVarint();
                if (length < 0 || length > limit - position) {
                    throw new AaProtocolException("skip overrun: " + length);
                }
                advance(length);
                return;
            case 5:
                advance(4);
                return;
            default:
                throw new AaProtocolException("unsupported wire type: " + wireType);
        }
    }

    private void advance(int count) throws AaProtocolException {
        if (count < 0 || count > limit - position) {
            throw new AaProtocolException("advance overrun: " + count);
        }
        position += count;
    }
}
