package com.clue.trace;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Writes a session's traces as frames.csv, input.csv and summary.txt into a directory.
 * Runs off the streaming path (at session end), and never throws: a failed write
 * must never take the stream down.
 */
public final class TraceWriter {
    static final String FRAMES_HEADER =
            "frame,firstPacketUs,completeUs,hostLatency01ms,decodeInNs,decodeOutNs,releaseNs,targetVsyncNs,presentNs";
    static final String INPUT_HEADER = "type,eventNs,sentNs";

    private TraceWriter() {}

    /** Returns false (without throwing) if anything could not be written. */
    public static boolean write(FrameTrace frames, InputTrace input, SessionCounters counters,
                                File dir, Map<String, String> meta) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            writeFrames(frames, new File(dir, "frames.csv"));
            writeInput(input, new File(dir, "input.csv"));
            writeSummary(frames, counters, meta, new File(dir, "summary.txt"));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static Writer open(File f) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8), 1 << 16);
    }

    private static void writeFrames(FrameTrace frames, File f) throws IOException {
        try (Writer w = open(f)) {
            w.write(FRAMES_HEADER);
            w.write('\n');
            final IOException[] error = new IOException[1];
            final StringBuilder sb = new StringBuilder(128);
            frames.forEach((frameNumber, firstPacketUs, completeUs, hostLatency01ms, decodeInNs,
                            decodeOutNs, releaseNs, targetVsyncNs, presentNs) -> {
                if (error[0] != null) {
                    return;
                }
                sb.setLength(0);
                sb.append(frameNumber).append(',').append(firstPacketUs).append(',').append(completeUs)
                        .append(',').append(hostLatency01ms).append(',').append(decodeInNs)
                        .append(',').append(decodeOutNs).append(',').append(releaseNs)
                        .append(',').append(targetVsyncNs).append(',').append(presentNs).append('\n');
                try {
                    w.append(sb);
                } catch (IOException e) {
                    error[0] = e;
                }
            });
            if (error[0] != null) {
                throw error[0];
            }
        }
    }

    private static void writeInput(InputTrace input, File f) throws IOException {
        try (Writer w = open(f)) {
            w.write(INPUT_HEADER);
            w.write('\n');
            final IOException[] error = new IOException[1];
            final StringBuilder sb = new StringBuilder(64);
            input.forEach((type, eventNs, sentNs) -> {
                if (error[0] != null) {
                    return;
                }
                sb.setLength(0);
                sb.append(type).append(',').append(eventNs).append(',').append(sentNs).append('\n');
                try {
                    w.append(sb);
                } catch (IOException e) {
                    error[0] = e;
                }
            });
            if (error[0] != null) {
                throw error[0];
            }
        }
    }

    static final String INPUT_WIRE_HEADER = "type,enqueueNs,wireNs";

    /**
     * Writes inputwire.csv from {@code n} rows of (type, enqueueNs, wireNs) packed in {@code rows}.
     * Returns false (without throwing) on any failure.
     */
    public static boolean writeInputWire(long[] rows, int n, File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            StringBuilder sb = new StringBuilder(n * 32 + 32);
            sb.append(INPUT_WIRE_HEADER).append('\n');
            for (int i = 0; i < n; i++) {
                sb.append(rows[3 * i]).append(',').append(rows[3 * i + 1]).append(',').append(rows[3 * i + 2]).append('\n');
            }
            try (Writer w = open(new File(dir, "inputwire.csv"))) {
                w.append(sb);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static final String PRESENTER_HEADER = "hostNs,outNs,targetNs";
    static final String VSYNC_HEADER = "callbackNs,deadlineNs,expectedPresentNs,periodNs";

    /** Writes presenter.csv and vsync.csv. Returns false (without throwing) on any failure. */
    public static boolean writePresenter(PresenterTrace trace, File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            final StringBuilder sb = new StringBuilder(trace.frameCount() * 40 + 64);
            sb.append(PRESENTER_HEADER).append('\n');
            trace.forEachFrame((host, out, target) ->
                    sb.append(host).append(',').append(out).append(',').append(target).append('\n'));
            try (Writer w = open(new File(dir, "presenter.csv"))) {
                w.append(sb);
            }
            sb.setLength(0);
            sb.append(VSYNC_HEADER).append('\n');
            trace.forEachVsync((callback, deadline, present, period) ->
                    sb.append(callback).append(',').append(deadline).append(',').append(present)
                            .append(',').append(period).append('\n'));
            try (Writer w = open(new File(dir, "vsync.csv"))) {
                w.append(sb);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static void writeSummary(FrameTrace frames, SessionCounters counters,
                                     Map<String, String> meta, File f) throws IOException {
        try (Writer w = open(f)) {
            for (Map.Entry<String, String> e : meta.entrySet()) {
                w.write(e.getKey() + "=" + e.getValue() + "\n");
            }
            w.write("frames=" + frames.size() + "\n");
            w.write("ptsMisses=" + frames.ptsMisses() + "\n");
            w.write("audioPacketsPlayed=" + counters.audioPacketsPlayed.get() + "\n");
            w.write("audioPacketsDropped=" + counters.audioPacketsDropped.get() + "\n");
            w.write("audioUnderruns=" + counters.audioUnderruns.get() + "\n");
            w.write("audioPath=" + counters.audioPath + "\n");
            w.write("audioXruns=" + counters.audioXruns.get() + "\n");
            w.write("audioDriftFramesDropped=" + counters.audioDriftFramesDropped.get() + "\n");
            w.write("audioDriftFramesInserted=" + counters.audioDriftFramesInserted.get() + "\n");
            w.write("audioBufferFrames=" + counters.audioBufferFrames.get() + "\n");
            w.write("audioRingUs=" + counters.audioRingUs.get() + "\n");
            w.write("audioRingTargetUs=" + counters.audioRingTargetUs.get() + "\n");
            w.write("audioRingSetpointUs=" + counters.audioRingSetpointUs.get() + "\n");
            long hwN = counters.audioHwLatencySamples.get();
            w.write("audioHwLatencyUs=" + (hwN > 0 ? counters.audioHwLatencySumUs.get() / hwN : -1) + "\n");
            w.write("audioOutputLatencyUs=" + counters.audioOutputLatencyUs() + "\n");
            w.write("audioStartupTrimmedFrames=" + counters.audioStartupTrimmedFrames.get() + "\n");
            w.write("audioRingSteadyUs=" + counters.audioRingSteadyUs.get() + "\n");
            w.write("audioUnderrunsSteady=" + counters.audioUnderrunsSteady.get() + "\n");
            w.write("audioDriftFramesDroppedSteady=" + counters.audioDriftFramesDroppedSteady.get() + "\n");
            w.write("audioDriftFramesInsertedSteady=" + counters.audioDriftFramesInsertedSteady.get() + "\n");
            w.write("audioOutputLatencySteadyUs=" + counters.audioOutputLatencySteadyUs() + "\n");
        }
    }
}
