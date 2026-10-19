// Native tests for the AAudio path's ring and drift control (clue_audio_ring.c).
// Built for arm64 and run on the device by tools/native-test.sh.
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#include "clue_audio_ring.h"

static int failures;
#define CHECK(cond) do { if (!(cond)) { printf("  FAIL %s:%d: %s\n", __func__, __LINE__, #cond); failures++; } } while (0)

#define RATE 48000
#define PACKET 240 // 5 ms Opus packets
#define BURST 192  // POCO Pad SHARED stream burst (4 ms)

// ---------------------------------------------------------------- ring

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

// ---------------------------------------------------------------- drift: configuration and target

static void driftStartsAtOneBurstOrTwoMs(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    CHECK(d.target == 192);
    CHECK(d.windowPackets == 200);
    CHECK(d.deadBand == 96);
    CHECK(d.raiseStep == 96);
    CHECK(ClueDriftPrimeFrames(&d, PACKET) == 192 + PACKET);
    ClueDriftInit(&d, 48, PACKET, RATE);
    CHECK(d.target == 96);
    CHECK(d.raiseStep == 48);
    CHECK(d.deadBand == 96);
}

static void underrunsDuringTheFirstTwoSecondsDoNotRaiseTheTarget(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    for (int i = 0; i < 2 * d.windowPackets - 1; i++) {
        ClueDriftOnPacket(&d, 300, PACKET);
        if (i % 50 == 0) CHECK(ClueDriftOnUnderrun(&d) == 0);
    }
    CHECK(d.target == 192);
    ClueDriftOnPacket(&d, 300, PACKET);
    CHECK(ClueDriftOnUnderrun(&d) == 1);
    CHECK(d.target == 192 + 96);
    // A restart (stream reopened, host paused its audio) opens a new grace period
    ClueDriftRestart(&d);
    ClueDriftOnPacket(&d, 300, PACKET);
    CHECK(ClueDriftOnUnderrun(&d) == 0);
    CHECK(d.target == 192 + 96);
}

static void underrunRaisesTargetUpToTheCap(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    d.sinceStart = INT32_MAX;
    for (int i = 0; i < 100; i++) ClueDriftOnUnderrun(&d);
    CHECK(d.target == 40 * 48);
    CHECK(d.pending == 0 && d.windowCount == 0);
}

static void targetDecaysAfterTenCleanSecondsDownToOneBurst(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    d.sinceStart = INT32_MAX;
    ClueDriftOnUnderrun(&d);
    ClueDriftOnUnderrun(&d);
    CHECK(d.target == 192 + 2 * 96);
    for (int i = 0; i < 10 * d.windowPackets - 1; i++) ClueDriftOnPacket(&d, 400, PACKET);
    CHECK(d.target == 192 + 2 * 96);
    ClueDriftOnPacket(&d, 400, PACKET);
    CHECK(d.target == 192 + 96);
    for (int i = 0; i < 30 * d.windowPackets; i++) ClueDriftOnPacket(&d, 400, PACKET);
    CHECK(d.target == 192);
}

static void anUnderrunRightAfterADecayDoublesTheDecayInterval(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    d.sinceStart = INT32_MAX;
    ClueDriftOnUnderrun(&d);
    for (int i = 0; i < 10 * d.windowPackets; i++) ClueDriftOnPacket(&d, 400, PACKET);
    CHECK(d.target == 192);           // decayed
    ClueDriftOnPacket(&d, 400, PACKET);
    ClueDriftOnUnderrun(&d);          // ... and underran right away
    CHECK(d.decayPackets == 20 * d.windowPackets);
    CHECK(d.target == 192 + 96);
    for (int i = 0; i < 10 * d.windowPackets; i++) ClueDriftOnPacket(&d, 400, PACKET);
    CHECK(d.target == 192 + 96);      // not yet: the interval is 20 s now
    for (int i = 0; i < 10 * d.windowPackets; i++) ClueDriftOnPacket(&d, 400, PACKET);
    CHECK(d.target == 192);
    // Capped at 120 s
    for (int i = 0; i < 10; i++) {
        d.sinceDecay = 0;
        ClueDriftOnUnderrun(&d);
    }
    CHECK(d.decayPackets == 120 * d.windowPackets);
}

// ---------------------------------------------------------------- drift: steering

// Runs one window with a constant fill; returns the sum of the decisions taken during it.
static int runWindow(CLUE_AUDIO_DRIFT* d, int fill) {
    int sum = 0;
    for (int i = 0; i < d->windowPackets; i++) {
        sum += ClueDriftOnPacket(d, fill, PACKET);
    }
    return sum;
}

static void driftDoesNothingInsideTheDeadBand(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    CHECK(runWindow(&d, d.target + d.deadBand) == 0);
    CHECK(runWindow(&d, d.target - d.deadBand) == 0);
    CHECK(runWindow(&d, d.target) == 0);
}

