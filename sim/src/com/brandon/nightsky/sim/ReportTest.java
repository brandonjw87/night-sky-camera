package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.LocalWeights;
import com.brandon.nightsky.processing.NoiseProbe;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.ReportCard;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;
import com.brandon.nightsky.processing.Aligner;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The report card against simulated sessions with one known fault each: the fault must come first in the
 * diagnosis. And its measured noise gain against a direct measurement: the same session run twice with
 * different noise (the sky the same), so the difference of the two stacks is noise only, and so is the
 * difference of the two renderings of the reference frame; the effective number of frames from those must
 * agree with the report's within 10%.
 * <p>
 * Usage: ReportTest outDir [scale=2] [frames=24] [cases: clean cloud bump fog focus gain gaincloud]
 */
public final class ReportTest {
    interface Setup {
        void apply(SkySim s, int frames);
    }

    static final class Run {
        PostProcessor.Output out;
        SkySim sim;
        SensorSpec spec;
        ReportCard card;
        long seed;
    }

    static final TimeZone ZONE = TimeZone.getTimeZone("America/New_York");
    static BrightStars catalog;

    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "report-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 24;
        List<String> cases = new ArrayList<String>();
        for (int i = 3; i < args.length; i++) cases.add(args[i]);
        if (cases.isEmpty()) {
            for (String c : new String[]{"clean", "cloud", "bump", "fog", "focus", "gain", "gaincloud"}) cases.add(c);
        }
        catalog = SimRunner.loadCatalog();
        int failed = 0;
        for (String c : cases) {
            long t0 = System.currentTimeMillis();
            boolean ok;
            try {
                ok = runCase(c, new File(outDir, c), scale, frames);
            } catch (Throwable t) {
                t.printStackTrace(System.out);
                ok = false;
            }
            System.out.printf(Locale.US, "== %s: %s (%.0f s)%n%n", c, ok ? "PASS" : "FAIL", (System.currentTimeMillis() - t0) / 1000.0);
            if (!ok) failed++;
        }
        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    static boolean runCase(String c, File dir, int scale, final int frames) throws Exception {
        if (c.equals("gain") || c.equals("gaincloud")) {
            final boolean cloudy = c.equals("gaincloud");
            Setup s = new Setup() {
                @Override
                public void apply(SkySim sim, int n) {
                    if (cloudy) {
                        // thin cloud over a few frames and a cloud edge over part of a few others
                        sim.cloudFrom = n / 3;
                        sim.cloudTo = n / 3 + 2;
                        sim.cloudT = 0.7;
                        sim.partialFrom = (2 * n) / 3;
                        sim.partialTo = (2 * n) / 3 + 2;
                    }
                }
            };
            Run a = run(new File(dir, "a"), scale, frames, 7, -1, s);
            int ref = a.out.referenceUnit;
            Run b = run(new File(dir, "b"), scale, frames, Long.getLong("seedB", 1234567L), ref, s);
            if (b.out.referenceUnit != ref) {
                System.out.println("the second run chose another reference frame: " + b.out.referenceUnit);
                return false;
            }
            return compareGain(a, b, ref);
        }
        Setup s;
        final String expect;
        if (c.equals("clean")) {
            expect = "";
            s = null;
        } else if (c.equals("cloud")) {
            expect = "clouds";
            s = new Setup() {
                @Override
                public void apply(SkySim sim, int n) {
                    sim.cloudFrom = (3 * n) / 8;
                    sim.cloudTo = (3 * n) / 8 + 3;
                }
            };
        } else if (c.equals("bump")) {
            expect = "bump";
            s = new Setup() {
                @Override
                public void apply(SkySim sim, int n) {
                    sim.bumpFrame = n / 2;
                }
            };
        } else if (c.equals("fog")) {
            expect = "dew";
            s = new Setup() {
                @Override
                public void apply(SkySim sim, int n) {
                    sim.fogFrom = n / 3;
                    sim.fogPerFrame = 0.05;
                    sim.fogMax = 0.6;
                }
            };
        } else if (c.equals("focus")) {
            expect = "focus";
            final double sc = Math.sqrt(scale);
            s = new Setup() {
                @Override
                public void apply(SkySim sim, int n) {
                    sim.focusFrom = n / 3;
                    sim.focusPerFrame = 0.12 / sc;
                    sim.focusMax = 2.0 / sc;
                }
            };
        } else {
            System.out.println("unknown case " + c);
            return false;
        }
        Run r = run(dir, scale, frames, 7, -1, s);
        System.out.println(r.card.describe());
        String first = r.card.problems.isEmpty() ? "" : r.card.problems.get(0).key;
        System.out.printf(Locale.US, "expected first: %s, got: %s%n", expect.isEmpty() ? "(none)" : expect, first.isEmpty() ? "(none)" : first);
        boolean ok = expect.isEmpty() ? r.card.problems.isEmpty() : first.equals(expect);
        if (expect.isEmpty() && r.out.noise != null) {
            // a clean session: every frame alike, so the stack is worth about all of them
            double ne = r.out.noise.nEff;
            System.out.printf(Locale.US, "clean session: N_eff %.2f of %d frames%n", ne, frames);
            if (!(ne > 0.85 * frames && ne < 1.15 * frames)) ok = false;
        }
        return ok;
    }

