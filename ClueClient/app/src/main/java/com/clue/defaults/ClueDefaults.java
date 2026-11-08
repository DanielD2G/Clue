package com.clue.defaults;

/**
 * Factory defaults tuned for Clue's reference hardware (POCO Pad, 2560x1600 @ 120 Hz)
 * streaming from Vibepollo. They apply only when the user has not set the preference.
 */
public final class ClueDefaults {
    public static final String FPS = "120";
    public static final int BITRATE_KBPS = 80000;
    /** Top of the bitrate slider (keep in sync with android:max in res/xml/preferences.xml). */
    public static final int MAX_BITRATE_KBPS = 300000;
    public static final String FRAME_PACING = "clue";
    public static final boolean PERF_OVERLAY = true;

    /** Frame rates offered by the settings list (keep in sync with fps_values in res/values/arrays.xml). */
    static final int[] FPS_VALUES = {30, 60, 90, 120, 144, 165};
    /** One-shot migration flag: installs still on the old 120 default move to the panel's rate. */
    public static final String FPS_NATIVE_MIGRATION_PREF = "clue_fps_native_v1";

    private ClueDefaults() {}

    /**
     * Default frame rate for a panel whose fastest mode is {@code maxRefreshHz}: the highest list
     * entry the panel can show, allowing 2 Hz of slack for rates reported a bit low (119.88, 164.6).
     * 165 on the Legion Tab TB323FU, 120 on the POCO Pad; {@link #FPS} when the rate is unknown.
     */
    public static String nativeFps(float maxRefreshHz) {
        if (!(maxRefreshHz > 0)) {
            return FPS;
        }
        int best = 0;
        for (int fps : FPS_VALUES) {
            if (fps <= maxRefreshHz + 2) {
                best = fps;
            }
        }
        return best > 0 ? Integer.toString(best) : FPS;
    }

    /**
     * Whether the native-fps migration can be marked done. While the system peak refresh rate
     * ({@code cappedHz}, 0 when unreadable) holds the panel below a faster list entry, it stays
     * pending so a later launch moves to the faster rate once the cap is lifted. Lenovo rewrites
     * that setting per foreground app (Clue gets 120 Hz on the TB323FU unless set to the top level).
     */
    public static boolean fpsMigrationFinal(float modeMaxHz, float cappedHz) {
        return !(cappedHz > 0) || nativeFps(cappedHz).equals(nativeFps(modeMaxHz));
    }

    /**
     * Value to write for the one-shot native-fps migration, or null to keep the current one.
     * "120" was written by setDefaultValues on every existing install, so it can't be told apart
     * from a choice and moves to the native rate together with a missing value.
     */
    public static String migratedFps(String current, String nativeFps) {
        if ((current == null || current.equals(FPS)) && !nativeFps.equals(current)) {
            return nativeFps;
        }
        return null;
    }

    /** Native resolution string in landscape orientation, e.g. "2560x1600". */
    public static String nativeResolution(int displayWidth, int displayHeight) {
        int longSide = Math.max(displayWidth, displayHeight);
        int shortSide = Math.min(displayWidth, displayHeight);
        return longSide + "x" + shortSide;
    }
}
