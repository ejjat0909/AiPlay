package com.ejjat.aiplay.core.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ProtoTest {

    @Test
    public void varintMatchesProtobufReferenceVectors() {
        assertArrayEquals(new byte[]{0x08, 0x00}, new ProtoWriter().varint(1, 0).toByteArray());
        assertArrayEquals(new byte[]{0x08, 0x01}, new ProtoWriter().varint(1, 1).toByteArray());
        assertArrayEquals(new byte[]{0x08, (byte) 0x96, 0x01}, new ProtoWriter().varint(1, 150).toByteArray());
        assertArrayEquals(new byte[]{0x08, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F},
                new ProtoWriter().varint(1, 0xFFFFFFFFL).toByteArray());
    }

    @Test
    public void lengthDelimitedAndNested() {
        ProtoWriter inner = new ProtoWriter().varint(1, 7);
        byte[] encoded = new ProtoWriter().message(2, inner).toByteArray();
        assertArrayEquals(new byte[]{0x12, 0x02, 0x08, 0x07}, encoded);
    }

    @Test
    public void readerRoundTripsEverything() throws Exception {
        byte[] encoded = new ProtoWriter()
                .varint(1, 42)
                .string(2, "AiPlay")
                .bool(3, true)
                .bytes(4, new byte[]{9, 8, 7})
                .toByteArray();

        ProtoReader reader = new ProtoReader(encoded);
        int seen = 0;
        while (reader.hasMore()) {
            int tag = reader.readTag();
            switch (ProtoReader.field(tag)) {
                case 1:
                    assertEquals(42, reader.readVarint());
                    seen |= 1;
                    break;
                case 2:
                    assertEquals("AiPlay", reader.readString());
                    seen |= 2;
                    break;
                case 3:
                    assertEquals(1, reader.readVarint());
                    seen |= 4;
                    break;
                case 4:
                    assertArrayEquals(new byte[]{9, 8, 7}, reader.readBytes());
                    seen |= 8;
                    break;
                default:
                    fail("unexpected field " + ProtoReader.field(tag));
            }
        }
        assertEquals(15, seen);
    }

    @Test
    public void readerRejectsTruncatedVarint() {
        try {
            new ProtoReader(new byte[]{(byte) 0x80}).readVarint();
            fail("expected AaProtocolException");
        } catch (AaProtocolException expected) {
            assertTrue(expected.getMessage().contains("truncated"));
        }
    }

    @Test
    public void readerRejectsOverlongLengthDelimitedField() {
        try {
            new ProtoReader(new byte[]{0x0A, 0x7F, 0x01}).readBytes();
            fail("expected AaProtocolException");
        } catch (AaProtocolException expected) {
            assertTrue(expected.getMessage().contains("overruns"));
        }
    }

    @Test
    public void skipHandlesFixedWidthTypes() throws Exception {
        ProtoReader reader = new ProtoReader(new byte[]{0x09, 1, 2, 3, 4, 5, 6, 7, 8, 0x08, 0x02});
        int tag = reader.readTag();
        assertEquals(1, ProtoReader.field(tag));
        assertEquals(1, ProtoReader.wireType(tag));
        reader.skip(ProtoReader.wireType(tag));
        int next = reader.readTag();
        assertEquals(1, ProtoReader.field(next));
        assertEquals(2, reader.readVarint());
        assertFalse(reader.hasMore());
    }
}