static void driftDrainsABacklogGraduallyWithoutOvershoot(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, PACKET, RATE);
    int level = 192 + 960; // 20 ms too much, no jitter
    int drops = 0, dups = 0, maxStep = 0;
    for (int k = 0; k < 20 * d.windowPackets; k++) {
        int adj = ClueDriftOnPacket(&d, level, PACKET);
        if (adj > maxStep) maxStep = adj;
        if (adj > 0) drops += adj; else dups -= adj;
        level -= adj;
    }
    CHECK(dups == 0);
    CHECK(maxStep <= 4);  // never more than 4 frames of a 240-frame packet
    CHECK(level > d.target && level <= d.target + d.deadBand);
    CHECK(drops == 192 + 960 - level);
}

static void driftStepNeverExceedsAnEighthOfThePacket(void) {
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, BURST, 16, RATE);
    for (int i = 0; i < d.windowPackets - 1; i++) ClueDriftOnPacket(&d, 5000, 16);
    CHECK(ClueDriftOnPacket(&d, 5000, 16) == 2);
    CHECK(ClueDriftOnPacket(&d, 5000, 16) == 2);
}

// ---------------------------------------------------------------- drift: closed-loop simulation
//
// Event simulation of the real pipeline: the host sends a 240-frame packet every 5 ms on its own
// clock (ppm faster than the DAC), the network delays each by 2 ms + uniform [0, jitterUs) and,
// with burstEvery > 1, holds packets back and releases them in groups. The producer runs drift
// control and writes into the ring; the consumer reads callbackFrames per callback on the DAC
// clock, with the same priming and underrun rules as the AAudio callback in clue_aaudio.c.

typedef struct {
    int ppm;
    int jitterUs;
    int burstEvery;
    int spikeEvery;     // > 0: about one packet in this many is 8 ms later still (Wi-Fi retries)
    int callbackFrames;
    int seconds;
    int warmupSeconds;
} SCENARIO;

typedef struct {
    long long drops, dups;           // after warm-up
    int underruns;                   // after warm-up
    int mixedWindows;                // windows with both drops and duplicates (whole run)
    double meanOccupancyMs;          // mean ring level as played, after warm-up
    int target;
} RESULT;

static RESULT simulate(const SCENARIO* sc) {
    RESULT res;
    memset(&res, 0, sizeof(res));
    CLUE_AUDIO_DRIFT d;
    ClueDriftInit(&d, sc->callbackFrames, PACKET, RATE);

    const double packetNs = 5e6 * 1e6 / (1e6 + sc->ppm);
    const double callbackNs = sc->callbackFrames * 1e9 / RATE;
    const double endNs = sc->seconds * 1e9, warmNs = sc->warmupSeconds * 1e9;
    unsigned lcg = 2024;
    long long k = 0;
    double prevArrival = 0, nextCallback = callbackNs;
    int ring = 0, primed = 0, underruns = 0, underrunsSeen = 0, windowSign = 0;
    double occSum = 0;
    long long occN = 0;

    double nextArrival = 0;
    for (;;) {
        // arrival of packet k
        long long releaseIdx = sc->burstEvery > 1 ? (k / sc->burstEvery + 1) * sc->burstEvery - 1 : k;
        lcg = lcg * 1103515245u + 12345u;
        double delay = 2e6 + (sc->jitterUs > 0 ? (double)((lcg >> 8) % (unsigned)sc->jitterUs) * 1e3 : 0);
        if (sc->spikeEvery > 0 && lcg % (unsigned)sc->spikeEvery == 7) delay += 8e6;
        nextArrival = releaseIdx * packetNs + delay;
        if (nextArrival < prevArrival) nextArrival = prevArrival;

        // consumer callbacks that come before it
        while (nextCallback < nextArrival && nextCallback < endNs) {
            if (!primed && ring >= ClueDriftPrimeFrames(&d, PACKET)) primed = 1;
            if (primed) {
                if (nextCallback >= warmNs) {
                    occSum += ring - sc->callbackFrames / 2.0;
                    occN++;
                }
                if (ring < sc->callbackFrames) {
                    ring = 0;
                    primed = 0;
                    underruns++;
                    if (nextCallback >= warmNs) res.underruns++;
                } else {
                    ring -= sc->callbackFrames;
                }
            }
            nextCallback += callbackNs;
        }
        if (nextArrival >= endNs) break;

        // producer: same order as clueAudioWrite
        if (underruns != underrunsSeen) {
            underrunsSeen = underruns;
            ClueDriftOnUnderrun(&d);
        }
        int adj = ClueDriftOnPacket(&d, ring, PACKET);
        if (d.windowCount == 0) windowSign = 0; // a window ended inside this call
        if (adj != 0) {
            int sign = adj > 0 ? 1 : -1;
            if (windowSign != 0 && windowSign != sign) res.mixedWindows++;
            windowSign = sign;
            if (nextArrival >= warmNs) {
                if (adj > 0) res.drops += adj; else res.dups -= adj;
            }
        }
        ring += PACKET - adj;
        prevArrival = nextArrival;
        k++;
    }
    res.meanOccupancyMs = occN > 0 ? occSum / occN / (RATE / 1000.0) : -1;
    res.target = d.target;
    return res;
}

