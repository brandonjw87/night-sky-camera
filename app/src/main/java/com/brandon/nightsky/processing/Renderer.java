package com.brandon.nightsky.processing;

import java.util.Locale;

/**
 * Turns the linear stacked image into the final picture:
 * camera RGB -> linear sRGB (the sensor's own colour matrices, night white balance), clipped star cores
 * made neutral, background gradient removal and neutralization (robust 2D polynomial fitted to star-masked
 * cell medians), the sky's noise taken out of the luminance with the stars set aside ({@link Denoise}, 1.4),
 * asinh stretch of the luminance with the
 * sky background at a chosen grey, and colour carried as ratios to the luminance: taken from the pixel
 * where it is well measured and from a luminance-weighted local average where it is not, display-encoded,
 * applied in proportion to the real signal (the background stays neutral), with dithered 8-bit output.
 * <p>
 * The input image is never modified, so it can be re-rendered with a different brightness, stretch, exposure,
 * black point, saturation or look (G3).
 */
public final class Renderer {
    private Renderer() {}

    /**
     * The rendering's version: raised whenever a change makes the same stack give a different photo (2: the sky's noise
     * taken out with the stars set aside, 1.4). Saved with every recipe (G3), so a photo rendered by an older version can
     * be rendered again from its kept stack with this one.
     */
    public static final int VERSION = 2;

    public static final class Params {
        /** 0 = dark sky, 1 = bright sky; 0.5 puts the background near 12% grey. */
        public float brightness = 0.5f;
        /** Colour strength on display-encoded colours; 1 = colorimetric. */
        public float saturation = 1.35f;
        /**
         * How much of the sky's noise {@link Denoise} finds is taken out of the luminance: 0 (none) to 1 (all it takes out).
         * Full-size renders, photos saved smaller and the photo's preview; not the live picture while stacking.
         */
        public float lumaNr = 1f;
        public boolean removeGradient = true;
        /**
         * Strength of the stretch, 1 (gentle) to 10 (strong): how far faint light is lifted above the sky background,
         * which stays at the grey {@link #brightness} gives it. 5 is the develop step's.
         */
        public float stretch = 5f;
        /** Exposure in stops, -2 to +2: the light above the sky background times 2^ev (the background stays put). */
        public float ev;
        /** Black point, 0 to 1: how far the sky background's grey is taken towards black (0: not at all). */
        public float black;
        /** The look (A7): {@link #NATURAL}, {@link #PUNCHY} or {@link #LINEAR}. */
        public int look = NATURAL;

        /**
         * The develop step's gentle asinh stretch; more contrast and colour in the faint light just above the
         * background (which keeps its grey); a plain linear scale, display-encoded, where bright stars clip.
         */
        public static final int NATURAL = 0, PUNCHY = 1, LINEAR = 2;
    }

    public static final class Analysis {
        /** Camera RGB -> linear sRGB, row-major. */
        public float[] m;
        /** Per channel: background polynomial c0 + c1 u + c2 v + c3 u^2 + c4 uv + c5 v^2, u,v in [-1, 1] over the crop. */
        public double[][] poly = new double[3][6];
        /** Noise sigma of the full-resolution luminance after background removal. */
        public float noiseY;
        /** Luminance that maps to white (per-frame clip level). */
        public float whiteY;
        /** Per-frame clip level in camera units. */
        public float clip;
        public int cx0, cy0, cw, ch;
        /** Median background before removal (linear sRGB), for logs and DNG exposure. */
        public float[] skyLevel = new float[3];
        public float maskedFraction;
        public int goodCells;
        /**
         * Star colours: chroma q = (r/Y - 1, b/Y - 1) is split into s along the blackbody locus direction
         * (axis) and p across it; locus[k] is the locus' p at s = locusS0 + k * locusDs.
         */
        public float axisR, axisB;
        public float[] locus;
        public float locusS0, locusDs;
        /** Full-resolution noise sigma of the chroma along and across the locus, in luminance units. */
        public float noiseS, noiseP;
        /**
         * Size (Gaussian sigma, px) of the window star colours are measured over: a star image for stacked
         * stars, longer for star trails, whose colour flickers along the trail with the sampling phase.
         */
        public float colorSigma = 1.2f;
        /**
         * 1 / clip level per channel on a CGX x CGY grid of nodes spanning the crop (lens shading raises the
         * clip level towards the corners): [(c * CGY + j) * CGX + i].
         */
        public float[] clipInv;
        /** The sky's noise, found by the first full-size render of {@link #denoised} and kept for the next ones (1.4). */
        Denoise.Result denoise;
        RgbImage denoised;
        /**
         * The same for the binned copy last shown as the photo's preview ({@link #renderPreview}), and the full-size
         * correction it was binned from (null: found in the copy itself).
         */
        Denoise.Result previewDenoise, previewFrom;
        RgbImage previewDenoised;
        /** What the last noise reduction with this analysis found (a full-size render's or a smaller photo's). */
        private String noiseNote = "";

        /** What the noise reduction found and took out, for the log; "" until a photo has been rendered. */
        public synchronized String noiseNote() {
            return noiseNote;
        }

        /** What the noise reduction took out of the preview's copy ({@link #renderPreview}); null before one is shown. */
        public synchronized Denoise.Result previewNoise() {
            return previewDenoise;
        }

        /** What it found in the full-size picture ({@link #render}); null before a full-size render. */
        public synchronized Denoise.Result photoNoise() {
            return denoise;
        }

        synchronized void noted(Denoise.Result r, String size) {
            if (r != null) noiseNote = size + r.describe();
        }

        public String describe() {
            return String.format(Locale.US, "crop %dx%d+%d+%d noiseY=%.3f chroma=%.3f/%.3f whiteY=%.1f sky=%.2f/%.2f/%.2f masked=%.1f%% cells=%d",
                    cw, ch, cx0, cy0, noiseY, noiseS, noiseP, whiteY, skyLevel[0], skyLevel[1], skyLevel[2], 100 * maskedFraction,
                    goodCells);
        }
    }

