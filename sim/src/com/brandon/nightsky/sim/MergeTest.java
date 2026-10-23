package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.MergeProcessor;
import com.brandon.nightsky.processing.NoiseProbe;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.StarList;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The multi-night merge (G1) on synthetic nights of the real sky. The second night starts one sidereal day after
 * the first (the same stars at the same height), with the phone put down again (turned and tilted differently)
 * and fresh noise. Done when merging the two gives no doubled stars and the noise matches the combined frame
 * count: every frame of both nights must map onto the first night's reference frame as the truth does, the stars
 * of the merged stack must be as small and round as one night's, and the stack must be worth as many frames as
 * the two nights hold (its sky noise down by the square root of that). Then a second night under a brighter sky
 * with dimmer stars: its starlight must be measured as dimmer, and it must count for less.
 * <p>
 * Usage: MergeTest outDir [scale=2] [frames=16]
 */
public final class MergeTest {
    static int failures;
    /** One sidereal day (ms): the same stars return to the same place in the sky. */
    static final long SIDEREAL_DAY_MS = 86164091L;

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "merge-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 16;
        outDir.mkdirs();
        BrightStars catalog = SimRunner.loadCatalog();
        int W = 4080 / scale, H = 3072 / scale;
        CameraModel truth = CameraModel.nominal(W, H, 2875.0 / scale);
        truth.cx = W / 2.0 + 14.0 / scale;
        truth.cy = H / 2.0 - 9.0 / scale;
        truth.k1 = -0.045;
        truth.k2 = 0.018;
        truth.source = "truth";
        long start1 = SimRunner.DEFAULT_TIME_MS, start2 = start1 + SIDEREAL_DAY_MS;
        double lon = SimRunner.DEFAULT_LON;

        SkySim n1 = night(W, H, truth, catalog, start1, lon, scale);
        SkySim n2 = night(W, H, truth, catalog, start2, lon, scale);
        // put down again: turned 6 degrees, tilted a degree more and the other way
        n2.setAttitude(31, 3.6, 95);
        SensorSpec spec = n1.spec(true);
        ColorMath.compute(spec, ColorMath.DEFAULT_CCT);
        File root = new File(outDir, "sessions");
        root.mkdirs();
        SessionStore s1 = capture(n1, spec, root, frames, 7);
        SessionStore s2 = capture(n2, spec, root, frames, 8);

        // the first night alone, as it was developed
        PostProcessor p1 = new PostProcessor(s1, spec);
        p1.sky = sky(n1, catalog, start1, lon);
        long t0 = System.currentTimeMillis();
        PostProcessor.Output o1 = p1.run(false, quiet());
        System.out.printf(Locale.US, "night 1 alone: %s (%d ms)%n", o1.summary, System.currentTimeMillis() - t0);

        // the two nights merged
        t0 = System.currentTimeMillis();
        MergeProcessor.Result r = new MergeProcessor(Arrays.asList(session(s1, spec, n1, catalog, start1, lon, "night 1"),
                session(s2, spec, n2, catalog, start2, lon, "night 2"))).run(quiet());
        PostProcessor.Output om = r.out;
        System.out.printf(Locale.US, "merged: %s (%d ms)%n", om.summary, System.currentTimeMillis() - t0);
        for (String d : om.decisions) if (d.startsWith("merge")) System.out.println("  " + d);
        MergeProcessor.Part pt = r.parts.get(1);
        check(r.parts.get(0).skipped == null && pt.skipped == null, "both nights merged" + (pt.skipped != null ? ": " + pt.skipped : ""));
        if (pt.skipped != null) finish();
        check(pt.matched >= 30 && pt.rms <= 0.8, String.format(Locale.US, "the second night placed on %d shared stars at %.2f px "
                + "(the plate solutions were %.1f px off)", pt.matched, pt.rms, pt.solutionOffsetPx));
        check(r.nights == 2 && r.total.contains("over 2 nights"), "the total reads: " + r.total);

