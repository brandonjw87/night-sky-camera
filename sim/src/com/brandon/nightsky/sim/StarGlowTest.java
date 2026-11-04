package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.Recipe;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SkySolver;
import com.brandon.nightsky.processing.StarGlow;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Star glow (1.10) on a made-up sky whose stars are known: two dozen bright stars of known magnitude and colour among
 * thousands of faint ones, the brightest of them clipped, a planet, a satellite's streak, the Moon, and a catalogue star
 * that a cloud hides.
 * <ul>
 * <li>the lights are found and measured: an unclipped star's light and colour as they are; the streak and the Moon
 * don't glow;</li>
 * <li>with the catalogue's stars placed in the picture, a clipped star takes the light of its magnitude (by the zero
 * point of the unclipped ones) and the colour of its colour index, the planet too; the hidden star gets no glow; a
 * catalogue star wrongly placed on a fainter light can't make it more than {@code MOST_GAIN} times brighter;</li>
 * <li>renders: 0% is the photo as it was, bit for bit, also after glowing renders; more glow at 200% than at 100%, in the
 * stars' colours;</li>
 * <li>the preview (a binned copy) glows with the lights the full-size photo has, found once; found in the copy itself
 * they're much the same; the glow scaled down holds the same light;</li>
 * <li>the edit's setting kept in its recipe (none for star trails), the solution's own lens model kept with the master,
 * and the catalogue placed by a plate solution where it is.</li>
 * </ul>
 * Usage: StarGlowTest [out dir for pictures]
 */
public final class StarGlowTest {
    private static int failures;

    static final int W = 2000, H = 1500;
    static final float BG = 40f, NOISE = 2f, PSF = 1.3f;
    /** The light of a magnitude-0 star: a third-magnitude one peaks at about half the clip level. */
    static final double ZP = 76000;
    /** The bright stars: x, y, V, B-V. */
    static final double[][] STARS = {
            {420.5, 310.2, 0.05, 0.00}, {1530.3, 1105.7, 0.45, 1.20}, {760.2, 980.4, 0.90, 1.55}, {1260.8, 420.6, 1.30, -0.20},
            {260.4, 760.9, 1.70, 0.60}, {1720.6, 520.3, 2.05, 0.95}, {980.7, 1300.2, 2.30, 0.10}, {600.3, 160.8, 2.55, 1.10},
            {1420.2, 760.5, 2.70, -0.10}, {180.6, 1260.4, 2.85, 0.40}, {1840.3, 1360.6, 3.00, 1.40}, {880.5, 560.3, 3.10, 0.75},
            {1120.4, 860.7, 3.25, 0.25}, {340.7, 520.2, 3.40, 1.00}, {1600.8, 220.4, 3.50, 0.05}, {700.3, 1380.6, 3.60, 0.50},
            {1180.6, 160.3, 3.70, 1.25}, {1880.2, 860.8, 3.80, -0.05}, {520.6, 720.4, 3.90, 0.85}, {1340.4, 1240.6, 4.00, 0.30},
            {140.3, 380.7, 4.10, 1.10}, {1000.2, 700.8, 4.20, 0.65}, {1760.7, 1040.3, 4.30, 0.15}, {460.8, 1100.5, 4.40, 1.30},
    };
    /** The planet (Mars' colour index), the hidden star, a fainter star the catalogue wrongly puts a bright one on. */
    static final double[] PLANET = {1060.4, 1050.6, -1.0, 1.36}, HIDDEN = {1700.5, 1250.5, 1.5, 0.3}, WRONG = {1380.5, 600.5, 1.9, 0.5};
    /** The streak's ends, and the Moon's centre and radius. */
    static final float SX0 = 300, SY0 = 1200, SX1 = 380, SY1 = 1240, MX = 1000, MY = 200, MR = 14;

