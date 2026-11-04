package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Denoise;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.Recipe;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;

import java.util.Locale;
import java.util.Random;

/**
 * The sky's detail brought out (1.9) on a made-up sky whose parts are known: a Milky Way band with star clouds and a
 * dark lane, a bright star with a dewy glow, the Moon with its glow, the ground under a wavy skyline, and plain sky.
 * <ul>
 * <li>the band's own detail comes out (the detail found follows it), the plain sky gets next to none;</li>
 * <li>the bright star's glow and the Moon's aren't brought out, nor a dark ring drawn around them;</li>
 * <li>nothing on the ground or along the skyline;</li>
 * <li>the same detail bit for bit in tiles of another size; a half-size copy (the screen's preview) gets the full-size
 * detail binned exactly, and found in the copy itself it's much the same;</li>
 * <li>renders: none of it at 0, more of it at twice the setting; the edit's setting kept in its recipe.</li>
 * </ul>
 * Usage: DetailTest [out dir for pictures]
 */
public final class DetailTest {
    private static int failures;

    static final int W = 2400, H = 1600;
    static final float NOISE = 2f, PSF = 1.2f, BG = 50f;
    /** The band's brightness at its middle, its width (sigma, px), and its middle line y = BAND_Y + BAND_TILT (x - W / 2). */
    static final float BAND = 8f, BAND_W = 90f, BAND_Y = 1000f, BAND_TILT = 0f;
    /** The camera's white level: brighter clips (the Moon does, the bright star doesn't). */
    static final float WHITE = 3000f;
    /** The bright star with a glow: where, its peak, the glow's share of its light and its size (sigma, px). */
    static final float GX = 2000, GY = 300, G_PEAK = 3000f, G_SHARE = 0.3f, G_SIGMA = 15f;
    /** The Moon: where, its radius and level, and its glow's level at the limb and how it falls off (px). */
    static final float MX = 1150, MY = 300, M_R = 14f, M_LEVEL = 4000f, M_GLOW = 150f, M_FALL = 25f;
    /** The ground: under y = GROUND_Y + 30 sin(x / 120), at this level. */
    static final float GROUND_Y = 1450f, GROUND = 8f;

    public static void main(String[] args) throws Exception {
        java.io.File out = args.length > 0 ? new java.io.File(args[0]) : null;
        Sky s = new Sky(5);
        RgbImage img = s.image();
        SensorSpec spec = new SensorSpec();
        Renderer.Analysis a = Master.analyze(img, spec, false);
        Denoise.Result r = Denoise.run(img, a, 1f);
        check("the sky is measured and has detail", r != null && r.dd != null, r == null ? "null" : r.describe());
        if (r == null || r.dd == null) finish();
        System.out.println("found: " + r.describe());
        background(s, a);
        float[] dd = new float[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) dd[y * W + x] = r.detailAt(x, y);
        if (out != null) {
            out.mkdirs();
            int[] px = new int[W * H];
            for (int i = 0; i < W * H; i++) {
                int v = Math.max(0, Math.min(255, Math.round(128 + 32 * dd[i])));
                px[i] = 0xFF000000 | v << 16 | v << 8 | v;
            }
            SimRunner.writePng(px, W, H, new java.io.File(out, "dd.png"));
        }
        band(s, dd);
        floor(s, dd);
        plain(s, dd);
        glow(s, dd);
        moon(s, dd);
        ground(s, dd);
        tiles(img, a, r);
        half(img, a, r);
        renders(img, a, s, out);
        recipe();
        finish();
    }

