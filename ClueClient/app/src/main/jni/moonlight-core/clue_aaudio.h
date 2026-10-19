// CLUE: AAudio output for decoded Opus audio (H4), see clue_aaudio.c
#pragma once

#include <stdbool.h>
#include <stdint.h>

// True while the AAudio path owns this session's audio (set by MoonBridge.clueAudioOpen).
bool clueAudioIsActive(void);
// Decode target for one Opus packet: samplesPerFrame * channelCount samples.
int16_t* clueAudioDecodeBuffer(void);
// Audio decoder thread: queues one decoded packet. Never blocks or allocates.
void clueAudioWrite(const int16_t* pcm, int frames);

// Layout of MoonBridge.clueAudioGetStats(long[]); mirrored by com.clue.audio.ClueAudio.
enum {
    CLUE_AUDIO_STAT_PLAYED = 0,          // packets queued whole
    CLUE_AUDIO_STAT_DROPPED,             // packets cut short because the ring was full
    CLUE_AUDIO_STAT_UNDERRUNS,           // callbacks that ran out of data (silence was played)
    CLUE_AUDIO_STAT_XRUNS,               // AAudio XRuns, summed over reopened streams
    CLUE_AUDIO_STAT_DRIFT_DROPPED,       // single frames merged away by drift control
    CLUE_AUDIO_STAT_DRIFT_INSERTED,      // single frames added by drift control
    CLUE_AUDIO_STAT_HW_LATENCY_US,       // AAudio write-to-DAC latency right now, -1 if unknown
    CLUE_AUDIO_STAT_RING_MEAN_US,        // mean ring occupancy while playing, over the session
    CLUE_AUDIO_STAT_BUFFER_FRAMES,       // AAudio buffer size
    CLUE_AUDIO_STAT_BURST_FRAMES,
    CLUE_AUDIO_STAT_EXCLUSIVE,           // 1 EXCLUSIVE (MMAP), 0 SHARED
    CLUE_AUDIO_STAT_SAMPLE_RATE,
    CLUE_AUDIO_STAT_TARGET_FRAMES,       // drift control target ring level
    CLUE_AUDIO_STAT_REOPENS,             // streams reopened after a device change
    CLUE_AUDIO_STAT_FRAMES_LOST,         // frames that did not fit in the ring
    CLUE_AUDIO_STAT_SETPOINT_FRAMES,     // drift control setpoint of the mean ring level (target + jitter)
    CLUE_AUDIO_STAT_COUNT
};
