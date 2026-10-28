package com.ejjat.aiplay.core.qr;

import java.nio.charset.Charset;

/**
 * A small QR Code encoder: byte mode, error-correction level M, versions 1 to 10 (up to 213 bytes).
 * Enough for a Wi-Fi join code; written here because AiPlay ships no libraries.
 *
 * <p>Pure Java, no Android types. Verified two ways: structural unit tests, and by decoding rendered
 * images with an independent decoder (see tests/QR-VERIFY in docs/TESTING.md).</p>
 */
public final class QrCode {

    public static final int MAX_VERSION = 10;
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    private final int version;
    private final int size;
    private final int mask;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    /** Encodes UTF-8 text. Throws IllegalArgumentException when it does not fit in version 10. */
    public static QrCode encodeText(String text) {
        return encodeText(text, -1);
    }

    /** As {@link #encodeText(String)} but with a fixed mask 0..7 (for tests); -1 picks the best. */
    public static QrCode encodeText(String text, int forcedMask) {
        byte[] data = text.getBytes(Charset.forName("UTF-8"));
        for (int version = 1; version <= MAX_VERSION; version++) {
            int capacityBits = numDataCodewords(version) * 8;
            int countBits = version <= 9 ? 8 : 16;
            int needed = 4 + countBits + data.length * 8;
            if (needed <= capacityBits) {
                return new QrCode(version, data, forcedMask);
            }
        }
        throw new IllegalArgumentException("text too long for a version " + MAX_VERSION
                + " QR code (" + data.length + " bytes)");
    }

    private QrCode(int version, byte[] data, int forcedMask) {
        this.version = version;
        this.size = version * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];

        drawFunctionPatterns();
        byte[] all = addEccAndInterleave(encodeData(version, data));
        drawCodewords(all);

