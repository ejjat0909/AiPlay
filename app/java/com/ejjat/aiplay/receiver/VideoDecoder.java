package com.ejjat.aiplay.receiver;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;

import com.ejjat.aiplay.core.aa.H264;
import com.ejjat.aiplay.device.Diag;

import java.nio.ByteBuffer;

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
    private static final long INPUT_TIMEOUT_US = 20000L;
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
    }

    /** The phone stopped streaming: free the codec but keep the surface. */
    void stop() {
        synchronized (lock) {
            if (fed > 0 || dropped > 0) {
                Diag.log("video", "decoder stopped: " + fed + " fed, " + dropped + " dropped");
            }
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
            if (surface == null || failed) {
                dropped++;
                return;
            }
            if (codec == null && !startCodec()) {
                dropped++;
                return;
            }
            if (waitForKeyframe) {
                if (!H264.hasIdr(mask)) {
                    dropped++;
                    return;
                }
                waitForKeyframe = false;
            }
            long ts = timestampMicros >= 0 ? timestampMicros : synthetic * SYNTHETIC_FRAME_US;
            synthetic++;
            try {
                if (!queue(data, offset, length, ts, 0)) {
                    dropped++;
                    waitForKeyframe = true; // a dropped P frame corrupts everything after it
                } else {
                    fed++;
                }
                drain();
            } catch (RuntimeException error) {
                fail("decoder error: " + error);
            }
        }
    }

    private boolean startCodec() {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT);
            if (Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            MediaCodec created = MediaCodec.createDecoderByType(MIME);
            created.configure(format, surface, null, 0);
            created.start();
            codec = created;
            Diag.log("video", "decoder started: " + created.getName());
            if (config != null && config.length > 0) {
                queue(config, 0, config.length, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
            }
            return true;
        } catch (Exception error) {
            fail("could not start an H.264 decoder: " + error);
            return false;
        }
    }

    private boolean queue(byte[] data, int offset, int length, long ts, int flags) {
        int index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US);
        if (index < 0) {
            return false;
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
