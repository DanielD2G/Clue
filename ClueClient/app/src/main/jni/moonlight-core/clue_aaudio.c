// CLUE: AAudio output for decoded Opus audio (H4).
//
// Threads:
//   - moonlight-common-c's AudioDec thread decodes Opus into clueAudioDecodeBuffer() and calls
//     clueAudioWrite(), which applies drift control and pushes the frames into a lock-free SPSC
//     ring. It never blocks: if the ring is full the rest of the packet is dropped and counted.
//   - AAudio's real-time callback thread pulls frames from the ring. If the ring runs dry it
//     plays silence, counts an underrun and waits for the ring to refill to the prime level.
//     During the first ~2 s it also discards a start-up backlog at once when the producer asks
//     (trimRequest), instead of letting drift control drain it gradually.
//   - The Java stats thread polls clueAudioGetStats() (timestamps, XRuns) under streamLock, and
//     logs clueAudioLogInterval() every 5 s.
//   - A short-lived thread reopens the stream after AAUDIO_ERROR_DISCONNECTED (device change).
//
// AAudio is loaded with dlopen, because minSdk is 21 and libaaudio.so only exists on API 26+.
// Java only selects this path on API 27+ and falls back to AudioTrack if opening fails.

#include <jni.h>

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <dlfcn.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "clue_aaudio.h"
#include "clue_audio_ring.h"

#define TAG "ClueAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define RING_CAPACITY_FRAMES (1u << 14) // ~341 ms at 48 kHz
#define START_BURSTS 2
#define MAX_BURSTS 8
#define XRUN_CHECK_PACKETS 20 // ~100 ms at 5 ms packets
#define MAX_DRIFT_STEP 8      // frames a packet can grow by (ClueDriftApply never exceeds frames/8)

static struct {
    int loaded;
    aaudio_result_t (*createStreamBuilder)(AAudioStreamBuilder**);
    void (*setDirection)(AAudioStreamBuilder*, aaudio_direction_t);
    void (*setSampleRate)(AAudioStreamBuilder*, int32_t);
    void (*setChannelCount)(AAudioStreamBuilder*, int32_t);
    void (*setChannelMask)(AAudioStreamBuilder*, aaudio_channel_mask_t); // API 32
    void (*setFormat)(AAudioStreamBuilder*, aaudio_format_t);
    void (*setSharingMode)(AAudioStreamBuilder*, aaudio_sharing_mode_t);
    void (*setPerformanceMode)(AAudioStreamBuilder*, aaudio_performance_mode_t);
    void (*setUsage)(AAudioStreamBuilder*, aaudio_usage_t); // API 28
    void (*setBufferCapacityInFrames)(AAudioStreamBuilder*, int32_t);
    void (*setDataCallback)(AAudioStreamBuilder*, AAudioStream_dataCallback, void*);
    void (*setErrorCallback)(AAudioStreamBuilder*, AAudioStream_errorCallback, void*);
    aaudio_result_t (*openStream)(AAudioStreamBuilder*, AAudioStream**);
    aaudio_result_t (*builderDelete)(AAudioStreamBuilder*);
    aaudio_result_t (*requestStart)(AAudioStream*);
    aaudio_result_t (*requestStop)(AAudioStream*);
    aaudio_result_t (*close)(AAudioStream*);
    int32_t (*getFramesPerBurst)(AAudioStream*);
    int32_t (*getBufferCapacityInFrames)(AAudioStream*);
    aaudio_result_t (*setBufferSizeInFrames)(AAudioStream*, int32_t);
    int32_t (*getBufferSizeInFrames)(AAudioStream*);
    int32_t (*getXRunCount)(AAudioStream*);
    int32_t (*getSampleRate)(AAudioStream*);
    aaudio_sharing_mode_t (*getSharingMode)(AAudioStream*);
    aaudio_performance_mode_t (*getPerformanceMode)(AAudioStream*);
    int64_t (*getFramesWritten)(AAudioStream*);
    aaudio_result_t (*getTimestamp)(AAudioStream*, clockid_t, int64_t*, int64_t*);
    const char* (*convertResultToText)(aaudio_result_t);
} AA;

