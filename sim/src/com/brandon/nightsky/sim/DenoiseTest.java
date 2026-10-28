package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Denoise;
import com.brandon.nightsky.processing.Half;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.Stats;

import java.util.Locale;
import java.util.Random;

/**
 * The sky's noise reduction (1.4) on a made-up sky whose noise, stars and faint light are known:
 * <ul>
 * <li>the noise it measures between the stars, layer by layer, against the noise's own layers (a dense star field
 * mustn't pass for noise);</li>
 * <li>how much of each faint test star's light it keeps, from 1 to 8 times the noise;</li>
 * <li>how much of the grain and of the wide blotches it takes out, and that the sky's level doesn't move;</li>
 * <li>a faint band (a Milky Way with a dark lane) keeps its light;</li>
 * <li>the same correction, bit for bit, in tiles of other sizes (no seams, nothing left to how the work is split);</li>
 * <li>full-size renders use it (the same photo twice, less noise than without), Quick look's half-size photo too, and
 * pictures too small or without noise are left as they are.</li>
 * </ul>
 * Usage: DenoiseTest
 */
public final class DenoiseTest {
    private static int failures;

    static final int W = 1500, H = 1100;
    /** The white noise's sigma and the blotches' (white noise blurred at 6 px), luminance units. */
    static final float NOISE = 2f, BLOTCH = 0.6f;
    /** Test stars' peaks, in NOISE. */
    static final float[] SNR = {1f, 2f, 3f, 5f, 8f};
    static final float PSF = 1.2f;

