package com.clue.trace;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TraceStatsTest {
    private static final long VSYNC_NS = 8_333_333L;

    @Test
    public void percentilesNearestRank() {
        FrameTrace ft = new FrameTrace(256);
        for (int n = 1; n <= 100; n++) {
            long pts = 1000L * n;
            ft.onReceived(n, 0, 0, 0);
            ft.onDecodeInput(n, pts, 1_000_000_000L);
            ft.onDecodeOutput(pts, 1_000_000_000L + n * 1_000_000L); // decode = n ms
        }
        TraceStats s = new TraceStats(240);
        s.compute(ft, new InputTrace(8), VSYNC_NS);
        assertEquals(50.0, s.decodeP50(), 1e-9);
        assertEquals(99.0, s.decodeP99(), 1e-9);
    }

    @Test
    public void repeatedCountsDoubleIntervals() {
        FrameTrace ft = new FrameTrace(256);
        long t = 1_000_000_000L;
        int frames = 101;
        for (int n = 1; n <= frames; n++) {
            long pts = 1000L * n;
            ft.onReceived(n, 0, 0, 0);
            ft.onDecodeInput(n, pts, t);
            t += (n == 50) ? 2 * VSYNC_NS : VSYNC_NS;
            ft.onPresented(pts, t);
        }
        TraceStats s = new TraceStats(240);
        s.compute(ft, new InputTrace(8), VSYNC_NS);
        assertEquals(100.0 / (frames - 1), s.repeatedPct(), 1e-6);
    }

    @Test
    public void ignoresMissingStages() {
        FrameTrace ft = new FrameTrace(16);
        ft.onReceived(1, 10, 20, 0);
        ft.onDecodeInput(1, 1000, 30_000);
        ft.onPresented(1000, 25_000); // present 5 ms after complete (20 us)
        ft.onReceived(2, 30, 40, 0);   // never presented
        TraceStats s = new TraceStats(240);
        s.compute(ft, new InputTrace(8), VSYNC_NS);
        assertEquals((25_000 - 20_000) / 1e6, s.toPresentP50(), 1e-9);
    }

    @Test
    public void inputSendPercentiles() {
        InputTrace it = new InputTrace(8);
        it.onSent(it.onEvent(InputTrace.GAMEPAD, 0), 2_000_000); // 2 ms
        it.onEvent(InputTrace.GAMEPAD, 5);                         // unsent, ignored
        TraceStats s = new TraceStats(240);
        s.compute(new FrameTrace(8), it, VSYNC_NS);
        assertEquals(2.0, s.inputSendP99(), 1e-9);
    }
}