    private static void finish() {
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** The band's detail (the band less its 128 px blur) against the detail found, where the band is and nothing else. */
    private static void band(Sky s, float[] dd) {
        float[] wide = blur(s.band, W, H, 128f);
        double sxy = 0, sxx = 0, syy = 0;
        int n = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                if (!s.inBand(x, y) || s.nearBright(x, y)) continue;
                double t = s.band[i] - wide[i], d = dd[i];
                sxy += t * d;
                sxx += t * t;
                syy += d * d;
                n++;
            }
        }
        double corr = sxy / Math.sqrt(sxx * syy), slope = sxy / sxx;
        String got = String.format(Locale.US, "correlation %.3f, %.2f of the band's detail found (%d px), its detail %.2f rms", corr,
                slope, n, Math.sqrt(sxx / n));
        System.out.println("band: " + got);
        check("the band's detail is what's found", corr > 0.8, got);
        check("much of the band's detail is found", slope > 0.35 && slope < 1.3, got);
    }

    /**
     * Even at the most the edit screen gives, no part of the sky is taken darker than the plain sky: a lane has only the
     * band's light to lose, and a wide layer dips beside a bright band (which would shadow the sky along it).
     */
    private static void floor(Sky s, float[] dd) {
        float most = 2 * Renderer.Params.DETAIL, worst = Float.MAX_VALUE, worstAll = Float.MAX_VALUE;
        int wx = 0, wy = 0, ns = 0, under = 0;
        double side = 0;
        // the sky right around a bright star has its wings' light too
        boolean[] nearStar = new boolean[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (s.ground(x, y) || s.sky[y * W + x] - (BG + 0.004f * x + 0.003f * y) < 50) continue;
                for (int j = Math.max(0, y - 12); j <= Math.min(H - 1, y + 12); j++) {
                    for (int i = Math.max(0, x - 12); i <= Math.min(W - 1, x + 12); i++) nearStar[j * W + i] = true;
                }
            }
        }
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (s.nearBright(x, y) || y > s.skyline(x) - 100) continue;
                int i = y * W + x;
                float v = s.band[i] + most * dd[i];
                worstAll = Math.min(worstAll, v);
                if (v < -0.01f * BAND) under++;
                if (v < worst && !nearStar[i]) {
                    worst = v;
                    wx = x;
                    wy = y;
                }
                float d = Math.abs(y - (BAND_Y + BAND_TILT * (x - W / 2f)));
                if (d > 2.5f * BAND_W && d < 5 * BAND_W) {
                    side += v;
                    ns++;
                }
            }
        }
        String got = String.format(Locale.US, "at %.0f%%: %.3f under the plain sky at most away from bright stars (%d, %d), %.3f beside them; "
                + "%d px more than %.2f under; %.4f over it on average beside the band", 100 * most, -worst, wx, wy, -worstAll, under,
                0.01f * BAND, side / ns);
        System.out.println("floor: " + got);
        check("never darker than the plain sky", worst > -0.02f * BAND, got);
        check("the sky beside the band isn't shadowed", side / ns > -0.002f * BAND, got);
    }

    /**
     * The background fitted around the ground (1.9): on the plain sky (away from the band and the bright lights) it's
     * the sky's own level, not bent down to meet the ground below.
     */
    private static void background(Sky s, Renderer.Analysis a) {
        float grey = a.whiteY / a.clip, worst = 0;
        int wx = 0, wy = 0;
        for (int y = 10; y < H; y += 20) {
            for (int x = 10; x < W; x += 20) {
                float d = Math.abs(y - (BAND_Y + BAND_TILT * (x - W / 2f)));
                if (d < 3 * BAND_W || s.nearBright(x, y) || y > s.skyline(x) - 20) continue;
                double u = ((x - a.cx0 + 0.5) / a.cw) * 2 - 1, v = ((y - a.cy0 + 0.5) / a.ch) * 2 - 1, f = 0;
                double[] t = {1, u, v, u * u, u * v, v * v};
                for (int c = 0; c < 3; c++) {
                    double wt = c == 0 ? 0.2126 : c == 1 ? 0.7152 : 0.0722, fc = 0;
                    for (int k = 0; k < 6; k++) fc += a.poly[c][k] * t[k];
                    f += wt * fc;
                }
                float off = (float) Math.abs(f - grey * (BG + 0.004f * x + 0.003f * y));
                if (off > worst) {
                    worst = off;
                    wx = x;
                    wy = y;
                }
            }
        }
        String got = String.format(Locale.US, "off the plain sky by %.3f at most (%d, %d; sky %.0f), %s", worst, wx, wy, grey * BG, a.describe());
        System.out.println("background: " + got);
        check("the background is fitted around the ground", worst < 0.01f * grey * BG, got);
    }

    /** The plain sky: next to no detail. */
    private static void plain(Sky s, float[] dd) {
        float[] v = new float[W * H];
        int n = 0;
        for (int y = 30; y < 350; y++) for (int x = 40; x < 700; x++) v[n++] = Math.abs(dd[y * W + x]);
        float p99 = pct(v, n, 0.99f), max = pct(v, n, 1f);
        String got = String.format(Locale.US, "|detail| 99th percentile %.4f, most %.4f (the band's %.1f)", p99, max, BAND);
        System.out.println("plain sky: " + got);
        check("the plain sky gets next to none", p99 < 0.01f * BAND && max < 0.03f * BAND, got);
    }

    /** The bright star's glow: not brought out, no dark ring. */
    private static void glow(Sky s, float[] dd) {
        float glowPeak = s.glowPeak;
        float in = 0, ring = 0;
        double ringSum = 0;
        int ringN = 0;
        for (int y = (int) (GY - 160); y <= GY + 160; y++) {
            for (int x = (int) (GX - 160); x <= GX + 160; x++) {
                float r = (float) Math.hypot(x - GX, y - GY);
                float d = dd[y * W + x];
                if (r < 3 * G_SIGMA) in = Math.max(in, Math.abs(d));
                else if (r < 150) {
                    ring = Math.min(ring, d);
                    ringSum += d;
                    ringN++;
                }
            }
        }
        String got = String.format(Locale.US, "glow %.2f at its peak: |detail| in it up to %.3f, around it %.3f on average, %.3f at the least",
                glowPeak, in, ringSum / ringN, ring);
        System.out.println("glow: " + got);
        check("a bright star's glow isn't brought out", in < 0.1f * glowPeak, got);
        check("no dark ring around it", ringSum / ringN > -0.02f * glowPeak && ring > -0.1f * glowPeak, got);
    }

    /** The Moon's glow: no dark ring around it. */
    private static void moon(Sky s, float[] dd) {
        float least = 0, most = 0;
        double[] sum = new double[20];
        int[] cnt = new int[20];
        for (int y = (int) (MY - 200); y <= MY + 200; y++) {
            for (int x = (int) Math.max(0, MX - 200); x <= MX + 200; x++) {
                float r = (float) Math.hypot(x - MX, y - MY);
                if (r < M_R + 4 || r >= 200 || s.ground(x, y)) continue;
                float d = dd[y * W + x];
                least = Math.min(least, d);
                most = Math.max(most, d);
                sum[(int) (r / 10)] += d;
                cnt[(int) (r / 10)]++;
            }
        }
        double worst = 0;
        for (int k = 0; k < 20; k++) if (cnt[k] > 0) worst = Math.min(worst, sum[k] / cnt[k]);
        String got = String.format(Locale.US, "detail around it %.3f to %.3f, darkest 10 px ring %.3f on average (sky %.0f)", least, most,
                worst, BG);
        System.out.println("moon: " + got);
        check("no dark ring around the Moon", worst > -0.01f * BG && least > -0.03f * BG, got);
        check("its glow isn't brought out", most < 0.03f * BG, got);
    }

    /** The ground and the skyline: nothing. */
    private static void ground(Sky s, float[] dd) {
        float on = 0, along = 0;
        int ax = 0, ay = 0;
        for (int y = 1300; y < H; y++) {
            for (int x = 0; x < W; x++) {
                float d = Math.abs(dd[y * W + x]);
                float sky = s.skyline(x);
                if (y >= sky) on = Math.max(on, d);
                else if (y > sky - 40 && !s.nearBright(x, y) && d > along) {
                    along = d;
                    ax = x;
                    ay = y;
                }
            }
        }
        String got = String.format(Locale.US, "|detail| on the ground up to %.4f, along the skyline up to %.4f (at %d,%d)", on, along, ax, ay);
        System.out.println("ground: " + got);
        check("nothing on the ground", on < 0.02f * BAND, got);
        check("no rim along the skyline", along < 0.05f * BAND, got);
    }

    /** Other tiles: the same detail, bit for bit. */
    private static void tiles(RgbImage img, Renderer.Analysis a, Denoise.Result r) {
        Denoise.Result r2 = Denoise.run(img, a, 1f, 2 * Denoise.TILE);
        boolean same = r2 != null && r2.dd != null && java.util.Arrays.equals(r.dd, r2.dd) && r2.haloes == r.haloes;
        check("the same detail in tiles of " + 2 * Denoise.TILE + " px", same, r2 == null ? "null" : r2.describe());
    }

    /** A half-size copy: the full-size detail binned as the copy is; found in the copy itself, much the same. */
    private static void half(RgbImage img, Renderer.Analysis a, Denoise.Result r) {
        RgbImage small = img.binned(2);
        int w = small.width, h = small.height;
        Denoise.Result b = Denoise.binned(r, 2, w, h);
        float worst = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float m = 0.25f * (r.detailAt(2 * x, 2 * y) + r.detailAt(2 * x + 1, 2 * y) + r.detailAt(2 * x, 2 * y + 1)
                        + r.detailAt(2 * x + 1, 2 * y + 1));
                worst = Math.max(worst, Math.abs(b.detailAt(x, y) - m));
            }
        }
        check("the full-size detail binned for the preview", worst < 0.002f * BAND, String.format(Locale.US, "off by %.5f at most", worst));
        Denoise.Result c = Renderer.smallNoise(small, a, 2);
        check("found in the copy too", c != null && c.dd != null, c == null ? "null" : c.describe());
        if (c == null || c.dd == null) return;
        System.out.println("half size: " + c.describe());
        double sxy = 0, sxx = 0, syy = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double p = b.detailAt(x, y), q = c.detailAt(x, y);
                sxy += p * q;
                sxx += p * p;
                syy += q * q;
            }
        }
        double corr = sxy / Math.sqrt(sxx * syy), ratio = Math.sqrt(syy / sxx);
        String got = String.format(Locale.US, "correlation %.3f, %.2f times as strong", corr, ratio);
        System.out.println("half size against full size: " + got);
        check("the copy's own detail is much the full-size one's", corr > 0.85 && ratio > 0.7 && ratio < 1.3, got);
    }

    /** Renders at 0, the default and twice it: none of it, some, more; the plain sky the same at all three. */
    private static void renders(RgbImage img, Renderer.Analysis a, Sky s, java.io.File out) throws Exception {
        int[] p0 = render(img, a, 0f), p1 = render(img, a, Renderer.Params.DETAIL), p2 = render(img, a, 2 * Renderer.Params.DETAIL);
        int w = a.cw, h = a.ch;
        double band1 = 0, band2 = 0;
        int nb = 0, plainOff = 0, np = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x, sx = x + a.cx0, sy = y + a.cy0;
                if (s.inBand(sx, sy) && !s.nearBright(sx, sy)) {
                    band1 += diff(p0[i], p1[i]);
                    band2 += diff(p0[i], p2[i]);
                    nb++;
                }
                if (sx >= 40 && sx < 700 && sy >= 30 && sy < 350) {
                    if (diff(p0[i], p1[i]) > 1) plainOff++;
                    np++;
                }
            }
        }
        String got = String.format(Locale.US, "the band's pixels move %.2f levels at %.1f, %.2f at %.1f; %d of %d plain sky pixels more than a level",
                band1 / nb, Renderer.Params.DETAIL, band2 / nb, 2 * Renderer.Params.DETAIL, plainOff, np);
        System.out.println("renders: " + got);
        check("the setting brings it out, more at twice it", band1 / nb > 1 && band2 > 1.6 * band1, got);
        check("the plain sky looks the same", plainOff < np / 1000, got);
        // the plain sky as even near the ground as up high
        float[] top = new float[w * h], low = new float[w * h];
        int nt = 0, nl = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = x + a.cx0, sy = y + a.cy0;
                if (s.nearBright(sx, sy)) continue;
                float g = grey(p0[y * w + x]);
                if (sy >= 30 && sy < 350) top[nt++] = g;
                else if (sy >= 1300 && sy < s.skyline(sx) - 40) low[nl++] = g;
            }
        }
        float mt = pct(top, nt, 0.5f), ml = pct(low, nl, 0.5f);
        String even = String.format(Locale.US, "the plain sky's middle grey %.1f up high, %.1f just over the ground", mt, ml);
        System.out.println("renders: " + even);
        check("the plain sky is even down to the ground", Math.abs(mt - ml) < 3, even);
        if (out != null) {
            out.mkdirs();
            SimRunner.writePng(p0, w, h, new java.io.File(out, "detail_0.png"));
            SimRunner.writePng(p1, w, h, new java.io.File(out, "detail_" + Renderer.Params.DETAIL + ".png"));
            SimRunner.writePng(p2, w, h, new java.io.File(out, "detail_" + 2 * Renderer.Params.DETAIL + ".png"));
        }
    }

    /** The edit's setting: kept in the recipe, read back, the renderer's amount from it, and its note. */
    private static void recipe() {
        Recipe r = new Recipe();
        Recipe.Part part = new Recipe.Part();
        part.session = "session_20261104_210000";
        r.parts.add(part);
        check("a recipe starts as developed", r.detail == 1f && r.params().detail == Renderer.Params.DETAIL, "detail " + r.detail);
        Recipe e = r.edit();
        e.detail = 0.5f;
        Recipe back = Recipe.fromJson(e.toJson());
        check("the setting is kept", back.detail == 0.5f && Math.abs(back.params().detail - 0.5f * Renderer.Params.DETAIL) < 1e-6f,
                "read back " + back.detail);
        check("and noted", e.changes(r).contains("sky detail 50%"), e.changes(r));
        String old = r.toJson().replace(",\"detail\":1", "");
        check("a recipe from before has it as developed", !old.contains("\"detail\"") && Recipe.fromJson(old).detail == 1f, old);
    }

    private static int[] render(RgbImage img, Renderer.Analysis a, float detail) {
        Renderer.Params p = new Renderer.Params();
        p.detail = detail;
        final int[] px = new int[a.cw * a.ch];
        Renderer.render(img, a, p, new Renderer.RowSink() {
            @Override
            public void rows(int y, int count, int[] argb, int width) {
                System.arraycopy(argb, 0, px, y * width, count * width);
            }
        }, null);
        return px;
    }

    /** The largest of the three colours' differences, in 8-bit levels. */
    private static int diff(int a, int b) {
        int d = 0;
        for (int s = 0; s < 24; s += 8) d = Math.max(d, Math.abs(((a >> s) & 255) - ((b >> s) & 255)));
        return d;
    }

    static final class Sky {
        /** Background and the field stars; the band; the glow and the Moon; the noise. */
        final float[] sky = new float[W * H], band = new float[W * H], bright = new float[W * H], noise = new float[W * H];
        float glowPeak;

        Sky(long seed) {
            Random rnd = new Random(seed);
            float[] stars = new float[W * H];
            for (int k = 0; k < 12000; k++) {
                float x = rnd.nextFloat() * W, y = rnd.nextFloat() * H;
                // none within the bright things' reach
                if (Math.hypot(x - GX, y - GY) < 60 || Math.hypot(x - MX, y - MY) < 60) continue;
                double u = Math.max(1e-6, rnd.nextDouble());
                stamp(stars, x, y, (float) Math.min(1500, 0.4 * NOISE * Math.pow(u, -1.4)), PSF);
            }
            // the band: star clouds and a dark lane along it
            float[][] clouds = new float[14][];
            for (int k = 0; k < clouds.length; k++) {
                float cx = 80 + rnd.nextFloat() * (W - 160);
                float cy = BAND_Y + BAND_TILT * (cx - W / 2f) + (rnd.nextFloat() - 0.5f) * BAND_W;
                clouds[k] = new float[] {cx, cy, 40 + rnd.nextFloat() * 70, 0.3f + 0.5f * rnd.nextFloat()};
            }
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    float d = y - (BAND_Y + BAND_TILT * (x - W / 2f));
                    double b = Math.exp(-d * d / (2 * BAND_W * BAND_W));
                    double c = 1;
                    for (float[] cl : clouds) {
                        double r2 = (x - cl[0]) * (x - cl[0]) + (y - cl[1]) * (y - cl[1]);
                        c += cl[3] * Math.exp(-r2 / (2 * cl[2] * cl[2]));
                    }
                    double lane = 1 - 0.75 * Math.exp(-(d + 25) * (d + 25) / (2 * 22.0 * 22.0)) * (0.7 + 0.3 * Math.sin(x / 140.0));
                    band[y * W + x] = (float) (BAND * b * c * lane);
                }
            }
            // the bright star, a share of its light in a glow
            stamp(bright, GX, GY, G_PEAK * (1 - G_SHARE), PSF);
            float flux = (float) (G_PEAK * 2 * Math.PI * PSF * PSF);
            glowPeak = (float) (G_SHARE * flux / (2 * Math.PI * G_SIGMA * G_SIGMA));
            stamp(bright, GX, GY, glowPeak, G_SIGMA);
            // the Moon and its glow
            for (int y = (int) (MY - 400); y <= MY + 400; y++) {
                for (int x = (int) Math.max(0, MX - 400); x <= Math.min(W - 1, MX + 400); x++) {
                    if (y < 0 || y >= H) continue;
                    double r = Math.hypot(x - MX, y - MY);
                    double v = r <= M_R ? M_LEVEL : M_GLOW / Math.pow(1 + ((r - M_R) / M_FALL) * ((r - M_R) / M_FALL), 1.5);
                    bright[y * W + x] += (float) v;
                }
            }
            for (int i = 0; i < W * H; i++) {
                int x = i % W, y = i / W;
                boolean g = ground(x, y);
                sky[i] = g ? GROUND : BG + 0.004f * x + 0.003f * y + stars[i];
                if (g) {
                    band[i] = 0;
                    bright[i] = 0;
                }
                noise[i] = (float) rnd.nextGaussian() * NOISE;
            }
        }

        RgbImage image() {
            RgbImage im = new RgbImage(W, H);
            im.clip = new byte[W * H];
            for (int i = 0; i < W * H; i++) {
                float v = sky[i] + band[i] + bright[i] + noise[i];
                if (sky[i] + band[i] + bright[i] >= WHITE) {
                    v = WHITE;
                    im.clip[i] = (byte) 255;
                }
                im.r[i] = v;
                im.g[i] = v;
                im.b[i] = v;
            }
            return im;
        }

        float skyline(int x) {
            return GROUND_Y + 30f * (float) Math.sin(x / 120.0);
        }

        boolean ground(int x, int y) {
            return y >= skyline(x);
        }

        /** Within two band widths of the band's middle, on the sky. */
        boolean inBand(int x, int y) {
            float d = y - (BAND_Y + BAND_TILT * (x - W / 2f));
            return Math.abs(d) < 2 * BAND_W && !ground(x, y) && y < skyline(x) - 60;
        }

        /** Within reach of the bright star's glow or the Moon's. */
        boolean nearBright(int x, int y) {
            return Math.hypot(x - GX, y - GY) < 200 || Math.hypot(x - MX, y - MY) < 250;
        }

        static void stamp(float[] into, float px, float py, float peak, float sg) {
            int r = (int) Math.ceil(5 * sg);
            for (int y = (int) py - r; y <= (int) py + r; y++) {
                if (y < 0 || y >= H) continue;
                for (int x = (int) px - r; x <= (int) px + r; x++) {
                    if (x < 0 || x >= W) continue;
                    float dx = x - px, dy = y - py;
                    into[y * W + x] += peak * (float) Math.exp(-(dx * dx + dy * dy) / (2 * sg * sg));
                }
            }
        }
    }

    static float[] blur(float[] s, int w, int h, float sg) {
        int r = (int) Math.ceil(3 * sg);
        float[] k = new float[2 * r + 1];
        float sum = 0;
        for (int i = -r; i <= r; i++) sum += k[i + r] = (float) Math.exp(-i * i / (2.0 * sg * sg));
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        float[] t = new float[w * h], o = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float acc = 0;
                for (int i = -r; i <= r; i++) acc += k[i + r] * s[y * w + Math.max(0, Math.min(w - 1, x + i))];
                t[y * w + x] = acc;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float acc = 0;
                for (int i = -r; i <= r; i++) acc += k[i + r] * t[Math.max(0, Math.min(h - 1, y + i)) * w + x];
                o[y * w + x] = acc;
            }
        }
        return o;
    }

    private static float grey(int argb) {
        return 0.2126f * (argb >> 16 & 0xFF) + 0.7152f * (argb >> 8 & 0xFF) + 0.0722f * (argb & 0xFF);
    }

    private static float pct(float[] v, int n, float p) {
        float[] c = java.util.Arrays.copyOf(v, n);
        java.util.Arrays.sort(c);
        return c[Math.min(n - 1, (int) (p * (n - 1)))];
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : ": " + detail));
        if (!ok) failures++;
    }
}
