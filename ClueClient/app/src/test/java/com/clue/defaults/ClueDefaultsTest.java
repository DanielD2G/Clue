package com.clue.defaults;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
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
        assertEquals("clue", ClueDefaults.FRAME_PACING);
        assertTrue(ClueDefaults.PERF_OVERLAY);
    }

    @Test
    public void nativeFpsMatchesListEntries() {
        assertEquals("165", ClueDefaults.nativeFps(165.0f));   // Legion Tab TB323FU
        assertEquals("120", ClueDefaults.nativeFps(120.0f));   // POCO Pad
        assertEquals("144", ClueDefaults.nativeFps(144.0f));
        assertEquals("90", ClueDefaults.nativeFps(90.0f));
        assertEquals("60", ClueDefaults.nativeFps(60.0f));
    }

    @Test
    public void nativeFpsToleratesRoundedDownRates() {
        assertEquals("120", ClueDefaults.nativeFps(119.88f));
        assertEquals("60", ClueDefaults.nativeFps(59.94f));
        assertEquals("165", ClueDefaults.nativeFps(164.6f));
        assertEquals("120", ClueDefaults.nativeFps(118.5f));
    }

    @Test
    public void nativeFpsFallsToNextLowerEntry() {
        assertEquals("90", ClueDefaults.nativeFps(100.0f));
        assertEquals("144", ClueDefaults.nativeFps(160.0f));
        assertEquals("165", ClueDefaults.nativeFps(240.0f));
        assertEquals("30", ClueDefaults.nativeFps(50.0f));
    }

    @Test
    public void nativeFpsUnknownDisplayKeepsDefault() {
        assertEquals(ClueDefaults.FPS, ClueDefaults.nativeFps(0f));
        assertEquals(ClueDefaults.FPS, ClueDefaults.nativeFps(Float.NaN));
        assertEquals(ClueDefaults.FPS, ClueDefaults.nativeFps(20f));
    }

    @Test
    public void fpsMigrationMovesOldDefaultToNative() {
        assertEquals("165", ClueDefaults.migratedFps(null, "165"));
        assertEquals("165", ClueDefaults.migratedFps("120", "165"));
    }

    @Test
    public void fpsMigrationKeepsExplicitChoices() {
        assertNull(ClueDefaults.migratedFps("60", "165"));
        assertNull(ClueDefaults.migratedFps("90", "165"));
        assertNull(ClueDefaults.migratedFps("144", "165"));
        // POCO Pad: native is already 120, nothing to write
        assertNull(ClueDefaults.migratedFps("120", "120"));
    }
}
