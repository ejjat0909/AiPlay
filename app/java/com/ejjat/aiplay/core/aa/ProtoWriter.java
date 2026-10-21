package com.ejjat.aiplay.core.aa;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * Minimal protobuf wire-format writer (proto3 semantics, no required fields).
 *
 * <p>AiPlay deliberately does not depend on a protobuf runtime: the Android Auto control plane
 * uses a handful of small messages, and a 120-line writer is smaller, faster and easier to audit
 * than generated code for the whole schema.</p>
 */
public final class ProtoWriter {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int WIRE_VARINT = 0;
    private static final int WIRE_LENGTH = 2;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

    public ProtoWriter varint(int field, long value) {
        writeTag(field, WIRE_VARINT);
        writeRawVarint(value);
        return this;
    }

    public ProtoWriter bool(int field, boolean value) {
        return varint(field, value ? 1 : 0);
    }

    public ProtoWriter bytes(int field, byte[] value) {
        writeTag(field, WIRE_LENGTH);
        writeRawVarint(value.length);
        out.write(value, 0, value.length);
        return this;
    }

    public ProtoWriter string(int field, String value) {
        if (value == null || value.isEmpty()) {
            return this;
        }
        return bytes(field, value.getBytes(UTF_8));
    }

    public ProtoWriter message(int field, ProtoWriter nested) {
        return bytes(field, nested.toByteArray());
    }

    public int size() {
        return out.size();
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }

    private void writeTag(int field, int wireType) {
        if (field <= 0 || field > 0x1FFFFFFF) {
            throw new IllegalArgumentException("field out of range: " + field);
        }
        writeRawVarint(((long) field << 3) | wireType);
    }

    private void writeRawVarint(long value) {
        long v = value;
        while ((v & ~0x7FL) != 0L) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
    }
}
