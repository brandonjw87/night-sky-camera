package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.DngWriter;
import com.brandon.nightsky.processing.LiveStats;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.Rejection;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * End-to-end test on a simulated session, through the same classes the app uses: live unit processing,
 * post-processing, rendering, DNG. Prints alignment errors against the known truth.
 * <p>
 * Usage: SimRunner outDir [scale=1|2] [frames=37] [calibrated=true|false] [mode=deep|trails] [events=true|false]
 */
public final class SimRunner {
    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "sim-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 37;
        boolean calibrated = args.length > 3 && Boolean.parseBoolean(args[3]);
        boolean trails = args.length > 4 && args[4].equals("trails");
        boolean events = args.length <= 5 || Boolean.parseBoolean(args[5]);
        outDir.mkdirs();

        int W = 4080 / scale, H = 3072 / scale;
        CameraModel truth = CameraModel.nominal(W, H, 2875.0 / scale);
        truth.cx = W / 2.0 + 14.0 / scale;
        truth.cy = H / 2.0 - 9.0 / scale;
        truth.k1 = -0.045;
        truth.k2 = 0.018;
        truth.source = "truth";
        SkySim sim = new SkySim(W, H, truth, 42);
        if (events) {
            sim.satelliteFrame = frames / 4;
            sim.planeFrame = frames / 2;
            sim.meteorFrame = (frames * 2) / 3;
            sim.cloudFrom = frames / 3;
            sim.cloudTo = frames / 3 + 1;
            sim.partialFrom = (frames * 3) / 4;
            sim.partialTo = (frames * 3) / 4 + 1;
            sim.bumpFrame = frames - 4;
        }
        // SIM_PARTIAL=from,to: frames clouded over the left part of the field; SIM_CLOUD=from,to: clouded all over
        int[] partial = range(System.getenv("SIM_PARTIAL"));
        if (partial != null) {
            sim.partialFrom = partial[0];
            sim.partialTo = partial[1];
        }
        if (System.getenv("SIM_TRANSIENTS") != null) {
            // only what outlier rejection has to remove: a satellite, a plane and a meteor
            sim.satelliteFrame = frames / 4;
            sim.planeFrame = frames / 2;
            sim.meteorFrame = (frames * 2) / 3;
        }
        // SIM_STARS=0.05: stars that much fainter (a sparse field); SIM_BRANCH=1: a branch over the left edge
        if (System.getenv("SIM_STARS") != null) sim.starGain = Double.parseDouble(System.getenv("SIM_STARS"));
        sim.branch = System.getenv("SIM_BRANCH") != null;
        int[] overcast = range(System.getenv("SIM_CLOUD"));
        if (overcast != null) {
            sim.cloudFrom = overcast[0];
            sim.cloudTo = overcast[1];
        }
        sim.psfSigma = 0.85 / Math.sqrt(scale);
        System.out.printf(Locale.US, "Sim %dx%d, %d stars, %d frames of %.0fs, calibrated=%s, mode=%s%n", W, H, sim.starCount(),
                frames, sim.frameExp, calibrated, trails ? "trails" : "deep");

        SensorSpec spec = sim.spec(calibrated);
        ColorMath.compute(spec, ColorMath.DEFAULT_CCT);
        File root = new File(outDir, "sessions");
        root.mkdirs();
        SessionStore store = SessionStore.create(root);
        long t0 = System.currentTimeMillis();
        final int[] aligned = {0};
        // what the remote viewer sees while capturing: provisional verdicts and the latest frame
        final LiveStats liveStats = new LiveStats();
        final UnitProcessor.Latest[] latest = {null};
        UnitProcessor up = new UnitProcessor(store, spec, 1, 3, new UnitProcessor.Listener() {
            @Override
            public void onUnit(UnitProcessor src, UnitRecord rec, Aligner.Result live, int unitsDone) {
                if (live.ok) aligned[0]++;
                liveStats.add(rec, live, System.currentTimeMillis());
                if (src.latest() != null) latest[0] = src.latest();
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }
        });
        up.setKeepLatest(true);
        long simMs = 0;
        for (int i = 0; i < frames; i++) {
            short[] buf = up.obtainBuffer(60000);
            UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
            long a = System.currentTimeMillis();
            sim.render(i, buf, info, 7);
            simMs += System.currentTimeMillis() - a;
            up.submit(buf, info);
        }
        up.finish();
        long t1 = System.currentTimeMillis();
        System.out.printf(Locale.US, "capture: %d ms (render %d ms), live aligned %d/%d%n", t1 - t0, simMs, aligned[0], frames);

