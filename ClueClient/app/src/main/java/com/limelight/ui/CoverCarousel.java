package com.limelight.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.database.DataSetObserver;
import android.util.AttributeSet;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import com.limelight.R;

import java.util.ArrayList;
import java.util.BitSet;

// CLUE: console-style cover carousel for the launcher (PcView / AppView).
//
// It shows the items of a regular BaseAdapter in one row. The selected item is anchored at the
// left edge, drawn larger, with a glow and an outline. The carousel itself is the only focusable
// view: D-pad/stick left and right move the selection, A (or center/enter) activates it, Y (or
// menu) asks for its options. On touch, a swipe scrolls and snaps to the closest cover, a tap on
// another cover selects it, a tap on the selected cover activates it and a long press asks for
// its options.
//
// Items are bound lazily (only around the viewport), because CachedAppAssetLoader drops loads when
// too many are queued at once, and big libraries would otherwise inflate and load everything.
public class CoverCarousel extends HorizontalScrollView {
    public interface Listener {
        void onSelectionChanged(int position);
        void onItemActivated(int position);
        void onItemOptions(int position);
        // cover is null while the selected item has no cover art (yet)
        void onSelectedCoverChanged(int position, Drawable cover);
        void onMetricsChanged(CoverCarousel carousel);
    }

    private static final float BIG_HEIGHT_FRACTION = 0.54f; // of the window height
    private static final float SMALL_SCALE = 0.8f;
    private static final float COVER_ASPECT = 0.7f; // width / height
    private static final int BIND_MARGIN = 3;
    private static final long ANIM_MS = 170;
    private static final long SNAP_DELAY_MS = 70;

    private final Track track;
    private final int gap, anchor, glowPad, radius, stroke;
    private int smallW, smallH, bigW, bigH, viewportW;

    private BaseAdapter adapter;
    private boolean observerRegistered;
    private Listener listener;
    private final BitSet bound = new BitSet();
    private int selected = -1;
    private Object selectedItem;

    private ValueAnimator animator;
    private final ArrayList<View> animViews = new ArrayList<>();
    private int[] animFrom = new int[0];
    private int[] animTo = new int[0];
    private int scrollFrom, scrollTo;
    private int pendingScrollX = -1;

    private boolean touching, userScrolled;

    private Bitmap glowBitmap;
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF drawRect = new RectF();

    private final DataSetObserver observer = new DataSetObserver() {
        @Override
        public void onChanged() {
            syncWithAdapter(false);
        }

        @Override
        public void onInvalidated() {
            syncWithAdapter(true);
        }
    };

