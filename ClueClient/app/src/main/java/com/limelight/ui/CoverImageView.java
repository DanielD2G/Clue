package com.limelight.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

// CLUE: cover ImageView that reports when its art changes, so the launcher can refresh the
// blurred background once CachedAppAssetLoader finishes an async load for the selected cover.
public class CoverImageView extends ImageView {
    public interface Listener {
        void onCoverChanged(CoverImageView view);
    }

    private Listener listener;

    public CoverImageView(Context context) {
        super(context);
    }

    public CoverImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CoverImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setCoverListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        if (listener != null) {
            listener.onCoverChanged(this);
        }
    }
}
