package com.ejjat.aiplay.core.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Contract tests for the Android Auto control session.
 *
 * <p>These run the session in plaintext mode (no TLS) so channel logic is tested on its own; the
 * in-band TLS handshake over a real TCP loopback is {@code AaTransportTest}.</p>
 */
public class AaSessionTest {

    private static AaControl.ServiceDiscoveryConfig discovery() {
        AaControl.ServiceDiscoveryConfig config = new AaControl.ServiceDiscoveryConfig();
        config.headUnitName = "AiPlay-Test";
        return config;
    }

    @Test
    public void beginSendsVersionRequest() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> events = new ArrayList<String>();
        AaSession session = new AaSession(new ByteArrayInputStream(new byte[0]), out,
                sink(events), discovery());
        session.begin();
        session.begin(); // idempotent

        AaFrame frame = readFrame(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(AaMessageType.VERSION_REQUEST, frame.messageId);
        assertTrue(events.size() >= 1);
    }

    @Test
    public void answersVersionThenDiscoveryInOrder() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> events = new ArrayList<String>();
        AaSession session = new AaSession(new ByteArrayInputStream(new byte[0]), out,
                sink(events), discovery());

        session.handle(AaFrame.control(AaMessageType.VERSION_RESPONSE,
                AaControl.versionResponse(1, 1, 0)));
        session.handle(AaFrame.control(AaMessageType.SERVICE_DISCOVERY_REQUEST,
                new ProtoWriter().string(4, "Pixel").string(5, "Google").toByteArray()));