    private final ViewOutlineProvider cardOutline = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
        }
    };

    private final OnClickListener cardClick = new OnClickListener() {
        @Override
        public void onClick(View v) {
            int i = track.indexOfChild(v);
            if (i < 0) {
                return;
            }
            if (i == selected) {
                if (listener != null) {
                    listener.onItemActivated(i);
                }
            }
            else {
                setSelection(i, true);
            }
        }
    };

    private final OnLongClickListener cardLongClick = new OnLongClickListener() {
        @Override
        public boolean onLongClick(View v) {
            int i = track.indexOfChild(v);
            if (i < 0) {
                return false;
            }
            setSelection(i, true);
            if (listener != null) {
                listener.onItemOptions(i);
            }
            return true;
        }
    };

    private final CoverImageView.Listener coverListener = new CoverImageView.Listener() {
        @Override
        public void onCoverChanged(CoverImageView view) {
            if (selected >= 0 && view.getParent() == track.getChildAt(selected)) {
                notifySelectedCover();
            }
        }
    };

    private final Runnable snapRunnable = new Runnable() {
        @Override
        public void run() {
            if (touching || (animator != null && animator.isRunning())) {
                return;
            }
            userScrolled = false;

            // Snap to the cover whose resting position is closest to the current scroll
            int scrollX = getScrollX();
            int best = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int i = 0; i < track.getChildCount(); i++) {
                int distance = Math.abs(track.getChildAt(i).getLeft() - track.getPaddingLeft() - scrollX);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = i;
                }
            }
            if (best >= 0) {
                setSelection(best, true);
            }
        }
    };

    public CoverCarousel(Context context) {
        this(context, null);
    }

    public CoverCarousel(Context context, AttributeSet attrs) {
        super(context, attrs);

        gap = getResources().getDimensionPixelSize(R.dimen.clue_launcher_card_gap);
        anchor = getResources().getDimensionPixelSize(R.dimen.clue_launcher_edge);
        glowPad = getResources().getDimensionPixelSize(R.dimen.clue_launcher_glow);
        radius = getResources().getDimensionPixelSize(R.dimen.clue_launcher_card_radius);
        stroke = getResources().getDimensionPixelSize(R.dimen.clue_launcher_stroke);

        glowPaint.setColor(getResources().getColor(R.color.clue_launcher_glow));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(stroke);
        strokePaint.setColor(Color.WHITE);

        // The anchor math below assumes left-to-right
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(false);
        // Cards never take focus. This also keeps focus here when a key leaves touch mode,
        // instead of ViewRootImpl moving it to the first focusable view of the window.
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);

        track = new Track(context);
        addView(track, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setAdapter(BaseAdapter newAdapter) {
        unregisterObserver();
        adapter = newAdapter;
        selected = -1;
        selectedItem = null;
        if (isAttachedToWindow()) {
            registerObserver();
        }
        syncWithAdapter(true);
    }

    public void activateSelection() {
        if (selected >= 0 && listener != null) {
            listener.onItemActivated(selected);
        }
    }

    public int getSelection() {
        return selected;
    }

    public View getSelectedView() {
        if (selected < 0 || selected >= track.getChildCount()) {
            return null;
        }
        bind(selected);
        return track.getChildAt(selected);
    }

    // Layout metrics for LauncherUi, in pixels relative to this view
    public int getAnchorX() {
        return anchor;
    }

    public int getBigCoverWidth() {
        return bigW;
    }

    public int getSmallCoverBottom() {
        return glowPad + smallH;
    }

    public void setSelection(int index, boolean animate) {
        if (adapter == null || track.getChildCount() == 0) {
            return;
        }
        index = Math.max(0, Math.min(index, track.getChildCount() - 1));

        boolean changed = index != selected;
        selected = index;
        selectedItem = adapter.getItem(index);

        bind(index);
        startTransition(animate);

        if (changed && listener != null) {
            listener.onSelectionChanged(index);
            notifySelectedCover();
        }
        track.invalidate();
    }

    private void registerObserver() {
        if (adapter != null && !observerRegistered) {
            adapter.registerDataSetObserver(observer);
            observerRegistered = true;
        }
    }

    private void unregisterObserver() {
        if (adapter != null && observerRegistered) {
            adapter.unregisterDataSetObserver(observer);
            observerRegistered = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (adapter != null && !observerRegistered) {
            registerObserver();
            // We may have missed changes while detached
            syncWithAdapter(false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        unregisterObserver();
        removeCallbacks(snapRunnable);
        if (animator != null) {
            animator.cancel();
        }
    }

    private void syncWithAdapter(boolean dropViews) {
        if (animator != null) {
            animator.cancel();
        }

        if (dropViews) {
            track.removeAllViews();
        }

        int count = adapter != null ? adapter.getCount() : 0;
        while (track.getChildCount() > count) {
            track.removeViewAt(track.getChildCount() - 1);
        }
        while (track.getChildCount() < count) {
            track.addView(new Placeholder(getContext()), new LinearLayout.LayoutParams(smallW, smallH));
        }
        bound.clear();

        // Keep the same item selected if it is still there
        int newSelection = -1;
        if (count > 0) {
            if (selectedItem != null) {
                for (int i = 0; i < count; i++) {
                    if (adapter.getItem(i) == selectedItem) {
                        newSelection = i;
                        break;
                    }
                }
            }
            if (newSelection < 0) {
                newSelection = Math.max(0, Math.min(selected, count - 1));
            }
        }
        selected = newSelection;
        selectedItem = newSelection >= 0 ? adapter.getItem(newSelection) : null;

        for (int i = 0; i < count; i++) {
            applyRestingSize(track.getChildAt(i), i == selected);
        }
        if (!touching && selected >= 0) {
            setPendingScroll(scrollFor(selected));
        }
        track.requestLayout();

        bindWindow();

        if (listener != null) {
            listener.onSelectionChanged(selected);
            notifySelectedCover();
        }
    }

    private void notifySelectedCover() {
        if (listener == null) {
            return;
        }
        Drawable cover = null;
        View card = selected >= 0 && selected < track.getChildCount() ? track.getChildAt(selected) : null;
        if (card != null) {
            View image = card.findViewById(R.id.grid_image);
            if (image instanceof CoverImageView) {
                cover = ((CoverImageView) image).getDrawable();
            }
        }
        listener.onSelectedCoverChanged(selected, cover);
    }

    private int scrollFor(int index) {
        return Math.max(0, index) * (smallW + gap);
    }

    private void setPendingScroll(int x) {
        pendingScrollX = x;
        requestLayout();
    }

    private void applyRestingSize(View child, boolean big) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
        lp.width = big ? bigW : smallW;
        lp.height = big ? bigH : smallH;
        lp.rightMargin = gap;
    }

    private void bindWindow() {
        if (adapter == null) {
            return;
        }
        int count = track.getChildCount();
        int pitch = smallW + gap;
        if (count == 0 || pitch <= 0) {
            return;
        }

        int scrollX = getScrollX();
        int first = Math.max(0, scrollX / pitch - BIND_MARGIN);
        int last = Math.min(count - 1, (scrollX + viewportW) / pitch + BIND_MARGIN);
        for (int i = first; i <= last; i++) {
            bind(i);
        }
        if (selected >= 0) {
            bind(selected);
        }
    }

    private void bind(int i) {
        if (adapter == null || bound.get(i) || i >= track.getChildCount()) {
            return;
        }

        View current = track.getChildAt(i);
        View convertView = current instanceof Placeholder ? null : current;
        View view = adapter.getView(i, convertView, track);
        if (view != current) {
            ViewGroup.LayoutParams lp = current.getLayoutParams();
            track.removeViewAt(i);
            track.addView(view, i, lp);
            view.setOutlineProvider(cardOutline);
            view.setClipToOutline(true);
            view.setOnClickListener(cardClick);
            view.setOnLongClickListener(cardLongClick);
            view.setFocusable(false);
            View image = view.findViewById(R.id.grid_image);
            if (image instanceof CoverImageView) {
                ((CoverImageView) image).setCoverListener(coverListener);
            }
        }
        bound.set(i);
    }

    private void startTransition(boolean animate) {
        if (animator != null) {
            animator.cancel();
        }

        // Every card whose size is not at rest animates from where it is now, so fast
        // repeated presses retarget smoothly instead of jumping.
        animViews.clear();
        int count = track.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = track.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            boolean big = i == selected;
            if (lp.width != (big ? bigW : smallW) || lp.height != (big ? bigH : smallH)) {
                animViews.add(child);
            }
        }
        if (animFrom.length < animViews.size() * 2) {
            animFrom = new int[animViews.size() * 2];
            animTo = new int[animViews.size() * 2];
        }
        for (int i = 0; i < animViews.size(); i++) {
            View child = animViews.get(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            boolean big = track.indexOfChild(child) == selected;
            animFrom[i * 2] = lp.width;
            animFrom[i * 2 + 1] = lp.height;
            animTo[i * 2] = big ? bigW : smallW;
            animTo[i * 2 + 1] = big ? bigH : smallH;
        }
        scrollFrom = getScrollX();
        scrollTo = scrollFor(selected);

        if (!animate || !isLaidOut() || bigW == 0) {
            applyTransition(1f);
            return;
        }

        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(ANIM_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                applyTransition((float) animation.getAnimatedValue());
            }
        });
        animator.start();
    }

    private void applyTransition(float f) {
        for (int i = 0; i < animViews.size(); i++) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) animViews.get(i).getLayoutParams();
            lp.width = Math.round(animFrom[i * 2] + (animTo[i * 2] - animFrom[i * 2]) * f);
            lp.height = Math.round(animFrom[i * 2 + 1] + (animTo[i * 2 + 1] - animFrom[i * 2 + 1]) * f);
        }
        pendingScrollX = Math.round(scrollFrom + (scrollTo - scrollFrom) * f);
        track.requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int big = Math.round(getResources().getDisplayMetrics().heightPixels * BIG_HEIGHT_FRACTION);
        if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            big = Math.min(big, MeasureSpec.getSize(heightMeasureSpec) - 2 * glowPad);
        }
        big = Math.max(big, 1);

        updateMetrics(width, big);

        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(big + 2 * glowPad, MeasureSpec.EXACTLY));
    }

    private void updateMetrics(int width, int big) {
        if (big == bigH && width == viewportW) {
            return;
        }
        if (animator != null) {
            animator.cancel();
        }

        bigH = big;
        bigW = Math.round(big * COVER_ASPECT);
        smallH = Math.round(big * SMALL_SCALE);
        smallW = Math.round(smallH * COVER_ASPECT);
        viewportW = width;

        // Right padding lets the last cover reach the anchor too
        track.setPadding(anchor, glowPad, Math.max(0, width - anchor - bigW), glowPad);
        for (int i = 0; i < track.getChildCount(); i++) {
            applyRestingSize(track.getChildAt(i), i == selected);
        }
        if (!touching) {
            pendingScrollX = scrollFor(selected);
        }
        buildGlow();

        post(new Runnable() {
            @Override
            public void run() {
                bindWindow();
                if (listener != null) {
                    listener.onMetricsChanged(CoverCarousel.this);
                }
            }
        });
    }

    private void buildGlow() {
        int w = bigW + 2 * glowPad;
        int h = bigH + 2 * glowPad;
        if (glowBitmap != null && glowBitmap.getWidth() == w && glowBitmap.getHeight() == h) {
            return;
        }
        if (glowBitmap != null) {
            glowBitmap.recycle();
        }

        // Rendered once in software (BlurMaskFilter), then just stretched over the selected card
        glowBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(glowBitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setMaskFilter(new BlurMaskFilter(glowPad * 0.7f, BlurMaskFilter.Blur.OUTER));
        canvas.drawRoundRect(new RectF(glowPad, glowPad, glowPad + bigW, glowPad + bigH), radius, radius, paint);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (pendingScrollX >= 0) {
            scrollTo(pendingScrollX, 0);
            pendingScrollX = -1;
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        bindWindow();
        if (userScrolled && !touching) {
            removeCallbacks(snapRunnable);
            postDelayed(snapRunnable, SNAP_DELAY_MS);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                removeCallbacks(snapRunnable);
                if (animator != null && animator.isRunning()) {
                    animator.end();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                userScrolled = true;
                removeCallbacks(snapRunnable);
                postDelayed(snapRunnable, SNAP_DELAY_MS);
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int count = track.getChildCount();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (selected > 0) {
                    setSelection(selected - 1, true);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (selected >= 0 && selected < count - 1) {
                    setSelection(selected + 1, true);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (event.getRepeatCount() == 0 && selected >= 0 && listener != null) {
                    listener.onItemActivated(selected);
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_MENU:
                if (event.getRepeatCount() == 0 && selected >= 0 && listener != null) {
                    listener.onItemOptions(selected);
                }
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean executeKeyEvent(KeyEvent event) {
        // Selection, not free scrolling, is what moves the carousel with keys
        return false;
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        track.invalidate();
    }

    @Override
    protected ContextMenu.ContextMenuInfo getContextMenuInfo() {
        View view = getSelectedView();
        if (view == null) {
            return null;
        }
        return new AdapterView.AdapterContextMenuInfo(view, selected, adapter.getItemId(selected));
    }

    private static class Placeholder extends View {
        Placeholder(Context context) {
            super(context);
        }
    }

    private class Track extends LinearLayout {
        Track(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setBaselineAligned(false);
            setClipChildren(false);
            setClipToPadding(false);
            setLayoutDirection(LAYOUT_DIRECTION_LTR);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            View card = selected >= 0 && selected < getChildCount() ? getChildAt(selected) : null;
            float emphasis = CoverCarousel.this.hasFocus() ? 1f : 0.4f;

            if (card != null && glowBitmap != null) {
                drawRect.set(card.getLeft() - glowPad, card.getTop() - glowPad,
                        card.getRight() + glowPad, card.getBottom() + glowPad);
                glowPaint.setAlpha(Math.round(200 * emphasis));
                canvas.drawBitmap(glowBitmap, null, drawRect, glowPaint);
            }

            super.dispatchDraw(canvas);

            if (card != null) {
                float half = stroke / 2f;
                drawRect.set(card.getLeft() + half, card.getTop() + half,
                        card.getRight() - half, card.getBottom() - half);
                strokePaint.setAlpha(Math.round(255 * emphasis));
                canvas.drawRoundRect(drawRect, radius - half, radius - half, strokePaint);
            }
        }
    }
}
