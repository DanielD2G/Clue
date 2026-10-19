package com.clue.trace;

/**
 * Per-frame timing record for the video pipeline, kept in a preallocated ring
 * (struct of arrays) so the hot path never allocates or locks.
 *
 * Timestamps share one clock: microseconds for the network stages (from
 * moonlight-common-c, converted to CLOCK_MONOTONIC in JNI) and nanoseconds for
 * the decoder/presentation stages (System.nanoTime / MediaCodec callbacks).
 * Fields with no data are 0.
 *
 * Threading: each stage is written by a single thread (receive, decoder output,
 * render callback). Readers during a session (the overlay) may observe a frame
 * half-updated; that is acceptable for statistics. 64-bit stores are atomic on
 * arm64, the only ABI Clue targets for measurement.
 */
public final class FrameTrace {
    /** How far back from the newest decoder input a PTS lookup searches. */
    static final int PTS_SEARCH_DEPTH = 16;
    private static final long NO_PTS = Long.MIN_VALUE;

    public interface FrameVisitor {
        void visit(int frameNumber, long firstPacketUs, long completeUs, int hostLatency01ms,
                   long decodeInNs, long decodeOutNs, long releaseNs, long targetVsyncNs,
                   long presentNs);
    }

    private final int mask;
    private final int[] frameNumber;
    private final long[] firstPacketUs;
    private final long[] completeUs;
    private final int[] hostLatency01ms;
    private final long[] ptsUs;
    private final long[] decodeInNs;
    private final long[] decodeOutNs;
    private final long[] releaseNs;
    private final long[] targetVsyncNs;
    private final long[] presentNs;

    /** Number of frames ever received; slot of frame k is (k & mask). */
    private volatile long head;
    /** Ring index (not slot) of the most recent decoder input, or -1. */
    private volatile long newestInput = -1;
    private volatile long ptsMisses;

    public FrameTrace(int capacityPow2) {
        if (capacityPow2 <= 0 || Integer.bitCount(capacityPow2) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        mask = capacityPow2 - 1;
        frameNumber = new int[capacityPow2];
        firstPacketUs = new long[capacityPow2];
        completeUs = new long[capacityPow2];
        hostLatency01ms = new int[capacityPow2];
        ptsUs = new long[capacityPow2];
        decodeInNs = new long[capacityPow2];
        decodeOutNs = new long[capacityPow2];
        releaseNs = new long[capacityPow2];
        targetVsyncNs = new long[capacityPow2];
        presentNs = new long[capacityPow2];
    }

    public void onReceived(int frame, long firstPacket, long complete, int hostLatency) {
        long index = head;
        int s = (int) (index & mask);
        frameNumber[s] = frame;
        firstPacketUs[s] = firstPacket;
        completeUs[s] = complete;
        hostLatency01ms[s] = hostLatency;
        ptsUs[s] = NO_PTS;
        decodeInNs[s] = 0;
        decodeOutNs[s] = 0;
        releaseNs[s] = 0;
        targetVsyncNs[s] = 0;
        presentNs[s] = 0;
        head = index + 1;
    }

    public void onDecodeInput(int frame, long pts, long tNs) {
        long newest = head - 1;
        long oldest = Math.max(0, head - Math.min(PTS_SEARCH_DEPTH, mask + 1));
        for (long i = newest; i >= oldest; i--) {
            int s = (int) (i & mask);
            if (frameNumber[s] == frame) {
                ptsUs[s] = pts;
                decodeInNs[s] = tNs;
                newestInput = i;
                return;
            }
        }
        ptsMisses++;
    }

    public void onDecodeOutput(long pts, long tNs) {
        int s = slotForPts(pts);
        if (s >= 0) {
            decodeOutNs[s] = tNs;
        }
    }

    public void onRelease(long pts, long tNs, long targetVsync) {
        int s = slotForPts(pts);
        if (s >= 0) {
            releaseNs[s] = tNs;
            targetVsyncNs[s] = targetVsync;
        }
    }

    public void onPresented(long pts, long present) {
        int s = slotForPts(pts);
        if (s >= 0) {
            presentNs[s] = present;
        }
    }

    /** Called when the decoder is (re)created: earlier PTS values must not match new frames. */
    public void reset() {
        long newest = head - 1;
        long oldest = Math.max(0, head - (mask + 1));
        for (long i = newest; i >= oldest; i--) {
            ptsUs[(int) (i & mask)] = NO_PTS;
        }
        newestInput = -1;
    }

    public long ptsMisses() {
        return ptsMisses;
    }

    public int size() {
        return (int) Math.min(head, mask + 1);
    }

    /** Visits recorded frames from oldest to newest. */
    public void forEach(FrameVisitor v) {
        forEachRecent(mask + 1, v);
    }

    /** Visits the newest {@code n} recorded frames, oldest first. */
    public void forEachRecent(int n, FrameVisitor v) {
        long end = head;
        long start = Math.max(0, end - Math.min(n, mask + 1));
        for (long i = start; i < end; i++) {
            int s = (int) (i & mask);
            v.visit(frameNumber[s], firstPacketUs[s], completeUs[s], hostLatency01ms[s],
                    decodeInNs[s], decodeOutNs[s], releaseNs[s], targetVsyncNs[s], presentNs[s]);
        }
    }

    private int slotForPts(long pts) {
        long newest = newestInput;
        if (newest >= 0) {
            long oldest = Math.max(Math.max(0, head - (mask + 1)), newest - PTS_SEARCH_DEPTH + 1);
            for (long i = newest; i >= oldest; i--) {
                int s = (int) (i & mask);
                if (ptsUs[s] == pts) {
                    return s;
                }
            }
        }
        ptsMisses++;
        return -1;
    }
}
