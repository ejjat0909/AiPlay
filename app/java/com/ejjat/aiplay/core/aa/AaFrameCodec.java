package com.ejjat.aiplay.core.aa;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Android Auto frame codec — the real wire format.
 *
 * <pre>
 * +-----------+-----------+---------------------+------------------+
 * | channel   | flags     | size                | payload          |
 * | uint8     | uint8     | uint16 BE           | size bytes       |
 * |           |           | (+ uint32 BE total  |                  |
 * |           |           |  when FIRST frame)  |                  |
 * +-----------+-----------+---------------------+------------------+
 * </pre>
 *
 * <p>Flags carry three fields at once: frame type ({@code MIDDLE=0}, {@code FIRST=1},
 * {@code LAST=2}, {@code BULK=3}), {@code ENCRYPTED=8} and {@code CONTROL=4}. The reassembled
 * message payload starts with the message id, two bytes big endian, followed by the protobuf.</p>
 *
 * <p>Citations: aasdk {@code src/Messenger/MessageOutStream.cpp} (header first, then size),
 * {@code FrameHeader.hpp}, {@code FrameSize.hpp}, {@code MessageId.cpp}. A message of
 * {@code cMaxFramePayloadSize = 0x4000} bytes or more is split into FIRST/MIDDLE…/LAST frames and
 * only the FIRST frame carries the extended size.</p>
 *
 * <p><strong>This is a 0.1.2 rewrite.</strong> 0.1.0–0.1.1 wrote
 * {@code [size uint16][message id uint16][protobuf]} with no channel id and no flags. That format
 * is self-consistent — which is why AiPlay's own probe completed a "handshake" against it — but no
 * real Android Auto phone speaks it.</p>
 */
public final class AaFrameCodec {

    /** channel + flags. */
    public static final int HEADER_SIZE = 2;
    public static final int SIZE_SHORT = 2;
    public static final int SIZE_EXTENDED = 6;

    /** One frame carries at most this many bytes of the message payload. */
    public static final int MAX_FRAME_PAYLOAD = AaChannel.MAX_FRAME_PAYLOAD;

    /** Guard against a hostile peer declaring a huge reassembly target. */
    public static final int MAX_MESSAGE_PAYLOAD = 4 * 1024 * 1024;

    private AaFrameCodec() {
    }

    public static byte[] encode(AaFrame frame) {
        return encode(frame.channel, frame.control, frame.messageId, frame.payload);
    }

    /** Encodes one message into one or more wire frames. */
    public static byte[] encode(int channel, boolean control, int messageId, byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload == null");
        }
        if (channel < 0 || channel > 0xFF) {
            throw new IllegalArgumentException("channel out of range: " + channel);
        }
        if (messageId < 0 || messageId > 0xFFFF) {
            throw new IllegalArgumentException("messageId out of range: " + messageId);
        }
        byte[] message = new byte[2 + payload.length];
        message[0] = (byte) ((messageId >>> 8) & 0xFF);
        message[1] = (byte) (messageId & 0xFF);
        System.arraycopy(payload, 0, message, 2, payload.length);
        if (message.length > MAX_MESSAGE_PAYLOAD) {
            throw new IllegalArgumentException("message too large: " + message.length);
        }

        int baseFlags = control ? AaChannel.FLAG_CONTROL : 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(message.length + 8);

        if (message.length <= MAX_FRAME_PAYLOAD) {
            writeFrame(out, channel, baseFlags | AaChannel.FRAME_BULK, message, 0, message.length, -1);
            return out.toByteArray();
        }

