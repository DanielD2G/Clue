// CLUE: SPSC frame ring and drift control for the AAudio path, see clue_audio_ring.h.
#include "clue_audio_ring.h"

#include <limits.h>
#include <string.h>

void ClueRingInit(CLUE_AUDIO_RING* r, int16_t* buf, uint32_t capacity, uint32_t channels) {
    r->buf = buf;
    r->capacity = capacity;
    r->channels = channels;
    atomic_store_explicit(&r->head, 0, memory_order_relaxed);
    atomic_store_explicit(&r->tail, 0, memory_order_relaxed);
}

uint32_t ClueRingFill(CLUE_AUDIO_RING* r) {
    uint32_t tail = atomic_load_explicit(&r->tail, memory_order_acquire);
    uint32_t head = atomic_load_explicit(&r->head, memory_order_acquire);
    return head - tail;
}

// Copies `frames` frames between the ring at frame index `pos` and a linear buffer.
static void ringCopy(CLUE_AUDIO_RING* r, uint32_t pos, int16_t* linear, uint32_t frames, int toRing) {
    uint32_t mask = r->capacity - 1;
    uint32_t start = pos & mask;
    uint32_t first = r->capacity - start;
    if (first > frames) {
        first = frames;
    }
    size_t frameBytes = (size_t)r->channels * sizeof(int16_t);
    int16_t* ringA = r->buf + (size_t)start * r->channels;
    if (toRing) {
        memcpy(ringA, linear, first * frameBytes);
        memcpy(r->buf, linear + (size_t)first * r->channels, (frames - first) * frameBytes);
    } else {
        memcpy(linear, ringA, first * frameBytes);
        memcpy(linear + (size_t)first * r->channels, r->buf, (frames - first) * frameBytes);
    }
}

uint32_t ClueRingWrite(CLUE_AUDIO_RING* r, const int16_t* pcm, uint32_t frames) {
    uint32_t head = atomic_load_explicit(&r->head, memory_order_relaxed);
    uint32_t tail = atomic_load_explicit(&r->tail, memory_order_acquire);
    uint32_t space = r->capacity - (head - tail);
    uint32_t n = frames < space ? frames : space;
    if (n > 0) {
        ringCopy(r, head, (int16_t*)pcm, n, 1);
        atomic_store_explicit(&r->head, head + n, memory_order_release);
    }
    return n;
}

uint32_t ClueRingRead(CLUE_AUDIO_RING* r, int16_t* out, uint32_t frames) {
    uint32_t tail = atomic_load_explicit(&r->tail, memory_order_relaxed);
    uint32_t head = atomic_load_explicit(&r->head, memory_order_acquire);
    uint32_t avail = head - tail;
    uint32_t n = frames < avail ? frames : avail;
    if (n > 0) {
        ringCopy(r, tail, out, n, 0);
        atomic_store_explicit(&r->tail, tail + n, memory_order_release);
    }
    return n;
}

void ClueRingFlush(CLUE_AUDIO_RING* r) {
    uint32_t head = atomic_load_explicit(&r->head, memory_order_acquire);
    atomic_store_explicit(&r->tail, head, memory_order_release);
}

uint32_t ClueRingSkip(CLUE_AUDIO_RING* r, uint32_t frames) {
    uint32_t tail = atomic_load_explicit(&r->tail, memory_order_relaxed);
    uint32_t head = atomic_load_explicit(&r->head, memory_order_acquire);
    uint32_t avail = head - tail;
    uint32_t n = frames < avail ? frames : avail;
    atomic_store_explicit(&r->tail, tail + n, memory_order_release);
    return n;
}

static void resetWindow(CLUE_AUDIO_DRIFT* d) {
    d->windowCount = 0;
    d->windowMin = INT32_MAX;
    d->windowSum = 0;
    d->windowAdj = 0;
}

static int32_t max32(int32_t a, int32_t b) {
    return a > b ? a : b;
}

void ClueDriftInit(CLUE_AUDIO_DRIFT* d, int32_t burstFrames, int32_t packetFrames, int32_t sampleRate) {
    int32_t ms = sampleRate / 1000;
    memset(d, 0, sizeof(*d));
    d->burst = burstFrames;
    d->startExcess = max32(burstFrames, 20 * ms);
    d->minTarget = max32(burstFrames, 2 * ms);
    d->maxTarget = 40 * ms;
    d->raiseStep = max32(burstFrames / 2, ms);
    d->deadBand = max32(burstFrames / 2, 2 * ms);
    d->windowPackets = packetFrames > 0 && sampleRate / packetFrames > 0 ? sampleRate / packetFrames : 1;
    d->gracePackets = 2 * d->windowPackets;
    d->minDecayPackets = 10 * d->windowPackets;
    d->maxDecayPackets = 120 * d->windowPackets;
    d->decayPackets = d->minDecayPackets;
    d->target = d->minTarget;
    d->sinceDecay = INT32_MAX;
    ClueDriftRestart(d);
}

void ClueDriftRestart(CLUE_AUDIO_DRIFT* d) {
    d->sinceStart = 0;
    d->haveFilter = 0;
    d->pending = 0;
    resetWindow(d);
}

int32_t ClueDriftOnUnderrun(CLUE_AUDIO_DRIFT* d) {
    d->pending = 0;
    resetWindow(d);
    // Right after (re)start the ring is still filling and packets arrive in a burst: not jitter
    if (d->sinceStart < d->gracePackets) {
        return 0;
    }
    // Underran soon after giving latency back: wait twice as long before the next decay
    if (d->sinceDecay < d->decayPackets) {
        d->decayPackets = d->decayPackets * 2 > d->maxDecayPackets ? d->maxDecayPackets : d->decayPackets * 2;
    }
    d->cleanPackets = 0;
    d->target += d->raiseStep;
    if (d->target > d->maxTarget) {
        d->target = d->maxTarget;
    }
    return 1;
}