        // no doubled stars (1): every used frame of both nights maps as the truth does, the rotation they all share
        // taken out (the stack's own coordinates are arbitrary to that)
        int ref = o1.referenceUnit;
        List<double[]> est = new ArrayList<double[]>(), tru = new ArrayList<double[]>();
        List<String> who = new ArrayList<String>();
        for (PostProcessor.FrameReport f : om.frames) {
            if (!f.used || f.R == null) continue;
            boolean second = f.session.equals(s2.id());
            est.add(f.R);
            tru.add(second ? n2.trueRotationFrom(n1, ref, f.index) : n1.trueRotation(ref, f.index));
            who.add(second ? "2" : "1");
        }
        double[] g = new double[3];
        for (int i = 0; i < est.size(); i++) {
            double[] d = Rot.toRotVec(Rot.mul(Rot.transpose(est.get(i)), tru.get(i)));
            for (int c = 0; c < 3; c++) g[c] += d[c] / est.size();
        }
        double[] G = Rot.fromRotVec(g);
        double[] worst = new double[2], mean = new double[2];
        int[] cnt = new int[2];
        for (int i = 0; i < est.size(); i++) {
            int k = who.get(i).equals("2") ? 1 : 0;
            double e = SimRunner.mappingError(om.model, Rot.mul(est.get(i), G), truth, tru.get(i), W, H);
            worst[k] = Math.max(worst[k], e);
            mean[k] += e;
            cnt[k]++;
        }
        for (int k = 0; k < 2; k++) mean[k] /= Math.max(1, cnt[k]);
        check(cnt[1] >= frames - 3 && worst[1] < 0.5 && worst[0] < 0.5, String.format(Locale.US, "every frame where the truth puts it: "
                + "night 1 %d frames, mean %.3f px, worst %.3f px; night 2 %d frames, mean %.3f px, worst %.3f px", cnt[0], mean[0],
                worst[0], cnt[1], mean[1], worst[1]));

        // no doubled stars (2): the merged stack's stars as small and round as one night's
        StarList stars = o1.referenceStars;
        double[] m1 = shapes(o1.image, stars), mm = shapes(om.image, stars);
        check(mm[0] <= 1.08 * m1[0] && mm[1] <= m1[1] + 0.04, String.format(Locale.US, "stars as sharp as one night's: half-flux "
                + "radius %.3f px merged vs %.3f px, elongation %.3f vs %.3f (%d stars)", mm[0], m1[0], mm[1], m1[1], (int) mm[2]));

        // the noise matches the combined frame count
        int used1 = o1.unitsUsed, usedM = om.unitsUsed;
        double expect = usedM / (double) used1;
        double gain = om.noise != null && o1.noise != null ? om.noise.nEff / o1.noise.nEff : Double.NaN;
        check(Math.abs(gain / expect - 1) < 0.15, String.format(Locale.US, "worth %.1f frames merged, %.1f from night 1 alone: "
                + "%.2f times, for %d frames against %d (%.2f times)", om.noise.nEff, o1.noise.nEff, gain, usedM, used1, expect));
        double sn1 = skyNoise(o1.image, stars), snm = skyNoise(om.image, stars);
        double ratio = sn1 / snm;
        check(Math.abs(ratio / Math.sqrt(expect) - 1) < 0.1, String.format(Locale.US, "sky noise %.4f merged vs %.4f: down %.3f "
                + "times, the square root of the frames is %.3f", snm, sn1, ratio, Math.sqrt(expect)));

        // the coverage map: both nights over most of the frame
        int both = 0;
        for (byte b : r.coverage) if (b >= 2) both++;
        double share = both / (double) r.coverage.length;
        check(share > 0.8, String.format(Locale.US, "both nights cover %.0f%% of the first night's frame", 100 * share));
        printCoverage(r);
        save(o1, spec, new File(outDir, "night1.png"));
        save(om, spec, new File(outDir, "merged.png"));

