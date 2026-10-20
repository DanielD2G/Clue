package com.limelight.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.preferences.StreamSettings;

// CLUE: shared chrome of the console-style launcher (activity_launcher.xml): top bar tabs,
// blurred cover background, info row, button hints and immersive mode. PcView is the
// Computers tab and AppView the Library tab; Settings opens StreamSettings.
public class LauncherUi {
    public static final int TAB_LIBRARY = 0;
    public static final int TAB_COMPUTERS = 1;
    public static final int TAB_SETTINGS = 2;
    private static final int TAB_COUNT = 3;

    public interface Host {
        // Only called for tabs other than the active one, and never for TAB_SETTINGS
        void onTabRequested(int tab);
    }

    private static final int BACKGROUND_ART_WIDTH = 128;
    private static final long BACKGROUND_FADE_MS = 280;

    private final Activity activity;
    private final int activeTab;
    private final Host host;

    private final View[] tabs = new View[TAB_COUNT];
    private final CoverCarousel carousel;
    private final View info;
    private final TextView title;
    private final TextView chip;
    private final View empty;
    private final View emptyProgress;
    private final TextView emptyText;
    private final TextView footer;
    private final TextView filter;
    private final ImageView[] backgrounds = new ImageView[2];
    private int frontBackground;
    private Bitmap backgroundSource;

