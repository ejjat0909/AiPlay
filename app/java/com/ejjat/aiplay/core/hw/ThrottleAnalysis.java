package com.ejjat.aiplay.core.hw;

import java.util.Locale;

/**
 * Reads the all-core phase of {@link CpuBench} for signs of throttling.
 *
 * <p>The comparison is the average of the first two seconds against the last two. A drop of at least
 * {@link #DROP_PERCENT} is "suspected throttling"; it is called <em>likely thermal</em> only when the
 * temperature also rose by {@link #TEMP_RISE_C} or the clock fell by {@link #FREQ_DROP_PERCENT}. A drop
 * with no thermal or clock evidence could equally be another app taking the CPU, and says so.</p>
 */
public final class ThrottleAnalysis {

    public static final int DROP_PERCENT = 15;
    public static final int TEMP_RISE_C = 8;
    public static final int FREQ_DROP_PERCENT = 10;

    public enum Kind {
        TOO_SHORT, STEADY, RAMPED_UP, DROPPED_THERMAL, DROPPED_NO_EVIDENCE
    }

    public static final class Verdict {
        public final Kind kind;
        public final int changePercent;
        public final String text;

        Verdict(Kind kind, int changePercent, String text) {
            this.kind = kind;
            this.changePercent = changePercent;
            this.text = text;
        }

        public boolean throttled() {
            return kind == Kind.DROPPED_THERMAL || kind == Kind.DROPPED_NO_EVIDENCE;
        }
    }

    private ThrottleAnalysis() {
    }

    /**
     * @param kops   work per second, all cores
     * @param tempsC temperature each second, or -1 when unknown (may be null)
     * @param freqs  average clock in kHz each second, or -1 when unknown (may be null)
     */
    public static Verdict analyze(long[] kops, int[] tempsC, int[] freqs) {
        if (kops == null || kops.length < 4) {
            return new Verdict(Kind.TOO_SHORT, 0, "too short to judge (needs at least 4 seconds)");
        }
        double first = (kops[0] + kops[1]) / 2.0;
        double last = (kops[kops.length - 1] + kops[kops.length - 2]) / 2.0;
        if (first <= 0) {
            return new Verdict(Kind.TOO_SHORT, 0, "no work was recorded in the first seconds");
        }
        int change = (int) Math.round(100.0 * (last - first) / first);
        if (change > DROP_PERCENT) {
            return new Verdict(Kind.RAMPED_UP, change, String.format(Locale.US,
                    "speed rose %d%% over the test (clocks ramping up or the runtime warming up); "
                            + "no throttling seen", change));
        }
        if (change > -DROP_PERCENT) {
            return new Verdict(Kind.STEADY, change, String.format(Locale.US,
                    "steady: %+d%% between the first and last seconds; no throttling in this short test",
                    change));
        }
        int tempRise = riseOf(tempsC);
        int freqDrop = dropPercentOf(freqs);
        boolean thermal = tempRise >= TEMP_RISE_C || freqDrop >= FREQ_DROP_PERCENT;
        if (thermal) {
            StringBuilder why = new StringBuilder();
            if (tempRise >= TEMP_RISE_C) {
                why.append("temperature rose ").append(tempRise).append(" C");
            }
            if (freqDrop >= FREQ_DROP_PERCENT) {
                if (why.length() > 0) {
                    why.append(" and ");
                }
                why.append("clock fell ").append(freqDrop).append("%");
            }
            return new Verdict(Kind.DROPPED_THERMAL, change, String.format(Locale.US,
                    "work fell %d%% while the %s: likely thermal throttling", -change, why));
        }
        return new Verdict(Kind.DROPPED_NO_EVIDENCE, change, String.format(Locale.US,
                "work fell %d%% but no temperature or clock change could be read to say why: "
                        + "throttling, or another app using the CPU", -change));
    }

    private static int riseOf(int[] values) {
        if (values == null || values.length < 2) {
            return 0;
        }
        int first = values[0];
        int last = values[values.length - 1];
        return first < 0 || last < 0 ? 0 : Math.max(0, last - first);
    }

    private static int dropPercentOf(int[] values) {
        if (values == null || values.length < 2) {
            return 0;
        }
        int maxSeen = -1;
        for (int v : values) {
            maxSeen = Math.max(maxSeen, v);
        }
        int last = values[values.length - 1];
        if (maxSeen <= 0 || last < 0) {
            return 0;
        }
        return (int) Math.round(100.0 * (maxSeen - last) / maxSeen);
    }
}
