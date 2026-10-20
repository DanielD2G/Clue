package com.limelight.ui;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.TextView;

import com.limelight.R;

// CLUE: shared bits of Clue's full-screen screens (launcher, settings, add PC)
public final class ClueScreen {
    private ClueScreen() {}

    // Wires clue_screen_header.xml: back button, title and the tappable B hint
    public static void setupHeader(final Activity activity, CharSequence title) {
        ((TextView) activity.findViewById(R.id.screen_title)).setText(title);
        View.OnClickListener back = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.onBackPressed();
            }
        };
        activity.findViewById(R.id.screen_back).setOnClickListener(back);
        activity.findViewById(R.id.screen_hint_b).setOnClickListener(back);
    }

    // Hides the system bars and drops the status bar padding UiHelper.notifyNewRootView() adds
    public static void applyImmersive(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, 0, 0, 0);
                return insets;
            }
        });
        content.setPadding(0, 0, 0, 0);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = activity.getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        else {
            activity.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