    public LauncherUi(Activity activity, int activeTab, Host host) {
        this.activity = activity;
        this.activeTab = activeTab;
        this.host = host;

        tabs[TAB_LIBRARY] = activity.findViewById(R.id.tab_library);
        tabs[TAB_COMPUTERS] = activity.findViewById(R.id.tab_computers);
        tabs[TAB_SETTINGS] = activity.findViewById(R.id.tab_settings);
        carousel = activity.findViewById(R.id.launcher_carousel);
        info = activity.findViewById(R.id.launcher_info);
        title = activity.findViewById(R.id.launcher_title);
        chip = activity.findViewById(R.id.launcher_chip);
        empty = activity.findViewById(R.id.launcher_empty);
        emptyProgress = activity.findViewById(R.id.launcher_empty_progress);
        emptyText = activity.findViewById(R.id.launcher_empty_text);
        footer = activity.findViewById(R.id.launcher_footer);
        filter = activity.findViewById(R.id.launcher_filter);
        backgrounds[0] = activity.findViewById(R.id.launcher_bg_a);
        backgrounds[1] = activity.findViewById(R.id.launcher_bg_b);

        for (int i = 0; i < TAB_COUNT; i++) {
            final int tab = i;
            tabs[i].setSelected(i == activeTab);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    requestTab(tab);
                }
            });
        }
        activity.findViewById(activeTab == TAB_LIBRARY ? R.id.tab_library_label : R.id.tab_computers_label)
                .setVisibility(View.VISIBLE);
        carousel.setNextFocusUpId(tabs[activeTab].getId());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The source is a tiny bitmap, so the GPU blur on top is cheap and only redone on change
            RenderEffect blur = RenderEffect.createBlurEffect(72, 72, Shader.TileMode.CLAMP);
            backgrounds[0].setRenderEffect(blur);
            backgrounds[1].setRenderEffect(blur);
        }
    }

    public CoverCarousel getCarousel() {
        return carousel;
    }

    public void setAction(int viewId, boolean visible, View.OnClickListener listener) {
        View v = activity.findViewById(viewId);
        v.setVisibility(visible ? View.VISIBLE : View.GONE);
        v.setOnClickListener(listener);
    }

    public void setHint(int hintId, int labelId, String label, View.OnClickListener listener) {
        View hint = activity.findViewById(hintId);
        if (label == null) {
            hint.setVisibility(View.GONE);
            return;
        }
        hint.setVisibility(View.VISIBLE);
        ((TextView) activity.findViewById(labelId)).setText(label);
        hint.setOnClickListener(listener);
    }

    public void setFooter(CharSequence text, int iconRes) {
        footer.setText(text);
        if (iconRes != 0) {
            Drawable icon = activity.getResources().getDrawable(iconRes, activity.getTheme()).mutate();
            int size = Math.round(22 * activity.getResources().getDisplayMetrics().density);
            icon.setBounds(0, 0, size, size);
            icon.setAlpha(180);
            footer.setCompoundDrawablesRelative(icon, null, null, null);
        }
        else {
            footer.setCompoundDrawablesRelative(null, null, null, null);
        }
    }

    public void setFilterLabel(String text) {
        if (text == null || text.isEmpty()) {
            filter.setVisibility(View.GONE);
        }
        else {
            filter.setText(activity.getString(R.string.launcher_filter, text));
            filter.setVisibility(View.VISIBLE);
        }
    }

    public void setInfo(CharSequence titleText, CharSequence chipText, int chipIconRes) {
        if (titleText == null) {
            info.setVisibility(View.INVISIBLE);
            return;
        }
        info.setVisibility(View.VISIBLE);
        title.setText(titleText);
        if (chipText == null) {
            chip.setVisibility(View.GONE);
        }
        else {
            chip.setVisibility(View.VISIBLE);
            chip.setText(chipText);
            if (chipIconRes != 0) {
                Drawable icon = activity.getResources().getDrawable(chipIconRes, activity.getTheme()).mutate();
                int size = Math.round(16 * activity.getResources().getDisplayMetrics().density);
                icon.setBounds(0, 0, size, size);
                chip.setCompoundDrawablesRelative(icon, null, null, null);
            }
            else {
                chip.setCompoundDrawablesRelative(null, null, null, null);
            }
        }
    }

    public void setEmpty(String text, boolean loading) {
        if (text == null) {
            empty.setVisibility(View.GONE);
            return;
        }
        empty.setVisibility(View.VISIBLE);
        emptyProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        emptyText.setText(text);
    }

    // Puts the title next to the selected cover, under the row of small covers
    public void positionInfo() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) info.getLayoutParams();
        float density = activity.getResources().getDisplayMetrics().density;
        lp.leftMargin = carousel.getLeft() + carousel.getAnchorX() + carousel.getBigCoverWidth() + Math.round(28 * density);
        lp.topMargin = carousel.getTop() + carousel.getSmallCoverBottom() + Math.round(16 * density);
        lp.rightMargin = Math.round(48 * density);
        info.setLayoutParams(lp);
    }

    // Shows the given cover, blurred and dimmed, behind the whole launcher (null keeps the current one)
    public void setBackgroundArt(Drawable cover) {
        if (!(cover instanceof BitmapDrawable)) {
            return;
        }
        Bitmap source = ((BitmapDrawable) cover).getBitmap();
        if (source == null || source == backgroundSource || source.getWidth() < 64 || source.getHeight() < 64) {
            return;
        }
        backgroundSource = source;

        int height = Math.max(1, Math.round(BACKGROUND_ART_WIDTH * (float) source.getHeight() / source.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(source, BACKGROUND_ART_WIDTH, height, true);

        ImageView front = backgrounds[frontBackground];
        ImageView back = backgrounds[1 - frontBackground];
        back.animate().cancel();
        front.animate().cancel();
        back.setImageBitmap(small);
        back.setAlpha(0f);
        raiseBackground(back);
        back.animate().alpha(1f).setDuration(BACKGROUND_FADE_MS).start();
        front.animate().alpha(0f).setDuration(BACKGROUND_FADE_MS).start();
        frontBackground = 1 - frontBackground;
    }

    private void raiseBackground(ImageView layer) {
        // Put the incoming layer right above the outgoing one, still under the scrim and content
        FrameLayout root = activity.findViewById(R.id.launcher_root);
        root.removeView(layer);
        root.addView(layer, 1);
    }

    public boolean handleKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_L1:
                if (event.getRepeatCount() == 0 && activeTab > 0) {
                    requestTab(activeTab - 1);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (event.getRepeatCount() == 0 && activeTab < TAB_COUNT - 1) {
                    requestTab(activeTab + 1);
                }
                return true;
        }
        return false;
    }

    private void requestTab(int tab) {
        if (tab == activeTab) {
            carousel.requestFocus();
        }
        else if (tab == TAB_SETTINGS) {
            activity.startActivity(new Intent(activity, StreamSettings.class));
        }
        else {
            host.onTabRequested(tab);
        }
    }

    public void applyImmersive() {
        // UiHelper.notifyNewRootView() pads the content for the status bar; the launcher hides
        // the system bars and draws edge to edge instead
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
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
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
