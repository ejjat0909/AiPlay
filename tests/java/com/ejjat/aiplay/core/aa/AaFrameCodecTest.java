package com.ejjat.aiplay.core.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire-format contract tests.
 *
 * <p>Vectors are hand-computed from the aasdk definitions, not from AiPlay's own encoder, so a
 * regression in the encoder cannot hide behind a matching decoder. The format under test:
 * {@code [channel u8][flags u8][size u16 BE][payload]} with the message id as the first two bytes
 * of the payload, and a six-byte size field (size + total) on the FIRST frame of a split
 * message.</p>
 */
public class AaFrameCodecTest {

    @Test
    public void controlChannelMessagesAreSpecificNotControl() {
        // aasdk sends channel-0 messages as MessageType::SPECIFIC: flags = BULK only (0x03).
        // [channel 0][flags 0x03][size 6][id 0x0001][major 1][minor 1]
        byte[] frame = AaFrameCodec.encode(AaChannel.CONTROL, false, AaMessageType.VERSION_REQUEST,
                AaControl.versionRequest());
        assertArrayEquals(new byte[]{0x00, 0x03, 0x00, 0x06, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01},
                frame);
    }

    @Test
    public void channelOpenIsControlTypedOnItsOwnChannel() {
        // video channel, BULK|CONTROL = 0x07, id 0x0008 (ChannelOpenResponse), status OK = 08 00
        byte[] frame = AaFrameCodec.encode(AaChannel.VIDEO, true,
                AaMessageType.CHANNEL_OPEN_RESPONSE, new byte[]{0x08, 0x00});
        assertArrayEquals(new byte[]{0x03, 0x07, 0x00, 0x04, 0x00, 0x08, 0x08, 0x00}, frame);
    }

    @Test
    public void channelMessagesCarryTheirOwnFlags() {
        // video channel, BULK without CONTROL, id 0x8000
        byte[] frame = AaFrameCodec.encode(AaChannel.VIDEO, false, 0x8000, new byte[]{0x0A, 0x00});
        assertArrayEquals(new byte[]{0x03, 0x03, 0x00, 0x04, (byte) 0x80, 0x00, 0x0A, 0x00}, frame);
    }

    @Test
    public void roundTripsEmptyPayload() throws Exception {
        AaFrame decoded = AaFrameCodec.decode(
                AaFrameCodec.encode(AaFrame.control(AaMessageType.PING_REQUEST, new byte[0])));
        assertEquals(AaChannel.CONTROL, decoded.channel);
        assertTrue(!decoded.control);
        assertTrue(decoded.isControlId());
        assertEquals(AaMessageType.PING_REQUEST, decoded.messageId);
        assertEquals(0, decoded.payload.length);
    }

    @Test
    public void roundTripsLargePayload() throws Exception {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        AaFrame decoded = AaFrameCodec.decode(AaFrameCodec.encode(AaChannel.VIDEO, false, 0x1234,
                payload));
        assertEquals(AaChannel.VIDEO, decoded.channel);
        assertEquals(0x1234, decoded.messageId);
        assertArrayEquals(payload, decoded.payload);
    }

    @Test
    public void splitsLargeMessagesIntoFirstMiddleLast() throws Exception {
        byte[] payload = new byte[40000];
        byte[] wire = AaFrameCodec.encode(AaChannel.VIDEO, false, 0x0001, payload);
        // 40002-byte message: FIRST 16384, MIDDLE 16384, LAST 7234
        assertEquals(2 + 6 + 16384 + 2 + 2 + 16384 + 2 + 2 + 7234, wire.length);
        assertEquals(AaChannel.VIDEO, wire[0]);
        assertEquals(AaChannel.FRAME_FIRST, wire[1] & 0x03);
        assertEquals(0x4000, ((wire[2] & 0xFF) << 8) | (wire[3] & 0xFF));
        int total = ((wire[4] & 0xFF) << 24) | ((wire[5] & 0xFF) << 16)
                | ((wire[6] & 0xFF) << 8) | (wire[7] & 0xFF);
        assertEquals(40002, total);

        AaFrame decoded = AaFrameCodec.decode(wire);
        assertArrayEquals(payload, decoded.payload);
    }

