package com.clue.input;

import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.PixelCopy;
import android.view.SurfaceView;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.Arrays;

/**
 * Automatic button-to-frame latency test, run only when requested at launch.
 *
 * The host shows tools/host/gamepad-photon.html: a 256x256 square at the top left that turns white
 * while button A is held on any gamepad. This test presses A on controller 0, then reads a small
 * patch of that square back from the stream's SurfaceView with PixelCopy until it turns white.
 *
 * PixelCopy on a SurfaceView reads the last buffer queued for display, so the result is button ->
 * frame queued for display; the real photon time adds the queue->present delay that
 * tools/measure.sh reports from SurfaceFlinger.
 */
@TargetApi(Build.VERSION_CODES.N)
public final class PhotonTest {
    static final long PRESS_INTERVAL_MS = 400;
    static final long HOLD_MS = 100;
    static final long DETECT_TIMEOUT_NS = 300_000_000L;
    static final int PATCH = 16;
    static final int HOST_SQUARE_CENTER = 128; // host pixels, from the page

    public static final class Stats {
        public final int n, misses;
        public final double p50Ms, p95Ms, p99Ms;

        Stats(int n, int misses, double p50Ms, double p95Ms, double p99Ms) {
            this.n = n;
            this.misses = misses;
            this.p50Ms = p50Ms;
            this.p95Ms = p95Ms;
            this.p99Ms = p99Ms;
        }
    }

    /** Nearest-rank percentiles of detect - press over the first {@code count} presses; detect 0 is a miss. */
    public static Stats stats(long[] press, long[] detect, int count) {
        long[] lat = new long[count];
        int n = 0;
        for (int i = 0; i < count; i++) {
            if (detect[i] > 0) {
                lat[n++] = detect[i] - press[i];
            }
        }
        if (n == 0) {
            return new Stats(0, count, Double.NaN, Double.NaN, Double.NaN);
        }
        Arrays.sort(lat, 0, n);
        return new Stats(n, count - n, rank(lat, n, 0.50), rank(lat, n, 0.95), rank(lat, n, 0.99));
    }

    private static double rank(long[] sorted, int n, double p) {
        int idx = (int) Math.ceil(p * n) - 1;
        return sorted[Math.max(0, Math.min(n - 1, idx))] / 1e6;
    }

    public interface Listener {
        void onFinished(long[] press, long[] detect, int count, Stats stats);
    }

    private final NvConnection conn;
    private final SurfaceView view;
    private final int streamWidth, streamHeight;
    private final int presses;
    private final Listener listener;
    private final long[] pressNs, detectNs;
    private final Bitmap patch = Bitmap.createBitmap(PATCH, PATCH, Bitmap.Config.ARGB_8888);
    private HandlerThread thread;
    private Handler handler;
    private volatile boolean stopped;
    private int index;

    public PhotonTest(NvConnection conn, SurfaceView view, int streamWidth, int streamHeight,
                      int presses, Listener listener) {
        this.conn = conn;
        this.view = view;
        this.streamWidth = streamWidth;
        this.streamHeight = streamHeight;
        this.presses = presses;
        this.listener = listener;
        this.pressNs = new long[presses];
        this.detectNs = new long[presses];
    }

    /** Starts after {@code delayMs}, giving the stream time to settle. */
    public void start(long delayMs) {
        thread = new HandlerThread("Clue photon test");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.postDelayed(() -> {
            conn.sendControllerArrivalEvent((byte) 0, (short) 1, MoonBridge.LI_CTYPE_XBOX,
                    0xFFFF, (short) 0);
            handler.postDelayed(this::pressNext, 1000);
        }, delayMs);
    }

    public void stop() {
        stopped = true;
        if (thread != null) {
            thread.quitSafely();
        }
    }

    private void pressNext() {
        if (stopped) {
            return;
        }
        if (index == presses) {
            finish();
            return;
        }
        final int i = index;
        pressNs[i] = System.nanoTime();
        conn.sendControllerInput((short) 0, (short) 1, ControllerPacket.A_FLAG,
                (byte) 0, (byte) 0, (short) 0, (short) 0, (short) 0, (short) 0);
        handler.postDelayed(() -> conn.sendControllerInput((short) 0, (short) 1, 0,
                (byte) 0, (byte) 0, (short) 0, (short) 0, (short) 0, (short) 0), HOLD_MS);
        poll(i);
        handler.postDelayed(() -> {
            index++;
            pressNext();
        }, PRESS_INTERVAL_MS);
    }

    /** Copies the patch until it reads white (detected) or the timeout passes (miss). */
    private void poll(final int i) {
        if (stopped || detectNs[i] != 0 || System.nanoTime() - pressNs[i] > DETECT_TIMEOUT_NS) {
            return;
        }
        final long issued = System.nanoTime();
        int cx = HOST_SQUARE_CENTER * view.getWidth() / streamWidth;
        int cy = HOST_SQUARE_CENTER * view.getHeight() / streamHeight;
        Rect src = new Rect(cx - PATCH / 2, cy - PATCH / 2, cx + PATCH / 2, cy + PATCH / 2);
        try {
            PixelCopy.request(view, src, patch, result -> {
                if (result == PixelCopy.SUCCESS && isWhite(patch)) {
                    detectNs[i] = issued;
                } else {
                    poll(i);
                }
            }, handler);
        } catch (IllegalArgumentException e) {
            // Surface gone: the stream is ending
        }
    }

    private static boolean isWhite(Bitmap b) {
        int c = b.getPixel(PATCH / 2, PATCH / 2);
        int luma = (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
        return luma > 128;
    }

    private void finish() {
        Stats s = stats(pressNs, detectNs, presses);
        listener.onFinished(pressNs, detectNs, presses, s);
        stop();
    }
}
