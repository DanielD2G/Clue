package com.clue.menu;

/**
 * Artemis' gamepad shortcut for the game menu: tap Start, then press and hold Start again.
 * <p>
 * The second press must come within {@link #QUICK_MENU_FIRST_STAGE_MS} of the first release and
 * be held longer than the long press that otherwise toggles mouse emulation.
 */
public final class ControllerMenuCombo {
    // Artemis ControllerHandler.QUICK_MENU_FIRST_STAGE_MS
    public static final int QUICK_MENU_FIRST_STAGE_MS = 200;

    private ControllerMenuCombo() {}

    /** On Start down: does this press arm the menu (it follows a quick tap)? */
    public static boolean isBackMenuPending(long startDownTime, long lastStartUpTime) {
        return startDownTime - lastStartUpTime <= QUICK_MENU_FIRST_STAGE_MS;
    }

    /** On Start up: was the press long enough for the long-press actions? */
    public static boolean isLongPress(long startUpTime, long startDownTime, int longPressMs) {
        return startUpTime - startDownTime > longPressMs;
    }
}
