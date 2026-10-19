// CLUE: pure audio logic for the AAudio path (no Android dependencies, unit tested natively).
//
// - CLUE_AUDIO_RING: single-producer/single-consumer lock-free ring of interleaved int16 frames.
//   The Opus decoder thread writes, the AAudio data callback reads.
// - CLUE_AUDIO_DRIFT: decides, per decoded packet, how many single frames to drop or duplicate
//   so the ring level stays put despite host/DAC clock drift and backlogs, and how much safety
//   margin (target) the ring keeps against underruns.
#pragma once

#include <stdatomic.h>
#include <stdint.h>

typedef struct {
    int16_t* buf;            // capacity * channels samples
    uint32_t capacity;       // frames, power of two
    uint32_t channels;
    atomic_uint head;        // total frames written (producer only); wraps
    atomic_uint tail;        // total frames read (consumer only); wraps
} CLUE_AUDIO_RING;

// buf must hold capacity * channels samples; capacity must be a power of two.
void ClueRingInit(CLUE_AUDIO_RING* r, int16_t* buf, uint32_t capacity, uint32_t channels);
// Frames currently queued, from either side (unsigned wrap is fine while fill <= capacity).
uint32_t ClueRingFill(CLUE_AUDIO_RING* r);
// Producer: writes up to `frames` frames, returns how many fit.
uint32_t ClueRingWrite(CLUE_AUDIO_RING* r, const int16_t* pcm, uint32_t frames);
// Consumer: reads up to `frames` frames into out, returns how many were available.
uint32_t ClueRingRead(CLUE_AUDIO_RING* r, int16_t* out, uint32_t frames);
// Consumer: discards everything queued.
void ClueRingFlush(CLUE_AUDIO_RING* r);

// Drift control. The ring level is sampled just before each packet is written. Per window
// (~1 s) it keeps the mean level and the lowest level. The controller steers a filtered mean
// towards setpoint = target + spread, where spread is a slow average of (mean - lowest): the
// jitter the ring has to absorb. The mean is robust to the odd late packet, so jitter does
// not make it chase; target is the margin left at the lowest point and only underruns raise it.
typedef struct {
    // Configuration (frames / packets)
    int32_t minTarget;       // initial and lowest target: one burst, at least 2 ms
    int32_t maxTarget;       // cap after repeated underruns (40 ms)
    int32_t raiseStep;       // target change per underrun / per decay (half a burst, >= 1 ms)
    int32_t deadBand;        // no correction while |mean - setpoint| <= this (>= 2 ms)
    int32_t windowPackets;   // packets per measurement window (~1 s)
    int32_t gracePackets;    // underruns this soon after (re)start do not raise the target (~2 s)
    int32_t minDecayPackets; // underrun-free packets before the target is lowered (~10 s)
    int32_t maxDecayPackets; // the decay interval doubles after a decay that underran (cap ~120 s)

    // State
    int32_t target;
    int32_t decayPackets;    // current decay interval
    int32_t cleanPackets;    // packets since the last underrun or decay
    int32_t sinceDecay;      // packets since the last decay (INT32_MAX if none)
    int32_t sinceStart;      // packets since init / restart (saturates)
    int32_t windowCount;
    int32_t windowMin;       // lowest level of the window, with this window's own corrections undone
    int64_t windowSum;       // sum of levels, same reference
    int32_t windowAdj;       // net frames dropped (> 0) or added (< 0) so far in this window
    int32_t haveFilter;
    int32_t meanF;           // filtered mean level, in the current reference (frames)
    int32_t spreadQ4;        // filtered (mean - lowest), x16
    int32_t pending;         // > 0 frames still to drop, < 0 frames still to duplicate
} CLUE_AUDIO_DRIFT;

void ClueDriftInit(CLUE_AUDIO_DRIFT* d, int32_t burstFrames, int32_t packetFrames, int32_t sampleRate);
// The stream was (re)started or the ring flushed: forget the filters and start a grace period.
void ClueDriftRestart(CLUE_AUDIO_DRIFT* d);
// The consumer ran dry. Outside the grace period this raises the target one step (up to the
// cap). Returns 1 if the target was raised.
int32_t ClueDriftOnUnderrun(CLUE_AUDIO_DRIFT* d);
// Called once per decoded packet with the ring fill seen just before writing it.
// Returns the number of frames to drop (> 0) or duplicate (< 0) in this packet; never more
// than 4, never more than packetFrames / 8, and never both signs within one window.
int32_t ClueDriftOnPacket(CLUE_AUDIO_DRIFT* d, int32_t fillFrames, int32_t packetFrames);
// Ring level the consumer waits for before (re)starting playback.
int32_t ClueDriftPrimeFrames(const CLUE_AUDIO_DRIFT* d, int32_t packetFrames);
// Current setpoint of the mean level (target + spread), for stats.
int32_t ClueDriftSetpoint(const CLUE_AUDIO_DRIFT* d);

// Applies a drift decision to one packet: adj > 0 merges adj pairs of adjacent frames into their
// average, adj < 0 inserts -adj averaged frames, both spread evenly over the packet.
// out must hold frames + |adj| frames. Returns the number of frames written to out.
int32_t ClueDriftApply(const int16_t* in, int32_t frames, int32_t channels, int32_t adj, int16_t* out);
