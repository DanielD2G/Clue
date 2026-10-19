// CLUE: pure audio logic for the AAudio path (no Android dependencies, unit tested natively).
//
// - CLUE_AUDIO_RING: single-producer/single-consumer lock-free ring of interleaved int16 frames.
//   The Opus decoder thread writes, the AAudio data callback reads.
// - CLUE_AUDIO_DRIFT: decides, per decoded packet, how many single frames to drop or duplicate
//   so the ring level stays at its target despite host/DAC clock drift and backlogs.
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

typedef struct {
    // Configuration (frames)
    int32_t minTarget;       // initial and lowest target
    int32_t maxTarget;       // cap after repeated underruns
    int32_t raiseStep;       // target increase per underrun
    int32_t hyst;            // dead band around the target
    int32_t windowPackets;   // packets per measurement window (about 1 s)
    int32_t decayPackets;    // underrun-free packets before the target is lowered one step

    // State
    int32_t target;          // desired ring level just before a packet is written
    int32_t windowCount;
    int32_t windowMin;       // lowest fill of the window, with this window's own corrections undone
    int32_t windowAdj;       // net frames dropped (> 0) or added (< 0) so far in this window
    int32_t pending;         // >0 frames still to drop, <0 frames still to duplicate
    int32_t cleanPackets;    // packets since the last underrun
} CLUE_AUDIO_DRIFT;

void ClueDriftInit(CLUE_AUDIO_DRIFT* d, int32_t burstFrames, int32_t packetFrames, int32_t sampleRate);
// The consumer ran dry: raise the target one step (up to the cap) and restart the window.
void ClueDriftOnUnderrun(CLUE_AUDIO_DRIFT* d);
// Called once per decoded packet with the ring fill seen just before writing it.
// Returns the number of frames to drop (> 0) or duplicate (< 0) in this packet; never more
// than a few frames, and never more than packetFrames / 8.
int32_t ClueDriftOnPacket(CLUE_AUDIO_DRIFT* d, int32_t fillFrames, int32_t packetFrames);
// Ring level the consumer waits for before (re)starting playback.
int32_t ClueDriftPrimeFrames(const CLUE_AUDIO_DRIFT* d, int32_t packetFrames);

// Applies a drift decision to one packet: adj > 0 merges adj pairs of adjacent frames into their
// average, adj < 0 inserts -adj averaged frames, both spread evenly over the packet.
// out must hold frames + |adj| frames. Returns the number of frames written to out.
int32_t ClueDriftApply(const int16_t* in, int32_t frames, int32_t channels, int32_t adj, int16_t* out);
