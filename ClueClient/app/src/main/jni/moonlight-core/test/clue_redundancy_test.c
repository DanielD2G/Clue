// Native tests for the controller redundancy policy (moonlight-common-c/src/ClueRedundancy.c).
// Built for arm64 and run on the device by tools/native-test.sh.
#include <stdio.h>
#include <string.h>

#include "ClueRedundancy.h"

static int failures;
#define CHECK(cond) do { if (!(cond)) { printf("  FAIL %s:%d: %s\n", __func__, __LINE__, #cond); failures++; } } while (0)

static void buttonChangeSendsTwoCopies(void) {
    CLUE_PAD_STATE s; memset(&s, 0, sizeof(s));
    CHECK(ClueRedundancyOnState(&s, 0x0000, 1000) == 1);  // first state, no buttons
    CHECK(ClueRedundancyOnState(&s, 0x1000, 2000) == 2);  // A pressed
    CHECK(ClueRedundancyOnState(&s, 0x0000, 3000) == 2);  // A released
}

static void axisOnlyChangeSendsOneCopy(void) {
    CLUE_PAD_STATE s; memset(&s, 0, sizeof(s));
    ClueRedundancyOnState(&s, 0x1000, 1000);
    CHECK(ClueRedundancyOnState(&s, 0x1000, 2000) == 1);  // same buttons, sticks moved
}

static void idleStateResendsEvery4msWhileActive(void) {
    CLUE_PAD_STATE s; memset(&s, 0, sizeof(s));
    ClueRedundancyOnState(&s, 0x1000, 1000);
    ClueRedundancyOnSent(&s, 1000);
    CHECK(!ClueRedundancyShouldResend(&s, 1000 + 3999));
    CHECK(ClueRedundancyShouldResend(&s, 1000 + 4000));
    ClueRedundancyOnSent(&s, 5000);
    CHECK(!ClueRedundancyShouldResend(&s, 8000));
    CHECK(ClueRedundancyShouldResend(&s, 9000));
}

static void noResendAfter500msIdle(void) {
    CLUE_PAD_STATE s; memset(&s, 0, sizeof(s));
    ClueRedundancyOnState(&s, 0x1000, 1000);
    ClueRedundancyOnSent(&s, 1000);
    CHECK(ClueRedundancyShouldResend(&s, 1000 + 499000));
    CHECK(!ClueRedundancyShouldResend(&s, 1000 + 500000));
}

static void noResendBeforeAnyState(void) {
    CLUE_PAD_STATE s; memset(&s, 0, sizeof(s));
    CHECK(!ClueRedundancyShouldResend(&s, 100000));
}

static void multipleControllersKeepIndependentState(void) {
    CLUE_PAD_STATE pads[4]; memset(pads, 0, sizeof(pads));
    ClueRedundancyOnState(&pads[0], 0x1000, 1000);
    ClueRedundancyOnSent(&pads[0], 1000);
    CHECK(ClueRedundancyOnState(&pads[1], 0x0000, 1500) == 1);  // pad 1's first state
    CHECK(ClueRedundancyOnState(&pads[1], 0x1000, 2000) == 2);  // pad 1 press, independent of pad 0
    CHECK(ClueRedundancyShouldResend(&pads[0], 6000));
    CHECK(!ClueRedundancyShouldResend(&pads[2], 6000));
}

static void nextCheckIsZeroWhenNoControllerIsActive(void) {
    CLUE_PAD_STATE pads[4]; memset(pads, 0, sizeof(pads));
    CHECK(ClueRedundancyNextCheckUs(pads, 4, 100000) == 0);         // nothing seen yet: block
    ClueRedundancyOnState(&pads[1], 0x1000, 1000);
    ClueRedundancyOnSent(&pads[1], 1000);
    CHECK(ClueRedundancyNextCheckUs(pads, 4, 1000 + 500000) == 0);  // idle 500 ms: block again
}

static void nextCheckIsDueOneIntervalAfterTheLastSend(void) {
    CLUE_PAD_STATE pads[2]; memset(pads, 0, sizeof(pads));
    ClueRedundancyOnState(&pads[0], 0x1000, 1000);
    ClueRedundancyOnSent(&pads[0], 1000);
    CHECK(ClueRedundancyNextCheckUs(pads, 2, 2000) == 5000);
}

static void nextCheckPicksTheEarliestController(void) {
    CLUE_PAD_STATE pads[3]; memset(pads, 0, sizeof(pads));
    ClueRedundancyOnState(&pads[0], 0x1000, 1000);
    ClueRedundancyOnSent(&pads[0], 2000);
    ClueRedundancyOnState(&pads[2], 0x0000, 1000);
    ClueRedundancyOnSent(&pads[2], 1500);
    CHECK(ClueRedundancyNextCheckUs(pads, 3, 2000) == 5500);
}

static void nextCheckIsNeverInThePast(void) {
    // A new state is still queued (not sent yet): the input thread must not spin on a past deadline.
    CLUE_PAD_STATE pads[1]; memset(pads, 0, sizeof(pads));
    ClueRedundancyOnState(&pads[0], 0x1000, 10000);
    CHECK(ClueRedundancyNextCheckUs(pads, 1, 10000) == 10000 + CLUE_MIN_WAKE_US);
}

int main(void) {
    buttonChangeSendsTwoCopies();
    axisOnlyChangeSendsOneCopy();
    idleStateResendsEvery4msWhileActive();
    noResendAfter500msIdle();
    noResendBeforeAnyState();
    multipleControllersKeepIndependentState();
    nextCheckIsZeroWhenNoControllerIsActive();
    nextCheckIsDueOneIntervalAfterTheLastSend();
    nextCheckPicksTheEarliestController();
    nextCheckIsNeverInThePast();
    printf(failures ? "NATIVE TESTS FAILED (%d)\n" : "NATIVE TESTS OK\n", failures);
    return failures ? 1 : 0;
}
