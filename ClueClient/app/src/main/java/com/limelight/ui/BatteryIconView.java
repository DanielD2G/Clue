package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

// CLUE: small horizontal battery glyph for the launcher status area
public class BatteryIconView extends View {
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private int level = 100;
    private boolean charging;

    public BatteryIconView(Context context) {
        this(context, null);
    }

    public BatteryIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(1.6f * density);
        outlinePaint.setColor(0xE6FFFFFF);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setState(int level, boolean charging) {
        if (level != this.level || charging != this.charging) {
            this.level = Math.max(0, Math.min(100, level));
            this.charging = charging;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float stroke = outlinePaint.getStrokeWidth();
        float nubWidth = 2.5f * density;
        float w = getWidth() - nubWidth;
        float h = getHeight();
        float radius = 3f * density;

        // Body
        rect.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
        canvas.drawRoundRect(rect, radius, radius, outlinePaint);

        // Nub
        fillPaint.setColor(0xE6FFFFFF);
        rect.set(w, h * 0.3f, w + nubWidth, h * 0.7f);
        canvas.drawRoundRect(rect, density, density, fillPaint);

        // Charge level
        if (charging) {
            fillPaint.setColor(0xFF69F0AE);
        }
        else if (level <= 15) {
            fillPaint.setColor(0xFFFF5252);
        }
        else {
            fillPaint.setColor(0xFFFFFFFF);
        }
        float inset = stroke + 1.2f * density;
        float innerWidth = (w - 2 * inset) * level / 100f;
        if (innerWidth > 0) {
            rect.set(inset, inset, inset + innerWidth, h - inset);
            canvas.drawRoundRect(rect, radius / 2, radius / 2, fillPaint);
        }
    }
}
