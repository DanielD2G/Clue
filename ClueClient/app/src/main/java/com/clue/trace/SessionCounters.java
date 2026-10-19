package com.clue.trace;

import java.util.concurrent.atomic.AtomicLong;

/** Per-session event counters that are not per-frame (audio health for now). */
public final class SessionCounters {
    /** Audio packets dropped because too much audio was already pending. */
    public final AtomicLong audioPacketsDropped = new AtomicLong();
    /** Audio packets written to the output. */
    public final AtomicLong audioPacketsPlayed = new AtomicLong();
    /** Latest AudioTrack underrun count reported by the platform. */
    public final AtomicLong audioUnderruns = new AtomicLong();
}
