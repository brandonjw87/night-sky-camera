package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SkyBackground;
import com.brandon.nightsky.processing.SkySolver;

import java.io.File;
import java.util.Locale;
import java.util.Random;

/**
 * The sky's glow taken out with the Milky Way kept (2.4, {@link SkyBackground}): stacks drawn with a known sky (the
 * Milky Way where the plate solution says it is, a town's light dome that isn't a quadratic, stars, noise), measured
 * with the 2.3 background (no solution) and the 2.4 one.
 * <ul>
 * <li>A Milky Way across a light dome: 2.4 keeps far more of the band's light, and the sky either side of it is flatter.
 * <li>No Milky Way and a plain quadratic glow (the galactic pole): 2.4 does no harm.
 * <li>A picture full of Milky Way (the galactic centre): part of the sky stays in the fit, and the background is sane.
 * <li>Without a solution the analysis is 2.3's, to the bit.
 * </ul>
 * Optional argument: a folder for before/after pictures of the first night.
 */
public final class BackgroundTest {
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        File out = args.length > 0 ? new File(args[0]) : null;
        if (out != null) out.mkdirs();
        milkyWayOverhead(out);
        steepDome();
        domeUnderBand();
        galacticPole();
        galacticCentre();
        noSolution();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    static final int W = 2040, H = 1536;
    static final double F = 1437.5;

    /** A drawn stack: the picture, the Milky Way's and the stars' light on their own (green), the sky glow's green. */
    static final class Scene {
        RgbImage img;
        float[] mw, stars, glow, galaxy;
        SkySolver.Solution sol;
        SensorSpec spec = new SensorSpec();
    }

    /**
     * The camera pointed at galactic (l, b), turned {@code roll} degrees; mwLevel: the band's light where it's middling
     * bright, as a share of the glow's green; dome: the light dome's height over the glow at the bottom edge.
     */
    static Scene scene(double l, double b, double roll, double mwLevel, double dome, double slope, long seed) {
        return scene(l, b, roll, mwLevel, dome, 0.7, slope, seed);
    }

    /** domeX: where along the bottom edge the dome stands (0: left, 1: right). */
    /** Where the next scene has a galaxy (fractions of the width and height) and how bright (its middle over the sky's level), or null. */
    static double[] galaxyAt;
    /** The next scenes' noise per pixel (ADU, the sky at 150). */
    static double noise = 1.5;

