package com.clue.trace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class PresenterTraceTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void ringKeepsNewestOldestFirst() {
        PresenterTrace pt = new PresenterTrace(2);
        pt.onFrame(1, 10, 0);
        pt.onFrame(2, 20, 0);
        pt.onFrame(3, 30, 35);
        final StringBuilder sb = new StringBuilder();
        pt.forEachFrame((host, out, target) -> sb.append(host).append(':').append(out).append(':').append(target).append(' '));
        assertEquals("2:20:0 3:30:35 ", sb.toString());
        assertEquals(2, pt.frameCount());
    }

    @Test
    public void writesPresenterAndVsyncCsv() throws Exception {
        PresenterTrace pt = new PresenterTrace(8);
        pt.onFrame(1000, 2000, 2500);
        pt.onVsync(100, 200, 300, 8333333);
        File dir = tmp.newFolder("session");
        assertTrue(TraceWriter.writePresenter(pt, dir));

        List<String> frames = Files.readAllLines(new File(dir, "presenter.csv").toPath(), StandardCharsets.UTF_8);
        assertEquals("hostNs,outNs,targetNs", frames.get(0));
        assertEquals("1000,2000,2500", frames.get(1));
        List<String> vsyncs = Files.readAllLines(new File(dir, "vsync.csv").toPath(), StandardCharsets.UTF_8);
        assertEquals("callbackNs,deadlineNs,expectedPresentNs,periodNs", vsyncs.get(0));
        assertEquals("100,200,300,8333333", vsyncs.get(1));
    }
}
