package com.clue.trace;

import java.util.Arrays;

/**
 * Rolling statistics over the newest frames and input events, for the overlay.
 * All buffers are preallocated: compute() allocates nothing besides the two
 * visitor objects created once in the constructor.
 *
 * Note: presentNs is the time MediaCodec reports for onFrameRendered, which on the
 * reference device equals the scheduled vsync. "repeated" therefore reflects the
 * pacer's schedule; measured display cadence comes from SurfaceFlinger (tools/cadence.sh).
 */
public final class TraceStats {
    private final int window;
    private final long[] net;
    private final long[] decode;
    private final long[] toPresent;
    private final long[] inputSend;
    private int nNet, nDecode, nToPresent, nInput;

    private long lastPresentNs;
    private long vsyncNs;
    private int intervals, repeated;
    private int framesSeen, unpresented;

    private double netP50, netP99, decodeP50, decodeP99, toPresentP50, toPresentP99;
    private double inputSendP50, inputSendP99, repeatedPct, unpresentedPct;

    private final FrameTrace.FrameVisitor frameVisitor;
    private final InputTrace.InputVisitor inputVisitor;

    public TraceStats(int window) {
        this.window = window;
        net = new long[window];
        decode = new long[window];
        toPresent = new long[window];
        inputSend = new long[window];

        frameVisitor = (frameNumber, firstPacketUs, completeUs, hostLatency01ms, decodeInNs,
                        decodeOutNs, releaseNs, targetVsyncNs, presentNs) -> {
            framesSeen++;
            if (completeUs > 0 && firstPacketUs > 0 && completeUs >= firstPacketUs) {
                net[nNet++] = (completeUs - firstPacketUs) * 1000L;
            }
            if (decodeOutNs > 0 && decodeInNs > 0 && decodeOutNs >= decodeInNs) {
                decode[nDecode++] = decodeOutNs - decodeInNs;
            }
            if (presentNs > 0) {
                long dt = presentNs - completeUs * 1000L;
                if (completeUs > 0 && dt > 0) {
                    toPresent[nToPresent++] = dt;
                }
                if (lastPresentNs > 0) {
                    intervals++;
                    if (presentNs - lastPresentNs >= vsyncNs + vsyncNs / 2) {
                        repeated++;
                    }
                }
                lastPresentNs = presentNs;
            } else {
                unpresented++;
            }
        };
        inputVisitor = (type, eventNs, sentNs) -> {
            if (sentNs > 0 && sentNs >= eventNs) {
                inputSend[nInput++] = sentNs - eventNs;
            }
        };
    }

    public void compute(FrameTrace ft, InputTrace it, long vsyncNanos) {
        nNet = nDecode = nToPresent = nInput = 0;
        intervals = repeated = framesSeen = unpresented = 0;
        lastPresentNs = 0;
        vsyncNs = vsyncNanos;

        ft.forEachRecent(window, frameVisitor);
        it.forEachRecent(window, inputVisitor);

        netP50 = percentileMs(net, nNet, 0.50);
        netP99 = percentileMs(net, nNet, 0.99);
        decodeP50 = percentileMs(decode, nDecode, 0.50);
        decodeP99 = percentileMs(decode, nDecode, 0.99);
        toPresentP50 = percentileMs(toPresent, nToPresent, 0.50);
        toPresentP99 = percentileMs(toPresent, nToPresent, 0.99);
        inputSendP50 = percentileMs(inputSend, nInput, 0.50);
        inputSendP99 = percentileMs(inputSend, nInput, 0.99);
        repeatedPct = intervals > 0 ? 100.0 * repeated / intervals : 0;
        unpresentedPct = framesSeen > 0 ? 100.0 * unpresented / framesSeen : 0;
    }

    /** Nearest-rank percentile of the first n values (ns), in milliseconds; 0 when empty. */
    static double percentileMs(long[] values, int n, double q) {
        if (n == 0) {
            return 0;
        }
        Arrays.sort(values, 0, n);
        int rank = (int) Math.ceil(q * n);
        return values[Math.max(0, rank - 1)] / 1e6;
    }

    public double netP50() { return netP50; }
    public double netP99() { return netP99; }
    public double decodeP50() { return decodeP50; }
    public double decodeP99() { return decodeP99; }
    public double toPresentP50() { return toPresentP50; }
    public double toPresentP99() { return toPresentP99; }
    public double inputSendP50() { return inputSendP50; }
    public double inputSendP99() { return inputSendP99; }
    public double repeatedPct() { return repeatedPct; }
    public double unpresentedPct() { return unpresentedPct; }
}