        RgbImage prev = up.preview().image();
        if (prev != null) {
            Renderer.Analysis pa = Renderer.analyze(prev, spec);
            int[] px = Renderer.renderSmall(prev.binned(1), pa, new Renderer.Params(), 1);
            writePng(px, prev.cropWidth(), prev.cropHeight(), new File(outDir, "preview.png"));
            if (latest[0] != null) {
                // the latest frame as the viewer shows it: the live stack's crop and stretch
                RgbImage li = latest[0].image;
                RgbImage view = new RgbImage(li.width, li.height, li.r, li.g, li.b);
                view.x0 = pa.cx0;
                view.y0 = pa.cy0;
                view.x1 = pa.cx0 + pa.cw;
                view.y1 = pa.cy0 + pa.ch;
                RgbImage ls = view.binned(1);
                int[] lp = Renderer.renderSmall(ls, pa, new Renderer.Params(), 1);
                writePng(lp, ls.width, ls.height, new File(outDir, "latest.png"));
                System.out.printf(Locale.US, "latest frame %d: clipped red %.5f green %.5f blue %.5f%n", latest[0].index,
                        latest[0].clipped[0], latest[0].clipped[1], latest[0].clipped[2]);
            }
        }
        for (LiveStats.Unit u : liveStats.from(0)) {
            System.out.printf(Locale.US, "  live %2d T=%.2f hfr=%.2f stars=%d %s%n", u.index, u.transparency, u.hfr, u.stars,
                    u.reason == null ? "kept" : "left out: " + u.reason);
        }

