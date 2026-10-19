package com.clue.trace;

import android.content.Context;

/**
 * Owns the trace buffers for one streaming session. Hooks in the upstream code
 * fetch the active buffers through the static accessors, which return null
 * when no session is being traced, so a missing session costs one volatile read.
 */
public final class TraceSession {
    static final int FRAME_CAPACITY = 1 << 17;

    private static volatile TraceSession current;

    private final FrameTrace frames = new FrameTrace(FRAME_CAPACITY);

    private TraceSession() {}

    public static TraceSession start(Context ctx) {
        TraceSession s = new TraceSession();
        current = s;
        return s;
    }

    public static TraceSession current() {
        return current;
    }

    /** Frame trace of the active session, or null. */
    public static FrameTrace activeFrames() {
        TraceSession s = current;
        return s != null ? s.frames : null;
    }

    public FrameTrace frames() {
        return frames;
    }

    public void stop() {
        if (current == this) {
            current = null;
        }
        logSummary();
    }

    /** One logcat line per session, so a trace can be sanity-checked without pulling files. */
    private void logSummary() {
        final long[] c = new long[4]; // presented, negative stage deltas, receive->present sum us, n
        frames.forEach((frameNumber, firstPacketUs, completeUs, hostLatency01ms, decodeInNs,
                        decodeOutNs, releaseNs, targetVsyncNs, presentNs) -> {
            if (presentNs > 0) {
                c[0]++;
                long toPresentUs = presentNs / 1000 - completeUs;
                if (completeUs < firstPacketUs || toPresentUs < 0
                        || (decodeOutNs > 0 && decodeOutNs < decodeInNs)) {
                    c[1]++;
                } else {
                    c[2] += toPresentUs;
                    c[3]++;
                }
            }
        });
        final long[] d = new long[3]; // completeUs->decodeIn (us), decodeIn->present (us), n
        frames.forEach((frameNumber, firstPacketUs, completeUs, hostLatency01ms, decodeInNs,
                        decodeOutNs, releaseNs, targetVsyncNs, presentNs) -> {
            if (presentNs > 0 && decodeInNs > 0) {
                d[0] += decodeInNs / 1000 - completeUs;
                d[1] += (presentNs - decodeInNs) / 1000;
                d[2]++;
            }
        });
        android.util.Log.i("ClueTrace", "session frames=" + frames.size()
                + " presented=" + c[0]
                + " ptsMisses=" + frames.ptsMisses()
                + " negativeStages=" + c[1]
                + " avgCompleteToPresentMs=" + (c[3] > 0 ? (c[2] / c[3]) / 1000.0 : -1)
                + " avgCompleteToDecodeInMs=" + (d[2] > 0 ? (d[0] / d[2]) / 1000.0 : -1)
                + " avgDecodeInToPresentMs=" + (d[2] > 0 ? (d[1] / d[2]) / 1000.0 : -1));
    }
}