    public static void main(String[] args) {
        Sky s = new Sky(11);
        SensorSpec spec = new SensorSpec();
        RgbImage full = s.image(true), base = s.image(false);
        Renderer.Analysis a = Master.analyze(full, spec, false), ab = Master.analyze(base, spec, false);
        Denoise.Result rf = Denoise.run(full, a, 1f), rb = Denoise.run(base, ab, 1f);
        check("a noisy sky is measured", rf != null && rb != null, "");
        if (rf == null || rb == null) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("found: " + rf.describe());
        measured(s, rf);
        kept(s, full, base, rf, rb);
        tiles(full, a, rf);
        renders(s, full, a);
        edges(spec);
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    /** The noise measured between the stars against the noise's own layers. */
    private static void measured(Sky s, Denoise.Result r) {
        float[][] c = smooths(s.noise, W, H);
        float[] truth = new float[Denoise.LEVELS];
        for (int j = 0; j < Denoise.LEVELS; j++) truth[j] = madOf(c[j], c[j + 1], null);
        float[] mf = blur(s.noise, W, H, PSF);
        float tmf = madOf(mf, c[3], null);
        String got = String.format(Locale.US, "measured %.3f %.3f %.3f mf %.3f, truth %.3f %.3f %.3f mf %.3f", r.sigma[0], r.sigma[1],
                r.sigma[2], r.sigmaMf, truth[0], truth[1], truth[2], tmf);
        System.out.println("noise: " + got + String.format(Locale.US, "; stars %.0f%% of the pixels", 100 * r.starFraction));
        check("finest layer's noise within 8% of the truth", Math.abs(r.sigma[0] / truth[0] - 1) < 0.08f, got);
        check("2-4 px layer's noise within 15%", Math.abs(r.sigma[1] / truth[1] - 1) < 0.15f, got);
        check("4-8 px layer's noise within 25%", Math.abs(r.sigma[2] / truth[2] - 1) < 0.25f, got);
        check("matched filter's noise within 15%", Math.abs(r.sigmaMf / tmf - 1) < 0.15f, got);
    }

    /** Test stars and the band: what's left of them; the grain and blotches: what's left of them; the sky's level. */
    private static void kept(Sky s, RgbImage full, RgbImage base, Denoise.Result rf, Denoise.Result rb) {
        float[] of = new float[W * H], ob = new float[W * H], diff = new float[W * H];
        for (int i = 0; i < W * H; i++) {
            of[i] = full.g[i] + Half.toFloat(rf.dy[i]);
            ob[i] = base.g[i] + Half.toFloat(rb.dy[i]);
            diff[i] = of[i] - ob[i];
        }
        // test stars: their brightness in what they add (a fit of the star's shape), over the level around them (the band's
        // light); the noise on a star stays (it's a star's pixel), so it's the average over many that tells
        float[] sum = new float[SNR.length];
        int[] cnt = new int[SNR.length];
        for (float[] t : s.tests) {
            int cx = (int) t[0], cy = (int) t[1];
            float base0 = 0;
            int nb = 0;
            for (int dy = -8; dy <= 8; dy++) {
                for (int dx = -8; dx <= 8; dx++) {
                    int r2 = dx * dx + dy * dy;
                    if (r2 < 25 || r2 > 64) continue;
                    base0 += diff[(cy + dy) * W + cx + dx];
                    nb++;
                }
            }
            base0 /= nb;
            double sg = 0, gg = 0;
            for (int dy = -3; dy <= 3; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    float ex = cx + dx + 0.5f - t[0], ey = cy + dy + 0.5f - t[1];
                    double g = Math.exp(-(ex * ex + ey * ey) / (2 * PSF * PSF));
                    sg += g * (diff[(cy + dy) * W + cx + dx] - base0);
                    gg += g * g;
                }
            }
            int k = (int) t[3];
            sum[k] += (float) (sg / gg) / t[2];
            cnt[k]++;
        }
        float[] need = {0.4f, 0.6f, 0.75f, 0.88f, 0.93f};
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        for (int k = 0; k < SNR.length; k++) {
            float kept = sum[k] / cnt[k];
            sb.append(String.format(Locale.US, "%s%.0fx %.0f%%", k > 0 ? ", " : "", SNR[k], 100 * kept));
            if (kept < need[k] || kept > 1.15f) ok = false;
        }
        System.out.println("test stars' light kept: " + sb);
        check("faint stars keep their light (1x 40%, 2x 60%, 3x 75%, 5x 88%, 8x 93% or more)", ok, sb.toString());

        // the band: its light, smoothed, away from the test stars
        float[] bd = blur(diff, W, H, 8f), bt = blur(s.band, W, H, 8f);
        double got = 0, want = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                if (s.band[i] < 0.3f * NOISE || s.nearTest[i]) continue;
                got += bd[i];
                want += bt[i];
            }
        }
        float band = (float) (got / want);
        check("the faint band keeps 85% of its light or more", band > 0.85f && band < 1.08f, String.format(Locale.US, "%.0f%%", 100 * band));

        // what's left of the noise on the sky between the stars: grain (1-2 and 2-4 px) and blotches (8 px and wider)
        boolean[] sky = new boolean[W * H];
        double lift = 0;
        int ns = 0;
        for (int i = 0; i < W * H; i++) {
            sky[i] = s.stars[i] < 0.05f * NOISE;
            if (!sky[i]) continue;
            lift += Half.toFloat(rb.dy[i]);
            ns++;
        }
        float[] res = new float[W * H];
        for (int i = 0; i < W * H; i++) res[i] = ob[i] - s.clean[i];
        float[][] cr = smooths(res, W, H), cn = smooths(s.noise, W, H);
        float g1 = madOf(cr[0], cr[1], sky) / madOf(cn[0], cn[1], sky);
        float g2 = madOf(cr[1], cr[2], sky) / madOf(cn[1], cn[2], sky);
        float[] wr = blur(res, W, H, 8f), wn = blur(s.noise, W, H, 8f);
        float bl = madOf(wr, null, sky) / madOf(wn, null, sky);
        String left = String.format(Locale.US, "1-2 px %.0f%%, 2-4 px %.0f%%, blotches %.0f%%", 100 * g1, 100 * g2, 100 * bl);
        System.out.println("noise left on the sky: " + left);
        check("the grain mostly goes (1-2 px under 35% of it, 2-4 px under 50%)", g1 < 0.35f && g2 < 0.5f, left);
        check("blotches are lowered (under 85%)", bl < 0.85f, left);
        float meanLift = (float) (lift / ns);
        // a little over nothing: noise peaks taken for faint stars keep their noise, and the rest loses its
        check("the sky's level stays put (mean change under 5% of the noise)", Math.abs(meanLift) < 0.05f * NOISE,
                String.format(Locale.US, "%.4f", meanLift));
    }

    /** The same correction, bit for bit, in tiles of other sizes. */
    private static void tiles(RgbImage full, Renderer.Analysis a, Denoise.Result r) {
        for (int t : new int[]{128, 384, 512}) {
            Denoise.Result o = Denoise.run(full, a, 1f, t);
            int diff = 0, first = -1;
            for (int i = 0; i < r.dy.length; i++) {
                if (o.dy[i] != r.dy[i]) {
                    if (first < 0) first = i;
                    diff++;
                }
            }
            check("tiles of " + t + " px give the same correction", diff == 0, diff + " pixels differ"
                    + (first >= 0 ? ", the first at " + (first % W) + "," + (first / W) : ""));
        }
    }

    /** Renders: the same photo twice, less noise with it than without; the half-size photo too. */
    private static void renders(Sky s, RgbImage full, Renderer.Analysis a) {
        Renderer.Params on = new Renderer.Params(), off = new Renderer.Params();
        off.lumaNr = 0;
        int[] p1 = Master.render(full, a, on), p2 = Master.render(full, a, on), p0 = Master.render(full, a, off);
        check("a full-size render with it comes out the same twice", Master.pixelHash(p1).equals(Master.pixelHash(p2)), "");
        check("its finding is kept for the log", !a.noiseNote().isEmpty(), a.noiseNote());
        float n1 = skyNoise(p1, W, H, s), n0 = skyNoise(p0, W, H, s);
        check("the photo's sky is quieter with it", n1 < 0.6f * n0, String.format(Locale.US, "%.2f vs %.2f levels", n1, n0));
        RgbImage half = full.binned(2);
        int[] q1 = Renderer.renderSmallPhoto(half, a, on, 2), q0 = Renderer.renderSmall(half, a, on, 2);
        float m1 = skyNoise(q1, half.width, half.height, null), m0 = skyNoise(q0, half.width, half.height, null);
        check("Quick look's half-size photo is quieter too", m1 < 0.75f * m0, String.format(Locale.US, "%.2f vs %.2f levels", m1, m0));
    }

    /** Pictures too small, or without any noise: left as they are, without a fuss. */
    private static void edges(SensorSpec spec) {
        RgbImage tiny = flat(40, 30, 20f);
        tiny.g[5 * 40 + 7] += 50;
        Renderer.Analysis at = Master.analyze(tiny, spec, false);
        check("a picture too small is left as it is", Denoise.run(tiny, at, 1f) == null, "");
        int[] pt = Master.render(tiny, at, new Renderer.Params());
        check("and still renders", pt.length == 40 * 30, "");
        RgbImage still = flat(400, 300, 20f);
        Renderer.Analysis as = Master.analyze(still, spec, false);
        Denoise.Result r = null;
        String err = "";
        try {
            r = Denoise.run(still, as, 1f);
        } catch (RuntimeException e) {
            err = e.toString();
        }
        boolean clean = err.isEmpty();
        if (r != null) for (short h : r.dy) if (Half.toFloat(h) != 0f) clean = false;
        check("a sky without noise is left as it is", clean, err);
    }

    private static RgbImage flat(int w, int h, float v) {
        RgbImage im = new RgbImage(w, h);
        java.util.Arrays.fill(im.r, v);
        java.util.Arrays.fill(im.g, v);
        java.util.Arrays.fill(im.b, v);
        return im;
    }

    /** A rendered photo's grain: the median absolute difference of neighbouring pixels' green, on the sky. */
    private static float skyNoise(int[] px, int w, int h, Sky s) {
        float[] d = new float[w * h];
        int n = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x + 1 < w; x++) {
                int i = y * w + x;
                if (s != null && (s.stars[i] >= 0.05f * NOISE || s.stars[i + 1] >= 0.05f * NOISE || s.band[i] > 0.2f * NOISE)) continue;
                d[n++] = Math.abs(((px[i] >> 8) & 0xFF) - ((px[i + 1] >> 8) & 0xFF));
            }
        }
        // 8-bit levels: the mean is steadier than the median here
        double m = 0;
        for (int i = 0; i < n; i++) m += d[i];
        return (float) (m / Math.max(1, n));
    }

    // ---- the made-up sky ----

    static final class Sky {
        /** Background and field stars; the band; the test stars; the noise. */
        final float[] clean = new float[W * H], stars = new float[W * H], band = new float[W * H], test = new float[W * H],
                noise = new float[W * H];
        final boolean[] nearTest = new boolean[W * H];
        /** Test stars: x, y, peak, index into SNR. */
        final java.util.List<float[]> tests = new java.util.ArrayList<float[]>();

        Sky(long seed) {
            Random rnd = new Random(seed);
            // a dense field: many stars at and under the noise, a few bright ones
            for (int k = 0; k < 14000; k++) {
                float x = rnd.nextFloat() * W, y = rnd.nextFloat() * H;
                double u = Math.max(1e-6, rnd.nextDouble());
                float peak = (float) Math.min(3000, 0.4 * NOISE * Math.pow(u, -1.4));
                stamp(stars, x, y, peak);
            }
            for (int i = 0; i < W * H; i++) {
                int x = i % W, y = i / W;
                clean[i] = 50f + 0.004f * x + 0.003f * y + stars[i];
            }
            // a faint band across the middle, half the noise at its brightest, with a dark lane
            for (int y = 0; y < H; y++) {
                double dy = y - H * 0.45;
                double b = 0.5 * NOISE * Math.exp(-dy * dy / (2 * 140.0 * 140.0)) * (1 - 0.6 * Math.exp(-(dy - 15) * (dy - 15) / (2 * 14.0 * 14.0)));
                for (int x = 0; x < W; x++) band[y * W + x] = (float) (b * (0.8 + 0.2 * Math.sin(x / 90.0)));
            }
            // test stars on a grid, each strength in every row and column of five
            for (int j = 0; j < 13; j++) {
                for (int i = 0; i < 19; i++) {
                    int k = (i + j) % SNR.length;
                    float x = 120 + i * 70 + rnd.nextFloat(), y = 130 + j * 70 + rnd.nextFloat();
                    stamp(test, x, y, SNR[k] * NOISE);
                    tests.add(new float[]{x, y, SNR[k] * NOISE, k});
                    for (int dy = -10; dy <= 10; dy++) {
                        for (int dx = -10; dx <= 10; dx++) nearTest[(Math.round(y) + dy) * W + Math.round(x) + dx] = true;
                    }
                }
            }
            // noise: white, plus blotches (white noise blurred at 6 px)
            float[] wb = new float[W * H];
            for (int i = 0; i < W * H; i++) wb[i] = (float) rnd.nextGaussian();
            wb = blur(wb, W, H, 6f);
            float sb = madOf(wb, null, null);
            for (int i = 0; i < W * H; i++) noise[i] = (float) rnd.nextGaussian() * NOISE + wb[i] * (BLOTCH / sb);
        }

        RgbImage image(boolean withTests) {
            RgbImage im = new RgbImage(W, H);
            for (int i = 0; i < W * H; i++) {
                float v = clean[i] + noise[i] + (withTests ? band[i] + test[i] : 0f);
                im.r[i] = v;
                im.g[i] = v;
                im.b[i] = v;
            }
            return im;
        }

        static void stamp(float[] into, float px, float py, float peak) {
            int r = (int) Math.ceil(5 * PSF);
            for (int y = (int) py - r; y <= (int) py + r; y++) {
                if (y < 0 || y >= H) continue;
                for (int x = (int) px - r; x <= (int) px + r; x++) {
                    if (x < 0 || x >= W) continue;
                    float dx = x + 0.5f - px, dy = y + 0.5f - py;
                    into[y * W + x] += peak * (float) Math.exp(-(dx * dx + dy * dy) / (2 * PSF * PSF));
                }
            }
        }
    }

    // ---- helpers ----

    /** Robust sigma (MAD) of a - b (b null: of a about its median) over the masked pixels (null: all). */
    static float madOf(float[] a, float[] b, boolean[] mask) {
        float[] t = new float[a.length];
        int n = 0;
        for (int i = 0; i < a.length; i++) if (mask == null || mask[i]) t[n++] = b != null ? a[i] - b[i] : a[i];
        float med = b != null ? 0f : Stats.median(t.clone(), n);
        for (int i = 0; i < n; i++) t[i] = Math.abs(t[i] - med);
        return 1.4826f * Stats.median(t, n);
    }

    /** c[0] = the image, c[j + 1] = its B3-spline smooth at step 2^j (mirrored at the edges). */
    static float[][] smooths(float[] y, int w, int h) {
        float[][] c = new float[Denoise.LEVELS + 1][];
        c[0] = y;
        for (int j = 0; j < Denoise.LEVELS; j++) c[j + 1] = b3(c[j], w, h, 1 << j);
        return c;
    }

    static float[] b3(float[] s, int w, int h, int step) {
        float[] t = new float[s.length], o = new float[s.length];
        for (int y = 0; y < h; y++) {
            int i = y * w;
            for (int x = 0; x < w; x++) {
                t[i + x] = (6 * s[i + x] + 4 * (s[i + mirror(x - step, w)] + s[i + mirror(x + step, w)]) + s[i + mirror(x - 2 * step, w)]
                        + s[i + mirror(x + 2 * step, w)]) / 16f;
            }
        }
        for (int y = 0; y < h; y++) {
            int a1 = mirror(y - step, h) * w, b1 = mirror(y + step, h) * w, a2 = mirror(y - 2 * step, h) * w, b2 = mirror(y + 2 * step, h) * w;
            for (int x = 0; x < w; x++) o[y * w + x] = (6 * t[y * w + x] + 4 * (t[a1 + x] + t[b1 + x]) + t[a2 + x] + t[b2 + x]) / 16f;
        }
        return o;
    }

    static float[] blur(float[] s, int w, int h, float sg) {
        int r = (int) Math.ceil(3 * sg);
        float[] k = new float[2 * r + 1];
        float ks = 0;
        for (int i = -r; i <= r; i++) ks += k[i + r] = (float) Math.exp(-i * i / (2.0 * sg * sg));
        for (int i = 0; i < k.length; i++) k[i] /= ks;
        float[] t = new float[s.length], o = new float[s.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float acc = 0;
                for (int j = -r; j <= r; j++) acc += k[j + r] * s[y * w + mirror(x + j, w)];
                t[y * w + x] = acc;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float acc = 0;
                for (int j = -r; j <= r; j++) acc += k[j + r] * t[mirror(y + j, h) * w + x];
                o[y * w + x] = acc;
            }
        }
        return o;
    }

    static int mirror(int i, int n) {
        while (i < 0 || i >= n) i = i < 0 ? -i : 2 * (n - 1) - i;
        return i;
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failures++;
    }
}