        int offset = 0;
        while (offset < message.length) {
            int size = Math.min(MAX_FRAME_PAYLOAD, message.length - offset);
            boolean first = offset == 0;
            boolean last = offset + size >= message.length;
            int frameType = first ? AaChannel.FRAME_FIRST : (last ? AaChannel.FRAME_LAST
                    : AaChannel.FRAME_MIDDLE);
            writeFrame(out, channel, baseFlags | frameType, message, offset, size,
                    first ? message.length : -1);
            offset += size;
        }
        return out.toByteArray();
    }

    private static void writeFrame(ByteArrayOutputStream out, int channel, int flags,
                                   byte[] data, int offset, int size, int totalSize) {
        out.write(channel);
        out.write(flags);
        out.write((size >>> 8) & 0xFF);
        out.write(size & 0xFF);
        if (totalSize >= 0) {
            out.write((totalSize >>> 24) & 0xFF);
            out.write((totalSize >>> 16) & 0xFF);
            out.write((totalSize >>> 8) & 0xFF);
            out.write(totalSize & 0xFF);
        }
        out.write(data, offset, size);
    }

    /** Decodes exactly one complete message. Trailing bytes are rejected. */
    public static AaFrame decode(byte[] data) throws AaProtocolException {
        if (data == null) {
            throw new AaProtocolException("no data");
        }
        Parser parser = new Parser();
        List<AaFrame> frames = new java.util.ArrayList<AaFrame>(1);
        parser.feed(data, 0, data.length, frames);
        if (frames.isEmpty()) {
            throw new AaProtocolException("incomplete frame");
        }
        if (frames.size() > 1) {
            throw new AaProtocolException("more than one message in buffer");
        }
        if (parser.bufferedBytes() != 0) {
            throw new AaProtocolException("trailing bytes after message");
        }
        return frames.get(0);
    }

    /** Incremental parser for a TCP byte stream. Not thread safe; one instance per connection. */
    public static final class Parser {

        private final ByteArrayOutputStream pending = new ByteArrayOutputStream(512);
        private ByteArrayOutputStream message;
        private int messageChannel;
        private boolean messageControl;
        private int messageTotal;

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

            while (true) {
                if (data.length - position < HEADER_SIZE) {
                    break;
                }
                int channel = data[position] & 0xFF;
                int flags = data[position + 1] & 0xFF;
                int frameType = flags & AaChannel.FRAME_BULK;
                boolean encrypted = (flags & AaChannel.FLAG_ENCRYPTED) != 0;
                if (encrypted) {
                    throw new AaProtocolException("encrypted frames are not supported");
                }
                boolean first = frameType == AaChannel.FRAME_FIRST;
                int sizeFieldBytes = first ? SIZE_EXTENDED : SIZE_SHORT;
                if (data.length - position < HEADER_SIZE + sizeFieldBytes) {
                    break;
                }
                int size = ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
                int totalSize = -1;
                if (first) {
                    totalSize = ((data[position + 4] & 0xFF) << 24)
                            | ((data[position + 5] & 0xFF) << 16)
                            | ((data[position + 6] & 0xFF) << 8)
                            | (data[position + 7] & 0xFF);
                    if (totalSize < 2 || totalSize > MAX_MESSAGE_PAYLOAD) {
                        throw new AaProtocolException("implausible message size: " + totalSize);
                    }
                } else if (size > MAX_FRAME_PAYLOAD) {
                    throw new AaProtocolException("frame payload too large: " + size);
                }
                int headerBytes = HEADER_SIZE + sizeFieldBytes;
                if (data.length - position < headerBytes + size) {
                    break;
                }

                boolean control = (flags & AaChannel.FLAG_CONTROL) != 0;
                int payloadAt = position + headerBytes;
                if (first) {
                    message = new ByteArrayOutputStream(totalSize);
                    messageChannel = channel;
                    messageControl = control;
                    messageTotal = totalSize;
                } else if (frameType != AaChannel.FRAME_BULK && message == null) {
                    throw new AaProtocolException("continuation frame without a FIRST frame");
                }
                if (message != null && !first) {
                    if (channel != messageChannel) {
                        throw new AaProtocolException("interleaved channels");
                    }
                }
                if (message == null) {
                    // BULK: a whole message in one frame.
                    sink.add(toFrame(channel, control, data, payloadAt, size));
                } else {
                    message.write(data, payloadAt, size);
                    if (frameType == AaChannel.FRAME_LAST) {
                        if (message.size() != messageTotal) {
                            throw new AaProtocolException("message size mismatch: got "
                                    + message.size() + ", expected " + messageTotal);
                        }
                        sink.add(toFrame(messageChannel, messageControl, message.toByteArray(), 0,
                                message.size()));
                        message = null;
                    }
                }
                position += headerBytes + size;
            }

            pending.reset();
            if (position < data.length) {
                pending.write(data, position, data.length - position);
            }
        }

        private static AaFrame toFrame(int channel, boolean control, byte[] data, int offset,
                                       int length) throws AaProtocolException {
            if (length < 2) {
                throw new AaProtocolException("message shorter than a message id");
            }
            int messageId = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            byte[] payload = new byte[length - 2];
            System.arraycopy(data, offset + 2, payload, 0, payload.length);
            return new AaFrame(channel, control, messageId, payload);
        }

        public int bufferedBytes() {
            return pending.size();
        }

        public void reset() {
            pending.reset();
            message = null;
        }
    }
}
