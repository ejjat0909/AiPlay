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
 * <p>Implemented: version negotiation, auth complete, service discovery, channel open responses,
 * ping and shutdown. Video, audio and input channels are not implemented — the session says so in
 * its event log instead of silently dropping messages.</p>
 *
 * <p>Wire format: see {@link AaFrameCodec}. Every message carries a channel id and a flags byte
 * before its size; the message id is the first two bytes of the message payload.</p>
 */
public final class AaSession {

    /** Receives human-readable session events for diagnostics. */
    public interface Sink {
        void onEvent(String message);
    }

    private static final int MAX_MESSAGE = AaFrameCodec.MAX_MESSAGE_PAYLOAD;

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
        sendControl(AaMessageType.VERSION_REQUEST, AaControl.versionRequest());
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

    /** Processes one message and writes any replies. Exposed for tests. */
    public void handle(AaFrame frame) throws IOException {
        if (!frame.control) {
            event("message " + frame + " on " + AaChannel.name(frame.channel)
                    + " — no channel handler in 0.1.x");
            return;
        }
        switch (frame.messageId) {
            case AaMessageType.VERSION_RESPONSE: {
                AaControl.VersionResponse response = AaControl.parseVersionResponse(frame.payload);
                event("peer version " + response);
                sendControl(AaMessageType.AUTH_COMPLETE, AaControl.authComplete(AaControl.STATUS_OK));
                break;
            }
            case AaMessageType.SERVICE_DISCOVERY_REQUEST: {
                AaControl.ServiceDiscoveryRequest request =
                        AaControl.parseServiceDiscoveryRequest(frame.payload);
                event("service discovery from " + request.deviceBrand + " " + request.deviceName);
                sendControl(AaMessageType.SERVICE_DISCOVERY_RESPONSE,
                        AaControl.serviceDiscoveryResponse(discovery));
                break;
            }
            case AaMessageType.CHANNEL_OPEN_REQUEST: {
                event("channel open request (" + frame.payload.length + "B)");
                sendControl(AaMessageType.CHANNEL_OPEN_RESPONSE,
                        AaControl.channelOpenResponse(AaControl.STATUS_FAIL));
                break;
            }
            case AaMessageType.PING_REQUEST: {
                event("ping");
                sendControl(AaMessageType.PING_RESPONSE,
                        AaControl.pingResponse(System.nanoTime() / 1000L));
                break;
            }
            case AaMessageType.SHUTDOWN_REQUEST: {
                event("peer requested shutdown");
                sendControl(AaMessageType.SHUTDOWN_RESPONSE, new byte[0]);
                break;
            }
            default: {
                event("message " + frame + " — not handled in 0.1.x");
                break;
            }
        }
    }

    private void sendControl(int messageId, byte[] payload) throws IOException {
        send(AaChannel.CONTROL, true, messageId, payload);
    }

    /** Writes one message on the wire, splitting it into frames when it is large. */
    public void send(int channel, boolean control, int messageId, byte[] payload) throws IOException {
        if (payload.length + 2 > MAX_MESSAGE) {
            throw new IOException("message too large: " + payload.length);
        }
        output.write(AaFrameCodec.encode(channel, control, messageId, payload));
        output.flush();
        event("sent " + AaChannel.name(channel) + "/"
                + (control ? AaMessageType.name(messageId) : String.format("0x%04X", messageId))
                + " (" + payload.length + "B)");
    }

    private void event(String message) {
        if (sink != null) {
            sink.onEvent(message);
        }
    }
}
