package com.ejjat.aiplay.core.hw;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A short CPU load, used to see how the chip behaves when it is actually working.
 *
 * <p>Sampling frequency and temperature on an idle unit proves nothing about throttling, because an
 * idle governor clocks down anyway. This puts every core under load for a few seconds and records the
 * work done per second: a chip that throttles does less work in its last second than in its first, and
 * the temperature and clock readings (taken by the caller through {@link Sampler}) show why. It is a
 * <em>short</em> test: throttling that needs minutes of heat soak will not show here.</p>
 *
 * <p>The numbers are loop iterations, not a standard benchmark. Compare them between units or between
 * runs of the same unit, not against published scores.</p>
 */
public final class CpuBench {

    public static final int PHASE_SINGLE = 0;
    public static final int PHASE_ALL = 1;
    /** A warm-up whose numbers are thrown away (the runtime compiles the loop during it). */
    public static final int PHASE_WARMUP = 2;

    /** Called once per second of each phase, on the calling thread, so it may read files. */
    public interface Sampler {
        void onSecond(int phase, int second, long opsThisSecond);
    }

    private static final int BLOCK = 20000;

    private CpuBench() {
    }

    /** What a run measured. Operations are in thousands of loop iterations per second. */
    public static final class Result {
        public int threads;
        public long singleThreadKops;
        public long[] allCoreKopsPerSecond = new long[0];
        public int[] tempsC = new int[0];
        public int[] avgFreqKhz = new int[0];
        public ThrottleAnalysis.Verdict verdict;

        public long allCoreKopsAverage() {
            if (allCoreKopsPerSecond.length == 0) {
                return 0;
            }
            long sum = 0;
            for (long v : allCoreKopsPerSecond) {
                sum += v;
            }
            return sum / allCoreKopsPerSecond.length;
        }

        /** How much more the cores do together than one alone: close to {@code threads} is good. */
        public double scaling() {
            return singleThreadKops <= 0 ? 0 : (double) allCoreKopsAverage() / singleThreadKops;
        }
    }

    /**
     * @param threads       number of worker threads for the all-core phase (normally the core count)
     * @param warmSeconds   a one-thread warm-up that is not measured: without it the first phase
     *                      runs while the runtime is still compiling the loop, and the one-core
     *                      figure comes out far too low (3 threads then "did 6.5x one core")
     * @param singleSeconds length of the one-thread phase
     * @param allSeconds    length of the all-core phase
     */
    public static Result run(int threads, int warmSeconds, int singleSeconds, int allSeconds,
                             Sampler sampler) throws InterruptedException {
        Result result = new Result();
        result.threads = Math.max(1, threads);

        if (warmSeconds > 0) {
            phase(1, warmSeconds, sampler, PHASE_WARMUP);
        }

        long singleTotal = 0;
        long[] single = phase(1, singleSeconds, sampler, PHASE_SINGLE);
        for (long ops : single) {
            singleTotal += ops;
        }
        result.singleThreadKops = single.length == 0 ? 0 : singleTotal / single.length;

        result.allCoreKopsPerSecond = phase(result.threads, allSeconds, sampler, PHASE_ALL);
        return result;
    }

    private static volatile long sink;

    private static long[] phase(int workers, int seconds, Sampler sampler, int phase)
            throws InterruptedException {
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicLong counter = new AtomicLong();
        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            final long seed = 0x9E3779B97F4A7C15L * (i + 1);
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    long x = seed;
                    double f = 1.0;
                    while (!stop.get()) {
                        for (int n = 0; n < BLOCK; n++) {
                            x = x * 6364136223846793005L + 1442695040888963407L;
                            f = f * 1.0000001 + (x >>> 60) * 1e-9;
                        }
                        counter.addAndGet(BLOCK);
                    }
                    sink = x ^ Double.doubleToLongBits(f);
                }
            }, "aiplay-cpubench-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        long[] perSecond = new long[Math.max(0, seconds)];
        try {
            long last = 0;
            long start = System.nanoTime();
            for (int s = 0; s < seconds; s++) {
                long target = start + (s + 1) * 1000000000L;
                long wait = (target - System.nanoTime()) / 1000000L;
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                long now = counter.get();
                perSecond[s] = (now - last) / 1000L;      // thousands of iterations
                last = now;
                if (sampler != null) {
                    sampler.onSecond(phase, s, perSecond[s]);
                }
            }
        } finally {
            stop.set(true);
            for (Thread thread : threads) {
                thread.join(2000);
            }
        }
        return perSecond;
    }
}