    /** Receives rendered rows (crop coordinates). Calls may come from several threads but never overlap rows. */
    public interface RowSink {
        void rows(int y, int count, int[] argb, int width) throws Exception;
    }

    private static final float KR = 0.2126f, KG = 0.7152f, KB = 0.0722f;
    static final int CGX = 33, CGY = 25;

    // ------------------------------------------------------------------------------------------------
    // Analysis
    // ------------------------------------------------------------------------------------------------

    public static Analysis analyze(RgbImage img, SensorSpec spec) {
        final Analysis a = new Analysis();
        a.m = spec.camToSrgb.clone();
        a.clip = spec.range();
        a.cx0 = img.x0;
        a.cy0 = img.y0;
        a.cw = img.cropWidth();
        a.ch = img.cropHeight();
        float[] m = a.m;
        a.whiteY = a.clip * ((KR * m[0] + KG * m[3] + KB * m[6]) + (KR * m[1] + KG * m[4] + KB * m[7])
                + (KR * m[2] + KG * m[5] + KB * m[8]));
        if (!(a.whiteY > 0)) a.whiteY = a.clip;

        int F = Math.max(1, Math.round(a.cw / 480f));
        RgbImage p = img.binned(F);
        int pw = p.width, ph = p.height;
        toSrgb(p, m);
        float[] Y = new float[pw * ph];
        for (int i = 0; i < Y.length; i++) Y[i] = KR * p.r[i] + KG * p.g[i] + KB * p.b[i];

        // Cell statistics of the proxy: median and noise of the luminance.
        int gx = 24, gy = 18;
        float[] cellMed = new float[gx * gy], cellSig = new float[gx * gy];
        float[] buf = new float[(pw / gx + 2) * (ph / gy + 2)];
        for (int cy = 0; cy < gy; cy++) {
            for (int cx = 0; cx < gx; cx++) {
                int xa = cx * pw / gx, xb = (cx + 1) * pw / gx, ya = cy * ph / gy, yb = (cy + 1) * ph / gy;
                int n = 0;
                for (int y = ya; y < yb; y++) for (int x = xa; x < xb; x++) buf[n++] = Y[y * pw + x];
                float med = Stats.median(buf, n);
                n = 0;
                for (int y = ya; y < yb; y++) for (int x = xa; x < xb; x++) buf[n++] = Y[y * pw + x];
                cellMed[cy * gx + cx] = med;
                cellSig[cy * gx + cx] = Math.max(1e-6f, Stats.madSigma(buf, n, med));
            }
        }
        float sigP = median(cellSig);
        // Mask only clearly bright structure (stars, their halos); a star field must not mask everything.
        boolean[] mask = new boolean[pw * ph];
        float kMask = 5f;
        for (int pass = 0; pass < 4; pass++) {
            int masked = 0;
            for (int y = 0; y < ph; y++) {
                int cy = Math.min(gy - 1, y * gy / ph);
                for (int x = 0; x < pw; x++) {
                    int cx = Math.min(gx - 1, x * gx / pw);
                    boolean mk = Y[y * pw + x] > cellMed[cy * gx + cx] + kMask * sigP;
                    mask[y * pw + x] = mk;
                    if (mk) masked++;
                }
            }
            if (masked < pw * ph / 25) break;
            kMask *= 1.6f;
        }
        mask = dilate(mask, pw, ph, 1);
        int maskedTotal = 0;
        for (boolean b : mask) if (b) maskedTotal++;
        a.maskedFraction = maskedTotal / (float) (pw * ph);

        // Per cell and channel: median of the unmasked pixels, then clipped once more at +2.5 sigma so the
        // sea of faint stars doesn't lift it. Then a robust polynomial per channel.
        double[] cu = new double[gx * gy], cv = new double[gx * gy];
        double[][] cval = new double[3][gx * gy];
        boolean[] cok = new boolean[gx * gy];
        float[] buf2 = new float[buf.length];
        for (int cy = 0; cy < gy; cy++) {
            for (int cx = 0; cx < gx; cx++) {
                int xa = cx * pw / gx, xb = (cx + 1) * pw / gx, ya = cy * ph / gy, yb = (cy + 1) * ph / gy;
                int kk = cy * gx + cx;
                cu[kk] = ((xa + xb) * 0.5 / pw) * 2 - 1;
                cv[kk] = ((ya + yb) * 0.5 / ph) * 2 - 1;
                int total = (xb - xa) * (yb - ya);
                cok[kk] = true;
                for (int c = 0; c < 3; c++) {
                    float[] src = p.channel(c);
                    int n = 0;
                    for (int y = ya; y < yb; y++) {
                        for (int x = xa; x < xb; x++) {
                            int i = y * pw + x;
                            if (!mask[i]) buf[n++] = src[i];
                        }
                    }
                    if (n < Math.max(4, total / 4)) {
                        cok[kk] = false;
                        continue;
                    }
                    System.arraycopy(buf, 0, buf2, 0, n);
                    float med = Stats.median(buf2, n);
                    System.arraycopy(buf, 0, buf2, 0, n);
                    float sd = Stats.madSigma(buf2, n, med);
                    int m2 = 0;
                    for (int i = 0; i < n; i++) if (buf[i] < med + 2.5f * sd) buf2[m2++] = buf[i];
                    cval[c][kk] = m2 >= 4 ? Stats.median(buf2, m2) : med;
                }
            }
        }
        int okCells = 0;
        for (boolean b : cok) if (b) okCells++;
        a.goodCells = okCells;
        for (int c = 0; c < 3; c++) a.poly[c] = robustPoly2(cu, cv, cval[c], cok);

        // Sky level (for logs / DNG): median of the cell medians.
        for (int c = 0; c < 3; c++) {
            float[] t = new float[gx * gy];
            int n = 0;
            for (int k = 0; k < gx * gy; k++) if (cok[k]) t[n++] = (float) cval[c][k];
            a.skyLevel[c] = n > 0 ? Stats.median(t, n) : 0f;
        }

        starLocus(a, spec.wbCct > 0 ? spec.wbCct : ColorMath.DEFAULT_CCT);
        a.clipInv = clipGrid(img, spec);

        // Full-resolution noise of the luminance and of the chroma along / across the star colour locus,
        // from differences of background pixels 4 px apart.
        int sx = Math.max(1, a.cw / 700), sy = Math.max(1, a.ch / 500);
        int cap = (a.cw / sx + 1) * (a.ch / sy + 1);
        float[] d = new float[cap], ds = new float[cap], dp = new float[cap];
        float[] c1 = new float[3], c2 = new float[3];
        int n = 0;
        for (int y = a.cy0; y < a.cy0 + a.ch; y += sy) {
            int py = Math.min(ph - 1, (y - a.cy0) / F);
            for (int x = a.cx0; x + 4 < a.cx0 + a.cw; x += sx) {
                int px = Math.min(pw - 1, (x - a.cx0) / F);
                if (mask[py * pw + px]) continue;
                int i = y * img.width + x, j = i + 4;
                rgb(m, img.r[i], img.g[i], img.b[i], c1);
                rgb(m, img.r[j], img.g[j], img.b[j], c2);
                float y1 = KR * c1[0] + KG * c1[1] + KB * c1[2];
                float y2 = KR * c2[0] + KG * c2[1] + KB * c2[2];
                float dr = (c1[0] - y1) - (c2[0] - y2), db = (c1[2] - y1) - (c2[2] - y2);
                d[n] = y1 - y2;
                ds[n] = a.axisR * dr + a.axisB * db;
                dp[n] = -a.axisB * dr + a.axisR * db;
                n++;
            }
        }
        float rt2 = (float) Math.sqrt(2);
        if (n > 16) {
            a.noiseY = Math.max(1e-4f, Stats.madSigma(d, n, 0f) / rt2);
            a.noiseS = Math.max(1e-4f, Stats.madSigma(ds, n, 0f) / rt2);
            a.noiseP = Math.max(1e-4f, Stats.madSigma(dp, n, 0f) / rt2);
        } else {
            a.noiseY = Math.max(1e-4f, sigP * F);
            a.noiseS = a.noiseP = 3 * a.noiseY;
        }
        return a;
    }

