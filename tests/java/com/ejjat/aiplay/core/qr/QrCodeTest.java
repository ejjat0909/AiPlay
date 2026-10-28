package com.ejjat.aiplay.core.qr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Structure checks. Whether the matrix really <em>decodes</em> is checked against an independent
 * decoder outside the JVM (docs/TESTING.md, "QR verification"); these pin the parts a decoder needs.
 */
public class QrCodeTest {

    @Test
    public void sizeFollowsVersion() {
        QrCode q = QrCode.encodeText("hi");
        assertEquals(1, q.version());
        assertEquals(21, q.size());
        QrCode big = QrCode.encodeText("WIFI:T:WPA;S:DIRECT-xy-Example;P:Example12;;");
        assertEquals(big.version() * 4 + 17, big.size());
        assertTrue(big.version() >= 3 && big.version() <= 5);
    }

    @Test
    public void finderPatternsAreInThreeCorners() {
        QrCode q = QrCode.encodeText("WIFI:T:WPA;S:a;P:bbbbbbbb;;");
        int n = q.size();
        int[][] corners = {{0, 0}, {n - 7, 0}, {0, n - 7}};
        for (int[] c : corners) {
            for (int dy = 0; dy < 7; dy++) {
                for (int dx = 0; dx < 7; dx++) {
                    boolean ring = dx == 0 || dy == 0 || dx == 6 || dy == 6;
                    boolean core = dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4;
                    assertEquals(ring || core, q.get(c[0] + dx, c[1] + dy));
                }
            }
        }
    }

    @Test
    public void timingPatternAlternates() {
        QrCode q = QrCode.encodeText("WIFI:T:WPA;S:abc;P:12345678;;");
        for (int i = 8; i < q.size() - 8; i++) {
            assertEquals(i % 2 == 0, q.get(i, 6));
            assertEquals(i % 2 == 0, q.get(6, i));
        }
    }

    @Test
    public void formatInformationIsAValidBchCodeword() {
        QrCode q = QrCode.encodeText("WIFI:T:WPA;S:abc;P:12345678;;");
        int bits = 0;
        // first copy, bits 0..14 as written by the encoder
        for (int i = 0; i <= 5; i++) bits |= (q.get(8, i) ? 1 : 0) << i;
        bits |= (q.get(8, 7) ? 1 : 0) << 6;
        bits |= (q.get(8, 8) ? 1 : 0) << 7;
        bits |= (q.get(7, 8) ? 1 : 0) << 8;
        for (int i = 9; i < 15; i++) bits |= (q.get(14 - i, 8) ? 1 : 0) << i;
        int data = (bits ^ 0x5412) >>> 10;
        assertEquals("error-correction level M is 00", 0, data >>> 3);
        assertEquals(q.mask(), data & 7);
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        assertEquals(((data << 10) | rem) ^ 0x5412, bits);
    }

    @Test
    public void darkModuleIsAlwaysSet() {
        QrCode q = QrCode.encodeText("x");
        assertTrue(q.get(8, q.size() - 8));
    }

    @Test
    public void sameTextGivesSameMatrix() {
        QrCode a = QrCode.encodeText("WIFI:T:WPA;S:n;P:Example12;;");
        QrCode b = QrCode.encodeText("WIFI:T:WPA;S:n;P:Example12;;");
        for (int y = 0; y < a.size(); y++) {
            for (int x = 0; x < a.size(); x++) {
                assertEquals(a.get(x, y), b.get(x, y));
            }
        }
    }

    @Test
    public void tooLongTextIsRefused() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 300; i++) s.append('a');
        try {
            QrCode.encodeText(s.toString());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("too long"));
        }
    }

    @Test
    public void everyVersionUpToTenBuilds() {
        for (int len = 1; len <= 200; len += 7) {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < len; i++) s.append((char) ('a' + i % 26));
            QrCode q = QrCode.encodeText(s.toString());
            assertTrue(q.version() >= 1 && q.version() <= QrCode.MAX_VERSION);
        }
    }

    private static long fingerprint(QrCode q) {
        long h = 0xcbf29ce484222325L;
        for (int y = 0; y < q.size(); y++) {
            for (int x = 0; x < q.size(); x++) {
                h ^= q.get(x, y) ? 1 : 0;
                h *= 0x100000001b3L;
            }
        }
        return h;
    }

    /**
     * Golden matrices. Both were rendered to PNG and decoded back to the exact input with an
     * independent decoder (macOS Vision). The second is a version 10 code with several
     * error-correction blocks, which is what catches interleaving mistakes: the first version of the
     * encoder skipped a data byte there and produced codes nothing could read.
     */
    @Test
    public void goldenMatricesThatWereDecodedByAnIndependentDecoder() {
        QrCode wifi = QrCode.encodeText(WifiQr.payload("DIRECT-xy-Example", "Example12"));
        assertEquals(4, wifi.version());
        assertEquals(-2822382685441294646L, fingerprint(wifi));

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 150; i++) text.append('x');
        QrCode multi = QrCode.encodeText(text + "WIFI:T:WPA;S:long;P:Example12;;");
        assertEquals(10, multi.version());
        assertEquals(-2451969337286432083L, fingerprint(multi));
    }

    @Test
    public void wifiPayloadFormat() {
        assertEquals("WIFI:T:WPA;S:DIRECT-xy-Example;P:Example12;;",
                WifiQr.payload("DIRECT-xy-Example", "Example12"));
        assertEquals("WIFI:T:nopass;S:open;;", WifiQr.payload("open", ""));
    }

    @Test
    public void wifiPayloadEscapesSpecialCharacters() {
        assertEquals("WIFI:T:WPA;S:a\\;b\\:c;P:p\\\\w\\,d\\\";;", WifiQr.payload("a;b:c", "p\\w,d\""));
    }
}
