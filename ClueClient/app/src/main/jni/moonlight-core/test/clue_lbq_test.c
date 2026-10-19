// Native tests for the timed queue wait used by the input thread to resend controller state
// (moonlight-common-c/src/LinkedBlockingQueue.c, LbqWaitForQueueElementTimeout).
// Built for arm64 together with Platform.c and run on the device by tools/native-test.sh.
#include <stdio.h>
#include <string.h>

#include "Limelight-internal.h"

// Platform.c links against these; the tests never call them.
CONNECTION_LISTENER_CALLBACKS ListenerCallbacks;
int enet_initialize(void) { return 0; }
void enet_deinitialize(void) {}
int initializePlatformSockets(void) { return 0; }
void cleanupPlatformSockets(void) {}
void enterLowLatencyMode(void) {}
void exitLowLatencyMode(void) {}

static int failures;
#define CHECK(cond) do { if (!(cond)) { printf("  FAIL %s:%d: %s\n", __func__, __LINE__, #cond); failures++; } } while (0)

static void timesOutOnAnEmptyQueue(void) {
    LINKED_BLOCKING_QUEUE q;
    void* data = NULL;
    LbqInitializeLinkedBlockingQueue(&q, 4);
    uint64_t start = PltGetMicroseconds();
    CHECK(LbqWaitForQueueElementTimeout(&q, &data, 3000) == LBQ_TIMED_OUT);
    uint64_t elapsed = PltGetMicroseconds() - start;
    CHECK(elapsed >= 3000);
    CHECK(elapsed < 50000);
    CHECK(data == NULL);
    LbqSignalQueueShutdown(&q);
    LbqDestroyLinkedBlockingQueue(&q);
}

static void zeroTimeoutPollsWithoutWaiting(void) {
    LINKED_BLOCKING_QUEUE q;
    void* data = NULL;
    LbqInitializeLinkedBlockingQueue(&q, 4);
    uint64_t start = PltGetMicroseconds();
    CHECK(LbqWaitForQueueElementTimeout(&q, &data, 0) == LBQ_TIMED_OUT);
    CHECK(PltGetMicroseconds() - start < 2000);
    LbqSignalQueueShutdown(&q);
    LbqDestroyLinkedBlockingQueue(&q);
}

static void returnsAQueuedElementAtOnce(void) {
    LINKED_BLOCKING_QUEUE q;
    LINKED_BLOCKING_QUEUE_ENTRY entry;
    int item = 42;
    void* data = NULL;
    LbqInitializeLinkedBlockingQueue(&q, 4);
    CHECK(LbqOfferQueueItem(&q, &item, &entry) == LBQ_SUCCESS);
    CHECK(LbqWaitForQueueElementTimeout(&q, &data, 100000) == LBQ_SUCCESS);
    CHECK(data == &item);
    CHECK(LbqGetItemCount(&q) == 0);
    LbqSignalQueueShutdown(&q);
    LbqDestroyLinkedBlockingQueue(&q);
}

static LINKED_BLOCKING_QUEUE offerQueue;
static LINKED_BLOCKING_QUEUE_ENTRY offerEntry;
static int offerItem = 7;

static void offerLater(void* context) {
    (void)context;
    PltSleepMs(5);
    LbqOfferQueueItem(&offerQueue, &offerItem, &offerEntry);
}

static void wakesAsSoonAsAnElementArrives(void) {
    PLT_THREAD thread;
    void* data = NULL;
    LbqInitializeLinkedBlockingQueue(&offerQueue, 4);
    uint64_t start = PltGetMicroseconds();
    CHECK(PltCreateThread("ClueLbqTest", offerLater, NULL, &thread) == 0);
    CHECK(LbqWaitForQueueElementTimeout(&offerQueue, &data, 500000) == LBQ_SUCCESS);
    uint64_t elapsed = PltGetMicroseconds() - start;
    CHECK(data == &offerItem);
    CHECK(elapsed < 100000);
    PltJoinThread(&thread);
    LbqSignalQueueShutdown(&offerQueue);
    LbqDestroyLinkedBlockingQueue(&offerQueue);
}

static void drainingEmptyQueueIsInterrupted(void) {
    LINKED_BLOCKING_QUEUE q;
    void* data = NULL;
    LbqInitializeLinkedBlockingQueue(&q, 4);
    LbqSignalQueueDrain(&q);
    CHECK(LbqWaitForQueueElementTimeout(&q, &data, 100000) == LBQ_INTERRUPTED);
    LbqDestroyLinkedBlockingQueue(&q);
}

int main(void) {
    timesOutOnAnEmptyQueue();
    zeroTimeoutPollsWithoutWaiting();
    returnsAQueuedElementAtOnce();
    wakesAsSoonAsAnElementArrives();
    drainingEmptyQueueIsInterrupted();
    printf(failures ? "NATIVE LBQ TESTS FAILED (%d)\n" : "NATIVE LBQ TESTS OK\n", failures);
    return failures ? 1 : 0;
}
