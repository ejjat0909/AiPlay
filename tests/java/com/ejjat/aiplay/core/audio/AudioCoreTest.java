package com.ejjat.aiplay.core.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.ejjat.aiplay.core.aa.AaChannel;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public class AudioCoreTest {

    @Test
    public void putCopiesAndTakeReturnsInOrder() throws Exception {
        PcmQueue queue = new PcmQueue(1000);
        byte[] source = {1, 2, 3, 4};
        queue.put(source, 1, 2);            // copies bytes {2,3}
        source[1] = 99;                     // later changes must not leak in
        queue.put(new byte[]{9}, 0, 1);
        assertArrayEquals(new byte[]{2, 3}, queue.take(10));
        assertArrayEquals(new byte[]{9}, queue.take(10));
        assertEquals(0, queue.queuedBytes());
    }

    @Test
    public void overflowDropsTheOldestAudioAndKeepsTheNewest() throws Exception {
        PcmQueue queue = new PcmQueue(10);
        queue.put(new byte[]{1, 1, 1, 1}, 0, 4);
        queue.put(new byte[]{2, 2, 2, 2}, 0, 4);
        queue.put(new byte[]{3, 3, 3, 3}, 0, 4);       // 12 > 10: the first chunk goes
        assertEquals(4, queue.droppedBytes());
        assertEquals(8, queue.queuedBytes());
        assertEquals(2, queue.take(10)[0]);
        assertEquals(3, queue.take(10)[0]);
    }

    @Test
    public void aSingleOversizedChunkIsKeptRatherThanDroppedToNothing() throws Exception {
        PcmQueue queue = new PcmQueue(4);
        queue.put(new byte[100], 0, 100);
        assertEquals(100, queue.queuedBytes());
        assertEquals(100, queue.take(10).length);
    }

    @Test
    public void takeTimesOutWithNullAndClearDiscards() throws Exception {
        PcmQueue queue = new PcmQueue(100);
        long started = System.nanoTime();
        assertNull(queue.take(80));
        assertTrue((System.nanoTime() - started) / 1000000L >= 60);
        queue.put(new byte[]{1}, 0, 1);
        queue.clear();
        assertNull(queue.take(10));
    }

    @Test
    public void takeBlocksUntilAnotherThreadPuts() throws Exception {
        final PcmQueue queue = new PcmQueue(100);
        final AtomicReference<byte[]> got = new AtomicReference<byte[]>();
        Thread consumer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    got.set(queue.take(3000));
                } catch (InterruptedException ignored) {
                    // test ends
                }
            }
        });
        consumer.start();
        Thread.sleep(100);
        queue.put(new byte[]{7, 7}, 0, 2);
        consumer.join(3000);
        assertArrayEquals(new byte[]{7, 7}, got.get());
    }

    @Test
    public void closeWakesAWaitingConsumerAndRejectsFurtherAudio() throws Exception {
        final PcmQueue queue = new PcmQueue(100);
        final AtomicReference<String> result = new AtomicReference<String>("waiting");
        Thread consumer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    result.set(queue.take(5000) == null ? "null" : "data");
                } catch (InterruptedException ignored) {
                    result.set("interrupted");
                }
            }
        });
        consumer.start();
        Thread.sleep(100);
        queue.close();
        consumer.join(3000);
        assertEquals("null", result.get());
        queue.put(new byte[]{1}, 0, 1);
        assertEquals(0, queue.queuedBytes());
    }

    @Test
    public void musicIsDuckedOnlyWhileSpeechOrSystemSoundsPlay() {
        AudioMixPolicy policy = new AudioMixPolicy();
        assertEquals(1f, policy.volumeFor(AaChannel.MEDIA_AUDIO), 0f);

        policy.setActive(AaChannel.SPEECH_AUDIO, true);
        assertEquals(AudioMixPolicy.DUCKED, policy.volumeFor(AaChannel.MEDIA_AUDIO), 0f);
        assertEquals("speech itself is never ducked", 1f, policy.volumeFor(AaChannel.SPEECH_AUDIO), 0f);
        assertEquals(1f, policy.volumeFor(AaChannel.SYSTEM_AUDIO), 0f);

        policy.setActive(AaChannel.SPEECH_AUDIO, false);
        assertEquals(1f, policy.volumeFor(AaChannel.MEDIA_AUDIO), 0f);

        policy.setActive(AaChannel.SYSTEM_AUDIO, true);
        assertEquals(AudioMixPolicy.DUCKED, policy.volumeFor(AaChannel.MEDIA_AUDIO), 0f);
        policy.reset();
        assertEquals(1f, policy.volumeFor(AaChannel.MEDIA_AUDIO), 0f);
    }

    @Test
    public void overlappingSpeechAndSystemKeepMusicDuckedUntilBothEnd() {
        AudioMixPolicy policy = new AudioMixPolicy();
        policy.setActive(AaChannel.SPEECH_AUDIO, true);
        policy.setActive(AaChannel.SYSTEM_AUDIO, true);
        policy.setActive(AaChannel.SPEECH_AUDIO, false);
        assertEquals(AudioMixPolicy.DUCKED, policy.volumeFor(AaChannel.MEDIA_AUDIO), 0f);
        policy.setActive(AaChannel.SYSTEM_AUDIO, false);
        assertEquals(1f, policy.volumeFor(AaChannel.MEDIA_AUDIO), 0f);
    }

    @Test
    public void finishLetsTheQueuedTailPlayThenEnds() throws Exception {
        PcmQueue queue = new PcmQueue(1000);
        queue.put(new byte[]{1, 1}, 0, 2);
        queue.put(new byte[]{2, 2}, 0, 2);
        queue.finish();
        queue.put(new byte[]{3}, 0, 1);                  // too late: the stream has ended
        assertEquals(1, queue.take(10)[0]);
        assertEquals(2, queue.take(10)[0]);
        assertNull("drained and finished", queue.take(10));
    }

    @Test
    public void closeThrowsTheQueuedAudioAway() throws Exception {
        PcmQueue queue = new PcmQueue(1000);
        queue.put(new byte[]{1, 1}, 0, 2);
        queue.close();
        assertNull(queue.take(10));
    }
}
