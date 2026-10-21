package com.ejjat.aiplay.receiver;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.ejjat.aiplay.core.aa.AaAudio;
import com.ejjat.aiplay.core.aa.AaChannel;
import com.ejjat.aiplay.core.audio.PcmQueue;
import com.ejjat.aiplay.device.Diag;

/**
 * Plays one Android Auto audio stream through an {@link AudioTrack}.
 *
 * <p>The session thread only {@link #write}s into a bounded {@link PcmQueue} and returns; this
 * class's own thread feeds the track, which may block. On {@link #stop} the queue is drained and the
 * track is allowed to finish what it has buffered, so the end of a navigation prompt is not cut
 * off; {@code onFinished} then tells the owner (the music can stop being ducked).</p>
 */
final class AudioPlayer {

    interface Listener {
        /** The stream has fully played out and the track is released. */
        void onFinished(int channel);
    }

    /** Roughly this much audio may wait for the device before the oldest is discarded. */
    private static final double MAX_QUEUE_SECONDS = 1.5;
    private static final long TAIL_WAIT_MS = 2000L;

    private final int channel;
    private final AaAudio.Format format;
    private final PcmQueue queue;
    private final Listener listener;
    private final String label;

    private AudioTrack track;
    private Thread thread;
    private volatile boolean stopping;
    private volatile long framesWritten;
    private volatile float lastVolume = -1f;

    AudioPlayer(int channel, AaAudio.Format format, Listener listener) {
        this.channel = channel;
        this.format = format;
        this.listener = listener;
        this.label = AaChannel.name(channel);
        this.queue = new PcmQueue((int) (format.bytesPerSecond() * MAX_QUEUE_SECONDS));
    }

    /** Creates the track and starts playing. Returns false (and logs why) if the device refuses. */
    boolean start(float volume) {
        try {
            int mask = format.channels == 1 ? AudioFormat.CHANNEL_OUT_MONO
                    : AudioFormat.CHANNEL_OUT_STEREO;
            int minimum = AudioTrack.getMinBufferSize(format.sampleRate, mask,
                    AudioFormat.ENCODING_PCM_16BIT);
            int buffer = Math.max(minimum * 2, format.bytesPerSecond() / 5);   // >= 200 ms
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(usage())
                            .setContentType(channel == AaChannel.SPEECH_AUDIO
                                    ? AudioAttributes.CONTENT_TYPE_SPEECH
                                    : AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(format.sampleRate)
                            .setChannelMask(mask)
                            .build())
                    .setBufferSizeInBytes(buffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Diag.log("audio", label + ": the audio device did not initialise");
                track.release();
                track = null;
                return false;
            }
            track.setVolume(volume);
            lastVolume = volume;
            track.play();
        } catch (RuntimeException error) {
            Diag.log("audio", label + ": could not start playback", error);
            if (track != null) {
                track.release();
                track = null;
            }
            return false;
        }
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                pump();
            }
        }, "aiplay-audio-" + label);
        thread.start();
        Diag.log("audio", label + ": playing " + format.sampleRate + " Hz, " + format.channels
                + " ch, volume " + volume);
        return true;
    }

    private int usage() {
        switch (channel) {
            case AaChannel.SPEECH_AUDIO:
                return AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
            case AaChannel.SYSTEM_AUDIO:
                return AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
            default:
                return AudioAttributes.USAGE_MEDIA;
        }
    }

    /** Called on the session thread; never blocks. */
    void write(byte[] data, int offset, int length) {
        queue.put(data, offset, length);
    }

    void setVolume(float volume) {
        AudioTrack current = track;
        if (current != null && volume != lastVolume) {
            lastVolume = volume;
            Diag.log("audio", label + " volume " + volume + (volume < 1f ? " (ducked)" : ""));
            try {
                current.setVolume(volume);
            } catch (RuntimeException ignored) {
                // the track was released between the check and the call
            }
        }
    }

    /** Lets the queued audio finish, then releases the track. Returns immediately. */
    void stop() {
        stopping = true;
        queue.finish();     // wakes the thread; what is already queued still plays first
    }

    private void pump() {
        AudioTrack current = track;
        long played = 0;
        try {
            while (true) {
                byte[] chunk = queue.take(100);
                if (chunk == null) {
                    if (stopping) {
                        break;
                    }
                    continue;
                }
                int written = 0;
                while (written < chunk.length) {
                    int n = current.write(chunk, written, chunk.length - written);
                    if (n < 0) {
                        Diag.log("audio", label + ": write failed (" + n + ")");
                        return;
                    }
                    written += n;
                }
                framesWritten += chunk.length / (format.channels * 2);
            }
            // Let what the track already holds finish playing. The position must be read while the
            // track is still playing: Android reports 0 for a stopped track, so calling stop()
            // first both loses the number and makes this wait always run to its deadline.
            long deadline = System.currentTimeMillis() + TAIL_WAIT_MS;
            while ((current.getPlaybackHeadPosition() & 0xFFFFFFFFL) < framesWritten
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            played = current.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            current.stop();
        } catch (InterruptedException ignored) {
            // being torn down
        } catch (RuntimeException error) {
            Diag.log("audio", label + ": playback error", error);
        } finally {
            try {
                current.release();
            } catch (RuntimeException ignored) {
                // already released
            }
            Diag.log("audio", label + ": finished, " + framesWritten + " frames written, "
                    + played + " played, " + queue.droppedBytes() + " bytes dropped");
            if (listener != null) {
                listener.onFinished(channel);
            }
        }
    }
}
