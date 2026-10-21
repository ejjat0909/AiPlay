package com.ejjat.aiplay.receiver;

import android.content.Context;

import com.ejjat.aiplay.R;
import com.ejjat.aiplay.core.aa.AaControl;
import com.ejjat.aiplay.core.aa.AaCryptor;
import com.ejjat.aiplay.core.aa.AaProtocolException;
import com.ejjat.aiplay.core.aa.AaSession;
import com.ejjat.aiplay.device.Diag;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

/**
 * Android Auto wireless endpoint: a plain TCP listener that speaks the AA protocol.
 *
 * <p>The socket carries plain AA frames, as it does on a real head unit. TLS is not on the socket:
 * the session runs the TLS 1.2 handshake <em>inside</em> {@code SSL_HANDSHAKE} messages with this
 * side as the TLS client, then encrypts every frame ({@link AaCryptor}, {@link AaSession}).
 * 0.1.x to 0.2.0-alpha3 ran a TLS server on the socket instead ({@code AaTlsServer}), which a real
 * phone would not have connected to.</p>
 *
 * <p>Scope: version negotiation, in-band TLS, auth, service discovery, ping, and the video channel.
 * The session log records exactly what the phone asked for.</p>
 */
public final class AaServer {

    public interface Listener {
        void onEvent(String message);
    }

    private static final String IDENTITY_PASSWORD = "aiplay";
    private static final AtomicInteger SESSIONS = new AtomicInteger();

    private final Context context;
    private final int port;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private SSLContext tlsContext;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public AaServer(Context context, int port, Listener listener) {
        this.context = context.getApplicationContext();
        this.port = port;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }

    public int port() {
        return port;
    }

    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            tlsContext = buildClientContext();
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            serverSocket = socket;
        } catch (Exception error) {
            running.set(false);
            throw error;
        }
        event("listening on tcp " + port + " (plain AA; TLS runs inside the protocol)");

        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "aiplay-accept");
        acceptThread.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closeQuietly(serverSocket);
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        event("stopped");
    }

    /**
     * The head unit's TLS client identity (the committed, legacy-encrypted PKCS12 — see
     * {@code ReceiverIdentityTest}). It is AiPlay's own self-signed certificate; no credential
     * issued to another vendor is used.
     */
    private SSLContext buildClientContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        InputStream input = context.getResources().openRawResource(R.raw.receiver_identity);
        try {
            keyStore.load(input, IDENTITY_PASSWORD.toCharArray());
        } finally {
            input.close();
        }
        return AaCryptor.clientContext(keyStore, IDENTITY_PASSWORD.toCharArray());
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                final Socket session = socket;
                final int id = SESSIONS.incrementAndGet();
                event("session " + id + " accepted from "
                        + session.getInetAddress().getHostAddress());
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        handle(session, id);
                    }
                }, "aiplay-session-" + id);
                worker.start();
            } catch (IOException error) {
                if (running.get()) {
                    event("accept failed: " + error.getMessage());
                }
                closeQuietly(socket);
            }
        }
    }

    private void handle(Socket socket, int id) {
        AaSession attached = null;
        try {
            socket.setSoTimeout(15000);
            socket.setTcpNoDelay(true);

            AaControl.ServiceDiscoveryConfig discovery = new AaControl.ServiceDiscoveryConfig();
            discovery.headUnitName = "AiPlay";
            discovery.swVersion = com.ejjat.aiplay.BuildConfig.VERSION_NAME;
            discovery.videoWidth = 1280;
            discovery.videoHeight = 720;

            final AaSession session = new AaSession(socket.getInputStream(), socket.getOutputStream(),
                    new AaSession.Sink() {
                        @Override
                        public void onEvent(String message) {
                            Diag.log("receiver", message);
                        }
                    }, discovery, new AaCryptor(tlsContext));
            ProjectionHub hub = ProjectionHub.get(context);
            session.setVideoSink(hub);
            session.setAudioSink(hub);
            session.setSensorSource(hub);
            hub.attachSession(session);
            attached = session;
            session.begin();

            byte[] buffer = new byte[65536];
            while (running.get()) {
                if (session.pump(buffer) < 0) {
                    break;
                }
            }
        } catch (AaProtocolException error) {
            event("session " + id + ": protocol error: " + error.getMessage());
        } catch (Exception error) {
            event("session " + id + " ended: " + error.getClass().getSimpleName() + " "
                    + error.getMessage());
        } finally {
            if (attached != null) {
                ProjectionHub.get(context).detachSession(attached);
            }
            closeQuietly(socket);
        }
    }

    private void event(String message) {
        Diag.log("receiver", message);
        if (listener != null) {
            listener.onEvent(message);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }
}
