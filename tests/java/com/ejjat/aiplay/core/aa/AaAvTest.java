package com.ejjat.aiplay.core.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Wire vectors for the AV channel messages, computed by hand from the aasdk protobufs. */
public class AaAvTest {

    @Test
    public void messageIdsMatchAasdk() {
        assertEquals(0x0000, AaAv.MEDIA_WITH_TIMESTAMP);
        assertEquals(0x0001, AaAv.MEDIA);
        assertEquals(0x8000, AaAv.SETUP_REQUEST);
        assertEquals(0x8001, AaAv.START_INDICATION);
        assertEquals(0x8002, AaAv.STOP_INDICATION);
        assertEquals(0x8003, AaAv.SETUP_RESPONSE);
        assertEquals(0x8004, AaAv.MEDIA_ACK);
        assertEquals(0x8007, AaAv.VIDEO_FOCUS_REQUEST);
        assertEquals(0x8008, AaAv.VIDEO_FOCUS_INDICATION);
        assertEquals(2, AaAv.SETUP_OK); // not 1: 0.2.0 was almost written with the wrong value
    }

    @Test
    public void setupResponseEncodesStatusMaxUnackedAndConfigs() {
        assertArrayEquals(new byte[]{0x08, 0x02, 0x10, 0x01, 0x18, 0x00},
                AaAv.setupResponse(AaAv.SETUP_OK, 1, 0));
        assertArrayEquals(new byte[]{0x08, 0x02, 0x10, 0x04, 0x18, 0x00, 0x18, 0x01},
                AaAv.setupResponse(AaAv.SETUP_OK, 4, 0, 1));
    }

    @Test
    public void mediaAckEncodesSessionAndValue() {
        assertArrayEquals(new byte[]{0x08, 0x05, 0x10, 0x01}, AaAv.mediaAck(5, 1));
    }

    @Test
    public void focusIndicationEncodesModeAndUnrequested() {
        assertArrayEquals(new byte[]{0x08, 0x01, 0x10, 0x00},
                AaAv.videoFocusIndication(AaAv.FOCUS_FOCUSED, false));
        assertArrayEquals(new byte[]{0x08, 0x02, 0x10, 0x01},
                AaAv.videoFocusIndication(AaAv.FOCUS_UNFOCUSED, true));
    }

    @Test
    public void parsesRequests() {
        assertEquals(0, AaAv.parseSetupRequest(new byte[]{0x08, 0x00}));
        assertEquals(-1, AaAv.parseSetupRequest(new byte[0]));
        int[] start = AaAv.parseStartIndication(new byte[]{0x08, 0x09, 0x10, 0x00});
        assertEquals(9, start[0]);
        assertEquals(0, start[1]);
        assertEquals(AaAv.FOCUS_FOCUSED,
                AaAv.parseVideoFocusMode(new byte[]{0x08, 0x00, 0x10, 0x01, 0x18, 0x01}));
        assertEquals(-1, AaAv.parseVideoFocusMode(new byte[0]));
    }

    @Test
    public void truncatedMessagesYieldMinusOneInsteadOfThrowing() {
        assertEquals(-1, AaAv.parseSetupRequest(new byte[]{0x08}));
        assertEquals(-1, AaAv.parseStartIndication(new byte[]{(byte) 0x80})[0]);
    }

    @Test
    public void mediaTimestampIsEightBytesBigEndian() {
        byte[] payload = {0, 0, 0, 0, 0, 1, 0, 0, 0x65};
        assertEquals(65536L, AaAv.mediaTimestamp(AaAv.MEDIA_WITH_TIMESTAMP, payload));
        assertEquals(8, AaAv.mediaDataOffset(AaAv.MEDIA_WITH_TIMESTAMP, payload));
        assertEquals(-1L, AaAv.mediaTimestamp(AaAv.MEDIA, payload));
        assertEquals(0, AaAv.mediaDataOffset(AaAv.MEDIA, payload));
    }

    @Test
    public void h264MaskFindsSpsPpsAndIdrWithBothStartCodeLengths() {
        byte[] stream = {
                0, 0, 0, 1, 0x67, 1, 2,        // SPS (4-byte start code)
                0, 0, 1, 0x68, 3,              // PPS (3-byte start code)
                0, 0, 0, 1, 0x65, 9, 9, 9};    // IDR slice
        int mask = H264.nalTypeMask(stream, 0, stream.length);
        assertTrue(H264.hasSps(mask));
        assertTrue(H264.hasPps(mask));
        assertTrue(H264.hasIdr(mask));
    }

    @Test
    public void h264MaskDistinguishesAPFrame() {
        byte[] stream = {0, 0, 0, 1, 0x41, 1, 2, 3};
        int mask = H264.nalTypeMask(stream, 0, stream.length);
        assertFalse(H264.hasIdr(mask));
        assertFalse(H264.hasSps(mask));
        assertTrue((mask & (1 << H264.NAL_SLICE)) != 0);
    }

    @Test
    public void h264MaskRespectsOffsetAndLengthAndTrailingStartCode() {
        byte[] stream = {0, 0, 0, 1, 0x65, 0, 0, 0, 1, 0x41};
        // only the second NAL is inside the window
        int mask = H264.nalTypeMask(stream, 5, 5);
        assertFalse(H264.hasIdr(mask));
        // a start code with no NAL byte after it must not read past the window
        assertEquals(0, H264.nalTypeMask(new byte[]{0, 0, 1}, 0, 3));
    }

    @Test
    public void configPrefixStopsAtTheFirstSlice() {
        byte[] stream = {
                0, 0, 0, 1, 0x67, 1, 2,        // SPS
                0, 0, 0, 1, 0x68, 3,           // PPS
                0, 0, 0, 1, 0x65, 9, 9};       // IDR
        assertEquals(13, H264.configPrefixLength(stream, 0, stream.length));
        // no slice at all: the whole chunk is configuration
        assertEquals(7, H264.configPrefixLength(new byte[]{0, 0, 0, 1, 0x67, 1, 2}, 0, 7));
        // a picture with no configuration in front of it has an empty prefix
        assertEquals(0, H264.configPrefixLength(new byte[]{0, 0, 0, 1, 0x41, 1}, 0, 6));
    }
}