        int best = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            if (forcedMask >= 0 && m != forcedMask) {
                continue;
            }
            applyMask(m);
            drawFormatBits(m);
            int penalty = penalty();
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                best = m;
            }
            applyMask(m); // undo
        }
        this.mask = best;
        applyMask(best);
        drawFormatBits(best);
    }

    public int version() {
        return version;
    }

    public int size() {
        return size;
    }

    public int mask() {
        return mask;
    }

    /** True for a dark module. x is the column, y the row. */
    public boolean get(int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size && modules[y][x];
    }

    // ---- data -----------------------------------------------------------------------------

    private static int numRawModules(int version) {
        int result = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int numAlign = version / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (version >= 7) {
                result -= 36;
            }
        }
        return result;
    }

    private static int numDataCodewords(int version) {
        return numRawModules(version) / 8 - ECC_PER_BLOCK[version] * NUM_BLOCKS[version];
    }

    private static byte[] encodeData(int version, byte[] data) {
        int capacityBits = numDataCodewords(version) * 8;
        BitBuffer bits = new BitBuffer();
        bits.append(0x4, 4); // byte mode
        bits.append(data.length, version <= 9 ? 8 : 16);
        for (byte b : data) {
            bits.append(b & 0xFF, 8);
        }
        bits.append(0, Math.min(4, capacityBits - bits.length()));
        bits.append(0, (8 - bits.length() % 8) % 8);
        for (int pad = 0xEC; bits.length() < capacityBits; pad ^= 0xEC ^ 0x11) {
            bits.append(pad, 8);
        }
        return bits.toBytes();
    }

    private static byte[] addEccAndInterleave(byte[] data) {
        int version = versionForDataLength(data.length);
        int numBlocks = NUM_BLOCKS[version];
        int blockEcc = ECC_PER_BLOCK[version];
        int rawCodewords = numRawModules(version) / 8;
        int numShort = numBlocks - rawCodewords % numBlocks;
        int shortLen = rawCodewords / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] divisor = reedSolomonDivisor(blockEcc);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int len = shortLen - blockEcc + (i < numShort ? 0 : 1);
            byte[] dat = new byte[len];
            System.arraycopy(data, k, dat, 0, len);
            k += len;
            byte[] ecc = reedSolomonRemainder(dat, divisor);
            // Every block gets shortLen + 1 slots; a short block leaves one data slot unused, which
            // the interleaving below skips.
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, len);
            System.arraycopy(ecc, 0, block, block.length - blockEcc, blockEcc);
            blocks[i] = block;
        }
        byte[] result = new byte[rawCodewords];
        int out = 0;
        for (int i = 0; i < shortLen + 1; i++) {
            for (int j = 0; j < numBlocks; j++) {
                // Short blocks have no data byte where long blocks have their extra one.
                if (i != shortLen - blockEcc || j >= numShort) {
                    result[out++] = blocks[j][i];
                }
            }
        }
        return result;
    }

    private static int versionForDataLength(int dataCodewords) {
        for (int v = 1; v <= MAX_VERSION; v++) {
            if (numDataCodewords(v) == dataCodewords) {
                return v;
            }
        }
        throw new IllegalStateException("no version with " + dataCodewords + " data codewords");
    }

    // ---- Reed-Solomon over GF(256), polynomial 0x11D ---------------------------------------

    private static int gfMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static byte[] reedSolomonDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMultiply(result[j] & 0xFF, root);
                if (j + 1 < degree) {
                    result[j] ^= result[j + 1];
                }
            }
            root = gfMultiply(root, 0x02);
        }
        return result;
    }

    private static byte[] reedSolomonRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) {
                result[i] ^= (byte) gfMultiply(divisor[i] & 0xFF, factor);
            }
        }
        return result;
    }

    // ---- drawing --------------------------------------------------------------------------

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            set(6, i, i % 2 == 0);
            set(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);

        int[] align = alignmentPositions();
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (!((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0))) {
                    drawAlignment(align[i], align[j]);
                }
            }
        }
        drawFormatBits(0); // reserve the area; rewritten with the real mask later
        drawVersion();
    }

    private void drawFinder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) {
                    set(x, y, dist != 2 && dist != 4);
                }
            }
        }
    }

    private void drawAlignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                set(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
    }

    private int[] alignmentPositions() {
        if (version == 1) {
            return new int[0];
        }
        int numAlign = version / 7 + 2;
        int step = (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = numAlign - 1, pos = size - 7; i >= 1; i--, pos -= step) {
            result[i] = pos;
        }
        return result;
    }

    private void drawFormatBits(int msk) {
        int data = (0 << 3) | msk; // error-correction level M has format bits 00
        int rem = data;
        for (int i = 0; i < 10; i++) {
            rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) {
            set(8, i, bit(bits, i));
        }
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) {
            set(14 - i, 8, bit(bits, i));
        }
        for (int i = 0; i < 8; i++) {
            set(size - 1 - i, 8, bit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            set(8, size - 15 + i, bit(bits, i));
        }
        set(8, size - 8, true); // the always-dark module
    }

    private void drawVersion() {
        if (version < 7) {
            return;
        }
        int rem = version;
        for (int i = 0; i < 12; i++) {
            rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        }
        int bits = (version << 12) | rem;
        for (int i = 0; i < 18; i++) {
            boolean dark = bit(bits, i);
            int a = size - 11 + i % 3;
            int b = i / 3;
            set(a, b, dark);
            set(b, a, dark);
        }
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int msk) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean invert;
                switch (msk) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (!isFunction[y][x] && invert) {
                    modules[y][x] = !modules[y][x];
                }
            }
        }
    }

    // ---- mask choice: the four standard penalty rules -------------------------------------

    private int penalty() {
        int total = 0;
        for (int pass = 0; pass < 2; pass++) { // rows, then columns
            for (int a = 0; a < size; a++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    if (at(pass, a, b) == at(pass, a, b - 1)) {
                        run++;
                        if (run == 5) {
                            total += 3;
                        } else if (run > 5) {
                            total++;
                        }
                    } else {
                        run = 1;
                    }
                }
                // finder-like 1:1:3:1:1 with four light modules on either side
                for (int b = 0; b + 11 <= size; b++) {
                    if (matches(pass, a, b, true) || matches(pass, a, b, false)) {
                        total += 40;
                    }
                }
            }
        }
        for (int y = 0; y + 1 < size; y++) {
            for (int x = 0; x + 1 < size; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) {
                    total += 3;
                }
            }
        }
        int dark = 0;
        for (boolean[] row : modules) {
            for (boolean m : row) {
                if (m) {
                    dark++;
                }
            }
        }
        int percent = dark * 100 / (size * size);
        total += Math.abs(percent - 50) / 5 * 10;
        return total;
    }

    private boolean at(int pass, int a, int b) {
        return pass == 0 ? modules[a][b] : modules[b][a];
    }

    private boolean matches(int pass, int a, int b, boolean darkFirst) {
        boolean[] pattern = {true, false, true, true, true, false, true, false, false, false, false};
        for (int i = 0; i < 11; i++) {
            boolean expected = darkFirst ? pattern[i] : pattern[10 - i];
            if (at(pass, a, b + i) != expected) {
                return false;
            }
        }
        return true;
    }

    private static boolean bit(int value, int index) {
        return ((value >>> index) & 1) != 0;
    }

    // ---- bits -----------------------------------------------------------------------------

    private static final class BitBuffer {
        private final java.util.ArrayList<Boolean> bits = new java.util.ArrayList<Boolean>();

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                bits.add(((value >>> i) & 1) != 0);
            }
        }

        int length() {
            return bits.size();
        }

        byte[] toBytes() {
            byte[] out = new byte[(bits.size() + 7) / 8];
            for (int i = 0; i < bits.size(); i++) {
                if (bits.get(i)) {
                    out[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
                }
            }
            return out;
        }
    }
}
