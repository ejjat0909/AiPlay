package com.ejjat.aiplay.core.aa;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Android Auto frame codec.
 *
 * <pre>
 * +-------------+-------------+------------------+
 * | length (BE) | type  (BE)  | payload (length) |
 * | uint16      | uint16      | protobuf bytes   |
 * +-------------+-------------+------------------+
 * </pre>
 *
 * <p>The length counts the payload only. {@link Parser} tolerates arbitrary TCP fragmentation and
 * enforces {@link #MAX_PAYLOAD} before allocating, so a hostile or broken peer cannot make the
 * head unit allocate unbounded memory.</p>
 */
public final class AaFrameCodec {

    public static final int HEADER_SIZE = 4;
    /** The length field is 16-bit, so this is the largest frame the wire can express. */
    public static final int MAX_PAYLOAD = 0xFFFF;

    private AaFrameCodec() {
    }

    public static byte[] encode(int type, byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload == null");
        }
        if (type < 0 || type > 0xFFFF) {
            throw new IllegalArgumentException("type out of range: " + type);
        }
        if (payload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too large: " + payload.length);
        }
        byte[] out = new byte[HEADER_SIZE + payload.length];
        out[0] = (byte) ((payload.length >>> 8) & 0xFF);
        out[1] = (byte) (payload.length & 0xFF);
        out[2] = (byte) ((type >>> 8) & 0xFF);
        out[3] = (byte) (type & 0xFF);
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.length);
        return out;
    }

    public static byte[] encode(AaFrame frame) {
        return encode(frame.type, frame.payload);
    }

    /** Decodes exactly one complete frame. Trailing bytes are rejected. */
    public static AaFrame decode(byte[] data) throws AaProtocolException {
        if (data == null || data.length < HEADER_SIZE) {
            throw new AaProtocolException("frame shorter than header");
        }
        int length = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        int type = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        if (length > MAX_PAYLOAD) {
            throw new AaProtocolException("declared payload too large: " + length);
        }
        if (data.length != HEADER_SIZE + length) {
            throw new AaProtocolException(
                    "frame length mismatch: declared " + length + ", actual " + (data.length - HEADER_SIZE));
        }
        byte[] payload = new byte[length];
        System.arraycopy(data, HEADER_SIZE, payload, 0, length);
        return new AaFrame(type, payload);
    }

    /** Incremental parser for a TCP byte stream. Not thread safe; one instance per connection. */
    public static final class Parser {

        private final ByteArrayOutputStream pending = new ByteArrayOutputStream(256);

        public void feed(byte[] buffer, int offset, int length, List<AaFrame> sink)
                throws AaProtocolException {
            if (buffer == null || length <= 0) {
                return;
            }
            if (offset < 0 || offset + length > buffer.length) {
                throw new AaProtocolException("feed range out of bounds");
            }
            pending.write(buffer, offset, length);
            byte[] data = pending.toByteArray();
            int position = 0;
            while (data.length - position >= HEADER_SIZE) {
                int declared = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
                if (declared > MAX_PAYLOAD) {
                    throw new AaProtocolException("declared payload too large: " + declared);
                }
                int total = HEADER_SIZE + declared;
                if (data.length - position < total) {
                    break;
                }
                int type = ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
                byte[] payload = new byte[declared];
                System.arraycopy(data, position + HEADER_SIZE, payload, 0, declared);
                sink.add(new AaFrame(type, payload));
                position += total;
            }
            pending.reset();
            if (position < data.length) {
                pending.write(data, position, data.length - position);
            }
        }

        public int bufferedBytes() {
            return pending.size();
        }

        public void reset() {
            pending.reset();
        }
    }
}
