package com.ejjat.aiplay.core.aa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLEngine;

/** The in-band TLS layer against a real server-mode engine that demands a client certificate. */
public class AaCryptorTest {

    /** Runs the handshake by passing flights back and forth, exactly as SSL_HANDSHAKE messages do. */
    private static void handshake(AaCryptor client, AaCryptor phone) throws Exception {
        byte[] flight = client.beginHandshake();
        phone.beginHandshake();
        for (int round = 0; round < 20; round++) {
            byte[] reply = phone.handshake(flight, 0, flight.length);
            flight = reply.length == 0 ? reply : client.handshake(reply, 0, reply.length);
            if (client.isHandshakeDone() && phone.isHandshakeDone()) {
                return;
            }
            if (flight.length == 0 && reply.length == 0) {
                break;
            }
        }
        fail("handshake did not finish: client=" + client.isHandshakeDone()
                + " phone=" + phone.isHandshakeDone());
    }

    private static AaCryptor newClient() throws Exception {
        return new AaCryptor(TlsFixtures.headUnitContext());
    }

    @Test
    public void headUnitIsTheClientAndNegotiatesTls12() throws Exception {
        byte[] hello = newClient().beginHandshake();
        assertTrue("first flight is a TLS handshake record (0x16) from the client", hello.length > 5
                && hello[0] == 0x16 && hello[5] == 0x01 /* ClientHello */);

        AaCryptor client = newClient();
        handshake(client, new AaCryptor(TlsFixtures.phoneEngine()));
        assertEquals("TLSv1.2", client.protocol());
        assertTrue(client.isHandshakeDone());
    }

    @Test
    public void headUnitPresentsItsCertificateEvenThoughNoIssuerMatches() throws Exception {
        // The engine runs setNeedClientAuth(true): without a client certificate it throws. The
        // head unit's identity is self-signed, so the stock key manager would send nothing.
        SSLEngine phoneEngine = TlsFixtures.phoneEngine();
        AaCryptor client = newClient();
        AaCryptor phone = new AaCryptor(phoneEngine);
        handshake(client, phone);

        X509Certificate[] chain = (X509Certificate[]) phoneEngine.getSession().getPeerCertificates();
        assertTrue(chain.length >= 1);
        assertTrue("subject should be the committed receiver identity: " + chain[0].getSubjectX500Principal(),
                chain[0].getSubjectX500Principal().getName().contains("AiPlay"));
    }

    @Test
    public void roundTripsFramePayloadsBothWays() throws Exception {
        AaCryptor client = newClient();
        AaCryptor phone = new AaCryptor(TlsFixtures.phoneEngine());
        handshake(client, phone);

        byte[] message = new byte[AaChannel.MAX_FRAME_PAYLOAD];
        for (int i = 0; i < message.length; i++) {
            message[i] = (byte) (i * 7);
        }
        byte[] wire = client.encrypt(message, 0, message.length);
        assertTrue("TLS adds overhead, so the encrypted size exceeds the plaintext", wire.length > message.length);
        assertTrue("and is not the plaintext", !java.util.Arrays.equals(wire, message));
        assertArrayEquals(message, phone.decrypt(wire, 0, wire.length));

        byte[] back = phone.encrypt(new byte[]{1, 2, 3}, 0, 3);
        assertArrayEquals(new byte[]{1, 2, 3}, client.decrypt(back, 0, back.length));
    }

    @Test
    public void aHandshakeFlightSplitIntoTinyPiecesStillCompletes() throws Exception {
        // TCP may deliver one SSL_HANDSHAKE message in several reads; a TLS record must be able to
        // arrive in pieces. Feed the phone's flights to the head unit 7 bytes at a time.
        AaCryptor client = newClient();
        AaCryptor phone = new AaCryptor(TlsFixtures.phoneEngine());
        byte[] flight = client.beginHandshake();
        phone.beginHandshake();
        for (int round = 0; round < 20 && !(client.isHandshakeDone() && phone.isHandshakeDone());
             round++) {
            byte[] reply = phone.handshake(flight, 0, flight.length);
            ByteArrayOutputStream next = new ByteArrayOutputStream();
            for (int at = 0; at < reply.length; at += 7) {
                int n = Math.min(7, reply.length - at);
                byte[] out = client.handshake(reply, at, n);
                next.write(out, 0, out.length);
            }
            flight = next.toByteArray();
        }
        assertTrue(client.isHandshakeDone());
        assertTrue(phone.isHandshakeDone());
    }

    @Test
    public void decryptRejectsGarbageAndEarlyFrames() throws Exception {
        AaCryptor early = newClient();
        try {
            early.decrypt(new byte[]{1, 2, 3}, 0, 3);
            fail("expected a protocol error before the handshake finished");
        } catch (AaProtocolException expected) {
            assertTrue(expected.getMessage().contains("handshake"));
        }

        AaCryptor client = newClient();
        handshake(client, new AaCryptor(TlsFixtures.phoneEngine()));
        try {
            client.decrypt(new byte[]{0x17, 0x03, 0x03, 0x00, 0x05, 1, 2, 3, 4, 5}, 0, 10);
            fail("expected a protocol error for a record that does not authenticate");
        } catch (AaProtocolException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    public void handshakeCannotStartTwiceOrBeFedBeforeStarting() throws Exception {
        AaCryptor client = newClient();
        try {
            client.handshake(new byte[]{1}, 0, 1);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // good
        }
        client.beginHandshake();
        try {
            client.beginHandshake();
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // good
        }
    }
}
