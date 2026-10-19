package com.clue.video;

/**
 * Chooses the vsync each decoded frame is released against.
 *
 * Per-frame arrival times jitter by a millisecond or two, so choosing a vsync from each frame's own
 * arrival makes frames near a deadline alternate between two vsyncs (repeat, then drop). Instead each
 * frame gets a "virtual arrival" from its host capture time:
 *
 *   virtual arrival = host time + offset + margin + safety
 *
 * where offset is the smoothed host-to-local delay and margin a windowed high percentile of how late
 * frames arrive against it. Host time carries the real cadence, so a game that skips frames or a
 * panel at another rate needs no guessing. Arrivals delayed by more than a vsync (network stalls)
 * are left out of the margin: they cannot be absorbed by it and would only add latency afterwards.
 *
 * Frame N+1 keeps the vsync that follows frame N's by its host interval unless the virtual arrival
 * crosses a deadline: later immediately, otherwise late frames would miss the latch; earlier only
 * past a hysteresis band, which happens once per host/panel clock beat and costs one dropped frame
 * instead of a run of alternations. A frame never targets more than one vsync past the first one
 * it can reach, so SurfaceFlinger holds at most one frame of buffer.
 *
 * Targets sit half a period before the expected presentation time, because SurfaceFlinger latches a
 * buffer only when its desired present time is not after the vsync's expected present time.
 *
 * onVsync() runs on the vsync thread and targetFor() on the renderer thread; the vsync snapshot is
 * shared through a seqlock. Nothing here allocates after construction.
 */
public final class PresentPolicy {
    public static final long DEFAULT_SAFETY_NS = 500_000L;
    public static final long DEFAULT_HYSTERESIS_NS = 500_000L;
    // Chosen by replaying a recorded POCO Pad session (tools/presenter-sim.sh): 95th percentile
    // with 0.5 ms safety gave the best p99 and fewest drops at the same p50.
    public static final int DEFAULT_MARGIN_PER_MILLE = 50;
    static final long INITIAL_MARGIN_NS = 2_000_000L;

    static final int WINDOW = 240;          // frames in the margin window
    static final int MIN_SAMPLES = 30;      // below this the initial margin applies
    static final long BIN_NS = 125_000L;    // margin histogram resolution
    static final int BINS = 160;            // covers 20 ms, a vsync at 50 Hz
    static final long RESYNC_NS = 100_000_000L;

    private final long safetyNs;
    private final long hysteresisNs;
    private final int marginPerMille; // share of arrivals allowed past the margin, per 1000

    // Vsync snapshot, written by onVsync() under the seqlock.
    private volatile long seq;
    private volatile long deadlineNs;
    private volatile long expectedPresentNs;
    private volatile long periodNs;

    // Renderer-thread state.
    private boolean haveOffset;
    private long offsetNs;
    private long lastHostNs;
    private long lastDeadlineNs;
    private long lastTarget;
    private final short[] window = new short[WINDOW];
    private final int[] histogram = new int[BINS];
    private int windowHead;
    private int samples;
    private volatile long marginNs = INITIAL_MARGIN_NS;
    private volatile long remaps;

    public PresentPolicy(long safetyNs, long hysteresisNs) {
        this(safetyNs, hysteresisNs, DEFAULT_MARGIN_PER_MILLE);
    }

    /** @param marginPerMille arrivals per 1000 allowed later than the margin (50 = 95th percentile) */
    public PresentPolicy(long safetyNs, long hysteresisNs, int marginPerMille) {
        this.marginPerMille = marginPerMille;
        this.safetyNs = safetyNs;
        this.hysteresisNs = hysteresisNs;
    }

    public void onVsync(long deadlineNs, long expectedPresentNs, long periodNs) {
        long s = seq;
        seq = s + 1;
        this.deadlineNs = deadlineNs;
        this.expectedPresentNs = expectedPresentNs;
        if (periodNs > 0) {
            this.periodNs = periodNs;
        }
        seq = s + 2;
    }

