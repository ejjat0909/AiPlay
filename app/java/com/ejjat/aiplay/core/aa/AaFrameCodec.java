package com.ejjat.aiplay.core.aa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

    /**
     * Encrypts and decrypts individual frame payloads. aasdk encrypts <em>each frame's</em>
     * payload chunk as its own TLS record: the frame size on the wire is the encrypted size, while
     * the {@code total} in a FIRST frame is the plaintext size of the whole message.
     */
    public interface Cipher {
        byte[] encrypt(byte[] data, int offset, int length) throws IOException;

        byte[] decrypt(byte[] data, int offset, int length) throws AaProtocolException;
    }

    /** Receives each message as soon as its last frame has been parsed. */
    public interface Listener {
        void onMessage(AaFrame frame) throws IOException, AaProtocolException;
    }

    public static byte[] encode(AaFrame frame) {
        return encode(frame.channel, frame.control, frame.messageId, frame.payload);
    }

    /** Encodes one plain message into one or more wire frames. */
    public static byte[] encode(int channel, boolean control, int messageId, byte[] payload) {
        try {
            return encode(channel, control, messageId, payload, null);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible); // no cipher, so no I/O
        }
    }

    /**
     * Encodes one message into one or more wire frames. With a {@code cipher} every frame carries
     * the ENCRYPTED flag and its payload chunk is encrypted separately.
     */
    public static byte[] encode(int channel, boolean control, int messageId, byte[] payload,
                                Cipher cipher) throws IOException {
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

        int baseFlags = (control ? AaChannel.FLAG_CONTROL : 0)
                | (cipher != null ? AaChannel.FLAG_ENCRYPTED : 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream(message.length + 64);

        if (message.length <= MAX_FRAME_PAYLOAD) {
            writeFrame(out, channel, baseFlags | AaChannel.FRAME_BULK, message, 0, message.length,
                    -1, cipher);
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
                    first ? message.length : -1, cipher);
            offset += size;
        }
        return out.toByteArray();
    }

    private static void writeFrame(ByteArrayOutputStream out, int channel, int flags,
                                   byte[] data, int offset, int size, int totalSize,
                                   Cipher cipher) throws IOException {
        byte[] body = data;
        int bodyOffset = offset;
        int bodySize = size;
        if (cipher != null) {
            body = cipher.encrypt(data, offset, size);
            bodyOffset = 0;
            bodySize = body.length;
            if (bodySize > 0xFFFF) {
                throw new IOException("encrypted frame too large: " + bodySize);
            }
        }
        out.write(channel);
        out.write(flags);
        out.write((bodySize >>> 8) & 0xFF);
        out.write(bodySize & 0xFF);
        if (totalSize >= 0) {
            // The total is the plaintext size of the whole message, never the encrypted size.
            out.write((totalSize >>> 24) & 0xFF);
            out.write((totalSize >>> 16) & 0xFF);
            out.write((totalSize >>> 8) & 0xFF);
            out.write(totalSize & 0xFF);
        }
        out.write(body, bodyOffset, bodySize);
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
        private boolean messageEncrypted;
        private int messageTotal;
        private Cipher cipher;

        /** Installs the cipher used for frames that carry the ENCRYPTED flag. */
        public void setCipher(Cipher cipher) {
            this.cipher = cipher;
        }

        public void feed(byte[] buffer, int offset, int length, final List<AaFrame> sink)
                throws AaProtocolException {
            try {
                feed(buffer, offset, length, new Listener() {
                    @Override
                    public void onMessage(AaFrame frame) {
                        sink.add(frame);
                    }
                });
            } catch (IOException impossible) {
                // the collecting listener above never throws
                throw new AaProtocolException(String.valueOf(impossible.getMessage()));
            }
        }

        /**
         * Parses {@code buffer}, handing each completed message to {@code listener} immediately —
         * before the next frame is parsed. That ordering matters: the handler of the last
         * {@code SSL_HANDSHAKE} message switches encryption on, and the very next frame in the
         * same read may already be encrypted.
         */
        public void feed(byte[] buffer, int offset, int length, Listener listener)
                throws IOException, AaProtocolException {
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
                } else if (size > MAX_FRAME_PAYLOAD && !encrypted) {
                    throw new AaProtocolException("frame payload too large: " + size);
                }
                int headerBytes = HEADER_SIZE + sizeFieldBytes;
                if (data.length - position < headerBytes + size) {
                    break;
                }

                boolean control = (flags & AaChannel.FLAG_CONTROL) != 0;
                int payloadAt = position + headerBytes;
                byte[] chunk = data;
                int chunkAt = payloadAt;
                int chunkSize = size;
                if (encrypted) {
                    if (cipher == null) {
                        throw new AaProtocolException(
                                "encrypted frame but the TLS handshake has not completed");
                    }
                    chunk = cipher.decrypt(data, payloadAt, size);
                    chunkAt = 0;
                    chunkSize = chunk.length;
                }
                if (first) {
                    message = new ByteArrayOutputStream(totalSize);
                    messageChannel = channel;
                    messageControl = control;
                    messageEncrypted = encrypted;
                    messageTotal = totalSize;
                } else if (frameType != AaChannel.FRAME_BULK && message == null) {
                    throw new AaProtocolException("continuation frame without a FIRST frame");
                }
                if (message != null && !first) {
                    if (channel != messageChannel) {
                        throw new AaProtocolException("interleaved channels");
                    }
                }
                position += headerBytes + size;
                if (message == null) {
                    // BULK: a whole message in one frame.
                    listener.onMessage(toFrame(channel, control, encrypted, chunk, chunkAt,
                            chunkSize));
                } else {
                    message.write(chunk, chunkAt, chunkSize);
                    if (frameType == AaChannel.FRAME_LAST) {
                        if (message.size() != messageTotal) {
                            throw new AaProtocolException("message size mismatch: got "
                                    + message.size() + ", expected " + messageTotal);
                        }
                        byte[] whole = message.toByteArray();
                        message = null;
                        listener.onMessage(toFrame(messageChannel, messageControl,
                                messageEncrypted, whole, 0, whole.length));
                    }
                }
            }

            pending.reset();
            if (position < data.length) {
                pending.write(data, position, data.length - position);
            }
        }

        private static AaFrame toFrame(int channel, boolean control, boolean encrypted,
                                       byte[] data, int offset, int length)
                throws AaProtocolException {
            if (length < 2) {
                throw new AaProtocolException("message shorter than a message id");
            }
            int messageId = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            byte[] payload = new byte[length - 2];
            System.arraycopy(data, offset + 2, payload, 0, payload.length);
            return new AaFrame(channel, control, messageId, payload, encrypted);
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
