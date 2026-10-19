package com.clue.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

public class TraceWriterTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writesHeadersAndRows() throws Exception {
        FrameTrace ft = new FrameTrace(8);
        for (int n = 1; n <= 2; n++) {
            ft.onReceived(n, 10 * n, 10 * n + 5, 3);
            ft.onDecodeInput(n, 1000L * n, 100L * n);
            ft.onPresented(1000L * n, 100L * n + 50);
        }
        InputTrace it = new InputTrace(8);
        it.onSent(it.onEvent(InputTrace.GAMEPAD, 7), 9);
        SessionCounters counters = new SessionCounters();
        counters.audioPacketsDropped.set(4);

        File dir = tmp.newFolder("session");
        assertTrue(TraceWriter.write(ft, it, counters, dir, Collections.singletonMap("fps", "120")));

        List<String> frames = Files.readAllLines(new File(dir, "frames.csv").toPath(), StandardCharsets.UTF_8);
        assertEquals("frame,firstPacketUs,completeUs,hostLatency01ms,decodeInNs,decodeOutNs,releaseNs,targetVsyncNs,presentNs",
                frames.get(0));
        assertEquals(3, frames.size());
        assertEquals("1,10,15,3,100,0,0,0,150", frames.get(1));

        List<String> input = Files.readAllLines(new File(dir, "input.csv").toPath(), StandardCharsets.UTF_8);
        assertEquals("type,eventNs,sentNs", input.get(0));
        assertEquals(2, input.size());
        assertEquals("1,7,9", input.get(1));

        List<String> summary = Files.readAllLines(new File(dir, "summary.txt").toPath(), StandardCharsets.UTF_8);
        assertTrue(summary.contains("fps=120"));
        assertTrue(summary.contains("audioPacketsDropped=4"));
        assertTrue(summary.contains("audioOutputLatencyUs=-1"));
        assertTrue(summary.contains("ptsMisses=0"));
    }

    @Test
    public void writeFailureDoesNotThrow() throws Exception {
        File notADir = tmp.newFile("plain-file");
        assertFalse(TraceWriter.write(new FrameTrace(8), new InputTrace(8), new SessionCounters(),
                notADir, Collections.emptyMap()));
    }

    @Test
    public void writesInputWireCsv() throws Exception {
        long[] rows = {1, 100, 250, 2, 300, 310};
        File dir = tmp.newFolder("wire");
        assertTrue(TraceWriter.writeInputWire(rows, 2, dir));
        List<String> lines = Files.readAllLines(new File(dir, "inputwire.csv").toPath(), StandardCharsets.UTF_8);
        assertEquals("type,enqueueNs,wireNs", lines.get(0));
        assertEquals("1,100,250", lines.get(1));
        assertEquals("2,300,310", lines.get(2));
        assertEquals(3, lines.size());
    }
}
