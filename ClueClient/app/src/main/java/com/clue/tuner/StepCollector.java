package com.clue.tuner;

import com.clue.trace.FrameTrace;

import java.util.Arrays;

/**
 * Collects one tuner step's measurement window: decoder counters at the start and end,
 * RTT samples in between, and decode times from the in-memory frame trace.
 *
 * All buffers are preallocated in the constructor; begin/addRtt/end allocate nothing
 * except the returned StepMetrics. Runs on the UI thread, off the stream's hot path.
 */
public final class StepCollector {
    private final long[] rtt;
    private int nRtt;
    private final long[] decode;
    private int nDecode;

    private long beginNs, endNs;
    private int recv0, lost0, rendered0;

    private final FrameTrace.FrameVisitor decodeVisitor;

    public StepCollector(int maxRttSamples, int maxFrames) {
        rtt = new long[maxRttSamples];
        decode = new long[maxFrames];
        decodeVisitor = (frameNumber, firstPacketUs, completeUs, hostLatency01ms, decodeInNs,
                         decodeOutNs, releaseNs, targetVsyncNs, presentNs) -> {
            if (decodeInNs >= beginNs && decodeInNs <= endNs && decodeOutNs >= decodeInNs
                    && nDecode < decode.length) {
                decode[nDecode++] = decodeOutNs - decodeInNs;
            }
        };
    }

    /** Start of the measurement window (end of warm-up), with the decoder's cumulative counters. */
    public void begin(long nowNs, int framesReceived, int framesLost, int framesRendered) {
        beginNs = nowNs;
        recv0 = framesReceived;
        lost0 = framesLost;
        rendered0 = framesRendered;
        nRtt = 0;
        nDecode = 0;
    }

    public void addRtt(int rttMs) {
        if (nRtt < rtt.length) {
            rtt[nRtt++] = rttMs;
        }
    }

    /** End of the window. {@code ft} may be null (no decode data: decode p95 reads 0). */
    public StepMetrics end(long nowNs, int framesReceived, int framesLost, int framesRendered,
                           FrameTrace ft, int requestedKbps, int fpsTarget) {
        endNs = nowNs;
        StepMetrics m = new StepMetrics();
        m.requestedKbps = requestedKbps;
        m.fpsTarget = fpsTarget;
        m.framesReceived = framesReceived - recv0;
        m.framesLost = framesLost - lost0;
        m.framesPresented = framesRendered - rendered0;
        double seconds = (endNs - beginNs) / 1e9;
        m.fpsReceived = seconds > 0 ? m.framesReceived / seconds : 0;
        m.rttP95Ms = percentile(rtt, nRtt, 0.95);

        nDecode = 0;
        if (ft != null) {
            ft.forEach(decodeVisitor);
        }
        m.decodeP95Ms = percentile(decode, nDecode, 0.95) / 1e6;
        m.interrupted = false;
        return m;
    }

    public static StepMetrics interrupted(int requestedKbps) {
        StepMetrics m = new StepMetrics();
        m.requestedKbps = requestedKbps;
        m.interrupted = true;
        return m;
    }

    /** Nearest-rank percentile of the first n values; sorts them in place. 0 when n == 0. */
    static double percentile(long[] v, int n, double p) {
        if (n <= 0) {
            return 0;
        }
        Arrays.sort(v, 0, n);
        int rank = (int) Math.ceil(p * n) - 1;
        return v[Math.max(0, Math.min(n - 1, rank))];
    }
}