    /** One simulated session through capture, the stack and the report. */
    static Run run(File dir, int scale, int frames, long seed, int forceRef, Setup setup) throws Exception {
        dir.mkdirs();
        int W = 4080 / scale, H = 3072 / scale;
        CameraModel truth = CameraModel.nominal(W, H, 2875.0 / scale);
        truth.cx = W / 2.0 + 14.0 / scale;
        truth.cy = H / 2.0 - 9.0 / scale;
        truth.k1 = -0.045;
        truth.k2 = 0.018;
        truth.source = "truth";
        long startMs = SimRunner.DEFAULT_TIME_MS;
        double lonDeg = SimRunner.DEFAULT_LON;
        SkySim sim = new SkySim(W, H, truth, 42, catalog, startMs, lonDeg);
        sim.psfSigma = 0.85 / Math.sqrt(scale);
        sim.magLimit = Double.parseDouble(System.getProperty("magLimit", String.valueOf(sim.magLimit)));
        if (setup != null) setup.apply(sim, frames);
        SensorSpec spec = sim.spec(true);
        ColorMath.compute(spec, ColorMath.DEFAULT_CCT);
        File root = new File(dir, "sessions");
        root.mkdirs();
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
        // -Donly=k: frame k alone gets this run's noise, the others the first run's (seed 7), for tests of what
        // one frame's noise does to the stack
        int only = Integer.getInteger("only", -1);
        for (int i = 0; i < frames; i++) {
            short[] buf = up.obtainBuffer(60000);
            UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
            sim.render(i, buf, info, only >= 0 && i != only ? 7 : seed);
            up.submit(buf, info);
        }
        up.finish();
        PostProcessor pp = new PostProcessor(store, spec);
        pp.forceReference = forceRef;
        pp.patternCleanup = !"0".equals(System.getProperty("pattern", "1"));
        pp.clipHigh = Float.parseFloat(System.getProperty("clipHigh", "3"));
        pp.clipLow = Float.parseFloat(System.getProperty("clipLow", "4"));
        SessionSky sky = new SessionSky();
        sky.latDeg = sim.latitudeDeg;
        sky.lonDeg = lonDeg;
        sky.locationSource = "simulation";
        sky.clockWallMs = startMs;
        sky.clockTimestampNs = 1_000_000_000L;
        sky.clockValid = true;
        sky.catalog = catalog;
        pp.sky = sky;
        PostProcessor.Output o = pp.run(false, new Progress() {
            @Override
            public void update(String stage, float fraction) {
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        });
        ReportCard.Input in = new ReportCard.Input();
        in.out = o;
        in.sky = sky;
        in.zone = ZONE;
        in.catalog = catalog;
        Run r = new Run();
        r.out = o;
        r.sim = sim;
        r.spec = spec;
        r.seed = seed;
        r.card = ReportCard.build(in);
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, "report.json")), "UTF-8");
        try {
            w.write(r.card.toJson());
        } finally {
            w.close();
        }
        store.deleteBulk();
        return r;
    }

    /**
     * The report's effective number of frames against the direct one: the noise of the stack from the two
     * stacks' difference, and of the reference frame from its two renderings' difference, sampled as the
     * stacker samples it (defective pixels replaced, the green planes' bilinear values at the pixel centres),
     * both in the same blocks with the same stars masked.
     */
    static boolean compareGain(Run a, Run b, int ref) {
        RgbImage ia = a.out.image, ib = b.out.image;
        int W = ia.width, H = ia.height;
        long[] mask = NoiseProbe.starMask(a.out.referenceStars, W, H);
        int x0 = Math.max(ia.x0, ib.x0), x1 = Math.min(ia.x1, ib.x1), y0 = Math.max(ia.y0, ib.y0), y1 = Math.min(ia.y1, ib.y1);
        int bin = NoiseProbe.binFor(W);
        // the reference frame, twice
        short[] ra = new short[W * H], rb = new short[W * H];
        a.sim.render(ref, ra, new UnitProcessor.FrameInfo(), a.seed);
        b.sim.render(ref, rb, new UnitProcessor.FrameInfo(), b.seed);
        // with the defective pixels each run found taken out, as the stacker takes them out of every frame
        if (a.out.hot != null) a.out.hot.fix(ra);
        if (b.out.hot != null) b.out.hot.fix(rb);
        int g1 = -1, g2 = -1;
        for (int q = 0; q < 4; q++) {
            if (a.spec.cfa[q] == 1) {
                if (g1 < 0) g1 = q;
                else g2 = q;
            }
        }
        float tRef = 1f;
        // the stack scales each frame's starlight (and noise) by its transparency where it is
        LocalWeights tr = null;
        for (PostProcessor.FrameReport f : a.out.frames) {
            if (f.index != ref) continue;
            if (!Float.isNaN(f.transparency)) tRef = f.transparency;
            if (f.regionT != null) {
                tr = new LocalWeights(PostProcessor.GX, PostProcessor.GY, W, H);
                System.arraycopy(f.regionT, 0, tr.w, 0, tr.w.length);
            }
        }
        // where the stacker samples the reference frame: its final rotation needn't be exactly the identity
        double[] Rref = null;
        for (PostProcessor.FrameReport f : a.out.frames) if (f.index == ref) Rref = f.R;
        CameraModel cm = a.out.model;
        if (Rref != null) System.out.printf(Locale.US, "reference frame's own rotation: %.4f px at the corner%n",
                Rot.angle(Rref) * cm.fx);
        double[] bb = new double[3], rb3 = new double[3], mp = new double[2];
        int nbx = W / bin, nby = H / bin;
        float[] ds = new float[nbx * nby], d1 = new float[nbx * nby];
        int nd = 0;
        for (int by = 0; by < nby; by++) {
            for (int bx = 0; bx < nbx; bx++) {
                int xa = bx * bin, ya = by * bin;
                if (xa < x0 + 8 || ya < y0 + 8 || xa + bin > x1 - 8 || ya + bin > y1 - 8) continue;
                double s = 0, s1 = 0;
                int k = 0;
                for (int y = ya; y < ya + bin; y++) {
                    for (int x = xa; x < xa + bin; x++) {
                        if (NoiseProbe.masked(mask, W, x, y)) continue;
                        int p = y * W + x;
                        float va = ia.g[p], vb = ib.g[p];
                        if (Float.isNaN(va) || Float.isNaN(vb)) continue;
                        // the stack has the lens shading taken out; the frames and the report's noise haven't
                        s += (va - vb) / greenGain(a.spec, x, y, g1, g2);
                        double mx = x + 0.5, my = y + 0.5;
                        if (Rref != null) {
                            cm.unproject(x + 0.5, y + 0.5, bb);
                            Rot.apply(Rref, bb[0], bb[1], bb[2], rb3);
                            if (cm.project(rb3[0], rb3[1], rb3[2], mp)) {
                                mx = mp[0];
                                my = mp[1];
                            }
                        }
                        double d = greenAt(ra, W, mx, my, g1, g2) - greenAt(rb, W, mx, my, g1, g2);
                        s1 += d / Math.min(1.5f, Math.max(0.5f, tr != null ? tr.at(x + 0.5, y + 0.5) : tRef));
                        k++;
                    }
                }
                if (k < bin * bin / 2) continue;
                ds[nd] = (float) (s / k);
                d1[nd] = (float) (s1 / k);
                nd++;
            }
        }
        double sStack = NoiseProbe.sigma(ds, nd) / Math.sqrt(2);
        // in the stack's units: the frame's values scaled by 1 / transparency (above)
        double sFrame = NoiseProbe.sigma(d1, nd) / Math.sqrt(2);
        double direct = (sFrame * sFrame) / (sStack * sStack);
        NoiseProbe.Result pa = a.out.noise, pb = b.out.noise;
        System.out.println(a.card.describe());
        System.out.printf(Locale.US, "direct: one frame %.4f, stack %.4f per block (%d blocks): N_eff %.2f%n", sFrame, sStack, nd, direct);
        System.out.printf(Locale.US, "probe A: one frame %.4f, stack %.4f (%d blocks): N_eff %.2f%n", pa.sigmaFrame, pa.sigmaStack, pa.blocks, pa.nEff);
        System.out.printf(Locale.US, "probe B: one frame %.4f, stack %.4f (%d blocks): N_eff %.2f%n", pb.sigmaFrame, pb.sigmaStack, pb.blocks, pb.nEff);
        System.out.printf(Locale.US, "reference frame's share of the mean: %.4f and %.4f (1/N = %.4f)%n", pa.singleShare, pb.singleShare,
                1.0 / Math.max(1, a.out.unitsUsed));
        float[] dsS = new float[nd];
        for (int i = 0; i < nd; i++) dsS[i] = (float) (ds[i] / Math.sqrt(2));
        diagnose("(A-B)/sqrt2", dsS, bx(nd, ia, ib, mask, x0, y0, x1, y1), W / bin);
        diagnose("probe A noise copy", pa.stackBlocks, pa.blocksX);
        diagnose("probe A single-stack", pa.singleBlocks, pa.blocksX);
        pixelSigma(ia, ib, mask, x0, y0, x1, y1, a.spec, g1, g2);
        matched(ds, d1, bx(nd, ia, ib, mask, x0, y0, x1, y1), pa);
        double ea = pa.nEff / direct - 1, eb = pb.nEff / direct - 1;
        System.out.printf(Locale.US, "probe vs direct: %+.1f%% and %+.1f%%%n", 100 * ea, 100 * eb);
        return Math.abs(ea) <= 0.10 && Math.abs(eb) <= 0.10;
    }

    /** Block positions of compareGain's blocks, in its order (for the diagnostics). */
    static int[] bx(int nd, RgbImage ia, RgbImage ib, long[] mask, int x0, int y0, int x1, int y1) {
        int W = ia.width, H = ia.height, bin = NoiseProbe.binFor(W), nbx = W / bin, nby = H / bin;
        int[] pos = new int[nd];
        int k = 0;
        for (int by = 0; by < nby; by++) {
            for (int bx = 0; bx < nbx; bx++) {
                int xa = bx * bin, ya = by * bin;
                if (xa < x0 + 8 || ya < y0 + 8 || xa + bin > x1 - 8 || ya + bin > y1 - 8) continue;
                int c = 0;
                for (int y = ya; y < ya + bin; y++) {
                    for (int x = xa; x < xa + bin; x++) {
                        if (NoiseProbe.masked(mask, W, x, y)) continue;
                        int p = y * W + x;
                        if (Float.isNaN(ia.g[p]) || Float.isNaN(ib.g[p])) continue;
                        c++;
                    }
                }
                if (c < bin * bin / 2) continue;
                if (k < nd) pos[k] = by * nbx + bx;
                k++;
            }
        }
        return pos;
    }

    /** Blocks given in order with their grid positions: spread to a grid, then diagnose. */
    static void diagnose(String name, float[] v, int[] pos, int nbx) {
        int max = 0;
        for (int p : pos) max = Math.max(max, p + 1);
        float[] g = new float[max + nbx];
        java.util.Arrays.fill(g, Float.NaN);
        for (int i = 0; i < pos.length; i++) g[pos[i]] = v[i];
        diagnose(name, g, nbx);
    }

    /**
     * Noise structure of a block grid (NaN = none): robust sigma; lag-1 correlation across and down; sigma
     * after taking out the mean of the 4 neighbours (scaled for white noise); and of 2x2 super-blocks
     * (times 2, so white noise keeps its value).
     */
    static void diagnose(String name, float[] g, int nbx) {
        int n = g.length, nby = n / nbx;
        float[] all = new float[n], hp = new float[n], sb = new float[n];
        int na = 0, nh = 0, ns = 0;
        double sxy = 0, sxx = 0, syy = 0, sy2 = 0, sxy2 = 0, syy2 = 0;
        double mean = 0;
        int nm = 0;
        for (float f : g) if (!Float.isNaN(f)) {
            mean += f;
            nm++;
        }
        mean /= Math.max(1, nm);
        for (int y = 0; y < nby; y++) {
            for (int x = 0; x < nbx; x++) {
                float c = g[y * nbx + x];
                if (Float.isNaN(c)) continue;
                all[na++] = c;
                if (x + 1 < nbx && !Float.isNaN(g[y * nbx + x + 1])) {
                    double a = c - mean, b = g[y * nbx + x + 1] - mean;
                    sxy += a * b;
                    sxx += a * a;
                    syy += b * b;
                }
                if (y + 1 < nby && !Float.isNaN(g[(y + 1) * nbx + x])) {
                    double a = c - mean, b = g[(y + 1) * nbx + x] - mean;
                    sy2 += a * b;
                    sxy2 += a * a;
                    syy2 += b * b;
                }
                if (x > 0 && y > 0 && x + 1 < nbx && y + 1 < nby) {
                    float l = g[y * nbx + x - 1], r = g[y * nbx + x + 1], u = g[(y - 1) * nbx + x], d = g[(y + 1) * nbx + x];
                    if (!Float.isNaN(l) && !Float.isNaN(r) && !Float.isNaN(u) && !Float.isNaN(d))
                        hp[nh++] = (float) ((c - (l + r + u + d) / 4) * Math.sqrt(4.0 / 5.0));
                }
                if ((x & 1) == 0 && (y & 1) == 0 && x + 1 < nbx && y + 1 < nby) {
                    float r = g[y * nbx + x + 1], d = g[(y + 1) * nbx + x], rd = g[(y + 1) * nbx + x + 1];
                    if (!Float.isNaN(r) && !Float.isNaN(d) && !Float.isNaN(rd)) sb[ns++] = (c + r + d + rd) / 4 * 2;
                }
            }
        }
        System.out.printf(Locale.US, "  %-22s sigma %.4f (%d)  corr across %+.2f down %+.2f  high-pass %.4f  2x2 %.4f%n", name,
                NoiseProbe.sigma(all, na), na, sxy / Math.sqrt(sxx * syy), sy2 / Math.sqrt(sxy2 * syy2),
                NoiseProbe.sigma(hp, nh), NoiseProbe.sigma(sb, ns));
    }

    /** Per pixel: the robust sigma of the two stacks' difference over sqrt(2), away from stars. */
    static void pixelSigma(RgbImage ia, RgbImage ib, long[] mask, int x0, int y0, int x1, int y1, SensorSpec spec, int g1, int g2) {
        int W = ia.width, H = ia.height, gw = (W >> 4) + 1;
        float[] gain = new float[gw * ((H >> 4) + 1)];
        for (int y = 0; y < H; y += 16) for (int x = 0; x < W; x += 16) gain[(y >> 4) * gw + (x >> 4)] = greenGain(spec, Math.min(W - 1, x + 8), Math.min(H - 1, y + 8), g1, g2);
        float[] v = new float[(x1 - x0) * (y1 - y0) / 16 + 16];
        int n = 0;
        for (int y = y0 + 8; y < y1 - 8; y += 4) {
            for (int x = x0 + 8; x < x1 - 8; x += 4) {
                if (NoiseProbe.masked(mask, W, x, y)) continue;
                int p = y * W + x;
                if (Float.isNaN(ia.g[p]) || Float.isNaN(ib.g[p]) || n >= v.length) continue;
                v[n++] = (ia.g[p] - ib.g[p]) / gain[(y >> 4) * gw + (x >> 4)];
            }
        }
        System.out.printf(Locale.US, "  per pixel A-B: %.4f (%d)%n", NoiseProbe.sigma(v, n) / Math.sqrt(2), n);
    }

    /** Direct and probe on the blocks both measured: the block sets' difference taken out of the comparison. */
    static void matched(float[] ds, float[] d1, int[] pos, NoiseProbe.Result pr) {
        int n = pos.length;
        float[] a = new float[n], b = new float[n], c = new float[n], e = new float[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            float st = pr.stackBlocks[pos[i]], si = pr.singleBlocks[pos[i]];
            if (Float.isNaN(st) || Float.isNaN(si)) continue;
            a[k] = (float) (ds[i] / Math.sqrt(2));
            b[k] = (float) (d1[i] / Math.sqrt(2));
            c[k] = st;
            e[k] = si;
            k++;
        }
        double sa = NoiseProbe.sigma(a, k), sb = NoiseProbe.sigma(b, k), sc = NoiseProbe.sigma(c, k), se = NoiseProbe.sigma(e, k);
        double sh = Double.isNaN(pr.singleShare) ? 0 : pr.singleShare;
        double pf = Math.sqrt(Math.max(0, (se * se - sc * sc) / (1 - 2 * sh)));
        System.out.printf(Locale.US, "  same %d blocks: direct frame %.4f stack %.4f (N_eff %.2f); probe frame %.4f stack %.4f (N_eff %.2f)%n",
                k, sb, sa, sb * sb / (sa * sa), pf, sc, pf * pf / (sc * sc));
    }

    /** The stack's lens shading gain for green at pixel (x, y) (1 without a shading map). */
    static float greenGain(SensorSpec spec, int x, int y, int g1, int g2) {
        if (spec.shading == null || spec.shading.isUnity()) return 1f;
        return 0.5f * (spec.shading.gain(g1, x + 0.5, y + 0.5) + spec.shading.gain(g2, x + 0.5, y + 0.5));
    }

    /** Green at frame position (mx, my) as the stacker samples it: the mean of the two green planes' bilinear values. */
    static double greenAt(short[] raw, int W, double mx, double my, int g1, int g2) {
        return 0.5 * (planeAt(raw, W, mx, my, g1) + planeAt(raw, W, mx, my, g2));
    }

    private static double planeAt(short[] raw, int W, double mx, double my, int p) {
        int ox = p & 1, oy = p >> 1;
        double fx = (mx - 0.5 - ox) * 0.5, fy = (my - 0.5 - oy) * 0.5;
        int iu = (int) Math.floor(fx), iv = (int) Math.floor(fy);
        double ax = fx - iu, ay = fy - iv;
        int i = (2 * iv + oy) * W + 2 * iu + ox;
        if (iu < 0 || iv < 0 || i + 2 * W + 2 >= raw.length) return 0;
        double d00 = raw[i] & 0xFFFF, d01 = raw[i + 2] & 0xFFFF, d10 = raw[i + 2 * W] & 0xFFFF, d11 = raw[i + 2 * W + 2] & 0xFFFF;
        return (1 - ay) * (d00 + ax * (d01 - d00)) + ay * (d10 + ax * (d11 - d10));
    }

    /** Green at pixel (x, y) as the stacker samples it: the mean of the two green planes' bilinear values at its centre. */
    static double green(short[] raw, int W, int x, int y, int g1, int g2) {
        return 0.5 * (plane(raw, W, x, y, g1) + plane(raw, W, x, y, g2));
    }

    private static double plane(short[] raw, int W, int x, int y, int p) {
        int ox = p & 1, oy = p >> 1;
        double fx = (x - ox) * 0.5, fy = (y - oy) * 0.5;
        int iu = (int) Math.floor(fx), iv = (int) Math.floor(fy);
        double ax = fx - iu, ay = fy - iv;
        int i = (2 * iv + oy) * W + 2 * iu + ox;
        if (iu < 0 || iv < 0 || i + 2 * W + 2 >= raw.length) return 0;
        double d00 = raw[i] & 0xFFFF, d01 = raw[i + 2] & 0xFFFF, d10 = raw[i + 2 * W] & 0xFFFF, d11 = raw[i + 2 * W + 2] & 0xFFFF;
        return (1 - ay) * (d00 + ax * (d01 - d00)) + ay * (d10 + ax * (d11 - d10));
    }
}
