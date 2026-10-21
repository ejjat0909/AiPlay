package com.ejjat.aiplay.core.aa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * The in-band TLS layer of an Android Auto session.
 *
 * <p>Real Android Auto does not run TLS on the socket. The TCP stream carries plain AA frames;
 * the TLS handshake travels inside {@code SSL_HANDSHAKE} control messages, and once it completes
 * every frame payload (except version, handshake and auth-complete) is a TLS record. The head
 * unit is the TLS <em>client</em>, TLS 1.2, and presents a certificate (aasdk
 * {@code Messenger/Cryptor.cpp}: {@code SSL_set_connect_state}, {@code TLSv1_2_client_method},
 * certificate and key loaded into the context, no peer verification).</p>
 *
 * <p>This class is the {@code Cryptor}: it holds an {@link SSLEngine} and exposes the handshake as
 * "bytes in, bytes out" so the session can carry it in messages, and {@link #encrypt} /
 * {@link #decrypt} for individual frames. One TLS record per frame, both ways.</p>
 *
 * <p>Pure JDK ({@code javax.net.ssl}): it is exercised by desktop JVM tests against a real
 * server-mode engine, and runs unchanged on Android.</p>
 */
public final class AaCryptor implements AaFrameCodec.Cipher {

    private final SSLEngine engine;
    private final ByteArrayOutputStream inbound = new ByteArrayOutputStream();
    private boolean handshakeStarted;
    private boolean handshakeDone;

    public AaCryptor(SSLContext context) {
        engine = context.createSSLEngine();
        engine.setUseClientMode(true);
        engine.setEnabledProtocols(new String[]{"TLSv1.2"});
    }

    /** Test seam: wraps an already configured engine (a server-mode engine plays the phone). */
    AaCryptor(SSLEngine engine) {
        this.engine = engine;
    }

    /** Builds the head unit's client context from a keystore holding its identity. */
    public static SSLContext clientContext(KeyStore identity, char[] password) throws Exception {
        KeyManagerFactory factory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(identity, password);
        X509KeyManager base = null;
        for (javax.net.ssl.KeyManager manager : factory.getKeyManagers()) {
            if (manager instanceof X509KeyManager) {
                base = (X509KeyManager) manager;
            }
        }
        if (base == null) {
            throw new IllegalStateException("no X509KeyManager for the receiver identity");
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(new javax.net.ssl.KeyManager[]{new AlwaysPresent(base)},
                new TrustManager[]{new TrustPhone()}, null);
        return context;
    }

    public boolean isHandshakeDone() {
        return handshakeDone;
    }

    public String protocol() {
        return engine.getSession().getProtocol();
    }

    public String cipherSuite() {
        return engine.getSession().getCipherSuite();
    }

    /**
     * Starts the handshake and returns the first flight (ClientHello) to send in an
     * {@code SSL_HANDSHAKE} message.
     */
    public byte[] beginHandshake() throws IOException {
        if (handshakeStarted) {
            throw new IllegalStateException("handshake already started");
        }
        handshakeStarted = true;
        engine.beginHandshake();
        return drive();
    }

    /**
     * Feeds the peer's handshake bytes (the payload of one {@code SSL_HANDSHAKE} message) and
     * returns what must be sent back, possibly nothing. After this returns,
     * {@link #isHandshakeDone()} says whether TLS is up.
     */
    public byte[] handshake(byte[] data, int offset, int length) throws IOException {
        if (!handshakeStarted) {
            throw new IllegalStateException("handshake not started");
        }
        inbound.write(data, offset, length);
        return drive();
    }

    /**
     * Runs the engine until it needs more input or is done, collecting output. Input is kept in
     * {@link #inbound} so a flight that arrives split across messages is not lost.
     */
    private byte[] drive() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer empty = ByteBuffer.allocate(0);
        ByteBuffer net = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        ByteBuffer app = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
        int guard = 0;
        while (guard++ < 200) {
            SSLEngineResult.HandshakeStatus status = engine.getHandshakeStatus();
            if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                Runnable task;
                while ((task = engine.getDelegatedTask()) != null) {
                    task.run();
                }
                continue;
            }
            if (status == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                net.clear();
                SSLEngineResult result = engine.wrap(empty, net);
                net.flip();
                out.write(net.array(), net.arrayOffset() + net.position(), net.remaining());
                if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                    handshakeDone = true;
                }
                continue;
            }
            if (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                byte[] pending = inbound.toByteArray();
                if (pending.length == 0) {
                    break; // wait for the phone
                }
                ByteBuffer in = ByteBuffer.wrap(pending);
                app.clear();
                SSLEngineResult result = engine.unwrap(in, app);
                inbound.reset();
                if (in.hasRemaining()) {
                    inbound.write(pending, in.position(), in.remaining());
                }
                if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                    handshakeDone = true;
                }
                if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    break; // a partial record: wait for the rest
                }
                continue;
            }
            // FINISHED, or NOT_HANDSHAKING once the engine has reported FINISHED in a result above.
            if (status == SSLEngineResult.HandshakeStatus.FINISHED) {
                handshakeDone = true;
            }
            break;
        }
        return out.toByteArray();
    }

    /** Encrypts one frame payload into exactly one TLS record (plus nothing else). */
    @Override
    public byte[] encrypt(byte[] data, int offset, int length) throws IOException {
        requireDone();
        ByteArrayOutputStream out = new ByteArrayOutputStream(length + 64);
        ByteBuffer in = ByteBuffer.wrap(data, offset, length);
        ByteBuffer net = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        while (in.hasRemaining()) {
            net.clear();
            SSLEngineResult result = engine.wrap(in, net);
            if (result.getStatus() != SSLEngineResult.Status.OK) {
                throw new SSLException("TLS wrap failed: " + result.getStatus());
            }
            net.flip();
            out.write(net.array(), net.arrayOffset() + net.position(), net.remaining());
        }
        return out.toByteArray();
    }

    /** Decrypts one frame payload (one or more complete TLS records). */
    @Override
    public byte[] decrypt(byte[] data, int offset, int length) throws AaProtocolException {
        if (!handshakeDone) {
            throw new AaProtocolException("encrypted frame before the TLS handshake finished");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(length);
            ByteBuffer in = ByteBuffer.wrap(data, offset, length);
            ByteBuffer app = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
            while (in.hasRemaining()) {
                app.clear();
                SSLEngineResult result = engine.unwrap(in, app);
                if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    throw new AaProtocolException("truncated TLS record in an encrypted frame");
                }
                if (result.getStatus() != SSLEngineResult.Status.OK) {
                    throw new AaProtocolException("TLS unwrap failed: " + result.getStatus());
                }
                app.flip();
                out.write(app.array(), app.arrayOffset() + app.position(), app.remaining());
            }
            return out.toByteArray();
        } catch (SSLException error) {
            throw new AaProtocolException("TLS error: " + error.getMessage());
        }
    }

    private void requireDone() {
        if (!handshakeDone) {
            throw new IllegalStateException("TLS handshake not finished");
        }
    }

    /**
     * Presents the receiver's certificate whatever the phone's CertificateRequest lists as
     * acceptable issuers. A self-signed head-unit certificate never matches a Google issuer list,
     * and the default key manager would then silently send no certificate at all.
     */
    private static final class AlwaysPresent extends X509ExtendedKeyManager {
        private final X509KeyManager base;

        AlwaysPresent(X509KeyManager base) {
            this.base = base;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return base.getClientAliases(keyType, null);
        }

        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            return first(keyTypes);
        }

        @Override
        public String chooseEngineClientAlias(String[] keyTypes, Principal[] issuers,
                                              SSLEngine engine) {
            return first(keyTypes);
        }

        private String first(String[] keyTypes) {
            for (String keyType : keyTypes) {
                String[] aliases = base.getClientAliases(keyType, null);
                if (aliases != null && aliases.length > 0) {
                    return aliases[0];
                }
            }
            return null;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return base.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return base.chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return base.getCertificateChain(alias);
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return base.getPrivateKey(alias);
        }
    }

    /**
     * Accepts the phone's certificate without validation, like aasdk (no peer verification).
     * Android Auto's in-band TLS is not an authentication mechanism: the phone's certificate
     * chains to nothing a head unit could check. What gates a session is the Bluetooth pairing and
     * Wi-Fi credentials, not this handshake.
     */
    private static final class TrustPhone implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
