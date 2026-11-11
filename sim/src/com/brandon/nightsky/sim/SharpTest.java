package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Denoise;
import com.brandon.nightsky.processing.Half;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.Recipe;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Sharper stars (2.0, {@link Denoise.Result#ds}) on made-up skies whose stars are known:
 * <ul>
 * <li>a star image's size is measured as made, for small and large star images;</li>
 * <li>bright stars get narrower and higher and keep their light, star by star; nothing goes under the sky;</li>
 * <li>stars near the clip level get less of it, and the sky between the stars, the faintest stars, stars at most of
 * the clip level, clipped stars and stars marked as clipped in some frames are left exactly as they were;</li>
 * <li>star images too small to gain, too few stars to measure and star trails are left alone;</li>
 * <li>the same result, bit for bit, in tiles of other sizes, and binned for the preview as the picture is;</li>
 * <li>the render uses it (brighter star middles, the same photo twice, none of it at 0, with the noise left in too),
 * and a recipe keeps the setting.</li>
 * </ul>
 * Usage: SharpTest [out-dir for two pictures to look at]
 */
public final class SharpTest {
    private SharpTest() {}

    private static int failures;
    static final int W = 1280, H = 960;
    static final float SKY = 100f, NOISE = 2f;

    /** A made-up sky: x, y, peak of each star by kind. */
    static final class Field {
        final float sigma;
        final float[] v = new float[W * H];
        final byte[] clip = new byte[W * H];
        final List<float[]> bright = new ArrayList<float[]>(), faint = new ArrayList<float[]>(), clipped = new ArrayList<float[]>(),
                marked = new ArrayList<float[]>(), half = new ArrayList<float[]>(), big = new ArrayList<float[]>();
        /** Each pixel's distance to the nearest star of any kind, capped at 99. */
        final float[] near = new float[W * H];

        /**
         * {@code nBright} bright stars of this size (15 to 100 times the noise at their peaks: well under the clip level),
         * each in its own cell; with {@code others}, among them stars at the noise's level, stars whose peaks stand at
         * half the clip level and at three quarters of it and more, clipped stars and stars marked as clipped in some
         * frames.
         */
        Field(float sigma, long seed, int nBright, boolean others, float clipLevel) {
            this.sigma = sigma;
            Random r = new Random(seed);
            Arrays.fill(near, 99f);
            int cell = 28, gw = W / cell, gh = H / cell;
            List<Integer> cells = new ArrayList<Integer>();
            for (int k = 0; k < gw * gh; k++) {
                int cx = k % gw, cy = k / gw;
                if (cx > 0 && cy > 0 && cx < gw - 1 && cy < gh - 1) cells.add(k);
            }
            java.util.Collections.shuffle(cells, r);
            int at = 0;
            for (int k = 0; k < nBright && at < cells.size(); k++, at++) {
                float[] s = place(cells.get(at), cell, gw, r);
                s[2] = (float) (15 * NOISE * Math.pow(100 / 15.0, r.nextDouble()));
                bright.add(s);
            }
            if (others) {
                for (int k = 0; k < 120 && at < cells.size(); k++, at++) {
                    float[] s = place(cells.get(at), cell, gw, r);
                    s[2] = NOISE * (0.8f + 1.4f * r.nextFloat());
                    faint.add(s);
                }
                for (int k = 0; k < 10 && at < cells.size(); k++, at++) {
                    float[] s = place(cells.get(at), cell, gw, r);
                    s[2] = clipLevel * (1.5f + 3f * r.nextFloat());
                    clipped.add(s);
                }
                for (int k = 0; k < 10 && at < cells.size(); k++, at++) {
                    float[] s = place(cells.get(at), cell, gw, r);
                    s[2] = 60 * NOISE;
                    marked.add(s);
                }
                for (int k = 0; k < 24 && at < cells.size(); k++, at++) {
                    float[] s = place(cells.get(at), cell, gw, r);
                    s[2] = clipLevel * (0.47f + 0.04f * r.nextFloat()) - SKY;
                    half.add(s);
                }
                for (int k = 0; k < 10 && at < cells.size(); k++, at++) {
                    float[] s = place(cells.get(at), cell, gw, r);
                    s[2] = clipLevel * (0.72f + 0.12f * r.nextFloat()) - SKY;
                    big.add(s);
                }
            }
            for (int i = 0; i < W * H; i++) v[i] = SKY + NOISE * (float) r.nextGaussian();
            for (float[] s : bright) spot(s);
            for (float[] s : faint) spot(s);
            for (float[] s : marked) {
                spot(s);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) clip[((int) s[1] + dy) * W + (int) s[0] + dx] = 30;
            }
            for (float[] s : half) spot(s);
            for (float[] s : big) spot(s);
            for (float[] s : clipped) spot(s);
            for (int i = 0; i < W * H; i++) if (v[i] > clipLevel) v[i] = clipLevel;
        }

        private float[] place(int k, int cell, int gw, Random r) {
            return new float[]{(k % gw) * cell + 8 + 12 * r.nextFloat(), (k / gw) * cell + 8 + 12 * r.nextFloat(), 0f};
        }

        /** A round star, each pixel's share of its light; its peak is what a star at a pixel's middle would show. */
        private void spot(float[] s) {
            double inv = 1 / (Math.sqrt(2) * sigma);
            double mid = SkySim.erf(0.5 * inv), flux = s[2] / (mid * mid);
            int rad = (int) Math.ceil(5 * sigma) + 1, x0 = (int) Math.floor(s[0]) - rad, y0 = (int) Math.floor(s[1]) - rad, m = 2 * rad + 1;
            double[] fx = new double[m], fy = new double[m];
            for (int q = 0; q < m; q++) {
                fx[q] = 0.5 * (SkySim.erf((x0 + q + 1 - s[0]) * inv) - SkySim.erf((x0 + q - s[0]) * inv));
                fy[q] = 0.5 * (SkySim.erf((y0 + q + 1 - s[1]) * inv) - SkySim.erf((y0 + q - s[1]) * inv));
            }
            for (int j = 0; j < m; j++) {
                for (int i = 0; i < m; i++) v[(y0 + j) * W + x0 + i] += (float) (flux * fx[i] * fy[j]);
            }
            for (int dy = -12; dy <= 12; dy++) {
                for (int dx = -12; dx <= 12; dx++) {
                    int x = (int) s[0] + dx, y = (int) s[1] + dy;
                    if (x < 0 || y < 0 || x >= W || y >= H) continue;
                    float d = (float) Math.hypot(x + 0.5 - s[0], y + 0.5 - s[1]);
                    if (d < near[y * W + x]) near[y * W + x] = d;
                }
            }
        }

        RgbImage image() {
            RgbImage im = new RgbImage(W, H);
            for (int i = 0; i < W * H; i++) {
                im.r[i] = v[i];
                im.g[i] = v[i];
                im.b[i] = v[i];
            }
            im.clip = clip;
            return im;
        }
    }

    public static void main(String[] args) throws Exception {
        SensorSpec spec = new SensorSpec();
        float clipLevel = spec.range();

        // 1) a star image's size, as made (a pixel's own width adds a little: 1/12 px^2)
        for (float sg : new float[]{0.9f, 1.2f, 1.6f, 2.2f}) {
            Field f = new Field(sg, 3, 500, false, clipLevel);
            RgbImage im = f.image();
            Denoise.Result r = Denoise.run(im, Master.analyze(im, spec, false), 1f);
            float want = (float) Math.sqrt(sg * sg + 1 / 12.0);
            check(String.format(Locale.US, "star images made %.2f px (sigma) are measured as that", want),
                    r != null && Math.abs(r.starSigma / want - 1) < 0.07f, r == null ? "nothing" : r.sharpNote());
        }

        // 2) the main field
        Field f = new Field(1.25f, 7, 520, true, clipLevel);
        RgbImage im = f.image();
        Renderer.Analysis a = Master.analyze(im, spec, false);
        Denoise.Result r = Denoise.run(im, a, 1f);
        check("a starry sky gets its stars' sharpening", r != null && r.ds != null, r == null ? "nothing" : r.sharpNote());
        if (r == null || r.ds == null) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("found: " + r.sharpNote() + " (" + r.msSharp + " ms of " + r.ms + ")");
        // the luminance of a pixel whose colours are all v
        float lum = a.whiteY / a.clip;
        float[] d = new float[W * H];
        for (int i = 0; i < W * H; i++) d[i] = Half.toFloat(r.ds[i]) / lum;

        // bright stars: narrower, higher, the same light
        float[] m = shape(f, f.bright, d);
        String got = String.format(Locale.US, "%d bright stars: their middles %.2f times as high, %.0f%% as wide, %.4f of their light "
                + "(the worst star %.4f off)", f.bright.size(), m[0], 100 * m[1], m[2], m[3]);
        System.out.println(got);
        check("bright stars' middles stand higher (1.25 times or more) and the stars are narrower (92% or less)", m[0] >= 1.25f && m[1] <= 0.92f, got);
        check("each bright star keeps its light (within 0.5%)", Math.abs(m[2] - 1) < 0.002f && m[3] < 0.005f, got);
        // what a photo gets as developed: the develop step's share of it
        float[] dev = new float[W * H];
        for (int i = 0; i < W * H; i++) dev[i] = Renderer.Params.SHARP * d[i];
        float[] md = shape(f, f.bright, dev);
        check("as developed a photo gets part of that", md[0] > 1.15f && md[0] < m[0] && md[1] < 0.97f && md[1] > m[1], String.format(Locale.US,
                "%.0f%% of it: their middles %.2f times as high, %.0f%% as wide", 100 * Renderer.Params.SHARP, md[0], 100 * md[1]));
        float[] mh = shape(f, f.half, d);
        check("stars whose peaks stand at half the clip level get less of it, and keep their light", mh[0] > 1.03f && mh[0] < 1 + 0.8f * (m[0] - 1)
                && Math.abs(mh[2] - 1) < 0.002f && mh[3] < 0.005f, String.format(Locale.US, "their middles %.2f times as high, %.0f%% as wide, "
                + "%.4f of their light (the worst star %.4f off)", mh[0], 100 * mh[1], mh[2], mh[3]));

        // nothing under the sky, and the sky between the stars as it was
        int under = 0, skyPx = 0, skyMoved = 0;
        float skyMost = 0;
        for (int i = 0; i < W * H; i++) {
            if (d[i] != 0 && f.v[i] + d[i] < SKY - 4 * NOISE) under++;
            if (f.near[i] > 11) {
                skyPx++;
                if (r.ds[i] != 0 && r.ds[i] != (short) 0x8000) {
                    skyMoved++;
                    skyMost = Math.max(skyMost, Math.abs(d[i]));
                }
            }
        }
        check("no pixel is taken under the sky", under == 0, under + " pixels");
        check("the sky between the stars is left as it was (under 0.05% of it touched, by under the noise)",
                skyMoved < 0.0005f * skyPx && skyMost < NOISE, String.format(Locale.US, "%d of %d pixels, the most %.2f", skyMoved, skyPx, skyMost));

        // faint stars, clipped stars and stars clipped in some frames: as they were
        check("the faintest stars (1 to 2 times the noise) are left as they were", touched(f.faint, r, 3) <= f.faint.size() / 20,
                touched(f.faint, r, 3) + " of " + f.faint.size() + " touched");
        check("stars at three quarters of the clip level and more are left as they were", touched(f.big, r, 7) == 0,
                touched(f.big, r, 7) + " of " + f.big.size());
        check("clipped stars are left as they were", touched(f.clipped, r, 7) == 0, touched(f.clipped, r, 7) + " of " + f.clipped.size());
        check("so are stars clipped in some of the frames", touched(f.marked, r, 7) == 0, touched(f.marked, r, 7) + " of " + f.marked.size());

        // 3) left alone: small star images, too few stars, star trails
        Field small = new Field(0.6f, 5, 500, false, clipLevel);
        RgbImage ims = small.image();
        Denoise.Result rs = Denoise.run(ims, Master.analyze(ims, spec, false), 1f);
        check("star images too small to gain are left alone", rs != null && rs.ds == null && rs.starSigma < Denoise_SHARP_MIN,
                rs == null ? "nothing" : rs.sharpNote());
        Field few = new Field(1.25f, 6, 12, false, clipLevel);
        RgbImage imf = few.image();
        Denoise.Result rw = Denoise.run(imf, Master.analyze(imf, spec, false), 1f);
        check("a dozen stars are too few to go by", rw != null && rw.ds == null && rw.starSigma != rw.starSigma,
                rw == null ? "nothing" : rw.sharpNote());
        Renderer.Analysis at = Master.analyze(im, spec, true);
        Denoise.Result rt = Denoise.run(im, at, 1f);
        check("star trails are left alone", rt != null && rt.ds == null, rt == null ? "nothing" : rt.sharpNote());

        // 4) the same in tiles of other sizes, and binned as the picture is
        for (int t : new int[]{128, 384, 512}) {
            Denoise.Result o = Denoise.run(im, a, 1f, t);
            int diff = 0;
            for (int i = 0; i < r.ds.length; i++) if (o.ds[i] != r.ds[i]) diff++;
            int diffY = 0;
            for (int i = 0; i < r.dy.length; i++) if (o.dy[i] != r.dy[i]) diffY++;
            check("tiles of " + t + " px give the same sharpening and the same noise correction", diff == 0 && diffY == 0
                    && o.starSigma == r.starSigma, diff + " and " + diffY + " pixels differ");
        }
        Denoise.Result b2 = Denoise.binned(r, 2, W / 2, H / 2);
        int off = 0;
        for (int y = 0; y < H / 2; y++) {
            for (int x = 0; x < W / 2; x++) {
                float sum = Half.toFloat(r.ds[2 * y * W + 2 * x]) + Half.toFloat(r.ds[2 * y * W + 2 * x + 1])
                        + Half.toFloat(r.ds[(2 * y + 1) * W + 2 * x]) + Half.toFloat(r.ds[(2 * y + 1) * W + 2 * x + 1]);
                if (b2.ds[y * (W / 2) + x] != Half.fromFloat(sum * 0.25f)) off++;
            }
        }
        check("the preview's copy gets the photo's sharpening, binned as the picture is", b2.ds != null && off == 0, off + " pixels differ");

        // 5) renders
        Renderer.Params on = new Renderer.Params(), none = new Renderer.Params(), raw = new Renderer.Params(), rawOn = new Renderer.Params();
        none.sharp = 0;
        raw.sharp = 0;
        raw.lumaNr = 0;
        rawOn.lumaNr = 0;
        int[] p1 = Master.render(im, a, on), p2 = Master.render(im, a, on), p0 = Master.render(im, a, none);
        int[] q0 = Master.render(im, a, raw), q1 = Master.render(im, a, rawOn);
        check("a render with it comes out the same twice", Arrays.equals(p1, p2), "");
        int higher = 0, lower = 0, seen = 0;
        for (float[] s : f.bright) {
            if (s[2] > 50 * NOISE) continue; // brighter ones are white either way
            int g1 = 0, g0 = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int i = ((int) s[1] + dy) * W + (int) s[0] + dx;
                    g1 = Math.max(g1, (p1[i] >> 8) & 0xFF);
                    g0 = Math.max(g0, (p0[i] >> 8) & 0xFF);
                }
            }
            seen++;
            if (g1 > g0) higher++;
            if (g1 < g0) lower++;
        }
        check("in the photo the stars' middles are brighter with it", seen > 20 && higher > 0.9f * seen && lower == 0,
                higher + " of " + seen + " brighter, " + lower + " darker");
        int skyDiff = 0, starDiff = 0, rawSky = 0;
        for (int i = 0; i < W * H; i++) {
            if (f.near[i] > 11) {
                if (p1[i] != p0[i]) skyDiff++;
                if (q1[i] != q0[i]) rawSky++;
            } else if (q1[i] != q0[i]) {
                starDiff++;
            }
        }
        check("and the sky between them is the same photo, pixel for pixel (but the few places counted above)",
                skyDiff <= 2 * skyMoved + 8 * 8 && rawSky <= 2 * skyMoved + 8 * 8, skyDiff + " and " + rawSky + " pixels differ");
        check("with the noise left in, the stars are still sharpened", starDiff > 1000, starDiff + " star pixels differ");

        // 6) the recipe keeps it
        Recipe rc = Recipe.single("night", false);
        String plain = rc.toJson();
        rc.sharp = 0.5f;
        Recipe back = Recipe.fromJson(rc.toJson()), was = Recipe.fromJson(plain);
        Recipe trails = Recipe.single("night", true);
        String rec = String.format(Locale.US, "%.2f back, %.3f in the render; unsaid when as developed: %s; changes: %s; trails %.1f", back.sharp,
                back.params().sharp, !plain.contains("\"sharp\""), back.changes(was), trails.params().sharp);
        check("a recipe keeps the star sharpness, and star trails get none", back.sharp == 0.5f
                && Math.abs(back.params().sharp - 0.5f * Renderer.Params.SHARP) < 1e-6f && !plain.contains("\"sharp\"") && was.sharp == 1f
                && back.changes(was).equals("star sharpness 50%") && trails.params().sharp == 0f, rec);

        // two pictures to look at: a corner of the photo without and with
        if (args.length > 0) {
            File dir = new File(args[0]);
            dir.mkdirs();
            save(p0, 40, 40, 320, 240, 3, new File(dir, "stars_as_drawn.png"));
            save(p1, 40, 40, 320, 240, 3, new File(dir, "stars_sharper.png"));
            System.out.println("pictures in " + dir);
        }
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    private static final float Denoise_SHARP_MIN = 0.85f;

    /**
     * What the changes d do to these stars: {the median of how much higher their highest pixel stands over the sky, of
     * how wide they are (the light's second moment within 5 px, as a width) against before, of their light within 9 px
     * against before, and the farthest any star's light is from what it was}.
     */
    private static float[] shape(Field f, List<float[]> stars, float[] d) {
        int n = 0;
        float[] peakUp = new float[stars.size()], narrower = new float[stars.size()], kept = new float[stars.size()];
        float worstKept = 0;
        for (float[] s : stars) {
            int cx = (int) s[0], cy = (int) s[1];
            double f0 = 0, f1 = 0, m0 = 0, m1 = 0, w0 = 0, w1 = 0, p0 = 0, p1 = 0;
            for (int dy = -9; dy <= 9; dy++) {
                for (int dx = -9; dx <= 9; dx++) {
                    int i = (cy + dy) * W + cx + dx;
                    double b = f.v[i] - SKY, c = b + d[i];
                    f0 += b;
                    f1 += c;
                    double r2 = sq(cx + dx + 0.5 - s[0]) + sq(cy + dy + 0.5 - s[1]);
                    if (r2 <= 25) {
                        m0 += b * r2;
                        w0 += b;
                        m1 += c * r2;
                        w1 += c;
                    }
                    p0 = Math.max(p0, b);
                    p1 = Math.max(p1, c);
                }
            }
            kept[n] = (float) (f1 / f0);
            worstKept = Math.max(worstKept, Math.abs(kept[n] - 1));
            peakUp[n] = (float) (p1 / p0);
            narrower[n] = (float) Math.sqrt((m1 / w1) / (m0 / w0));
            n++;
        }
        return new float[]{median(peakUp, n), median(narrower, n), median(kept, n), worstKept};
    }

    /** How many of the stars have a changed pixel within rad px of their middle. */
    private static int touched(List<float[]> stars, Denoise.Result r, int rad) {
        int n = 0;
        for (float[] s : stars) {
            boolean any = false;
            for (int dy = -rad; dy <= rad && !any; dy++) {
                for (int dx = -rad; dx <= rad; dx++) {
                    short h = r.ds[((int) s[1] + dy) * W + (int) s[0] + dx];
                    if (h != 0 && h != (short) 0x8000) {
                        any = true;
                        break;
                    }
                }
            }
            if (any) n++;
        }
        return n;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static float median(float[] v, int n) {
        float[] c = Arrays.copyOf(v, n);
        Arrays.sort(c);
        return n == 0 ? Float.NaN : c[n / 2];
    }

    /** A w x h part of a W-wide picture from (x0, y0), each pixel z x z. */
    private static void save(int[] px, int x0, int y0, int w, int h, int z, File out) throws Exception {
        BufferedImage bi = new BufferedImage(w * z, h * z, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h * z; y++) for (int x = 0; x < w * z; x++) bi.setRGB(x, y, px[(y0 + y / z) * W + x0 + x / z]);
        javax.imageio.ImageIO.write(bi, "png", out);
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
        if (!ok) failures++;
    }
}
