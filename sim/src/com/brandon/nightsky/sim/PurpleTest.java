package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.StarHalo;

import java.io.File;
import java.util.Locale;
import java.util.Random;

/**
 * Purple star halos (2.4, {@link StarHalo}): stacks drawn with each colour's star image its own size (blue wider than
 * green, with a faint skirt, red a little wider), rendered with and without the matched colour windows.
 * <ul>
 * <li>The sizes are measured as drawn (to a tenth of a pixel).
 * <li>Around the bright stars, the ring's colour is the star's own with the windows, and clearly purple without.
 * <li>The stars' colours stay right (the faint ones in a crowded field too).
 * <li>Stars whose colours are all the same size: nothing is changed (the windows aren't used).
 * </ul>
 * Optional argument: a folder for before/after crops of a bright star.
 */
public final class PurpleTest {
    static int failures = 0;
    static final int W = 1600, H = 1200;

    public static void main(String[] args) throws Exception {
        File out = args.length > 0 ? new File(args[0]) : null;
        if (out != null) out.mkdirs();
        purple(out);
        matched();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    /** A drawn stack's stars: place, total light and colour (camera r/g, b/g). */
    static final class Field {
        RgbImage img;
        double[] x, y, flux, cr, cb;
        int n;
        SensorSpec spec = new SensorSpec();
    }

    /**
     * Stars with each camera colour's image a Gaussian of its own size, blue with a share of its light in a wide skirt:
     * sg green, sr red, sb blue (sigma, px), skirt: blue's share in a skirt of skirtSigma.
     */
    static Field field(double sr, double sg, double sb, double skirt, double skirtSigma, int stars, long seed) {
        Field f = new Field();
        Random rnd = new Random(seed);
        int n = W * H;
        float[] r = new float[n], g = new float[n], b = new float[n];
        for (int i = 0; i < n; i++) {
            r[i] = 90;
            g[i] = 100;
            b[i] = 80;
        }
        f.n = stars;
        f.x = new double[stars];
        f.y = new double[stars];
        f.flux = new double[stars];
        f.cr = new double[stars];
        f.cb = new double[stars];
        for (int k = 0; k < stars; k++) {
            f.x[k] = 20 + rnd.nextDouble() * (W - 40);
            f.y[k] = 20 + rnd.nextDouble() * (H - 40);
            double mag = 9.5 + 2.4 * Math.log10(Math.max(1e-4, rnd.nextDouble()));
            f.flux[k] = 4000 * Math.pow(10, -0.4 * (mag - 4));
            // a blackbody's camera colour: hot stars blue-white, cool ones orange
            double t = rnd.nextDouble();
            f.cr[k] = 0.75 + 0.6 * t;
            f.cb[k] = 1.25 - 0.6 * t;
            spot(r, f.x[k], f.y[k], sr, f.flux[k] * f.cr[k]);
            spot(g, f.x[k], f.y[k], sg, f.flux[k]);
            spot(b, f.x[k], f.y[k], sb, f.flux[k] * f.cb[k] * (1 - skirt));
            if (skirt > 0) spot(b, f.x[k], f.y[k], skirtSigma, f.flux[k] * f.cb[k] * skirt);
        }
        float clip = f.spec.range();
        for (int i = 0; i < n; i++) {
            r[i] = Math.min(clip, (float) (r[i] + 1.2 * rnd.nextGaussian()));
            g[i] = Math.min(clip, (float) (g[i] + 1.2 * rnd.nextGaussian()));
            b[i] = Math.min(clip, (float) (b[i] + 1.2 * rnd.nextGaussian()));
        }
        f.img = new RgbImage(W, H, r, g, b);
        f.img.x0 = 4;
        f.img.y0 = 4;
        f.img.x1 = W - 4;
        f.img.y1 = H - 4;
        return f;
    }

    static void spot(float[] img, double px, double py, double sg, double flux) {
        int R = (int) Math.ceil(4.5 * sg);
        for (int y = Math.max(0, (int) py - R); y <= Math.min(H - 1, (int) py + R); y++) {
            for (int x = Math.max(0, (int) px - R); x <= Math.min(W - 1, (int) px + R); x++) {
                double d2 = sq(x + 0.5 - px) + sq(y + 0.5 - py);
                img[y * W + x] += (float) (flux / (2 * Math.PI * sg * sg) * Math.exp(-d2 / (2 * sg * sg)));
            }
        }
    }

    /** How a render coloured the stars: the ring's blue/green and red/green over the core's, and the cores' colour error. */
    static final class Look {
        double ringBlue, ringRed, coreErr;
        int bright;
    }

    static Look look(Field f, Renderer.Analysis a, int[] px) {
        Look l = new Look();
        double sb = 0, sr = 0, se = 0;
        int nb = 0, ne = 0;
        int cw = a.cw;
        for (int k = 0; k < f.n; k++) {
            double x = f.x[k] - a.cx0, y = f.y[k] - a.cy0;
            if (x < 12 || y < 12 || x > a.cw - 12 || y > a.ch - 12) continue;
            if (!single(f, k, 14, 0.05)) continue;
            double[] core = mean(px, cw, x, y, 0, 1.5), ring = mean(px, cw, x, y, 2.5, 5);
            if (core == null || ring == null) continue;
            // bright stars, unclipped: a ring bright enough to show its colour
            double peak = f.flux[k] / (2 * Math.PI * 1.69);
            if (peak > 60 && peak < 600 && ring[1] > 20) {
                sb += (ring[2] / ring[1]) / (core[2] / core[1]) - 1;
                sr += (ring[0] / ring[1]) / (core[0] / core[1]) - 1;
                nb++;
            }
        }
        l.ringBlue = nb > 0 ? sb / nb : Double.NaN;
        l.ringRed = nb > 0 ? sr / nb : Double.NaN;
        l.bright = nb;
        return l;
    }

    /** The mean display colour (0..255 each) of the crop's pixels between r0 and r1 px from (x, y), less the sky's. */
    static double[] mean(int[] px, int cw, double x, double y, double r0, double r1) {
        double[] s = new double[3];
        int n = 0;
        int R = (int) Math.ceil(r1) + 1;
        for (int yy = (int) y - R; yy <= (int) y + R; yy++) {
            for (int xx = (int) x - R; xx <= (int) x + R; xx++) {
                double d = Math.hypot(xx + 0.5 - x, yy + 0.5 - y);
                if (d < r0 || d > r1) continue;
                int v = px[yy * cw + xx];
                s[0] += (v >> 16) & 0xFF;
                s[1] += (v >> 8) & 0xFF;
                s[2] += v & 0xFF;
                n++;
            }
        }
        if (n == 0) return null;
        for (int c = 0; c < 3; c++) s[c] /= n;
        return s;
    }

    /** No other star over {@code share} of this one's light within {@code r} px. */
    static boolean single(Field f, int k, double r, double share) {
        for (int j = 0; j < f.n; j++) {
            if (j == k) continue;
            if (Math.abs(f.x[j] - f.x[k]) > r || Math.abs(f.y[j] - f.y[k]) > r) continue;
            if (Math.hypot(f.x[j] - f.x[k], f.y[j] - f.y[k]) <= r && f.flux[j] > share * f.flux[k]) return false;
        }
        return true;
    }

    /**
     * The stars' colours as rendered against their true colours: the rms over the stars of the error in display
     * log(r/g) and log(b/g) of the light within 2.5 px (the sky taken off), for stars from faint to bright.
     */
    static double colourError(Field f, Renderer.Analysis a, int[] px) {
        double sky[] = skyLevel(px, a.cw, a.ch), se = 0;
        int n = 0;
        for (int k = 0; k < f.n; k++) {
            double x = f.x[k] - a.cx0, y = f.y[k] - a.cy0;
            if (x < 12 || y < 12 || x > a.cw - 12 || y > a.ch - 12) continue;
            double peak = f.flux[k] / (2 * Math.PI * 1.69);
            if (peak < 15 || peak > 600) continue;
            double[] c = mean(px, a.cw, x, y, 0, 2.5);
            if (c == null) continue;
            double r = c[0] - sky[0], g = c[1] - sky[1], b = c[2] - sky[2];
            if (g < 12 || r <= 1 || b <= 1) continue;
            // the true colour through the same (identity) matrix, in display terms (about the square root)
            double tr = Math.sqrt(f.cr[k]), tb = Math.sqrt(f.cb[k]);
            double er = Math.log((r / g) / tr), eb = Math.log((b / g) / tb);
            se += er * er + eb * eb;
            n++;
        }
        return n > 0 ? Math.sqrt(se / (2 * n)) : Double.NaN;
    }

    static double[] skyLevel(int[] px, int w, int h) {
        long[] s = new long[3];
        int n = 0;
        for (int y = 0; y < h; y += 7) {
            for (int x = 0; x < w; x += 7) {
                int v = px[y * w + x];
                s[0] += (v >> 16) & 0xFF;
                s[1] += (v >> 8) & 0xFF;
                s[2] += v & 0xFF;
                n++;
            }
        }
        return new double[]{s[0] / (double) n, s[1] / (double) n, s[2] / (double) n};
    }

    static void purple(File out) throws Exception {
        Field f = field(1.5, 1.3, 1.75, 0.15, 4.0, 2500, 3);
        Renderer.Analysis a = Master.analyze(f.img, f.spec, false);
        StarHalo h = a.halo;
        System.out.println(h != null ? h.describe() : "no halo measurement");
        check(h != null && h.stars >= 50, "the halos are measured on 50 stars or more");
        if (h == null) return;
        // what a moment over 6 px makes of the drawn blue: its core and skirt together
        double sbTrue = Math.sqrt(0.85 * 1.75 * 1.75 + 0.15 * momentSq(4.0, 6) / 2);
        System.out.println(String.format(Locale.US, "  drawn: r 1.50, g 1.30, b %.2f (core and skirt within 6 px)", sbTrue));
        check(Math.abs(h.size[0] - 1.5) < 0.1 && Math.abs(h.size[1] - 1.3) < 0.1 && Math.abs(h.size[2] - sbTrue) < 0.15,
                "each colour's star size is measured as drawn");
        check(h.ringBlue > 0.15, "the measured ring is clearly bluer than the star");
        check(h.windows != null, "the colour windows are used");
        Renderer.Params p = new Renderer.Params();
        int[] after = Master.render(f.img, a, p);
        Renderer.Analysis b = Master.analyze(f.img, f.spec, false);
        b.colorWindows = null;
        int[] before = Master.render(f.img, b, p);
        Look lb = look(f, b, before), la = look(f, a, after);
        double eb = colourError(f, b, before), ea = colourError(f, a, after);
        System.out.println(String.format(Locale.US, "  rings around %d bright stars: 2.3 blue %+.0f%% red %+.0f%%, 2.4 blue %+.0f%% red %+.0f%%; "
                + "star colour error 2.3 %.3f, 2.4 %.3f", la.bright, 100 * lb.ringBlue, 100 * lb.ringRed, 100 * la.ringBlue, 100 * la.ringRed, eb, ea));
        check(la.bright >= 20, "20 bright single stars or more to look at");
        check(lb.ringBlue > 0.1, "2.3's rings are purple (a tenth bluer than the core, or more)");
        check(Math.abs(la.ringBlue) < 0.4 * Math.abs(lb.ringBlue) && Math.abs(la.ringBlue) < 0.08, "2.4's rings are the star's colour");
        check(Math.abs(la.ringRed) <= Math.max(0.05, Math.abs(lb.ringRed)), "2.4's rings aren't redder than 2.3's");
        check(ea <= eb * 1.1 + 0.005, "the stars' own colours are no worse");
        if (out != null) {
            crops(f, b, before, a, after, out);
        }
    }

    /** The second moment (x^2 + y^2) of a Gaussian of sigma s truncated at radius R. */
    static double momentSq(double s, double R) {
        double m = 0, w = 0;
        for (double r = 0.005; r < R; r += 0.01) {
            double v = Math.exp(-r * r / (2 * s * s)) * r;
            m += v * r * r;
            w += v;
        }
        return m / w;
    }

    static void matched() {
        Field f = field(1.3, 1.3, 1.3, 0, 4, 2500, 5);
        Renderer.Analysis a = Master.analyze(f.img, f.spec, false);
        System.out.println(a.halo != null ? a.halo.describe() : "no halo measurement");
        check(a.halo != null && a.halo.windows == null, "the colours' star sizes match: no windows, the render is 2.3's");
    }

    /** The brightest single stars' crops, before and after, side by side and four times the size. */
    static void crops(Field f, Renderer.Analysis b, int[] before, Renderer.Analysis a, int[] after, File out) throws Exception {
        int C = 24, Z = 4, k0 = -1;
        double best = 0;
        for (int k = 0; k < f.n; k++) {
            double x = f.x[k] - a.cx0, y = f.y[k] - a.cy0;
            double peak = f.flux[k] / (2 * Math.PI * 1.69);
            if (x < C || y < C || x > a.cw - C || y > a.ch - C || peak > 500 || !single(f, k, 20, 0.03)) continue;
            if (peak > best) {
                best = peak;
                k0 = k;
            }
        }
        if (k0 < 0) return;
        int cx = (int) (f.x[k0] - a.cx0), cy = (int) (f.y[k0] - a.cy0);
        int ow = (2 * C) * Z * 2 + 8, oh = 2 * C * Z;
        int[] o = new int[ow * oh];
        java.util.Arrays.fill(o, 0xFFFFFFFF);
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < 2 * C * Z; x++) {
                int sx = cx - C + x / Z, sy = cy - C + y / Z;
                o[y * ow + x] = before[sy * a.cw + sx];
                o[y * ow + x + 2 * C * Z + 8] = after[sy * a.cw + sx];
            }
        }
        File fo = new File(out, "halo-before-after.png");
        SimRunner.writePng(o, ow, oh, fo);
        System.out.println("  picture: " + fo);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static double sq(double v) {
        return v * v;
    }
}