    static Scene scene(double l, double b, double roll, double mwLevel, double dome, double domeX, double slope, long seed) {
        Scene s = new Scene();
        s.sol = pointing(l, b, roll);
        int n = W * H;
        float[] r = new float[n], g = new float[n], bl = new float[n];
        s.mw = new float[n];
        s.stars = new float[n];
        s.glow = new float[n];
        s.galaxy = new float[n];
        double sky = 150;
        // the Milky Way on a grid every 4 px (its light is smooth at that scale)
        int st = 4, gw = W / st + 2, gh = H / st + 2;
        float[] mg = new float[gw * gh];
        double[] dir = new double[3], q = new double[3];
        for (int j = 0; j < gh; j++) {
            for (int i = 0; i < gw; i++) {
                dir = s.sol.direction(i * st, j * st);
                rot(SkySim.J2000_TO_GAL, dir, q);
                double m = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2]);
                mg[j * gw + i] = (float) SkySim.milkyWayAt(q[0] / m, q[1] / m, q[2] / m);
            }
        }
        Random rnd = new Random(seed);
        for (int y = 0; y < H; y++) {
            double fy = y / (double) H;
            for (int x = 0; x < W; x++) {
                double fx = x / (double) W;
                // a gentle slope plus a town's dome rising steeply towards the bottom edge, off to the right
                // a gentle slope plus a town's dome rising steeply towards the bottom edge (dome > 0), or (dome < 0) a
                // town's glow towards the right edge as the zenith sees it, steeper towards the town
                double glow = dome >= 0
                        ? sky * (1 + slope * (fx - 0.5) * 2 + dome * Math.exp(-(1 - fy) / 0.12) * Math.exp(-sq((fx - domeX) / 0.3)))
                        : sky * (1 + slope * (fy - 0.5) * 2 - dome * Math.exp((fx - 1) / 0.3) * (1 + 0.3 * Math.exp(-sq((fy - domeX) / 0.3))));
                double u = x / (double) st, v = y / (double) st;
                int i0 = (int) u, j0 = (int) v;
                double tu = u - i0, tv = v - j0;
                int k = j0 * gw + i0;
                double mw = mg[k] + tu * (mg[k + 1] - mg[k]) + tv * (mg[k + gw] - mg[k] + tu * (mg[k + gw + 1] - mg[k + gw] - mg[k + 1] + mg[k]));
                mw *= mwLevel * sky;
                int p = y * W + x;
                s.mw[p] = (float) mw;
                s.glow[p] = (float) glow;
                r[p] = (float) (glow * 1.12 + mw * 1.06);
                g[p] = (float) (glow + mw);
                bl[p] = (float) (glow * 0.78 + mw * 0.86);
            }
        }
        // a big galaxy off the band (Andromeda's size: 3 by 1 degrees, its middle a quarter over the sky's level)
        if (galaxyAt != null) {
            double gxc = galaxyAt[0] * W, gyc = galaxyAt[1] * H, deg = F * Math.PI / 180;
            double ca = Math.cos(0.6), sa = Math.sin(0.6), sl = 0.75 * deg, sw = 0.22 * deg;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    double dx = x + 0.5 - gxc, dy = y + 0.5 - gyc;
                    double a1 = ca * dx + sa * dy, a2 = -sa * dx + ca * dy;
                    double e = 0.5 * (sq(a1 / sl) + sq(a2 / sw));
                    if (e > 12) continue;
                    double v = galaxyAt[2] * sky * (Math.exp(-e) + 0.6 * Math.exp(-e * 6));
                    int p = y * W + x;
                    s.galaxy[p] = (float) v;
                    r[p] += (float) (v * 1.1);
                    g[p] += (float) v;
                    bl[p] += (float) (v * 0.8);
                }
            }
        }
        // stars: many faint, a few bright (clipped)
        double sig = 1.3;
        for (int k = 0; k < 5000; k++) {
            double sx = rnd.nextDouble() * W, sy = rnd.nextDouble() * H;
            double mag = 10 + 2.2 * Math.log10(Math.max(1e-4, rnd.nextDouble()));
            double flux = 20000 * Math.pow(10, -0.4 * (mag - 2));
            double cr = 0.8 + 0.5 * rnd.nextDouble(), cb = 1.8 - cr;
            int R = (int) Math.ceil(4 * sig);
            for (int yy = Math.max(0, (int) sy - R); yy <= Math.min(H - 1, (int) sy + R); yy++) {
                for (int xx = Math.max(0, (int) sx - R); xx <= Math.min(W - 1, (int) sx + R); xx++) {
                    double d2 = sq(xx + 0.5 - sx) + sq(yy + 0.5 - sy);
                    double v = flux / (2 * Math.PI * sig * sig) * Math.exp(-d2 / (2 * sig * sig));
                    int p = yy * W + xx;
                    s.stars[p] += (float) v;
                    r[p] += (float) (v * cr);
                    g[p] += (float) v;
                    bl[p] += (float) (v * cb);
                }
            }
        }
        float clip = s.spec.range();
        for (int p = 0; p < n; p++) {
            r[p] = Math.min(clip, (float) (r[p] + noise * rnd.nextGaussian()));
            g[p] = Math.min(clip, (float) (g[p] + noise * rnd.nextGaussian()));
            bl[p] = Math.min(clip, (float) (bl[p] + noise * rnd.nextGaussian()));
        }
        s.img = new RgbImage(W, H, r, g, bl);
        s.img.x0 = 4;
        s.img.y0 = 4;
        s.img.x1 = W - 4;
        s.img.y1 = H - 4;
        return s;
    }

    /** A solution looking at galactic (l, b) with the band's north turned roll degrees from the picture's up. */
    static SkySolver.Solution pointing(double l, double b, double roll) {
        double lr = Math.toRadians(l), br = Math.toRadians(b);
        double[] z = {Math.cos(br) * Math.cos(lr), Math.cos(br) * Math.sin(lr), Math.sin(br)};
        double[] pole = {0, 0, 1};
        if (Math.abs(z[2]) > 0.999) pole = new double[]{1, 0, 0};
        double pz = pole[0] * z[0] + pole[1] * z[1] + pole[2] * z[2];
        double[] nn = norm(new double[]{pole[0] - pz * z[0], pole[1] - pz * z[1], pole[2] - pz * z[2]});
        double[] e = cross(nn, z);
        double t = Math.toRadians(roll);
        double[] y = new double[3];
        for (int i = 0; i < 3; i++) y[i] = -(Math.cos(t) * nn[i] + Math.sin(t) * e[i]);
        double[] x = cross(y, z);
        double[] X = toJ2000(x), Y = toJ2000(y), Z = toJ2000(z);
        SkySolver.Solution s = new SkySolver.Solution();
        s.R = new double[]{X[0], Y[0], Z[0], X[1], Y[1], Z[1], X[2], Y[2], Z[2]};
        s.model = CameraModel.nominal(W, H, F);
        s.matched = 300;
        return s;
    }

    static double[] toJ2000(double[] gal) {
        double[] G = SkySim.J2000_TO_GAL, o = new double[3];
        for (int i = 0; i < 3; i++) o[i] = G[i] * gal[0] + G[3 + i] * gal[1] + G[6 + i] * gal[2];
        return o;
    }

    static void rot(double[] M, double[] v, double[] o) {
        for (int i = 0; i < 3; i++) o[i] = M[3 * i] * v[0] + M[3 * i + 1] * v[1] + M[3 * i + 2] * v[2];
    }

    /** What a background leaves: the band's light kept, the sky's unevenness off it, the fitted glow's level. */
    static final class Measure {
        double kept, flat, level, bandShare, galaxy = Double.NaN;
    }

    /**
     * kept: the band's light left after the background (where the band is a fifth of its brightest or more) over its
     * true light; flat: the rms of what's left of the glow, off the band, over 32 x 32 px blocks (the noise averaged
     * out), as a share of the glow; level: the fitted glow's mean over the true glow's.
     */
    static Measure measure(Scene s, Renderer.Analysis a) {
        Measure m = new Measure();
        float mwMax = 0;
        for (float v : s.mw) mwMax = Math.max(mwMax, v);
        double sumKept = 0, sumTrue = 0, sumFlat = 0, sumGlow = 0, sumFit = 0;
        int nFlat = 0, nAll = 0, nBand = 0;
        float[] bg = new float[3];
        RgbImage img = s.img;
        int B = 32;
        for (int by = img.y0; by + B <= img.y1; by += B) {
            for (int bx = img.x0; bx + B <= img.x1; bx += B) {
                double blockLeft = 0;
                int nb = 0;
                boolean off = true;
                for (int y = by; y < by + B; y += 2) {
                    for (int x = bx; x < bx + B; x += 2) {
                        int p = y * W + x;
                        if (mwMax > 0 && s.mw[p] >= 0.02f * mwMax) off = false;
                        if (s.stars[p] > 0.3f) continue;
                        Renderer.backgroundAt(a, x - img.x0 + 0.5, y - img.y0 + 0.5, bg);
                        double left = img.g[p] - bg[1];
                        sumGlow += s.glow[p];
                        sumFit += bg[1];
                        nAll++;
                        if (mwMax > 0 && s.mw[p] > 0.2f * mwMax) {
                            sumKept += left;
                            sumTrue += s.mw[p];
                            nBand++;
                        }
                        blockLeft += left - s.mw[p];
                        nb++;
                    }
                }
                if (off && nb > 16) {
                    sumFlat += sq(blockLeft / nb);
                    nFlat++;
                }
            }
        }
        m.kept = nBand > 0 ? sumKept / sumTrue : Double.NaN;
        m.flat = nFlat > 0 ? Math.sqrt(sumFlat / nFlat) / (sumGlow / nAll) : Double.NaN;
        m.level = sumFit / sumGlow;
        return m;
    }

    /**
     * The app's own night (the phone on its back, the camera at the zenith): the Milky Way overhead, Aquila to Cygnus
     * corner to corner, a town's glow brightening towards one edge (half the sky's level again at the edge).
     */
    /** The share of a galaxy's light the background leaves it: one minus what the fit rose by over it (a: with, b: without it). */
    static double galaxyKept(Scene s, Renderer.Analysis a, Renderer.Analysis b) {
        float[] ba = new float[3], bb = new float[3];
        double rise = 0, light = 0;
        RgbImage img = s.img;
        for (int y = img.y0; y < img.y1; y += 2) {
            for (int x = img.x0; x < img.x1; x += 2) {
                int p = y * W + x;
                if (s.galaxy[p] < 0.005f * 150) continue;
                Renderer.backgroundAt(a, x - img.x0 + 0.5, y - img.y0 + 0.5, ba);
                Renderer.backgroundAt(b, x - img.x0 + 0.5, y - img.y0 + 0.5, bb);
                rise += ba[1] - bb[1];
                light += s.galaxy[p];
            }
        }
        return 1 - rise / light;
    }

    static void milkyWayOverhead(File out) throws Exception {
        Scene s = scene(45, -2, 35, 0.35, -0.5, 0.4, 0.1, 7);
        long t0 = System.currentTimeMillis();
        Renderer.Analysis old = Renderer.analyze(s.img, s.spec);
        long t1 = System.currentTimeMillis();
        Renderer.Analysis now = Renderer.analyze(s.img, s.spec, s.sol);
        long t2 = System.currentTimeMillis();
        Measure mo = measure(s, old), mn = measure(s, now);
        System.out.println(String.format(Locale.US, "Milky Way overhead, a town to one side: 2.3 kept %.0f%% of the band, sky %.2f%% uneven; "
                        + "2.4 kept %.0f%%, sky %.2f%% uneven (%d / %d ms)%s", 100 * mo.kept, 100 * mo.flat,
                100 * mn.kept, 100 * mn.flat, t1 - t0, t2 - t1, now.bgNote));
        // the same night with a big galaxy off the band (Andromeda's size): how much of it the background takes
        // (and on a noisier night, where a faint one doesn't stand out of the noise enough to be masked)
        for (double[] gn : new double[][]{{0.25, 1.5}, {0.05, 1.5}, {0.02, 1.5}, {0.05, 8}, {0.02, 8}}) {
            double gb = gn[0];
            noise = gn[1];
            Renderer.Analysis now0 = now, old0 = old;
            if (noise != 1.5) {
                Scene s0 = scene(45, -2, 35, 0.35, -0.5, 0.4, 0.1, 7);
                now0 = Renderer.analyze(s0.img, s0.spec, s0.sol);
                old0 = Renderer.analyze(s0.img, s0.spec);
            }
            galaxyAt = new double[]{0.62, 0.22, gb};
            Scene sg = scene(45, -2, 35, 0.35, -0.5, 0.4, 0.1, 7);
            galaxyAt = null;
            noise = 1.5;
            double kg = galaxyKept(sg, Renderer.analyze(sg.img, sg.spec, sg.sol), now0);
            double ko = galaxyKept(sg, Renderer.analyze(sg.img, sg.spec), old0);
            System.out.println(String.format(Locale.US, "  a galaxy off the band, its middle %.0f%% over the sky, noise %.1f: 2.3 kept %.0f%% of its "
                            + "light, 2.4 %.0f%%", 100 * gb, gn[1], 100 * ko, 100 * kg));
            check(kg > 0.9 || kg > ko - 0.03, "the galaxy keeps 90% of its light or more (or as much as with 2.3)");
        }
        check(now.bgRes != null, "a residual surface was fitted");
        check(mn.kept > 0.85 && mn.kept < 1.15, "2.4 keeps the band's light (85 to 115% of it)");
        check(mn.kept > mo.kept + 0.15, "2.4 keeps clearly more of the band than 2.3");
        check(mn.flat < mo.flat, "the sky off the band is flatter with 2.4");
        check(mn.flat < 0.01, "the sky off the band is even to 1% with 2.4");
        check(Math.abs(mn.level - 1) < 0.04, "the fitted glow is the glow's level");
        if (out != null) {
            renderPair(s, old, now, new File(out, "background-2.3.png"), new File(out, "background-2.4.png"));
        }
    }

    /** A steep dome low beside the band (the camera tipped towards a town): 2.4 follows it at least as well as 2.3. */
    static void steepDome() {
        Scene s = scene(45, -2, 35, 0.35, 0.6, 0.12, 0.15, 7);
        Renderer.Analysis old = Renderer.analyze(s.img, s.spec), now = Renderer.analyze(s.img, s.spec, s.sol);
        Measure mo = measure(s, old), mn = measure(s, now);
        System.out.println(String.format(Locale.US, "A steep dome beside the band: 2.3 kept %.0f%%, sky %.2f%% uneven; 2.4 kept %.0f%%, sky %.2f%% uneven%s",
                100 * mo.kept, 100 * mo.flat, 100 * mn.kept, 100 * mn.flat, now.bgNote));
        check(mn.flat <= mo.flat, "a steep dome: the sky off the band at least as flat as 2.3's");
        check(mn.kept > mo.kept + 0.15 && mn.kept < 1.2, "a steep dome: the band kept, clearly more than 2.3");
    }

    /** A dome under the band (its light can't be told from the band's): 2.4 is no worse than 2.3 off the band. */
    static void domeUnderBand() {
        Scene s = scene(45, -2, 35, 0.35, 0.6, 0.75, 0.12, 9);
        Renderer.Analysis old = Renderer.analyze(s.img, s.spec), now = Renderer.analyze(s.img, s.spec, s.sol);
        Measure mo = measure(s, old), mn = measure(s, now);
        System.out.println(String.format(Locale.US, "Dome under the band: 2.3 kept %.0f%%, sky %.2f%% uneven; 2.4 kept %.0f%%, sky %.2f%% uneven%s",
                100 * mo.kept, 100 * mo.flat, 100 * mn.kept, 100 * mn.flat, now.bgNote));
        check(mn.flat <= mo.flat * 1.05 + 0.001, "a dome under the band: the sky off it is no worse than 2.3's");
        check(mn.kept > mo.kept, "a dome under the band: more of the band kept than 2.3");
    }

    static void galacticPole() {
        Scene s = scene(120, 88, 0, 0.35, 0, 0.3, 11);
        Renderer.Analysis old = Renderer.analyze(s.img, s.spec), now = Renderer.analyze(s.img, s.spec, s.sol);
        Measure mo = measure(s, old), mn = measure(s, now);
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        if (now.bgRes != null) for (float v : now.bgRes) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        System.out.println(String.format(Locale.US, "Galactic pole: 2.3 sky %.2f%% uneven, 2.4 %.2f%%; surface %.2f..%.2f ADU%s", 100 * mo.flat,
                100 * mn.flat, lo, hi, now.bgNote));
        check(mn.flat <= mo.flat * 1.1 + 0.0005, "no Milky Way: 2.4 is as flat as 2.3");
        check(now.bgRes == null || Math.max(-lo, hi) < 0.01 * 150, "no Milky Way, a quadratic glow: the surface is next to nothing");
    }

    static void galacticCentre() {
        Scene s = scene(5, 0, 10, 0.35, 0.4, 0.15, 13);
        Renderer.Analysis old = Renderer.analyze(s.img, s.spec), now = Renderer.analyze(s.img, s.spec, s.sol);
        Measure mo = measure(s, old), mn = measure(s, now);
        System.out.println(String.format(Locale.US, "Galactic centre: 2.3 kept %.0f%%, 2.4 kept %.0f%%, sky %.2f%% uneven, fitted level %.3f of the glow%s",
                100 * mo.kept, 100 * mn.kept, 100 * mn.flat, mn.level, now.bgNote));
        check(mn.kept > mo.kept, "full of Milky Way: more of it kept than 2.3");
        check(!Double.isNaN(mn.kept) && !Double.isNaN(mn.level), "full of Milky Way: the fit is a number");
        check(mn.level > 0.85 && mn.level < 1.2, "full of Milky Way: the fitted glow is near the glow's level");
        check(mn.kept > 0.6, "full of Milky Way: most of the band is kept");
    }

    static void noSolution() {
        Scene s = scene(45, -2, 35, 0.35, 0.8, 0.15, 7);
        Renderer.Analysis a = Renderer.analyze(s.img, s.spec), b = Renderer.analyze(s.img, s.spec, null);
        boolean same = b.bgRes == null && "".equals(b.bgNote);
        for (int c = 0; c < 3; c++) for (int k = 0; k < 6; k++) same &= a.poly[c][k] == b.poly[c][k];
        check(same, "without a solution the background is 2.3's");
    }

    /** The scene rendered with each background, the same look, for the release notes. */
    static void renderPair(Scene s, Renderer.Analysis old, Renderer.Analysis now, File fo, File fn) throws Exception {
        Renderer.Params p = new Renderer.Params();
        int[] a = com.brandon.nightsky.processing.Master.render(s.img, old, p);
        int[] b = com.brandon.nightsky.processing.Master.render(s.img, now, p);
        SimRunner.writePng(a, old.cw, old.ch, fo);
        SimRunner.writePng(b, now.cw, now.ch, fn);
        System.out.println("  pictures: " + fo + ", " + fn);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] norm(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / n, v[1] / n, v[2] / n};
    }

    static double sq(double v) {
        return v * v;
    }
}
