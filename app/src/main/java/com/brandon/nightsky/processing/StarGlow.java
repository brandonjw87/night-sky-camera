package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Star glow (1.10): a soft glow around the brightest stars, as a soft-focus filter spreads a little of each light around
 * it. Stacked, the bright stars come out hardly bigger than the thousands of faint ones, and the constellations' shapes
 * are lost among them; with the glow the brightest stand out in their own colours, a bigger glow the brighter the star,
 * as the eye picks them out of the sky.
 * <p>
 * The lights are found in the picture itself, once for each picture rendered: peaks standing {@link #DETECT} times the
 * noise over their surroundings in the picture binned to about {@link #BIN} x {@link #BIN} full-size pixels, then
 * measured at the picture's own resolution: the light in a disc of {@link #APERTURE} full-size pixels less the median
 * of a ring outside it, centred on that light, with its colour from the disc's unclipped pixels. A light that isn't a
 * point (a streak, or a wide glow such as the Moon or a lamp, whose ring is bright too) doesn't glow. The {@link #MOST}
 * brightest are kept.
 * <p>
 * The brightest stars clip, so the photo holds only part of their light and none of their colour: Vega measures hardly
 * brighter than a star of the third magnitude. In a plate-solved photo the catalogue's stars and the planets are placed
 * in the picture ({@link #sky}), and a clipped light where one of them is ({@link #MATCH}) takes its brightness from its
 * magnitude, by the zero point of the unclipped stars there, and its colour from its colour index. A star the photo
 * doesn't show (behind a tree, or a cloud) has no light there and doesn't glow.
 * <p>
 * Each glows {@link #SIZE} of the picture's longer side wide, wider as the fourth root of its light over the light of
 * the {@link #REF}th brightest, as a soft core with wide wings ({@link #WIDTHS}) that carries {@link #SHARE} of its light
 * at 100%, in a paler share of its colour ({@link #CHROMA}); lights {@link #FADE} times fainter than that one fade out of
 * it. The glow is added to the light before the stretch, so it takes the photo's tone curve and colour like the rest,
 * and a picture scaled down gets the same glow scaled down.
 */
public final class StarGlow {
    /** The most lights that glow. */
    static final int MOST = 150;
    /** The binning (in full-size pixels) the lights are found in, and how far over the noise their peak must stand. */
    static final int BIN = 4;
    static final float DETECT = 25f;
    /** The disc a light is measured in (full-size pixels), and the ring outside it its surroundings come from. */
    static final float APERTURE = 5f, RING_IN = 7f, RING_OUT = 11f;
    /**
     * A light whose ring holds over WIDE of its peak is a wide glow, not a point; so is one with over CLIPPED_RING of its
     * ring clipped (a clipped disc, as the Moon's: around a point on its limb the sky is dark enough).
     */
    static final float WIDE = 0.25f, CLIPPED_RING = 0.1f;
    /** A light this much longer than wide (its second moments' square root) is a streak. */
    static final float STREAK = 2f;
    /** The glow's Gaussian, as a share of the picture's longer side, for the reference light; its range of sizes. */
    static final float SIZE = 0.0022f, SMALLEST = 0.35f, LARGEST = 2.6f;
    /** The REFth brightest light sets the sizes; lights FADE times fainter than it fade out. */
    static final int REF = 8;
    static final float FADE = 40f;
    /** Share of a light that goes into its glow at 100%. */
    static final float SHARE = 1f;
    /**
     * The glow's profile: Gaussians these many times its size wide, with these shares of its light, a soft core with
     * wide wings that fade into the sky (one Gaussian alone, stretched, shows as a disc with an edge).
     */
    static final float[] WIDTHS = {1f, 2f, 4f}, SHARES = {0.5f, 0.3f, 0.2f};
    /** How much of its star's colour a glow takes (a halo's light is paler than the star's own). */
    static final float CHROMA = 0.75f;
    /** Catalogue stars to this magnitude, and the planets, are placed in the picture ({@link #sky}). */
    static final float SKY_MAG = 5.5f;
    /** Floats per star or planet in {@link Renderer.Analysis#sky}: x, y (crop, full size), V, B-V (NaN: not known), 1 for a planet. */
    public static final int SKY_STRIDE = 5;
    /** How near (full-size pixels) a light must be to where the solution puts a star to be that star. */
    static final float MATCH = 4f;
    /** Unclipped stars the zero point needs, and how many times its measured light a clipped one may be made at most. */
    static final int ZERO_STARS = 5;
    static final float MOST_GAIN = 30f;
    /** B-V of the planets of {@link Planets#NAMES} (sunlight they reflect). */
    private static final float[] PLANET_BV = {0.93f, 0.82f, 1.36f, 0.83f, 1.04f, 0.56f, 0.41f};

    /** The lights, brightest first: crop position (full-size pixels, a pixel's centre at +0.5), light, colour. */
    final int n;
    final float[] x, y, light, qr, qb;
    /** The full-size crop's size. */
    final int cw, ch;
    /** How many peaks were looked at, and how many were streaks or wide glows. */
    int peaks, streaks, wide;
    /**
     * Lights found where the catalogue has a star or a planet; how many of those clipped ones were made brighter, and
     * how many took the catalogue's colour; the zero point (light of a magnitude-0 star; NaN: not found).
     */
    int matched, brightened, coloured;
    double zeroPoint = Double.NaN;

    private StarGlow(int n, int cw, int ch) {
        this.n = n;
        this.cw = cw;
        this.ch = ch;
        x = new float[n];
        y = new float[n];
        light = new float[n];
        qr = new float[n];
        qb = new float[n];
    }

    /** How many lights glow. */
    public int count() {
        return n;
    }

    public String describe() {
        return String.format(Locale.US, "%d lights glow (of %d peaks; %d streaks and %d wide glows left out)%s%s", n, peaks, streaks, wide,
                n > 0 ? String.format(Locale.US, "; the brightest %.0f, the %dth %.0f", light[0], Math.min(REF, n), light[Math.min(REF, n) - 1]) : "",
                matched > 0 ? String.format(Locale.US, "; %d are catalogue stars or planets: %d clipped ones made brighter by %s, "
                        + "%d coloured by their colour index", matched, brightened, Double.isNaN(zeroPoint) ? "no zero point (too few "
                        + "unclipped stars)" : String.format(Locale.US, "the zero point %.0f", zeroPoint), coloured) : "");
    }

    private static final float KR = 0.2126f, KG = 0.7152f, KB = 0.0722f;

    /**
     * The lights of {@code a}'s crop of {@code img}, a picture {@code scale} times the full size each way (1: full size;
     * 1/f: binned f x f, whose crop is the whole copy); positions and light in the full size's terms.
     */
    public static StarGlow find(RgbImage img, Renderer.Analysis a, float scale) {
        final int w = a.cw, h = a.ch, x0 = a.cx0, y0 = a.cy0, iw = img.width;
        final float inv = 1f / scale;
        final int B = Math.max(1, Math.round(BIN * scale));
        final int gw = w / B, gh = h / B;
        int fw = Math.round(w * inv), fh = Math.round(h * inv);
        if (gw < 5 || gh < 5 || !(a.noiseY > 0)) return new StarGlow(0, fw, fh);
        final float[] m = a.m;
        final double[][] P = a.poly;
        // the luminance binned B x B (background off)
        final float[] L = new float[gw * gh];
        Parallel.forRange(gh, Math.max(1, gh / Parallel.threads()), new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int j = j0; j < j1; j++) {
                    for (int yy = j * B; yy < j * B + B; yy++) {
                        int src = (y0 + yy) * iw + x0;
                        double v = ((yy + 0.5) / h) * 2 - 1;
                        for (int i = 0; i < gw; i++) {
                            float s = 0;
                            for (int xx = i * B; xx < i * B + B; xx++) s += lum(img, src + xx, m, P, xx, w, v);
                            L[j * gw + i] += s;
                        }
                    }
                    for (int i = 0; i < gw; i++) L[j * gw + i] /= B * B;
                }
            }
        });
        // peaks over their surroundings (the ring of cells two out)
        float noiseBin = a.noiseY * scale / B;
        float thr = DETECT * noiseBin;
        List<float[]> cand = new ArrayList<float[]>();
        for (int j = 2; j < gh - 2; j++) {
            for (int i = 2; i < gw - 2; i++) {
                int k = j * gw + i;
                float c = L[k];
                if (c <= thr) continue;
                boolean peak = true;
                for (int dj = -1; dj <= 1 && peak; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        if (di == 0 && dj == 0) continue;
                        float o = L[k + dj * gw + di];
                        // strictly over the neighbours before it, at least as high as those after (one peak on a tie)
                        if (o > c || o == c && (dj < 0 || dj == 0 && di < 0)) {
                            peak = false;
                            break;
                        }
                    }
                }
                if (!peak) continue;
                float ring = 0;
                for (int d = -2; d <= 2; d++) ring += L[k - 2 * gw + d] + L[k + 2 * gw + d] + (d > -2 && d < 2 ? L[k + d * gw - 2] + L[k + d * gw + 2] : 0);
                ring /= 16;
                if (c - ring > thr) cand.add(new float[]{c - ring, i, j});
            }
        }
        Collections.sort(cand, new Comparator<float[]>() {
            @Override
            public int compare(float[] p, float[] q) {
                return Float.compare(q[0], p[0]);
            }
        });
        int look = Math.min(cand.size(), 4 * MOST);
        // measured at the picture's resolution
        float ap = Math.max(1.5f, APERTURE * scale), r1 = Math.max(ap + 1, RING_IN * scale), r2 = Math.max(r1 + 2, RING_OUT * scale);
        int R = (int) Math.ceil(r2) + 1;
        List<float[]> found = new ArrayList<float[]>();
        int streaks = 0, wide = 0;
        float[] ringv = new float[(2 * R + 1) * (2 * R + 1)];
        float[] ic = new float[3];
        for (int c = 0; c < look; c++) {
            float[] cd = cand.get(c);
            int ci = (int) cd[1], cj = (int) cd[2];
            // the brightest pixel in the cell and around it
            int bx = -1, by = -1;
            float best = -Float.MAX_VALUE;
            for (int yy = Math.max(0, cj * B - B); yy < Math.min(h, cj * B + 2 * B); yy++) {
                int src = (y0 + yy) * iw + x0;
                double v = ((yy + 0.5) / h) * 2 - 1;
                for (int xx = Math.max(0, ci * B - B); xx < Math.min(w, ci * B + 2 * B); xx++) {
                    float l = lum(img, src + xx, m, P, xx, w, v);
                    if (l > best) {
                        best = l;
                        bx = xx;
                        by = yy;
                    }
                }
            }
            if (bx < R || by < R || bx >= w - R || by >= h - R) continue;
            // the ring's median, and how much of it clips (a star's clipped core never reaches it: the Moon's disc does)
            clipInv(a, bx + 0.5f, by + 0.5f, w, h, ic);
            int nr = 0, nrc = 0;
            for (int dy = -R; dy <= R; dy++) {
                for (int dx = -R; dx <= R; dx++) {
                    float d2 = dx * dx + dy * dy;
                    if (d2 < r1 * r1 || d2 > r2 * r2) continue;
                    int yy = by + dy, src = (y0 + yy) * iw + x0 + bx + dx;
                    ringv[nr++] = lum(img, src, m, P, bx + dx, w, ((yy + 0.5) / h) * 2 - 1);
                    if (Math.max(img.r[src] * ic[0], Math.max(img.g[src] * ic[1], img.b[src] * ic[2])) > 0.9f) nrc++;
                }
            }
            if (nr < 8) continue;
            Arrays.sort(ringv, 0, nr);
            float bg = ringv[nr / 2];
            if (bg > WIDE * best || nrc > CLIPPED_RING * nr) {
                wide++;
                continue;
            }
            // the disc: light, centre, second moments, colour from its unclipped pixels
            double sl = 0, sp = 0, sx = 0, sy = 0, sr = 0, sb = 0, sy2 = 0, sc = 0;
            double mxx = 0, myy = 0, mxy = 0;
            for (int dy = -R; dy <= R; dy++) {
                for (int dx = -R; dx <= R; dx++) {
                    if (dx * dx + dy * dy > ap * ap) continue;
                    int yy = by + dy, xx = bx + dx;
                    int src = (y0 + yy) * iw + x0 + xx;
                    double u = ((xx + 0.5) / w) * 2 - 1, v = ((yy + 0.5) / h) * 2 - 1;
                    float cr = img.r[src], cg = img.g[src], cb = img.b[src];
                    float r = (float) (m[0] * cr + m[1] * cg + m[2] * cb - poly(P[0], u, v));
                    float g = (float) (m[3] * cr + m[4] * cg + m[5] * cb - poly(P[1], u, v));
                    float b = (float) (m[6] * cr + m[7] * cg + m[8] * cb - poly(P[2], u, v));
                    float y = KR * r + KG * g + KB * b;
                    float l = y - bg;
                    sl += l;
                    if (l > 0) {
                        // centre and shape from the light over the surroundings
                        sp += l;
                        sx += l * dx;
                        sy += l * dy;
                        mxx += l * dx * dx;
                        myy += l * dy * dy;
                        mxy += l * dx * dy;
                    }
                    boolean clipped = img.clip != null && (img.clip[src] & 0xFF) > 5
                            || Math.max(cr * ic[0], Math.max(cg * ic[1], cb * ic[2])) > 0.9f;
                    if (clipped && l > 0) sc += l;
                    if (!clipped && y > 0) {
                        // the colour of the light over the surroundings: the surroundings taken off in proportion
                        float k = l / y;
                        sr += r * k;
                        sb += b * k;
                        sy2 += l;
                    }
                }
            }
            if (!(sl > 0) || !(sp > 0)) continue;
            double cx = sx / sp, cy = sy / sp;
            double ixx = mxx / sp - cx * cx, iyy = myy / sp - cy * cy, ixy = mxy / sp - cx * cy;
            double tr = ixx + iyy, det = ixx * iyy - ixy * ixy;
            double disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
            double l1 = tr / 2 + disc, l2 = tr / 2 - disc;
            if (l2 > 0 && Math.sqrt(l1 / l2) > STREAK) {
                streaks++;
                continue;
            }
            float qr = 0, qb = 0;
            boolean coloured = sy2 > 0.3 * sl;
            if (coloured) {
                qr = (float) (sr / sy2 - 1);
                qb = (float) (sb / sy2 - 1);
            }
            if (Float.isNaN(qr) || Float.isNaN(qb) || Math.abs(qr) > 3 || Math.abs(qb) > 3) {
                qr = qb = 0;
                coloured = false;
            }
            // light, centre, colour, the share of the light in clipped pixels, whether the colour was measured
            found.add(new float[]{(float) sl * inv * inv, (float) ((bx + 0.5 + cx) * inv), (float) ((by + 0.5 + cy) * inv), qr, qb,
                    (float) (sc / sp), coloured ? 1 : 0});
        }
        int[] fromSky = fromSky(found, a.sky, Math.max(MATCH, 1.5f * inv));
        Collections.sort(found, new Comparator<float[]>() {
            @Override
            public int compare(float[] p, float[] q) {
                return Float.compare(q[0], p[0]);
            }
        });
        // one light each: a fainter one within the disc of a brighter one is its own peak twice
        List<float[]> keep = new ArrayList<float[]>();
        float sep2 = APERTURE * APERTURE;
        for (float[] f : found) {
            boolean dup = false;
            for (float[] k : keep) {
                float dx = f[1] - k[1], dy = f[2] - k[2];
                if (dx * dx + dy * dy < sep2) {
                    dup = true;
                    break;
                }
            }
            if (!dup) keep.add(f);
            if (keep.size() >= MOST) break;
        }
        StarGlow g = new StarGlow(keep.size(), fw, fh);
        for (int k = 0; k < g.n; k++) {
            float[] f = keep.get(k);
            g.light[k] = f[0];
            g.x[k] = f[1];
            g.y[k] = f[2];
            g.qr[k] = f[3];
            g.qb[k] = f[4];
        }
        g.peaks = cand.size();
        g.streaks = streaks;
        g.wide = wide;
        g.matched = fromSky[0];
        g.brightened = fromSky[1];
        g.coloured = fromSky[2];
        g.zeroPoint = fromSky[3] > 0 ? Float.intBitsToFloat(fromSky[4]) : Double.NaN;
        return g;
    }

    /**
     * Gives the lights {@code found} (light, x, y, qr, qb, clipped share, colour measured) that are the stars and planets
     * of {@code sky} (within {@code r} full-size pixels; {@link Renderer.Analysis#sky}, null: none) what the photo lost
     * where it clips them: the brightness of their magnitude by the zero point of the unclipped stars, never less than
     * measured, and the colour of their colour index. Returns how many were matched, made brighter and coloured, whether
     * a zero point was found and its float bits.
     */
    static int[] fromSky(List<float[]> found, float[] sky, float r) {
        int[] out = new int[5];
        if (sky == null || found.isEmpty()) return out;
        int nf = found.size(), ns = sky.length / SKY_STRIDE;
        double[] flux = new double[nf];
        int[] first = new int[nf];
        Arrays.fill(first, -1);
        float r2 = r * r;
        // each star or planet to the nearest light (the brightest first, so a light's first is the brightest it holds)
        for (int s = 0; s < ns; s++) {
            float hx = sky[s * SKY_STRIDE], hy = sky[s * SKY_STRIDE + 1];
            int near = -1;
            float nd = r2;
            for (int k = 0; k < nf; k++) {
                float[] f = found.get(k);
                float dx = f[1] - hx, dy = f[2] - hy, d2 = dx * dx + dy * dy;
                if (d2 <= nd) {
                    nd = d2;
                    near = k;
                }
            }
            if (near < 0) continue;
            flux[near] += Math.pow(10, -0.4 * sky[s * SKY_STRIDE + 2]);
            if (first[near] < 0) first[near] = s;
        }
        // the zero point: the light of a magnitude-0 star, from the stars that didn't clip
        double[] z = new double[nf];
        int nz = 0;
        for (int k = 0; k < nf; k++) {
            float[] f = found.get(k);
            if (flux[k] > 0 && sky[first[k] * SKY_STRIDE + 4] == 0 && f[5] == 0 && f[0] > 0) z[nz++] = f[0] / flux[k];
        }
        double zp = Double.NaN;
        if (nz >= ZERO_STARS) {
            Arrays.sort(z, 0, nz);
            zp = nz % 2 == 1 ? z[nz / 2] : 0.5 * (z[nz / 2 - 1] + z[nz / 2]);
        }
        float[] q = new float[2];
        for (int k = 0; k < nf; k++) {
            if (!(flux[k] > 0)) continue;
            out[0]++;
            float[] f = found.get(k);
            boolean clipped = f[5] > 0;
            if (clipped && zp > 0) {
                float want = (float) Math.min(zp * flux[k], f[0] * (double) MOST_GAIN);
                if (want > f[0]) {
                    f[0] = want;
                    out[1]++;
                }
            }
            float bv = sky[first[k] * SKY_STRIDE + 3];
            if ((clipped || f[6] == 0) && !Float.isNaN(bv)) {
                colourOf(bv, q);
                f[3] = q[0];
                f[4] = q[1];
                out[2]++;
            }
        }
        if (zp > 0) {
            out[3] = 1;
            out[4] = Float.floatToIntBits((float) zp);
        }
        return out;
    }

    /** The colour (r / Y - 1, b / Y - 1 of linear sRGB, a Sun-like star white) of a star of colour index {@code bv}. */
    static void colourOf(double bv, float[] out) {
        double t = ColorCalibration.kelvin(Math.max(ColorCalibration.BV_MIN, Math.min(ColorCalibration.BV_MAX, bv)));
        double[] e = ColorCalibration.expected(t);
        double y = KR * e[0] + KG * e[1] + KB * e[2];
        out[0] = (float) (e[0] / y - 1);
        out[1] = (float) (e[2] / y - 1);
    }

    /**
     * The catalogue's stars to {@link #SKY_MAG} and the planets in {@code a}'s crop, placed by a plate solution of the
     * stack ({@code jdUtc}: its reference frame's time, NaN: unknown, no planets), brightest first, for
     * {@link Renderer.Analysis#sky}; null without a solution.
     */
    public static float[] sky(SkySolver.Solution sol, double jdUtc, BrightStars cat, Renderer.Analysis a) {
        if (sol == null || sol.R == null || sol.model == null || a == null || a.cw <= 0 || a.ch <= 0) return null;
        SkyGuide.Frame f = new SkyGuide.Frame(sol, a.cx0, a.cy0, a.cw, a.ch);
        List<float[]> l = new ArrayList<float[]>();
        if (cat != null) {
            for (int i = 0; i < cat.n; i++) {
                if (cat.vmag[i] <= SKY_MAG) place(l, f, cat.vec(i), cat.vmag[i], cat.bv[i], 0, a);
            }
        }
        if (!Double.isNaN(jdUtc)) {
            Planets.Seen[] p = Planets.all(Astro.jdTt(jdUtc));
            for (int k = 0; k < p.length; k++) {
                if (p[k].mag <= SKY_MAG) place(l, f, p[k].dir, (float) p[k].mag, PLANET_BV[k], 1, a);
            }
        }
        Collections.sort(l, new Comparator<float[]>() {
            @Override
            public int compare(float[] p, float[] q) {
                return Float.compare(p[2], q[2]);
            }
        });
        float[] out = new float[l.size() * SKY_STRIDE];
        for (int k = 0; k < l.size(); k++) System.arraycopy(l.get(k), 0, out, k * SKY_STRIDE, SKY_STRIDE);
        return out;
    }

    private static void place(List<float[]> l, SkyGuide.Frame f, double[] dir, float v, float bv, float planet, Renderer.Analysis a) {
        double[] uv = f.near(dir);
        if (uv == null || uv[0] < 0 || uv[0] > 1 || uv[1] < 0 || uv[1] > 1) return;
        l.add(new float[]{(float) (uv[0] * a.cw), (float) (uv[1] * a.ch), v, bv, planet});
    }

    /** The luminance of pixel {@code src} of {@code img} (column xx of the crop, v its row's place in -1..1), background off. */
    private static float lum(RgbImage img, int src, float[] m, double[][] P, int xx, int w, double v) {
        float cr = img.r[src], cg = img.g[src], cb = img.b[src];
        double u = ((xx + 0.5) / w) * 2 - 1;
        float r = (float) (m[0] * cr + m[1] * cg + m[2] * cb - poly(P[0], u, v));
        float g = (float) (m[3] * cr + m[4] * cg + m[5] * cb - poly(P[1], u, v));
        float b = (float) (m[6] * cr + m[7] * cg + m[8] * cb - poly(P[2], u, v));
        return KR * r + KG * g + KB * b;
    }

    private static double poly(double[] k, double u, double v) {
        return k[0] + k[1] * u + k[2] * v + k[3] * u * u + k[4] * u * v + k[5] * v * v;
    }

    /** 1 / clip level per channel at crop position (px, py) of a w x h crop, from the analysis' grid. */
    private static void clipInv(Renderer.Analysis a, float px, float py, int w, int h, float[] out) {
        if (a.clipInv == null) {
            float c = a.clip > 0 ? 1f / a.clip : 0f;
            out[0] = out[1] = out[2] = c;
            return;
        }
        int gx = Renderer.CGX, gy = Renderer.CGY;
        float fx = Math.min(gx - 1f, Math.max(0f, px / w * (gx - 1))), fy = Math.min(gy - 1f, Math.max(0f, py / h * (gy - 1)));
        int i = Math.min(gx - 2, (int) fx), j = Math.min(gy - 2, (int) fy);
        float tx = fx - i, ty = fy - j;
        for (int c = 0; c < 3; c++) {
            float[] g = a.clipInv;
            int o = c * gy * gx;
            float top = g[o + j * gx + i] + tx * (g[o + j * gx + i + 1] - g[o + j * gx + i]);
            float bot = g[o + (j + 1) * gx + i] + tx * (g[o + (j + 1) * gx + i + 1] - g[o + (j + 1) * gx + i]);
            out[c] = top + ty * (bot - top);
        }
    }

    /**
     * The glow for a render of strength {@code strength} (1: 100%) in a picture {@code scale} times the full size each
     * way, whose crop is {@code w} x {@code h} pixels; null when nothing glows.
     */
    public Field field(float strength, float scale, int w, int h) {
        if (n == 0 || !(strength > 0)) return null;
        float ref = light[Math.min(REF, n) - 1];
        float base = SIZE * Math.max(cw, ch) * scale;
        List<float[]> src = new ArrayList<float[]>();
        for (int k = 0; k < n; k++) {
            float rel = light[k] / ref;
            // fading out from FADE times fainter than the reference to 4 times brighter than that
            float t = (float) (Math.log(rel * FADE) / Math.log(4));
            if (t <= 0) continue;
            float fade = t >= 1 ? 1f : t * t * (3 - 2 * t);
            float s = base * clamp((float) Math.pow(rel, 0.25), SMALLEST, LARGEST);
            for (int c = 0; c < WIDTHS.length; c++) {
                float sc = Math.max(0.6f, s * WIDTHS[c]);
                // the light in the scaled picture's pixels: light x scale^2 summed over pixels scale^-2 times bigger
                float amp = strength * SHARE * SHARES[c] * fade * light[k] * scale * scale / (float) (2 * Math.PI * sc * sc);
                src.add(new float[]{x[k] * scale, y[k] * scale, sc, amp, CHROMA * qr[k], CHROMA * qb[k]});
            }
        }
        if (src.isEmpty()) return null;
        return new Field(src, w, h);
    }

    /** The glow of a render, ready to add to its rows. */
    public static final class Field {
        final int n, w, h;
        final float[] cx, cy, s2, amp, qr, qb;
        final int[] x0, x1, r;
        final float[][] prof;

        Field(List<float[]> src, int w, int h) {
            Collections.sort(src, new Comparator<float[]>() {
                @Override
                public int compare(float[] p, float[] q) {
                    return Float.compare(p[1], q[1]);
                }
            });
            n = src.size();
            this.w = w;
            this.h = h;
            cx = new float[n];
            cy = new float[n];
            s2 = new float[n];
            amp = new float[n];
            qr = new float[n];
            qb = new float[n];
            x0 = new int[n];
            x1 = new int[n];
            r = new int[n];
            prof = new float[n][];
            for (int k = 0; k < n; k++) {
                float[] f = src.get(k);
                cx[k] = f[0];
                cy[k] = f[1];
                float s = f[2];
                s2[k] = 1f / (2 * s * s);
                amp[k] = f[3];
                qr[k] = f[4];
                qb[k] = f[5];
                r[k] = (int) Math.ceil(4 * s);
                x0[k] = Math.max(0, (int) Math.floor(cx[k] - r[k]));
                x1[k] = Math.min(w - 1, (int) Math.ceil(cx[k] + r[k]));
                float[] p = new float[Math.max(0, x1[k] - x0[k] + 1)];
                for (int i = 0; i < p.length; i++) {
                    float dx = x0[k] + i + 0.5f - cx[k];
                    p[i] = (float) Math.exp(-dx * dx * s2[k]);
                }
                prof[k] = p;
            }
        }

        /**
         * Adds the glow to crop rows y0 to y1 - 1 held in arrays of rows of w pixels from row y0: luminance Y and the
         * colour-weighted luminance YC by its light, the chroma differences CR (r - Y) and CB (b - Y) by its colour.
         */
        public void add(int y0, int y1, float[] Y, float[] CR, float[] CB, float[] YC) {
            for (int k = 0; k < n; k++) {
                int ya = Math.max(y0, (int) Math.floor(cy[k] - r[k])), yb = Math.min(y1 - 1, (int) Math.ceil(cy[k] + r[k]));
                if (ya > yb) continue;
                float[] p = prof[k];
                float a = amp[k], er = qr[k], eb = qb[k], ss = s2[k];
                int xa = x0[k];
                for (int yy = ya; yy <= yb; yy++) {
                    float dy = yy + 0.5f - cy[k];
                    float fy = a * (float) Math.exp(-dy * dy * ss);
                    int o = (yy - y0) * w + xa;
                    for (int i = 0; i < p.length; i++) {
                        float g = fy * p[i];
                        Y[o + i] += g;
                        YC[o + i] += g;
                        CR[o + i] += g * er;
                        CB[o + i] += g * eb;
                    }
                }
            }
        }

        /** The glow's luminance at crop pixel (px, py), for tests. */
        public float at(int px, int py) {
            float sum = 0;
            for (int k = 0; k < n; k++) {
                float dx = px + 0.5f - cx[k], dy = py + 0.5f - cy[k];
                if (Math.abs(dx) > r[k] || Math.abs(dy) > r[k] || px < x0[k] || px > x1[k]) continue;
                sum += amp[k] * (float) Math.exp(-dy * dy * s2[k]) * prof[k][px - x0[k]];
            }
            return sum;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
