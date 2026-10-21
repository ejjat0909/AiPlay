package com.ejjat.aiplay.core.aa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * One Android Auto session over an established byte stream.
 *
 * <p>Pure Java on purpose: the listener in the Android layer only accepts sockets and hands the
 * streams here, which means the whole exchange can be exercised by a desktop JVM test.</p>
 *
 * <p>Implemented: version negotiation, auth complete, service discovery, ping, shutdown, and (0.2.0)
 * the video channel: channel open, AV setup, start/stop, video focus, and media acks. Audio, input
 * and sensor channels are not implemented; they are not advertised in service discovery and an
 * attempt to open one is answered with FAIL and logged, never silently dropped.</p>
 *
 * <p>Wire format: see {@link AaFrameCodec}. Every message carries a channel id and a flags byte
 * before its size; the message id is the first two bytes of the message payload.</p>
 */
public final class AaSession {

    /** Receives human-readable session events for diagnostics. */
    public interface Sink {
        void onEvent(String message);
    }

    /** Receives the phone's video stream. Called on the session thread; keep it fast. */
    public interface VideoSink {
        /** The phone selected a video configuration ({@code config_index}). */
        void onVideoSetup(int configIndex);

        /** The phone started streaming ({@code session}, {@code config}). */
        void onVideoStart(int session, int config);

        /**
         * One Annex-B H.264 chunk. {@code timestampMicros} is -1 for messages that carry none.
         * The array is owned by the callee only for the duration of the call.
         */
        void onVideoFrame(long timestampMicros, byte[] data, int offset, int length);

        /** The phone stopped streaming. */
        void onVideoStop();

        /** The phone asked for, or released, video focus. */
        void onVideoFocus(boolean focused);
    }

    /**
     * Frames the phone may send before it has to wait for an ack. 1 is the most conservative value;
     * every media frame is acked.
     */
    public static final int VIDEO_MAX_UNACKED = 1;

    private static final int MAX_MESSAGE = AaFrameCodec.MAX_MESSAGE_PAYLOAD;
    private static final int LOG_EVERY_FRAMES = 150;

    private final InputStream input;
    private final OutputStream output;
    private final Sink sink;
    private final AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
    private final List<AaFrame> pending = new ArrayList<AaFrame>();
    private final AaControl.ServiceDiscoveryConfig discovery;

    private VideoSink videoSink;
    private boolean versionSent;
    private boolean videoOpen;
    private boolean videoStarted;
    private int videoSession;
    private long videoFrames;
    private long videoBytes;

    public AaSession(InputStream input, OutputStream output, Sink sink,
                     AaControl.ServiceDiscoveryConfig discovery) {
        this.input = input;
        this.output = output;
        this.sink = sink;
        this.discovery = discovery;
    }

    public void setVideoSink(VideoSink videoSink) {
        this.videoSink = videoSink;
    }

    public long videoFrames() {
        return videoFrames;
    }

    public boolean isVideoStarted() {
        return videoStarted;
    }