// Stream lifecycle. Taken by open/close/reopen and the stats poll; the producer only trylocks
// it and the data callback never touches it.
static pthread_mutex_t streamLock = PTHREAD_MUTEX_INITIALIZER;
static AAudioStream* stream;     // guarded by streamLock
static int bufferBursts;         // guarded by streamLock
static int maxBursts;            // guarded by streamLock
static int32_t lastXruns;        // XRun count of the current stream, guarded by streamLock
static atomic_uint generation;   // bumps on every open/close (under streamLock)
static atomic_bool reopenInFlight;

static atomic_bool active;
static atomic_bool closing;

// Format, fixed between open and close
static int channelCount;
static int sampleRate;
static int packetFrames;

// Buffers, allocated at open
static int16_t* ringBuf;
static int16_t* decodeBuf;
static int16_t* driftBuf;
static CLUE_AUDIO_RING ring;

// Producer (AudioDec thread) state
static CLUE_AUDIO_DRIFT drift;
static unsigned underrunsSeen;
static unsigned packetsSinceXrunCheck;
static int64_t lastWriteNs;
static unsigned reopensSeen;
static unsigned packetsSinceOpen;  // for the steady-state stats
static unsigned steadyPackets;     // packets in the first STEADY_AFTER_S seconds

// Consumer (callback) state
static int primed;
static atomic_int primeFrames;
static atomic_bool flushRequest;
static atomic_int trimRequest;     // frames the producer asks the callback to discard at once
static uint64_t framesSinceOpen;   // frames handed to AAudio since open, for the steady-state stats

// Stats. "Steady" ones leave out the first STEADY_AFTER_S seconds after open.
#define STEADY_AFTER_S 3
static atomic_uint underruns;
static atomic_ullong statPlayed, statDropped, statFramesLost, statDriftDropped, statDriftInserted;
static atomic_ullong statFillSum, statFillSamples;
static atomic_ullong statFillSumSteady, statFillSamplesSteady;
static atomic_uint statUnderrunsSteady;
static atomic_ullong statDriftDroppedSteady, statDriftInsertedSteady, statTrimmed;
static atomic_int intervalMinFill;  // lowest ring level after a read since the last diagnostics line

// Counter values at the last diagnostics line (stats thread only)
static struct {
    int64_t ns;
    unsigned long long fillSum, fillSamples, driftDropped, driftInserted, trimmed;
    unsigned underruns;
    long long xruns;
} diagLast;
static atomic_llong statXrunsBase; // XRuns of streams already closed
static atomic_int statXrunsNow;
static atomic_int statBufferFrames, statBurstFrames, statExclusive, statSampleRate, statReopens;
static atomic_int statSetpoint;

static int64_t monoNowNs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static jlong meanUs(unsigned long long sumFrames, unsigned long long samples, int rate) {
    if (samples == 0 || rate <= 0) {
        return -1;
    }
    return (jlong)(sumFrames * 1000000ULL / samples / (unsigned)rate);
}

