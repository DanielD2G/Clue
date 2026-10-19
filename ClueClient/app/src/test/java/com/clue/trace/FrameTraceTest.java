package com.clue.trace;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class FrameTraceTest {
    private static final class Row {
        int frame; long first, complete; int host; long decIn, decOut, release, vsync, present;
    }

    private static List<Row> rows(FrameTrace ft) {
        List<Row> out = new ArrayList<>();
        ft.forEach((frameNumber, firstPacketUs, completeUs, hostLatency01ms, decodeInNs,
                    decodeOutNs, releaseNs, targetVsyncNs, presentNs) -> {
            Row r = new Row();
            r.frame = frameNumber; r.first = firstPacketUs; r.complete = completeUs;
            r.host = hostLatency01ms; r.decIn = decodeInNs; r.decOut = decodeOutNs;
            r.release = releaseNs; r.vsync = targetVsyncNs; r.present = presentNs;
            out.add(r);
        });
        return out;
    }

    private static void fullFrame(FrameTrace ft, int n) {
        long pts = 1000L * n;
        ft.onReceived(n, 10L * n, 10L * n + 5, 23);
        ft.onDecodeInput(n, pts, 100L * n);
        ft.onDecodeOutput(pts, 100L * n + 7);
        ft.onRelease(pts, 100L * n + 9, 100L * n + 11);
        ft.onPresented(pts, 100L * n + 13);
    }

    @Test
    public void recordsAllStagesInOrder() {
        FrameTrace ft = new FrameTrace(8);
        fullFrame(ft, 5);
        List<Row> r = rows(ft);
        assertEquals(1, r.size());
        Row a = r.get(0);
        assertEquals(5, a.frame);
        assertEquals(50, a.first);
        assertEquals(55, a.complete);
        assertEquals(23, a.host);
        assertEquals(500, a.decIn);
        assertEquals(507, a.decOut);
        assertEquals(509, a.release);
        assertEquals(511, a.vsync);
        assertEquals(513, a.present);
        assertEquals(0, ft.ptsMisses());
    }

    @Test
    public void wrapsAroundWithoutMixingFrames() {
        FrameTrace ft = new FrameTrace(8);
        for (int n = 0; n < 20; n++) {
            fullFrame(ft, n);
        }
        assertEquals(8, ft.size());
        List<Row> r = rows(ft);
        for (int i = 0; i < 8; i++) {
            assertEquals(12 + i, r.get(i).frame);
            assertEquals(100L * (12 + i) + 13, r.get(i).present);
        }
    }

    @Test
    public void unpresentedFramesStayUnpresented() {
        FrameTrace ft = new FrameTrace(8);
        ft.onReceived(1, 1, 2, 0);
        ft.onDecodeInput(1, 1000, 10);
        ft.onReceived(2, 3, 4, 0);
        ft.onDecodeInput(2, 2000, 20);
        ft.onPresented(2000, 99);
        List<Row> r = rows(ft);
        assertEquals(0, r.get(0).present);
        assertEquals(99, r.get(1).present);
    }

    @Test
    public void ptsMissIsCounted() {
        FrameTrace ft = new FrameTrace(8);
        fullFrame(ft, 1);
        ft.onPresented(424242, 777);
        assertEquals(1, ft.ptsMisses());
        assertEquals(113, rows(ft).get(0).present);
    }

    @Test
    public void resetClearsPtsAssociation() {
        FrameTrace ft = new FrameTrace(8);
        ft.onReceived(1, 1, 2, 0);
        ft.onDecodeInput(1, 1000, 10);
        ft.reset();
        ft.onDecodeOutput(1000, 15);
        assertEquals(1, ft.ptsMisses());
    }
}
