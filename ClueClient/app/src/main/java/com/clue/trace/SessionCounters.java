package com.clue.trace;

import java.util.concurrent.atomic.AtomicLong;

/** Per-session event counters that are not per-frame (audio health for now). */
public final class SessionCounters {
    /**
     * Audio packets dropped: on AudioTrack because too much audio was already pending, on AAudio
     * because the ring was full.
     */
    public final AtomicLong audioPacketsDropped = new AtomicLong();
    /** Audio packets written to the output. */
    public final AtomicLong audioPacketsPlayed = new AtomicLong();
    /** Platform underrun count (AudioTrack), or ring underruns plus AAudio XRuns. */
    public final AtomicLong audioUnderruns = new AtomicLong();

    // AAudio path only (H4)
    /** AAudio XRuns (also included in audioUnderruns). */
    public final AtomicLong audioXruns = new AtomicLong();
    /** Single frames dropped or duplicated by drift control. */
    public final AtomicLong audioDriftFramesDropped = new AtomicLong();
    public final AtomicLong audioDriftFramesInserted = new AtomicLong();
    /** Latest AAudio write-to-DAC latency (us), from AAudioStream_getTimestamp; -1 if unknown. */
    public final AtomicLong audioHwLatencyUs = new AtomicLong(-1);
    /** Mean of the AAudio write-to-DAC latency samples (us) and how many there were. */
    public final AtomicLong audioHwLatencySumUs = new AtomicLong();
    public final AtomicLong audioHwLatencySamples = new AtomicLong();
    /**
     * Mean ring occupancy while playing (us), sampled by the AAudio callback over the whole
     * session: decoded audio waits this long before reaching AAudio.
     */
    public final AtomicLong audioRingUs = new AtomicLong(-1);
    /** Latest drift-control safety margin at the ring's lowest point, and mean-level setpoint (us). */
    public final AtomicLong audioRingTargetUs = new AtomicLong(-1);
    public final AtomicLong audioRingSetpointUs = new AtomicLong(-1);
    /** AAudio buffer size in frames (starts at 2 bursts, grows on XRuns). */
    public final AtomicLong audioBufferFrames = new AtomicLong();
    /** The same leaving out the first 3 s after the stream opens (start-up effects). */
    public final AtomicLong audioRingSteadyUs = new AtomicLong(-1);
    public final AtomicLong audioUnderrunsSteady = new AtomicLong();
    public final AtomicLong audioDriftFramesDroppedSteady = new AtomicLong();
    public final AtomicLong audioDriftFramesInsertedSteady = new AtomicLong();
    /** Start-up backlog discarded at once instead of drained by drift control (frames). */
    public final AtomicLong audioStartupTrimmedFrames = new AtomicLong();
    /** "aaudio-exclusive", "aaudio-shared" or "audiotrack". */
    public volatile String audioPath = "none";

    /** Decode-to-DAC latency (us): mean ring level plus mean AAudio latency; -1 if unknown. */
    public long audioOutputLatencyUs() {
        long n = audioHwLatencySamples.get();
        long ring = audioRingUs.get();
        if (n == 0 || ring < 0) {
            return -1;
        }
        return ring + audioHwLatencySumUs.get() / n;
    }

    /** Same with the steady-state ring mean; -1 if unknown. */
    public long audioOutputLatencySteadyUs() {
        long n = audioHwLatencySamples.get();
        long ring = audioRingSteadyUs.get();
        if (n == 0 || ring < 0) {
            return -1;
        }
        return ring + audioHwLatencySumUs.get() / n;
    }
}
