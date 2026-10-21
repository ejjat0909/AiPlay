package com.ejjat.aiplay.core.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class AaFrameCodecTest {

    @Test
    public void encodesLengthAndTypeBigEndian() {
        byte[] frame = AaFrameCodec.encode(AaMessageType.VERSION_REQUEST, new byte[]{0x08, 0x01});
        assertArrayEquals(new byte[]{0x00, 0x02, 0x00, 0x01, 0x08, 0x01}, frame);
    }

    @Test
    public void roundTripsEmptyPayload() throws Exception {
        byte[] frame = AaFrameCodec.encode(AaMessageType.PING_REQUEST, new byte[0]);
        AaFrame decoded = AaFrameCodec.decode(frame);
        assertEquals(AaMessageType.PING_REQUEST, decoded.type);
        assertEquals(0, decoded.payload.length);
    }

    @Test
    public void roundTripsLargePayload() throws Exception {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        AaFrame decoded = AaFrameCodec.decode(AaFrameCodec.encode(0x1234, payload));
        assertEquals(0x1234, decoded.type);
        assertArrayEquals(payload, decoded.payload);
    }

    @Test
    public void rejectsShortFrame() {
        try {
            AaFrameCodec.decode(new byte[]{0x00, 0x01});
            fail("expected AaProtocolException");
        } catch (AaProtocolException expected) {
            assertTrue(expected.getMessage().contains("header"));
        }
    }

    @Test
    public void rejectsLengthMismatch() {
        try {
            AaFrameCodec.decode(new byte[]{0x00, 0x05, 0x00, 0x01, 0x00});
            fail("expected AaProtocolException");
        } catch (AaProtocolException expected) {
            assertTrue(expected.getMessage().contains("mismatch"));
        }
    }

    @Test
    public void rejectsPayloadBeyondWireLimit() {
        try {
            AaFrameCodec.encode(0x0001, new byte[AaFrameCodec.MAX_PAYLOAD + 1]);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("too large"));
        }
    }

    @Test
    public void parserHandlesByteAtATimeFragmentation() throws Exception {
        byte[] first = AaFrameCodec.encode(AaMessageType.VERSION_RESPONSE, new byte[]{0x08, 0x01, 0x10, 0x06});
        byte[] second = AaFrameCodec.encode(AaMessageType.AUTH_COMPLETE, new byte[]{0x08, 0x00});
        byte[] stream = new byte[first.length + second.length];
        System.arraycopy(first, 0, stream, 0, first.length);
        System.arraycopy(second, 0, stream, first.length, second.length);

        AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
        List<AaFrame> frames = new ArrayList<AaFrame>();
        for (byte b : stream) {
            parser.feed(new byte[]{b}, 0, 1, frames);
        }
        assertEquals(2, frames.size());
        assertEquals(AaMessageType.VERSION_RESPONSE, frames.get(0).type);
        assertEquals(AaMessageType.AUTH_COMPLETE, frames.get(1).type);
        assertEquals(0, parser.bufferedBytes());
    }

    @Test
    public void parserKeepsTrailingPartialFrame() throws Exception {
        byte[] complete = AaFrameCodec.encode(AaMessageType.PING_REQUEST, new byte[]{0x08, 0x7F});
        byte[] partial = new byte[]{0x00, 0x04, 0x00, 0x0C, 0x08};
        byte[] stream = new byte[complete.length + partial.length];
        System.arraycopy(complete, 0, stream, 0, complete.length);
        System.arraycopy(partial, 0, stream, complete.length, partial.length);

        AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
        List<AaFrame> frames = new ArrayList<AaFrame>();
        parser.feed(stream, 0, stream.length, frames);

        assertEquals(1, frames.size());
        assertEquals(5, parser.bufferedBytes());

        byte[] rest = new byte[]{0x01, 0x02, 0x03};
        parser.feed(rest, 0, rest.length, frames);
        assertEquals(2, frames.size());
        assertEquals(AaMessageType.PING_RESPONSE, frames.get(1).type);
        assertEquals(0, parser.bufferedBytes());
    }

    @Test
    public void parserDoesNotAllocateForUnfulfilledDeclaration() throws Exception {
        // A hostile peer can declare 64 KiB and then send nothing. The parser must hold only the
        // header it actually received instead of allocating the declared payload.
        AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
        List<AaFrame> frames = new ArrayList<AaFrame>();
        parser.feed(new byte[]{(byte) 0xFF, (byte) 0xFF, 0x00, 0x01}, 0, 4, frames);
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
    }
}
