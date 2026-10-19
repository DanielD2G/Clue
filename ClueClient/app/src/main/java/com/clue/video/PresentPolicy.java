package com.clue.video;

/**
 * Chooses the vsync each decoded frame is released against, with an adaptive buffer of 0 or 1 frame.
 *
 * Depth 0 targets the first vsync whose deadline is still reachable (lowest latency). When arrivals
 * crowd the deadline edge, which is what the host/panel clock beat does periodically, depth 1 targets
 * one vsync later so arrival jitter cannot alternate repeats and drops.
 *
 * onVsync() runs on the vsync thread and targetFor() on the renderer thread; the vsync snapshot is
 * shared through a seqlock. Nothing here allocates after construction.
 */
public final class PresentPolicy {
    public static final long DEFAULT_SAFETY_NS = 1_000_000L;
    public static final long DEFAULT_GUARD_NS = 1_500_000L;
    public static final int ENTER_WINDOW = 120, ENTER_EDGES = 12;
    public static final int EXIT_WINDOW = 240;

    private final long safetyNs;
    private final long guardNs;

    // Vsync snapshot, written by onVsync() under the seqlock.
    private volatile long seq;
    private volatile long deadlineNs;
    private volatile long expectedPresentNs;
    private volatile long periodNs;

    // Renderer-thread state.
    private final boolean[] edges = new boolean[EXIT_WINDOW];
    private int edgeHead;       // index of the next slot to write
    private int samples;        // frames recorded, capped at EXIT_WINDOW
    private int edgesEnter;     // edges among the last ENTER_WINDOW frames
    private int edgesExit;      // edges among the last EXIT_WINDOW frames
    private int depth;
    private long lastTarget;
    private volatile long depthSwitches;
    private volatile int edgesInWindowSnapshot;

    public PresentPolicy(long safetyNs, long guardNs) {
        this.safetyNs = safetyNs;
        this.guardNs = guardNs;
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

        long ahead = nowNs + safetyNs - d0;
        long k = ahead > 0 ? (ahead + period - 1) / period : 0;
        long slack = d0 + k * period - nowNs - safetyNs;

        recordEdge(slack < guardNs || (slack > period - guardNs && slack < period));

        long target = p0 + (k + depth) * period;
        if (target < lastTarget) {
            target = lastTarget;
        }
        lastTarget = target;
        return target;
    }

    private void recordEdge(boolean edge) {
        if (samples >= ENTER_WINDOW) {
            int leavingEnter = (edgeHead - ENTER_WINDOW + EXIT_WINDOW) % EXIT_WINDOW;
            if (edges[leavingEnter]) {
                edgesEnter--;
            }
        }
        if (samples >= EXIT_WINDOW && edges[edgeHead]) {
            edgesExit--;
        }
        edges[edgeHead] = edge;
        if (edge) {
            edgesEnter++;
            edgesExit++;
        }
        edgeHead = (edgeHead + 1) % EXIT_WINDOW;
        if (samples < EXIT_WINDOW) {
            samples++;
        }
        edgesInWindowSnapshot = edgesEnter;

        if (depth == 0 && edgesEnter >= ENTER_EDGES) {
            depth = 1;
            depthSwitches++;
        } else if (depth == 1 && samples >= EXIT_WINDOW && edgesExit == 0) {
            depth = 0;
            depthSwitches++;
        }
    }

    public int depth() {
        return depth;
    }

    public int edgesInWindow() {
        return edgesInWindowSnapshot;
    }

    public long depthSwitches() {
        return depthSwitches;
    }

    /** Clears the buffer decision and the last target; keeps the vsync snapshot. */
    public void reset() {
        java.util.Arrays.fill(edges, false);
        edgeHead = 0;
        samples = 0;
        edgesEnter = 0;
        edgesExit = 0;
        depth = 0;
        lastTarget = 0;
        edgesInWindowSnapshot = 0;
    }
}