    /** Per-channel 1 / clip level over the crop, including the lens shading gain applied after stacking. */
    static float[] clipGrid(RgbImage img, SensorSpec spec) {
        float range = Math.max(1f, spec.range());
        ShadingMap sh = spec.shading;
        int pR = 0, pG1 = -1, pG2 = -1, pB = 3;
        for (int q = 0; q < 4; q++) {
            int c = spec.cfa[q];
            if (c == 0) pR = q;
            else if (c == 2) pB = q;
            else if (pG1 < 0) pG1 = q;
            else pG2 = q;
        }
        if (pG1 < 0) pG1 = 1;
        if (pG2 < 0) pG2 = pG1;
        float[] g = new float[3 * CGX * CGY];
        int cw = img.cropWidth(), ch = img.cropHeight();
        for (int j = 0; j < CGY; j++) {
            double y = img.y0 + j * (double) ch / (CGY - 1);
            for (int i = 0; i < CGX; i++) {
                double x = img.x0 + i * (double) cw / (CGX - 1);
                float gr = 1f, gg = 1f, gb = 1f;
                if (sh != null) {
                    gr = sh.gain(pR, x, y);
                    gg = 0.5f * (sh.gain(pG1, x, y) + sh.gain(pG2, x, y));
                    gb = sh.gain(pB, x, y);
                }
                g[(0 * CGY + j) * CGX + i] = 1f / (range * gr);
                g[(1 * CGY + j) * CGX + i] = 1f / (range * gg);
                g[(2 * CGY + j) * CGX + i] = 1f / (range * gb);
            }
        }
        return g;
    }

    private static void rgb(float[] m, float r, float g, float b, float[] out) {
        out[0] = m[0] * r + m[1] * g + m[2] * b;
        out[1] = m[3] * r + m[4] * g + m[5] * b;
        out[2] = m[6] * r + m[7] * g + m[8] * b;
    }

    /**
     * The blackbody (star colour) locus in chroma space for this white balance: its main direction and,
     * as a table over the position along it, how far it bends away from that direction.
     */
    static void starLocus(Analysis a, double wbCct) {
        int nT = 160;
        double[] qs = new double[nT], qp = new double[nT];
        double[][] q = new double[nT][2];
        double sxx = 0, sxy = 0, syy = 0;
        for (int k = 0; k < nT; k++) {
            double t = 1800 * Math.pow(30000 / 1800.0, k / (double) (nT - 1));
            double[] v = ColorMath.blackbodyRgb(t, wbCct);
            double Y = KR * v[0] + KG * v[1] + KB * v[2];
            q[k][0] = v[0] / Y - 1;
            q[k][1] = v[2] / Y - 1;
            if (t >= 3000 && t <= 15000) {
                sxx += q[k][0] * q[k][0];
                sxy += q[k][0] * q[k][1];
                syy += q[k][1] * q[k][1];
            }
        }
        // principal direction of the 3000..15000 K part (through white), pointing to hot stars
        double ang = 0.5 * Math.atan2(2 * sxy, sxx - syy);
        double ar = Math.cos(ang), ab = Math.sin(ang);
        if (ab * q[nT - 1][1] + ar * q[nT - 1][0] < 0) {
            ar = -ar;
            ab = -ab;
        }
        a.axisR = (float) ar;
        a.axisB = (float) ab;
        for (int k = 0; k < nT; k++) {
            qs[k] = ar * q[k][0] + ab * q[k][1];
            qp[k] = -ab * q[k][0] + ar * q[k][1];
        }
        int nL = 128;
        a.locus = new float[nL];
        a.locusS0 = (float) qs[0];
        a.locusDs = (float) ((qs[nT - 1] - qs[0]) / (nL - 1));
        int k = 0;
        for (int i = 0; i < nL; i++) {
            double sv = a.locusS0 + i * (double) a.locusDs;
            while (k < nT - 2 && qs[k + 1] < sv) k++;
            double f = (sv - qs[k]) / (qs[k + 1] - qs[k]);
            f = Math.max(0, Math.min(1, f));
            a.locus[i] = (float) (qp[k] + f * (qp[k + 1] - qp[k]));
        }
    }

