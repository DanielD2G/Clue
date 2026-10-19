package com.clue.audio;

import com.clue.trace.SessionCounters;

/**
 * Selection and stats plumbing for the native AAudio output (H4, clue_aaudio.c).
 * Decoding and playback never pass through Java on that path; Java only opens/closes the
 * stream and polls its counters off the hot path.
 */
public final class ClueAudio {
    /** SharedPreferences boolean; also a PcView launch extra (--ez clue_aaudio true|false). */
    public static final String PREF = "clue_aaudio";

    /** AAudio before 8.1 had too many device bugs to be worth it. */
    public static final int MIN_SDK = 27;
    /** Positional surround masks (AAudioStreamBuilder_setChannelMask) arrived in Android 12L. */
    public static final int MIN_SDK_SURROUND = 32;

    // Layout of MoonBridge.clueAudioGetStats(long[]), mirrors CLUE_AUDIO_STAT_* in clue_aaudio.h
    public static final int STAT_PLAYED = 0;
    public static final int STAT_DROPPED = 1;
    public static final int STAT_UNDERRUNS = 2;
    public static final int STAT_XRUNS = 3;
    public static final int STAT_DRIFT_DROPPED = 4;
    public static final int STAT_DRIFT_INSERTED = 5;
    public static final int STAT_HW_LATENCY_US = 6;
    public static final int STAT_RING_MEAN_US = 7;
    public static final int STAT_BUFFER_FRAMES = 8;
    public static final int STAT_BURST_FRAMES = 9;
    public static final int STAT_EXCLUSIVE = 10;
    public static final int STAT_SAMPLE_RATE = 11;
    public static final int STAT_TARGET_FRAMES = 12;
    public static final int STAT_REOPENS = 13;
    public static final int STAT_FRAMES_LOST = 14;
    public static final int STAT_COUNT = 15;

    private ClueAudio() {
    }

    /** Default of the {@link #PREF} preference. */
    public static boolean defaultEnabled(int sdkInt) {
        return sdkInt >= MIN_SDK;
    }

    /**
     * Whether to try AAudio for this stream. Audio effects need an AudioTrack session, so they
     * keep the upstream path.
     */
    public static boolean shouldTry(int sdkInt, boolean prefEnabled, boolean enableAudioFx, int channelCount) {
        if (sdkInt < MIN_SDK || !prefEnabled || enableAudioFx) {
            return false;
        }
        return channelCount <= 2 || sdkInt >= MIN_SDK_SURROUND;
    }

    /** Copies one stats poll into the session counters. */
    public static void apply(long[] s, int n, SessionCounters c) {
        if (c == null || n < STAT_COUNT) {
            return;
        }
        c.audioPacketsPlayed.set(s[STAT_PLAYED]);
        c.audioPacketsDropped.set(s[STAT_DROPPED]);
        c.audioUnderruns.set(s[STAT_UNDERRUNS] + s[STAT_XRUNS]);
        c.audioXruns.set(s[STAT_XRUNS]);
        c.audioDriftFramesDropped.set(s[STAT_DRIFT_DROPPED]);
        c.audioDriftFramesInserted.set(s[STAT_DRIFT_INSERTED]);
        c.audioRingUs.set(s[STAT_RING_MEAN_US]);
        c.audioBufferFrames.set(s[STAT_BUFFER_FRAMES]);
        c.audioPath = s[STAT_EXCLUSIVE] != 0 ? "aaudio-exclusive" : "aaudio-shared";
        long hw = s[STAT_HW_LATENCY_US];
        // Ignore the stream's first moments and anything implausible (timestamps not valid yet)
        if (hw > 0 && hw < 1_000_000) {
            c.audioHwLatencyUs.set(hw);
            c.audioHwLatencySumUs.addAndGet(hw);
            c.audioHwLatencySamples.incrementAndGet();
        }
    }

    /** Polls the native stats into the active trace session twice a second while playing. */
    public static final class Poller extends Thread {
        private final long[] stats = new long[STAT_COUNT];
        private volatile boolean running = true;

        public Poller() {
            super("Clue audio stats");
            setDaemon(true);
            setPriority(Thread.MIN_PRIORITY);
        }

        /** Takes one sample now (any thread, not concurrently with itself running). */
        public synchronized void pollOnce() {
            int n = com.limelight.nvstream.jni.MoonBridge.clueAudioGetStats(stats);
            apply(stats, n, com.clue.trace.TraceSession.activeCounters());
        }

        @Override
        public void run() {
            while (running) {
                pollOnce();
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        public void shutdown() {
            running = false;
            interrupt();
            try {
                join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
