import com.clue.video.PresentPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Replays a recorded Clue session (presenter.csv + vsync.csv) through a presenter and a model of
 * SurfaceFlinger, so presenter policies can be compared offline on the exact arrivals and vsync
 * timelines of the device.
 *
 * SurfaceFlinger model: at each recorded vsync k it latches at deadline_k + latchOffset, takes the
 * newest queued buffer whose desired present time is due (<= expectedPresent_k), drops older due
 * buffers, and presents it at expectedPresent_k. The latch offset is calibrated once against the
 * device's measured queue->present (dumpsys SurfaceFlinger --frametimeline).
 *
 * Usage: tools/presenter-sim.sh <session dir> [latchOffsetMs]
 */
public class PresenterSim {
    record Frame(long host, long out) {}
    record Vsync(long callback, long deadline, long present, long period) {}

    interface Presenter {
        /** Called in time order. Returns buffers queued at this event: {queueTime, desired, frameIndex}. */
        void onVsync(Vsync v, List<long[]> queue);
        void onFrame(int index, Frame f, List<long[]> queue);
    }

    /** Clue presenter: release at decode-out with the policy's target. */
    static final class CluePresenter implements Presenter {
        final PresentPolicy policy;
        CluePresenter(PresentPolicy policy) { this.policy = policy; }
        public void onVsync(Vsync v, List<long[]> queue) { policy.onVsync(v.deadline, v.present, v.period); }
        public void onFrame(int index, Frame f, List<long[]> queue) {
            long t = policy.targetFor(f.out, f.host);
            queue.add(new long[] {f.out, t == 0 ? f.out : t, index});
        }
    }

