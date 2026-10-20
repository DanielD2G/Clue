package com.clue.input;

/**
 * Touchscreen modes ported from Artemis ("mouse_mode_list"). The values, the default and the
 * mapping to the two upstream flags follow Artemis's PreferenceConfiguration.readPreferences()
 * and Game.applyMouseMode().
 */
public final class TouchMode {
    public static final String PREF = "mouse_mode_list";
    public static final String REMEMBER_PREF = "checkbox_remember_mouse_mode";
    public static final String GESTURES_PREF = "checkbox_multi_touch_gestures";

    // One-shot migration away from upstream's "checkbox_touchscreen_trackpad"
    public static final String MIGRATION_PREF = "clue_touch_mode_v1";
    public static final String LEGACY_TRACKPAD_PREF = "checkbox_touchscreen_trackpad";

    public static final int MULTI_TOUCH = 0;
    public static final int ABSOLUTE = 1;          // "Normal mouse"
    public static final int TRACKPAD_NATURAL = 2;  // TrackpadContext
    public static final int TRACKPAD_GAMING = 3;   // RelativeTouchContext
    public static final int DISABLED = 4;
    public static final int ABSOLUTE_SWAPPED = 5;  // "Normal mouse with swapped buttons"

    public static final String DEFAULT = "0";

    private TouchMode() {}

    /** Artemis's Game.initMouseMode(): unparsable values fall back to Multi-touch. */
    public static int parse(String value) {
        if (value == null) {
            return MULTI_TOUCH;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return MULTI_TOUCH;
        }
    }

    /** PreferenceConfiguration.enableMultiTouchScreen for this mode. */
    public static boolean enableMultiTouchScreen(int mode) {
        return mode == MULTI_TOUCH;
    }

    /** PreferenceConfiguration.touchscreenTrackpad for this mode. */
    public static boolean touchscreenTrackpad(int mode) {
        return mode == TRACKPAD_NATURAL || mode == TRACKPAD_GAMING;
    }

    /**
     * The mode value to write for the one-shot migration, or null when the install was already
     * migrated. The old checkbox default cannot be told apart from an explicit choice, so
     * everyone moves to the Artemis default once.
     */
    public static String migrationValue(boolean alreadyMigrated) {
        return alreadyMigrated ? null : DEFAULT;
    }
}