    /** Sends the head unit's version request. Called once, right after the transport is up. */
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
            finishVideo();
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
            if (frame.channel == AaChannel.VIDEO && videoOpen) {
                handleVideo(frame);
            } else if (frame.channel == AaChannel.VIDEO) {
                event("video message " + frame + " before the channel was opened — ignored");
            } else {
                event("message " + frame + " on " + AaChannel.name(frame.channel)
                        + " — channel not implemented");
            }
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
                openChannel(frame);
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
                finishVideo();
                sendControl(AaMessageType.SHUTDOWN_RESPONSE, new byte[0]);
                break;
            }
            default: {
                event("message " + frame + " — not handled");
                break;
            }
        }
    }

    /**
     * A phone opens a channel by sending {@code ChannelOpenRequest} <em>on that channel</em>; the
     * answer goes back on the same channel. A request on the control channel is accepted too, with
     * the target taken from {@code channel_id}, because that is what 0.1.x probes sent.
     */
    private void openChannel(AaFrame frame) throws IOException {
        int target = frame.channel != AaChannel.CONTROL
                ? frame.channel : AaControl.parseChannelOpenRequest(frame.payload);
        boolean accept = target == AaChannel.VIDEO && discovery.advertiseVideo;
        event("channel open request for " + AaChannel.name(target)
                + (accept ? " — accepted" : " — refused (not implemented)"));
        if (accept) {
            videoOpen = true;
        }
        send(frame.channel, true, AaMessageType.CHANNEL_OPEN_RESPONSE,
                AaControl.channelOpenResponse(accept ? AaControl.STATUS_OK : AaControl.STATUS_FAIL));
    }

    private void handleVideo(AaFrame frame) throws IOException {
        switch (frame.messageId) {
            case AaAv.SETUP_REQUEST: {
                int index = AaAv.parseSetupRequest(frame.payload);
                boolean ok = index == 0;
                event("video setup request, config " + index + (ok ? "" : " — refused"));
                if (ok && videoSink != null) {
                    videoSink.onVideoSetup(index);
                }
                send(AaChannel.VIDEO, false, AaAv.SETUP_RESPONSE,
                        AaAv.setupResponse(ok ? AaAv.SETUP_OK : AaAv.SETUP_FAIL,
                                VIDEO_MAX_UNACKED, 0));
                break;
            }
            case AaAv.VIDEO_FOCUS_REQUEST: {
                boolean focused = AaAv.parseVideoFocusMode(frame.payload) != AaAv.FOCUS_UNFOCUSED;
                event("video focus request: " + (focused ? "focused" : "unfocused"));
                if (videoSink != null) {
                    videoSink.onVideoFocus(focused);
                }
                send(AaChannel.VIDEO, false, AaAv.VIDEO_FOCUS_INDICATION,
                        AaAv.videoFocusIndication(
                                focused ? AaAv.FOCUS_FOCUSED : AaAv.FOCUS_UNFOCUSED, false));
                break;
            }
            case AaAv.START_INDICATION: {
                int[] start = AaAv.parseStartIndication(frame.payload);
                videoSession = start[0];
                videoStarted = true;
                event("video start: session " + start[0] + ", config " + start[1]);
                if (videoSink != null) {
                    videoSink.onVideoStart(start[0], start[1]);
                }
                break;
            }
            case AaAv.STOP_INDICATION: {
                event("video stop");
                finishVideo();
                break;
            }
            case AaAv.MEDIA_WITH_TIMESTAMP:
            case AaAv.MEDIA: {
                handleMedia(frame);
                break;
            }
            default: {
                event("video message " + frame + " — not handled");
                break;
            }
        }
    }

    private void handleMedia(AaFrame frame) throws IOException {
        long timestamp = AaAv.mediaTimestamp(frame.messageId, frame.payload);
        int offset = AaAv.mediaDataOffset(frame.messageId, frame.payload);
        int length = frame.payload.length - offset;
        videoFrames++;
        videoBytes += length;
        if (videoFrames == 1 || videoFrames % LOG_EVERY_FRAMES == 0) {
            event("video frame " + videoFrames + " (" + length + "B"
                    + (timestamp >= 0 ? ", ts " + timestamp + "us" : "") + "), "
                    + videoBytes + "B total");
        }
        if (videoSink != null) {
            videoSink.onVideoFrame(timestamp, frame.payload, offset, length);
        }
        // Every frame is acked, quietly: 30 log lines a second would bury everything else.
        write(AaChannel.VIDEO, false, AaAv.MEDIA_ACK, AaAv.mediaAck(videoSession, 1));
    }

    private void finishVideo() {
        if (videoStarted) {
            videoStarted = false;
            event("video ended after " + videoFrames + " frame(s), " + videoBytes + "B");
            if (videoSink != null) {
                videoSink.onVideoStop();
            }
        }
    }

    private void sendControl(int messageId, byte[] payload) throws IOException {
        send(AaChannel.CONTROL, true, messageId, payload);
    }

    /** Writes one message on the wire, splitting it into frames when it is large, and logs it. */
    public void send(int channel, boolean control, int messageId, byte[] payload) throws IOException {
        write(channel, control, messageId, payload);
        event("sent " + AaChannel.name(channel) + "/"
                + (control ? AaMessageType.name(messageId)
                        : channel == AaChannel.VIDEO ? AaAv.name(messageId)
                        : String.format("0x%04X", messageId))
                + " (" + payload.length + "B)");
    }

    private void write(int channel, boolean control, int messageId, byte[] payload)
            throws IOException {
        if (payload.length + 2 > MAX_MESSAGE) {
            throw new IOException("message too large: " + payload.length);
        }
        output.write(AaFrameCodec.encode(channel, control, messageId, payload));
        output.flush();
    }

    private void event(String message) {
        if (sink != null) {
            sink.onEvent(message);
        }
    }
}
