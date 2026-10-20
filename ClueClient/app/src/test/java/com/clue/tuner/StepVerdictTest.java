package com.clue.tuner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StepVerdictTest {

    /** A clean 120 fps step: 1440 frames in 12 s, all presented, fast decode, flat RTT. */
    private static StepMetrics good() {
        StepMetrics m = new StepMetrics();
        m.requestedKbps = 50000;
        m.framesReceived = 10000;
        m.framesLost = 0;
        m.framesPresented = 10000;
        m.fpsTarget = 120;
        m.fpsReceived = 120;
        m.decodeP95Ms = 5;
        m.rttP95Ms = 5;
        m.interrupted = false;
        return m;
    }

    private static StepVerdict.Result eval(StepMetrics m) {
        return StepVerdict.evaluate(m, 5);
    }

    @Test
    public void cleanStepIsStable() {
        StepVerdict.Result r = eval(good());
        assertTrue(r.reason, r.stable);
        assertEquals(StepVerdict.Reason.OK, r.code);
    }

    @Test
    public void lossJustBelowHalfPercentPasses() {
        StepMetrics m = good();
        m.framesLost = 49;          // 0.49 %
        assertTrue(eval(m).stable);
    }

    @Test
    public void lossAtHalfPercentFails() {
        StepMetrics m = good();
        m.framesLost = 50;          // 0.50 %
        StepVerdict.Result r = eval(m);
        assertFalse(r.stable);
        assertEquals(StepVerdict.Reason.LOSS, r.code);
    }

    @Test
    public void decodeAtTwoFramePeriodsPasses() {
        StepMetrics m = good();
        m.decodeP95Ms = 2 * 1000.0 / 120;
        assertTrue(eval(m).stable);
    }

    @Test
    public void decodeAboveTwoFramePeriodsFails() {
        StepMetrics m = good();
        m.decodeP95Ms = 2 * 1000.0 / 120 + 0.01;
        StepVerdict.Result r = eval(m);
        assertFalse(r.stable);
        assertEquals(StepVerdict.Reason.DECODE, r.code);
    }

    @Test
    public void unpresentedJustBelowTwoPercentPasses() {
        StepMetrics m = good();
        m.framesPresented = 9801;   // 1.99 % not presented
        assertTrue(eval(m).stable);
    }

    @Test
    public void unpresentedAtTwoPercentFails() {
        StepMetrics m = good();
        m.framesPresented = 9800;   // 2.00 %
        StepVerdict.Result r = eval(m);
        assertFalse(r.stable);
        assertEquals(StepVerdict.Reason.UNPRESENTED, r.code);
    }

    @Test
    public void rttAtBaselinePlusFifteenPasses() {
        StepMetrics m = good();
        m.rttP95Ms = 20;
        assertTrue(eval(m).stable);
    }

    @Test
    public void rttAboveBaselinePlusFifteenFails() {
        StepMetrics m = good();
        m.rttP95Ms = 20.5;
        StepVerdict.Result r = eval(m);
        assertFalse(r.stable);
        assertEquals(StepVerdict.Reason.RTT, r.code);
    }

    @Test
    public void rttIgnoredWithoutBaseline() {
        StepMetrics m = good();
        m.rttP95Ms = 500;
        assertTrue(StepVerdict.evaluate(m, -1).stable);
    }

    @Test
    public void fpsAtNinetyPercentPasses() {
        StepMetrics m = good();
        m.fpsReceived = 108;
        assertTrue(eval(m).stable);
    }

    @Test
    public void fpsBelowNinetyPercentFails() {
        StepMetrics m = good();
        m.fpsReceived = 107.9;
        StepVerdict.Result r = eval(m);
        assertFalse(r.stable);
        assertEquals(StepVerdict.Reason.FPS, r.code);
    }

    @Test
    public void interruptedSessionFails() {
        StepMetrics m = good();
        m.interrupted = true;
        StepVerdict.Result r = eval(m);
        assertFalse(r.stable);
        assertEquals(StepVerdict.Reason.INTERRUPTED, r.code);
    }

    @Test
    public void noFramesFails() {
        StepMetrics m = good();
        m.framesReceived = 0;
        m.framesPresented = 0;
        StepVerdict.Result r = eval(m);
        assertFalse(r.stable);
        assertEquals(StepVerdict.Reason.NO_FRAMES, r.code);
    }
}
