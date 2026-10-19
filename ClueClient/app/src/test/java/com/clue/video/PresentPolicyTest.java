package com.clue.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PresentPolicyTest {
    private static final long T = 8_333_333L;
    private static final long HALF = T / 2;
    private static final long D0 = 100_000_000L;
    private static final long P0 = 110_000_000L;

    private static PresentPolicy newPolicy() {
        PresentPolicy p = new PresentPolicy(1_000_000L, 500_000L);
        p.onVsync(D0, P0, T);
        return p;
    }

    /** Deterministic jitter in [-1.2, +1.2] ms. */
    private static long jitter(int i) {
        long[] pattern = {0, 1_200_000L, -900_000L, 600_000L, -1_200_000L, 300_000L, 900_000L, -600_000L};
        return pattern[i % pattern.length];
    }

    @Test
    public void noVsyncReturnsZero() {
        assertEquals(0, new PresentPolicy(1_000_000L, 500_000L).targetFor(5, 0));
    }

    @Test
    public void firstFrameTargetsNextReachableVsyncHalfPeriodEarly() {
        assertEquals(P0 - HALF, newPolicy().targetFor(95_000_000L, 0));
    }

    @Test
    public void jitterStraddlingTheDeadlineStillMapsToConsecutiveVsyncs() {
        // Raw arrival + safety lands at D_i - 1 ms +/- 1.2 ms: a per-frame choice would alternate.
        PresentPolicy p = newPolicy();
        long last = 0;
        for (int i = 0; i < 400; i++) {
            long target = p.targetFor(D0 + i * T - 2_000_000L + jitter(i), i * T);
            if (i >= 40) {
                assertEquals("frame " + i, T, target - last);
            }
            last = target;
        }
    }

    @Test
    public void singleLateSpikeDoesNotRemap() {
        PresentPolicy p = newPolicy();
        for (int i = 0; i < 200; i++) {
            p.targetFor(D0 + i * T - 5_000_000L, i * T);
        }
        p.targetFor(D0 + 200 * T - 5_000_000L + 6_000_000L, 200 * T);
        for (int i = 201; i < 221; i++) {
            assertEquals("frame " + i, P0 + i * T - HALF, p.targetFor(D0 + i * T - 5_000_000L, i * T));
        }
    }

    @Test
    public void sourceFasterThanDisplayDropsOncePerCrossing() {
        PresentPolicy p = newPolicy();
        long sourcePeriod = T - 2_000L;
        long last = 0;
        int collisions = 0, gaps = 0;
        for (int i = 0; i < 6000; i++) {
            long target = p.targetFor(D0 + i * sourcePeriod - 4_000_000L, i * sourcePeriod);
            if (i > 0) {
                long diff = target - last;
                if (diff == 0) collisions++;
                if (diff >= 2 * T) gaps++;
            }
            last = target;
        }
        // 12 ms of drift crosses one or two vsync boundaries: one drop each, never a repeat.
        assertTrue("collisions=" + collisions, collisions >= 1 && collisions <= 2);
        assertEquals(0, gaps);
    }

    @Test
    public void halfRateSourceAdvancesTwoVsyncsPerFrame() {
        PresentPolicy p = newPolicy();
        long last = 0;
        for (int i = 0; i < 200; i++) {
            long target = p.targetFor(D0 + i * 2 * T - 4_000_000L + jitter(i), i * 2 * T);
            if (i >= 40) {
                assertEquals("frame " + i, 2 * T, target - last);
            }
            last = target;
        }
    }

    @Test
    public void skippedHostFramesKeepLatencyAndCadence() {
        // A game below the stream rate: the host skips every 4th frame.
        PresentPolicy p = newPolicy();
        long last = 0, lastHost = 0;
        for (int i = 0; i < 2000; i++) {
            if (i % 4 == 3) {
                continue;
            }
            long host = i * T;
            long now = D0 - 4_000_000L + host + jitter(i);
            long target = p.targetFor(now, host);
            if (i >= 40) {
                assertEquals("frame " + i, host - lastHost, target - last);
                assertTrue("too far ahead at " + i, target - now < 2 * T);
                assertTrue("margin " + p.marginNs(), p.marginNs() <= T);
            }
            last = target;
            lastHost = host;
        }
    }

    @Test
    public void networkStallsDoNotInflateTheMargin() {
        // Every 360 frames the network holds frames for 25 ms, then releases them in a burst.
        PresentPolicy p = newPolicy();
        long stallEnd = 0;
        for (int i = 0; i < 3000; i++) {
            long host = i * T;
            long now = D0 - 4_000_000L + host + jitter(i);
            if (i % 360 == 0) {
                stallEnd = now + 25_000_000L;
            }
            now = Math.max(now, stallEnd);
            long target = p.targetFor(now, host);
            if (i >= 300) {
                assertTrue("margin " + p.marginNs() + " at " + i, p.marginNs() <= 2_000_000L);
                assertTrue("too far ahead at " + i, target - now < 2 * T);
            }
        }
    }

    @Test
    public void panelAt60HzDoesNotRunAhead() {
        long t60 = 16_666_667L;
        PresentPolicy p = new PresentPolicy(1_000_000L, 500_000L);
        p.onVsync(D0, P0, t60);
        for (int i = 0; i < 600; i++) {
            long now = D0 + i * T - 4_000_000L + jitter(i);
            long target = p.targetFor(now, i * T);
            assertTrue("target " + (target - now) + " ns ahead at " + i, target - now < 40_000_000L);
        }
    }

    @Test
    public void hostTimestampJumpIsCappedToOneFrameAhead() {
        PresentPolicy p = newPolicy();
        for (int i = 0; i < 200; i++) {
            p.targetFor(D0 + i * T - 4_000_000L + jitter(i), i * T);
        }
        // Host time leaps 30 ms while arrivals keep their pace (below the resync threshold).
        for (int i = 200; i < 210; i++) {
            long now = D0 + i * T - 4_000_000L + jitter(i);
            long target = p.targetFor(now, i * T + 30_000_000L);
            assertTrue("target " + (target - now) + " ns ahead at " + i, target - now < 25_000_000L);
        }
    }

    @Test
    public void periodMismatchKeepsTargetsMonotonic() {
        PresentPolicy p = new PresentPolicy(1_000_000L, 500_000L);
        p.onVsync(D0, P0, 11_111_111L);
        long last = Long.MIN_VALUE;
        for (int i = 0; i < 300; i++) {
            long now = D0 + i * T;
            long target = p.targetFor(now, i * T);
            assertTrue("target went backwards at " + i, target >= last);
            assertTrue("target not in the future at " + i, target > now);
            last = target;
        }
    }

    @Test
    public void staleSnapshotStillTargetsFuture() {
        PresentPolicy p = newPolicy();
        long now = D0 + 10_000_000_000L;
        long target = p.targetFor(now, 0);
        assertTrue(target > now);
        assertTrue(target < now + 3 * T);
    }

    @Test
    public void pauseKeepsConsecutiveMapping() {
        PresentPolicy p = newPolicy();
        for (int i = 0; i < 100; i++) {
            p.targetFor(D0 + i * T - 5_000_000L, i * T);
        }
        int resume = 100 + 120; // one second later, host time moved on too
        long last = 0;
        for (int i = resume; i < resume + 50; i++) {
            long now = D0 + i * T - 5_000_000L;
            long target = p.targetFor(now, i * T);
            assertTrue(target > now);
            if (i > resume) {
                assertEquals("frame " + i, T, target - last);
            }
            last = target;
        }
    }

    @Test
    public void hostClockRestartResyncs() {
        PresentPolicy p = newPolicy();
        long last = 0;
        for (int i = 0; i < 100; i++) {
            last = p.targetFor(D0 + i * T - 5_000_000L, 5_000_000_000L + i * T);
        }
        for (int i = 100; i < 150; i++) {
            long now = D0 + i * T - 5_000_000L;
            long target = p.targetFor(now, (i - 100) * T);
            assertTrue(target > now);
            assertTrue(target >= last);
            last = target;
        }
    }

    @Test
    public void resetClearsState() {
        PresentPolicy p = newPolicy();
        for (int i = 0; i < 100; i++) {
            p.targetFor(D0 + i * T - 2_000_000L + jitter(i), i * T);
        }
        p.reset();
        assertEquals(PresentPolicy.INITIAL_MARGIN_NS, p.marginNs());
        assertEquals(P0 - HALF, p.targetFor(95_000_000L, 0));
    }
}
