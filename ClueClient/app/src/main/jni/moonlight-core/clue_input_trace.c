// CLUE: native ring of input packets "on the wire", fed by moonlight-common-c's
// LiClueSetInputWireCallback hook and drained by the Java trace at session end.
//
// Each entry is (type, enqueueNs, wireNs) on CLOCK_MONOTONIC, the clock System.nanoTime uses.
// Writers claim slots with an atomic counter, so the hook never blocks or allocates.

#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <time.h>

#include <Limelight.h>

#include "clue_input_trace.h"

#define CLUE_WIRE_CAPACITY 4096 // power of two
#define CLUE_WIRE_MASK (CLUE_WIRE_CAPACITY - 1)

// Same values as com.clue.trace.InputTrace
#define CLUE_TYPE_GAMEPAD 1
#define CLUE_TYPE_MOUSE 2
#define CLUE_TYPE_KEYBOARD 3
#define CLUE_TYPE_TOUCH 4

// Mirrors moonlight-common-c's CTRL_CHANNEL_* (Limelight-internal.h)
#define CLUE_CH_KEYBOARD 0x02
#define CLUE_CH_MOUSE 0x03
#define CLUE_CH_PEN 0x04
#define CLUE_CH_TOUCH 0x05
#define CLUE_CH_GAMEPAD_BASE 0x10
#define CLUE_CH_GAMEPAD_END 0x1F

static int64_t wireType[CLUE_WIRE_CAPACITY];
static int64_t wireEnqueueNs[CLUE_WIRE_CAPACITY];
static int64_t wireSentNs[CLUE_WIRE_CAPACITY];
static atomic_uint_fast64_t wireHead;
static atomic_uint_fast64_t wireDrained;

static int64_t monoNowNs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void onInputWire(uint8_t channelId, uint64_t enqueueUs) {
    int type;
    if (channelId >= CLUE_CH_GAMEPAD_BASE && channelId <= CLUE_CH_GAMEPAD_END) {
        type = CLUE_TYPE_GAMEPAD;
    } else if (channelId == CLUE_CH_MOUSE) {
        type = CLUE_TYPE_MOUSE;
    } else if (channelId == CLUE_CH_KEYBOARD) {
        type = CLUE_TYPE_KEYBOARD;
    } else if (channelId == CLUE_CH_TOUCH || channelId == CLUE_CH_PEN) {
        type = CLUE_TYPE_TOUCH;
    } else {
        return;
    }

    // LiGetMicroseconds has its own epoch; map it onto CLOCK_MONOTONIC with the current offset.
    int64_t nowNs = monoNowNs();
    int64_t offsetNs = nowNs - (int64_t)LiGetMicroseconds() * 1000LL;

    uint64_t i = atomic_fetch_add_explicit(&wireHead, 1, memory_order_relaxed);
    int s = (int)(i & CLUE_WIRE_MASK);
    wireType[s] = type;
    wireEnqueueNs[s] = (int64_t)enqueueUs * 1000LL + offsetNs;
    wireSentNs[s] = nowNs;
}

void clueInputTraceStart(void) {
    atomic_store(&wireHead, 0);
    atomic_store(&wireDrained, 0);
    LiClueSetInputWireCallback(onInputWire);
}

// Copies the entries recorded since the last drain (oldest first, at most the ring size) into
// out as (type, enqueueNs, wireNs) triples and returns how many were copied.
JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_clueDrainInputWire(JNIEnv *env, jclass clazz, jlongArray out) {
    jsize capacity = (*env)->GetArrayLength(env, out) / 3;
    uint64_t head = atomic_load(&wireHead);
    uint64_t start = atomic_load(&wireDrained);
    if (head - start > CLUE_WIRE_CAPACITY) {
        start = head - CLUE_WIRE_CAPACITY;
    }
    if (head - start > (uint64_t)capacity) {
        start = head - (uint64_t)capacity;
    }

    jint n = 0;
    jlong triple[3];
    for (uint64_t i = start; i < head; i++, n++) {
        int s = (int)(i & CLUE_WIRE_MASK);
        triple[0] = wireType[s];
        triple[1] = wireEnqueueNs[s];
        triple[2] = wireSentNs[s];
        (*env)->SetLongArrayRegion(env, out, n * 3, 3, triple);
    }
    atomic_store(&wireDrained, head);
    return n;
}

// Test-only loss injection: one in every N unreliable controller datagrams is dropped (0 = off).
JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_clueSetInputDropEvery(JNIEnv *env, jclass clazz, jint everyN) {
    LiClueSetInputDropEvery(everyN);
}
