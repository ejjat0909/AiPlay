package com.ejjat.aiplay.receiver;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import com.ejjat.aiplay.core.aa.H264;
import com.ejjat.aiplay.device.Diag;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * H.264 (Annex-B) to {@link Surface} through {@link MediaCodec}.
 *
 * <p>Two things make a phone's stream awkward: the SPS/PPS arrive once, at the start, possibly
 * before the screen exists; and a decoder that joins mid-GOP paints garbage until the next IDR.
 * So the decoder remembers the last SPS/PPS, replays it into a freshly created codec, and drops
 * pictures until it has seen an IDR.</p>
 *
 * <p>Called from the session thread ({@link #feed}) and the UI thread ({@link #setSurface}); all
 * state is guarded by one lock. Decoding is synchronous on the session thread: input is queued
 * and ready output is released immediately, which keeps latency at one frame.</p>
 */
final class VideoDecoder {

    /** Notified on the session thread. */
    interface Listener {
        void onFirstFrame(int width, int height);

        void onFailure(String reason);
    }

    private static final String MIME = "video/avc";
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    /** One poll of the codec's input queue; between polls the output is drained. */
    private static final long INPUT_POLL_US = 10000L;
    /**
     * How long to wait for the codec to accept a picture before giving up on it. A slow decoder
     * (an emulator, a cheap head-unit SoC) must be allowed to fall behind: blocking here delays
     * the ack, which is exactly how the phone is told to slow down.
     */
    private static final long INPUT_WAIT_MS = 1500L;
    /** Pictures kept since the last keyframe while there is no surface or codec yet. */
    private static final int PENDING_MAX_PICTURES = 120;
    private static final int PENDING_MAX_BYTES = 8 * 1024 * 1024;
    private static final long SYNTHETIC_FRAME_US = 33333L;

    private final Object lock = new Object();
    private final Listener listener;

    private Surface surface;
    private MediaCodec codec;
    private byte[] config;
    private boolean waitForKeyframe = true;
    private boolean failed;
    private boolean firstFrameReported;
    private long synthetic;
    private long fed;
    private long dropped;
    private long stalls;
    private final List<Picture> pending = new ArrayList<Picture>();
    private int pendingBytes;

    /** A picture held back until a decoder can take it. */
    private static final class Picture {
        final long timestamp;
        final byte[] data;
        final boolean keyframe;

        Picture(long timestamp, byte[] data, boolean keyframe) {
            this.timestamp = timestamp;
            this.data = data;
            this.keyframe = keyframe;
        }
    }

    VideoDecoder(Listener listener) {
        this.listener = listener;
    }

    void setSurface(Surface newSurface) {
        synchronized (lock) {
            if (surface == newSurface) {
                return;
            }
            releaseCodec();
            surface = newSurface;
            failed = false;
            waitForKeyframe = true;
            firstFrameReported = false;
        }
        if (newSurface != null) {
            // The phone may already be streaming (the screen takes a moment to come up). Replay
            // what was buffered instead of waiting for the next keyframe, which a phone sends
            // rarely. Off the UI thread: starting a codec can take hundreds of milliseconds.
            Thread replay = new Thread(new Runnable() {
                @Override
                public void run() {
                    synchronized (lock) {
                        flushPending();
                    }
                }
            }, "aiplay-video-replay");
            replay.start();
        }
    }

    /** The phone stopped streaming: free the codec but keep the surface. */
    void stop() {
        synchronized (lock) {
            if (fed > 0 || dropped > 0) {
                Diag.log("video", "decoder stopped: " + fed + " fed, " + dropped + " dropped, "
                        + stalls + " input stall(s)");
            }
            pending.clear();
            pendingBytes = 0;
            stalls = 0;
            releaseCodec();
            config = null;
            waitForKeyframe = true;
            firstFrameReported = false;
            failed = false;
            synthetic = 0;
            fed = 0;
            dropped = 0;
        }
    }

    void feed(long timestampMicros, byte[] data, int offset, int length) {
        int mask = H264.nalTypeMask(data, offset, length);
        synchronized (lock) {
            if (H264.hasSps(mask)) {
                int prefix = H264.configPrefixLength(data, offset, length);
                config = new byte[prefix];
                System.arraycopy(data, offset, config, 0, prefix);
            }
            long ts = timestampMicros >= 0 ? timestampMicros : synthetic * SYNTHETIC_FRAME_US;
            synthetic++;
            byte[] copy = new byte[length];
            System.arraycopy(data, offset, copy, 0, length);
            Picture picture = new Picture(ts, copy, H264.hasIdr(mask));

            if (surface == null || failed) {
                hold(picture);
                return;
            }
            flushPending();
            submit(picture);
        }
    }

    /** Keeps pictures from the latest keyframe on, so a late decoder can still start cleanly. */
    private void hold(Picture picture) {
        if (picture.keyframe) {
            dropped += pending.size(); // superseded by this newer keyframe
            pending.clear();
            pendingBytes = 0;
        } else if (pending.isEmpty()) {
            dropped++; // nothing to decode it against
            return;
        }
        if (pending.size() >= PENDING_MAX_PICTURES
                || pendingBytes + picture.data.length > PENDING_MAX_BYTES) {
            dropped += pending.size() + 1;
            pending.clear();
            pendingBytes = 0;
            return;
        }
        pending.add(picture);
        pendingBytes += picture.data.length;
    }

    /** Feeds everything buffered, oldest first. Caller holds the lock. */
    private void flushPending() {
        if (surface == null || failed || pending.isEmpty()) {
            return;
        }
        List<Picture> batch = new ArrayList<Picture>(pending);
        pending.clear();
        pendingBytes = 0;
        Diag.log("video", "replaying " + batch.size() + " buffered picture(s)");
        for (int i = 0; i < batch.size(); i++) {
            submit(batch.get(i));
        }
    }

    /** Sends one picture to the codec, starting it if needed. Caller holds the lock. */
    private void submit(Picture picture) {
        if (codec == null && !startCodec()) {
            dropped++;
            return;
        }
        if (waitForKeyframe) {
            if (!picture.keyframe) {
                dropped++;
                return;
            }
            waitForKeyframe = false;
        }
        try {
            if (!queue(picture.data, 0, picture.data.length, picture.timestamp, 0)) {
                dropped++;
                waitForKeyframe = true; // a dropped P frame corrupts everything after it
                Diag.log("video", "decoder did not accept a picture within " + INPUT_WAIT_MS
                        + " ms; resyncing at the next keyframe");
            } else {
                fed++;
            }
            drain();
        } catch (RuntimeException error) {
            fail("decoder error: " + error);
        }
    }

    private boolean startCodec() {
        // KEY_LOW_LATENCY is only a hint, but some decoders (the emulator's goldfish codec, and
        // many cheap head-unit SoCs) reject it and then refuse to configure at all. Try it first,
        // then retry without: a decoder that works slowly beats one that does not start.
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            boolean lowLatency = attempt == 0 && Build.VERSION.SDK_INT >= 30;
            if (attempt == 1 && Build.VERSION.SDK_INT < 30) {
                break; // the first attempt already ran without the hint
            }
            MediaCodec created = null;
            try {
                MediaFormat format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT);
                if (lowLatency) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
                }
                created = MediaCodec.createDecoderByType(MIME);
                created.configure(format, surface, null, 0);
                created.start();
                codec = created;
                Diag.log("video", "decoder started: " + created.getName()
                        + (lowLatency ? " (low latency)" : ""));
                if (config != null && config.length > 0) {
                    queue(config, 0, config.length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
                }
                return true;
            } catch (Exception error) {
                lastError = error;
                codec = null;
                if (created != null) {
                    try {
                        created.release();
                    } catch (RuntimeException ignored) {
                        // nothing useful to do
                    }
                }
                Diag.log("video", "decoder start failed"
                        + (lowLatency ? " with the low-latency hint" : "") + ": " + error);
            }
        }
        fail("could not start an H.264 decoder: " + lastError);
        return false;
    }

    private boolean queue(byte[] data, int offset, int length, long ts, int flags) {
        long deadline = System.nanoTime() + INPUT_WAIT_MS * 1000000L;
        int index = codec.dequeueInputBuffer(INPUT_POLL_US);
        boolean stalled = false;
        while (index < 0) {
            if (!stalled) {
                stalled = true;
                stalls++;
            }
            // The codec is full because nobody has taken its output yet: take it, then retry.
            drain();
            if (System.nanoTime() > deadline) {
                return false;
            }
            index = codec.dequeueInputBuffer(INPUT_POLL_US);
        }
        ByteBuffer buffer = codec.getInputBuffer(index);
        if (buffer == null || buffer.capacity() < length) {
            codec.queueInputBuffer(index, 0, 0, ts, 0);
            return false;
        }
        buffer.clear();
        buffer.put(data, offset, length);
        codec.queueInputBuffer(index, 0, length, ts, flags);
        return true;
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index = codec.dequeueOutputBuffer(info, 0);
            if (index >= 0) {
                codec.releaseOutputBuffer(index, true);
                if (!firstFrameReported) {
                    firstFrameReported = true;
                    MediaFormat out = codec.getOutputFormat();
                    int w = out.containsKey(MediaFormat.KEY_WIDTH)
                            ? out.getInteger(MediaFormat.KEY_WIDTH) : WIDTH;
                    int h = out.containsKey(MediaFormat.KEY_HEIGHT)
                            ? out.getInteger(MediaFormat.KEY_HEIGHT) : HEIGHT;
                    Diag.log("video", "first picture rendered, " + w + "x" + h);
                    if (listener != null) {
                        listener.onFirstFrame(w, h);
                    }
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                Diag.log("video", "output format " + codec.getOutputFormat());
            } else {
                return; // INFO_TRY_AGAIN_LATER
            }
        }
    }

    private void fail(String reason) {
        Diag.log("video", reason);
        releaseCodec();
        failed = true;
        if (listener != null) {
            listener.onFailure(reason);
        }
    }

    private void releaseCodec() {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
            // already stopped or in an error state
        }
        try {
            codec.release();
        } catch (RuntimeException ignored) {
            // nothing useful to do
        }
        codec = null;
    }
}
