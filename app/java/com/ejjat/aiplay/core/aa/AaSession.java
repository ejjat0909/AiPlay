package com.ejjat.aiplay.core.aa;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * One Android Auto session over an established byte stream.
 *
 * <p>Pure Java on purpose: the listener in the Android layer only accepts sockets and hands the
 * streams here, which means the whole exchange can be exercised by a desktop JVM test.</p>
 *
 * <p>Order on the wire (aasdk + OpenAuto, head-unit role): the head unit sends the version request;
 * the phone answers; the head unit, the TLS <em>client</em>, then runs a TLS 1.2 handshake carried
 * in {@code SSL_HANDSHAKE} messages and sends auth-complete; from then on every frame is encrypted
 * except those three message types. See {@link AaCryptor}.</p>
 *
 * <p>Without a cryptor the session runs in plaintext mode (auth-complete follows the version
 * response directly). That exists so channel logic can be unit-tested without TLS; the receiver
 * always passes a cryptor.</p>
 *
 * <p>Channels served: video; media, speech and system audio; input (touch and keys, which flow from
 * the head unit to the phone); sensor (driving status, night mode). Plus audio and navigation focus on
 * the control channel. A channel that is not served is refused with FAIL and logged, never silently
 * dropped.</p>
 *
 * <p><b>Threads.</b> {@link #pump} and everything it triggers run on one session thread. The
 * {@code send*}/{@code push*} methods may be called from any thread: all writes go through one lock,
 * which also keeps TLS records in the order they were encrypted.</p>
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

    /** Receives the phone's audio. Called on the session thread; must not block. */
    public interface AudioSink {
        /** A stream started on {@code channel} ({@link AaChannel#MEDIA_AUDIO} etc). */
        void onAudioStart(int channel, int session);

        /** One chunk of 16-bit little-endian PCM in the channel's {@link AaAudio.Format}. */
        void onAudioData(int channel, long timestampMicros, byte[] data, int offset, int length);

        void onAudioStop(int channel);

        /** The phone asked for audio focus ({@link AaAudio} types); {@code grantedState} was sent. */
        void onAudioFocus(int requestedType, int grantedState);
    }

    /** What the head unit reports to the phone's sensor requests. */
    public interface SensorSource {
        boolean isNight();

        /** {@code DrivingStatus} flags; {@link AaSensor#DRIVING_UNRESTRICTED} when not tracked. */
        int drivingStatus();
    }

    /**
     * Frames the phone may send before it has to wait for an ack. 1 is the most conservative value
     * and what OpenAuto answers; every media frame is acked.
     */
    public static final int MAX_UNACKED = 1;
    /** Kept for callers written before audio existed. */
    public static final int VIDEO_MAX_UNACKED = MAX_UNACKED;

    /**
     * The head unit pings the phone this often (OpenAuto: {@code Pinger(ioService, 5000)}). The phone
     * is declared gone when two pings are unanswered, not when no data has arrived: a phone showing
     * a still map may send nothing for many seconds and is still there.
     */
    public static final long PING_INTERVAL_MS = 5000L;
    /** How long TLS and auth may take before a silent connection is abandoned. */
    public static final long HANDSHAKE_TIMEOUT_MS = 15000L;

    private static final int MAX_MESSAGE = AaFrameCodec.MAX_MESSAGE_PAYLOAD;
    private static final int LOG_EVERY_FRAMES = 150;

    /** State of one audio/video channel. */
    private static final class AvState {
        boolean open;
        boolean started;
        int session;
        long frames;
        long bytes;
    }

    private final InputStream input;
    private final OutputStream output;
    private final Sink sink;
    private final AaFrameCodec.Parser parser = new AaFrameCodec.Parser();
    private final AaControl.ServiceDiscoveryConfig discovery;
    private final AaCryptor cryptor;
    private final Object writeLock = new Object();
    private final AvState[] av = new AvState[9];

    private VideoSink videoSink;
    private AudioSink audioSink;
    private SensorSource sensorSource;
    private boolean versionSent;
    private volatile boolean encrypting;
    private volatile boolean inputOpen;
    private volatile boolean inputBound;
    private volatile boolean sensorOpen;
    private volatile boolean nightSubscribed;
    private volatile boolean drivingSubscribed;

    private long pingIntervalMs = PING_INTERVAL_MS;
    private long startedAt = Long.MIN_VALUE;
    private long lastPingAt = Long.MIN_VALUE;
    private int pingsSent;
    private int pongsReceived;

    /** Plaintext session (tests): no TLS handshake. */
    public AaSession(InputStream input, OutputStream output, Sink sink,
                     AaControl.ServiceDiscoveryConfig discovery) {
        this(input, output, sink, discovery, null);
    }

    public AaSession(InputStream input, OutputStream output, Sink sink,
                     AaControl.ServiceDiscoveryConfig discovery, AaCryptor cryptor) {
        this.input = input;
        this.output = output;
        this.sink = sink;
        this.discovery = discovery;
        this.cryptor = cryptor;
        for (int i = 0; i < av.length; i++) {
            av[i] = new AvState();
        }
    }

    /** True once the TLS handshake finished and outgoing frames are being encrypted. */
    public boolean isEncrypted() {
        return encrypting;
    }

    /** For tests: how often the head unit pings. */
    public void setPingIntervalMillis(long millis) {
        this.pingIntervalMs = millis;
    }

    public int pingsSent() {
        return pingsSent;
    }

    public int pongsReceived() {
        return pongsReceived;
    }

    /**
     * Keep-alive, called on the session thread every time a read comes back (with data or after a
     * short timeout). Pings the phone when the interval has passed and reports whether it is still
     * there.
     *
     * @return false when the connection should be dropped: two pings went unanswered, or the TLS
     *         handshake never finished
     */
    public boolean tick(long nowMillis) throws IOException {
        if (startedAt == Long.MIN_VALUE) {
            startedAt = nowMillis;
        }
        boolean established = cryptor == null || encrypting;
        if (!established) {
            if (nowMillis - startedAt > HANDSHAKE_TIMEOUT_MS) {
                event("no TLS handshake after " + HANDSHAKE_TIMEOUT_MS / 1000 + " s, giving up");
                return false;
            }
            return true;
        }
        if (lastPingAt == Long.MIN_VALUE) {
            lastPingAt = nowMillis;     // the first ping is one interval after the session is up
            return true;
        }
        if (nowMillis - lastPingAt < pingIntervalMs) {
            return true;
        }
        if (pingsSent - pongsReceived > 1) {
            event("the phone stopped answering pings (" + pingsSent + " sent, " + pongsReceived
                    + " answered)");
            return false;
        }
        lastPingAt = nowMillis;
        pingsSent++;
        write(AaChannel.CONTROL, false, AaMessageType.PING_REQUEST,
                AaControl.pingRequest(nowMillis * 1000L));       // quiet: one line per 5 s is noise
        return true;
    }

    public void setVideoSink(VideoSink videoSink) {
        this.videoSink = videoSink;
    }

    public void setAudioSink(AudioSink audioSink) {
        this.audioSink = audioSink;
    }

    public void setSensorSource(SensorSource sensorSource) {
        this.sensorSource = sensorSource;
    }

    public long videoFrames() {
        return av[AaChannel.VIDEO].frames;
    }

    public boolean isVideoStarted() {
        return av[AaChannel.VIDEO].started;
    }

    public long audioFrames(int channel) {
        return av[channel].frames;
    }

    public boolean isAudioStarted(int channel) {
        return av[channel].started;
    }

    /** True once the phone's binding request was accepted: touch and key events may be sent. */
    public boolean isInputBound() {
        return inputBound;
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
            finishAll();
            return -1;
        }
        final int[] handled = {0};
        parser.feed(buffer, 0, read, new AaFrameCodec.Listener() {
            @Override
            public void onMessage(AaFrame frame) throws IOException, AaProtocolException {
                handled[0]++;
                handle(frame);
            }
        });
        return handled[0];
    }

    /** Processes one message and writes any replies. Exposed for tests. */
    public void handle(AaFrame frame) throws IOException, AaProtocolException {
        if (frame.channel != AaChannel.CONTROL) {
            handleChannel(frame);
            return;
        }
        switch (frame.messageId) {
            case AaMessageType.VERSION_RESPONSE: {
                AaControl.VersionResponse response = AaControl.parseVersionResponse(frame.payload);
                event("peer " + response);
                if (!response.matches()) {
                    throw new AaProtocolException("phone reports a protocol version mismatch");
                }
                if (cryptor == null) {
                    sendControl(AaMessageType.AUTH_COMPLETE,
                            AaControl.authComplete(AaControl.STATUS_OK));
                } else {
                    byte[] hello = cryptor.beginHandshake();
                    event("starting TLS (head unit is the client)");
                    sendControl(AaMessageType.SSL_HANDSHAKE, hello);
                }
                break;
            }
            case AaMessageType.SSL_HANDSHAKE: {
                if (cryptor == null) {
                    event("SSL_HANDSHAKE in plaintext mode — ignored");
                    break;
                }
                byte[] reply = cryptor.handshake(frame.payload, 0, frame.payload.length);
                if (reply.length > 0) {
                    sendControl(AaMessageType.SSL_HANDSHAKE, reply);
                }
                if (cryptor.isHandshakeDone() && !encrypting) {
                    parser.setCipher(cryptor);
                    event("TLS up: " + cryptor.protocol() + " " + cryptor.cipherSuite());
                    // auth-complete is still sent in the clear; everything after it is encrypted.
                    sendControl(AaMessageType.AUTH_COMPLETE,
                            AaControl.authComplete(AaControl.STATUS_OK));
                    encrypting = true;
                }
                break;
            }
            case AaMessageType.SERVICE_DISCOVERY_REQUEST: {
                AaControl.ServiceDiscoveryRequest request =
                        AaControl.parseServiceDiscoveryRequest(frame.payload);
                event("service discovery from " + request.deviceBrand + " " + request.deviceName
                        + (frame.encrypted ? " (encrypted)" : ""));
                sendControl(AaMessageType.SERVICE_DISCOVERY_RESPONSE,
                        AaControl.serviceDiscoveryResponse(discovery));
                break;
            }
            case AaMessageType.AUDIO_FOCUS_REQUEST: {
                int type = AaAudio.parseFocusType(frame.payload);
                int state = AaAudio.stateFor(type);
                event("audio focus request, type " + type + " -> state " + state);
                sendControl(AaMessageType.AUDIO_FOCUS_RESPONSE, AaAudio.focusResponse(state));
                if (audioSink != null) {
                    audioSink.onAudioFocus(type, state);
                }
                break;
            }
            case AaMessageType.NAVIGATION_FOCUS_REQUEST: {
                event("navigation focus request");
                sendControl(AaMessageType.NAVIGATION_FOCUS_RESPONSE,
                        AaAudio.navigationFocusResponse());
                break;
            }
            case AaMessageType.PING_RESPONSE: {
                pongsReceived++;          // the answer to tick()'s ping; not worth a log line
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
                finishAll();
                sendControl(AaMessageType.SHUTDOWN_RESPONSE, new byte[0]);
                break;
            }
            default: {
                event("message " + frame + " — not handled");
                break;
            }
        }
    }

    // ---- channels -----------------------------------------------------------------------------

    private void handleChannel(AaFrame frame) throws IOException {
        int channel = frame.channel;
        // aasdk dispatches on the message id: channel open is a CONTROL-typed message on the
        // channel itself, everything else is channel-specific.
        if (frame.messageId == AaMessageType.CHANNEL_OPEN_REQUEST && frame.control) {
            openChannel(frame);
        } else if (AaChannel.isAv(channel)) {
            if (av[channel].open) {
                handleAv(frame);
            } else {
                event(AaChannel.name(channel) + " message " + frame
                        + " before the channel was opened — ignored");
            }
        } else if (channel == AaChannel.INPUT && inputOpen) {
            handleInput(frame);
        } else if (channel == AaChannel.SENSOR && sensorOpen) {
            handleSensor(frame);
        } else {
            event("message " + frame + " on " + AaChannel.name(channel)
                    + " — channel not open or not implemented");
        }
    }

    private boolean served(int channel) {
        switch (channel) {
            case AaChannel.VIDEO: return discovery.advertiseVideo;
            case AaChannel.MEDIA_AUDIO: return discovery.advertiseMediaAudio;
            case AaChannel.SPEECH_AUDIO: return discovery.advertiseSpeechAudio;
            case AaChannel.SYSTEM_AUDIO: return discovery.advertiseSystemAudio;
            case AaChannel.INPUT: return discovery.advertiseInput;
            case AaChannel.SENSOR: return discovery.advertiseSensor;
            default: return false;
        }
    }

    /**
     * A phone opens a channel by sending {@code ChannelOpenRequest} <em>on that channel</em>
     * (CONTROL-typed); the answer goes back on the same channel the same way.
     */
    private void openChannel(AaFrame frame) throws IOException {
        int target = frame.channel;
        boolean accept = served(target);
        event("channel open request for " + AaChannel.name(target)
                + (accept ? " — accepted" : " — refused (not implemented)"));
        if (accept) {
            if (AaChannel.isAv(target)) {
                av[target].open = true;
            } else if (target == AaChannel.INPUT) {
                inputOpen = true;
            } else if (target == AaChannel.SENSOR) {
                sensorOpen = true;
            }
        }
        send(target, true, AaMessageType.CHANNEL_OPEN_RESPONSE,
                AaControl.channelOpenResponse(accept ? AaControl.STATUS_OK : AaControl.STATUS_FAIL));
    }

    // ---- audio / video ------------------------------------------------------------------------

    private void handleAv(AaFrame frame) throws IOException {
        int channel = frame.channel;
        AvState state = av[channel];
        String label = AaChannel.name(channel);
        switch (frame.messageId) {
            case AaAv.SETUP_REQUEST: {
                int index = AaAv.parseSetupRequest(frame.payload);
                boolean ok = index == 0;
                event(label + " setup request, config " + index + (ok ? "" : " — refused"));
                if (ok && channel == AaChannel.VIDEO && videoSink != null) {
                    videoSink.onVideoSetup(index);
                }
                send(channel, false, AaAv.SETUP_RESPONSE,
                        AaAv.setupResponse(ok ? AaAv.SETUP_OK : AaAv.SETUP_FAIL, MAX_UNACKED, 0));
                break;
            }
            case AaAv.VIDEO_FOCUS_REQUEST: {
                if (channel != AaChannel.VIDEO) {
                    event(label + " message " + frame + " — not handled");
                    break;
                }
                boolean focused = AaAv.parseVideoFocusMode(frame.payload) != AaAv.FOCUS_UNFOCUSED;
                event("video focus request: " + (focused ? "focused" : "unfocused"));
                if (videoSink != null) {
                    videoSink.onVideoFocus(focused);
                }
                send(channel, false, AaAv.VIDEO_FOCUS_INDICATION,
                        AaAv.videoFocusIndication(
                                focused ? AaAv.FOCUS_FOCUSED : AaAv.FOCUS_UNFOCUSED, false));
                break;
            }
            case AaAv.START_INDICATION: {
                int[] start = AaAv.parseStartIndication(frame.payload);
                state.session = start[0];
                state.started = true;
                event(label + " start: session " + start[0] + ", config " + start[1]);
                if (channel == AaChannel.VIDEO) {
                    if (videoSink != null) {
                        videoSink.onVideoStart(start[0], start[1]);
                    }
                } else if (audioSink != null) {
                    audioSink.onAudioStart(channel, start[0]);
                }
                break;
            }
            case AaAv.STOP_INDICATION: {
                event(label + " stop");
                finish(channel);
                break;
            }
            case AaAv.MEDIA_WITH_TIMESTAMP:
            case AaAv.MEDIA: {
                handleMedia(frame, state);
                break;
            }
            default: {
                event(label + " message " + frame + " — not handled");
                break;
            }
        }
    }

    private void handleMedia(AaFrame frame, AvState state) throws IOException {
        int channel = frame.channel;
        long timestamp = AaAv.mediaTimestamp(frame.messageId, frame.payload);
        int offset = AaAv.mediaDataOffset(frame.messageId, frame.payload);
        int length = frame.payload.length - offset;
        state.frames++;
        state.bytes += length;
        if (state.frames == 1 || state.frames % LOG_EVERY_FRAMES == 0) {
            event(AaChannel.name(channel) + " frame " + state.frames + " (" + length + "B"
                    + (timestamp >= 0 ? ", ts " + timestamp + "us" : "") + "), "
                    + state.bytes + "B total");
        }
        if (channel == AaChannel.VIDEO) {
            if (videoSink != null) {
                videoSink.onVideoFrame(timestamp, frame.payload, offset, length);
            }
        } else if (audioSink != null) {
            audioSink.onAudioData(channel, timestamp, frame.payload, offset, length);
        }
        // Every message is acked, quietly: 30 log lines a second would bury everything else.
        write(channel, false, AaAv.MEDIA_ACK, AaAv.mediaAck(state.session, 1));
    }

    private void finish(int channel) {
        AvState state = av[channel];
        if (!state.started) {
            return;
        }
        state.started = false;
        event(AaChannel.name(channel) + " ended after " + state.frames + " frame(s), "
                + state.bytes + "B");
        if (channel == AaChannel.VIDEO) {
            if (videoSink != null) {
                videoSink.onVideoStop();
            }
        } else if (audioSink != null) {
            audioSink.onAudioStop(channel);
        }
    }

    private void finishAll() {
        for (int channel = 0; channel < av.length; channel++) {
            if (AaChannel.isAv(channel)) {
                finish(channel);
            }
        }
        inputBound = false;
        nightSubscribed = false;
        drivingSubscribed = false;
    }

    // ---- input: head unit -> phone ------------------------------------------------------------

    private void handleInput(AaFrame frame) throws IOException {
        if (frame.messageId != AaInput.BINDING_REQUEST) {
            event("input message " + frame + " — not handled");
            return;
        }
        int[] codes = AaInput.parseBindingRequest(frame.payload);
        boolean ok = AaInput.bindingAcceptable(codes);
        inputBound = ok;
        event("input binding request, " + codes.length + " key(s) -> " + (ok ? "OK" : "FAIL"));
        send(AaChannel.INPUT, false, AaInput.BINDING_RESPONSE, AaInput.bindingResponse(ok));
    }

    /**
     * Sends one touch indication to the phone. Returns false (and sends nothing) until the phone
     * has bound the input channel. Safe to call from any thread.
     */
    public boolean sendTouch(long timestampMicros, int action, int actionIndex, int[] xs, int[] ys,
                             int[] pointerIds) throws IOException {
        if (!inputBound) {
            return false;
        }
        write(AaChannel.INPUT, false, AaInput.INPUT_EVENT_INDICATION,
                AaInput.touchEvent(timestampMicros, action, actionIndex, xs, ys, pointerIds));
        return true;
    }

    /** Sends one key press or release ({@link AaInput#SUPPORTED_KEYS}). Any thread. */
    public boolean sendButton(long timestampMicros, int scanCode, boolean pressed)
            throws IOException {
        if (!inputBound || !AaInput.isSupportedKey(scanCode)) {
            return false;
        }
        write(AaChannel.INPUT, false, AaInput.INPUT_EVENT_INDICATION,
                AaInput.buttonEvent(timestampMicros, scanCode, pressed));
        return true;
    }

    // ---- sensor -------------------------------------------------------------------------------

    private void handleSensor(AaFrame frame) throws IOException {
        if (frame.messageId != AaSensor.START_REQUEST) {
            event("sensor message " + frame + " — not handled");
            return;
        }
        int type = AaSensor.parseStartRequest(frame.payload);
        boolean ok = type == AaSensor.TYPE_DRIVING_STATUS || type == AaSensor.TYPE_NIGHT_DATA;
        event("sensor start request, type " + type + (ok ? "" : " — not offered"));
        send(AaChannel.SENSOR, false, AaSensor.START_RESPONSE, AaSensor.startResponse(ok));
        if (type == AaSensor.TYPE_DRIVING_STATUS) {
            drivingSubscribed = true;
            sendDrivingStatus();
        } else if (type == AaSensor.TYPE_NIGHT_DATA) {
            nightSubscribed = true;
            sendNightMode(sensorSource != null && sensorSource.isNight());
        }
    }

    private void sendDrivingStatus() throws IOException {
        int status = sensorSource == null ? AaSensor.DRIVING_UNRESTRICTED
                : sensorSource.drivingStatus();
        send(AaChannel.SENSOR, false, AaSensor.EVENT_INDICATION, AaSensor.drivingStatus(status));
    }

    private void sendNightMode(boolean night) throws IOException {
        send(AaChannel.SENSOR, false, AaSensor.EVENT_INDICATION, AaSensor.nightMode(night));
    }

    /** Tells the phone the head unit switched day/night, if it asked for night data. Any thread. */
    public boolean pushNightMode(boolean night) throws IOException {
        if (!nightSubscribed) {
            return false;
        }
        sendNightMode(night);
        return true;
    }

    // ---- writing ------------------------------------------------------------------------------

    /** A message on channel 0. aasdk sends these as SPECIFIC: the CONTROL flag stays clear. */
    private void sendControl(int messageId, byte[] payload) throws IOException {
        send(AaChannel.CONTROL, false, messageId, payload);
    }

    /** Writes one message on the wire, splitting it into frames when it is large, and logs it. */
    public void send(int channel, boolean control, int messageId, byte[] payload) throws IOException {
        write(channel, control, messageId, payload);
        boolean controlId = channel == AaChannel.CONTROL || control;
        event("sent " + AaChannel.name(channel) + "/"
                + AaChannel.messageName(channel, controlId, messageId)
                + " (" + payload.length + "B)");
    }

    /** True for the three messages aasdk sends in the clear even after TLS is up. */
    private static boolean alwaysPlain(int channel, int messageId) {
        return channel == AaChannel.CONTROL
                && (messageId == AaMessageType.VERSION_REQUEST
                || messageId == AaMessageType.SSL_HANDSHAKE
                || messageId == AaMessageType.AUTH_COMPLETE);
    }

    private void write(int channel, boolean control, int messageId, byte[] payload)
            throws IOException {
        if (payload.length + 2 > MAX_MESSAGE) {
            throw new IOException("message too large: " + payload.length);
        }
        // Encrypt and write under one lock: TLS records must reach the phone in the order they
        // were produced, and the engine is shared with the session thread's decryption.
        synchronized (writeLock) {
            boolean encrypt = encrypting && cryptor != null && !alwaysPlain(channel, messageId);
            output.write(AaFrameCodec.encode(channel, control, messageId, payload,
                    encrypt ? cryptor : null));
            output.flush();
        }
    }

    private void event(String message) {
        if (sink != null) {
            sink.onEvent(message);
        }
    }
}