    @Test
    public void parserReassemblesSplitMessageByteAtATime() throws Exception {
        byte[] payload = new byte[40000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        byte[] wire = AaFrameCodec.encode(AaChannel.VIDEO, false, 0x0007, payload);
        AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
        List<AaFrame> frames = new ArrayList<AaFrame>();
        for (byte b : wire) {
            parser.feed(new byte[]{b}, 0, 1, frames);
        }
        assertEquals(1, frames.size());
        assertEquals(AaChannel.VIDEO, frames.get(0).channel);
        assertEquals(0x0007, frames.get(0).messageId);
        assertArrayEquals(payload, frames.get(0).payload);
        assertEquals(0, parser.bufferedBytes());
    }

    @Test
    public void rejectsEncryptedFrames() {
        // flags 0x0F = BULK|CONTROL|ENCRYPTED
        try {
            AaFrameCodec.decode(new byte[]{0x00, 0x0F, 0x00, 0x02, 0x00, 0x01});
            fail("expected AaProtocolException");
        } catch (AaProtocolException expected) {
            assertTrue(expected.getMessage().contains("encrypted"));
        }
    }

    @Test
    public void rejectsContinuationWithoutFirst() {
        // flags 0x02 = LAST, no FIRST before it
        try {
            AaFrameCodec.decode(new byte[]{0x03, 0x02, 0x00, 0x02, 0x00, 0x01});
            fail("expected AaProtocolException");
        } catch (AaProtocolException expected) {
            assertTrue(expected.getMessage().contains("continuation"));
        }
    }

    @Test
    public void rejectsShortFrame() {
        try {
            AaFrameCodec.decode(new byte[]{0x00});
            fail("expected AaProtocolException");
        } catch (AaProtocolException expected) {
            assertTrue(expected.getMessage().contains("incomplete"));
        }
    }

    @Test
    public void rejectsPayloadBeyondWireLimit() {
        try {
            AaFrameCodec.encode(AaChannel.VIDEO, false, 0x0001,
                    new byte[AaFrameCodec.MAX_MESSAGE_PAYLOAD]);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("too large"));
        }
    }

    @Test
    public void parserHandlesTwoMessagesInOneRead() throws Exception {
        byte[] first = AaFrameCodec.encode(AaFrame.control(AaMessageType.VERSION_RESPONSE,
                new byte[]{0x08, 0x01, 0x10, 0x06}));
        byte[] second = AaFrameCodec.encode(AaFrame.control(AaMessageType.AUTH_COMPLETE,
                new byte[]{0x08, 0x00}));
        byte[] stream = new byte[first.length + second.length];
        System.arraycopy(first, 0, stream, 0, first.length);
        System.arraycopy(second, 0, stream, first.length, second.length);

        AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
        List<AaFrame> frames = new ArrayList<AaFrame>();
        parser.feed(stream, 0, stream.length, frames);

        assertEquals(2, frames.size());
        assertEquals(AaMessageType.VERSION_RESPONSE, frames.get(0).messageId);
        assertEquals(AaMessageType.AUTH_COMPLETE, frames.get(1).messageId);
        assertEquals(0, parser.bufferedBytes());
    }

    @Test
    public void parserKeepsTrailingPartialFrame() throws Exception {
        byte[] complete = AaFrameCodec.encode(AaFrame.control(AaMessageType.PING_REQUEST,
                new byte[]{0x08, 0x7F}));
        byte[] partial = new byte[]{0x00, 0x07, 0x00};   // header + half a size field
        byte[] stream = new byte[complete.length + partial.length];
        System.arraycopy(complete, 0, stream, 0, complete.length);
        System.arraycopy(partial, 0, stream, complete.length, partial.length);

        AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
        List<AaFrame> frames = new ArrayList<AaFrame>();
        parser.feed(stream, 0, stream.length, frames);

        assertEquals(1, frames.size());
        assertEquals(3, parser.bufferedBytes());

        byte[] rest = new byte[]{0x02, 0x00, 0x0C};
        parser.feed(rest, 0, rest.length, frames);
        assertEquals(2, frames.size());
        assertEquals(AaMessageType.PING_RESPONSE, frames.get(1).messageId);
        assertEquals(0, parser.bufferedBytes());
    }

    @Test
    public void parserDoesNotAllocateForUnfulfilledDeclaration() throws Exception {
        // A hostile peer can declare 0x4000 and then send nothing. The parser must hold only the
        // bytes it actually received instead of allocating the declared payload.
        AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
        List<AaFrame> frames = new ArrayList<AaFrame>();
        parser.feed(new byte[]{0x00, 0x07, 0x40, 0x00}, 0, 4, frames);
        assertEquals(0, frames.size());
        assertEquals(4, parser.bufferedBytes());
    }

    @Test
    public void parserRejectsOutOfBoundsFeed() throws Exception {
        AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
        try {
            parser.feed(new byte[2], 1, 4, new ArrayList<AaFrame>());
            fail("expected AaProtocolException");
        } catch (AaProtocolException expected) {
            assertTrue(expected.getMessage().contains("bounds"));
        }
    }

    @Test
    public void messageNamesAreStable() {
        assertEquals("VERSION_REQUEST", AaMessageType.name(AaMessageType.VERSION_REQUEST));
        assertEquals("SERVICE_DISCOVERY_RESPONSE", AaMessageType.name(0x0006));
        assertEquals("UNKNOWN_0x00AA", AaMessageType.name(0x00AA));
        assertTrue(AaMessageType.isKnown(0x0013));
        assertTrue(!AaMessageType.isKnown(0x0100));
        assertEquals("video", AaChannel.name(AaChannel.VIDEO));
    }