    /** The locus' cross coordinate at position s along it (clamped to its ends). */
    static float locusAt(Analysis a, float s) {
        float f = (s - a.locusS0) / a.locusDs;
        int n = a.locus.length;
        if (f <= 0) return a.locus[0];
        if (f >= n - 1) return a.locus[n - 1];
        int i = (int) f;
        float t = f - i;
        return a.locus[i] + t * (a.locus[i + 1] - a.locus[i]);
    }

    private static float median(float[] v) {
        float[] t = v.clone();
        return Stats.median(t, t.length);
    }

    private static void toSrgb(RgbImage p, float[] m) {
        for (int i = 0; i < p.r.length; i++) {
            float r = p.r[i], g = p.g[i], b = p.b[i];
            p.r[i] = m[0] * r + m[1] * g + m[2] * b;
            p.g[i] = m[3] * r + m[4] * g + m[5] * b;
            p.b[i] = m[6] * r + m[7] * g + m[8] * b;
        }
    }

    private static boolean[] dilate(boolean[] m, int w, int h, int r) {
        boolean[] o = new boolean[m.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!m[y * w + x]) continue;
                for (int yy = Math.max(0, y - r); yy <= Math.min(h - 1, y + r); yy++) {
                    for (int xx = Math.max(0, x - r); xx <= Math.min(w - 1, x + r); xx++) o[yy * w + xx] = true;
                }
            }
        }
        return o;
    }

    /** IRLS fit of a quadratic surface; positive residuals (stars, clouds, Milky Way) are down-weighted harder. */
    static double[] robustPoly2(double[] u, double[] v, double[] val, boolean[] ok) {
        int n = u.length;
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = ok[i] ? 1 : 0;
        double[] coef = new double[6];
        double[] res = new double[n];
        for (int it = 0; it < 12; it++) {
            double[][] A = new double[6][6];
            double[] B = new double[6];
            double[] t = new double[6];
            int used = 0;
            for (int i = 0; i < n; i++) {
                if (w[i] <= 0) continue;
                used++;
                terms(u[i], v[i], t);
                for (int r = 0; r < 6; r++) {
                    B[r] += w[i] * t[r] * val[i];
                    for (int c = 0; c < 6; c++) A[r][c] += w[i] * t[r] * t[c];
                }
            }
            if (used < 8) {
                // too little to fit a surface: constant level
                double s = 0, sw = 0;
                for (int i = 0; i < n; i++) if (ok[i]) {
                    s += val[i];
                    sw += 1;
                }
                coef = new double[6];
                coef[0] = sw > 0 ? s / sw : 0;
                return coef;
            }
            for (int r = 0; r < 6; r++) A[r][r] *= 1 + 1e-9;
            double[] sol = solve(A, B);
            if (sol == null) break;
            coef = sol;
            float[] ar = new float[n];
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (!ok[i]) continue;
                terms(u[i], v[i], t);
                double f = 0;
                for (int r = 0; r < 6; r++) f += coef[r] * t[r];
                res[i] = val[i] - f;
                ar[m++] = (float) Math.abs(res[i]);
            }
            double s = Math.max(1e-6, 1.4826 * Stats.median(ar, m));
            for (int i = 0; i < n; i++) {
                if (!ok[i]) continue;
                double c = res[i] > 0 ? 2.5 * s : 4.685 * s;
                double z = res[i] / c;
                w[i] = Math.abs(z) < 1 ? (1 - z * z) * (1 - z * z) : 0;
            }
        }
        return coef;
    }

    static void terms(double u, double v, double[] t) {
        t[0] = 1;
        t[1] = u;
        t[2] = v;
        t[3] = u * u;
        t[4] = u * v;
        t[5] = v * v;
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    static double[] solve(double[][] A0, double[] b0) {
        int n = b0.length;
        double[][] A = new double[n][];
        for (int i = 0; i < n; i++) A[i] = A0[i].clone();
        double[] b = b0.clone();
        for (int c = 0; c < n; c++) {
            int piv = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(A[r][c]) > Math.abs(A[piv][c])) piv = r;
            if (Math.abs(A[piv][c]) < 1e-300) return null;
            double[] tr = A[c];
            A[c] = A[piv];
            A[piv] = tr;
            double tb = b[c];
            b[c] = b[piv];
            b[piv] = tb;
            for (int r = c + 1; r < n; r++) {
                double f = A[r][c] / A[c][c];
                if (f == 0) continue;
                for (int k = c; k < n; k++) A[r][k] -= f * A[c][k];
                b[r] -= f * b[c];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int k = r + 1; k < n; k++) s -= A[r][k] * x[k];
            x[r] = s / A[r][r];
        }
        return x;
    }

    // ------------------------------------------------------------------------------------------------
    // Tone curve
    // ------------------------------------------------------------------------------------------------

    /**
     * Tone curve for the luminance: background (0) maps to T, white point maps to 1. The look and the black point
     * follow it; the exposure scales the light above the background before it.
     */
    static final class Curve {
        float bp, s, norm;
        /** The background's display grey. */
        float T;
        /** Light above the background is multiplied by this before the curve (the exposure). */
        float gain = 1f;
        int look = Params.NATURAL;
        /** {@link Params#LINEAR}: display = sRGB(k * I), with the background at T. */
        float k;
        /** Black point in display units (0: none), and 1 / (1 - it). */
        float black, blackScale = 1f;
        /** {@link Params#PUNCHY}: the contrast at the background grey. */
        static final float PUNCH = 1.4f;

        static Curve make(Analysis a, Params p) {
            Curve c = new Curve();
            float T = (float) (0.045 * Math.pow(0.30 / 0.045, Stats.clamp(p.brightness, 0f, 1f)));
            // the stretch: the curve's foot this many noise sigmas below the background; nearer lifts faint light more
            float off = p.stretch == 5f ? 2.2f : (float) (2.2 * Math.pow(2, (5 - Stats.clamp(p.stretch, 1f, 10f)) / 2.3));
            c.bp = -off * a.noiseY;
            double i0 = -c.bp, imax = Math.max(a.whiteY - c.bp, i0 * 4);
            // find s with asinh(i0/s)/asinh(imax/s) = T (decreasing in s)
            double lo = Math.log(i0 * 1e-6), hi = Math.log(imax * 1e6);
            for (int it = 0; it < 100; it++) {
                double mid = 0.5 * (lo + hi);
                double s = Math.exp(mid);
                double r = asinh(i0 / s) / asinh(imax / s);
                if (r > T) lo = mid;
                else hi = mid;
            }
            double s = Math.exp(0.5 * (lo + hi));
            c.s = (float) s;
            c.norm = (float) (1.0 / asinh(imax / s));
            c.T = T;
            c.look = p.look == Params.PUNCHY || p.look == Params.LINEAR ? p.look : Params.NATURAL;
            if (p.ev != 0f) c.gain = (float) Math.pow(2, Stats.clamp(p.ev, -2f, 2f));
            if (c.look == Params.LINEAR) c.k = (float) (srgbToLinear(T) / i0);
            float b = Stats.clamp(p.black, 0f, 1f) * T;
            if (b > 0f) {
                c.black = b;
                c.blackScale = 1f / (1f - b);
            }
            return c;
        }

        /** Display value of light I above the curve's foot (I > 0), before the black point. */
        float display(float I) {
            float F;
            if (look == Params.LINEAR) {
                float v = I * k;
                F = v >= 1f ? 1f : linearToSrgb(v);
            } else {
                F = asinhf(I / s) * norm;
                if (look == Params.PUNCHY) F = punch(F);
            }
            if (black > 0f) F = Math.max(0f, (F - black) * blackScale);
            return F;
        }

        /**
         * More contrast at the background's grey (T stays T): the noise below it darker, the faint light just above it
         * lifted, the highlights eased into white.
         */
        float punch(float F) {
            if (F <= 0f) return 0f;
            if (F < T) return T * (float) Math.pow(F / T, PUNCH);
            float x = Math.min(1f, (F - T) / (1f - T));
            return T + (1f - T) * (1f - (float) Math.pow(1f - x, PUNCH));
        }
    }

    static double srgbToLinear(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static float linearToSrgb(float v) {
        return v <= 0.0031308f ? 12.92f * v : 1.055f * (float) Math.pow(v, 1 / 2.4) - 0.055f;
    }

    static double asinh(double x) {
        return Math.log(x + Math.sqrt(x * x + 1));
    }

    private static float asinhf(float x) {
        return (float) Math.log(x + Math.sqrt(x * x + 1.0));
    }

    private static float smooth(float x, float a, float b) {
        float t = (x - a) / (b - a);
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }

    // ------------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------------

    /** Full-resolution render of the crop area, delivered in bands of rows to the sink. */
    public static void render(final RgbImage img, final Analysis a, final Params p, final RowSink sink, final Progress progress) {
        final Curve curve = Curve.make(a, p);
        final Denoise.Result dn = p.lumaNr > 0 ? denoise(img, a) : null;
        final int bandRows = 32;
        final int bands = (a.ch + bandRows - 1) / bandRows;
        final int[] done = {0};
        Parallel.forRange(bands, Math.max(1, bands / Parallel.threads()), new Parallel.Range() {
            @Override
            public void run(int b0, int b1) throws Exception {
                Band band = new Band(a.cw, bandRows, a.colorSigma);
                int[] argb = new int[a.cw * bandRows];
                for (int b = b0; b < b1; b++) {
                    int y0 = b * bandRows, y1 = Math.min(a.ch, y0 + bandRows);
                    band.process(img, a, p, curve, y0, y1, 1f, dn, argb);
                    sink.rows(y0, y1 - y0, argb, a.cw);
                    synchronized (done) {
                        done[0]++;
                        if (progress != null) progress.update("Finishing the photo", done[0] / (float) bands);
                    }
                }
            }
        });
    }

    /**
     * The sky's noise in a full-size render of {@code img}: found the first time and kept with the analysis for the next
     * renders of the same image (an image changed in place is analysed again, which starts afresh). Null when the
     * picture is too small or has no noise to measure.
     */
    static Denoise.Result denoise(RgbImage img, Analysis a) {
        synchronized (a) {
            if (a.denoised != img) {
                a.denoise = Denoise.run(img, a, 1f);
                a.denoised = img;
            }
            a.noted(a.denoise, "");
            return a.denoise;
        }
    }

    /** What a small render does about the sky's noise: nothing, as a smaller photo, or as the photo's preview. */
    private static final int AS_IS = 0, PHOTO = 1, PREVIEW = 2;

    /**
     * Quick render of a binned copy (from {@link RgbImage#binned}) with the same tone curve, for the live
     * picture while stacking. {@code factor} is the binning factor (noise is that many times lower).
     */
    public static int[] renderSmall(RgbImage small, Analysis a, Params p, int factor) {
        return renderSmall(small, a, p, factor, AS_IS);
    }

    /**
     * {@link #renderSmall} for a photo saved smaller than the stack (Quick look's half size): with the sky's noise
     * taken out as a full-size render does.
     */
    public static int[] renderSmallPhoto(RgbImage small, Analysis a, Params p, int factor) {
        return renderSmall(small, a, p, factor, PHOTO);
    }

    /**
     * {@link #renderSmall} for the developed photo on the screen (the result and the edit screen): with the sky's
     * noise taken out as in the saved photo. Once the photo has been rendered full size, that render's correction,
     * binned as the copy is, shows exactly what the saved photo lost; before, the noise is found in the copy itself.
     * Either is kept with the analysis for the next renders of the same copy (another brightness, an edit's
     * settings), which take no longer than before.
     */
    public static int[] renderPreview(RgbImage small, Analysis a, Params p, int factor) {
        return renderSmall(small, a, p, factor, PREVIEW);
    }

    /** The sky's noise in a copy binned {@code factor} x {@code factor} ({@link RgbImage#binned}), found in the copy itself. */
    public static Denoise.Result smallNoise(RgbImage small, Analysis a, int factor) {
        return Denoise.run(small, smallAnalysis(small, a, factor), 1f / factor);
    }

    private static int[] renderSmall(RgbImage small, Analysis a, Params p, int factor, int nr) {
        Curve curve = Curve.make(a, p);
        Analysis sa = smallAnalysis(small, a, factor);
        Denoise.Result dn = null;
        if (p.lumaNr > 0 && nr == PHOTO) {
            dn = Denoise.run(small, sa, 1f / factor);
            if (dn != null) a.noted(dn, factor + "x smaller: ");
        } else if (p.lumaNr > 0 && nr == PREVIEW) {
            synchronized (a) {
                Denoise.Result full = a.denoise != null && small.isBinned(a.denoised, factor, a.cx0, a.cy0)
                        && small.width == a.cw / factor && small.height == a.ch / factor ? a.denoise : null;
                if (a.previewDenoised != small || a.previewFrom != full) {
                    a.previewDenoise = full != null ? Denoise.binned(full, factor, small.width, small.height)
                            : Denoise.run(small, sa, 1f / factor);
                    a.previewDenoised = small;
                    a.previewFrom = full;
                }
                dn = a.previewDenoise;
            }
        }
        int[] out = new int[small.width * small.height];
        int bandRows = 32;
        Band band = new Band(small.width, bandRows, sa.colorSigma);
        int[] argb = new int[small.width * bandRows];
        for (int y0 = 0; y0 < small.height; y0 += bandRows) {
            int y1 = Math.min(small.height, y0 + bandRows);
            band.process(small, sa, p, curve, y0, y1, 1f / factor, dn, argb);
            System.arraycopy(argb, 0, out, y0 * small.width, (y1 - y0) * small.width);
        }
        return out;
    }

    /**
     * A picture of the crop scaled down {@code factor} times each way (any factor, not only a binning) rendered with the
     * photo's tone curve and colour, for the photo's video (1.8): {@code noiseScale} is its noise relative to the analysed
     * full-resolution picture's, which sets how much colour faint light gets. The sky's noise is left in. Bands of rows
     * run in parallel; the same picture gives the same pixels.
     */
    public static int[] renderScaled(final RgbImage img, Analysis a, final Params p, float factor, final float noiseScale) {
        final Curve curve = Curve.make(a, p);
        final Analysis sa = smallAnalysis(img, a, factor);
        final int w = img.width, h = img.height;
        final int[] out = new int[w * h];
        final int bandRows = 32;
        final int bands = (h + bandRows - 1) / bandRows;
        Parallel.forRange(bands, Math.max(1, bands / Parallel.threads()), new Parallel.Range() {
            @Override
            public void run(int b0, int b1) {
                Band band = new Band(w, bandRows, sa.colorSigma);
                int[] argb = new int[w * bandRows];
                for (int b = b0; b < b1; b++) {
                    int y0 = b * bandRows, y1 = Math.min(h, y0 + bandRows);
                    band.process(img, sa, p, curve, y0, y1, noiseScale, null, argb);
                    System.arraycopy(argb, 0, out, y0 * w, (y1 - y0) * w);
                }
            }
        });
        return out;
    }

    /** The analysis of {@code a}'s picture, for a copy of it scaled down {@code factor} times (binned, for a whole factor). */
    private static Analysis smallAnalysis(RgbImage small, Analysis a, float factor) {
        Analysis sa = new Analysis();
        sa.m = a.m;
        sa.poly = a.poly;
        sa.noiseY = a.noiseY;
        sa.noiseS = a.noiseS;
        sa.noiseP = a.noiseP;
        sa.axisR = a.axisR;
        sa.axisB = a.axisB;
        sa.locus = a.locus;
        sa.locusS0 = a.locusS0;
        sa.locusDs = a.locusDs;
        sa.clipInv = a.clipInv;
        sa.colorSigma = a.colorSigma / factor;
        sa.whiteY = a.whiteY;
        sa.clip = a.clip;
        sa.cx0 = 0;
        sa.cy0 = 0;
        sa.cw = small.width;
        sa.ch = small.height;
        return sa;
    }

    /** Working buffers for one band of rows plus margins. */
    static final class Band {
        /** Margin rows: at least the colour window radius and the luma filter radius. */
        final int M;
        /** Colour window: Gaussian kernel of radius GR (a matched filter for star images). */
        final int GR;
        final float[] GK;
        /** Sum of the squared 2D window weights: the window's noise variance relative to one pixel's. */
        final float GK2;
        final int w, rows;
        /** Luminance, chroma differences (r - Y, b - Y), colour weight (0 for clipped), weighted luminance. */
        final float[] Y, CR, CB, HW, YC, T1, T2, T3, TH, TMP;
        int rs, re; // crop rows held: [rs, re)

        Band(int w, int bandRows, float colorSigma) {
            float cs = Math.max(0.5f, colorSigma);
            GR = Math.max(2, (int) Math.ceil(3 * cs));
            GK = gaussKernel(GR, cs);
            float s2 = 0;
            for (float k : GK) s2 += k * k;
            GK2 = s2 * s2;
            M = Math.max(4, GR);
            this.w = w;
            this.rows = bandRows + 2 * M;
            int n = w * rows;
            Y = new float[n];
            CR = new float[n];
            CB = new float[n];
            HW = new float[n];
            YC = new float[n];
            T1 = new float[n];
            T2 = new float[n];
            T3 = new float[n];
            TH = new float[n];
            TMP = new float[n];
        }

        static float[] gaussKernel(int r, float sigma) {
            float[] k = new float[2 * r + 1];
            float s = 0;
            for (int i = -r; i <= r; i++) {
                k[i + r] = (float) Math.exp(-i * i / (2.0 * sigma * sigma));
                s += k[i + r];
            }
            for (int i = 0; i < k.length; i++) k[i] /= s;
            return k;
        }

        /**
         * {@code noiseScale}: the image's noise relative to the analysed full-resolution image; {@code dn}: the sky's noise
         * to take out of the luminance ({@link Params#lumaNr} of it), or null.
         */
        void process(RgbImage img, Analysis a, Params p, Curve curve, int y0, int y1, float noiseScale, Denoise.Result dn, int[] argb) {
            float noise = a.noiseY * noiseScale;
            rs = Math.max(0, y0 - M);
            re = Math.min(a.ch, y1 + M);
            float[] m = a.m;
            double[][] P = a.poly;
            boolean grad = p.removeGradient;
            double[] q = new double[9];
            float[] ic = new float[3 * CGX];
            float[] CN = T3; // clip badness 0..1, before T3 is needed
            byte[] flags = img.clip;
            for (int y = rs; y < re; y++) {
                int o = (y - rs) * w;
                int src = (a.cy0 + y) * img.width + a.cx0;
                double v = ((y + 0.5) / a.ch) * 2 - 1;
                // per row: polynomial in u -> q0 + q1 u + q2 u^2
                for (int c = 0; c < 3; c++) {
                    double[] k = P[c];
                    q[c * 3] = grad ? k[0] + k[2] * v + k[5] * v * v : 0;
                    q[c * 3 + 1] = grad ? k[1] + k[4] * v : 0;
                    q[c * 3 + 2] = grad ? k[3] : 0;
                }
                // per row: 1 / clip level of each channel at the grid columns
                float gyf = (float) ((y + 0.5) / a.ch * (CGY - 1));
                int gj = Math.min(CGY - 2, Math.max(0, (int) gyf));
                float gt = Math.min(1f, Math.max(0f, gyf - gj));
                for (int c = 0; c < 3; c++) {
                    for (int i = 0; i < CGX; i++) {
                        float g0 = a.clipInv[(c * CGY + gj) * CGX + i], g1 = a.clipInv[(c * CGY + gj + 1) * CGX + i];
                        ic[c * CGX + i] = g0 + gt * (g1 - g0);
                    }
                }
                float gxs = (CGX - 1f) / w;
                for (int x = 0; x < w; x++) {
                    float cr = img.r[src + x], cg = img.g[src + x], cb = img.b[src + x];
                    float fx = (x + 0.5f) * gxs;
                    int gi = Math.min(CGX - 2, (int) fx);
                    float t = fx - gi;
                    float nr0 = cr * (ic[gi] + t * (ic[gi + 1] - ic[gi]));
                    float ng0 = cg * (ic[CGX + gi] + t * (ic[CGX + gi + 1] - ic[CGX + gi]));
                    float nb0 = cb * (ic[2 * CGX + gi] + t * (ic[2 * CGX + gi + 1] - ic[2 * CGX + gi]));
                    float cn = Math.max(nr0, Math.max(ng0, nb0));
                    if (flags != null) {
                        CN[o + x] = Math.max(smooth(cn, 0.90f, 0.98f), smooth((flags[src + x] & 0xFF) * (1f / 255f), 0.02f, 0.25f));
                    } else {
                        CN[o + x] = smooth(cn, 0.80f, 0.95f);
                    }
                    float r = m[0] * cr + m[1] * cg + m[2] * cb;
                    float g = m[3] * cr + m[4] * cg + m[5] * cb;
                    float b = m[6] * cr + m[7] * cg + m[8] * cb;
                    double u = ((x + 0.5) / w) * 2 - 1;
                    r -= (float) (q[0] + u * (q[1] + u * q[2]));
                    g -= (float) (q[3] + u * (q[4] + u * q[5]));
                    b -= (float) (q[6] + u * (q[7] + u * q[8]));
                    float l = KR * r + KG * g + KB * b;
                    Y[o + x] = l;
                    CR[o + x] = r - l;
                    CB[o + x] = b - l;
                }
            }
            int n = re - rs;
            // Colour weight: 0 at and next to clipped samples (green clips first, so clipped star cores and
            // their interpolated neighbours turn magenta). Without per-frame clip flags only the stacked level
            // is known, which misses frames where an undersampled star peaked higher: dilate further then.
            maxFilter(CN, T1, HW, n, flags != null ? 1 : 3);
            for (int i = 0, e = n * w; i < e; i++) {
                float hw = 1f - HW[i];
                HW[i] = hw;
                CR[i] *= hw;
                CB[i] *= hw;
                YC[i] = hw * Y[i];
            }
            // Colour of the neighbourhood: window-weighted sums of chroma and of luminance, i.e. a star's
            // colour from its whole image. Per-pixel colour is unreliable at star scale: each CFA plane is
            // interpolated separately, so undersampled stars come out with red/blue fringes.
            gaussBlur(CR, T1, n);
            gaussBlur(CB, T2, n);
            gaussBlur(YC, T3, n);
            gaussBlur(HW, TH, n);
            short[] dy = dn != null ? dn.dy : null;
            float nrAmt = p.lumaNr;

            float inv = 1f / noise;
            float axR = a.axisR, axB = a.axisB;
            float nS2 = sq(a.noiseS * noiseScale), nP2 = sq(a.noiseP * noiseScale);
            // the punchy look's colour comes with its contrast
            float sat = curve.look == Params.PUNCHY ? p.saturation * 1.25f : p.saturation;
            float toe = -0.5f * curve.bp;
            boolean plainCurve = curve.look == Params.NATURAL && curve.black == 0f;
            int seed = y0 * 7919 + 17;
            for (int y = y0; y < y1; y++) {
                int o = (y - rs) * w;
                int oo = (y - y0) * w;
                for (int x = 0; x < w; x++) {
                    int i = o + x;
                    float l = Y[i];
                    // the sky's noise taken out (the stars keep their light: the correction is the starless sky's)
                    if (dy != null) l += nrAmt * Half.toFloat(dy[y * w + x]);
                    // Colour as ratios to the luminance (never absolute chroma, which would spill a star's
                    // colour onto dark neighbours), from the window; clipped samples carry no weight.
                    float th = TH[i];
                    float qr = 0f, qb = 0f, invL2 = 1e12f;
                    if (th > 0.02f) {
                        // the window's luminance sum has noise sigma * sqrt(GK2 * th); no colour without signal
                        float nv = GK2 * th;
                        float sw = noise * (float) Math.sqrt(nv);
                        float sl = T3[i];
                        float has = smooth(sl, 2f * sw, 4f * sw);
                        if (has > 0f) {
                            float is = has / sl;
                            qr = T1[i] * is;
                            qb = T2[i] * is;
                            invL2 = nv / (sl * sl);
                        }
                    }
                    // Stars are near-blackbodies: shrink the chroma towards white along the blackbody locus
                    // and much harder across it, each by its own noise, so faint stars are not given random
                    // green or purple tints.
                    float cs = axR * qr + axB * qb, cp = -axB * qr + axR * qb;
                    float res = cp - locusAt(a, cs);
                    cs *= shrink(cs, nS2 * invL2, 3f);
                    res *= shrink(res, nP2 * invL2, 9f);
                    cp = locusAt(a, cs) + res;
                    qr = axR * cs - axB * cp;
                    qb = axB * cs + axR * cp;
                    float rr = clampf(1f + qr, 0.02f, 4f), rb = clampf(1f + qb, 0.02f, 4f);
                    float rg = clampf((1f - KR * rr - KB * rb) * (1f / KG), 0.02f, 4f);
                    // display-encoded colour factors (square root ~ display gamma), unit luma
                    float fr = (float) Math.sqrt(rr), fg = (float) Math.sqrt(rg), fb = (float) Math.sqrt(rb);
                    float fl = 1f / (KR * fr + KG * fg + KB * fb);
                    // colour strength follows the real signal, not the background pedestal
                    float gs = sat * smooth(l * inv, 1f, 5f);
                    fr = Math.max(0f, 1f + gs * (fr * fl - 1f));
                    fg = Math.max(0f, 1f + gs * (fg * fl - 1f));
                    fb = Math.max(0f, 1f + gs * (fb * fl - 1f));
                    // asinh stretch of the luminance (the exposure first), soft toe below the black point
                    float I = curve.gain == 1f ? l - curve.bp : l * curve.gain - curve.bp;
                    if (I < toe) I = toe * (float) Math.exp(I / toe - 1f);
                    float F = plainCurve ? asinhf(I / curve.s) * curve.norm : curve.display(I);
                    // bright cores: desaturate just enough to fit, keeping their brightness
                    float fmax = Math.max(fr, Math.max(fg, fb));
                    if (fmax > 1f && F * fmax > 1f) {
                        float t = Math.max(0f, (1f / F - 1f) / (fmax - 1f));
                        fr = 1f + t * (fr - 1f);
                        fg = 1f + t * (fg - 1f);
                        fb = 1f + t * (fb - 1f);
                    }
                    // triangular dither, deterministic hash
                    int h1 = hash(seed + (y * 0x9E3779B1) + x * 0x85EBCA77);
                    int h2 = hash(h1 ^ 0x27D4EB2F);
                    float dith = ((h1 & 0xFFFF) + (h2 & 0xFFFF)) * (1f / 65536f) - 1f;
                    argb[oo + x] = 0xFF000000 | (q8(F * fr, dith) << 16) | (q8(F * fg, dith) << 8) | q8(F * fb, dith);
                }
            }
        }

        private static float clampf(float v, float lo, float hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }

        private static float sq(float v) {
            return v * v;
        }

        /** Positive-part James-Stein factor for an estimate x with noise variance v. */
        private static float shrink(float x, float v, float k) {
            float x2 = x * x;
            return x2 > k * v ? 1f - k * v / x2 : 0f;
        }

        private static int q8(float v, float dith) {
            int q = (int) Math.floor(v * 255f + 0.5f + dith * 0.5f);
            return q < 0 ? 0 : (q > 255 ? 255 : q);
        }

        private static int hash(int x) {
            x ^= x >>> 16;
            x *= 0x7FEB352D;
            x ^= x >>> 15;
            x *= 0x846CA68B;
            x ^= x >>> 16;
            return x;
        }

        /** Separable max filter of radius r over n rows; tmp is scratch. */
        private void maxFilter(float[] src, float[] tmp, float[] dst, int n, int r) {
            for (int y = 0; y < n; y++) {
                int o = y * w;
                for (int x = 0; x < w; x++) {
                    float mx = src[o + x];
                    for (int xx = Math.max(0, x - r), xe = Math.min(w - 1, x + r); xx <= xe; xx++) {
                        if (src[o + xx] > mx) mx = src[o + xx];
                    }
                    tmp[o + x] = mx;
                }
            }
            for (int y = 0; y < n; y++) {
                int ya = Math.max(0, y - r), yb = Math.min(n - 1, y + r);
                int o = y * w;
                for (int x = 0; x < w; x++) {
                    float mx = tmp[o + x];
                    for (int yy = ya; yy <= yb; yy++) {
                        float vv = tmp[yy * w + x];
                        if (vv > mx) mx = vv;
                    }
                    dst[o + x] = mx;
                }
            }
        }

        /** Separable Gaussian window (GK) over n rows; edges renormalize over the available pixels. */
        private void gaussBlur(float[] src, float[] dst, int n) {
            float[] k = GK;
            int r = GR;
            for (int y = 0; y < n; y++) {
                int o = y * w;
                for (int x = 0; x < w; x++) {
                    float acc = 0;
                    if (x >= r && x < w - r) {
                        for (int j = -r; j <= r; j++) acc += k[j + r] * src[o + x + j];
                    } else {
                        float ks = 0;
                        for (int j = Math.max(-r, -x); j <= Math.min(r, w - 1 - x); j++) {
                            acc += k[j + r] * src[o + x + j];
                            ks += k[j + r];
                        }
                        acc /= ks;
                    }
                    TMP[o + x] = acc;
                }
            }
            for (int y = 0; y < n; y++) {
                int o = y * w;
                int ja = Math.max(-r, -y), jb = Math.min(r, n - 1 - y);
                float ks = 0;
                for (int j = ja; j <= jb; j++) ks += k[j + r];
                float inv = 1f / ks;
                for (int x = 0; x < w; x++) {
                    float acc = 0;
                    for (int j = ja; j <= jb; j++) acc += k[j + r] * TMP[o + j * w + x];
                    dst[o + x] = acc * inv;
                }
            }
        }
    }
}