    /** Upstream balanced pacer: queue of 2, oldest released at each vsync callback, due immediately. */
    static final class BalancedPresenter implements Presenter {
        final ArrayDeque<long[]> pending = new ArrayDeque<>();
        public void onVsync(Vsync v, List<long[]> queue) {
            long[] p = pending.poll();
            if (p != null) queue.add(new long[] {v.callback, 0, p[2]});
        }
        public void onFrame(int index, Frame f, List<long[]> queue) {
            if (pending.size() == 2) pending.poll();
            pending.add(new long[] {f.out, 0, index});
        }
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        long latchOffsetNs = (long) ((args.length > 1 ? Double.parseDouble(args[1]) : 0.0) * 1e6);
        List<Frame> frames = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("presenter.csv")).subList(1, Files.readAllLines(dir.resolve("presenter.csv")).size())) {
            String[] c = line.split(",");
            long host = Long.parseLong(c[0]);
            if (host > 0) frames.add(new Frame(host, Long.parseLong(c[1])));
        }
        List<Vsync> vsyncs = new ArrayList<>();
        List<String> vl = Files.readAllLines(dir.resolve("vsync.csv"));
        for (String line : vl.subList(1, vl.size())) {
            String[] c = line.split(",");
            vsyncs.add(new Vsync(Long.parseLong(c[0]), Long.parseLong(c[1]), Long.parseLong(c[2]), Long.parseLong(c[3])));
        }
        // -Dskip=k drops every k-th frame, as if the host skipped it (a game below the stream rate)
        int skip = Integer.getInteger("skip", 0);
        if (skip > 1) {
            List<Frame> kept = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                if (i % skip != skip - 1) kept.add(frames.get(i));
            }
            frames = kept;
        }
        System.out.printf("session: %d frames, %d vsyncs, latch offset %.2f ms, skip %d%n",
                frames.size(), vsyncs.size(), latchOffsetNs / 1e6, skip);

        run("balanced (upstream)", new BalancedPresenter(), frames, vsyncs, latchOffsetNs);
        run("immediate (upstream latency)", new ImmediatePresenter(), frames, vsyncs, latchOffsetNs);
        Presenter v1 = loadV1();
        if (v1 != null) {
            run("clue v1 (arrival PLL)", v1, frames, vsyncs, latchOffsetNs);
        }
        // Each variant: safetyMs,hysteresisMs,marginPerMille
        for (String spec : System.getProperty("variants", "1.0,0.5,20").split(";")) {
            String[] p = spec.split(",");
            PresentPolicy policy = new PresentPolicy((long) (Double.parseDouble(p[0]) * 1e6),
                    (long) (Double.parseDouble(p[1]) * 1e6), Integer.parseInt(p[2]));
            run("clue s=" + p[0] + " h=" + p[1] + " pm=" + p[2], new CluePresenter(policy), frames, vsyncs, latchOffsetNs);
        }
    }

    /** Upstream "lowest latency" pacing: release at decode-out, due immediately. */
    static final class ImmediatePresenter implements Presenter {
        public void onVsync(Vsync v, List<long[]> queue) {}
        public void onFrame(int index, Frame f, List<long[]> queue) {
            queue.add(new long[] {f.out, f.out, index});
        }
    }

    /** Optional reference: a PresentPolicyV1 class on the classpath with targetFor(now). */
    static Presenter loadV1() {
        try {
            Class<?> c = Class.forName("com.clue.video.PresentPolicyV1");
            Object p = c.getConstructor(long.class, long.class).newInstance(1_000_000L, 500_000L);
            var onVsync = c.getMethod("onVsync", long.class, long.class, long.class);
            var targetFor = c.getMethod("targetFor", long.class);
            return new Presenter() {
                public void onVsync(Vsync v, List<long[]> queue) {
                    try { onVsync.invoke(p, v.deadline, v.present, v.period); } catch (Exception e) { throw new RuntimeException(e); }
                }
                public void onFrame(int index, Frame f, List<long[]> queue) {
                    try {
                        long t = (long) targetFor.invoke(p, f.out);
                        queue.add(new long[] {f.out, t == 0 ? f.out : t, index});
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
            };
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    static void run(String name, Presenter presenter, List<Frame> frames, List<Vsync> vsyncs, long latchOffsetNs) {
        List<long[]> queued = new ArrayList<>();   // {queueTime, desired, frameIndex}
        long[] presentedAt = new long[frames.size()];
        int fi = 0, vi = 0;
        int drops = 0, emptyVsyncs = 0;
        int lastPresented = -1;
        // Event loop over frames (at decode-out) and vsync callbacks, plus SF latches at deadlines.
        List<Vsync> pendingLatch = new ArrayList<>();
        while (fi < frames.size() || vi < vsyncs.size()) {
            long tf = fi < frames.size() ? frames.get(fi).out : Long.MAX_VALUE;
            long tv = vi < vsyncs.size() ? vsyncs.get(vi).callback : Long.MAX_VALUE;
            // Process SF latches due before the next event
            long next = Math.min(tf, tv);
            while (!pendingLatch.isEmpty() && pendingLatch.get(0).deadline + latchOffsetNs <= next) {
                Vsync v = pendingLatch.remove(0);
                long latch = v.deadline + latchOffsetNs;
                long[] best = null;
                List<long[]> due = new ArrayList<>();
                for (long[] b : queued) {
                    if (b[0] <= latch && b[1] <= v.present) due.add(b);
                }
                for (long[] b : due) if (best == null || b[2] > best[2]) best = b;
                if (best != null) {
                    for (long[] b : due) {
                        queued.remove(b);
                        if (b != best) drops++;
                    }
                    presentedAt[(int) best[2]] = v.present;
                    lastPresented = (int) best[2];
                } else if (lastPresented >= 0) {
                    emptyVsyncs++;
                }
            }
            if (tv <= tf) {
                Vsync v = vsyncs.get(vi++);
                presenter.onVsync(v, queued);
                // The latch for this callback's earliest timeline happens at its deadline
                pendingLatch.add(v);
            } else {
                presenter.onFrame(fi, frames.get(fi), queued);
                fi++;
            }
        }
        // Pacing errors: consecutive presented frames whose on-screen interval differs from their host
        // interval rounded to vsyncs (judder), whether or not a frame was dropped.
        long period = vsyncs.get(vsyncs.size() / 2).period > 0 ? vsyncs.get(vsyncs.size() / 2).period : 8_333_333L;
        int pacingErrors = 0, pairs = 0, prev = -1;
        for (int i = 0; i < frames.size(); i++) {
            if (presentedAt[i] == 0) continue;
            if (prev >= 0) {
                long hostVsyncs = Math.round((double) (frames.get(i).host - frames.get(prev).host) / period);
                long shownVsyncs = Math.round((double) (presentedAt[i] - presentedAt[prev]) / period);
                pairs++;
                if (hostVsyncs != shownVsyncs) pacingErrors++;
            }
            prev = i;
        }
        System.out.printf("%-34s pacing errors %.2f%% (%d/%d)%n", "", 100.0 * pacingErrors / Math.max(1, pairs), pacingErrors, pairs);

        long[] lat = new long[frames.size()];
        int n = 0;
        for (int i = 0; i < frames.size(); i++) {
            if (presentedAt[i] > 0) lat[n++] = presentedAt[i] - frames.get(i).out;
        }
        lat = Arrays.copyOf(lat, n);
        Arrays.sort(lat);
        System.out.printf("%-34s out->present p50=%6.2f p90=%6.2f p99=%6.2f ms | presented %d/%d, dropped %d, empty vsyncs %d%n",
                name, lat[n / 2] / 1e6, lat[(int) (n * 0.9)] / 1e6, lat[(int) (n * 0.99)] / 1e6,
                n, frames.size(), drops, emptyVsyncs);
    }
}
