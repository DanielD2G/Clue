package com.clue.tuner;

import java.util.Locale;

/**
 * Decides whether a tuner step was stable. All criteria from the spec must hold:
 * network loss < 0.5 %, decode p95 <= 2 frame periods, not presented < 2 %,
 * RTT p95 <= first-step RTT p95 + 15 ms, received fps >= 90 % of target, and the received
 * video payload reaches 65 % of the requested bitrate (the host spends ~20 % on FEC; less
 * means the host or encoder capped it).
 * Ratio checks use integer cross-multiplication so the borders are exact.
 */
public final class StepVerdict {
    public enum Reason { OK, INTERRUPTED, NO_FRAMES, LOSS, FPS, DECODE, UNPRESENTED, RTT, THROUGHPUT }

    public static final class Result {
        public final boolean stable;
        public final Reason code;
        /** Short technical detail (language neutral numbers), e.g. "0.62 %". */
        public final String reason;

        Result(Reason code, String reason) {
            this.stable = code == Reason.OK;
            this.code = code;
            this.reason = reason;
        }
    }

    public static final double RTT_MARGIN_MS = 15;

    private StepVerdict() {
    }

    /** baselineRttP95Ms < 0 means "this is the first step": the RTT criterion is skipped. */
    public static Result evaluate(StepMetrics m, double baselineRttP95Ms) {
        if (m.interrupted) {
            return new Result(Reason.INTERRUPTED, "");
        }
        long recv = m.framesReceived;
        if (recv <= 0 || m.fpsTarget <= 0) {
            return new Result(Reason.NO_FRAMES, "");
        }
        // lost / received < 0.5 %
        if ((long) m.framesLost * 1000 >= recv * 5) {
            return new Result(Reason.LOSS, pct(m.framesLost, recv));
        }
        // received fps >= 90 % of target
        if (m.fpsReceived * 10 < m.fpsTarget * 9) {
            return new Result(Reason.FPS, String.format(Locale.ROOT, "%.0f/%d fps", m.fpsReceived, m.fpsTarget));
        }
        // decode p95 <= 2 frame periods
        if (m.decodeP95Ms > 2000.0 / m.fpsTarget) {
            return new Result(Reason.DECODE, String.format(Locale.ROOT, "%.1f ms", m.decodeP95Ms));
        }
        // not presented < 2 %
        long unpresented = Math.max(0, recv - m.framesPresented);
        if (unpresented * 100 >= recv * 2) {
            return new Result(Reason.UNPRESENTED, pct(unpresented, recv));
        }
        // received >= 65 % of requested: the stream really carried this bitrate. Sunshine-family
        // hosts spend ~20 % of the requested bitrate on FEC, which is not counted here, so a full
        // stream delivers ~80 % as video payload; 65 % leaves room for encoder rate control.
        if (m.receivedKbps >= 0 && m.receivedKbps * 100 < (double) m.requestedKbps * 65) {
            return new Result(Reason.THROUGHPUT, String.format(Locale.ROOT, "%.0f/%d kbps", m.receivedKbps, m.requestedKbps));
        }
        if (baselineRttP95Ms >= 0 && m.rttP95Ms > baselineRttP95Ms + RTT_MARGIN_MS) {
            return new Result(Reason.RTT, String.format(Locale.ROOT, "%.0f ms", m.rttP95Ms));
        }
        return new Result(Reason.OK, "");
    }

    private static String pct(long part, long whole) {
        return String.format(Locale.ROOT, "%.2f %%", 100.0 * part / whole);
    }
}
