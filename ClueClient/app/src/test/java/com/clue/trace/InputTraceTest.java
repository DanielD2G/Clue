package com.clue.trace;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class InputTraceTest {
    private static List<long[]> rows(InputTrace it) {
        List<long[]> out = new ArrayList<>();
        it.forEach((type, eventNs, sentNs) -> out.add(new long[]{type, eventNs, sentNs}));
        return out;
    }

    @Test
    public void pairsEventWithSend() {
        InputTrace it = new InputTrace(8);
        int slot = it.onEvent(InputTrace.GAMEPAD, 100);
        it.onSent(slot, 130);
        List<long[]> r = rows(it);
        assertEquals(1, r.size());
        assertEquals(InputTrace.GAMEPAD, r.get(0)[0]);
        assertEquals(100, r.get(0)[1]);
        assertEquals(130, r.get(0)[2]);
    }

    @Test
    public void unsentEventKeepsZero() {
        InputTrace it = new InputTrace(8);
        it.onEvent(InputTrace.MOUSE, 50);
        assertEquals(0, rows(it).get(0)[2]);
    }

    @Test
    public void wrapsAround() {
        InputTrace it = new InputTrace(4);
        for (int i = 0; i < 6; i++) {
            it.onSent(it.onEvent(InputTrace.KEYBOARD, i), i + 1000);
        }
        assertEquals(4, it.size());
        List<long[]> r = rows(it);
        for (int i = 0; i < 4; i++) {
            assertEquals(i + 2, r.get(i)[1]);
        }
    }
}