int32_t ClueDriftSetpoint(const CLUE_AUDIO_DRIFT* d) {
    return d->target + d->spreadQ4 / 16;
}

int32_t ClueDriftStartupTrim(CLUE_AUDIO_DRIFT* d, int32_t fillFrames, int32_t packetFrames) {
    if (d->sinceStart >= d->gracePackets) {
        return 0;
    }
    int32_t level = max32(ClueDriftSetpoint(d), d->target + packetFrames);
    // Start-up jitter alone can reach ~10 ms above the level; a queued backlog is far more
    if (fillFrames <= level + d->startExcess) {
        return 0;
    }
    // The backlog would skew the filters; measure again from the trimmed level
    d->haveFilter = 0;
    d->pending = 0;
    resetWindow(d);
    return fillFrames - (level + d->burst);
}

int32_t ClueDriftOnPacket(CLUE_AUDIO_DRIFT* d, int32_t fillFrames, int32_t packetFrames) {
    if (d->sinceStart < INT32_MAX) {
        d->sinceStart++;
    }
    if (d->sinceDecay < INT32_MAX) {
        d->sinceDecay++;
    }

    // Undo this window's own corrections, so the stats measure the incoming level
    int32_t level = fillFrames + d->windowAdj;
    if (level < d->windowMin) {
        d->windowMin = level;
    }
    d->windowSum += level;
    d->windowCount++;

    // After a stretch without underruns, give back some of the margin added for jitter
    if (++d->cleanPackets >= d->decayPackets) {
        d->cleanPackets = 0;
        if (d->target > d->minTarget) {
            d->target = max32(d->target - d->raiseStep, d->minTarget);
            d->sinceDecay = 0;
        }
    }

    if (d->windowCount >= d->windowPackets) {
        // Window stats in the current reference (after this window's corrections)
        int32_t meanEnd = (int32_t)(d->windowSum / d->windowCount) - d->windowAdj;
        int32_t spread = (int32_t)(d->windowSum / d->windowCount) - d->windowMin;
        if (!d->haveFilter) {
            d->haveFilter = 1;
            d->meanF = meanEnd;
            d->spreadQ4 = spread * 16;
        } else {
            d->meanF += (meanEnd - d->meanF) / 2;
            d->spreadQ4 += (spread * 16 - d->spreadQ4) / 16;
        }
        // Plan to correct half of the error over the next window, in one direction only.
        // The plan is replaced, not accumulated, so it cannot overshoot.
        int32_t err = d->meanF - ClueDriftSetpoint(d);
        d->pending = (err > d->deadBand || err < -d->deadBand) ? err / 2 : 0;
        resetWindow(d);
    }

    if (d->pending == 0) {
        return 0;
    }
    int32_t mag = d->pending > 0 ? d->pending : -d->pending;
    // One frame per packet for drift (~0.4% at 5 ms packets), up to 4 to drain a real backlog.
    int32_t step = 1 + mag / 256;
    if (step > 4) {
        step = 4;
    }
    if (step > packetFrames / 8) {
        step = packetFrames / 8;
    }
    if (step > mag) {
        step = mag;
    }
    if (step <= 0) {
        return 0;
    }
    if (d->pending < 0) {
        step = -step;
    }
    d->pending -= step;
    d->windowAdj += step;
    d->meanF -= step;
    return step;
}

int32_t ClueDriftPrimeFrames(const CLUE_AUDIO_DRIFT* d, int32_t packetFrames) {
    return d->target + packetFrames;
}

int32_t ClueDriftApply(const int16_t* in, int32_t frames, int32_t channels, int32_t adj, int16_t* out) {
    size_t frameBytes = (size_t)channels * sizeof(int16_t);
    int32_t n = adj > 0 ? adj : -adj;
    if (n > frames / 8) {
        n = frames / 8;
    }
    if (n == 0) {
        memcpy(out, in, (size_t)frames * frameBytes);
        return frames;
    }

    int32_t spacing = frames / (n + 1);
    int32_t o = 0;
    int32_t k = 0;
    if (adj > 0) {
        // Merge frame i into the previous output frame at i = spacing, 2*spacing, ...
        int32_t next = spacing;
        for (int32_t i = 0; i < frames; i++) {
            const int16_t* src = in + (size_t)i * channels;
            if (k < n && i == next) {
                int16_t* dst = out + (size_t)(o - 1) * channels;
                for (int32_t c = 0; c < channels; c++) {
                    dst[c] = (int16_t)(((int32_t)dst[c] + (int32_t)src[c]) / 2);
                }
                k++;
                next = spacing * (k + 1);
            } else {
                memcpy(out + (size_t)o * channels, src, frameBytes);
                o++;
            }
        }
    } else {
        // After frame i = spacing - 1, 2*spacing - 1, ... insert the average of i and i + 1.
        int32_t next = spacing - 1;
        for (int32_t i = 0; i < frames; i++) {
            const int16_t* src = in + (size_t)i * channels;
            memcpy(out + (size_t)o * channels, src, frameBytes);
            o++;
            if (k < n && i == next) {
                const int16_t* nxt = src + channels;
                int16_t* dst = out + (size_t)o * channels;
                for (int32_t c = 0; c < channels; c++) {
                    dst[c] = (int16_t)(((int32_t)src[c] + (int32_t)nxt[c]) / 2);
                }
                o++;
                k++;
                next = spacing * (k + 1) - 1;
            }
        }
    }
    return o;
}
