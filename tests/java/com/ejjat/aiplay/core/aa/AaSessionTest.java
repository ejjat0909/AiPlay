package com.ejjat.aiplay.core.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Contract tests for the Android Auto control session.
 *
 * <p>The second test is a real loopback TLS handshake: server and client contexts are built from a
 * throwaway keystore that {@code tools/test.sh} generates with keytool. It proves the endpoint
 * negotiates TLS, speaks the frame format and answers the handshake in the documented order.</p>
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
                new byte[]{0x08, 0x01, 0x10, 0x06, 0x18, 0x00}));
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
        // Media audio is not implemented in 0.2.0: asked on its own channel, answered on it.
        session.handle(new AaFrame(AaChannel.MEDIA_AUDIO, true, AaMessageType.CHANNEL_OPEN_REQUEST,
                new ProtoWriter().varint(1, 0).varint(2, AaChannel.MEDIA_AUDIO).toByteArray()));

        AaFrame reply = readFrame(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(AaChannel.MEDIA_AUDIO, reply.channel);
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
    public void stillAcceptsAControlChannelOpenForVideo() throws Exception {
        Rig rig = new Rig();
        rig.session.handle(AaFrame.control(AaMessageType.CHANNEL_OPEN_REQUEST,
                new ProtoWriter().varint(1, 0).varint(2, AaChannel.VIDEO).toByteArray()));
        AaFrame reply = rig.next();
        assertEquals(AaChannel.CONTROL, reply.channel);
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

    @Test
    public void completesRealTlsHandshakeAndControlExchange() throws Exception {
        String keystorePath = System.getProperty("aiplay.test.keystore");
        if (keystorePath == null || !new File(keystorePath).isFile()) {
            fail("aiplay.test.keystore not set — run through tools/test.sh");
        }

        SSLServerSocket serverSocket = (SSLServerSocket) serverContext(keystorePath)
                .getServerSocketFactory().createServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        final int port = serverSocket.getLocalPort();

        final AtomicReference<Throwable> serverFailure = new AtomicReference<Throwable>();
        final List<String> serverEvents = new ArrayList<String>();

        Thread serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                SSLSocket socket = null;
                try {
                    socket = (SSLSocket) serverSocket.accept();
                    socket.setSoTimeout(8000);
                    socket.startHandshake();
                    AaSession session = new AaSession(socket.getInputStream(),
                            socket.getOutputStream(), sink(serverEvents), discovery());
                    session.begin();
                    byte[] buffer = new byte[4096];
                    for (int i = 0; i < 4; i++) {
                        if (session.pump(buffer) < 0) {
                            break;
                        }
                    }
                } catch (Throwable error) {
                    serverFailure.set(error);
                } finally {
                    closeQuietly(socket);
                }
            }
        }, "aiplay-test-server");
        serverThread.start();

        SSLSocket client = null;
        try {
            client = (SSLSocket) clientContext().getSocketFactory()
                    .createSocket("127.0.0.1", port);
            client.setSoTimeout(8000);
            client.startHandshake();

            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            AaFrame versionRequest = readFrame(in);
            assertEquals(AaMessageType.VERSION_REQUEST, versionRequest.messageId);

            out.write(AaFrameCodec.encode(AaChannel.CONTROL, true, AaMessageType.VERSION_RESPONSE,
                    new byte[]{0x08, 0x01, 0x10, 0x06, 0x18, 0x00}));
            out.flush();
            assertEquals(AaMessageType.AUTH_COMPLETE, readFrame(in).messageId);

            out.write(AaFrameCodec.encode(AaChannel.CONTROL, true, AaMessageType.SERVICE_DISCOVERY_REQUEST,
                    new ProtoWriter().string(4, "TestPhone").toByteArray()));
            out.flush();
            AaFrame discoveryReply = readFrame(in);
            assertEquals(AaMessageType.SERVICE_DISCOVERY_RESPONSE, discoveryReply.messageId);
            assertTrue(discoveryReply.payload.length > 4);

            out.write(AaFrameCodec.encode(AaChannel.CONTROL, true, AaMessageType.PING_REQUEST, AaControl.pingRequest(7L)));
            out.flush();
            assertEquals(AaMessageType.PING_RESPONSE, readFrame(in).messageId);
        } finally {
            closeQuietly(client);
            closeQuietly(serverSocket);
        }

        serverThread.join(8000);
        assertTrue("server thread did not finish", !serverThread.isAlive());
        if (serverFailure.get() != null) {
            throw new AssertionError("server failed: " + serverFailure.get(), serverFailure.get());
        }
        String events = serverEvents.toString();
        assertNotNull(events);
        assertTrue("server should announce its version: " + events,
                events.contains("sent control/VERSION_REQUEST"));
        assertTrue("server should record the peer's discovery: " + events,
                events.contains("service discovery from"));
    }

    // ---- helpers ---------------------------------------------------------------------------

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

    private static SSLContext serverContext(String path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        FileInputStream input = new FileInputStream(path);
        try {
            keyStore.load(input, "aiplay".toCharArray());
        } finally {
            input.close();
        }
        KeyManagerFactory factory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, "aiplay".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext clientContext() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        }};
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, null);
        return context;
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
