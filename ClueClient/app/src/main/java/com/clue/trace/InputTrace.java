package com.clue.trace;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Event-to-send timing for user input, in a preallocated ring. "Sent" means the
 * moment the event was handed to moonlight-common-c (its send call returned);
 * the on-the-wire time is measured once common-c is vendored (H3).
 * All times are CLOCK_MONOTONIC nanoseconds. Slots are claimed atomically, so
 * events from different threads never share a slot.
 */
public final class InputTrace {
    public static final byte GAMEPAD = 1;
    public static final byte MOUSE = 2;
    public static final byte KEYBOARD = 3;
    public static final byte TOUCH = 4;

    public interface InputVisitor {
        void visit(byte type, long eventNs, long sentNs);
    }

    private final int mask;
    private final byte[] type;
    private final long[] eventNs;
    private final long[] sentNs;
    private final AtomicLong head = new AtomicLong();

    public InputTrace(int capacityPow2) {
        if (capacityPow2 <= 0 || Integer.bitCount(capacityPow2) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        mask = capacityPow2 - 1;
        type = new byte[capacityPow2];
        eventNs = new long[capacityPow2];
        sentNs = new long[capacityPow2];
    }

    /** Records an input event and returns its slot for {@link #onSent}. */
    public int onEvent(byte eventType, long eventTimeNs) {
        int s = (int) (head.getAndIncrement() & mask);
        sentNs[s] = 0;
        type[s] = eventType;
        eventNs[s] = eventTimeNs;
        return s;
    }

    /** Marks the event in {@code slot} as handed to the network layer. A negative slot is ignored. */
    public void onSent(int slot, long sentTimeNs) {
        if (slot >= 0) {
            sentNs[slot & mask] = sentTimeNs;
        }
    }

    public int size() {
        return (int) Math.min(head.get(), mask + 1);
    }

    /** Visits recorded events from oldest to newest. */
    public void forEach(InputVisitor v) {
        long end = head.get();
        long start = Math.max(0, end - (mask + 1));
        for (long i = start; i < end; i++) {
            int s = (int) (i & mask);
            v.visit(type[s], eventNs[s], sentNs[s]);
        }
    }
}
