package com.clue.video;

import android.annotation.TargetApi;
import android.os.Build;
import android.view.Choreographer;

/**
 * Feeds Choreographer frame timelines into a {@link PresentPolicy} on every vsync.
 *
 * start() and stop() must run on the looper thread that owns the callbacks. After each update it
 * runs {@code afterVsync}, which the renderer uses for its codec recovery handshake.
 */
@TargetApi(Build.VERSION_CODES.TIRAMISU)
public final class ClueVsyncSource implements Choreographer.VsyncCallback {
    private final PresentPolicy policy;
    private final Runnable afterVsync;
    private Choreographer choreographer;
    private volatile boolean running;

    public ClueVsyncSource(PresentPolicy policy, Runnable afterVsync) {
        this.policy = policy;
        this.afterVsync = afterVsync;
    }

    public void start() {
        choreographer = Choreographer.getInstance();
        running = true;
        choreographer.postVsyncCallback(this);
    }

    public void stop() {
        running = false;
        if (choreographer != null) {
            choreographer.removeVsyncCallback(this);
        }
    }

    @Override
    public void onVsync(Choreographer.FrameData data) {
        if (!running) {
            return;
        }

        Choreographer.FrameTimeline preferred = data.getPreferredFrameTimeline();
        Choreographer.FrameTimeline[] timelines = data.getFrameTimelines();
        long periodNs = 0;
        if (timelines.length >= 2) {
            periodNs = timelines[1].getExpectedPresentationTimeNanos() -
                    timelines[0].getExpectedPresentationTimeNanos();
        }
        // The preferred timeline budgets for app rendering (on the POCO Pad it is one vsync later than
        // the earliest). A decoded buffer goes straight to SurfaceFlinger, so the earliest one is the
        // real floor; the policy extrapolates forward from it.
        Choreographer.FrameTimeline earliest = timelines.length > 0 ? timelines[0] : preferred;
        policy.onVsync(earliest.getDeadlineNanos(), earliest.getExpectedPresentationTimeNanos(), periodNs);

        com.clue.trace.PresenterTrace trace = com.clue.trace.TraceSession.activePresenter();
        if (trace != null) {
            trace.onVsync(System.nanoTime(), earliest.getDeadlineNanos(),
                    earliest.getExpectedPresentationTimeNanos(), periodNs);
        }

        afterVsync.run();
        choreographer.postVsyncCallback(this);
    }
}
