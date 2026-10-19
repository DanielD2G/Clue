package com.clue.video;

/**
 * Chooses the vsync each decoded frame is released against.
 *
 * Per-frame arrival times jitter by a millisecond or two, so choosing a vsync from each frame's own
 * arrival makes frames near a deadline alternate between two vsyncs (repeat, then drop). Instead a
 * phase-locked estimate of when frames should arrive (the host cadence) plus a robust lateness margin
 * gives a smooth "virtual arrival", and frame N+1 keeps the vsync after frame N's unless that virtual
 * arrival crosses a deadline:
 *  - later: immediately, otherwise late frames would miss the latch;
 *  - earlier: only past a hysteresis band, which happens once per host/panel clock beat and costs one
 *    dropped frame instead of a run of alternations.
 *
 * Targets sit half a period before the expected presentation time, because SurfaceFlinger latches a
 * buffer only when its desired present time is not after the vsync's expected present time.
 *
 * onVsync() runs on the vsync thread and targetFor() on the renderer thread; the vsync snapshot is
 * shared through a seqlock. Nothing here allocates.
 */
public final class PresentPolicy {
    public static final long DEFAULT_SAFETY_NS = 1_000_000L;
    public static final long DEFAULT_HYSTERESIS_NS = 500_000L;
    static final long INITIAL_MARGIN_NS = 2_000_000L;

    private final long safetyNs;
    private final long hysteresisNs;

    // Vsync snapshot, written by onVsync() under the seqlock.
    private volatile long seq;
    private volatile long deadlineNs;
    private volatile long expectedPresentNs;
    private volatile long periodNs;

    // Renderer-thread state.
    private long lastArrivalNs;
    private long sourcePeriodNs;
    private long predictedArrivalNs;
    private long lastDeadlineNs;
    private long lastTarget;
    private volatile long marginNs = INITIAL_MARGIN_NS;
    private volatile long remaps;

    public PresentPolicy(long safetyNs, long hysteresisNs) {
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

    /** Returns the presentation time for releaseOutputBuffer, or 0 before the first vsync. */
    public long targetFor(long nowNs) {
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

        trackArrival(nowNs, period);
        long virtualArrival = predictedArrivalNs + marginNs + safetyNs;

        long ahead = virtualArrival - d0;
        long candidate = d0 + (ahead > 0 ? (ahead + period - 1) / period : 0) * period;

        long chosen = candidate;
        if (lastDeadlineNs != 0) {
            long step = Math.max(1, (sourcePeriodNs + period / 2) / period) * period;
            long expected = lastDeadlineNs + step;
            if (virtualArrival <= expected && virtualArrival > expected - period - hysteresisNs) {
                chosen = expected;
            } else {
                remaps++;
            }
        }
        lastDeadlineNs = chosen;

        long target = p0 + (chosen - d0) - period / 2;
        if (target < lastTarget) {
            target = lastTarget;
        }
        lastTarget = target;
        return target;
    }

    private void trackArrival(long nowNs, long vsyncPeriodNs) {
        long dt = nowNs - lastArrivalNs;
        if (lastArrivalNs == 0 || dt <= 0 || (sourcePeriodNs > 0 && dt > 4 * sourcePeriodNs)) {
            // First frame or a pause: restart from this arrival
            predictedArrivalNs = nowNs;
            marginNs = INITIAL_MARGIN_NS;
            lastDeadlineNs = 0;
        } else {
            if (sourcePeriodNs == 0) {
                // Start from the nearest whole number of vsyncs: one interval carries a frame of
                // jitter, while streams run at (almost exactly) a multiple of the panel rate.
                sourcePeriodNs = Math.max(1, (dt + vsyncPeriodNs / 2) / vsyncPeriodNs) * vsyncPeriodNs;
            }
            // Second-order PLL: the arrival error corrects both phase and frequency, so a wrong
            // initial period (the first interval carries one frame of jitter) leaves no steady bias.
            predictedArrivalNs += sourcePeriodNs;
            long err = nowNs - predictedArrivalNs;
            predictedArrivalNs += err / 16;
            sourcePeriodNs += err / 256;

            long margin = marginNs;
            if (err > margin) {
                margin += (err - margin) / 4;
            } else {
                margin -= margin / 512;
            }
            marginNs = margin;
        }
        lastArrivalNs = nowNs;
    }

    /** Current lateness margin added to the predicted arrival, for the overlay. */
    public long marginNs() {
        return marginNs;
    }

    /** Times the frame-to-vsync mapping moved (each one costs one repeat or one drop). */
    public long remaps() {
        return remaps;
    }

    /** Forgets the cadence and the last target; keeps the vsync snapshot. */
    public void reset() {
        lastArrivalNs = 0;
        sourcePeriodNs = 0;
        predictedArrivalNs = 0;
        lastDeadlineNs = 0;
        lastTarget = 0;
        marginNs = 0;
    }
}
