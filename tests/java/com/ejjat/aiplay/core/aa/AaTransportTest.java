package com.ejjat.aiplay.core.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The whole transport over a real TCP loopback, the way a phone drives it: plain AA frames on the
 * socket, TLS 1.2 carried inside {@code SSL_HANDSHAKE} messages with the head unit as the client,
 * then encrypted frames. The "phone" is a {@link FakePhone} with a server-mode TLS engine that
 * requires a client certificate.
 *
 * <p>This replaces the pre-0.2.0-alpha4 test that ran a TLS server directly on the socket, which a
 * real phone would not have connected to.</p>
 */
public class AaTransportTest {

    private static AaControl.ServiceDiscoveryConfig discovery() {
        AaControl.ServiceDiscoveryConfig config = new AaControl.ServiceDiscoveryConfig();
        config.headUnitName = "AiPlay-Test";
        return config;
    }

    /** Runs a head-unit session on the accepted socket until the peer closes. */
    private static final class HeadUnit implements Runnable {
        final ServerSocket server;
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());
        final List<byte[]> pictures = Collections.synchronizedList(new ArrayList<byte[]>());
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        volatile AaSession session;
        final Thread thread;
        final long pingMs;

        HeadUnit() throws Exception {
            this(0);
        }

        /** @param pingMs when above 0, run the keep-alive exactly as AaServer does, at this interval */
        HeadUnit(long pingMs) throws Exception {
            this.pingMs = pingMs;
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this, "aiplay-test-head-unit");
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void run() {
            Socket socket = null;
            try {
                socket = server.accept();
                socket.setSoTimeout(pingMs > 0 ? 25 : 10000);
                session = new AaSession(socket.getInputStream(), socket.getOutputStream(),
                        new AaSession.Sink() {
                            @Override
                            public void onEvent(String message) {
                                events.add(message);
                            }
                        }, discovery(), new AaCryptor(TlsFixtures.headUnitContext()));
                session.setVideoSink(new AaSession.VideoSink() {
                    @Override
                    public void onVideoSetup(int configIndex) {
                    }

                    @Override
                    public void onVideoStart(int s, int config) {
                    }

                    @Override
                    public void onVideoFrame(long ts, byte[] data, int offset, int length) {
                        byte[] copy = new byte[length];
                        System.arraycopy(data, offset, copy, 0, length);
                        pictures.add(copy);
                    }

                    @Override
                    public void onVideoStop() {
                    }

                    @Override
                    public void onVideoFocus(boolean focused) {
                    }
                });
                if (pingMs > 0) {
                    session.setPingIntervalMillis(pingMs);
                }
                session.begin();
                byte[] buffer = new byte[65536];
                while (true) {
                    try {
                        if (session.pump(buffer) < 0) {
                            break;
                        }
                    } catch (java.net.SocketTimeoutException quiet) {
                        if (pingMs <= 0) {
                            throw quiet;
                        }
                    }
                    if (pingMs > 0 && !session.tick(System.currentTimeMillis())) {
                        break;
                    }
                }
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                try {
                    if (socket != null) {
                        socket.close();
                    }
                    server.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    /** The phone: plain TCP client, in-band TLS server, with a record of what it was sent. */
    private static final class FakePhone {
        final Socket socket;
        final InputStream in;
        final OutputStream out;
        final AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
        final AaCryptor tls;
        final LinkedList<AaFrame> inbox = new LinkedList<AaFrame>();
        /** Every message the head unit sent, in order, with whether it was encrypted. */
        final List<AaFrame> received = new ArrayList<AaFrame>();
        boolean encrypting;
        boolean answerPings;
        int pingsSeen;

        FakePhone(int port) throws Exception {
            socket = new Socket(InetAddress.getByName("127.0.0.1"), port);
            socket.setSoTimeout(10000);
            in = socket.getInputStream();
            out = socket.getOutputStream();
            tls = new AaCryptor(TlsFixtures.phoneEngine());
            tls.beginHandshake();
        }

        AaFrame next() throws Exception {
            byte[] buffer = new byte[65536];
            while (inbox.isEmpty()) {
                int read = in.read(buffer);
                if (read < 0) {
                    throw new IOException("head unit closed the connection");
                }
                parser.feed(buffer, 0, read, new AaFrameCodec.Listener() {
                    @Override
                    public void onMessage(AaFrame frame) throws IOException {
                        received.add(frame);
                        if (frame.channel == AaChannel.CONTROL
                                && frame.messageId == AaMessageType.PING_REQUEST) {
                            pingsSeen++;
                            if (answerPings) {
                                try {
                                    out.write(AaFrameCodec.encode(AaChannel.CONTROL, false,
                                            AaMessageType.PING_RESPONSE, frame.payload,
                                            encrypting ? tls : null));
                                    out.flush();
                                } catch (IOException error) {
                                    throw error;
                                }
                            }
                            return;         // keep-alive traffic is not what a test step waits for
                        }
                        inbox.add(frame);
                    }
                });
            }
            return inbox.removeFirst();
        }

        /** Reads and answers whatever arrives for {@code millis}; false if the head unit hung up. */
        boolean listenFor(long millis) throws Exception {
            socket.setSoTimeout(50);
            long end = System.currentTimeMillis() + millis;
            byte[] buffer = new byte[65536];
            while (System.currentTimeMillis() < end) {
                try {
                    int read = in.read(buffer);
                    if (read < 0) {
                        return false;
                    }
                    parser.feed(buffer, 0, read, new AaFrameCodec.Listener() {
                        @Override
                        public void onMessage(AaFrame frame) throws IOException {
                            received.add(frame);
                            if (frame.channel == AaChannel.CONTROL
                                    && frame.messageId == AaMessageType.PING_REQUEST) {
                                pingsSeen++;
                                if (answerPings) {
                                    out.write(AaFrameCodec.encode(AaChannel.CONTROL, false,
                                            AaMessageType.PING_RESPONSE, frame.payload,
                                            encrypting ? tls : null));
                                    out.flush();
                                }
                            } else {
                                inbox.add(frame);
                            }
                        }
                    });
                } catch (java.net.SocketTimeoutException quiet) {
                    // nothing yet
                }
            }
            socket.setSoTimeout(10000);
            return true;
        }

        void send(int channel, boolean control, int id, byte[] payload) throws Exception {
            out.write(AaFrameCodec.encode(channel, control, id, payload, encrypting ? tls : null));
            out.flush();
        }

        /** Version exchange and TLS, ending with the head unit's AUTH_COMPLETE. */
        void connect() throws Exception {
            AaFrame version = next();
            assertEquals(AaMessageType.VERSION_REQUEST, version.messageId);
            assertFalse("version request is SPECIFIC on channel 0, not CONTROL", version.control);
            assertFalse(version.encrypted);
            assertArrayEquals(new byte[]{0, 1, 0, 1}, version.payload);

            send(AaChannel.CONTROL, false, AaMessageType.VERSION_RESPONSE,
                    AaControl.versionResponse(1, 7, AaControl.VERSION_MATCH));

            AaFrame frame = next();
            while (frame.messageId == AaMessageType.SSL_HANDSHAKE) {
                assertFalse("handshake messages are never encrypted", frame.encrypted);
                assertEquals(AaChannel.CONTROL, frame.channel);
                byte[] reply = tls.handshake(frame.payload, 0, frame.payload.length);
                if (reply.length > 0) {
                    send(AaChannel.CONTROL, false, AaMessageType.SSL_HANDSHAKE, reply);
                }
                if (tls.isHandshakeDone() && !encrypting) {
                    parser.setCipher(tls);
                    encrypting = true;
                }
                frame = next();
            }
            assertEquals(AaMessageType.AUTH_COMPLETE, frame.messageId);
            assertFalse("auth complete is still sent in the clear", frame.encrypted);
            assertTrue("the phone finished TLS before auth complete", tls.isHandshakeDone());
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }

    private static void finish(HeadUnit unit) throws Exception {
        unit.thread.join(10000);
        assertFalse("head unit thread did not finish", unit.thread.isAlive());
        if (unit.failure.get() != null) {
            throw new AssertionError("head unit failed: " + unit.failure.get(), unit.failure.get());
        }
    }

    @Test
    public void completesInBandTlsThenRunsAnEncryptedVideoSession() throws Exception {
        HeadUnit unit = new HeadUnit();
        FakePhone phone = new FakePhone(unit.port());
        try {
            phone.connect();

            // Everything from here is encrypted.
            phone.send(AaChannel.CONTROL, false, AaMessageType.SERVICE_DISCOVERY_REQUEST,
                    new ProtoWriter().string(4, "TestPhone").string(5, "Test").toByteArray());
            AaFrame discoveryReply = phone.next();
            assertEquals(AaMessageType.SERVICE_DISCOVERY_RESPONSE, discoveryReply.messageId);
            assertTrue("service discovery response must be encrypted", discoveryReply.encrypted);
            assertTrue(discoveryReply.payload.length > 4);

            phone.send(AaChannel.CONTROL, false, AaMessageType.PING_REQUEST, AaControl.pingRequest(7L));
            AaFrame pong = phone.next();
            assertEquals(AaMessageType.PING_RESPONSE, pong.messageId);
            assertTrue(pong.encrypted);

            phone.send(AaChannel.VIDEO, true, AaMessageType.CHANNEL_OPEN_REQUEST,
                    new ProtoWriter().varint(1, 0).varint(2, AaChannel.VIDEO).toByteArray());
            AaFrame open = phone.next();
            assertEquals(AaChannel.VIDEO, open.channel);
            assertTrue("channel open response is CONTROL-typed", open.control);
            assertTrue(open.encrypted);
            assertEquals(AaControl.STATUS_OK, open.payload[1]);

            phone.send(AaChannel.VIDEO, false, AaAv.SETUP_REQUEST, new ProtoWriter().varint(1, 0).toByteArray());
            AaFrame setup = phone.next();
            assertEquals(AaAv.SETUP_RESPONSE, setup.messageId);
            assertTrue(setup.encrypted);

            phone.send(AaChannel.VIDEO, false, AaAv.START_INDICATION,
                    new ProtoWriter().varint(1, 3).varint(2, 0).toByteArray());

            // A small picture, and one that must be split into several encrypted frames.
            byte[] small = {0, 0, 0, 1, 0x65, 1, 2, 3};
            byte[] big = new byte[60000];
            for (int i = 0; i < big.length; i++) {
                big[i] = (byte) (i * 13);
            }
            phone.send(AaChannel.VIDEO, false, AaAv.MEDIA, small);
            phone.send(AaChannel.VIDEO, false, AaAv.MEDIA, big);

            AaFrame ack1 = phone.next();
            AaFrame ack2 = phone.next();
            assertEquals(AaAv.MEDIA_ACK, ack1.messageId);
            assertEquals(AaAv.MEDIA_ACK, ack2.messageId);
            assertTrue(ack1.encrypted && ack2.encrypted);
            assertArrayEquals(new byte[]{0x08, 0x03, 0x10, 0x01}, ack1.payload);

            phone.send(AaChannel.VIDEO, false, AaAv.STOP_INDICATION, new byte[0]);
            phone.send(AaChannel.CONTROL, false, AaMessageType.SHUTDOWN_REQUEST, new byte[0]);
            AaFrame bye = phone.next();
            assertEquals(AaMessageType.SHUTDOWN_RESPONSE, bye.messageId);
        } finally {
            phone.close();
        }
        finish(unit);

        assertEquals(2, unit.pictures.size());
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x65, 1, 2, 3}, unit.pictures.get(0));
        assertEquals(60000, unit.pictures.get(1).length);
        for (int i = 0; i < 60000; i += 997) {
            assertEquals((byte) (i * 13), unit.pictures.get(1)[i]);
        }

        // After TLS, only version request / handshake / auth complete may have been in the clear.
        for (AaFrame frame : phone.received) {
            boolean plainAllowed = frame.channel == AaChannel.CONTROL
                    && (frame.messageId == AaMessageType.VERSION_REQUEST
                    || frame.messageId == AaMessageType.SSL_HANDSHAKE
                    || frame.messageId == AaMessageType.AUTH_COMPLETE);
            assertEquals("encryption of " + frame, !plainAllowed, frame.encrypted);
        }
        String events = unit.events.toString();
        assertTrue(events, events.contains("TLS up: TLSv1.2"));
    }

    @Test
    public void aPhoneThatReportsAVersionMismatchEndsTheSession() throws Exception {
        HeadUnit unit = new HeadUnit();
        FakePhone phone = new FakePhone(unit.port());
        try {
            assertEquals(AaMessageType.VERSION_REQUEST, phone.next().messageId);
            phone.send(AaChannel.CONTROL, false, AaMessageType.VERSION_RESPONSE,
                    AaControl.versionResponse(2, 0, AaControl.VERSION_MISMATCH));
            unit.thread.join(10000);
        } finally {
            phone.close();
        }
        assertFalse(unit.thread.isAlive());
        assertTrue("expected a protocol error, got " + unit.failure.get(),
                unit.failure.get() instanceof AaProtocolException);
        assertTrue(unit.failure.get().getMessage().contains("mismatch"));
    }

    @Test
    public void anEncryptedFrameBeforeTlsIsUpIsRejected() throws Exception {
        HeadUnit unit = new HeadUnit();
        FakePhone phone = new FakePhone(unit.port());
        try {
            assertEquals(AaMessageType.VERSION_REQUEST, phone.next().messageId);
            // ENCRYPTED flag set, but no handshake has happened: nothing could decrypt this.
            phone.out.write(new byte[]{0x00, 0x0B, 0x00, 0x04, 0x00, 0x0B, 0x00, 0x00});
            phone.out.flush();
            unit.thread.join(10000);
        } finally {
            phone.close();
        }
        assertFalse(unit.thread.isAlive());
        assertTrue("expected a protocol error, got " + unit.failure.get(),
                unit.failure.get() instanceof AaProtocolException);
        if (!unit.failure.get().getMessage().contains("TLS")) {
            fail("error should mention TLS: " + unit.failure.get().getMessage());
        }
    }

    @Test
    public void touchSentFromAnotherThreadWhileAcksFlowKeepsTheTlsStreamIntact() throws Exception {
        // Touch is sent by the UI/input thread while the session thread writes media acks. Both
        // encrypt on one TLS engine; without one lock around encrypt+write, records interleave or
        // arrive out of order and the phone's decryption fails.
        final int count = 400;
        final HeadUnit unit = new HeadUnit();
        FakePhone phone = new FakePhone(unit.port());
        try {
            phone.connect();
            phone.send(AaChannel.INPUT, true, AaMessageType.CHANNEL_OPEN_REQUEST,
                    new ProtoWriter().varint(2, AaChannel.INPUT).toByteArray());
            assertEquals(AaMessageType.CHANNEL_OPEN_RESPONSE, phone.next().messageId);
            phone.send(AaChannel.INPUT, false, AaInput.BINDING_REQUEST,
                    new ProtoWriter().varint(1, AaInput.KEY_DPAD_UP).toByteArray());
            AaFrame binding = phone.next();
            assertEquals(AaInput.BINDING_RESPONSE, binding.messageId);
            assertTrue(binding.encrypted);

            phone.send(AaChannel.VIDEO, true, AaMessageType.CHANNEL_OPEN_REQUEST,
                    new ProtoWriter().varint(2, AaChannel.VIDEO).toByteArray());
            assertEquals(AaMessageType.CHANNEL_OPEN_RESPONSE, phone.next().messageId);
            phone.send(AaChannel.VIDEO, false, AaAv.START_INDICATION,
                    new ProtoWriter().varint(1, 1).toByteArray());

            final AtomicReference<Throwable> touchFailure = new AtomicReference<Throwable>();
            Thread touches = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        for (int n = 0; n < count; n++) {
                            while (!unit.session.sendTouch(n, AaInput.DRAG, 0,
                                    new int[]{n}, new int[]{n}, new int[]{0})) {
                                Thread.sleep(1);
                            }
                        }
                    } catch (Throwable error) {
                        touchFailure.set(error);
                    }
                }
            }, "test-touch-sender");
            touches.start();
            for (int n = 0; n < count; n++) {
                phone.send(AaChannel.VIDEO, false, AaAv.MEDIA, new byte[]{0, 0, 0, 1, 0x41, (byte) n});
            }

