package com.clue.video;

import java.util.concurrent.locks.LockSupport;

/**
 * Hands MediaCodec input buffer indices from the async codec callback thread (single producer) to
 * the decode-unit submit thread (single consumer).
 *
 * Lock-free and allocation-free after construction. The consumer can wait for an index with a
 * bound: it parks and the producer unparks it on the next offer. clear() is only safe while neither
 * side runs, which the renderer guarantees by calling it with all codec threads quiesced.
 */
public final class InputIndexRing {
    private final int[] slots;
    private final int mask;

    // Free-running counters; slot = counter & mask. head is written only by the consumer,
    // tail only by the producer (and both by clear()).
    private volatile long head;
    private volatile long tail;
    private volatile Thread waiter;

    public InputIndexRing(int minCapacity) {
        int cap = 1;
        while (cap < minCapacity) {
            cap <<= 1;
        }
        slots = new int[cap];
        mask = cap - 1;
    }

    /** Producer side. Returns false if the ring is full (the index is not stored). */
    public boolean offer(int index) {
        long t = tail;
        if (t - head == slots.length) {
            return false;
        }
        slots[(int) (t & mask)] = index;
        tail = t + 1; // publishes the slot write
        Thread w = waiter;
        if (w != null) {
            LockSupport.unpark(w);
        }
        return true;
    }

    /** Consumer side. Returns the oldest index, or -1 if there is none. */
    public int poll() {
        long h = head;
        if (h == tail) {
            return -1;
        }
        int index = slots[(int) (h & mask)];
        head = h + 1;
        return index;
    }

    /** Consumer side. Waits at most timeoutNs for an index; returns -1 on timeout or interrupt. */
    public int take(long timeoutNs) {
        int index = poll();
        if (index >= 0) {
            return index;
        }
        long deadline = System.nanoTime() + timeoutNs;
        Thread self = Thread.currentThread();
        waiter = self;
        try {
            for (;;) {
                // Re-check after publishing waiter: an offer that raced with us either is seen
                // here or sees waiter and unparks us.
                index = poll();
                if (index >= 0) {
                    return index;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0 || self.isInterrupted()) {
                    return -1;
                }
                LockSupport.parkNanos(this, remaining);
            }
        } finally {
            waiter = null;
        }
    }

    /** Drops all indices. Only call while neither producer nor consumer is running. */
    public void clear() {
        head = tail;
    }
}