static int loadAAudio(void) {
    if (AA.loaded) {
        return AA.loaded > 0;
    }
    AA.loaded = -1;
    void* lib = dlopen("libaaudio.so", RTLD_NOW);
    if (lib == NULL) {
        LOGW("libaaudio.so not available");
        return 0;
    }
#define LOAD(field, name) AA.field = dlsym(lib, name)
    LOAD(createStreamBuilder, "AAudio_createStreamBuilder");
    LOAD(setDirection, "AAudioStreamBuilder_setDirection");
    LOAD(setSampleRate, "AAudioStreamBuilder_setSampleRate");
    LOAD(setChannelCount, "AAudioStreamBuilder_setChannelCount");
    LOAD(setChannelMask, "AAudioStreamBuilder_setChannelMask");
    LOAD(setFormat, "AAudioStreamBuilder_setFormat");
    LOAD(setSharingMode, "AAudioStreamBuilder_setSharingMode");
    LOAD(setPerformanceMode, "AAudioStreamBuilder_setPerformanceMode");
    LOAD(setUsage, "AAudioStreamBuilder_setUsage");
    LOAD(setBufferCapacityInFrames, "AAudioStreamBuilder_setBufferCapacityInFrames");
    LOAD(setDataCallback, "AAudioStreamBuilder_setDataCallback");
    LOAD(setErrorCallback, "AAudioStreamBuilder_setErrorCallback");
    LOAD(openStream, "AAudioStreamBuilder_openStream");
    LOAD(builderDelete, "AAudioStreamBuilder_delete");
    LOAD(requestStart, "AAudioStream_requestStart");
    LOAD(requestStop, "AAudioStream_requestStop");
    LOAD(close, "AAudioStream_close");
    LOAD(getFramesPerBurst, "AAudioStream_getFramesPerBurst");
    LOAD(getBufferCapacityInFrames, "AAudioStream_getBufferCapacityInFrames");
    LOAD(setBufferSizeInFrames, "AAudioStream_setBufferSizeInFrames");
    LOAD(getBufferSizeInFrames, "AAudioStream_getBufferSizeInFrames");
    LOAD(getXRunCount, "AAudioStream_getXRunCount");
    LOAD(getSampleRate, "AAudioStream_getSampleRate");
    LOAD(getSharingMode, "AAudioStream_getSharingMode");
    LOAD(getPerformanceMode, "AAudioStream_getPerformanceMode");
    LOAD(getFramesWritten, "AAudioStream_getFramesWritten");
    LOAD(getTimestamp, "AAudioStream_getTimestamp");
    LOAD(convertResultToText, "AAudio_convertResultToText");
#undef LOAD
    // setChannelMask (API 32) and setUsage (API 28) are optional
    if (!AA.createStreamBuilder || !AA.setDirection || !AA.setSampleRate || !AA.setChannelCount ||
        !AA.setFormat || !AA.setSharingMode || !AA.setPerformanceMode ||
        !AA.setBufferCapacityInFrames || !AA.setDataCallback || !AA.setErrorCallback ||
        !AA.openStream || !AA.builderDelete || !AA.requestStart || !AA.requestStop || !AA.close ||
        !AA.getFramesPerBurst || !AA.getBufferCapacityInFrames || !AA.setBufferSizeInFrames ||
        !AA.getBufferSizeInFrames || !AA.getXRunCount || !AA.getSampleRate || !AA.getSharingMode ||
        !AA.getPerformanceMode || !AA.getFramesWritten || !AA.getTimestamp || !AA.convertResultToText) {
        LOGW("libaaudio.so is missing symbols");
        return 0;
    }
    AA.loaded = 1;
    return 1;
}

