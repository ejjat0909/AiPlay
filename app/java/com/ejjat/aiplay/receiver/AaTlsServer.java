package com.ejjat.aiplay.receiver;

import android.content.Context;

import com.ejjat.aiplay.R;
import com.ejjat.aiplay.core.aa.AaControl;
import com.ejjat.aiplay.core.aa.AaProtocolException;
import com.ejjat.aiplay.core.aa.AaSession;
import com.ejjat.aiplay.device.Diag;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Android Auto wireless endpoint: a TLS listener that speaks the AA control plane.
 *
 * <p>Scope in 0.1.0: TLS, frame codec, version negotiation, auth, service discovery and channel
 * open responses. Video, audio and input channels are not implemented yet; the session log records
 * exactly what the phone asked for so the next milestone has real evidence to build on.</p>
 */
public final class AaTlsServer {

    public interface Listener {
        void onEvent(String message);
    }

    private static final String KEYSTORE_PASSWORD = "aiplay";
    private static final AtomicInteger SESSIONS = new AtomicInteger();

    private final Context context;
    private final int port;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public AaTlsServer(Context context, int port, Listener listener) {
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
        SSLContext sslContext = buildContext();
        SSLServerSocket socket = (SSLServerSocket) sslContext.getServerSocketFactory()
                .createServerSocket();
        socket.setReuseAddress(true);
        socket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
        socket.bind(new InetSocketAddress(port));
        serverSocket = socket;
        event("listening on tcp " + port + " (TLS)");

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

    private SSLContext buildContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        InputStream input = context.getResources().openRawResource(R.raw.receiver_identity);
        try {
            keyStore.load(input, KEYSTORE_PASSWORD.toCharArray());
        } finally {
            input.close();
        }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, KEYSTORE_PASSWORD.toCharArray());
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(factory.getKeyManagers(), null, null);
        return sslContext;
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                final SSLSocket session = (SSLSocket) socket;
                session.setUseClientMode(false);
                int id = SESSIONS.incrementAndGet();
                event("session " + id + " accepted from " + session.getInetAddress().getHostAddress());
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

    private void handle(SSLSocket socket, int id) {
        try {
            socket.setSoTimeout(15000);
            try {
                socket.startHandshake();
            } catch (SSLException error) {
                // Usually a bare TCP connect-and-close (a readiness check, a port scanner, adb
                // forward with nothing behind it): no ClientHello, nothing to answer. Say that,
                // but keep the real reason so a genuine TLS incompatibility is not hidden.
                event("session " + id + ": TLS never completed (port probe or incompatible "
                        + "client): " + error.getMessage());
                return;
            }
            event("session " + id + ": tls handshake ok, cipher "
                    + socket.getSession().getCipherSuite());

            AaControl.ServiceDiscoveryConfig discovery = new AaControl.ServiceDiscoveryConfig();
            discovery.headUnitName = "AiPlay";
            discovery.swVersion = com.ejjat.aiplay.BuildConfig.VERSION_NAME;
            discovery.videoWidth = 1280;
            discovery.videoHeight = 720;

            AaSession session = new AaSession(socket.getInputStream(), socket.getOutputStream(),
                    new AaSession.Sink() {
                        @Override
                        public void onEvent(String message) {
                            Diag.log("receiver", message);
                        }
                    }, discovery);
            session.begin();

            byte[] buffer = new byte[8192];
            while (running.get()) {
                if (session.pump(buffer) < 0) {
                    break;
                }
            }
        } catch (AaProtocolException error) {
            event("protocol error: " + error.getMessage());
        } catch (Exception error) {
            event("session ended: " + error.getClass().getSimpleName() + " " + error.getMessage());
        } finally {
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
