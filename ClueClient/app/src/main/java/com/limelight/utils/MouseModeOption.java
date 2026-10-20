// CLUE: ported verbatim from Artemis (ClassicOldSong/moonlight-android, GPL-3.0).
package com.limelight.utils;

public class MouseModeOption {
    public final int index;
    public final String label;

    public MouseModeOption(int index, String label) {
        this.index = index;
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
