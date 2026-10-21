package com.ejjat.aiplay.core.aa;

/**
 * Just enough Annex-B H.264 inspection to start a decoder at the right place.
 *
 * <p>Android Auto sends video as Annex-B byte streams (start codes {@code 00 00 01} or
 * {@code 00 00 00 01}). A decoder that starts mid-GOP shows corrupt pictures until the next
 * keyframe, so the receiver needs to know whether a message carries SPS/PPS or an IDR slice.</p>
 */
public final class H264 {

    public static final int NAL_SLICE = 1;
    public static final int NAL_IDR = 5;
    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;

    private H264() {
    }

    /**
     * Bit {@code n} of the result is set when a NAL unit of type {@code n} (0..31) is present in
     * {@code data[offset, offset + length)}.
     */
    public static int nalTypeMask(byte[] data, int offset, int length) {
        int mask = 0;
        int end = offset + length;
        for (int i = offset; i + 3 <= end; i++) {
            // 00 00 01 NAL — a four byte start code is the same pattern preceded by one more zero.
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                if (i + 3 < end) {
                    mask |= 1 << (data[i + 3] & 0x1F);
                }
                i += 2;
            }
        }
        return mask;
    }

    /**
     * Length of the leading run of non-picture NAL units (SPS, PPS, SEI, AUD) in a chunk: the
     * offset of the first slice's start code, or {@code length} when the chunk has no slice. This
     * is the decoder configuration that can be replayed to a decoder created later.
     */
    public static int configPrefixLength(byte[] data, int offset, int length) {
        int end = offset + length;
        for (int i = offset; i + 3 < end; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                int type = data[i + 3] & 0x1F;
                if (type == NAL_SLICE || type == NAL_IDR) {
                    int start = (i > offset && data[i - 1] == 0) ? i - 1 : i;
                    return start - offset;
                }
                i += 2;
            }
        }
        return length;
    }

    public static boolean hasSps(int mask) {
        return (mask & (1 << NAL_SPS)) != 0;
    }

    public static boolean hasPps(int mask) {
        return (mask & (1 << NAL_PPS)) != 0;
    }

    public static boolean hasIdr(int mask) {
        return (mask & (1 << NAL_IDR)) != 0;
    }
}
