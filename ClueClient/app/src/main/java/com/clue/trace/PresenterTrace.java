package com.clue.trace;

/**
 * Raw inputs of the video presenter, recorded so presenter policies can be replayed and compared
 * offline (tools/PresenterSim.java) against the exact arrivals and vsync timelines of a session.
 *
 * Two preallocated rings, each written by a single thread: frames by the renderer thread at
 * decoder output, vsyncs by the vsync callback thread. Nothing allocates while streaming.
 */
public final class PresenterTrace {
    public interface FrameVisitor {
        void visit(long hostNs, long outNs, long targetNs);
    }

    public interface VsyncVisitor {
        void visit(long callbackNs, long deadlineNs, long expectedPresentNs, long periodNs);
    }

    private final int mask;
    private final long[] hostNs, outNs, targetNs;
    private final long[] callbackNs, deadlineNs, presentNs, periodNs;
    private volatile long frameHead, vsyncHead;

    public PresenterTrace(int capacityPow2) {
        if (capacityPow2 <= 0 || Integer.bitCount(capacityPow2) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        mask = capacityPow2 - 1;
        hostNs = new long[capacityPow2];
        outNs = new long[capacityPow2];
        targetNs = new long[capacityPow2];
        callbackNs = new long[capacityPow2];
        deadlineNs = new long[capacityPow2];
        presentNs = new long[capacityPow2];
        periodNs = new long[capacityPow2];
    }

    /** A decoded frame: its host capture time, when it left the decoder, and the target used (0 if none). */
    public void onFrame(long host, long out, long target) {
        long i = frameHead;
        int s = (int) (i & mask);
        hostNs[s] = host;
        outNs[s] = out;
        targetNs[s] = target;
        frameHead = i + 1;
    }

    /** One vsync callback with the timeline handed to the policy. */
    public void onVsync(long callback, long deadline, long expectedPresent, long period) {
        long i = vsyncHead;
        int s = (int) (i & mask);
        callbackNs[s] = callback;
        deadlineNs[s] = deadline;
        presentNs[s] = expectedPresent;
        periodNs[s] = period;
        vsyncHead = i + 1;
    }

    public int frameCount() {
        return (int) Math.min(frameHead, mask + 1);
    }

    public int vsyncCount() {
        return (int) Math.min(vsyncHead, mask + 1);
    }

    /** Oldest first. */
    public void forEachFrame(FrameVisitor v) {
        long end = frameHead;
        for (long i = Math.max(0, end - (mask + 1)); i < end; i++) {
            int s = (int) (i & mask);
            v.visit(hostNs[s], outNs[s], targetNs[s]);
        }
    }

    /** Oldest first. */
    public void forEachVsync(VsyncVisitor v) {
        long end = vsyncHead;
        for (long i = Math.max(0, end - (mask + 1)); i < end; i++) {
            int s = (int) (i & mask);
            v.visit(callbackNs[s], deadlineNs[s], presentNs[s], periodNs[s]);
        }
    }
}