static void report(const char* name, const RESULT* r) {
    printf("  %-34s drops %5lld dups %5lld underruns %d mixed %d ring %.1f ms target %d\n",
           name, r->drops, r->dups, r->underruns, r->mixedWindows, r->meanOccupancyMs, r->target);
}

// The device failure: bursty 5 ms packets with +-3 ms jitter and no clock drift made the old
// controller drop and insert ~10000 frames a minute.
static void jitteryButStableArrivalsNeedAlmostNoCorrection(void) {
    SCENARIO sc = { .ppm = 0, .jitterUs = 6000, .burstEvery = 2, .callbackFrames = BURST,
                    .seconds = 120, .warmupSeconds = 20 };
    RESULT r = simulate(&sc);
    report("jitter +-3 ms, bursts, 0 ppm", &r);
    CHECK(r.drops + r.dups <= 100); // over 100 s
    CHECK(r.mixedWindows == 0);
    CHECK(r.underruns == 0);
}

// Same, plus an 8 ms late packet about every 1.5 s. This is the pattern that reproduces the
// device numbers with the old min-chasing controller (~5800 frames dropped and ~5600 inserted
// in 100 s); the mean-based controller leaves it alone.
static void occasionalLatePacketsDoNotMakeItChase(void) {
    SCENARIO sc = { .ppm = 0, .jitterUs = 6000, .burstEvery = 2, .spikeEvery = 300,
                    .callbackFrames = BURST, .seconds = 120, .warmupSeconds = 20 };
    RESULT r = simulate(&sc);
    report("jitter +-3 ms, bursts, 8 ms spikes", &r);
    CHECK(r.drops + r.dups <= 100);
    CHECK(r.mixedWindows == 0);
    CHECK(r.underruns == 0);
}

static void tracksHostClockFasterThanTheDac(void) {
    SCENARIO sc = { .ppm = 200, .jitterUs = 6000, .burstEvery = 2, .callbackFrames = BURST,
                    .seconds = 120, .warmupSeconds = 20 };
    RESULT r = simulate(&sc);
    report("jitter +-3 ms, bursts, +200 ppm", &r);
    // 200 ppm of 48 kHz over 100 s is 960 frames, all of them drops
    CHECK(r.drops >= 700 && r.drops <= 1250);
    CHECK(r.dups <= 20);
    CHECK(r.mixedWindows == 0);
    CHECK(r.underruns == 0);
}

static void tracksHostClockSlowerThanTheDac(void) {
    SCENARIO sc = { .ppm = -200, .jitterUs = 6000, .burstEvery = 2, .callbackFrames = BURST,
                    .seconds = 120, .warmupSeconds = 20 };
    RESULT r = simulate(&sc);
    report("jitter +-3 ms, bursts, -200 ppm", &r);
    CHECK(r.dups >= 700 && r.dups <= 1250);
    CHECK(r.drops <= 20);
    CHECK(r.mixedWindows == 0);
    CHECK(r.underruns == 0);
}

static void cleanLinkKeepsTheRingShort(void) {
    SCENARIO sc = { .ppm = 0, .jitterUs = 500, .burstEvery = 1, .callbackFrames = BURST,
                    .seconds = 60, .warmupSeconds = 15 };
    RESULT r = simulate(&sc);
    report("clean link, 0 ppm", &r);
    CHECK(r.meanOccupancyMs > 0 && r.meanOccupancyMs <= 8.5); // 1 burst of margin + packet/callback phase
    CHECK(r.underruns == 0);
    CHECK(r.drops + r.dups <= 20);
}

// ---------------------------------------------------------------- applying a decision

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

int main(void) {
    ringWrapsAndKeepsOrder();
    ringFullTakesOnlyWhatFitsAndEmptyReadsNothing();
    ringCountersSurviveUnsignedWrap();
    driftStartsAtOneBurstOrTwoMs();
    underrunsDuringTheFirstTwoSecondsDoNotRaiseTheTarget();
    underrunRaisesTargetUpToTheCap();
    targetDecaysAfterTenCleanSecondsDownToOneBurst();
    anUnderrunRightAfterADecayDoublesTheDecayInterval();
    driftDoesNothingInsideTheDeadBand();
    driftDrainsABacklogGraduallyWithoutOvershoot();
    driftStepNeverExceedsAnEighthOfThePacket();
    jitteryButStableArrivalsNeedAlmostNoCorrection();
    occasionalLatePacketsDoNotMakeItChase();
    tracksHostClockFasterThanTheDac();
    tracksHostClockSlowerThanTheDac();
    cleanLinkKeepsTheRingShort();
    applyDropMergesNeighboursSpreadOverThePacket();
    applyInsertAddsAveragedFrames();
    applyClampsAndCopies();
    printf(failures ? "clue_audio_test: %d FAILED\n" : "clue_audio_test: all passed\n", failures);
    return failures ? 1 : 0;
}
