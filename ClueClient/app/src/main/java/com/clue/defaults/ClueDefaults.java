package com.clue.defaults;

/**
 * Factory defaults tuned for Clue's reference hardware (POCO Pad, 2560x1600 @ 120 Hz)
 * streaming from Vibepollo. They apply only when the user has not set the preference.
 */
public final class ClueDefaults {
    public static final String FPS = "120";
    public static final int BITRATE_KBPS = 80000;
    public static final String FRAME_PACING = "balanced";
    public static final boolean PERF_OVERLAY = true;

    private ClueDefaults() {}

    /** Native resolution string in landscape orientation, e.g. "2560x1600". */
    public static String nativeResolution(int displayWidth, int displayHeight) {
        int longSide = Math.max(displayWidth, displayHeight);
        int shortSide = Math.min(displayWidth, displayHeight);
        return longSide + "x" + shortSide;
    }
}
