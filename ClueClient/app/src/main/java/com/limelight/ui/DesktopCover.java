package com.limelight.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import com.limelight.R;
import com.limelight.nvstream.http.NvApp;

// CLUE: the host's "Desktop" app gets a Clue cover instead of the host's generic box art.
// It is drawn once into a bitmap so it can also feed the launcher's blurred background.
public final class DesktopCover {
    private static final int WIDTH = 600;
    private static final int HEIGHT = 857; // same aspect as the carousel covers

    private static Bitmap cover;
    private static String coverLabel;

    private DesktopCover() {}

    // Sunshine, Apollo and Vibepollo all ship a "Desktop" app; some users rename it to the local word
    public static boolean isDesktop(NvApp app) {
        String name = app.getAppName().trim();
        return name.equalsIgnoreCase("Desktop") || name.equalsIgnoreCase("Escritorio");
    }

    public static synchronized Bitmap get(Context context) {
        String label = context.getString(R.string.launcher_desktop);
        if (cover != null && label.equals(coverLabel)) {
            return cover;
        }

        Bitmap bmp = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Deep blue to teal, with a soft light behind the glyph
        paint.setShader(new LinearGradient(0, 0, WIDTH, HEIGHT,
                0xFF2B3C9B, 0xFF0F6E7E, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, WIDTH, HEIGHT, paint);
        paint.setShader(new RadialGradient(WIDTH / 2f, HEIGHT * 0.4f, WIDTH * 0.75f,
                0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, WIDTH, HEIGHT, paint);
        paint.setShader(new LinearGradient(0, HEIGHT * 0.6f, 0, HEIGHT,
                0x00000000, 0x66000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, WIDTH, HEIGHT, paint);
        paint.setShader(null);

        Drawable glyph = context.getResources().getDrawable(R.drawable.ic_launcher_desktop, context.getTheme());
        int glyphSize = Math.round(WIDTH * 0.56f);
        int left = (WIDTH - glyphSize) / 2;
        int top = Math.round(HEIGHT * 0.40f - glyphSize / 2f);
        glyph.setBounds(left, top, left + glyphSize, top + glyphSize);
        glyph.draw(canvas);

        paint.setColor(0xFFFFFFFF);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setTextSize(WIDTH * 0.105f);
        paint.setShadowLayer(8, 0, 2, 0x66000000);
        canvas.drawText(label, WIDTH / 2f, HEIGHT * 0.80f, paint);

        cover = bmp;
        coverLabel = label;
        return bmp;
    }
}
