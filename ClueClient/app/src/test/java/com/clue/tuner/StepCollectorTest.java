package com.clue.tuner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.clue.trace.FrameTrace;

import org.junit.Test;

public class StepCollectorTest {

    @Test
    public void percentileIsNearestRank() {
        long[] v = new long[100];
        for (int i = 0; i < 100; i++) {
            v[i] = 100 - i; // unsorted on purpose
        }
        assertEquals(95.0, StepCollector.percentile(v, 100, 0.95), 0);
        assertEquals(0.0, StepCollector.percentile(v, 0, 0.95), 0);
        long[] one = {7};
        assertEquals(7.0, StepCollector.percentile(one, 1, 0.95), 0);
    }

    @Test
    public void deltasAndRatesCoverOnlyTheMeasurementWindow() {
        StepCollector c = new StepCollector(256, 1024);
        long t0 = 10_000_000_000L;
        c.begin(t0, 500, 3, 490);
        for (int i = 1; i <= 20; i++) {
            c.addRtt(i); // 1..20 ms
        }
        long t1 = t0 + 12_000_000_000L; // 12 s later
        StepMetrics m = c.end(t1, 500 + 1440, 3 + 2, 490 + 1430, null, 50000, 120);

        assertEquals(50000, m.requestedKbps);
        assertEquals(1440, m.framesReceived);
        assertEquals(2, m.framesLost);
        assertEquals(1430, m.framesPresented);
        assertEquals(120, m.fpsTarget);
        assertEquals(120.0, m.fpsReceived, 1e-9);
        assertEquals(19.0, m.rttP95Ms, 0);
        assertFalse(m.interrupted);
    }

    @Test
    public void decodeP95ComesFromTraceFramesInsideTheWindow() {
        FrameTrace ft = new FrameTrace(1 << 10);
        long t0 = 1_000_000_000L;
        // A slow frame before the window must be ignored
        feed(ft, 1, t0 - 5_000_000L, 50_000_000L);
        for (int i = 0; i < 100; i++) {
            // decode time (i+1) ms
            feed(ft, 2 + i, t0 + i * 8_000_000L, (i + 1) * 1_000_000L);
        }
        // A slow frame after the window must be ignored too
        feed(ft, 200, t0 + 2_000_000_000L, 50_000_000L);

        StepCollector c = new StepCollector(16, 1024);
        c.begin(t0, 0, 0, 0);
        StepMetrics m = c.end(t0 + 1_000_000_000L, 100, 0, 100, ft, 20000, 120);
        assertEquals(95.0, m.decodeP95Ms, 1e-9);
    }

    @Test
    public void rttSamplesBeyondCapacityAreDropped() {
        StepCollector c = new StepCollector(4, 16);
        c.begin(0, 0, 0, 0);
        c.addRtt(1);
        c.addRtt(2);
        c.addRtt(3);
        c.addRtt(4);
        c.addRtt(1000);
        StepMetrics m = c.end(1_000_000_000L, 10, 0, 10, null, 20000, 60);
        assertTrue(m.rttP95Ms <= 4);
    }

    @Test
    public void interruptedMetricsKeepTheRequestedBitrate() {
        StepMetrics m = StepCollector.interrupted(74000);
        assertTrue(m.interrupted);
        assertEquals(74000, m.requestedKbps);
    }

    private static void feed(FrameTrace ft, int frame, long inNs, long decodeNs) {
        ft.onReceived(frame, inNs / 1000 - 100, inNs / 1000, 0);
        ft.onDecodeInput(frame, frame, inNs);
        ft.onDecodeOutput(frame, inNs + decodeNs);
    }
}
