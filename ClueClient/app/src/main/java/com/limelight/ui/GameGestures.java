package com.limelight.ui;

import com.limelight.binding.input.GameInputDevice;

public interface GameGestures {
    void toggleKeyboard();

    // CLUE: game menu entry point for input devices (from Artemis)
    default void showGameMenu(GameInputDevice device) {}
}
