package com.ejjat.aiplay.core.audio;

import java.util.ArrayDeque;

/**
 * A bounded hand-off between the session thread (which receives audio) and the playback thread
 * (which blocks on the audio device).
 *
 * <p>Writing to an {@code AudioTrack} blocks when its buffer is full. Doing that on the session
 * thread would stall every other channel, video included, behind a slow audio device. So the session
 * thread only {@link #put}s here and returns at once, and a separate thread {@link #take}s. If the
 * playback side falls behind, the <em>oldest</em> audio is discarded once more than
 * {@code maxBytes} are waiting: a late prompt is worse than a clipped one, and latency must stay
 * bounded.</p>
 */
public final class PcmQueue {

    private final ArrayDeque<byte[]> chunks = new ArrayDeque<byte[]>();
    private final int maxBytes;
    private int queuedBytes;
    private long droppedBytes;
    private boolean closed;

    public PcmQueue(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** Copies {@code data} in. Never blocks. */
    public synchronized void put(byte[] data, int offset, int length) {
        if (closed || length <= 0) {
            return;
        }
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        chunks.addLast(copy);
        queuedBytes += length;
        while (queuedBytes > maxBytes && chunks.size() > 1) {
            byte[] old = chunks.removeFirst();
            queuedBytes -= old.length;
            droppedBytes += old.length;
        }
        notifyAll();
    }

    /**
     * Waits up to {@code timeoutMillis} for audio. Returns null on timeout or once closed and
     * drained.
     */
    public synchronized byte[] take(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1000000L;
        while (chunks.isEmpty()) {
            if (closed) {
                return null;
            }
            long remaining = (deadline - System.nanoTime()) / 1000000L;
            if (remaining <= 0) {
                return null;
            }
            wait(remaining);
        }
        byte[] next = chunks.removeFirst();
        queuedBytes -= next.length;
        return next;
    }

    /** Discards everything waiting (a stream stopped; stale audio must not play later). */
    public synchronized void clear() {
        chunks.clear();
        queuedBytes = 0;
    }

    /**
     * Ends the stream but keeps what is queued: no more audio is accepted, {@link #take} hands out
     * the remainder and then returns null. For a stream that ended normally, so its tail still plays.
     */
    public synchronized void finish() {
        closed = true;
        notifyAll();
    }

    /** Ends the stream and throws away what is queued. */
    public synchronized void close() {
        closed = true;
        chunks.clear();
        queuedBytes = 0;
        notifyAll();
    }

    public synchronized int queuedBytes() {
        return queuedBytes;
    }

    public synchronized long droppedBytes() {
        return droppedBytes;
    }
}