        InputStream replies = new ByteArrayInputStream(out.toByteArray());
        AaFrame auth = readFrame(replies);
        AaFrame discoveryReply = readFrame(replies);
        assertEquals(AaMessageType.AUTH_COMPLETE, auth.messageId);
        assertEquals(AaMessageType.SERVICE_DISCOVERY_RESPONSE, discoveryReply.messageId);
        assertTrue(discoveryReply.payload.length > 0);
        assertTrue(events.toString().contains("Pixel"));
    }

    @Test
    public void refusesChannelsItDoesNotServe() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AaSession session = new AaSession(new ByteArrayInputStream(new byte[0]), out,
                sink(new ArrayList<String>()), discovery());
        // The Bluetooth channel is not served: asked on its own channel, answered on it.
        session.handle(new AaFrame(AaChannel.BLUETOOTH, true, AaMessageType.CHANNEL_OPEN_REQUEST,
                new ProtoWriter().varint(1, 0).varint(2, AaChannel.BLUETOOTH).toByteArray()));

        AaFrame reply = readFrame(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(AaChannel.BLUETOOTH, reply.channel);
        assertEquals(AaMessageType.CHANNEL_OPEN_RESPONSE, reply.messageId);
        assertEquals(0x08, reply.payload[0]);
        assertEquals(AaControl.STATUS_FAIL, reply.payload[1]);
    }

    @Test
    public void opensTheVideoChannelOnItsOwnChannel() throws Exception {
        Rig rig = new Rig();
        rig.session.handle(new AaFrame(AaChannel.VIDEO, true, AaMessageType.CHANNEL_OPEN_REQUEST,
                new ProtoWriter().varint(1, 0).varint(2, AaChannel.VIDEO).toByteArray()));
        AaFrame reply = rig.next();
        assertEquals(AaChannel.VIDEO, reply.channel);
        assertTrue(reply.control);
        assertEquals(AaMessageType.CHANNEL_OPEN_RESPONSE, reply.messageId);
        assertEquals(AaControl.STATUS_OK, reply.payload[1]);
    }

    @Test
    public void ignoresVideoMessagesBeforeTheChannelIsOpen() throws Exception {
        Rig rig = new Rig();
        rig.session.handle(AaFrame.channel(AaChannel.VIDEO, AaAv.SETUP_REQUEST,
                new ProtoWriter().varint(1, 0).toByteArray()));
        assertEquals(0, rig.out.size());
        assertTrue(rig.events.toString().contains("before the channel was opened"));
    }

    @Test
    public void videoSetupAnswersWithOkMaxUnackedAndConfigZero() throws Exception {
        Rig rig = new Rig();
        rig.openVideo();
        rig.session.handle(AaFrame.channel(AaChannel.VIDEO, AaAv.SETUP_REQUEST,
                new ProtoWriter().varint(1, 0).toByteArray()));
        AaFrame reply = rig.next();
        assertEquals(AaChannel.VIDEO, reply.channel);
        assertTrue(!reply.control);
        assertEquals(AaAv.SETUP_RESPONSE, reply.messageId);
        // media_status=2 (OK), max_unacked=1, configs=[0]
        assertArrayEquals(new byte[]{0x08, 0x02, 0x10, 0x01, 0x18, 0x00}, reply.payload);
        assertEquals(Collections.singletonList(0), rig.video.setups);
    }

    @Test
    public void videoSetupRefusesAConfigItDidNotOffer() throws Exception {
        Rig rig = new Rig();
        rig.openVideo();
        rig.session.handle(AaFrame.channel(AaChannel.VIDEO, AaAv.SETUP_REQUEST,
                new ProtoWriter().varint(1, 4).toByteArray()));
        AaFrame reply = rig.next();
        assertEquals(AaAv.SETUP_FAIL, reply.payload[1]);
        assertTrue(rig.video.setups.isEmpty());
    }

    @Test
    public void videoFocusRequestIsGranted() throws Exception {
        Rig rig = new Rig();
        rig.openVideo();
        rig.session.handle(AaFrame.channel(AaChannel.VIDEO, AaAv.VIDEO_FOCUS_REQUEST,
                new ProtoWriter().varint(1, 0).varint(2, AaAv.FOCUS_FOCUSED).varint(3, 1)
                        .toByteArray()));
        AaFrame reply = rig.next();
        assertEquals(AaAv.VIDEO_FOCUS_INDICATION, reply.messageId);
        // focus_mode=1 (FOCUSED), unrequested=false
        assertArrayEquals(new byte[]{0x08, 0x01, 0x10, 0x00}, reply.payload);
        assertEquals(Collections.singletonList(Boolean.TRUE), rig.video.focus);
    }

    @Test
    public void startThenFramesAreDeliveredAndEachIsAcked() throws Exception {
        Rig rig = new Rig();
        rig.openVideo();
        rig.session.handle(AaFrame.channel(AaChannel.VIDEO, AaAv.START_INDICATION,
                new ProtoWriter().varint(1, 7).varint(2, 0).toByteArray()));
        assertEquals(0, rig.unread()); // a start needs no answer
        assertTrue(rig.session.isVideoStarted());

        byte[] data = {0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F};
        byte[] media = new byte[8 + data.length];
        media[7] = 0x10; // timestamp 16 us, big endian
        System.arraycopy(data, 0, media, 8, data.length);
        rig.session.handle(AaFrame.channel(AaChannel.VIDEO, AaAv.MEDIA_WITH_TIMESTAMP, media));
        rig.session.handle(AaFrame.channel(AaChannel.VIDEO, AaAv.MEDIA, data));

        assertEquals(2, rig.video.frames.size());
        assertEquals(Long.valueOf(16L), rig.video.timestamps.get(0));
        assertEquals(Long.valueOf(-1L), rig.video.timestamps.get(1));
        assertArrayEquals(data, rig.video.frames.get(0));
        assertArrayEquals(data, rig.video.frames.get(1));

        AaFrame ack1 = rig.next();
        AaFrame ack2 = rig.next();
        assertEquals(AaAv.MEDIA_ACK, ack1.messageId);
        assertEquals(AaAv.MEDIA_ACK, ack2.messageId);
        // session=7, value=1
        assertArrayEquals(new byte[]{0x08, 0x07, 0x10, 0x01}, ack1.payload);
        assertEquals(2, rig.session.videoFrames());
    }

    @Test
    public void stopAndShutdownEndTheVideoOnce() throws Exception {
        Rig rig = new Rig();
        rig.openVideo();
        rig.session.handle(AaFrame.channel(AaChannel.VIDEO, AaAv.START_INDICATION,
                new ProtoWriter().varint(1, 1).varint(2, 0).toByteArray()));
        rig.session.handle(AaFrame.channel(AaChannel.VIDEO, AaAv.STOP_INDICATION, new byte[0]));
        rig.session.handle(AaFrame.control(AaMessageType.SHUTDOWN_REQUEST, new byte[0]));
        assertEquals(1, rig.video.stops);
        assertTrue(!rig.session.isVideoStarted());
    }

    @Test
    public void aLargeVideoFrameSurvivesSplitFraming() throws Exception {
        Rig rig = new Rig();
        rig.openVideo();
        byte[] data = new byte[50_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        byte[] wire = AaFrameCodec.encode(AaChannel.VIDEO, false, AaAv.MEDIA, data);
        assertTrue("must be split into several frames", wire.length > data.length + 20);

        AaSession session = new AaSession(new ByteArrayInputStream(wire), rig.out,
                sink(rig.events), discovery());
        session.setVideoSink(rig.video);
        // open first, as a phone would
        session.handle(new AaFrame(AaChannel.VIDEO, true, AaMessageType.CHANNEL_OPEN_REQUEST,
                new ProtoWriter().varint(2, AaChannel.VIDEO).toByteArray()));
        session.pump(new byte[100_000]);
        assertEquals(1, rig.video.frames.size());
        assertArrayEquals(data, rig.video.frames.get(0));
    }

    @Test
    public void answersPing() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AaSession session = new AaSession(new ByteArrayInputStream(new byte[0]), out,
                sink(new ArrayList<String>()), discovery());
        session.handle(AaFrame.control(AaMessageType.PING_REQUEST, AaControl.pingRequest(1L)));
        assertEquals(AaMessageType.PING_RESPONSE,
                readFrame(new ByteArrayInputStream(out.toByteArray())).messageId);
    }

    @Test
    public void reportsUnknownFramesInsteadOfDroppingThem() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> events = new ArrayList<String>();
        AaSession session = new AaSession(new ByteArrayInputStream(new byte[0]), out,
                sink(events), discovery());
        session.handle(AaFrame.control(0x00AA, new byte[]{1, 2, 3}));
        assertTrue(events.toString().contains("UNKNOWN_0x00AA"));
        assertEquals(0, out.size());
    }

    @Test
    public void pumpReturnsMinusOneOnPeerClose() throws Exception {
        AaSession session = new AaSession(new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream(), sink(new ArrayList<String>()), discovery());
        assertEquals(-1, session.pump(new byte[64]));
    }

    private static AaSession.Sink sink(final List<String> events) {
        return new AaSession.Sink() {
            @Override
            public void onEvent(String message) {
                events.add(message);
            }
        };
    }

    private static AaFrame readFrame(InputStream in) throws IOException, AaProtocolException {
        byte[] header = new byte[AaFrameCodec.HEADER_SIZE + AaFrameCodec.SIZE_EXTENDED];
        if (!readFully(in, header, AaFrameCodec.HEADER_SIZE + AaFrameCodec.SIZE_SHORT)) {
            throw new IOException("stream ended before a frame header");
        }
        boolean first = (header[1] & AaChannel.FRAME_BULK) == AaChannel.FRAME_FIRST;
        int headerBytes = AaFrameCodec.HEADER_SIZE
                + (first ? AaFrameCodec.SIZE_EXTENDED : AaFrameCodec.SIZE_SHORT);
        if (first && !readFully(in, header, headerBytes, AaFrameCodec.HEADER_SIZE
                + AaFrameCodec.SIZE_SHORT)) {
            throw new IOException("stream ended inside an extended size field");
        }
        int size = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
        byte[] wire = new byte[headerBytes + size];
        System.arraycopy(header, 0, wire, 0, headerBytes);
        if (!readFully(in, wire, headerBytes + size, headerBytes)) {
            throw new IOException("stream ended inside a frame");
        }
        return AaFrameCodec.decode(wire);
    }

    private static boolean readFully(InputStream in, byte[] buffer, int count) throws IOException {
        return readFully(in, buffer, count, 0);
    }

    private static boolean readFully(InputStream in, byte[] buffer, int count, int offset)
            throws IOException {
        int read = 0;
        while (read < count - offset) {
            int n = in.read(buffer, offset + read, count - offset - read);
            if (n < 0) {
                return false;
            }
            read += n;
        }
        return true;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    // ---- keep-alive: the head unit pings, the phone is gone after two unanswered pings -------------

    private static AaFrame lastWritten(Rig rig) throws Exception {
        AaFrame frame = null;
        while (rig.unread() > 0) {
            frame = rig.next();
        }
        return frame;
    }

    @Test
    public void theFirstPingIsOneIntervalAfterTheSessionIsUpThenEveryInterval() throws Exception {
        Rig rig = new Rig();                       // plaintext: established from the start
        assertTrue(rig.session.tick(0));
        assertEquals("no ping yet", 0, rig.session.pingsSent());
        assertTrue(rig.session.tick(AaSession.PING_INTERVAL_MS - 1));
        assertEquals(0, rig.session.pingsSent());
        assertTrue(rig.session.tick(AaSession.PING_INTERVAL_MS));
        assertEquals(1, rig.session.pingsSent());
        AaFrame ping = rig.next();
        assertEquals(AaChannel.CONTROL, ping.channel);
        assertTrue("channel-0 messages are not CONTROL-flagged", !ping.control);
        assertEquals(AaMessageType.PING_REQUEST, ping.messageId);
        assertTrue(rig.session.tick(AaSession.PING_INTERVAL_MS + 100));
        assertEquals("not again inside the interval", 1, rig.session.pingsSent());
        rig.session.handle(AaFrame.control(AaMessageType.PING_RESPONSE, ping.payload));
        assertTrue(rig.session.tick(2 * AaSession.PING_INTERVAL_MS));
        assertEquals(2, rig.session.pingsSent());
    }

    @Test
    public void aPhoneThatAnswersEveryPingIsNeverDropped() throws Exception {
        Rig rig = new Rig();
        long now = 0;
        for (int n = 0; n < 50; n++) {
            assertTrue("alive at " + now, rig.session.tick(now));
            while (rig.unread() > 0) {
                AaFrame f = rig.next();
                if (f.messageId == AaMessageType.PING_REQUEST) {
                    rig.session.handle(AaFrame.control(AaMessageType.PING_RESPONSE, f.payload));
                }
            }
            now += AaSession.PING_INTERVAL_MS;
        }
        assertTrue(rig.session.pongsReceived() >= 49);
    }

    @Test
    public void aPhoneThatNeverAnswersIsDroppedAfterTheSecondUnansweredPing() throws Exception {
        // OpenAuto: quit when pings sent - pongs received > 1 at the next timer.
        Rig rig = new Rig();
        long t = AaSession.PING_INTERVAL_MS;
        assertTrue(rig.session.tick(0));
        assertTrue(rig.session.tick(t));                 // ping 1
        assertTrue(rig.session.tick(2 * t));             // ping 2 (one unanswered is tolerated)
        assertEquals(2, rig.session.pingsSent());
        assertFalse("two unanswered pings: gone", rig.session.tick(3 * t));
        assertTrue(rig.events.toString().contains("stopped answering pings"));
        assertEquals("no third ping was sent to a dead phone", 2, rig.session.pingsSent());
    }

    @Test
    public void silenceAloneDoesNotDropAPhoneThatAnswersPings() throws Exception {
        // a still map: no media at all for a minute, but the phone answers the head unit's pings
        Rig rig = new Rig();
        long now = 0;
        rig.session.tick(now);
        for (int n = 1; n <= 12; n++) {
            now = n * AaSession.PING_INTERVAL_MS;
            assertTrue(rig.session.tick(now));
            AaFrame f = lastWritten(rig);
            if (f != null && f.messageId == AaMessageType.PING_REQUEST) {
                rig.session.handle(AaFrame.control(AaMessageType.PING_RESPONSE, f.payload));
            }
        }
        assertTrue(rig.session.pongsReceived() >= 11);
    }

    @Test
    public void aHandshakeThatNeverFinishesIsAbandonedAndNoPingIsSentBeforeTls() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AaSession session = new AaSession(new ByteArrayInputStream(new byte[0]), out,
                sink(new ArrayList<String>()), discovery(), new AaCryptor(tlsContext()));
        assertTrue(session.tick(0));
        assertTrue(session.tick(AaSession.HANDSHAKE_TIMEOUT_MS));
        assertEquals("nothing is written before TLS is up", 0, out.size());
        assertFalse(session.tick(AaSession.HANDSHAKE_TIMEOUT_MS + 1));
    }

    private static javax.net.ssl.SSLContext tlsContext() throws Exception {
        try {
            return javax.net.ssl.SSLContext.getDefault();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    /** A session wired to in-memory output and a recording video sink. */
    private static final class Rig {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<String> events = new ArrayList<String>();
        final RecordingVideo video = new RecordingVideo();
        final AaSession session = new AaSession(new ByteArrayInputStream(new byte[0]), out,
                sink(events), discovery());
        private InputStream replies;
        private int consumed;

        Rig() {
            session.setVideoSink(video);
        }

        void openVideo() throws Exception {
            session.handle(new AaFrame(AaChannel.VIDEO, true, AaMessageType.CHANNEL_OPEN_REQUEST,
                    new ProtoWriter().varint(2, AaChannel.VIDEO).toByteArray()));
            next(); // discard the open response
        }

        /** Bytes the session wrote that the test has not read yet. */
        int unread() {
            return out.size() - consumed;
        }

        /** The next frame the session wrote that has not been read yet. */
        AaFrame next() throws Exception {
            byte[] all = out.toByteArray();
            InputStream stream = new ByteArrayInputStream(all, consumed, all.length - consumed);
            AaFrame frame = readFrame(stream);
            consumed = all.length - stream.available();
            return frame;
        }
    }

    private static final class RecordingVideo implements AaSession.VideoSink {
        final List<Integer> setups = new ArrayList<Integer>();
        final List<Boolean> focus = new ArrayList<Boolean>();
        final List<byte[]> frames = new ArrayList<byte[]>();
        final List<Long> timestamps = new ArrayList<Long>();
        int stops;

        @Override
        public void onVideoSetup(int configIndex) {
            setups.add(configIndex);
        }

        @Override
        public void onVideoStart(int session, int config) {
        }

        @Override
        public void onVideoFrame(long timestampMicros, byte[] data, int offset, int length) {
            byte[] copy = new byte[length];
            System.arraycopy(data, offset, copy, 0, length);
            frames.add(copy);
            timestamps.add(timestampMicros);
        }

        @Override
        public void onVideoStop() {
            stops++;
        }

        @Override
        public void onVideoFocus(boolean focused) {
            focus.add(focused);
        }
    }
}