    /** A cipher that visibly changes sizes, like TLS does: 3 bytes of overhead and an XOR. */
    private static final class FakeCipher implements AaFrameCodec.Cipher {
        @Override
        public byte[] encrypt(byte[] data, int offset, int length) {
            byte[] out = new byte[length + 3];
            out[0] = out[1] = out[2] = (byte) 0xEE;
            for (int i = 0; i < length; i++) {
                out[3 + i] = (byte) (data[offset + i] ^ 0x5A);
            }
            return out;
        }

        @Override
        public byte[] decrypt(byte[] data, int offset, int length) throws AaProtocolException {
            if (length < 3 || data[offset] != (byte) 0xEE) {
                throw new AaProtocolException("not a FakeCipher record");
            }
            byte[] out = new byte[length - 3];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) (data[offset + 3 + i] ^ 0x5A);
            }
            return out;
        }
    }

    @Test
    public void encryptedFrameHasTheFlagAndTheEncryptedSize() throws Exception {
        byte[] frame = AaFrameCodec.encode(AaChannel.VIDEO, false, 0x8004, new byte[]{0x08, 0x01},
                new FakeCipher());
        // channel 3, flags BULK|ENCRYPTED = 0x0B, size = (2 id + 2 payload) + 3 overhead = 7
        assertEquals(0x03, frame[0]);
        assertEquals(0x0B, frame[1]);
        assertEquals(7, ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF));
        assertEquals(4 + 7, frame.length);
    }

    @Test
    public void splitEncryptedMessageEncryptsEachFrameAndTotalsThePlaintext() throws Exception {
        byte[] payload = new byte[40000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 3);
        }
        byte[] wire = AaFrameCodec.encode(AaChannel.VIDEO, false, 0x0001, payload, new FakeCipher());

        // FIRST frame: flags FIRST|ENCRYPTED = 0x09, size = 0x4000 + 3, total = 40000 + 2 (plaintext)
        assertEquals(0x09, wire[1]);
        assertEquals(0x4000 + 3, ((wire[2] & 0xFF) << 8) | (wire[3] & 0xFF));
        long total = ((wire[4] & 0xFFL) << 24) | ((wire[5] & 0xFFL) << 16)
                | ((wire[6] & 0xFFL) << 8) | (wire[7] & 0xFFL);
        assertEquals(40002L, total);

        AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
        parser.setCipher(new FakeCipher());
        List<AaFrame> frames = new java.util.ArrayList<AaFrame>();
        parser.feed(wire, 0, wire.length, frames);
        assertEquals(1, frames.size());
        assertTrue(frames.get(0).encrypted);
        assertArrayEquals(payload, frames.get(0).payload);
    }

    @Test
    public void encryptedFrameWithoutACipherIsAProtocolError() throws Exception {
        byte[] wire = AaFrameCodec.encode(AaChannel.CONTROL, false, 0x000B, new byte[0],
                new FakeCipher());
        try {
            new AaFrameCodec.Parser().feed(wire, 0, wire.length, new java.util.ArrayList<AaFrame>());
            org.junit.Assert.fail("expected AaProtocolException");
        } catch (AaProtocolException expected) {
            assertTrue(expected.getMessage().contains("TLS"));
        }
    }

    @Test
    public void theCipherCanBeInstalledBetweenTwoMessagesOfOneRead() throws Exception {
        // One TCP read holds the last (plain) handshake message AND the first encrypted frame.
        // The first message's handler switches encryption on; the second frame must then decrypt.
        byte[] plain = AaFrameCodec.encode(AaChannel.CONTROL, false, AaMessageType.SSL_HANDSHAKE,
                new byte[]{1, 2, 3});
        byte[] secret = AaFrameCodec.encode(AaChannel.CONTROL, false, AaMessageType.PING_REQUEST,
                new byte[]{9, 9}, new FakeCipher());
        byte[] both = new byte[plain.length + secret.length];
        System.arraycopy(plain, 0, both, 0, plain.length);
        System.arraycopy(secret, 0, both, plain.length, secret.length);

        final AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
        final List<AaFrame> seen = new java.util.ArrayList<AaFrame>();
        parser.feed(both, 0, both.length, new AaFrameCodec.Listener() {
            @Override
            public void onMessage(AaFrame frame) {
                seen.add(frame);
                if (frame.messageId == AaMessageType.SSL_HANDSHAKE) {
                    parser.setCipher(new FakeCipher());
                }
            }
        });
        assertEquals(2, seen.size());
        assertTrue(!seen.get(0).encrypted);
        assertTrue(seen.get(1).encrypted);
        assertArrayEquals(new byte[]{9, 9}, seen.get(1).payload);
    }
}
