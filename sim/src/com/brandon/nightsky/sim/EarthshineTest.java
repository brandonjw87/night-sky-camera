package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Earthshine;
import com.brandon.nightsky.processing.MoonFinder;
import com.brandon.nightsky.processing.MoonGeo;
import com.brandon.nightsky.processing.MoonLook;
import com.brandon.nightsky.processing.MoonStack;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.SensorSpec;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import javax.imageio.ImageIO;

/**
 * The Earth's glow (3.1) off the phone: a crescent Moon with a real earthshine (a few thousandths of the crescent's
 * brightness, bluer than the Sun's light), shot as the Moonshot shoots it: the burst metered for the crescent, then,
 * after a moment of the hand moving and the phone rolling, 120 frames 66 times brighter (eight times longer, eight
 * times the ISO), the crescent burnt white and the lens's wide glare around it, a twilight sky with a slope, hot
 * pixels. The glow merged into the stack is checked against the simulated Moon's own: its brightness, its seas, its
 * colour, no seam at the terminator, the sunlit crescent untouched, the sky black, the glare gone; and the merge
 * saying no when there is nothing to merge.
 */
public final class EarthshineTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what + (detail.isEmpty() ? "" : ": " + detail));
        if (!ok) failed++;
    }

    /** The lens's wide halo in the simulator (not the merge's own five spreads): sigma (px) and share of the light. */
    static final double[][] HALO = {{2, 3e-3}, {5, 2.5e-3}, {11, 2e-3}, {24, 1.5e-3}, {50, 1.2e-3}, {105, 1e-3}};

    /** The camera: electrons at the white level at the burst's ISO, read noise (electrons), and the glow's longer exposure and ISO. */
    static final double FULL = 575, READ = 1.6, LONGER = 8.25, ISO = 8;

    /** The glare around the Moon, in its own frame: (2N+1) px square per colour, the Moon's centre in the middle. */
    static float[][] glare(MoonSim sim, int N) {
        int n = 2 * N + 1;
        float[][] out = new float[3][];
        for (int c = 0; c < 3; c++) {
            float[] src = new float[n * n];
            double rr = Math.pow(1.15 * sim.rPx, 2);
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    double dx = i - N, dy = j - N;
                    if (dx * dx + dy * dy > rr) continue;
                    src[j * n + i] = (float) sim.truthBox(c, dx, dy, 1);
                }
            }
            float[] g = new float[n * n];
            for (double[] h : HALO) {
                float[] b = MoonSim.blur(src, n, h[0]);
                for (int k = 0; k < g.length; k++) g[k] += (float) (h[1] * b[k]);
            }
            out[c] = g;
        }
        return out;
    }

    /** The camera's view: one RAW crop. {@code photons}: electrons for the full Moon's 1 (per the sensor's own colour). */
    static short[] shot(MoonSim sim, float[][] glare, int N, double sky, int w, int h, int x0, int y0, double mx, double my, double rot, double blur,
                        double photons, double ePerDn, Random rnd, Set<Integer> hot, double hotE) {
        short[] out = new short[w * h];
        int[] cfa = {0, 1, 1, 2};
        double a = -rot * Math.PI / 180, cs = Math.cos(a), sn = Math.sin(a);
        double[] skyTint = {0.8, 1.0, 1.3};
        int n = 2 * N + 1;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int X = x0 + i, Y = y0 + j;
                int c = cfa[(Y & 1) * 2 + (X & 1)];
                double L = sim.light(c, X, Y, mx, my, rot, blur);
                double dx = X - mx, dy = Y - my;
                double rx = dx * cs + dy * sn + N, ry = -dx * sn + dy * cs + N;
                if (glare != null && rx >= 0 && ry >= 0 && rx < n - 1 && ry < n - 1) {
                    int ix = (int) rx, iy = (int) ry;
                    double fx = rx - ix, fy = ry - iy;
                    float[] g = glare[c];
                    int k = iy * n + ix;
                    L += (g[k] * (1 - fx) + g[k + 1] * fx) * (1 - fy) + (g[k + n] * (1 - fx) + g[k + n + 1] * fx) * fy;
                }
                // the twilight: bluer, brighter towards the bottom of the sensor
                L += sky * skyTint[c] * (1 + 0.004 * (Y - 1530));
                double e = L * sim.camRgb[c] * photons;
                if (hot != null && hot.contains(Y * 4080 + X)) e += hotE;
                e += Math.sqrt(Math.max(0, e) + READ * READ) * rnd.nextGaussian();
                long dn = Math.round(e / ePerDn + sim.black);
                out[j * w + i] = (short) Math.max(0, Math.min(sim.white, dn));
            }
        }
        return out;
    }

    /** A hand-held burst (as MoonshotTest's): the Moon's place, roll and blur frame by frame, the crop following the lit part. */
    static final class Hand {
        final Random r;
        double X, Y, rot, t;

        Hand(long seed, double X, double Y) {
            r = new Random(seed);
            this.X = X;
            this.Y = Y;
        }

        /** The next frame's {mx, my, rot, blur}. */
        double[] next() {
            t += 1 / 30.0;
            X += 0.8 / 30 + 0.12 * r.nextGaussian();
            Y += 0.3 / 30 + 0.12 * r.nextGaussian();
            double mx = X + 0.35 * Math.sin(2 * Math.PI * 9.1 * t + 0.4) + 0.2 * Math.sin(2 * Math.PI * 11.3 * t + 1.3);
            double my = Y + 0.3 * Math.sin(2 * Math.PI * 8.7 * t + 2.1) + 0.25 * Math.sin(2 * Math.PI * 12.1 * t);
            rot += 0.012 * r.nextGaussian();
            double u = r.nextDouble();
            double blur = u < 0.6 ? 0.2 + 0.3 * r.nextDouble() : u < 0.9 ? 0.6 + 0.4 * r.nextDouble() : 1.2 + 0.6 * r.nextDouble();
            return new double[]{mx, my, rot, blur};
        }
    }

    /** Where the lit part's middle is, from the Moon's centre (sensor px, the sensor's frame), as the camera's finder has it. */
    static double[] litMiddle(MoonSim sim, double rot) {
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9, top = 0;
        int R = (int) Math.ceil(sim.rPx) + 2;
        for (int j = -R; j <= R; j++) for (int i = -R; i <= R; i++) top = Math.max(top, sim.light(1, i, j, 0, 0, rot, 0));
        for (int j = -R; j <= R; j++) {
            for (int i = -R; i <= R; i++) {
                if (sim.light(1, i, j, 0, 0, rot, 0) < 0.3 * top) continue;
                minX = Math.min(minX, i);
                maxX = Math.max(maxX, i);
                minY = Math.min(minY, j);
                maxY = Math.max(maxY, j);
            }
        }
        return new double[]{(minX + maxX) / 2, (minY + maxY) / 2};
    }

    static final class Shot {
        final MoonshotTest.Burst main = new MoonshotTest.Burst();
        final List<MoonStack.Frame> glow = new ArrayList<MoonStack.Frame>();
        final List<Double> gains = new ArrayList<Double>();
        /** The glow frames' truth: {mx - x0, my - y0, rot}. */
        final List<double[]> glowWhere = new ArrayList<double[]>();
    }

    /**
     * The burst and its glow. {@code photons}: the burst's electrons for the full Moon's 1; {@code gain}: the glow's
     * light against the burst's; between them the hand moves {@code jump} px and rolls {@code roll} degrees.
     */
    static Shot shoot(MoonSim sim, float[][] glare, int N, double sky, int nMain, int nGlow, double photons, double longer, double iso,
                      double jump, double roll, long seed, Set<Integer> hot) {
        Shot s = new Shot();
        Hand hand = new Hand(seed, 2040.3, 1530.7);
        double ePerDn = FULL / (sim.white - sim.black);
        int side = MoonFinder.side(sim.rPx), sideG = MoonFinder.side(sim.rPx * 1.5);
        double[] prev = {hand.X, hand.Y};
        for (int i = 0; i < nMain; i++) {
            double[] m = hand.next();
            double[] lm = litMiddle0(sim, m[2]);
            int[] c = MoonFinder.corner(prev[0] + lm[0], prev[1] + lm[1], side, 4080, 3072);
            short[] raw = shot(sim, glare, N, sky, side, side, c[0], c[1], m[0], m[1], m[2], m[3], photons, ePerDn, hand.r, null, 0);
            MoonStack.Frame f = new MoonStack.Frame(raw, side, side, c[0], c[1], (long) (hand.t * 1e9));
            f.upPa = sim.upPa - m[2];
            s.main.frames.add(f);
            s.main.where.add(new double[]{m[0] - c[0], m[1] - c[1], m[2], m[3], 1});
            prev = new double[]{m[0], m[1]};
        }
        // the exposure changing (a few frames), the hand moving
        for (int i = 0; i < 6; i++) hand.next();
        hand.X += jump;
        hand.Y -= 0.6 * jump;
        hand.rot += roll;
        for (int i = 0; i < nGlow; i++) {
            double[] m = hand.next();
            double[] lm = litMiddle0(sim, m[2]);
            int[] c = MoonFinder.corner(prev[0] + lm[0], prev[1] + lm[1], sideG, 4080, 3072);
            short[] raw = shot(sim, glare, N, sky, sideG, sideG, c[0], c[1], m[0], m[1], m[2], m[3], photons * longer, ePerDn / iso, hand.r, hot,
                    400);
            MoonStack.Frame f = new MoonStack.Frame(raw, sideG, sideG, c[0], c[1], (long) (hand.t * 1e9));
            f.upPa = sim.upPa - m[2];
            s.glow.add(f);
            // the capture result's own light: a hair off the asked
            s.gains.add(longer * iso * (1 + 0.002 * hand.r.nextGaussian()));
            s.glowWhere.add(new double[]{m[0] - c[0], m[1] - c[1], m[2]});
            prev = new double[]{m[0], m[1]};
        }
        return s;
    }

    static double[] litCache;
    static double litCacheRot = Double.NaN;
    static MoonSim litCacheSim;

    /** The lit part's middle, worked out once per Moon (the roll moves it too little to matter for a crop's place). */
    static double[] litMiddle0(MoonSim sim, double rot) {
        if (litCache == null || litCacheSim != sim) {
            litCache = litMiddle(sim, rot);
            litCacheSim = sim;
            litCacheRot = rot;
        }
        return litCache;
    }

    static float[] lum(float[] r, float[] g, float[] b) {
        float[] y = new float[r.length];
        for (int k = 0; k < y.length; k++) y[k] = 0.2126f * r[k] + 0.7152f * g[k] + 0.0722f * b[k];
        return y;
    }

    /** The truth on the result's grid, a colour channel at a time (the lens's blur, no shake). */
    static float[] truthIn(MoonSim sim, MoonStack.Result res, MoonshotTest.Burst b, int ch) {
        int W = res.w;
        MoonStack.Frame rf = b.frames.get(res.reference);
        double[] rw = b.where.get(res.reference);
        double S = 2;
        double psi = Math.toRadians(rf.upPa - res.upPa), c = Math.cos(psi), s = Math.sin(psi);
        double a = -rw[2] * Math.PI / 180, ca = Math.cos(a), sa = Math.sin(a);
        float[] out = new float[W * W];
        for (int j = 0; j < W; j++) {
            for (int i = 0; i < W; i++) {
                double ox = (i - res.cx) / S, oy = (j - res.cy) / S;
                double x = res.disk.cx + c * ox - s * oy, y = res.disk.cy + s * ox + c * oy;
                double dx = x - rw[0], dy = y - rw[1];
                double tx = dx * ca + dy * sa, ty = -dx * sa + dy * ca;
                out[j * W + i] = (float) sim.truthBox(ch, tx, ty, 1 / S);
            }
        }
        return out;
    }

    static float percentileIn(float[] Y, int W, double cx, double cy, double r, double q) {
        float[] v = new float[Y.length];
        int n = 0;
        for (int j = 0; j < W; j++) for (int i = 0; i < W; i++) if (Math.hypot(i - cx, j - cy) <= r) v[n++] = Y[j * W + i];
        float[] s = Arrays.copyOf(v, n);
        Arrays.sort(s);
        return s[Math.min(n - 1, (int) Math.round(q * (n - 1)))];
    }

    static double median(List<Double> v) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        Arrays.sort(a);
        return a.length == 0 ? Double.NaN : a[a.length / 2];
    }

    static double corr(List<double[]> pairs) {
        double sa = 0, sb = 0;
        for (double[] p : pairs) {
            sa += p[0];
            sb += p[1];
        }
        double ma = sa / pairs.size(), mb = sb / pairs.size(), sab = 0, saa = 0, sbb = 0;
        for (double[] p : pairs) {
            sab += (p[0] - ma) * (p[1] - mb);
            saa += (p[0] - ma) * (p[0] - ma);
            sbb += (p[1] - mb) * (p[1] - mb);
        }
        return sab / Math.sqrt(saa * sbb + 1e-300);
    }

    static void png(File f, int[] argb, int w, int h) throws Exception {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        im.setRGB(0, 0, w, h, argb, 0, w);
        f.getParentFile().mkdirs();
        ImageIO.write(im, "png", f);
    }

    /** A picture's grey values stretched from 0 to {@code top} (linear) with a square-root's lift. */
    static int[] stretch(float[] y, double lo, double top) {
        int[] out = new int[y.length];
        for (int k = 0; k < y.length; k++) {
            double v = Math.max(0, Math.min(1, (y[k] - lo) / (top - lo)));
            int g = (int) Math.round(255 * Math.sqrt(v));
            out[k] = 0xFF000000 | (g << 16) | (g << 8) | g;
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "/tmp/earthshine");
        long t0 = System.currentTimeMillis();
        SensorSpec spec = MoonshotTest.spec();
        // a waxing crescent in the evening twilight, low in the west
        long t = MoonshotTest.find(MoonshotTest.utc(2026, 12, 11, 0, 0), 0.12, 0.2, 12);
        MoonGeo.View v = MoonGeo.view(t, MoonshotTest.LAT, MoonshotTest.LON);
        double upPa = v.q + 25;
        // the Earth's glow: about a 3000th of the crescent's bright parts, bluer than sunlight
        MoonSim sim = new MoonSim(t, MoonshotTest.LAT, MoonshotTest.LON, MoonshotTest.SCALE, upPa, 6, null, 6e-5, new double[]{0.9, 1.0, 1.12});
        int N = (int) Math.ceil(3.2 * sim.rPx);
        float[][] glare = glare(sim, N);
        // the burst: the crescent's bright parts at 62% of the white level
        double top = 0;
        for (int j = -(int) sim.rPx; j <= sim.rPx; j++)
            for (int i = -(int) sim.rPx; i <= sim.rPx; i++) top = Math.max(top, sim.light(1, i, j, 0, 0, 0, 0));
        double photons = 0.62 * FULL / (top * sim.camRgb[1]);
        double sky = 1.5e-5;
        Set<Integer> hot = new HashSet<Integer>();
        Random hr = new Random(77);
        while (hot.size() < 400) hot.add((1530 + hr.nextInt(300) - 150) * 4080 + 2040 + hr.nextInt(300) - 150);
        System.out.println(String.format(Locale.US, "EarthshineTest: a %.0f%% lit crescent, %.0f px across; the glow %.2g of the full Moon; %.1f s to draw",
                v.illum * 100, 2 * sim.rPx, sim.earthshine, (System.currentTimeMillis() - t0) / 1000.0));
        Shot s = shoot(sim, glare, N, sky, 60, 120, photons, LONGER, ISO, 7, 1.4, 31, hot);
        MoonStack.Options o = new MoonStack.Options();
        o.expectedRadius = sim.rPx;
        o.brightLimbPa = v.chi;
        o.outUpPa = v.q;
        MoonStack.Result res = MoonStack.stack(s.main.frames, spec, o, Progress.NONE);
        check("the burst stacked", res != null && res.r != null, res != null ? res.log : "null");
        if (res == null || res.r == null) {
            System.out.println("FAILED " + failed);
            System.exit(1);
        }
        double[] rwT = s.main.where.get(res.reference);
        System.out.println(String.format(Locale.US, "  the stack's centre %.2f,%.2f against the truth's %.2f,%.2f; its radius %.2f against %.2f", res.disk.cx,
                res.disk.cy, rwT[0], rwT[1], res.disk.r, sim.rPx));
        long tm = System.currentTimeMillis();
        Earthshine.Result es = Earthshine.merge(s.glow, s.gains, LONGER * ISO, res, spec, Progress.NONE);
        System.out.println("  " + es.log);
        System.out.println(String.format(Locale.US, "  (merged in %.2f s)", (System.currentTimeMillis() - tm) / 1000.0));
        check("the glow merged", es.ok, String.format(Locale.US, "level %.3g, %.1f times its noise", es.level, es.snr));
        check("all but a few of the glow frames merged", es.used >= 110, es.used + " of " + es.total);
        // the turn between the burst's reference and the glow's: the roll between them
        int gref = Math.max(0, es.reference);
        double rollTruth = s.main.where.get(res.reference)[2] - s.glowWhere.get(gref)[2];
        check("the glow's turn against the stack found", Math.abs(es.turn - rollTruth) < 0.35,
                String.format(Locale.US, "%.2f deg against the truth's %.2f (the tilt said %.2f)", es.turn, rollTruth, es.turnTilt));
        // the Moon's centre in the glow's average: where it was in the reference glow frame
        double[] gw = s.glowWhere.get(gref);
        check("the Moon's centre found by its dark edge", Math.hypot(es.cx - gw[0], es.cy - gw[1]) < 0.4,
                String.format(Locale.US, "%.2f,%.2f against %.2f,%.2f; radius %.2f against %.2f", es.cx, es.cy, gw[0], gw[1], es.radius, sim.rPx));

        if (es.r == null) {
            System.out.println("EarthshineTest: FAILED " + failed + " (no merged picture)");
            System.exit(1);
        }
        // the truth in the result's geometry, in the stack's units
        int W = res.w;
        float[][] tr = {truthIn(sim, res, s.main, 0), truthIn(sim, res, s.main, 1), truthIn(sim, res, s.main, 2)};
        float[] tY = lum(tr[0], tr[1], tr[2]);
        float tTop = percentileIn(tY, W, res.cx, res.cy, res.radius, 0.995);
        float[] mY = lum(res.r, res.g, res.b), eY = lum(es.r, es.g, es.b);
        double esTruth = sim.earthshine / tTop;
        // the night side: no sunlight (the truth no brighter than the glow at its brightest), off the edge
        List<double[]> night = new ArrayList<double[]>(), term = new ArrayList<double[]>();
        List<Double> ratio = new ArrayList<Double>(), nightT = new ArrayList<Double>(), nightE = new ArrayList<Double>();
        float[] eYb = MoonshotTest.blur(eY, W, 3), tYb = MoonshotTest.blur(tY, W, 3);
        double maxDiffSun = 0;
        int sunPx = 0;
        double[] sumE = new double[2], sumT = new double[2], sunRB = new double[2];
        int nb = 0;
        for (int j = 0; j < W; j++) {
            for (int i = 0; i < W; i++) {
                int k = j * W + i;
                double d = Math.hypot(i - res.cx, j - res.cy) / res.radius;
                if (d < 0.85 && tY[k] < 2.2 * sim.earthshine && tYb[k] < 1.6 * sim.earthshine) {
                    night.add(new double[]{eYb[k], tYb[k]});
                    nightT.add((double) tY[k] / tTop);
                    nightE.add((double) eY[k]);
                    sumE[0] += es.r[k];
                    sumE[1] += es.b[k];
                    sumT[0] += tr[0][k];
                    sumT[1] += tr[2][k];
                    nb++;
                }
                if (d < 0.9 && tY[k] / tTop > 0.004 && tY[k] / tTop < 0.05) term.add(new double[]{eY[k], tY[k] / tTop});
                if (mY[k] > 0.08) {
                    maxDiffSun = Math.max(maxDiffSun, Math.abs(eY[k] - mY[k]));
                    sunPx++;
                    if (mY[k] > 0.3) {
                        sunRB[0] += res.r[k];
                        sunRB[1] += res.b[k];
                    }
                }
            }
        }
        double lvlT = median(nightT), lvlE = median(nightE);
        check("the night side's glow as bright as the truth's", Math.abs(lvlE / lvlT - 1) < 0.15,
                String.format(Locale.US, "%.3g against %.3g (1 in %.0f of the bright parts; the level said %.3g)", lvlE, lvlT, 1 / lvlT, es.level));
        double cn = corr(night);
        check("its seas and highlands where the truth's are", cn > 0.8, String.format(Locale.US, "correlation %.3f over %d px", cn, night.size()));
        double bE = sumE[1] / sumE[0], bT = sumT[1] / sumT[0], bS = sunRB[1] / sunRB[0];
        check("its colour the Earth's, bluer than the crescent's", Math.abs(bE / bT - 1) < 0.08 && bE > bS * 1.1,
                String.format(Locale.US, "blue/red %.3f against the truth's %.3f (the sunlit stack's %.3f)", bE, bT, bS));
        // the terminator: where the Sun is just up, the stack's dim light and the glow's meet
        List<Double> tr2 = new ArrayList<Double>();
        for (double[] p : term) tr2.add(p[0] / p[1]);
        double tm2 = median(tr2);
        check("no seam where the Sun's light ends", Math.abs(tm2 - 1) < 0.2, String.format(Locale.US, "the merged over the truth %.3f there (%d px)",
                tm2, term.size()));
        check("the sunlit crescent untouched", maxDiffSun < 1e-6, String.format(Locale.US, "at most %.2g off over %d px", maxDiffSun, sunPx));
        // the sky round the Moon: black, the glare and the twilight gone
        List<Double> skyV = new ArrayList<Double>();
        for (int j = 0; j < W; j++) {
            for (int i = 0; i < W; i++) {
                double d = Math.hypot(i - res.cx, j - res.cy) / res.radius;
                if (d > 1.04 && d < 1.11) skyV.add((double) eYb[j * W + i]);
            }
        }
        double skyMed = median(skyV);
        check("the sky black around the Moon", Math.abs(skyMed) < 0.08 * lvlT, String.format(Locale.US, "%.2g (the glow %.3g)", skyMed, lvlT));
        // nearest the crescent, where the glare was worst
        List<Double> near = new ArrayList<Double>();
        for (int j = 0; j < W; j++) {
            for (int i = 0; i < W; i++) {
                int k = j * W + i;
                double d = Math.hypot(i - res.cx, j - res.cy) / res.radius;
                if (d > 0.9 || tY[k] > 2.2 * sim.earthshine) continue;
                // within 8 px (the result's 16) of sunlight
                boolean close = false;
                for (int dj = -16; dj <= 16 && !close; dj += 4)
                    for (int di = -16; di <= 16 && !close; di += 4) {
                        int ii = i + di, jj = j + dj;
                        if (ii >= 0 && jj >= 0 && ii < W && jj < W && tY[jj * W + ii] > 0.02 * tTop) close = true;
                    }
                if (close) near.add((double) eYb[k] / Math.max(1e-12, tYb[k] / tTop));
            }
        }
        double nm = median(near);
        check("the glare next to the crescent taken off", Math.abs(nm - 1) < 0.3, String.format(Locale.US, "the merged over the truth %.3f there (%d px)",
                nm, near.size()));
        // hot pixels: nothing sharp left on the night side
        float[] eYs = MoonshotTest.blur(eY, W, 2);
        double spike = 0;
        for (int j = 0; j < W; j++) {
            for (int i = 0; i < W; i++) {
                int k = j * W + i;
                double d = Math.hypot(i - res.cx, j - res.cy) / res.radius;
                if (d < 0.85 && tY[k] < 2.2 * sim.earthshine && tYb[k] < 1.6 * sim.earthshine) spike = Math.max(spike, eY[k] - eYs[k]);
            }
        }
        check("no hot pixels on the night side", spike < 0.6 * lvlT, String.format(Locale.US, "the sharpest bump %.2g (the glow %.3g)", spike, lvlT));
        // the finished photo's tones: the night side a soft grey, the sky black, the crescent as it was
        MoonLook.Picture pic = MoonLook.enlarge(es.r, es.g, es.b, es.w, es.h, res.cx, res.cy, res.radius, res.upPa, es.level, es.black);
        MoonLook.Picture plain = MoonLook.enlarge(res.r, res.g, res.b, res.w, res.h, res.cx, res.cy, res.radius, res.upPa);
        float[] tN = new float[tY.length];
        for (int k = 0; k < tY.length; k++) tN[k] = tY[k] / tTop;
        MoonLook.Picture truthPic = MoonLook.enlarge(tN, tN, tN, W, W, res.cx, res.cy, res.radius, res.upPa, esTruth, 0);
        List<Double> nightG = new ArrayList<Double>(), skyG = new ArrayList<Double>();
        for (int j = 0; j < pic.h; j += 2) {
            for (int i = 0; i < pic.w; i += 2) {
                double d = Math.hypot(i - pic.cx, j - pic.cy) / pic.r;
                int a = pic.argb[j * pic.w + i], g = (a >> 8) & 255;
                int ta = truthPic.argb[j * pic.w + i], tg = (ta >> 8) & 255;
                if (d < 0.8 && tg < 110 && tg > 20) nightG.add((double) g);
                if (d > 1.06 && d < 1.3) skyG.add((double) g);
            }
        }
        double ng = median(nightG), sg = median(skyG);
        double[] skyA = new double[skyG.size()];
        for (int q = 0; q < skyA.length; q++) skyA[q] = skyG.get(q);
        Arrays.sort(skyA);
        double sg99 = skyA[(int) (0.99 * (skyA.length - 1))];
        check("the night side a soft grey in the photo", ng > 45 && ng < 110, String.format(Locale.US, "%.0f of 255", ng));
        check("the sky black in the photo, specks and all", sg < 6 && sg99 < 30, String.format(Locale.US, "%.0f of 255 (its brightest 1%%: %.0f)", sg,
                sg99));
        png(new File(out, "earthshine_plain.png"), plain.argb, plain.w, plain.h);
        png(new File(out, "earthshine_merged.png"), pic.argb, pic.w, pic.h);
        png(new File(out, "earthshine_truth.png"), truthPic.argb, truthPic.w, truthPic.h);
        png(new File(out, "earthshine_mean.png"), stretch(es.mean, 0, 6 * lvlT), es.meanW, es.meanH);

        // nothing to merge: too few frames; no crescent burnt (the glow frames as dim as the burst's); a glow too faint
        Earthshine.Result few = Earthshine.merge(s.glow.subList(0, 5), s.gains.subList(0, 5), LONGER * ISO, res, spec, Progress.NONE);
        check("five frames: nothing merged", !few.ok, few.log);
        Shot dim = shoot(sim, glare, N, sky, 30, 30, photons, 1, 1, 0, 0, 32, null);
        MoonStack.Result dres = MoonStack.stack(dim.main.frames, spec, o, Progress.NONE);
        Earthshine.Result none = Earthshine.merge(dim.glow, dim.gains, 1, dres, spec, Progress.NONE);
        check("frames no brighter than the burst's: nothing merged", !none.ok, none.log);
        MoonSim dark = new MoonSim(t, MoonshotTest.LAT, MoonshotTest.LON, MoonshotTest.SCALE, upPa, 4, null, 0, null);
        Shot black = shoot(dark, glare(dark, N), N, sky, 40, 60, photons, LONGER, ISO, 3, 0.5, 33, null);
        MoonStack.Result bres = MoonStack.stack(black.main.frames, spec, o, Progress.NONE);
        Earthshine.Result nog = Earthshine.merge(black.glow, black.gains, LONGER * ISO, bres, spec, Progress.NONE);
        check("no glow on the night side: nothing merged", !nog.ok, nog.log);

        System.out.println(String.format(Locale.US, "  (%.1f s)", (System.currentTimeMillis() - t0) / 1000.0));
        // the gate reads the last line: "ALL PASS" like every other test
        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        if (failed > 0) System.exit(1);
    }
}