        if (System.getenv("SIM_HOTTEST") != null) hotTest(sim, store, spec, W, H);
        PostProcessor pp = new PostProcessor(store, spec);
        // SIM_REJECT=winsorized|percentile|gesd|linear_fit|sigma; SIM_STACKMEM=MB for the stack's sample buffers
        if (System.getenv("SIM_REJECT") != null) pp.rejection = Rejection.Method.parse(System.getenv("SIM_REJECT"));
        if (System.getenv("SIM_STACKMEM") != null) pp.stackMemory = Long.parseLong(System.getenv("SIM_STACKMEM")) << 20;
        final long[] last = {0};
        PostProcessor.Output o = pp.run(trails, new Progress() {
            @Override
            public void update(String stage, float fraction) {
                long now = System.currentTimeMillis();
                if (now - last[0] > 3000 || fraction >= 1f) {
                    last[0] = now;
                    System.out.printf(Locale.US, "  %s %.0f%%%n", stage, fraction * 100);
                }
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        });
        long t2 = System.currentTimeMillis();
        System.out.printf(Locale.US, "post: %d ms -> %s%n", t2 - t1, o.summary);
        System.out.println("analysis: " + o.analysis.describe());
        System.out.println("model: " + o.model);

        // alignment accuracy against the truth
        if (!trails && o.referenceUnit >= 0) {
            double worst = 0, sum = 0;
            int n = 0;
            for (PostProcessor.FrameReport f : o.frames) {
                if (f.R == null) {
                    System.out.printf(Locale.US, "  unit %2d %-8s %s%n", f.index, "-", f.reason);
                    continue;
                }
                double err = mappingError(o.model, f.R, truth, sim.trueRotation(o.referenceUnit, f.index), W, H);
                double merr = f.measuredR != null ? mappingError(o.model, f.measuredR, truth, sim.trueRotation(o.referenceUnit, f.index), W, H) : Double.NaN;
                System.out.printf(Locale.US, "  unit %2d %-8s err %.3f px (measured %.3f, %d matched, path sigma %.3f)  T=%.2f w=%.2f stars=%d %s%s%n", f.index,
                        f.used ? "used" : "skipped", err, merr, f.matched, f.alignSigma, f.transparency, f.weight, f.stars, f.reason, f.predicted ? " (predicted)" : "");
                if (f.used) {
                    worst = Math.max(worst, err);
                    sum += err;
                    n++;
                }
            }
            System.out.printf(Locale.US, "alignment error over used frames: mean %.3f px, worst %.3f px%n", n > 0 ? sum / n : 0, worst);
            // the same, frame against frame: the output's own coordinates are arbitrary, so take out the rotation
            // all frames share (what sharpness depends on)
            double[] g = new double[3];
            int ng = 0;
            for (PostProcessor.FrameReport f : o.frames) {
                if (f.R == null || !f.used) continue;
                double[] d = Rot.toRotVec(Rot.mul(Rot.transpose(f.R), sim.trueRotation(o.referenceUnit, f.index)));
                for (int c = 0; c < 3; c++) g[c] += d[c];
                ng++;
            }
            if (ng > 0) {
                double[] G = Rot.fromRotVec(g[0] / ng, g[1] / ng, g[2] / ng);
                double rw = 0, rs = 0;
                for (PostProcessor.FrameReport f : o.frames) {
                    if (f.R == null || !f.used) continue;
                    double err = mappingError(o.model, Rot.mul(f.R, G), truth, sim.trueRotation(o.referenceUnit, f.index), W, H);
                    rw = Math.max(rw, err);
                    rs += err;
                }
                System.out.printf(Locale.US, "frame-to-frame alignment error (common rotation taken out): mean %.3f px, worst %.3f px%n", rs / ng, rw);
            }
        }

        String near = System.getenv("SIM_NEAR");
        if (near != null && o.referenceUnit >= 0) {
            for (String pr : near.split(";")) {
                String[] xy = pr.split(",");
                double qx = Double.parseDouble(xy[0].trim()), qy = Double.parseDouble(xy[1].trim());
                System.out.printf(Locale.US, "truth near %.0f,%.0f (reference unit %d):%n%s", qx, qy, o.referenceUnit,
                        sim.starsNear(o.referenceUnit, qx, qy, 6));
            }
        }
        if (System.getenv("SIM_COLORTEST") != null && o.referenceUnit >= 0) colorTest(sim.starTable(o.referenceUnit), o.image);
        if (System.getenv("SIM_DUMP") != null) {
            // raw float32 planes of the whole stacked image (r, g, b) + clip bytes, for offline analysis
            RgbImage im = o.image;
            java.io.DataOutputStream ds = new java.io.DataOutputStream(new BufferedOutputStream(
                    new FileOutputStream(new File(outDir, "stack_" + im.width + "x" + im.height + ".f32")), 1 << 20));
            for (int c = 0; c < 3; c++) for (float v : im.channel(c)) ds.writeFloat(v);
            if (im.clip != null) ds.write(im.clip);
            ds.close();
            java.io.PrintWriter pw = new java.io.PrintWriter(new File(outDir, "stack.txt"), "UTF-8");
            pw.printf(Locale.US, "width %d%nheight %d%ncrop %d %d %d %d%nreference %d%n", im.width, im.height, im.x0, im.y0,
                    im.x1, im.y1, o.referenceUnit);
            pw.close();
        }
        // render
        final int cw = o.analysis.cw, ch = o.analysis.ch;
        final int[] pix = new int[cw * ch];
        Renderer.render(o.image, o.analysis, new Renderer.Params(), new Renderer.RowSink() {
            @Override
            public void rows(int y, int count, int[] argb, int width) {
                System.arraycopy(argb, 0, pix, y * width, count * width);
            }
        }, null);
        long t3 = System.currentTimeMillis();
        System.out.printf(Locale.US, "render: %d ms%n", t3 - t2);
        writePng(pix, cw, ch, new File(outDir, trails ? "trails.png" : "stack.png"));
        if (System.getenv("SIM_DEBUG") != null) debugStars(o, pix, cw, ch, spec);
        // crops at native resolution: centre and a corner
        writeCrop(pix, cw, ch, cw / 2 - 300, ch / 2 - 200, 600, 400, new File(outDir, "crop_center.png"));
        writeCrop(pix, cw, ch, cw - 620, 20, 600, 400, new File(outDir, "crop_corner.png"));

        DngWriter.Meta meta = new DngWriter.Meta();
        meta.exposureSeconds = o.integrationSeconds;
        meta.iso = 700;
        meta.description = o.summary;
        meta.baselineExposure = 2.0;
        OutputStream os = new BufferedOutputStream(new FileOutputStream(new File(outDir, "stack.dng")), 1 << 20);
        try {
            DngWriter.write(os, o.image, spec, meta, o.maxGain);
        } finally {
            os.close();
        }
        System.out.printf(Locale.US, "dng: %d ms%n", System.currentTimeMillis() - t3);
        System.out.println("session log: " + store.logFile());
    }

