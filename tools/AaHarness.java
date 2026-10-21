import com.ejjat.aiplay.core.aa.AaControl;
import com.ejjat.aiplay.core.aa.AaSession;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Desktop harness: runs the same AaSession the Android service uses, over TLS, so the receiver can
 * be exercised (and tools/aa-probe.py validated) without a phone.
 *
 * <pre>
 *   ./tools/test.sh                                  # builds core + a test keystore
 *   javac -cp build/test/classes -d build/harness tools/AaHarness.java
 *   java -cp build/test/classes:build/harness AaHarness 5277 build/test/keystore.p12 [video.h264]
 *   python3 tools/aa-probe.py 127.0.0.1
 * </pre>
 *
 * <p>With a fourth argument the harness writes every received video frame to that file, which is
 * how the probe's clip is checked for a byte-for-byte round trip.</p>
 */
public final class AaHarness {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 5277;
        String keystore = args.length > 1 ? args[1] : "build/test/keystore.p12";
        final String dump = args.length > 2 ? args[2] : null;

        KeyStore store = KeyStore.getInstance("PKCS12");
        InputStream input = new FileInputStream(keystore);
        try {
            store.load(input, "aiplay".toCharArray());
        } finally {
            input.close();
        }
        KeyManagerFactory factory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, "aiplay".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);

        final SSLServerSocket server =
                (SSLServerSocket) context.getServerSocketFactory().createServerSocket(port);
        server.setReuseAddress(true);
        System.out.println("AiPlay harness listening on tcp " + port + " (TLS)");
        System.out.println("probe it with: python3 tools/aa-probe.py 127.0.0.1 --port " + port);

        while (true) {
            final SSLSocket socket = (SSLSocket) server.accept();
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve(socket, dump);
                }
            }, "harness-session");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void serve(SSLSocket socket, String dump) {
        try {
            socket.setSoTimeout(30000);
            socket.startHandshake();
            System.out.println("--- session from " + socket.getInetAddress().getHostAddress()
                    + " cipher=" + socket.getSession().getCipherSuite());

            AaControl.ServiceDiscoveryConfig config = new AaControl.ServiceDiscoveryConfig();
            config.headUnitName = "AiPlay harness";
            config.carModel = "Desktop";
            config.swVersion = "0.1.0";

            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            AaSession session = new AaSession(in, out, new AaSession.Sink() {
                @Override
                public void onEvent(String message) {
                    System.out.println("    " + message);
                }
            }, config);
            final FileOutputStream video = dump == null ? null : new FileOutputStream(dump);
            session.setVideoSink(new AaSession.VideoSink() {
                @Override
                public void onVideoSetup(int configIndex) {
                }

                @Override
                public void onVideoStart(int session, int config) {
                }

                @Override
                public void onVideoFrame(long timestampMicros, byte[] data, int offset, int length) {
                    try {
                        if (video != null) {
                            video.write(data, offset, length);
                        }
                    } catch (java.io.IOException error) {
                        System.out.println("dump failed: " + error);
                    }
                }

                @Override
                public void onVideoStop() {
                    try {
                        if (video != null) {
                            video.close();
                        }
                    } catch (java.io.IOException ignored) {
                        // nothing useful to do
                    }
                }

                @Override
                public void onVideoFocus(boolean focused) {
                }
            });
            session.begin();

            byte[] buffer = new byte[65536];
            while (session.pump(buffer) >= 0) {
                // keep reading until the peer closes
            }
        } catch (Exception error) {
            System.out.println("session ended: " + error);
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
                // nothing useful to do
            }
        }
    }
}
