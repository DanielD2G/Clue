// Native tests for the AAudio path's ring and drift control (clue_audio_ring.c).
// Built for arm64 and run on the device by tools/native-test.sh.
#include <limits.h>
#include <stdio.h>
#include <string.h>

#include "clue_audio_ring.h"

static int failures;
#define CHECK(cond) do { if (!(cond)) { printf("  FAIL %s:%d: %s\n", __func__, __LINE__, #cond); failures++; } } while (0)

#define RATE 48000
#define PACKET 240 // 5 ms
#define BURST 96   // 2 ms

static void ringWrapsAndKeepsOrder(void) {
    int16_t buf[8 * 2];
    CLUE_AUDIO_RING r;
    ClueRingInit(&r, buf, 8, 2);
    int16_t in[6 * 2], out[8 * 2];
    for (int i = 0; i < 12; i++) in[i] = (int16_t)i;

    CHECK(ClueRingWrite(&r, in, 6) == 6);
    CHECK(ClueRingFill(&r) == 6);
    CHECK(ClueRingRead(&r, out, 4) == 4);
    CHECK(memcmp(out, in, 4 * 2 * sizeof(int16_t)) == 0);
    // Wraps around the end of the buffer
    CHECK(ClueRingWrite(&r, in, 6) == 6);
    CHECK(ClueRingFill(&r) == 8);
    CHECK(ClueRingRead(&r, out, 8) == 8);
    CHECK(memcmp(out, in + 4 * 2, 2 * 2 * sizeof(int16_t)) == 0);
    CHECK(memcmp(out + 2 * 2, in, 6 * 2 * sizeof(int16_t)) == 0);
    CHECK(ClueRingFill(&r) == 0);
}

static void ringFullTakesOnlyWhatFitsAndEmptyReadsNothing(void) {
    int16_t buf[8];
    CLUE_AUDIO_RING r;
    ClueRingInit(&r, buf, 8, 1);
    int16_t in[10] = { 0 }, out[10];
    CHECK(ClueRingRead(&r, out, 4) == 0);
    CHECK(ClueRingWrite(&r, in, 10) == 8);
    CHECK(ClueRingWrite(&r, in, 1) == 0);
    ClueRingFlush(&r);
    CHECK(ClueRingFill(&r) == 0);
    CHECK(ClueRingWrite(&r, in, 3) == 3);
}

static void ringCountersSurviveUnsignedWrap(void) {
    int16_t buf[4];
    CLUE_AUDIO_RING r;
    ClueRingInit(&r, buf, 4, 1);
    atomic_store(&r.head, UINT_MAX - 1);
    atomic_store(&r.tail, UINT_MAX - 1);
    int16_t in[3] = { 7, 8, 9 }, out[3];
    CHECK(ClueRingWrite(&r, in, 3) == 3);
    CHECK(ClueRingFill(&r) == 3);
    CHECK(ClueRingRead(&r, out, 3) == 3);
    CHECK(out[0] == 7 && out[1] == 8 && out[2] == 9);
}

static void driftStartsAtOneBurstOrTwoMs(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    CHECK(d.target == 96);
    CHECK(d.windowPackets == 200);
    CHECK(ClueDriftPrimeFrames(&d, PACKET) == 96 + PACKET);
    ClueDriftInit(&d, 48, PACKET, RATE);
    CHECK(d.target == 96);
    ClueDriftInit(&d, 192, PACKET, RATE);
    CHECK(d.target == 192);
}

// Runs one window with a constant fill; returns the sum of the decisions taken during it.
static int runWindow(CLUE_AUDIO_DRIFT* d, int fill) {
    int sum = 0;
    for (int i = 0; i < d->windowPackets; i++) {
        sum += ClueDriftOnPacket(d, fill, PACKET);
    }
    return sum;
}

typedef struct {
    int base;      // ring level before jitter; every decision moves it
    int maxStep;   // largest |decision|
    int drops;     // frames dropped
    int dups;      // frames duplicated
    int lastMin;   // lowest fill of the last full window
} SIM;

