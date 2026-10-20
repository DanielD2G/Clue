package com.clue.tuner;

import android.content.Intent;

/**
 * What one tuner step measured over its measurement window (warm-up excluded).
 * Travels from Game back to BitrateTunerActivity as Intent extras.
 */
public final class StepMetrics {
    public int requestedKbps;
    public int framesReceived;
    public int framesLost;
    public int framesPresented;
    public int fpsTarget;
    public double fpsReceived;
    public double decodeP95Ms;
    public double rttP95Ms;
    public boolean interrupted;

    private static final String P = "clue_tune_res_";

    public void writeTo(Intent i) {
        i.putExtra(P + "kbps", requestedKbps);
        i.putExtra(P + "recv", framesReceived);
        i.putExtra(P + "lost", framesLost);
        i.putExtra(P + "pres", framesPresented);
        i.putExtra(P + "fpsT", fpsTarget);
        i.putExtra(P + "fpsR", fpsReceived);
        i.putExtra(P + "dec", decodeP95Ms);
        i.putExtra(P + "rtt", rttP95Ms);
        i.putExtra(P + "int", interrupted);
    }

    /** Reads a result Intent; a missing or foreign Intent reads as an interrupted step. */
    public static StepMetrics readFrom(Intent i, int requestedKbps) {
        StepMetrics m = new StepMetrics();
        m.requestedKbps = requestedKbps;
        if (i == null || !i.hasExtra(P + "recv")) {
            m.interrupted = true;
            return m;
        }
        m.framesReceived = i.getIntExtra(P + "recv", 0);
        m.framesLost = i.getIntExtra(P + "lost", 0);
        m.framesPresented = i.getIntExtra(P + "pres", 0);
        m.fpsTarget = i.getIntExtra(P + "fpsT", 0);
        m.fpsReceived = i.getDoubleExtra(P + "fpsR", 0);
        m.decodeP95Ms = i.getDoubleExtra(P + "dec", 0);
        m.rttP95Ms = i.getDoubleExtra(P + "rtt", 0);
        m.interrupted = i.getBooleanExtra(P + "int", true);
        return m;
    }
}