        // a second night under a brighter sky, with dimmer stars: measured as dimmer, counted for less
        SkySim n3 = night(W, H, truth, catalog, start2, lon, scale);
        n3.setAttitude(20, 1.8, 250);
        n3.starGain = 0.8;
        for (int c = 0; c < 3; c++) n3.skyRate[c] *= 1.8;
        SessionStore s3 = capture(n3, spec, root, frames, 9);
        MergeProcessor.Result r3 = new MergeProcessor(Arrays.asList(session(s1, spec, n1, catalog, start1, lon, "night 1"),
                session(s3, spec, n3, catalog, start2, lon, "hazy night"))).run(quiet());
        MergeProcessor.Part p3 = r3.parts.get(1);
        System.out.println("hazy night: " + r3.out.summary);
        check(p3.skipped == null && Math.abs(p3.signal / 0.8 - 1) < 0.05, String.format(Locale.US, "the hazy night's starlight measured at "
                + "%.3f of the first's (0.8 made)", p3.signal));
        double g3 = r3.out.noise.nEff / o1.noise.nEff;
        check(g3 > 1.15 && g3 < 0.97 * expect, String.format(Locale.US, "the hazy night adds less: %.2f times one night's frames (two "
                + "clear nights %.2f)", g3, gain));
        finish();
    }

    static SkySim night(int W, int H, CameraModel truth, BrightStars catalog, long startMs, double lon, int scale) {
        SkySim s = new SkySim(W, H, truth, 42, catalog, startMs, lon);
        s.psfSigma = 0.85 / Math.sqrt(scale);
        return s;
    }

    static SessionStore capture(SkySim sim, SensorSpec spec, File root, int frames, long seed) throws Exception {
        SessionStore store = SessionStore.create(root);
        UnitProcessor up = new UnitProcessor(store, spec, 1, 3, new UnitProcessor.Listener() {
            @Override
            public void onUnit(UnitProcessor src, UnitRecord rec, Aligner.Result live, int unitsDone) {
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }
        });
        for (int i = 0; i < frames; i++) {
            short[] buf = up.obtainBuffer(60000);
            UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
            sim.render(i, buf, info, seed);
            up.submit(buf, info);
        }
        up.finish();
        return store;
    }

    static SessionSky sky(SkySim sim, BrightStars catalog, long startMs, double lon) {
        SessionSky sky = new SessionSky();
        sky.latDeg = sim.latitudeDeg;
        sky.lonDeg = lon;
        sky.locationSource = "simulation";
        // frame i starts at camera time (frameStart(i) + 1) s, which is startMs + frameStart(i) s in UTC
        sky.clockWallMs = startMs;
        sky.clockTimestampNs = 1_000_000_000L;
        sky.clockValid = true;
        sky.catalog = catalog;
        return sky;
    }

    static MergeProcessor.Session session(SessionStore st, SensorSpec spec, SkySim sim, BrightStars catalog, long startMs, double lon,
                                          String label) {
        MergeProcessor.Session s = new MergeProcessor.Session(st, spec);
        s.sky = sky(sim, catalog, startMs, lon);
        s.startMs = startMs;
        s.label = label;
        return s;
    }

    /**
     * Median half-flux radius and elongation (square root of the second moments' eigenvalue ratio) of the brighter
     * unsaturated stars of a list, measured in an image's green channel over a local background; and how many.
     */
    static double[] shapes(RgbImage im, StarList s) {
        int W = im.width, H = im.height;
        int n = Math.min(s.count, 400);
        double[] hfr = new double[n], el = new double[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (s.saturated[i]) continue;
            int cx = Math.round(s.x[i]), cy = Math.round(s.y[i]);
            if (cx < im.x0 + 12 || cy < im.y0 + 12 || cx >= im.x1 - 12 || cy >= im.y1 - 12) continue;
            // background: median of a ring 7..10 px out
            float[] ring = new float[600];
            int nr = 0;
            for (int dy = -10; dy <= 10; dy++) {
                for (int dx = -10; dx <= 10; dx++) {
                    int d2 = dx * dx + dy * dy;
                    if (d2 < 49 || d2 > 100) continue;
                    ring[nr++] = im.g[(cy + dy) * W + cx + dx];
                }
            }
            float[] rs = Arrays.copyOf(ring, nr);
            Arrays.sort(rs);
            double bg = rs[nr / 2];
            double sw = 0, sx = 0, sy = 0;
            for (int dy = -5; dy <= 5; dy++) {
                for (int dx = -5; dx <= 5; dx++) {
                    if (dx * dx + dy * dy > 25) continue;
                    double v = Math.max(0, im.g[(cy + dy) * W + cx + dx] - bg);
                    sw += v;
                    sx += v * dx;
                    sy += v * dy;
                }
            }
            if (sw <= 0) continue;
            double mx = sx / sw, my = sy / sw, sr = 0, sxx = 0, syy = 0, sxy = 0;
            for (int dy = -5; dy <= 5; dy++) {
                for (int dx = -5; dx <= 5; dx++) {
                    if (dx * dx + dy * dy > 25) continue;
                    double v = Math.max(0, im.g[(cy + dy) * W + cx + dx] - bg);
                    double ux = dx - mx, uy = dy - my;
                    sr += v * Math.sqrt(ux * ux + uy * uy);
                    sxx += v * ux * ux;
                    syy += v * uy * uy;
                    sxy += v * ux * uy;
                }
            }
            sxx /= sw;
            syy /= sw;
            sxy /= sw;
            double tr = sxx + syy, det = sxx * syy - sxy * sxy, disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
            double l1 = tr / 2 + disc, l2 = Math.max(1e-6, tr / 2 - disc);
            hfr[k] = sr / sw;
            el[k] = Math.sqrt(l1 / l2);
            k++;
        }
        Arrays.sort(hfr, 0, k);
        Arrays.sort(el, 0, k);
        return new double[]{k > 0 ? hfr[k / 2] : Double.NaN, k > 0 ? el[k / 2] : Double.NaN, k};
    }

    /** Robust noise of the green channel's sky in the middle half of the picture, stars masked. */
    static double skyNoise(RgbImage im, StarList s) {
        int W = im.width, H = im.height;
        long[] mask = NoiseProbe.starMask(s, W, H);
        int x0 = W / 4, x1 = 3 * W / 4, y0 = H / 4, y1 = 3 * H / 4;
        // differences of neighbours two pixels apart: the smooth sky cancels, the noise adds (times the square root of 2)
        float[] d = new float[(x1 - x0) * (y1 - y0) / 2];
        int n = 0;
        for (int y = y0; y < y1; y += 2) {
            for (int x = x0; x < x1 - 2; x += 2) {
                if (NoiseProbe.masked(mask, W, x, y) || NoiseProbe.masked(mask, W, x + 2, y)) continue;
                if (n < d.length) d[n++] = im.g[y * W + x] - im.g[y * W + x + 2];
            }
        }
        return NoiseProbe.robustSigma(d, n) / Math.sqrt(2);
    }

    static void printCoverage(MergeProcessor.Result r) {
        StringBuilder sb = new StringBuilder("coverage (sessions per cell):\n");
        for (int j = 0; j < r.coverH; j += 2) {
            sb.append("  ");
            for (int i = 0; i < r.coverW; i += 2) sb.append((char) ('0' + r.coverage[j * r.coverW + i]));
            sb.append('\n');
        }
        System.out.print(sb);
    }

    static void save(PostProcessor.Output o, SensorSpec spec, File f) throws Exception {
        final int cw = o.analysis.cw, ch = o.analysis.ch;
        final int[] pix = new int[cw * ch];
        Renderer.render(o.image, o.analysis, new Renderer.Params(), new Renderer.RowSink() {
            @Override
            public void rows(int y, int count, int[] argb, int width) {
                System.arraycopy(argb, 0, pix, y * width, count * width);
            }
        }, null);
        SimRunner.writePng(pix, cw, ch, f);
    }

    static Progress quiet() {
        return new Progress() {
            @Override
            public void update(String stage, float fraction) {
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        };
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  PASS: " : "  FAIL: ") + what);
        if (!ok) failures++;
    }

    static void finish() {
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        System.exit(failures > 0 ? 1 : 0);
    }
}