// Simulates `packets` packets. Packet k sees base + jitter(k). `driftEvery` != 0 moves the base by
// one frame every |driftEvery| packets (> 0: host clock faster than the DAC, the ring grows).
static void simulate(CLUE_AUDIO_DRIFT* d, SIM* s, int packets, int jitterRange, int driftEvery) {
    unsigned lcg = 12345;
    int windowMin = INT32_MAX;
    for (int k = 0; k < packets; k++) {
        int jitter = 0;
        if (jitterRange > 0) {
            lcg = lcg * 1103515245u + 12345u;
            jitter = (int)((lcg >> 16) % (unsigned)jitterRange);
        }
        if (driftEvery != 0 && k % (driftEvery > 0 ? driftEvery : -driftEvery) == 0) {
            s->base += driftEvery > 0 ? 1 : -1;
        }
        int fill = s->base + jitter;
        if (fill < windowMin) windowMin = fill;
        if ((k + 1) % d->windowPackets == 0) {
            s->lastMin = windowMin;
            windowMin = INT32_MAX;
        }
        int adj = ClueDriftOnPacket(d, fill, PACKET);
        int mag = adj < 0 ? -adj : adj;
        if (mag > s->maxStep) s->maxStep = mag;
        if (adj > 0) s->drops += adj; else s->dups -= adj;
        s->base -= adj;
    }
}

static void driftDoesNothingInsideTheDeadBand(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    CHECK(runWindow(&d, d.target + d.hyst) == 0);
    CHECK(runWindow(&d, d.target - d.hyst) == 0);
    CHECK(runWindow(&d, d.target) == 0);
}

static void driftDrainsABacklogGraduallyWithoutOvershoot(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    SIM s = { .base = 96 + 960 }; // 20 ms too much
    simulate(&d, &s, d.windowPackets, 0, 0);
    CHECK(s.drops <= 4); // the first window only measures
    simulate(&d, &s, 4 * d.windowPackets, 0, 0);
    CHECK(s.base == d.target);
    CHECK(s.drops == 960 && s.dups == 0);
    CHECK(s.maxStep <= 4); // never more than 4 frames of a 240-frame packet
}

static void driftCorrectsASmallOffsetOneFrameAtATime(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    SIM s = { .base = 96 + 60 };
    simulate(&d, &s, 3 * d.windowPackets, 0, 0);
    CHECK(s.base == d.target);
    CHECK(s.maxStep == 1);
}

static void driftDuplicatesWhenTheRingRunsLow(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    SIM s = { .base = 0 };
    simulate(&d, &s, 4 * d.windowPackets, 0, 0);
    CHECK(s.base == d.target);
    CHECK(s.drops == 0 && s.dups == d.target);
}

static void driftTracksSteadyClockDriftBothWays(void) {
    // 200 ppm at 48 kHz is about one frame every 21 packets of 240 frames
    for (int dir = -1; dir <= 1; dir += 2) {
        CLUE_AUDIO_DRIFT d;
        ClueDriftInit(&d, BURST, PACKET, RATE);
        SIM s = { .base = 96 };
        simulate(&d, &s, 60 * d.windowPackets, 0, 21 * dir);
        CHECK(s.lastMin >= d.target - d.hyst - 20);
        CHECK(s.lastMin <= d.target + d.hyst + 20);
        CHECK(s.maxStep == 1);
        CHECK(dir > 0 ? s.drops > 400 && s.dups == 0 : s.dups > 400 && s.drops == 0);
    }
}

static void driftKeepsTheJitterMinimumAtTarget(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    SIM s = { .base = 1500 };
    simulate(&d, &s, 15 * d.windowPackets, 300, 0);
    CHECK(s.lastMin >= d.target - d.hyst - 8);
    CHECK(s.lastMin <= d.target + d.hyst + 8);
    CHECK(s.maxStep <= 4);
}