static aaudio_data_callback_result_t dataCallback(AAudioStream* s, void* user, void* audioData, int32_t numFrames) {
    int16_t* out = (int16_t*)audioData;
    size_t frameBytes = (size_t)channelCount * sizeof(int16_t);

    int steady = framesSinceOpen >= (uint64_t)STEADY_AFTER_S * (uint64_t)sampleRate;
    framesSinceOpen += (uint64_t)numFrames;

    if (atomic_exchange_explicit(&flushRequest, false, memory_order_acq_rel)) {
        ClueRingFlush(&ring);
        atomic_store_explicit(&trimRequest, 0, memory_order_relaxed);
        primed = 0;
    }
    // Start-up backlog the producer wants gone at once (ClueDriftStartupTrim)
    int trim = atomic_exchange_explicit(&trimRequest, 0, memory_order_acq_rel);
    if (trim > 0) {
        ClueRingSkip(&ring, (uint32_t)trim);
    }
    if (!primed) {
        if ((int32_t)ClueRingFill(&ring) < atomic_load_explicit(&primeFrames, memory_order_relaxed)) {
            memset(out, 0, (size_t)numFrames * frameBytes);
            return AAUDIO_CALLBACK_RESULT_CONTINUE;
        }
        primed = 1;
    }

    // Ring occupancy as played: halfway through this read, sampled once per callback (evenly in time)
    uint32_t fillBefore = ClueRingFill(&ring);
    uint32_t half = (uint32_t)numFrames / 2;
    unsigned long long occ = fillBefore > half ? fillBefore - half : 0;
    atomic_fetch_add_explicit(&statFillSum, occ, memory_order_relaxed);
    atomic_fetch_add_explicit(&statFillSamples, 1, memory_order_relaxed);
    if (steady) {
        atomic_fetch_add_explicit(&statFillSumSteady, occ, memory_order_relaxed);
        atomic_fetch_add_explicit(&statFillSamplesSteady, 1, memory_order_relaxed);
    }

    uint32_t got = ClueRingRead(&ring, out, (uint32_t)numFrames);
    // Lowest level left after a read: the margin the ring kept against the next packet
    int after = (int)(fillBefore - got);
    int cur = atomic_load_explicit(&intervalMinFill, memory_order_relaxed);
    while (after < cur && !atomic_compare_exchange_weak_explicit(&intervalMinFill, &cur, after,
                                                                 memory_order_relaxed, memory_order_relaxed)) {
    }
    if (got < (uint32_t)numFrames) {
        memset(out + (size_t)got * channelCount, 0, (size_t)(numFrames - got) * frameBytes);
        atomic_fetch_add_explicit(&underruns, 1, memory_order_relaxed);
        if (steady) {
            atomic_fetch_add_explicit(&statUnderrunsSteady, 1, memory_order_relaxed);
        }
        primed = 0;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void* reopenThreadProc(void* arg);

static void errorCallback(AAudioStream* s, void* user, aaudio_result_t error) {
    LOGW("stream error %d (%s)", error, AA.convertResultToText(error));
    // The stream cannot be closed from this callback; reopen it from a thread of our own.
    if (error == AAUDIO_ERROR_DISCONNECTED && !atomic_load(&closing) &&
        !atomic_exchange(&reopenInFlight, true)) {
        pthread_t t;
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
        if (pthread_create(&t, &attr, reopenThreadProc, (void*)(uintptr_t)generation) != 0) {
            atomic_store(&reopenInFlight, false);
        }
        pthread_attr_destroy(&attr);
    }
}

// Opens and configures a stream; streamLock held. Tries EXCLUSIVE (MMAP) first, then SHARED.
static aaudio_result_t openStreamLocked(void) {
    static const aaudio_sharing_mode_t modes[] = { AAUDIO_SHARING_MODE_EXCLUSIVE, AAUDIO_SHARING_MODE_SHARED };
    aaudio_result_t res = AAUDIO_ERROR_INTERNAL;

    for (int m = 0; m < 2; m++) {
        AAudioStreamBuilder* b;
        res = AA.createStreamBuilder(&b);
        if (res != AAUDIO_OK) {
            return res;
        }
        AA.setDirection(b, AAUDIO_DIRECTION_OUTPUT);
        AA.setSampleRate(b, sampleRate);
        if (channelCount > 2 && AA.setChannelMask) {
            // Opus surround is decoded in Android/Windows order, which matches these masks
            AA.setChannelMask(b, channelCount == 4 ? AAUDIO_CHANNEL_QUAD :
                                 channelCount == 6 ? AAUDIO_CHANNEL_5POINT1 : AAUDIO_CHANNEL_7POINT1);
        } else {
            AA.setChannelCount(b, channelCount);
        }
        AA.setFormat(b, AAUDIO_FORMAT_PCM_I16);
        AA.setSharingMode(b, modes[m]);
        AA.setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        if (AA.setUsage) {
            AA.setUsage(b, AAUDIO_USAGE_GAME);
        }
        // Room to grow the buffer after XRuns; the device may round this
        AA.setBufferCapacityInFrames(b, sampleRate / 1000 * 40);
        AA.setDataCallback(b, dataCallback, NULL);
        AA.setErrorCallback(b, errorCallback, NULL);

        AAudioStream* s = NULL;
        res = AA.openStream(b, &s);
        AA.builderDelete(b);
        if (res != AAUDIO_OK) {
            LOGW("open (%s) failed: %s", m == 0 ? "exclusive" : "shared", AA.convertResultToText(res));
            continue;
        }

        int32_t burst = AA.getFramesPerBurst(s);
        int32_t capacity = AA.getBufferCapacityInFrames(s);
        if (burst <= 0) {
            burst = sampleRate / 1000 * 2;
        }
        maxBursts = capacity / burst;
        if (maxBursts > MAX_BURSTS) {
            maxBursts = MAX_BURSTS;
        }
        if (maxBursts < START_BURSTS) {
            maxBursts = START_BURSTS;
        }
        bufferBursts = START_BURSTS;
        AA.setBufferSizeInFrames(s, bufferBursts * burst);
        lastXruns = 0;

        stream = s;
        generation++;
        int exclusive = AA.getSharingMode(s) == AAUDIO_SHARING_MODE_EXCLUSIVE;
        atomic_store(&statBurstFrames, burst);
        atomic_store(&statBufferFrames, AA.getBufferSizeInFrames(s));
        atomic_store(&statExclusive, exclusive);
        atomic_store(&statSampleRate, AA.getSampleRate(s));
        LOGI("stream open: %s, perf %d, %d Hz, %d ch, burst %d, buffer %d, capacity %d",
             exclusive ? "EXCLUSIVE" : "SHARED", AA.getPerformanceMode(s), AA.getSampleRate(s),
             channelCount, burst, AA.getBufferSizeInFrames(s), capacity);
        return AAUDIO_OK;
    }
    return res;
}

static void closeStreamLocked(void) {
    if (stream != NULL) {
        atomic_fetch_add(&statXrunsBase, AA.getXRunCount(stream));
        atomic_store(&statXrunsNow, 0);
        AA.requestStop(stream);
        AA.close(stream);
        stream = NULL;
        generation++;
    }
}

static void* reopenThreadProc(void* arg) {
    unsigned gen = (unsigned)(uintptr_t)arg;
    pthread_mutex_lock(&streamLock);
    for (int attempt = 0; attempt < 5; attempt++) {
        if (atomic_load(&closing) || gen != generation) {
            break;
        }
        closeStreamLocked();
        if (openStreamLocked() == AAUDIO_OK) {
            atomic_store(&flushRequest, true);
            atomic_fetch_add(&statReopens, 1);
            AA.requestStart(stream);
            break;
        }
        gen = generation;
        pthread_mutex_unlock(&streamLock);
        usleep(200 * 1000);
        pthread_mutex_lock(&streamLock);
    }
    atomic_store(&reopenInFlight, false);
    pthread_mutex_unlock(&streamLock);
    return NULL;
}

bool clueAudioIsActive(void) {
    return atomic_load_explicit(&active, memory_order_relaxed);
}

int16_t* clueAudioDecodeBuffer(void) {
    return decodeBuf;
}

// Grows the AAudio buffer one burst per new XRun, up to the cap. Producer thread, never waits.
static void tuneBuffer(void) {
    if (++packetsSinceXrunCheck < XRUN_CHECK_PACKETS) {
        return;
    }
    packetsSinceXrunCheck = 0;
    if (pthread_mutex_trylock(&streamLock) != 0) {
        return;
    }
    if (stream != NULL) {
        int32_t x = AA.getXRunCount(stream);
        if (x > lastXruns) {
            lastXruns = x;
            if (bufferBursts < maxBursts) {
                bufferBursts++;
                AA.setBufferSizeInFrames(stream, bufferBursts * atomic_load(&statBurstFrames));
                atomic_store(&statBufferFrames, AA.getBufferSizeInFrames(stream));
            }
        }
        atomic_store(&statXrunsNow, x);
    }
    pthread_mutex_unlock(&streamLock);
}

void clueAudioWrite(const int16_t* pcm, int frames) {
    // A reopened stream or a pause in the host's audio (> 100 ms without packets) starts over:
    // the ring re-primes and the first packets arrive in a burst, which is not network jitter.
    int64_t nowNs = monoNowNs();
    unsigned restart = atomic_load_explicit(&statReopens, memory_order_relaxed);
    if (restart != reopensSeen || nowNs - lastWriteNs >= 100 * 1000000LL) {
        reopensSeen = restart;
        ClueDriftRestart(&drift);
    }
    lastWriteNs = nowNs;
    // Underruns reported by the callback raise the drift target (outside its grace period)
    unsigned u = atomic_load_explicit(&underruns, memory_order_relaxed);
    if (u != underrunsSeen) {
        underrunsSeen = u;
        ClueDriftOnUnderrun(&drift);
    }

    int steady = packetsSinceOpen >= steadyPackets;
    if (!steady) {
        packetsSinceOpen++;
    }

    // Frames already handed to the callback for discarding are as good as gone
    int32_t fill = (int32_t)ClueRingFill(&ring) - atomic_load_explicit(&trimRequest, memory_order_acquire);
    if (fill < 0) {
        fill = 0;
    }
    if (atomic_load_explicit(&trimRequest, memory_order_relaxed) == 0) {
        int32_t trim = ClueDriftStartupTrim(&drift, fill, frames);
        if (trim > 0) {
            atomic_store_explicit(&trimRequest, trim, memory_order_release);
            atomic_fetch_add_explicit(&statTrimmed, (unsigned long long)trim, memory_order_relaxed);
            fill -= trim;
        }
    }
    int32_t adj = ClueDriftOnPacket(&drift, fill, frames);
    atomic_store_explicit(&primeFrames, ClueDriftPrimeFrames(&drift, packetFrames), memory_order_relaxed);

    const int16_t* src = pcm;
    int32_t n = frames;
    if (adj != 0) {
        n = ClueDriftApply(pcm, frames, channelCount, adj, driftBuf);
        src = driftBuf;
        unsigned long long changed = (unsigned long long)(n < frames ? frames - n : n - frames);
        atomic_fetch_add_explicit(n < frames ? &statDriftDropped : &statDriftInserted, changed, memory_order_relaxed);
        if (steady) {
            atomic_fetch_add_explicit(n < frames ? &statDriftDroppedSteady : &statDriftInsertedSteady,
                                      changed, memory_order_relaxed);
        }
    }

    uint32_t written = ClueRingWrite(&ring, src, (uint32_t)n);
    if (written < (uint32_t)n) {
        atomic_fetch_add_explicit(&statDropped, 1, memory_order_relaxed);
        atomic_fetch_add_explicit(&statFramesLost, (unsigned long long)(n - written), memory_order_relaxed);
    } else {
        atomic_fetch_add_explicit(&statPlayed, 1, memory_order_relaxed);
    }
    atomic_store_explicit(&statSetpoint, ClueDriftSetpoint(&drift), memory_order_relaxed);

    tuneBuffer();
}

static void freeBuffers(void) {
    free(ringBuf);
    free(decodeBuf);
    free(driftBuf);
    ringBuf = decodeBuf = driftBuf = NULL;
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_clueAudioOpen(JNIEnv* env, jclass clazz,
                                                        jint channels, jint rate, jint samplesPerFrame) {
    if (!loadAAudio()) {
        return -1;
    }
    if (channels > 2 && AA.setChannelMask == NULL) {
        return -2; // no positional surround masks before API 32; AudioTrack handles it
    }

    channelCount = channels;
    sampleRate = rate;
    packetFrames = samplesPerFrame;
    ringBuf = malloc((size_t)RING_CAPACITY_FRAMES * channels * sizeof(int16_t));
    decodeBuf = malloc((size_t)samplesPerFrame * channels * sizeof(int16_t));
    driftBuf = malloc((size_t)(samplesPerFrame + MAX_DRIFT_STEP + samplesPerFrame / 8) * channels * sizeof(int16_t));
    if (ringBuf == NULL || decodeBuf == NULL || driftBuf == NULL) {
        freeBuffers();
        return -3;
    }
    ClueRingInit(&ring, ringBuf, RING_CAPACITY_FRAMES, (uint32_t)channels);

    atomic_store(&underruns, 0);
    underrunsSeen = 0;
    packetsSinceXrunCheck = 0;
    lastWriteNs = 0;
    reopensSeen = 0;
    atomic_store(&statSetpoint, 0);
    primed = 0;
    atomic_store(&flushRequest, false);
    atomic_store(&statPlayed, 0);
    atomic_store(&statDropped, 0);
    atomic_store(&statFramesLost, 0);
    atomic_store(&statDriftDropped, 0);
    atomic_store(&statDriftInserted, 0);
    atomic_store(&statFillSum, 0);
    atomic_store(&statFillSamples, 0);
    atomic_store(&statXrunsBase, 0);
    atomic_store(&statXrunsNow, 0);
    atomic_store(&statReopens, 0);
    atomic_store(&statFillSumSteady, 0);
    atomic_store(&statFillSamplesSteady, 0);
    atomic_store(&statUnderrunsSteady, 0);
    atomic_store(&statDriftDroppedSteady, 0);
    atomic_store(&statDriftInsertedSteady, 0);
    atomic_store(&statTrimmed, 0);
    atomic_store(&trimRequest, 0);
    atomic_store(&intervalMinFill, INT_MAX);
    framesSinceOpen = 0;
    packetsSinceOpen = 0;
    steadyPackets = samplesPerFrame > 0 ? (unsigned)(STEADY_AFTER_S * rate / samplesPerFrame) : 0;
    memset(&diagLast, 0, sizeof(diagLast));
    diagLast.ns = monoNowNs();
    atomic_store(&closing, false);

    pthread_mutex_lock(&streamLock);
    aaudio_result_t res = openStreamLocked();
    if (res == AAUDIO_OK) {
        ClueDriftInit(&drift, atomic_load(&statBurstFrames), packetFrames, sampleRate);
        atomic_store(&primeFrames, ClueDriftPrimeFrames(&drift, packetFrames));
        atomic_store(&active, true);
    }
    pthread_mutex_unlock(&streamLock);

    if (res != AAUDIO_OK) {
        freeBuffers();
        return res < 0 ? res : -4;
    }
    return 0;
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_clueAudioStart(JNIEnv* env, jclass clazz) {
    pthread_mutex_lock(&streamLock);
    if (stream != NULL) {
        aaudio_result_t res = AA.requestStart(stream);
        if (res != AAUDIO_OK) {
            LOGW("requestStart failed: %s", AA.convertResultToText(res));
        }
    }
    pthread_mutex_unlock(&streamLock);
}

JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_clueAudioClose(JNIEnv* env, jclass clazz) {
    // The decoder thread has been joined by now, so nothing writes the ring any more
    atomic_store(&closing, true);
    atomic_store(&active, false);
    pthread_mutex_lock(&streamLock);
    closeStreamLocked();
    pthread_mutex_unlock(&streamLock);
    freeBuffers();
}

JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_clueAudioGetStats(JNIEnv* env, jclass clazz, jlongArray out) {
    jlong s[CLUE_AUDIO_STAT_COUNT];
    s[CLUE_AUDIO_STAT_HW_LATENCY_US] = -1;

    pthread_mutex_lock(&streamLock);
    if (stream != NULL) {
        int64_t framePos, timeNs;
        int32_t rate = AA.getSampleRate(stream);
        if (rate > 0 && AA.getTimestamp(stream, CLOCK_MONOTONIC, &framePos, &timeNs) == AAUDIO_OK) {
            // When the last frame written so far reaches the DAC, relative to now
            int64_t written = AA.getFramesWritten(stream);
            int64_t presentNs = timeNs + (written - framePos) * 1000000000LL / rate;
            s[CLUE_AUDIO_STAT_HW_LATENCY_US] = (presentNs - monoNowNs()) / 1000;
        }
        atomic_store(&statXrunsNow, AA.getXRunCount(stream));
        atomic_store(&statBufferFrames, AA.getBufferSizeInFrames(stream));
    }
    pthread_mutex_unlock(&streamLock);

    int rate = atomic_load(&statSampleRate);
    s[CLUE_AUDIO_STAT_PLAYED] = (jlong)atomic_load(&statPlayed);
    s[CLUE_AUDIO_STAT_DROPPED] = (jlong)atomic_load(&statDropped);
    s[CLUE_AUDIO_STAT_UNDERRUNS] = (jlong)atomic_load(&underruns);
    s[CLUE_AUDIO_STAT_XRUNS] = (jlong)atomic_load(&statXrunsBase) + atomic_load(&statXrunsNow);
    s[CLUE_AUDIO_STAT_DRIFT_DROPPED] = (jlong)atomic_load(&statDriftDropped);
    s[CLUE_AUDIO_STAT_DRIFT_INSERTED] = (jlong)atomic_load(&statDriftInserted);
    s[CLUE_AUDIO_STAT_RING_MEAN_US] = meanUs(atomic_load(&statFillSum), atomic_load(&statFillSamples), rate);
    s[CLUE_AUDIO_STAT_RING_STEADY_US] = meanUs(atomic_load(&statFillSumSteady), atomic_load(&statFillSamplesSteady), rate);
    s[CLUE_AUDIO_STAT_UNDERRUNS_STEADY] = atomic_load(&statUnderrunsSteady);
    s[CLUE_AUDIO_STAT_DRIFT_DROPPED_STEADY] = (jlong)atomic_load(&statDriftDroppedSteady);
    s[CLUE_AUDIO_STAT_DRIFT_INSERTED_STEADY] = (jlong)atomic_load(&statDriftInsertedSteady);
    s[CLUE_AUDIO_STAT_TRIMMED_FRAMES] = (jlong)atomic_load(&statTrimmed);
    s[CLUE_AUDIO_STAT_BUFFER_FRAMES] = atomic_load(&statBufferFrames);
    s[CLUE_AUDIO_STAT_BURST_FRAMES] = atomic_load(&statBurstFrames);
    s[CLUE_AUDIO_STAT_EXCLUSIVE] = atomic_load(&statExclusive);
    s[CLUE_AUDIO_STAT_SAMPLE_RATE] = rate;
    s[CLUE_AUDIO_STAT_TARGET_FRAMES] = atomic_load(&primeFrames) - packetFrames;
    s[CLUE_AUDIO_STAT_REOPENS] = atomic_load(&statReopens);
    s[CLUE_AUDIO_STAT_FRAMES_LOST] = (jlong)atomic_load(&statFramesLost);
    s[CLUE_AUDIO_STAT_SETPOINT_FRAMES] = atomic_load(&statSetpoint);

    jsize n = (*env)->GetArrayLength(env, out);
    if (n > CLUE_AUDIO_STAT_COUNT) {
        n = CLUE_AUDIO_STAT_COUNT;
    }
    (*env)->SetLongArrayRegion(env, out, 0, n, s);
    return n;
}

// One logcat line per call (the Java stats thread calls it every 5 s) with what happened since
// the previous one, to tell start-up effects from steady-state ones.
JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_clueAudioLogInterval(JNIEnv* env, jclass clazz) {
    int rate = atomic_load(&statSampleRate);
    if (!atomic_load(&active) || rate <= 0) {
        return;
    }
    int64_t now = monoNowNs();
    unsigned long long fillSum = atomic_load(&statFillSum), fillSamples = atomic_load(&statFillSamples);
    unsigned long long dropped = atomic_load(&statDriftDropped), inserted = atomic_load(&statDriftInserted);
    unsigned long long trimmed = atomic_load(&statTrimmed);
    unsigned under = atomic_load(&underruns);
    long long xruns = atomic_load(&statXrunsBase) + atomic_load(&statXrunsNow);
    int minFill = atomic_exchange(&intervalMinFill, INT_MAX);
    double msPerFrame = 1000.0 / rate;
    unsigned long long dSamples = fillSamples - diagLast.fillSamples;

    LOGI("last %.1f s: ring mean %.1f ms min %.1f ms, setpoint %.1f ms, target %.1f ms, "
         "drift -%llu/+%llu frames, trimmed %llu, underruns %u, xruns %lld, buffer %d",
         (now - diagLast.ns) / 1e9,
         dSamples > 0 ? (double)(fillSum - diagLast.fillSum) / dSamples * msPerFrame : -1.0,
         minFill != INT_MAX ? minFill * msPerFrame : -1.0,
         atomic_load(&statSetpoint) * msPerFrame,
         (atomic_load(&primeFrames) - packetFrames) * msPerFrame,
         dropped - diagLast.driftDropped, inserted - diagLast.driftInserted, trimmed - diagLast.trimmed,
         under - diagLast.underruns, xruns - diagLast.xruns, atomic_load(&statBufferFrames));

    diagLast.ns = now;
    diagLast.fillSum = fillSum;
    diagLast.fillSamples = fillSamples;
    diagLast.driftDropped = dropped;
    diagLast.driftInserted = inserted;
    diagLast.trimmed = trimmed;
    diagLast.underruns = under;
    diagLast.xruns = xruns;
}
