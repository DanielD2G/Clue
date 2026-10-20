package com.clue.tuner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BitrateSearchTest {

    /** Runs the search against a link that is stable at or below thresholdKbps. Returns steps used. */
    private static int run(BitrateSearch s, int thresholdKbps) {
        int steps = 0;
        for (int kbps = s.nextKbps(); kbps != -1; kbps = s.nextKbps()) {
            steps++;
            assertTrue("runaway search", steps < 100);
            s.report(kbps, kbps <= thresholdKbps);
        }
        return steps;
    }

    @Test
    public void allStableReachesCapAndEnds() {
        BitrateSearch s = new BitrateSearch();
        run(s, Integer.MAX_VALUE);
        assertEquals(BitrateSearch.CAP_KBPS, s.bestStableKbps());
        assertEquals(-1, s.nextKbps());
    }

    @Test
    public void firstStepUnstableEndsWithNothing() {
        BitrateSearch s = new BitrateSearch();
        assertEquals(BitrateSearch.START_KBPS, s.nextKbps());
        int steps = run(s, 0);
        assertEquals(1, steps);
        assertEquals(0, s.bestStableKbps());
        assertEquals(-1, s.nextKbps());
    }

    @Test
    public void thresholdIsBracketedWithinTwelveSteps() {
        BitrateSearch s = new BitrateSearch();
        int steps = run(s, 61000);
        int best = s.bestStableKbps();
        assertTrue("best " + best, best >= 55000 && best <= 61000);
        assertTrue("steps " + steps, steps <= 12);
    }

    @Test
    public void ascentUsesGeometricStepsRoundedToThousands() {
        BitrateSearch s = new BitrateSearch();
        int[] expected = {20000, 26000, 34000, 44000, 57000, 74000};
        for (int e : expected) {
            int k = s.nextKbps();
            assertEquals(e, k);
            s.report(k, true);
        }
    }

    @Test
    public void nextKbpsIsIdempotentUntilReported() {
        BitrateSearch s = new BitrateSearch();
        assertEquals(s.nextKbps(), s.nextKbps());
    }

    @Test
    public void suggestAppliesMarginRoundingAndDecoderCap() {
        assertEquals(50000, BitrateSearch.suggest(61000, 70000));
        assertEquals(70000, BitrateSearch.suggest(100000, 70000));
        assertEquals(0, BitrateSearch.suggest(0, 0));
        assertEquals(125000, BitrateSearch.suggest(150000, 0));
    }
}
