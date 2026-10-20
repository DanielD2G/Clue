package com.clue.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TouchModeTest {
    @Test
    public void defaultIsMultiTouch() {
        assertEquals("0", TouchMode.DEFAULT);
        assertEquals(TouchMode.MULTI_TOUCH, TouchMode.parse(TouchMode.DEFAULT));
    }

    @Test
    public void valuesMatchArtemis() {
        assertEquals(0, TouchMode.MULTI_TOUCH);
        assertEquals(1, TouchMode.ABSOLUTE);
        assertEquals(2, TouchMode.TRACKPAD_NATURAL);
        assertEquals(3, TouchMode.TRACKPAD_GAMING);
        assertEquals(4, TouchMode.DISABLED);
        assertEquals(5, TouchMode.ABSOLUTE_SWAPPED);
        assertEquals("mouse_mode_list", TouchMode.PREF);
    }

    @Test
    public void parseFallsBackToMultiTouch() {
        assertEquals(TouchMode.TRACKPAD_GAMING, TouchMode.parse("3"));
        assertEquals(TouchMode.MULTI_TOUCH, TouchMode.parse(null));
        assertEquals(TouchMode.MULTI_TOUCH, TouchMode.parse(""));
        assertEquals(TouchMode.MULTI_TOUCH, TouchMode.parse("trackpad"));
    }

    @Test
    public void flagsPerMode() {
        // Multi-touch: native touch passthrough
        assertTrue(TouchMode.enableMultiTouchScreen(TouchMode.MULTI_TOUCH));
        assertFalse(TouchMode.touchscreenTrackpad(TouchMode.MULTI_TOUCH));

        // Normal mouse (and swapped): absolute cursor, no passthrough
        for (int mode : new int[] {TouchMode.ABSOLUTE, TouchMode.ABSOLUTE_SWAPPED}) {
            assertFalse(TouchMode.enableMultiTouchScreen(mode));
            assertFalse(TouchMode.touchscreenTrackpad(mode));
        }

        // Both trackpads are relative
        for (int mode : new int[] {TouchMode.TRACKPAD_NATURAL, TouchMode.TRACKPAD_GAMING}) {
            assertFalse(TouchMode.enableMultiTouchScreen(mode));
            assertTrue(TouchMode.touchscreenTrackpad(mode));
        }

        // Disabled and unknown values clear both flags, like Artemis's readPreferences()
        for (int mode : new int[] {TouchMode.DISABLED, 9, -1}) {
            assertFalse(TouchMode.enableMultiTouchScreen(mode));
            assertFalse(TouchMode.touchscreenTrackpad(mode));
        }
    }

    @Test
    public void migratesOnce() {
        assertEquals(TouchMode.DEFAULT, TouchMode.migrationValue(false));
        assertNull(TouchMode.migrationValue(true));
    }
}
