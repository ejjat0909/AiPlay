package com.ejjat.aiplay.core.bt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Bluetooth bootstrap. Byte vectors are computed by hand from the protos in
 * WirelessAndroidAutoDongle, not by the encoder under test. The session test runs over a real TCP
 * loopback standing in for the RFCOMM stream (the code only sees an InputStream/OutputStream).
 */
public class WifiBootstrapTest {

    private static byte[] ascii(String text) {
        try {
            return text.getBytes("US-ASCII");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    private static WifiBootstrap.Config config() {
        WifiBootstrap.Config config = new WifiBootstrap.Config();
        config.ipAddress = "192.168.0.5";
        config.port = 5277;
        config.ssid = "ab";
        config.key = "password123";
        config.bssid = "00:11:22:33:44:55";
        return config;
    }

    @Test
    public void serviceUuidMatchesTheDongle() {
        // bluetoothHandler.cpp: AAWG_PROFILE_UUID
        assertEquals("4de17a00-52cb-11e6-bdf4-0800200c9a66", WifiBootstrap.SERVICE_UUID);
    }

    @Test
    public void messageIdsMatchTheDongle() {
        assertEquals(1, WifiBootstrap.WIFI_START_REQUEST);
        assertEquals(2, WifiBootstrap.WIFI_INFO_REQUEST);
        assertEquals(3, WifiBootstrap.WIFI_INFO_RESPONSE);
        assertEquals(4, WifiBootstrap.WIFI_VERSION_REQUEST);
        assertEquals(5, WifiBootstrap.WIFI_VERSION_RESPONSE);
        assertEquals(6, WifiBootstrap.WIFI_CONNECT_STATUS);
        assertEquals(7, WifiBootstrap.WIFI_START_RESPONSE);
    }

    @Test
    public void startRequestWireBytes() {
        // 0A 0B "192.168.0.5"   10 9D 29 (5277 = 0x149D -> varint 9D 29)
        byte[] expected = concat(new byte[]{0x0A, 0x0B}, ascii("192.168.0.5"),
                new byte[]{0x10, (byte) 0x9D, 0x29});
        assertArrayEquals(expected, WifiBootstrap.startRequest("192.168.0.5", 5277));
        // framed: size 16 (0x0010), id 1
        assertArrayEquals(concat(new byte[]{0x00, 0x10, 0x00, 0x01}, expected),
                WifiBootstrap.frame(WifiBootstrap.WIFI_START_REQUEST,
                        WifiBootstrap.startRequest("192.168.0.5", 5277)));
    }

    @Test
    public void infoResponseWireBytesIncludeZeroValuedRequiredFields() {
        WifiBootstrap.Config config = config();
        config.ssid = "ab";
        config.key = "cd";
        config.securityMode = WifiBootstrap.SECURITY_WPA2_PERSONAL;   // 8
        config.accessPointType = WifiBootstrap.AP_STATIC;             // 0: still written (required)
        byte[] expected = concat(
                new byte[]{0x0A, 0x02}, ascii("ab"),
                new byte[]{0x12, 0x02}, ascii("cd"),
                new byte[]{0x1A, 0x11}, ascii("00:11:22:33:44:55"),
                new byte[]{0x20, 0x08},
                new byte[]{0x28, 0x00});
        assertArrayEquals(expected, WifiBootstrap.infoResponse(config));
    }

    @Test
    public void frameSizeExcludesTheHeader() {
        byte[] framed = WifiBootstrap.frame(3, new byte[300]);
        assertEquals(304, framed.length);
        assertEquals(0x01, framed[0]);   // 300 = 0x012C
        assertEquals(0x2C, framed[1]);
        assertEquals(0x00, framed[2]);
        assertEquals(0x03, framed[3]);
    }

    @Test
    public void readerReassemblesByteAtATimeAndSeveralMessagesInOneRead() throws Exception {
        byte[] a = WifiBootstrap.frame(WifiBootstrap.WIFI_INFO_REQUEST, new byte[0]);
        byte[] b = WifiBootstrap.frame(WifiBootstrap.WIFI_CONNECT_STATUS, new byte[]{0x08, 0x01});
        byte[] both = concat(a, b);

        WifiBootstrap.Reader slow = new WifiBootstrap.Reader();
        List<WifiBootstrap.Message> out = new ArrayList<WifiBootstrap.Message>();
        for (byte value : both) {
            slow.feed(new byte[]{value}, 0, 1, out);
        }
        assertEquals(2, out.size());
        assertEquals(WifiBootstrap.WIFI_INFO_REQUEST, out.get(0).id);
        assertEquals(WifiBootstrap.WIFI_CONNECT_STATUS, out.get(1).id);
        assertArrayEquals(new byte[]{0x08, 0x01}, out.get(1).payload);

        out.clear();
        new WifiBootstrap.Reader().feed(both, 0, both.length, out);
        assertEquals(2, out.size());
    }

    @Test
    public void readerRejectsAnImplausibleSize() {
        try {
            new WifiBootstrap.Reader().feed(new byte[]{(byte) 0xFF, (byte) 0xFF, 0, 2}, 0, 4,
                    new ArrayList<WifiBootstrap.Message>());
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("size"));
        }
    }

    @Test
    public void configProblemsAreNamed() {
        assertNull(config().problem());

        WifiBootstrap.Config noIp = config();
        noIp.ipAddress = "";
        assertTrue(noIp.problem().contains("IP"));

        WifiBootstrap.Config noSsid = config();
        noSsid.ssid = "";
        assertTrue(noSsid.problem().contains("network name"));

        WifiBootstrap.Config shortKey = config();
        shortKey.key = "short";
        assertTrue(shortKey.problem().contains("8"));

        WifiBootstrap.Config open = config();
        open.securityMode = WifiBootstrap.SECURITY_OPEN;
        open.key = "";
        assertNull("an open network needs no password", open.problem());
    }

    // ---- full session over a TCP loopback standing in for RFCOMM ---------------------------

    /** The phone's side: connects, expects the start request, asks for Wi-Fi info, reads it. */
    private static final class FakePhone implements Runnable {
        final Socket socket;
        final List<WifiBootstrap.Message> got = Collections.synchronizedList(
                new ArrayList<WifiBootstrap.Message>());
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        final boolean sendExtras;
        final boolean askForInfo;
        final Thread thread;

        FakePhone(int port, boolean askForInfo, boolean sendExtras) throws Exception {
            this.askForInfo = askForInfo;
            this.sendExtras = sendExtras;
            socket = new Socket(InetAddress.getByName("127.0.0.1"), port);
            socket.setSoTimeout(5000);
            thread = new Thread(this, "fake-phone");
            thread.start();
        }

        private void read(WifiBootstrap.Reader reader, int count) throws IOException {
            byte[] buffer = new byte[512];
            int before = got.size();
            while (got.size() - before < count) {
                int n = socket.getInputStream().read(buffer);
                if (n < 0) {
                    throw new IOException("head unit closed");
                }
                List<WifiBootstrap.Message> batch = new ArrayList<WifiBootstrap.Message>();
                reader.feed(buffer, 0, n, batch);
                got.addAll(batch);
            }
        }

        @Override
        public void run() {
            try {
                WifiBootstrap.Reader reader = new WifiBootstrap.Reader();
                read(reader, 1);                                   // WifiStartRequest
                OutputStream out = socket.getOutputStream();
                if (!askForInfo && !sendExtras) {
                    socket.close();                                // a phone that just hangs up
                    return;
                }
                if (askForInfo) {
                    out.write(WifiBootstrap.frame(WifiBootstrap.WIFI_INFO_REQUEST, new byte[0]));
                    out.flush();
                    try {
                        read(reader, 1);                           // WifiInfoResponse
                    } catch (IOException closed) {
                        return;                                    // refused: head unit hung up
                    }
                }
                if (sendExtras) {
                    out.write(WifiBootstrap.frame(WifiBootstrap.WIFI_START_RESPONSE,
                            new byte[]{0x08, 0x00}));
                    out.write(WifiBootstrap.frame(WifiBootstrap.WIFI_CONNECT_STATUS,
                            new byte[]{0x08, 0x00}));
                    out.flush();
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        }
    }

    private static WifiBootstrap.Result runSession(WifiBootstrap.Config config, boolean askForInfo,
                                                   boolean extras, List<String> events,
                                                   List<WifiBootstrap.Message> phoneGot)
            throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        FakePhone phone = new FakePhone(server.getLocalPort(), askForInfo, extras);
        Socket head = server.accept();
        head.setSoTimeout(5000);
        final List<String> log = events;
        try {
            WifiBootstrap.Result result = WifiBootstrap.run(head.getInputStream(),
                    head.getOutputStream(), config, new WifiBootstrap.Sink() {
                        @Override
                        public void onEvent(String message) {
                            log.add(message);
                        }
                    });
            if (!extras) {
                head.close();
            }
            phone.thread.join(5000);
            if (phone.failure.get() != null) {
                throw new AssertionError("phone failed: " + phone.failure.get(), phone.failure.get());
            }
            phoneGot.addAll(phone.got);
            return result;
        } finally {
            head.close();
            phone.socket.close();
            server.close();
        }
    }

    @Test
    public void fullExchangeSendsStartRequestThenWifiInfoAndStopsAfterTwoMoreMessages()
            throws Exception {
        List<String> events = new ArrayList<String>();
        List<WifiBootstrap.Message> phoneGot = new ArrayList<WifiBootstrap.Message>();
        WifiBootstrap.Result result = runSession(config(), true, true, events, phoneGot);

        assertTrue(result.complete());
        assertTrue(result.infoRequested);
        assertNull(result.refused);
        assertEquals(2, phoneGot.size());
        assertEquals(WifiBootstrap.WIFI_START_REQUEST, phoneGot.get(0).id);
        assertArrayEquals(WifiBootstrap.startRequest("192.168.0.5", 5277), phoneGot.get(0).payload);
        assertEquals(WifiBootstrap.WIFI_INFO_RESPONSE, phoneGot.get(1).id);
        assertArrayEquals(WifiBootstrap.infoResponse(config()), phoneGot.get(1).payload);
        assertTrue(events.toString(), events.toString().contains("bootstrap finished"));
        assertEquals(Arrays_asList("WifiInfoRequest(0B)", "WifiStartResponse(2B)",
                "WifiConnectStatus(2B)"), result.received);
    }

    private static List<String> Arrays_asList(String... items) {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, items);
        return list;
    }

    @Test
    public void withoutAnSsidTheWifiDetailsAreNotSent() throws Exception {
        WifiBootstrap.Config config = config();
        config.ssid = "";
        List<String> events = new ArrayList<String>();
        List<WifiBootstrap.Message> phoneGot = new ArrayList<WifiBootstrap.Message>();
        WifiBootstrap.Result result = runSession(config, true, false, events, phoneGot);

        assertFalse(result.complete());
        assertFalse(result.infoSent);
        assertNotNull(result.refused);
        assertTrue(result.refused.contains("network name"));
        assertEquals("only the start request reached the phone", 1, phoneGot.size());
        assertTrue(events.toString().contains("NOT sending Wi-Fi details"));
    }

    @Test
    public void aShortPasswordWarnsButStillSends() throws Exception {
        WifiBootstrap.Config config = config();
        config.key = "short";
        List<String> events = new ArrayList<String>();
        List<WifiBootstrap.Message> phoneGot = new ArrayList<WifiBootstrap.Message>();
        WifiBootstrap.Result result = runSession(config, true, true, events, phoneGot);
        assertTrue(result.infoSent);
        assertTrue(events.toString().contains("warning"));
    }

    @Test
    public void aPhoneThatHangsUpWithoutAskingForInfoGetsNoWifiDetails() throws Exception {
        List<String> events = new ArrayList<String>();
        List<WifiBootstrap.Message> phoneGot = new ArrayList<WifiBootstrap.Message>();
        WifiBootstrap.Result result = runSession(config(), false, false, events, phoneGot);
        assertTrue(result.startRequestSent);
        assertFalse(result.infoRequested);
        assertFalse(result.infoSent);
        assertEquals(1, phoneGot.size());
    }

    @Test
    public void ioFailureSurfacesInsteadOfLooping() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("rfcomm link lost");
            }
        };
        try {
            WifiBootstrap.run(broken, new ByteArrayOutputStream(), config(), null);
            fail("expected IOException");
        } catch (IOException expected) {
            assertEquals("rfcomm link lost", expected.getMessage());
        }
        // and a clean EOF returns normally with only the start request sent
        try {
            WifiBootstrap.Result result = WifiBootstrap.run(new ByteArrayInputStream(new byte[0]),
                    new ByteArrayOutputStream(), config(), null);
            assertTrue(result.startRequestSent);
            assertFalse(result.complete());
        } catch (IOException unexpected) {
            fail(unexpected.toString());
        }
    }

    @Test
    public void aSilentPhoneEndsWithTheSocketTimeoutNotAHang() throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Socket phone = new Socket(InetAddress.getByName("127.0.0.1"), server.getLocalPort());
        Socket head = server.accept();
        head.setSoTimeout(300);
        long started = System.nanoTime();
        try {
            WifiBootstrap.run(head.getInputStream(), head.getOutputStream(), config(), null);
            fail("expected a timeout");
        } catch (java.net.SocketTimeoutException expected) {
            assertTrue((System.nanoTime() - started) / 1000000L < 3000);
        } finally {
            phone.close();
            head.close();
            server.close();
        }
    }
}