            int acks = 0;
            int touchEvents = 0;
            int lastX = -1;
            while (acks < count || touchEvents < count) {
                AaFrame frame = phone.next();
                assertTrue("every frame after TLS is encrypted: " + frame, frame.encrypted);
                if (frame.messageId == AaAv.MEDIA_ACK) {
                    acks++;
                } else if (frame.channel == AaChannel.INPUT) {
                    AaInput.Touch touch = AaInput.parseTouch(frame.payload);
                    assertTrue("touch events must stay in order", touch.xs[0] > lastX);
                    lastX = touch.xs[0];
                    touchEvents++;
                }
            }
            touches.join(5000);
            assertEquals(null, touchFailure.get());
            assertEquals(count, acks);
            assertEquals(count, touchEvents);

            phone.send(AaChannel.CONTROL, false, AaMessageType.SHUTDOWN_REQUEST, new byte[0]);
            assertEquals(AaMessageType.SHUTDOWN_RESPONSE, phone.next().messageId);
        } finally {
            phone.close();
        }
        finish(unit);
        assertEquals(count, unit.pictures.size());
    }

    @Test
    public void aSilentPhoneThatAnswersPingsStaysConnected() throws Exception {
        HeadUnit unit = new HeadUnit(100);
        FakePhone phone = new FakePhone(unit.port());
        phone.answerPings = true;
        try {
            phone.connect();
            // ten ping intervals of complete silence from the phone's side: no media, no requests
            assertTrue("the head unit must not hang up on a phone that answers", phone.listenFor(1200));
            assertTrue("pings reached the phone, encrypted: " + phone.pingsSeen, phone.pingsSeen >= 5);
            for (AaFrame frame : phone.received) {
                if (frame.messageId == AaMessageType.PING_REQUEST) {
                    assertTrue("pings after TLS are encrypted", frame.encrypted);
                    assertFalse("a ping is a channel-0 message: not CONTROL-flagged", frame.control);
                }
            }
            // and the session still works afterwards
            phone.send(AaChannel.CONTROL, false, AaMessageType.SERVICE_DISCOVERY_REQUEST,
                    new ProtoWriter().string(4, "TestPhone").toByteArray());
            assertEquals(AaMessageType.SERVICE_DISCOVERY_RESPONSE, phone.next().messageId);
        } finally {
            phone.close();
        }
        finish(unit);
    }

    @Test
    public void aPhoneThatStopsAnsweringIsDroppedByTheHeadUnit() throws Exception {
        HeadUnit unit = new HeadUnit(100);
        FakePhone phone = new FakePhone(unit.port());
        phone.answerPings = false;
        try {
            phone.connect();
            boolean stillConnected = phone.listenFor(3000);
            assertFalse("the head unit should have closed the connection", stillConnected);
        } finally {
            phone.close();
        }
        finish(unit);
        assertTrue(unit.events.toString(), unit.events.toString().contains("stopped answering pings"));
        assertTrue("exactly two unanswered pings were sent, then it hung up: " + phone.pingsSeen,
                phone.pingsSeen == 2);
    }
}