    public static void main(String[] args) throws Exception {
        File out = args.length > 0 ? new File(args[0]) : null;
        SensorSpec spec = new SensorSpec();
        float clip = spec.range();
        RgbImage img = sky(clip);
        Renderer.Analysis a = Master.analyze(img, spec, false);
        System.out.println("analysis: " + a.describe());
        StarGlow plain = StarGlow.find(img, a, 1f);
        System.out.println("without the catalogue: " + plain.describe());
        measured(plain, clip);
        rejected(plain);
        a.sky = hints();
        StarGlow g = StarGlow.find(img, a, 1f);
        System.out.println("with the catalogue: " + g.describe());
        fromCatalogue(g, plain);
        renders(img, a, out);
        preview(img, spec);
        scaled(g);
        recipe();
        master();
        placed();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- the sky ----

    static double flux(double v) {
        return ZP * Math.pow(10, -0.4 * v);
    }

    /** The colour (r / Y - 1, b / Y - 1) a star of colour index bv has, as the glow takes it. */
    static float[] colour(double bv) throws Exception {
        Method m = StarGlow.class.getDeclaredMethod("colourOf", double.class, float[].class);
        m.setAccessible(true);
        float[] q = new float[2];
        m.invoke(null, bv, q);
        return q;
    }

    static RgbImage sky(float clip) throws Exception {
        float[] r = new float[W * H], g = new float[W * H], b = new float[W * H];
        Random rnd = new Random(11);
        // faint stars, none brighter than a fifth-magnitude one, none on the bright things
        for (int k = 0; k < 2500; k++) {
            float x = 20 + rnd.nextFloat() * (W - 40), y = 20 + rnd.nextFloat() * (H - 40);
            if (nearBright(x, y, 25)) continue;
            double f = Math.min(flux(5.2), 40 * Math.pow(Math.max(1e-6, rnd.nextDouble()), -1.2));
            double bv = -0.1 + 1.6 * rnd.nextDouble();
            stamp(r, g, b, x, y, f, bv, PSF);
        }
        for (double[] s : STARS) stamp(r, g, b, (float) s[0], (float) s[1], flux(s[2]), s[3], PSF);
        stamp(r, g, b, (float) PLANET[0], (float) PLANET[1], flux(PLANET[2]), PLANET[3], PSF);
        stamp(r, g, b, (float) WRONG[0], (float) WRONG[1], flux(WRONG[2]), WRONG[3], PSF);
        // the streak: white, along a line
        for (int y = (int) SY0 - 8; y <= SY1 + 8; y++) {
            for (int x = (int) SX0 - 8; x <= SX1 + 8; x++) {
                double d = segDist(x + 0.5, y + 0.5, SX0, SY0, SX1, SY1);
                double v = 400 * Math.exp(-d * d / (2 * PSF * PSF));
                r[y * W + x] += v;
                g[y * W + x] += v;
                b[y * W + x] += v;
            }
        }
        // the Moon: a clipped disc in a glow
        for (int y = (int) (MY - 120); y <= MY + 120; y++) {
            for (int x = (int) (MX - 120); x <= MX + 120; x++) {
                double d = Math.hypot(x + 0.5 - MX, y + 0.5 - MY);
                double v = d <= MR ? 3000 : 200 * Math.exp(-(d - MR) / 25);
                r[y * W + x] += v;
                g[y * W + x] += v;
                b[y * W + x] += v;
            }
        }
        RgbImage im = new RgbImage(W, H);
        im.clip = new byte[W * H];
        Random nz = new Random(3);
        for (int i = 0; i < W * H; i++) {
            float vr = BG + r[i], vg = BG + g[i], vb = BG + b[i];
            if (Math.max(vr, Math.max(vg, vb)) >= clip) im.clip[i] = (byte) 255;
            im.r[i] = Math.min(clip, vr + NOISE * (float) nz.nextGaussian());
            im.g[i] = Math.min(clip, vg + NOISE * (float) nz.nextGaussian());
            im.b[i] = Math.min(clip, vb + NOISE * (float) nz.nextGaussian());
        }
        return im;
    }

    static boolean nearBright(float x, float y, float d) {
        for (double[] s : STARS) if (Math.hypot(x - s[0], y - s[1]) < d) return true;
        for (double[] s : new double[][]{PLANET, HIDDEN, WRONG}) if (Math.hypot(x - s[0], y - s[1]) < d) return true;
        return segDist(x, y, SX0, SY0, SX1, SY1) < d || Math.hypot(x - MX, y - MY) < 150;
    }

    static double segDist(double x, double y, double x0, double y0, double x1, double y1) {
        double dx = x1 - x0, dy = y1 - y0, t = ((x - x0) * dx + (y - y0) * dy) / (dx * dx + dy * dy);
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(x - (x0 + t * dx), y - (y0 + t * dy));
    }

    /** A star of light f (luminance) and colour index bv, centred at (x, y) (a pixel's centre at +0.5). */
    static void stamp(float[] r, float[] g, float[] b, float x, float y, double f, double bv, float sg) throws Exception {
        float[] q = colour(bv);
        double peak = f / (2 * Math.PI * sg * sg);
        int rad = (int) Math.ceil(7 * sg);
        for (int yy = (int) y - rad; yy <= (int) y + rad; yy++) {
            for (int xx = (int) x - rad; xx <= (int) x + rad; xx++) {
                if (xx < 0 || yy < 0 || xx >= W || yy >= H) continue;
                double dx = xx + 0.5 - x, dy = yy + 0.5 - y;
                double v = peak * Math.exp(-(dx * dx + dy * dy) / (2 * sg * sg));
                int i = yy * W + xx;
                r[i] += (float) (v * (1 + q[0]));
                g[i] += (float) (v * (1 - (0.2126 * q[0] + 0.0722 * q[1]) / 0.7152));
                b[i] += (float) (v * (1 + q[1]));
            }
        }
    }

    /** The catalogue as a plate solution would place it: the bright stars, the planet, the hidden star, the wrong one. */
    static float[] hints() {
        List<double[]> l = new ArrayList<double[]>();
        for (double[] s : STARS) l.add(new double[]{s[0], s[1], s[2], s[3], 0});
        l.add(new double[]{PLANET[0], PLANET[1], PLANET[2], PLANET[3], 1});
        l.add(new double[]{HIDDEN[0], HIDDEN[1], HIDDEN[2], HIDDEN[3], 0});
        // a far brighter star than the one there (a bad match): -3.5 magnitude
        l.add(new double[]{WRONG[0] + 1, WRONG[1] - 1, -3.5, 1.0, 0});
        java.util.Collections.sort(l, new java.util.Comparator<double[]>() {
            @Override
            public int compare(double[] p, double[] q) {
                return Double.compare(p[2], q[2]);
            }
        });
        float[] out = new float[l.size() * StarGlow.SKY_STRIDE];
        for (int k = 0; k < l.size(); k++) for (int c = 0; c < 5; c++) out[k * StarGlow.SKY_STRIDE + c] = (float) l.get(k)[c];
        return out;
    }

    // ---- the lights ----

    static float[] arr(StarGlow g, String name) throws Exception {
        Field f = StarGlow.class.getDeclaredField(name);
        f.setAccessible(true);
        return (float[]) f.get(g);
    }

    static int num(StarGlow g, String name) throws Exception {
        Field f = StarGlow.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(g);
    }

    /** The light nearest (x, y) within d px, or -1. */
    static int near(StarGlow g, double x, double y, double d) throws Exception {
        float[] lx = arr(g, "x"), ly = arr(g, "y");
        int best = -1;
        for (int k = 0; k < g.count(); k++) {
            double e = Math.hypot(lx[k] - x, ly[k] - y);
            if (e <= d) {
                d = e;
                best = k;
            }
        }
        return best;
    }

    /** Unclipped stars measured as they are: where, how bright, what colour. */
    static void measured(StarGlow g, float clip) throws Exception {
        float[] l = arr(g, "light"), qr = arr(g, "qr"), qb = arr(g, "qb"), lx = arr(g, "x"), ly = arr(g, "y");
        int found = 0, n = 0;
        double worstL = 0, worstQ = 0, worstP = 0;
        for (double[] s : STARS) {
            int k = near(g, s[0], s[1], 3);
            if (k >= 0) found++;
            // unclipped: peak under 0.85 of the clip level
            double peak = flux(s[2]) / (2 * Math.PI * PSF * PSF) * 1.3 + BG;
            if (k < 0 || peak > 0.85 * clip) continue;
            float[] q = colour(s[3]);
            worstL = Math.max(worstL, Math.abs(l[k] / flux(s[2]) - 1));
            worstQ = Math.max(worstQ, Math.max(Math.abs(qr[k] - q[0]), Math.abs(qb[k] - q[1])));
            worstP = Math.max(worstP, Math.hypot(lx[k] - s[0], ly[k] - s[1]));
            n++;
        }
        String got = String.format(Locale.US, "%d of %d bright stars found; %d unclipped: light off by %.1f%% at most, colour by %.3f, "
                + "place by %.2f px", found, STARS.length, n, 100 * worstL, worstQ, worstP);
        System.out.println("measured: " + got);
        check("every bright star is a light", found == STARS.length, got);
        check("unclipped stars measured", n >= 12 && worstL < 0.06 && worstQ < 0.06 && worstP < 0.3, got);
        // the clipped ones lose light: the brightest measures far fainter than it is
        int v0 = near(g, STARS[0][0], STARS[0][1], 3);
        String gotC = v0 < 0 ? "not found" : String.format(Locale.US, "the brightest measures %.2f of its light", l[v0] / flux(STARS[0][2]));
        System.out.println("clipped: " + gotC);
        check("a clipped star measures fainter than it is", v0 >= 0 && l[v0] < 0.5 * flux(STARS[0][2]), gotC);
    }

    /** The streak and the Moon don't glow. */
    static void rejected(StarGlow g) throws Exception {
        float[] lx = arr(g, "x"), ly = arr(g, "y");
        int onStreak = 0, onMoon = 0;
        for (int k = 0; k < g.count(); k++) {
            if (segDist(lx[k], ly[k], SX0, SY0, SX1, SY1) < 6) onStreak++;
            if (Math.hypot(lx[k] - MX, ly[k] - MY) < MR + 8) onMoon++;
        }
        String got = String.format(Locale.US, "%d lights on the streak, %d on the Moon; %d streaks and %d wide glows left out", onStreak, onMoon,
                num(g, "streaks"), num(g, "wide"));
        System.out.println("rejected: " + got);
        check("the streak and the Moon don't glow", onStreak == 0 && onMoon == 0 && num(g, "streaks") >= 1 && num(g, "wide") >= 1, got);
    }

    /** What the catalogue gives: clipped stars' light and colour, the planet's, nothing for the hidden star, the cap. */
    static void fromCatalogue(StarGlow g, StarGlow plain) throws Exception {
        float[] l = arr(g, "light"), qr = arr(g, "qr"), qb = arr(g, "qb");
        Field zf = StarGlow.class.getDeclaredField("zeroPoint");
        zf.setAccessible(true);
        double zp = zf.getDouble(g);
        String gotZ = String.format(Locale.US, "zero point %.0f (true %.0f)", zp, ZP);
        System.out.println("catalogue: " + gotZ);
        check("the zero point is the unclipped stars'", Math.abs(zp / ZP - 1) < 0.05, gotZ);
        double worstL = 0, worstQ = 0;
        int clipped = 0;
        float[] pl = arr(plain, "light");
        for (double[] s : STARS) {
            int k = near(g, s[0], s[1], 3), p = near(plain, s[0], s[1], 3);
            if (k < 0 || p < 0 || pl[p] > 0.95 * flux(s[2])) continue;
            clipped++;
            float[] q = colour(s[3]);
            worstL = Math.max(worstL, Math.abs(l[k] / flux(s[2]) - 1));
            worstQ = Math.max(worstQ, Math.max(Math.abs(qr[k] - q[0]), Math.abs(qb[k] - q[1])));
        }
        String got = String.format(Locale.US, "%d clipped stars: light off by %.1f%% at most, colour by %.4f", clipped, 100 * worstL, worstQ);
        System.out.println("catalogue: " + got);
        check("clipped stars take their magnitude's light and their colour", clipped >= 5 && worstL < 0.08 && worstQ < 1e-4, got);
        int pk = near(g, PLANET[0], PLANET[1], 3);
        float[] pq = colour(PLANET[3]);
        String gotP = pk < 0 ? "not found" : String.format(Locale.US, "light %.2f of its magnitude's, colour %+.3f %+.3f (Mars' %+.3f %+.3f)",
                l[pk] / flux(PLANET[2]), qr[pk], qb[pk], pq[0], pq[1]);
        System.out.println("planet: " + gotP);
        check("the planet glows as bright as it is, in its colour", pk >= 0 && Math.abs(l[pk] / flux(PLANET[2]) - 1) < 0.08
                && Math.abs(qr[pk] - pq[0]) < 1e-4 && Math.abs(qb[pk] - pq[1]) < 1e-4, gotP);
        int hk = near(g, HIDDEN[0], HIDDEN[1], 8);
        StarGlow.Field f = g.field(1f, 1f, W, H);
        int[] at = {(int) HIDDEN[0], (int) HIDDEN[1]};
        float there = f.at(at[0], at[1]), planetAt = f.at((int) PLANET[0], (int) PLANET[1]);
        String gotH = String.format(Locale.US, "light there: %s; glow there %.3f (at the planet %.1f)", hk >= 0, there, planetAt);
        System.out.println("hidden: " + gotH);
        check("a star the photo doesn't show doesn't glow", hk < 0 && there < 0.002 * planetAt, gotH);
        int wk = near(g, WRONG[0], WRONG[1], 3), wp = near(plain, WRONG[0], WRONG[1], 3);
        String gotW = wk < 0 || wp < 0 ? "not found" : String.format(Locale.US, "%.1f times its measured light (the catalogue's would be %.0f times)",
                l[wk] / pl[wp], flux(-3.5) / pl[wp]);
        System.out.println("wrong match: " + gotW);
        check("a wrong match can't make a light more than 30 times brighter", wk >= 0 && wp >= 0 && l[wk] <= 30.001f * pl[wp]
                && flux(-3.5) > 30 * pl[wp], gotW);
        String gotN = String.format(Locale.US, "%d matched, %d made brighter, %d coloured", num(g, "matched"), num(g, "brightened"), num(g, "coloured"));
        System.out.println("counts: " + gotN);
        check("every star the photo shows is matched", num(g, "matched") == STARS.length + 2, gotN);
        // brightest first, and the sizes grow with the light
        boolean sorted = true;
        for (int k = 1; k < g.count(); k++) if (l[k] > l[k - 1]) sorted = false;
        check("brightest first", sorted, "");
    }

    // ---- renders ----

    static int[] render(RgbImage img, Renderer.Analysis a, float glow) {
        Renderer.Params p = new Renderer.Params();
        p.glow = glow;
        final int[] px = new int[a.cw * a.ch];
        Renderer.render(img, a, p, new Renderer.RowSink() {
            @Override
            public void rows(int y, int count, int[] argb, int width) {
                System.arraycopy(argb, 0, px, y * width, count * width);
            }
        }, null);
        return px;
    }

    static void renders(RgbImage img, Renderer.Analysis a, File out) throws Exception {
        int[] before = render(img, a, 0f);
        int[] one = render(img, a, 1f), two = render(img, a, 2f);
        int[] after = render(img, a, 0f);
        check("0% is the photo as it was, also after glowing renders", Arrays.equals(before, after), "");
        // around the brightest star, a few of its glow's sizes out: brighter with more glow
        double[] s = STARS[0];
        int x = (int) s[0] + 30, y = (int) s[1];
        int l0 = lum(before[y * W + x]), l1 = lum(one[y * W + x]), l2 = lum(two[y * W + x]);
        String got = String.format(Locale.US, "30 px from the brightest star: %d at 0%%, %d at 100%%, %d at 200%%", l0, l1, l2);
        System.out.println("renders: " + got);
        check("more glow, more light around the star", l1 > l0 + 8 && l2 > l1 + 4, got);
        // the glows' colours: around the planet (Mars' colour) red over blue, around the hottest star blue over red
        int px = (int) PLANET[0] + 18, py = (int) PLANET[1];
        int hx = (int) STARS[3][0] + 14, hy = (int) STARS[3][1];
        int pc = two[py * W + px], hc = two[hy * W + hx];
        String gotC = String.format(Locale.US, "beside the planet r %d b %d; beside the hot star r %d b %d", (pc >> 16) & 255, pc & 255,
                (hc >> 16) & 255, hc & 255);
        System.out.println("colours: " + gotC);
        check("glows in their stars' colours", ((pc >> 16) & 255) > (pc & 255) + 10 && (hc & 255) > ((hc >> 16) & 255) + 5, gotC);
        if (out != null) {
            out.mkdirs();
            SimRunner.writePng(before, W, H, new File(out, "glow_0.png"));
            SimRunner.writePng(one, W, H, new File(out, "glow_1.png"));
            SimRunner.writePng(two, W, H, new File(out, "glow_2.png"));
        }
    }

    static int lum(int argb) {
        return (int) Math.round(0.2126 * ((argb >> 16) & 255) + 0.7152 * ((argb >> 8) & 255) + 0.0722 * (argb & 255));
    }

    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    /**
     * The preview (a binned copy, as the result and edit screens show) and the photo glow alike: the copy's lights are
     * the full-size picture's, found once whichever is rendered first; found in the copy alone they're much the same.
     */
    static void preview(RgbImage img, SensorSpec spec) throws Exception {
        Renderer.Params p = new Renderer.Params();
        p.glow = 1f;
        Renderer.Analysis a = Master.analyze(img, spec, false);
        a.sky = hints();
        RgbImage small = img.binned(2);
        Renderer.renderPreview(small, a, p, 2);
        Object first = field(a, "glow"), from = field(a, "glowFrom");
        render(img, a, 1f);
        Object then = field(a, "glow");
        String got = String.format(Locale.US, "the preview's lights found in the full-size picture: %s; the photo's the same: %s", from == img, first == then);
        System.out.println("preview: " + got);
        check("the preview glows with the photo's lights", from == img && first == then && first != null, got);
        // in the copy alone (its picture gone): the same lights, near enough
        Method sm = Renderer.class.getDeclaredMethod("smallAnalysis", RgbImage.class, Renderer.Analysis.class, float.class);
        sm.setAccessible(true);
        Renderer.Analysis sa = (Renderer.Analysis) sm.invoke(null, small, a, 2f);
        StarGlow gs = StarGlow.find(small, sa, 0.5f), gf = (StarGlow) then;
        float[] fl = arr(gf, "light"), sl = arr(gs, "light"), fx = arr(gf, "x"), fy = arr(gf, "y");
        double worstP = 0, worstL = 0;
        int missing = 0;
        for (int k = 0; k < 20 && k < gf.count(); k++) {
            int j = near(gs, fx[k], fy[k], 4);
            if (j < 0) {
                missing++;
                continue;
            }
            float[] sx = arr(gs, "x"), sy = arr(gs, "y");
            worstP = Math.max(worstP, Math.hypot(sx[j] - fx[k], sy[j] - fy[k]));
            worstL = Math.max(worstL, Math.abs(sl[j] / fl[k] - 1));
        }
        String gotS = String.format(Locale.US, "the 20 brightest found in the half-size copy: %d missing, %.2f px and %.1f%% off at most", missing,
                worstP, 100 * worstL);
        System.out.println("copy: " + gotS);
        check("found in the copy alone, the same lights", missing == 0 && worstP < 1.5 && worstL < 0.15, gotS);
    }

    /** The glow of a picture scaled down holds the same light, in the same places. */
    static void scaled(StarGlow g) {
        double full = total(g.field(1f, 1f, W, H), W, H), half = total(g.field(1f, 0.5f, W / 2, H / 2), W / 2, H / 2);
        String got = String.format(Locale.US, "glow's light at full size %.4g, at half size x4 %.4g", full, 4 * half);
        System.out.println("scaled: " + got);
        check("the glow scaled down holds the same light", Math.abs(4 * half / full - 1) < 0.02, got);
    }

    static double total(StarGlow.Field f, int w, int h) {
        double sum = 0;
        int rows = 64;
        float[] y = new float[w * rows], cr = new float[w * rows], cb = new float[w * rows], yc = new float[w * rows];
        for (int y0 = 0; y0 < h; y0 += rows) {
            int y1 = Math.min(h, y0 + rows);
            Arrays.fill(y, 0f);
            f.add(y0, y1, y, cr, cb, yc);
            for (int i = 0; i < (y1 - y0) * w; i++) sum += y[i];
        }
        return sum;
    }

    // ---- kept ----

    static void recipe() {
        Recipe r = Recipe.single("s", false);
        String plain = r.toJson();
        Recipe was = r.edit();
        r.glow = 1.5f;
        Recipe back = Recipe.fromJson(r.toJson());
        String got = String.format(Locale.US, "glow %.2f back, %.2f in the render; none written when there's none: %s; changes: %s", back.glow,
                back.params().glow, !plain.contains("\"glow\""), r.changes(was));
        System.out.println("recipe: " + got);
        check("the edit's star glow is kept in its recipe", back.glow == 1.5f && back.params().glow == 1.5f && !plain.contains("\"glow\"")
                && r.changes(was).contains("star glow 150%"), got);
        Recipe t = Recipe.single("s", true);
        t.glow = 1f;
        check("no glow for star trails", t.params().glow == 0f, "");
    }

    /** A master keeps the lens model its plate solution refined, and gives the solution back with it. */
    static void master() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "starglowtest_" + System.nanoTime());
        RgbImage im = new RgbImage(16, 12);
        Master.Meta m = new Master.Meta();
        m.key = "glowtest";
        m.model = CameraModel.nominal(4080, 3072, 2875);
        CameraModel sm = m.model.copy();
        sm.k1 = -0.0431;
        sm.k2 = 0.0179;
        m.solutionModel = sm;
        m.solutionR = new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        m.solutionJd = 2461000.5;
        Master.write(dir, m, im);
        Master.Loaded l = Master.read(dir, "glowtest");
        SkySolver.Solution s = l != null ? l.meta.solution() : null;
        String got = l == null ? "not read back" : String.format(Locale.US, "solution's model k1 %.4f k2 %.4f (the frame's %.4f)",
                s.model.k1, s.model.k2, l.meta.model.k1);
        System.out.println("master: " + got);
        check("the master keeps the solution's own lens model", s != null && s.model.k1 == sm.k1 && s.model.k2 == sm.k2
                && l.meta.model.k1 == 0, got);
        m.solutionModel = null;
        m.key = "glowtest2";
        Master.write(dir, m, im);
        Master.Loaded l2 = Master.read(dir, "glowtest2");
        check("without one, the frame's", l2 != null && l2.meta.solutionModel == null && l2.meta.solution().model.k1 == 0, "");
        deleteTree(dir);
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** The catalogue placed by a plate solution: Vega where the camera points, brightest first, all in the picture. */
    static void placed() throws Exception {
        BrightStars cat = SimRunner.loadCatalog();
        int vega = -1;
        for (int i = 0; i < cat.n; i++) if ("Vega".equals(cat.name[i])) vega = i;
        double[] z = cat.vec(vega);
        double[] pole = {0, 0, 1};
        double[] x = norm(cross(pole, z)), y = cross(z, x);
        SkySolver.Solution s = new SkySolver.Solution();
        s.R = new double[]{x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2]};
        s.model = CameraModel.nominal(4080, 3072, 2875);
        Renderer.Analysis a = new Renderer.Analysis();
        a.cx0 = 40;
        a.cy0 = 30;
        a.cw = 4000;
        a.ch = 3000;
        float[] sky = StarGlow.sky(s, 2461000.5, cat, a);
        int n = sky.length / StarGlow.SKY_STRIDE;
        boolean sorted = true, inside = true;
        for (int k = 0; k < n; k++) {
            if (k > 0 && sky[k * 5 + 2] < sky[(k - 1) * 5 + 2]) sorted = false;
            if (sky[k * 5] < 0 || sky[k * 5] > a.cw || sky[k * 5 + 1] < 0 || sky[k * 5 + 1] > a.ch) inside = false;
        }
        double[] p = s.pixel(z);
        String got = String.format(Locale.US, "%d stars and planets; the first V %.2f B-V %.2f at %.1f, %.1f (Vega at %.1f, %.1f in the crop)", n,
                sky[2], sky[3], sky[0], sky[1], p[0] - a.cx0, p[1] - a.cy0);
        System.out.println("placed: " + got);
        check("the catalogue placed where the solution puts it", n > 50 && sorted && inside && Math.abs(sky[2] - 0.03f) < 0.02
                && Math.hypot(sky[0] - (p[0] - a.cx0), sky[1] - (p[1] - a.cy0)) < 0.01, got);
        check("no solution, nothing placed", StarGlow.sky(null, 2461000.5, cat, a) == null, "");
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] norm(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[]{v[0] / n, v[1] / n, v[2] / n};
    }

    static void check(String what, boolean ok, String got) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (got.isEmpty() ? "" : ": " + got));
        if (!ok) failures++;
    }
}