    /** "12,17" as {12, 17}; null when unset. */
    static int[] range(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        String[] p = s.split(",");
        return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[p.length - 1].trim())};
    }

    /** Prints stacked camera RGB and rendered output around a few bright and medium stars. */
    static void debugStars(PostProcessor.Output o, int[] pix, int cw, int ch, SensorSpec spec) {
        RgbImage img = o.image;
        // find local maxima of G in the crop, sort by brightness
        java.util.List<int[]> peaks = new java.util.ArrayList<int[]>();
        for (int y = img.y0 + 4; y < img.y1 - 4; y += 1) {
            for (int x = img.x0 + 4; x < img.x1 - 4; x++) {
                int i = y * img.width + x;
                float v = img.g[i];
                if (v < 30) continue;
                boolean max = true;
                for (int dy = -2; dy <= 2 && max; dy++) for (int dx = -2; dx <= 2; dx++) {
                    if ((dx != 0 || dy != 0) && img.g[i + dy * img.width + dx] >= v) { max = false; break; }
                }
                if (max) peaks.add(new int[]{x, y, (int) v});
            }
        }
        java.util.Collections.sort(peaks, new java.util.Comparator<int[]>() {
            public int compare(int[] a, int[] b) { return b[2] - a[2]; }
        });
        // the brightest peaks inside the centre crop, then a spread of brightness ranks overall
        java.util.List<int[]> chosen = new java.util.ArrayList<int[]>();
        int cx0 = img.x0 + cw / 2 - 300, cy0 = img.y0 + ch / 2 - 200;
        for (int[] pk : peaks) {
            if (pk[0] >= cx0 && pk[0] < cx0 + 600 && pk[1] >= cy0 && pk[1] < cy0 + 400) chosen.add(pk);
            if (chosen.size() >= 4) break;
        }
        int[] pick = {0, 20, 100, 400};
        for (int k : pick) if (k < peaks.size()) chosen.add(peaks.get(k));
        // SIM_PROBE=x,y;x,y... : neighbourhoods at centre-crop coordinates (snapped to the local G maximum)
        String probe = System.getenv("SIM_PROBE");
        if (probe != null) {
            chosen.clear();
            for (String pr : probe.split(";")) {
                String[] xy = pr.split(",");
                int px = cx0 + Integer.parseInt(xy[0].trim()), py = cy0 + Integer.parseInt(xy[1].trim());
                int bx = px, by = py;
                for (int dy = -3; dy <= 3; dy++) for (int dx = -3; dx <= 3; dx++) {
                    if (img.g[(py + dy) * img.width + px + dx] > img.g[by * img.width + bx]) {
                        bx = px + dx;
                        by = py + dy;
                    }
                }
                chosen.add(new int[]{bx, by, (int) img.g[by * img.width + bx]});
            }
        }
        for (int k = 0; k < chosen.size(); k++) {
            int[] pk = chosen.get(k);
            System.out.printf(Locale.US, "(crop_center at %d,%d) ", pk[0] - cx0, pk[1] - cy0);
            System.out.printf(Locale.US, "star #%d at %d,%d peakG=%d%n", k, pk[0], pk[1], pk[2]);
            for (int dy = -3; dy <= 3; dy++) {
                StringBuilder a = new StringBuilder("  cam ");
                StringBuilder b = new StringBuilder("  out ");
                for (int dx = -3; dx <= 3; dx++) {
                    int i = (pk[1] + dy) * img.width + pk[0] + dx;
                    a.append(String.format(Locale.US, "%5.0f/%5.0f/%5.0f%s", img.r[i], img.g[i], img.b[i],
                            img.clip != null && img.clip[i] != 0 ? "*" : " "));
                    int ox = pk[0] + dx - img.x0, oy = pk[1] + dy - img.y0;
                    int c = pix[oy * cw + ox];
                    b.append(String.format(Locale.US, "%3d/%3d/%3d       ", (c >> 16) & 255, (c >> 8) & 255, c & 255));
                }
                System.out.println(a);
                System.out.println(b);
            }
        }
    }

    /** Hot pixel detection against the simulated defects. */
    static void hotTest(SkySim sim, SessionStore store, SensorSpec spec, int W, int H) {
        int[] cnt = new int[1];
        float[] sum = store.readSum(W * H, cnt);
        for (int i = 0; i < sum.length; i++) sum[i] /= cnt[0];
        com.brandon.nightsky.processing.HotPixels hp = com.brandon.nightsky.processing.HotPixels.detect(sum, W, H, spec.cfa, 8f);
        int[] idx = sim.hotPixelIndices();
        float[] rate = sim.hotPixelRates();
        java.util.BitSet truth = new java.util.BitSet(W * H);
        int[] found = new int[4], total = new int[4];
        double[] edges = {0, 5, 15, 100, 1e9};
        for (int i = 0; i < idx.length; i++) {
            truth.set(idx[i]);
            int b = 0;
            while (b < 3 && rate[i] * sim.frameExp >= edges[b + 1] * 8) b++;
            total[b]++;
            if (hp.isBad(idx[i] % W, idx[i] / W)) found[b]++;
        }
        int fp = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) if (hp.isBad(x, y) && !truth.get(y * W + x)) fp++;
        System.out.printf(Locale.US, "hot pixels: detected %d, false %d; found by dark rate <5/s %d/%d, 5-15 %d/%d, 15-100 %d/%d, >100 %d/%d%n",
                hp.count(), fp, found[0], total[0], found[1], total[1], found[2], total[2], found[3], total[3]);
    }

    /**
     * Colour accuracy on isolated, unclipped stars: stacked flux ratios (R/G, B/G within 4.5 px, local
     * background from an annulus) against the simulated truth, in brightness bins.
     */
    static void colorTest(double[][] stars, RgbImage img) {
        int W = img.width, H = img.height;
        // brightest-first neighbour check on a coarse grid
        java.util.List<double[]> sel = new java.util.ArrayList<double[]>();
        for (double[] s : stars) {
            if (s[2] < 15) continue;
            int x = (int) s[0], y = (int) s[1];
            if (x < img.x0 + 16 || y < img.y0 + 16 || x >= img.x1 - 16 || y >= img.y1 - 16) continue;
            boolean iso = true;
            for (double[] t : stars) {
                if (t == s) continue;
                double d = Math.hypot(t[0] - s[0], t[1] - s[1]);
                if (d < 9 && t[2] > 0.03 * s[2]) { iso = false; break; }
            }
            if (!iso) continue;
            boolean clipped = false;
            for (int dy = -3; dy <= 3 && !clipped; dy++) for (int dx = -3; dx <= 3; dx++) {
                if (img.clip != null && img.clip[(y + dy) * W + x + dx] != 0) { clipped = true; break; }
            }
            if (clipped) continue;
            sel.add(s);
        }
        double[] edges = {15, 40, 100, 250, 1e9};
        for (int bI = 0; bI + 1 < edges.length; bI++) {
            java.util.List<double[]> rr = new java.util.ArrayList<double[]>();
            for (double[] s : sel) {
                if (s[2] < edges[bI] || s[2] >= edges[bI + 1]) continue;
                double[] tot = new double[3];
                float[] ann = new float[600];
                for (int c = 0; c < 3; c++) {
                    float[] ch = img.channel(c);
                    int na = 0;
                    double sum = 0;
                    int n = 0;
                    for (int dy = -14; dy <= 14; dy++) for (int dx = -14; dx <= 14; dx++) {
                        double px = Math.floor(s[0]) + dx + 0.5, py = Math.floor(s[1]) + dy + 0.5;
                        double d = Math.hypot(px - s[0], py - s[1]);
                        int i = ((int) Math.floor(s[1]) + dy) * W + (int) Math.floor(s[0]) + dx;
                        if (d < 4.5) { sum += ch[i]; n++; }
                        else if (d >= 8 && d < 14 && na < ann.length) ann[na++] = ch[i];
                    }
                    java.util.Arrays.sort(ann, 0, na);
                    tot[c] = sum - n * ann[na / 2];
                }
                if (tot[1] <= 0) continue;
                rr.add(new double[]{(tot[0] / tot[1]) / s[3], (tot[2] / tot[1]) / s[4]});
            }
            if (rr.isEmpty()) continue;
            double[] a = new double[rr.size()], b = new double[rr.size()];
            for (int i = 0; i < rr.size(); i++) { a[i] = rr.get(i)[0]; b[i] = rr.get(i)[1]; }
            java.util.Arrays.sort(a);
            java.util.Arrays.sort(b);
            int n = a.length;
            System.out.printf(Locale.US, "colour test flux %4.0f-%4.0f/s: %4d stars, measured/true R/G median %.3f (16-84%%: %.3f-%.3f), B/G %.3f (%.3f-%.3f)%n",
                    edges[bI], Math.min(edges[bI + 1], 9999), n, a[n / 2], a[n * 16 / 100], a[n * 84 / 100], b[n / 2], b[n * 16 / 100], b[n * 84 / 100]);
        }
    }

    /** Largest pixel disagreement between the estimated and true reference->frame mapping over a grid. */
    static double mappingError(CameraModel est, double[] R, CameraModel truth, double[] Rt, int W, int H) {
        double worst = 0;
        double[] b = new double[3], p = new double[2], q = new double[2];
        for (int gy = 0; gy <= 6; gy++) {
            for (int gx = 0; gx <= 8; gx++) {
                double x = 20 + (W - 40) * gx / 8.0, y = 20 + (H - 40) * gy / 6.0;
                est.unproject(x, y, b);
                Rot.apply(R, b[0], b[1], b[2], b);
                est.project(b[0], b[1], b[2], p);
                truth.unproject(x, y, b);
                Rot.apply(Rt, b[0], b[1], b[2], b);
                truth.project(b[0], b[1], b[2], q);
                worst = Math.max(worst, Math.hypot(p[0] - q[0], p[1] - q[1]));
            }
        }
        return worst;
    }

    static void writePng(int[] argb, int w, int h, File f) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        ImageIO.write(img, "png", f);
    }

    static void writeCrop(int[] argb, int w, int h, int x0, int y0, int cw, int ch, File f) throws Exception {
        x0 = Math.max(0, Math.min(w - cw, x0));
        y0 = Math.max(0, Math.min(h - ch, y0));
        BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, cw, ch, argb, y0 * w + x0, w);
        ImageIO.write(img, "png", f);
    }
}
