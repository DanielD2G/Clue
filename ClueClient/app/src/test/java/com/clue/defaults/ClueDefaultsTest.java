package com.clue.defaults;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClueDefaultsTest {
    @Test
    public void nativeResolutionIsLandscape() {
        assertEquals("2560x1600", ClueDefaults.nativeResolution(1600, 2560));
        assertEquals("2560x1600", ClueDefaults.nativeResolution(2560, 1600));
    }

    @Test
    public void referenceDefaults() {
        assertEquals("120", ClueDefaults.FPS);
        assertEquals(80000, ClueDefaults.BITRATE_KBPS);
        assertEquals("balanced", ClueDefaults.FRAME_PACING);
        assertTrue(ClueDefaults.PERF_OVERLAY);
    }
}