static void underrunRaisesTargetUpToTheCapAndDecaysSlowly(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    ClueDriftOnUnderrun(&d);
    CHECK(d.target == 96 + BURST);
    CHECK(d.pending == 0 && d.windowCount == 0);
    for (int i = 0; i < 100; i++) ClueDriftOnUnderrun(&d);
    CHECK(d.target == 40 * 48);
    // 60 s without underruns gives back half a step
    int before = d.target;
    for (int i = 0; i < d.decayPackets - 1; i++) ClueDriftOnPacket(&d, d.target, PACKET);
    CHECK(d.target == before);
    ClueDriftOnPacket(&d, d.target, PACKET);
    CHECK(d.target == before - BURST / 2);
}

static void decayStopsAtTheInitialTarget(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    for (int i = 0; i < 3 * d.decayPackets; i++) ClueDriftOnPacket(&d, d.target, PACKET);
    CHECK(d.target == d.minTarget);
}

static void applyDropMergesNeighboursSpreadOverThePacket(void) {
    int16_t in[PACKET * 2], out[(PACKET + 8) * 2];
    for (int i = 0; i < PACKET; i++) { in[2 * i] = (int16_t)(i * 10); in[2 * i + 1] = (int16_t)(-i * 10); }
    int n = ClueDriftApply(in, PACKET, 2, 3, out);
    CHECK(n == PACKET - 3);
    // spacing 60: frame 60 merged into frame 59
    CHECK(out[2 * 59] == (590 + 600) / 2);
    CHECK(out[2 * 59 + 1] == (-590 - 600) / 2);
    CHECK(out[2 * 60] == 610);
    CHECK(out[2 * (n - 1)] == (PACKET - 1) * 10); // last frame kept
    CHECK(out[0] == 0);
}

static void applyInsertAddsAveragedFrames(void) {
    int16_t in[PACKET], out[PACKET + 8];
    for (int i = 0; i < PACKET; i++) in[i] = (int16_t)(i * 2);
    int n = ClueDriftApply(in, PACKET, 1, -2, out);
    CHECK(n == PACKET + 2);
    // spacing 80: after frame 79 comes avg(79, 80)
    CHECK(out[79] == 158);
    CHECK(out[80] == 159);
    CHECK(out[81] == 160);
    CHECK(out[n - 1] == (PACKET - 1) * 2);
}

static void applyClampsAndCopies(void) {
    int16_t in[16], out[24];
    for (int i = 0; i < 16; i++) in[i] = (int16_t)i;
    CHECK(ClueDriftApply(in, 16, 1, 0, out) == 16);
    CHECK(memcmp(in, out, sizeof(in)) == 0);
    CHECK(ClueDriftApply(in, 16, 1, 100, out) == 14);  // at most frames / 8
    CHECK(ClueDriftApply(in, 16, 1, -100, out) == 18);
    CHECK(ClueDriftApply(in, 7, 1, 1, out) == 7);      // too short to touch
}

static void driftStepNeverExceedsAnEighthOfThePacket(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, 16, RATE);
    for (int i = 0; i < d.windowPackets; i++) ClueDriftOnPacket(&d, 5000, 16);
    int adj = ClueDriftOnPacket(&d, 5000, 16);
    CHECK(adj == 2);
}

int main(void) {
    ringWrapsAndKeepsOrder();
    ringFullTakesOnlyWhatFitsAndEmptyReadsNothing();
    ringCountersSurviveUnsignedWrap();
    driftStartsAtOneBurstOrTwoMs();
    driftDoesNothingInsideTheDeadBand();
    driftDrainsABacklogGraduallyWithoutOvershoot();
    driftCorrectsASmallOffsetOneFrameAtATime();
    driftDuplicatesWhenTheRingRunsLow();
    driftTracksSteadyClockDriftBothWays();
    driftKeepsTheJitterMinimumAtTarget();
    underrunRaisesTargetUpToTheCapAndDecaysSlowly();
    decayStopsAtTheInitialTarget();
    applyDropMergesNeighboursSpreadOverThePacket();
    applyInsertAddsAveragedFrames();
    applyClampsAndCopies();
    driftStepNeverExceedsAnEighthOfThePacket();
    printf(failures ? "clue_audio_test: %d FAILED\n" : "clue_audio_test: all passed\n", failures);
    return failures ? 1 : 0;
}
