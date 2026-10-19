package com.clue.trace;

import android.content.Context;

/**
 * Owns the trace buffers for one streaming session. Hooks in the upstream code
 * fetch the active buffers through the static accessors, which return null
 * when no session is being traced, so a missing session costs one volatile read.
 */
public final class TraceSession {
    static final int FRAME_CAPACITY = 1 << 17;

    private static volatile TraceSession current;

    static final int INPUT_CAPACITY = 1 << 16;

    private final FrameTrace frames = new FrameTrace(FRAME_CAPACITY);
    private final InputTrace input = new InputTrace(INPUT_CAPACITY);
    private final SessionCounters counters = new SessionCounters();

    /**
     * Slot of the keyboard/mouse/touch event currently being dispatched on the UI thread,
     * or -1. Game marks it on entry; NvConnection marks it sent after handing it to
     * moonlight-common-c. Gamepad events are traced by ControllerHandler instead.
     */
    private static int pendingInputSlot = -1;

    private final Context appContext;
    private final java.util.Map<String, String> meta;

    private TraceSession(Context ctx, java.util.Map<String, String> meta) {
        this.appContext = ctx.getApplicationContext();
        this.meta = meta;
    }

    /** Starts tracing a session. {@code meta} is written to summary.txt (resolution, fps, ...). */
    public static TraceSession start(Context ctx, java.util.Map<String, String> meta) {
        TraceSession s = new TraceSession(ctx, meta);
        current = s;
        return s;
    }

    public static TraceSession current() {
        return current;
    }

    /** Frame trace of the active session, or null. */
    public static FrameTrace activeFrames() {
        TraceSession s = current;
        return s != null ? s.frames : null;
    }

    /** Input trace of the active session, or null. */
    public static InputTrace activeInput() {
        TraceSession s = current;
        return s != null ? s.input : null;
    }

    /** UI thread: a keyboard, mouse or touch event starts being handled. */
    public static void inputEvent(byte type, android.view.InputEvent event) {
        InputTrace it = activeInput();
        if (it == null) {
            pendingInputSlot = -1;
            return;
        }
        pendingInputSlot = it.onEvent(type, eventTimeNs(event));
    }

    /** Event time in CLOCK_MONOTONIC ns, at full precision on API 34+. */
    public static long eventTimeNs(android.view.InputEvent event) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            if (event instanceof android.view.MotionEvent) {
                return ((android.view.MotionEvent) event).getEventTimeNanos();
            }
            // KeyEvent has no public nanosecond accessor; keys fall back to ms precision.
        }
        return event.getEventTime() * 1_000_000L;
    }

    /** The pending event was handed to moonlight-common-c. Later sends for the same event are ignored. */
    public static void inputSent() {
        int slot = pendingInputSlot;
        if (slot < 0) {
            return;
        }
        pendingInputSlot = -1;
        InputTrace it = activeInput();
        if (it != null) {
            it.onSent(slot, System.nanoTime());
        }
    }

    public FrameTrace frames() {
        return frames;
    }

    public InputTrace input() {
        return input;
    }

    public SessionCounters counters() {
        return counters;
    }

    /** Counters of the active session, or null. */
    public static SessionCounters activeCounters() {
        TraceSession s = current;
        return s != null ? s.counters : null;
    }

    public void stop() {
        if (current == this) {
            current = null;
        }
        logSummary();
        writeAsync();
    }

    /** Writes the traces to app-specific storage on a background thread. */
    private void writeAsync() {
        final java.io.File base = appContext.getExternalFilesDir("traces");
        if (base == null) {
            android.util.Log.w("ClueTrace", "no external files dir; trace not written");
            return;
        }
        final String name = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        new Thread(() -> {
            java.io.File dir = new java.io.File(base, name);
            boolean ok = TraceWriter.write(frames, input, counters, dir, meta);
            android.util.Log.i("ClueTrace", (ok ? "trace written to " : "trace write FAILED for ") + dir);
        }, "Clue trace writer").start();
    }

    /** One logcat line per session, so a trace can be sanity-checked without pulling files. */
    private void logSummary() {
        final long[] c = new long[4]; // presented, negative stage deltas, receive->present sum us, n
        frames.forEach((frameNumber, firstPacketUs, completeUs, hostLatency01ms, decodeInNs,
                        decodeOutNs, releaseNs, targetVsyncNs, presentNs) -> {
            if (presentNs > 0) {
                c[0]++;
                long toPresentUs = presentNs / 1000 - completeUs;
                if (completeUs < firstPacketUs || toPresentUs < 0
                        || (decodeOutNs > 0 && decodeOutNs < decodeInNs)) {
                    c[1]++;
                } else {
                    c[2] += toPresentUs;
                    c[3]++;
                }
            }
        });
        final long[] d = new long[3]; // completeUs->decodeIn (us), decodeIn->present (us), n
        frames.forEach((frameNumber, firstPacketUs, completeUs, hostLatency01ms, decodeInNs,
                        decodeOutNs, releaseNs, targetVsyncNs, presentNs) -> {
            if (presentNs > 0 && decodeInNs > 0) {
                d[0] += decodeInNs / 1000 - completeUs;
                d[1] += (presentNs - decodeInNs) / 1000;
                d[2]++;
            }
        });
        final long[] in = new long[4]; // events, sent, sum event->sent ns, max ns
        input.forEach((type, eventNs, sentNs) -> {
            in[0]++;
            if (sentNs > 0 && sentNs >= eventNs) {
                in[1]++;
                long dt = sentNs - eventNs;
                in[2] += dt;
                in[3] = Math.max(in[3], dt);
            }
        });
        android.util.Log.i("ClueTrace", "session audio played=" + counters.audioPacketsPlayed.get()
                + " dropped=" + counters.audioPacketsDropped.get()
                + " underruns=" + counters.audioUnderruns.get());
        android.util.Log.i("ClueTrace", "session input events=" + in[0] + " sent=" + in[1]
                + " avgEventToSentMs=" + (in[1] > 0 ? (in[2] / in[1]) / 1e6 : -1)
                + " maxEventToSentMs=" + in[3] / 1e6);
        android.util.Log.i("ClueTrace", "session frames=" + frames.size()
                + " presented=" + c[0]
                + " ptsMisses=" + frames.ptsMisses()
                + " negativeStages=" + c[1]
                + " avgCompleteToPresentMs=" + (c[3] > 0 ? (c[2] / c[3]) / 1000.0 : -1)
                + " avgCompleteToDecodeInMs=" + (d[2] > 0 ? (d[0] / d[2]) / 1000.0 : -1)
                + " avgDecodeInToPresentMs=" + (d[2] > 0 ? (d[1] / d[2]) / 1000.0 : -1));
    }
}
