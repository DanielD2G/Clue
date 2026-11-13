package com.clue.tuner;

/**
 * Bitrate search for the tuner: geometric ascent (x1.3 from 20 Mbps, capped at 150 Mbps)
 * until the first unstable step, then bisection between the last stable and the first
 * unstable step until the gap is under 10 % of the stable one.
 *
 * Pure logic, no Android dependencies. Not thread-safe; driven from the UI thread.
 */
public final class BitrateSearch {
    public static final int START_KBPS = 20000, CAP_KBPS = 150000;

    private static final double GROWTH = 1.3;
    private static final double BISECT_GAP = 0.10;

    private int pass;            // highest stable kbps so far (0 = none)
    private int fail;            // lowest unstable kbps so far (0 = none yet)
    private int pending;         // step handed out by nextKbps(), 0 = compute a new one
    private boolean done;

    public BitrateSearch() {
    }

    /** The next bitrate to test, or -1 when the search has finished. Idempotent until report(). */
    public int nextKbps() {
        if (done) {
            return -1;
        }
        if (pending != 0) {
            return pending;
        }

        int next;
        if (fail == 0) {
            // Ascent
            if (pass == 0) {
                next = START_KBPS;
            }
            else if (pass >= CAP_KBPS) {
                done = true;
                return -1;
            }
            else {
                next = Math.min(CAP_KBPS, roundToThousand(pass * GROWTH));
            }
        }
        else {
            // Bisection (or nothing left to do)
            if (pass == 0 || (fail - pass) / (double) pass < BISECT_GAP) {
                done = true;
                return -1;
            }
            next = ((pass + fail) / 2) / 1000 * 1000;
            if (next <= pass || next >= fail) {
                done = true;
                return -1;
            }
        }
        pending = next;
        return next;
    }

    public void report(int kbps, boolean stable) {
        if (stable) {
            pass = Math.max(pass, kbps);
        }
        else if (fail == 0 || kbps < fail) {
            fail = kbps;
        }
        if (kbps == pending) {
            pending = 0;
        }
    }

    /** Highest bitrate measured stable, or 0 if none was. */
    public int bestStableKbps() {
        return pass;
    }

    /**
     * Suggested bitrate: best stable x 0.85 (Wi-Fi headroom), never above the decoder's
     * declared maximum when it declares one, rounded down to 5 Mbps. 0 if nothing was stable.
     */
    public static int suggest(int bestKbps, int decoderMaxKbps) {
        if (bestKbps <= 0) {
            return 0;
        }
        long kbps = (long) bestKbps * 85 / 100;
        // A declared ceiling under 5 Mbps is bogus (vendor capability tables vary): ignore it
        // rather than recommend 0
        if (decoderMaxKbps >= 5000) {
            kbps = Math.min(kbps, decoderMaxKbps);
        }
        return (int) (kbps / 5000 * 5000);
    }

    private static int roundToThousand(double kbps) {
        return (int) Math.round(kbps / 1000.0) * 1000;
    }
}
