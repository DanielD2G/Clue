package com.clue.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.clue.trace.SessionCounters;

import org.junit.Test;

public class ClueAudioTest {
    @Test
    public void enabledByDefaultFromAndroid81() {
        assertFalse(ClueAudio.defaultEnabled(26));
        assertTrue(ClueAudio.defaultEnabled(27));
        assertTrue(ClueAudio.defaultEnabled(36));
    }

    @Test
    public void triesAAudioOnlyWhenEnabledSupportedAndWithoutAudioFx() {
        assertTrue(ClueAudio.shouldTry(36, true, false, 2));
        assertFalse(ClueAudio.shouldTry(36, false, false, 2));
        assertFalse(ClueAudio.shouldTry(36, true, true, 2));  // effects need an AudioTrack session
        assertFalse(ClueAudio.shouldTry(26, true, false, 2));
        assertTrue(ClueAudio.shouldTry(27, true, false, 2));
    }

    @Test
    public void surroundNeedsChannelMasks() {
        assertFalse(ClueAudio.shouldTry(31, true, false, 6));
        assertTrue(ClueAudio.shouldTry(32, true, false, 6));
        assertTrue(ClueAudio.shouldTry(36, true, false, 8));
    }

    private static long[] stats() {
        long[] s = new long[ClueAudio.STAT_COUNT];
        s[ClueAudio.STAT_PLAYED] = 12000;
        s[ClueAudio.STAT_DROPPED] = 1;
        s[ClueAudio.STAT_UNDERRUNS] = 2;
        s[ClueAudio.STAT_XRUNS] = 3;
        s[ClueAudio.STAT_DRIFT_DROPPED] = 40;
        s[ClueAudio.STAT_DRIFT_INSERTED] = 5;
        s[ClueAudio.STAT_HW_LATENCY_US] = 9000;
        s[ClueAudio.STAT_RING_MEAN_US] = 4500;
        s[ClueAudio.STAT_BUFFER_FRAMES] = 192;
        s[ClueAudio.STAT_EXCLUSIVE] = 1;
        s[ClueAudio.STAT_SAMPLE_RATE] = 48000;
        s[ClueAudio.STAT_TARGET_FRAMES] = 192;
        s[ClueAudio.STAT_SETPOINT_FRAMES] = 336;
        s[ClueAudio.STAT_RING_STEADY_US] = 3500;
        s[ClueAudio.STAT_UNDERRUNS_STEADY] = 1;
        s[ClueAudio.STAT_DRIFT_DROPPED_STEADY] = 30;
        s[ClueAudio.STAT_DRIFT_INSERTED_STEADY] = 4;
        s[ClueAudio.STAT_TRIMMED_FRAMES] = 6500;
        return s;
    }

    @Test
    public void statsMapOntoTheSessionCounters() {
        SessionCounters c = new SessionCounters();
        ClueAudio.apply(stats(), ClueAudio.STAT_COUNT, c);
        assertEquals(12000, c.audioPacketsPlayed.get());
        assertEquals(1, c.audioPacketsDropped.get());
        assertEquals(5, c.audioUnderruns.get()); // ring underruns + XRuns, both audible
        assertEquals(3, c.audioXruns.get());
        assertEquals(40, c.audioDriftFramesDropped.get());
        assertEquals(5, c.audioDriftFramesInserted.get());
        assertEquals(192, c.audioBufferFrames.get());
        assertEquals("aaudio-exclusive", c.audioPath);
        assertEquals(9000, c.audioHwLatencyUs.get());
        assertEquals(13500, c.audioOutputLatencyUs());
        assertEquals(4000, c.audioRingTargetUs.get());
        assertEquals(7000, c.audioRingSetpointUs.get());
        // Steady state leaves the first 3 s out; totals stay as they are
        assertEquals(3500, c.audioRingSteadyUs.get());
        assertEquals(1, c.audioUnderrunsSteady.get());
        assertEquals(30, c.audioDriftFramesDroppedSteady.get());
        assertEquals(4, c.audioDriftFramesInsertedSteady.get());
        assertEquals(6500, c.audioStartupTrimmedFrames.get());
        assertEquals(12500, c.audioOutputLatencySteadyUs());
    }

    @Test
    public void outputLatencyAveragesTheValidSamples() {
        SessionCounters c = new SessionCounters();
        assertEquals(-1, c.audioOutputLatencyUs());
        long[] s = stats();
        ClueAudio.apply(s, ClueAudio.STAT_COUNT, c);
        s[ClueAudio.STAT_HW_LATENCY_US] = 11000;
        ClueAudio.apply(s, ClueAudio.STAT_COUNT, c);
        s[ClueAudio.STAT_HW_LATENCY_US] = -1; // stream closed or timestamps not valid yet
        s[ClueAudio.STAT_EXCLUSIVE] = 0;
        ClueAudio.apply(s, ClueAudio.STAT_COUNT, c);
        assertEquals(2, c.audioHwLatencySamples.get());
        assertEquals(11000, c.audioHwLatencyUs.get());
        assertEquals(4500 + 10000, c.audioOutputLatencyUs());
        assertEquals("aaudio-shared", c.audioPath);
    }

    @Test
    public void shortOrMissingStatsAreIgnored() {
        SessionCounters c = new SessionCounters();
        ClueAudio.apply(stats(), ClueAudio.STAT_COUNT - 1, c);
        assertEquals(0, c.audioPacketsPlayed.get());
        ClueAudio.apply(stats(), ClueAudio.STAT_COUNT, null);
    }
}
