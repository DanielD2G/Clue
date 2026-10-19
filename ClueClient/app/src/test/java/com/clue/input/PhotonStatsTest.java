package com.clue.input;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PhotonStatsTest {
    @Test
    public void percentilesIgnoreMisses() {
        long[] press = {1_000_000_000L, 2_000_000_000L, 3_000_000_000L};
        long[] detect = {1_040_000_000L, 0, 3_060_000_000L};
        PhotonTest.Stats s = PhotonTest.stats(press, detect, 3);
        assertEquals(2, s.n);
        assertEquals(1, s.misses);
        assertEquals(40.0, s.p50Ms, 1e-9);
        assertEquals(60.0, s.p99Ms, 1e-9);
    }

    @Test
    public void emptyRunHasNoSamples() {
        PhotonTest.Stats s = PhotonTest.stats(new long[0], new long[0], 0);
        assertEquals(0, s.n);
        assertEquals(0, s.misses);
    }
}
