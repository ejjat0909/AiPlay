package com.ejjat.aiplay.core.aa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One Android Auto control session over an established byte stream.
 *
 * <p>Pure Java on purpose: the TLS listener in the Android layer only accepts sockets and hands the
 * streams here, which means the whole handshake can be exercised by a desktop JVM test over a real
 * TLS connection.</p>
 *
 * <p>Implemented in 0.1.0: version negotiation, auth complete, service discovery, channel open
 * responses, ping and shutdown. Video, audio and input channels are not implemented — the session
 * says so in its event log instead of silently dropping frames.</p>
 */
public final class AaSession {

    /** Receives human-readable session events for diagnostics. */
    public interface Sink {
        void onEvent(String message);
    }

    private static final int MAX_PAYLOAD = AaFrameCodec.MAX_PAYLOAD;

    private final InputStream input;
    private final OutputStream output;
    private final Sink sink;
    private final AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
    private final List<AaFrame> pending = new ArrayList<AaFrame>();
    private final AaControl.ServiceDiscoveryConfig discovery;

    private boolean versionSent;

    public AaSession(InputStream input, OutputStream output, Sink sink,
                     AaControl.ServiceDiscoveryConfig discovery) {
        this.input = input;
        this.output = output;
        this.sink = sink;
        this.discovery = discovery;
    }

    /** Sends the head unit's version request. Called once, right after the TLS handshake. */
    public void begin() throws IOException {
        if (versionSent) {
            return;
        }
        versionSent = true;
        send(AaMessageType.VERSION_REQUEST, AaControl.versionRequest());
    }

    /**
     * Reads once and processes every complete frame that arrived.
     *
     * @return number of frames handled, or -1 when the peer closed the stream
     */
    public int pump(byte[] buffer) throws IOException, AaProtocolException {
        int read = input.read(buffer);
        if (read < 0) {
            event("peer closed the session");
            return -1;
        }
        pending.clear();
        parser.feed(buffer, 0, read, pending);
        for (int i = 0; i < pending.size(); i++) {
            handle(pending.get(i));
        }
        return pending.size();
    }

    /** Processes one frame and writes any replies. Exposed for tests. */
    public void handle(AaFrame frame) throws IOException {
        switch (frame.type) {
            case AaMessageType.VERSION_RESPONSE: {
                AaControl.VersionResponse response = AaControl.parseVersionResponse(frame.payload);
                event("peer version " + response);
                send(AaMessageType.AUTH_COMPLETE, AaControl.authComplete(AaControl.STATUS_OK));
                break;
            }
            case AaMessageType.SERVICE_DISCOVERY_REQUEST: {
                AaControl.ServiceDiscoveryRequest request =
                        AaControl.parseServiceDiscoveryRequest(frame.payload);
                event("service discovery from " + request.deviceBrand + " " + request.deviceName);
                send(AaMessageType.SERVICE_DISCOVERY_RESPONSE,
                        AaControl.serviceDiscoveryResponse(discovery));
                break;
            }
            case AaMessageType.CHANNEL_OPEN_REQUEST: {
                event("channel open request (" + frame.payload.length + "B)");
                send(AaMessageType.CHANNEL_OPEN_RESPONSE,
                        AaControl.channelOpenResponse(AaControl.STATUS_FAIL));
                break;
            }
            case AaMessageType.PING_REQUEST: {
                event("ping");
                send(AaMessageType.PING_RESPONSE,
                        AaControl.pingResponse(System.nanoTime() / 1000L));
                break;
            }
            case AaMessageType.SHUTDOWN_REQUEST: {
                event("peer requested shutdown");
                send(AaMessageType.SHUTDOWN_RESPONSE, new byte[0]);
                break;
            }
            default: {
                event("frame " + frame.typeName() + " (" + frame.payload.length
                        + "B) — not handled in 0.1.0");
                break;
            }
        }
    }

    private void send(int type, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) {
            throw new IOException("reply too large: " + payload.length);
        }
        output.write(AaFrameCodec.encode(type, payload));
        output.flush();
        event("sent " + AaMessageType.name(type) + " (" + payload.length + "B)");
    }

    private void event(String message) {
        if (sink != null) {
            sink.onEvent(message);
        }
    }
}