    /**
     * Returns the presentation time for releaseOutputBuffer, or 0 before the first vsync.
     *
     * @param nowNs  local time the frame left the decoder (System.nanoTime)
     * @param hostNs the frame's host capture time, any epoch, in ns
     */
    public long targetFor(long nowNs, long hostNs) {
        long d0, p0, period, s;
        do {
            s = seq;
            d0 = deadlineNs;
            p0 = expectedPresentNs;
            period = periodNs;
        } while ((s & 1) != 0 || s != seq);

        if (s == 0 || period <= 0) {
            return 0;
        }

        trackArrival(nowNs, hostNs, period);
        long virtualArrival = hostNs + offsetNs + marginNs + safetyNs;

        long candidate = firstDeadlineAtOrAfter(virtualArrival, d0, period);
        long chosen = candidate;
        if (lastDeadlineNs != 0) {
            long step = Math.max(0, (hostNs - lastHostNs + period / 2) / period) * period;
            long expected = lastDeadlineNs + step;
            if (virtualArrival <= expected && virtualArrival > expected - period - hysteresisNs) {
                chosen = expected;
            } else {
                remaps++;
            }
        }

        // Never queue more than one frame ahead of the first vsync this frame can still reach
        long cap = firstDeadlineAtOrAfter(nowNs + safetyNs, d0, period) + period;
        if (chosen > cap) {
            chosen = cap;
            remaps++;
        }

        lastDeadlineNs = chosen;
        lastHostNs = hostNs;

        long target = p0 + (chosen - d0) - period / 2;
        if (target < lastTarget) {
            target = lastTarget;
        }
        lastTarget = target;
        return target;
    }

    private static long firstDeadlineAtOrAfter(long t, long d0, long period) {
        long ahead = t - d0;
        return d0 + (ahead > 0 ? (ahead + period - 1) / period : 0) * period;
    }

    private void trackArrival(long nowNs, long hostNs, long period) {
        long delay = nowNs - hostNs;
        if (!haveOffset || Math.abs(delay - offsetNs) > RESYNC_NS) {
            // First frame or the host clock restarted: start over from this frame
            haveOffset = true;
            offsetNs = delay;
            clearWindow();
            lastDeadlineNs = 0;
            return;
        }

        long residual = delay - offsetNs;
        offsetNs += Math.max(-period, Math.min(period, residual)) / 32;

        if (residual <= period) {
            int bin = (int) Math.max(0, Math.min(BINS - 1, residual / BIN_NS));
            if (samples == WINDOW) {
                histogram[window[windowHead]]--;
            } else {
                samples++;
            }
            window[windowHead] = (short) bin;
            histogram[bin]++;
            windowHead = (windowHead + 1) % WINDOW;
        }

        if (samples >= MIN_SAMPLES) {
            // High percentile (95th by default), rounded up to the bin edge, capped at one vsync
            int allowedAbove = samples * marginPerMille / 1000;
            int seen = 0;
            int bin = BINS - 1;
            while (bin > 0) {
                seen += histogram[bin];
                if (seen > allowedAbove) {
                    break;
                }
                bin--;
            }
            marginNs = Math.min(period, (bin + 1) * BIN_NS);
        }
    }

    private void clearWindow() {
        java.util.Arrays.fill(histogram, 0);
        windowHead = 0;
        samples = 0;
        marginNs = INITIAL_MARGIN_NS;
    }

    /** Current lateness margin added to the predicted arrival, for the overlay. */
    public long marginNs() {
        return marginNs;
    }

    /** Times the frame-to-vsync mapping moved (each one costs one repeat or one drop). */
    public long remaps() {
        return remaps;
    }

    /** Forgets the host clock relation and the last target; keeps the vsync snapshot. */
    public void reset() {
        haveOffset = false;
        offsetNs = 0;
        lastHostNs = 0;
        lastDeadlineNs = 0;
        lastTarget = 0;
        clearWindow();
    }
}
