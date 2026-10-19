package com.clue.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PresentPolicyTest {
    private static final long T = 8_333_333L;
    private static final long D0 = 100_000_000L;
    private static final long P0 = 110_000_000L;

    private static PresentPolicy newPolicy() {
        return new PresentPolicy(1_000_000L, 1_500_000L);
    }

    private static long clean(int i) {
        return D0 + i * T - 5_000_000L;
    }

    private static long edge(int i) {
        return D0 + i * T - 1_200_000L;
    }

    /** Drives 120 frames with one edge arrival every 10 frames; returns the next frame index. */
    private static int driveToDepth1(PresentPolicy p) {
        p.onVsync(D0, P0, T);
        for (int i = 0; i < 120; i++) {
            p.targetFor(i % 10 == 9 ? edge(i) : clean(i));
        }
        return 120;
    }

    @Test
    public void noVsyncReturnsZero() {
        assertEquals(0, newPolicy().targetFor(5));
    }

    @Test
    public void picksNextAchievableTimeline() {
        PresentPolicy p = newPolicy();
        p.onVsync(D0, P0, T);
        assertEquals(P0, p.targetFor(95_000_000L));
        assertEquals(P0 + T, p.targetFor(99_500_000L));
    }

    @Test
    public void lateCallbackExtrapolates() {
        PresentPolicy p = newPolicy();
        p.onVsync(D0, P0, T);
        assertEquals(P0 + 4 * T, p.targetFor(D0 + 3 * T + 500_000L));
    }

    @Test
    public void cleanArrivalsStayAtDepth0() {
        PresentPolicy p = newPolicy();
        p.onVsync(D0, P0, T);
        for (int i = 0; i < 500; i++) {
            p.targetFor(clean(i));
        }
        assertEquals(0, p.depth());
        assertEquals(0, p.depthSwitches());
    }

    @Test
    public void edgeArrivalsSwitchToDepth1() {
        PresentPolicy p = newPolicy();
        int i = driveToDepth1(p);
        assertEquals(1, p.depth());
        assertEquals(P0 + (i + 1) * T, p.targetFor(clean(i)));
    }

    @Test
    public void quietWindowReturnsToDepth0() {
        PresentPolicy p = newPolicy();
        int start = driveToDepth1(p);
        for (int i = start; i < start + 240; i++) {
            p.targetFor(clean(i));
        }
        assertEquals(0, p.depth());
        assertEquals(2, p.depthSwitches());
    }

    @Test
    public void periodMismatchKeepsTargetsMonotonic() {
        PresentPolicy p = newPolicy();
        long period = 11_111_111L;
        p.onVsync(D0, P0, period);
        long last = Long.MIN_VALUE;
        for (int i = 0; i < 300; i++) {
            long now = D0 + i * 8_333_333L;
            long target = p.targetFor(now);
            assertTrue("target went backwards at " + i, target >= last);
            assertTrue("target not in the future at " + i, target > now);
            last = target;
        }
    }

    @Test
    public void staleSnapshotStillTargetsFuture() {
        PresentPolicy p = newPolicy();
        p.onVsync(D0, P0, T);
        long now = D0 + 10_000_000_000L;
        long target = p.targetFor(now);
        assertTrue(target > now);
        assertTrue(target < now + 3 * T);
    }

    @Test
    public void resetClearsState() {
        PresentPolicy p = newPolicy();
        driveToDepth1(p);
        assertEquals(1, p.depth());
        p.reset();
        assertEquals(0, p.depth());
        assertEquals(0, p.edgesInWindow());
        assertEquals(P0, p.targetFor(95_000_000L));
    }
}
