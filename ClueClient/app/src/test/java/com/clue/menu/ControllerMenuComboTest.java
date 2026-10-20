package com.clue.menu;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ControllerMenuComboTest {
    private static final int LONG_PRESS_MS = 750; // ControllerHandler.START_DOWN_TIME_MOUSE_MODE_MS

    @Test
    public void pressRightAfterATapArmsTheMenu() {
        long firstUp = 10_000;
        assertTrue(ControllerMenuCombo.isBackMenuPending(firstUp + 150, firstUp));
        assertTrue(ControllerMenuCombo.isBackMenuPending(firstUp + 200, firstUp));
    }

    @Test
    public void lateOrFirstPressDoesNotArmTheMenu() {
        long firstUp = 10_000;
        assertFalse(ControllerMenuCombo.isBackMenuPending(firstUp + 201, firstUp));
        // First Start press of the session (no previous release)
        assertFalse(ControllerMenuCombo.isBackMenuPending(10_000, 0));
    }

    @Test
    public void longPressThreshold() {
        assertFalse(ControllerMenuCombo.isLongPress(1_750, 1_000, LONG_PRESS_MS));
        assertTrue(ControllerMenuCombo.isLongPress(1_751, 1_000, LONG_PRESS_MS));
    }
}
