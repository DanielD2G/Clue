import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes a Clue session trace folder (frames.csv, input.csv, summary.txt).
 *
 * Usage:
 *   java tools/TraceAnalyze.java <session-folder> [--vsync-ms 8.333]
 *   java tools/TraceAnalyze.java --selftest
 *
 * Prints n/p50/p95/p99/max per stage in ms. Negative deltas (clock or pairing
 * errors) are counted and reported, never folded into the percentiles.
 */
public class TraceAnalyze {
    static final class Stage {
        final String name;
        final List<Double> values = new ArrayList<>();
        int negative;

        Stage(String name) { this.name = name; }

        void add(double ms) {
            if (ms < 0) {
                negative++;
            } else {
                values.add(ms);
            }
        }

        double pct(double q) {
            if (values.isEmpty()) {
                return Double.NaN;
            }
            List<Double> s = new ArrayList<>(values);
            s.sort(null);
            int rank = (int) Math.ceil(q * s.size());
            return s.get(Math.max(0, rank - 1));
        }

        String row() {
            return String.format("%-28s n=%-7d p50=%7.2f p95=%7.2f p99=%7.2f max=%8.2f negative=%d",
                    name, values.size(), pct(0.50), pct(0.95), pct(0.99), pct(1.0), negative);
        }
    }

    static final class Result {
        final Stage net = new Stage("red+ensamblado");
        final Stage completeToDecodeIn = new Stage("completo->entrada decoder");
        final Stage decode = new Stage("decodificacion");
        final Stage decodeToRelease = new Stage("salida decoder->release");
        final Stage completeToPresent = new Stage("completo->pantalla(prog.)");
        final Stage input = new Stage("entrada->envio");
        int frames, presented, intervals;
        int[] intervalHist = new int[4]; // 1x, 2x, 3x, 4+x vsync
    }

    static Result analyze(List<String> framesCsv, List<String> inputCsv, double vsyncMs) {
        Result r = new Result();
        long lastPresent = 0;
        for (int i = 1; i < framesCsv.size(); i++) {
            String line = framesCsv.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(",");
            long firstUs = Long.parseLong(f[1]), completeUs = Long.parseLong(f[2]);
            long decInNs = Long.parseLong(f[4]), decOutNs = Long.parseLong(f[5]);
            long relNs = Long.parseLong(f[6]), presentNs = Long.parseLong(f[8]);
            r.frames++;
            if (firstUs > 0 && completeUs > 0) {
                r.net.add((completeUs - firstUs) / 1000.0);
            }
            if (decInNs > 0 && completeUs > 0) {
                r.completeToDecodeIn.add((decInNs / 1000.0 - completeUs) / 1000.0);
            }
            if (decOutNs > 0 && decInNs > 0) {
                r.decode.add((decOutNs - decInNs) / 1e6);
            }
            if (relNs > 0 && decOutNs > 0) {
                r.decodeToRelease.add((relNs - decOutNs) / 1e6);
            }
            if (presentNs > 0) {
                r.presented++;
                if (completeUs > 0) {
                    r.completeToPresent.add((presentNs / 1000.0 - completeUs) / 1000.0);
                }
                if (lastPresent > 0 && presentNs > lastPresent) {
                    double k = (presentNs - lastPresent) / 1e6 / vsyncMs;
                    int bucket = (int) Math.min(3, Math.max(0, Math.round(k) - 1));
                    r.intervalHist[bucket]++;
                    r.intervals++;
                }
                lastPresent = presentNs;
            }
        }
        for (int i = 1; i < inputCsv.size(); i++) {
            String line = inputCsv.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(",");
            long eventNs = Long.parseLong(f[1]), sentNs = Long.parseLong(f[2]);
            if (sentNs > 0) {
                r.input.add((sentNs - eventNs) / 1e6);
            }
        }
        return r;
    }

    static void print(Result r) {
        System.out.println("frames=" + r.frames + " presented=" + r.presented);
        for (Stage s : new Stage[]{r.net, r.completeToDecodeIn, r.decode, r.decodeToRelease,
                r.completeToPresent, r.input}) {
            System.out.println(s.row());
        }
        if (r.intervals > 0) {
            System.out.printf("cadencia (programada): 1x=%.1f%% 2x=%.1f%% 3x=%.1f%% 4+x=%.1f%% (intervalos=%d)%n",
                    100.0 * r.intervalHist[0] / r.intervals, 100.0 * r.intervalHist[1] / r.intervals,
                    100.0 * r.intervalHist[2] / r.intervals, 100.0 * r.intervalHist[3] / r.intervals,
                    r.intervals);
        }
    }

    static int selftest() {
        List<String> frames = List.of(
                "frame,firstPacketUs,completeUs,hostLatency01ms,decodeInNs,decodeOutNs,releaseNs,targetVsyncNs,presentNs",
                "1,1000,2000,0,2100000,6100000,7000000,0,10000000",   // net 1 ms, decode 4 ms
                "2,3000,5000,0,5100000,11100000,12000000,0,18333333", // net 2 ms, decode 6 ms
                "3,9000,8000,0,8100000,10100000,0,0,0");               // net negative -> reported
        List<String> input = List.of("type,eventNs,sentNs", "1,1000000,3000000", "2,5,0");
        Result r = analyze(frames, input, 8.333);
        boolean ok = true;
        if (r.net.negative != 1) { System.out.println("FAIL negativeDeltasAreReportedNotAveraged: negative=" + r.net.negative); ok = false; }
        if (r.net.values.size() != 2) { System.out.println("FAIL net count " + r.net.values.size()); ok = false; }
        if (Math.abs(r.net.pct(0.5) - 1.0) > 1e-9) { System.out.println("FAIL net p50 " + r.net.pct(0.5)); ok = false; }
        if (Math.abs(r.decode.pct(0.5) - 4.0) > 1e-9) { System.out.println("FAIL decode p50 " + r.decode.pct(0.5)); ok = false; }
        if (Math.abs(r.input.pct(0.5) - 2.0) > 1e-9) { System.out.println("FAIL input p50 " + r.input.pct(0.5)); ok = false; }
        if (r.intervalHist[0] != 1) { System.out.println("FAIL cadence 1x " + r.intervalHist[0]); ok = false; }
        System.out.println(ok ? "SELFTEST OK" : "SELFTEST FAILED");
        return ok ? 0 : 1;
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: java tools/TraceAnalyze.java <session-folder> [--vsync-ms 8.333] | --selftest");
            System.exit(2);
        }
        if (args[0].equals("--selftest")) {
            System.exit(selftest());
        }
        double vsyncMs = 8.333;
        for (int i = 1; i + 1 < args.length; i++) {
            if (args[i].equals("--vsync-ms")) {
                vsyncMs = Double.parseDouble(args[i + 1]);
            }
        }
        Path dir = Path.of(args[0]);
        Path summary = dir.resolve("summary.txt");
        if (Files.exists(summary)) {
            Files.readAllLines(summary, StandardCharsets.UTF_8).forEach(l -> System.out.println("  " + l));
        }
        print(analyze(Files.readAllLines(dir.resolve("frames.csv"), StandardCharsets.UTF_8),
                Files.readAllLines(dir.resolve("input.csv"), StandardCharsets.UTF_8), vsyncMs));
    }
}
