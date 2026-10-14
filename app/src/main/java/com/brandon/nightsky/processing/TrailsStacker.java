package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.List;

/**
 * Star-trail composite without alignment.
 * <p>
 * Each photosite keeps its brightest moment over the session ("lighten"), but judged on a colour-flattened
 * brightness: the white-balanced mosaic divided by the local colour of its own CFA colour, so a red star
 * does not make red photosites win and blue ones lose. The trail is that brightest value's excess over the
 * average of the other frames (low noise), through a soft threshold so the background noise isn't lifted
 * the way a plain lighten blend lifts it.
 * <p>
 * Trail colour does not come from the winning photosite itself (an undersampled star lights R, G and B
 * photosites very unevenly depending on where it falls, which makes trails flicker in colour along their
 * length). It comes from the colour of the light in a small window around the photosite in the winning
 * frame, minus the background: that window holds the whole star image, so its colour is the star's.
 * The background is the demosaiced average of the other frames. Lens shading is applied at the end.
 */
public final class TrailsStacker {
    public static final class Unit {
        public int unit;
        public float invScale = 1f;
        public final float[] black = new float[4];
    }

    /** Colour window radius (5x5): holds a whole star image. */
    private static final int CRAD = 2;

    private final SensorSpec spec;
    private final FrameStore store;
    private final HotPixels hot;
    private final int W, H;
    public int bandRows = 128;

    public TrailsStacker(SensorSpec spec, FrameStore store, HotPixels hot) {
        this.spec = spec;
        this.store = store;
        this.hot = hot;
        W = spec.width;
        H = spec.height;
    }

    public RgbImage stack(List<Unit> units, Progress progress) throws IOException {
        final int n = units.size();
        final int[] cfa = spec.cfa;
        // white balance per plane: a neutral source gives equal values in every colour
        final float[] neutral = new float[4];
        for (int p = 0; p < 4; p++) neutral[p] = Math.max(0.05f, spec.camNeutral[cfa[p]] / Math.max(0.05f, spec.camNeutral[1]));
        final float clipLevel = spec.white - Math.max(3f, 0.01f * spec.range());
        // the brightest few frames of each pixel are left out of its background statistics: a star covers a
        // pixel for a few frames as it drifts past
        final int kx = n >= 4 ? Math.min(3, n - 3) : 0;
        final float sdCorr = kx > 0 ? truncatedSd(kx / (float) n) : 1f;
        final float k = (float) Math.max(3.0, Math.sqrt(2 * Math.log(Math.max(2, n))) + 1.0);

        final RgbImage out = new RgbImage(W, H);
        final float[] bgMosaic = new float[W * H];
        final byte[] clipped = new byte[W * H];

        final int rowsMax = Math.min(bandRows, H) + 2 * CRAD;
        final int np = rowsMax * W;
        // per-pixel accumulators over the band plus its margin rows: luminance statistics, the three brightest
        // luminances with the mosaic value at each, and the local colour at the brightest
        final float[] meanY = new float[np], m2Y = new float[np], sumM = new float[np];
        final float[] y1 = new float[np], y2 = new float[np], y3 = new float[np];
        final float[] v1 = new float[np], v2 = new float[np], v3 = new float[np];
        final float[] cMax0 = new float[np], cMax1 = new float[np], cMax2 = new float[np];
        // sharp (gradient-corrected) luminance at the brightest frame
        final float[] sharpMax = new float[np];
        // per frame
        final short[] raw = new short[np];
        final float[] m = new float[np], lum = new float[np], tmp = new float[np];
        final float[][] csum = {new float[np], new float[np], new float[np]};
        final float[] sdPix = new float[Math.min(bandRows, H) * W];

        int bands = (H + bandRows - 1) / bandRows;
        for (int b = 0; b < bands; b++) {
            final int y0 = b * bandRows, y1r = Math.min(H, y0 + bandRows);
            final int ra = Math.max(0, y0 - CRAD), rb = Math.min(H, y1r + CRAD);
            final int nr = rb - ra;
            final int npx = nr * W;
            java.util.Arrays.fill(meanY, 0, npx, 0f);
            java.util.Arrays.fill(m2Y, 0, npx, 0f);
            java.util.Arrays.fill(sumM, 0, npx, 0f);
            java.util.Arrays.fill(y1, 0, npx, -Float.MAX_VALUE);
            java.util.Arrays.fill(y2, 0, npx, -Float.MAX_VALUE);
            java.util.Arrays.fill(y3, 0, npx, -Float.MAX_VALUE);
            for (int f = 0; f < n; f++) {
                final Unit u = units.get(f);
                final float inv = 1f / (f + 1);
                store.readBand(u.unit, ra, rb, raw);
                if (hot != null) hot.fix(raw, ra, rb, ra);
                // white-balanced mosaic, clip flags
                Parallel.forRange(nr, 8, new Parallel.Range() {
                    @Override
                    public void run(int j0, int j1) {
                        for (int j = j0; j < j1; j++) {
                            int y = ra + j;
                            int py = (y & 1) << 1;
                            int o = j * W;
                            boolean own = y >= y0 && y < y1r;
                            for (int x = 0; x < W; x++) {
                                int p = py | (x & 1);
                                float rv = (raw[o + x] & 0xFFFF) * u.invScale;
                                if (own && rv >= clipLevel) clipped[y * W + x] = 1;
                                m[o + x] = (rv - u.black[p]) / neutral[p];
                            }
                        }
                    }
                });
                luminance(m, lum, tmp, nr);
                colourSums(m, csum, tmp, ra, nr);
                // running statistics, the brightest frames and the colour around the brightest
                Parallel.forRange(nr, 8, new Parallel.Range() {
                    @Override
                    public void run(int j0, int j1) {
                        float[] c = new float[3];
                        for (int j = j0; j < j1; j++) {
                            int y = ra + j;
                            int o = j * W;
                            for (int x = 0; x < W; x++) {
                                int i = o + x;
                                float l = lum[i];
                                float v = m[i];
                                float d = l - meanY[i];
                                meanY[i] += d * inv;
                                m2Y[i] += d * (l - meanY[i]);
                                sumM[i] += v;
                                if (l <= y3[i]) continue;
                                if (l > y1[i]) {
                                    y3[i] = y2[i];
                                    v3[i] = v2[i];
                                    y2[i] = y1[i];
                                    v2[i] = v1[i];
                                    y1[i] = l;
                                    v1[i] = v;
                                    localColour(csum, i, x, y, c);
                                    cMax0[i] = c[0];
                                    cMax1[i] = c[1];
                                    cMax2[i] = c[2];
                                    sharpMax[i] = sharpLum(m, x, y, j, nr);
                                } else if (l > y2[i]) {
                                    y3[i] = y2[i];
                                    v3[i] = v2[i];
                                    y2[i] = l;
                                    v2[i] = v;
                                } else {
                                    y3[i] = l;
                                    v3[i] = v;
                                }
                            }
                        }
                    }
                });
                if (progress.cancelled()) throw new CancelledException();
                progress.update("Building star trails", (b + (f + 1f) / n) / bands);
            }
            // background mosaic: the average without each pixel's brightest frames
            for (int i = 0; i < npx; i++) {
                float s = sumM[i];
                if (kx >= 1) s -= v1[i];
                if (kx >= 2) s -= v2[i];
                if (kx >= 3) s -= v3[i];
                m[i] = s / (n - kx);
            }
            colourSums(m, csum, tmp, ra, nr);
            if (kx > 0) {
                // luminance noise of each pixel over time, without its brightest frames; a star that crosses
                // a pixel slowly can still inflate it, so it is held near the local typical value
                final int own0 = (y0 - ra) * W;
                final int ownN = (y1r - y0) * W;
                Parallel.forRange(ownN, 4096, new Parallel.Range() {
                    @Override
                    public void run(int a0, int a1) {
                        for (int a = a0; a < a1; a++) {
                            int i = own0 + a;
                            sdPix[a] = sdCorr > 0 ? (float) Math.sqrt(Math.max(0f, robustM2(i)) / (n - kx - 1)) / sdCorr : 0f;
                        }
                    }

                    private float robustM2(int i) {
                        float cnt = n, mu = meanY[i], q = m2Y[i];
                        float[] top = {y1[i], y2[i], y3[i]};
                        for (int t = 0; t < kx; t++) {
                            float xv = top[t];
                            float mu2 = (cnt * mu - xv) / (cnt - 1);
                            q -= (xv - mu) * (xv - mu2);
                            mu = mu2;
                            cnt -= 1;
                        }
                        return q;
                    }
                });
                final float[] blk = blockMedians(sdPix, ownN / W);
                Parallel.forRange(y1r - y0, 8, new Parallel.Range() {
                    @Override
                    public void run(int j0, int j1) {
                        float[] c = new float[3];
                        for (int jj = j0; jj < j1; jj++) {
                            int y = y0 + jj;
                            int o = (y - ra) * W;
                            int oo = y * W;
                            for (int x = 0; x < W; x++) {
                                int i = o + x;
                                float typ = blockAt(blk, x);
                                float sd = clampf(sdPix[jj * W + x], 0.8f * typ, 1.5f * typ);
                                float mean = n * meanY[i] - y1[i];
                                if (kx >= 2) mean -= y2[i];
                                if (kx >= 3) mean -= y3[i];
                                mean /= n - kx;
                                float dl = y1[i] - mean;
                                if (dl <= 0 || sd <= 0) continue;
                                float uu = dl / (k * sd);
                                float u4 = uu * uu * uu * uu;
                                float w = u4 / (1f + u4);
                                // bright trails take the sharp luminance, faint ones the smooth one, which
                                // has half the noise
                                float snr = dl / sd;
                                float a = smooth(snr, 10f, 30f);
                                if (a > 0f) {
                                    float ds = sharpMax[i] - sharpLum(m, x, y, y - ra, nr);
                                    dl += a * (ds - dl);
                                    if (dl <= 0) continue;
                                }
                                float trail = dl * w;
                                // colour of the light the star added around here, against the background
                                localColour(csum, i, x, y, c);
                                float e0 = Math.max(0f, cMax0[i] - c[0]);
                                float e1 = Math.max(0f, cMax1[i] - c[1]);
                                float e2 = Math.max(0f, cMax2[i] - c[2]);
                                float ea = 0.25f * (e0 + 2f * e1 + e2);
                                float r0 = 1f, r1 = 1f, r2 = 1f;
                                if (ea > 1e-3f) {
                                    r0 = Math.min(4f, e0 / ea);
                                    r1 = Math.min(4f, e1 / ea);
                                    r2 = Math.min(4f, e2 / ea);
                                }
                                out.r[oo + x] = trail * r0 * spec.camNeutral[0] / spec.camNeutral[1];
                                out.g[oo + x] = trail * r1;
                                out.b[oo + x] = trail * r2 * spec.camNeutral[2] / spec.camNeutral[1];
                            }
                        }
                    }
                });
            }
            for (int y = y0; y < y1r; y++) {
                int o = (y - ra) * W, oo = y * W, py = (y & 1) << 1;
                for (int x = 0; x < W; x++) bgMosaic[oo + x] = m[o + x] * neutral[py | (x & 1)];
            }
        }
        progress.update("Demosaicing", 1f);
        Demosaic.mhcAdd(bgMosaic, W, H, cfa, out);
        applyShading(out);
        out.clip = spreadClip(clipped);
        return out;
    }

    /** Column blocks over which the typical luminance noise is taken. */
    private static final int BLOCK = 64;

    /** Median of each column block of rows x W values. */
    private float[] blockMedians(final float[] v, final int rows) {
        final int nb = (W + BLOCK - 1) / BLOCK;
        final float[] med = new float[nb];
        Parallel.forRange(nb, 1, new Parallel.Range() {
            @Override
            public void run(int b0, int b1) {
                float[] s = new float[rows * BLOCK];
                for (int bk = b0; bk < b1; bk++) {
                    int xa = bk * BLOCK, xb = Math.min(W, xa + BLOCK);
                    int c = 0;
                    for (int j = 0; j < rows; j++) {
                        int o = j * W;
                        for (int x = xa; x < xb; x++) s[c++] = v[o + x];
                    }
                    med[bk] = c > 0 ? Stats.select(s, 0, c - 1, c / 2) : 0f;
                }
            }
        });
        return med;
    }

    /** Typical value at column x, interpolated between block centres. */
    private static float blockAt(float[] blk, int x) {
        float fx = (x + 0.5f) / BLOCK - 0.5f;
        if (fx <= 0) return blk[0];
        int i = (int) fx;
        if (i >= blk.length - 1) return blk[blk.length - 1];
        float t = fx - i;
        return blk[i] + (blk[i + 1] - blk[i]) * t;
    }

    /**
     * Standard deviation of a unit normal with its top fraction q cut off: how much leaving out the brightest
     * frames shrinks the spread of pure noise.
     */
    static float truncatedSd(double q) {
        if (q <= 0) return 1f;
        // z with upper tail q, by bisection on the normal tail
        double lo = -8, hi = 8;
        for (int it = 0; it < 80; it++) {
            double mid = 0.5 * (lo + hi);
            if (upperTail(mid) > q) lo = mid;
            else hi = mid;
        }
        double z = 0.5 * (lo + hi);
        double phi = Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI);
        double cdf = 1 - q;
        double r = phi / cdf;
        double var = 1 - z * r - r * r;
        return (float) Math.sqrt(Math.max(0.05, var));
    }

    private static double upperTail(double z) {
        return 0.5 * erfc(z / Math.sqrt(2));
    }

    /** Complementary error function (Numerical Recipes erfcc, relative error below 1.2e-7). */
    private static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1 / (1 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? r : 2 - r;
    }

    /**
     * Luminance of the white-balanced mosaic: a [1 2 1] x [1 2 1] / 16 blur, which at every site of a Bayer
     * mosaic weighs red, green and blue 1 : 2 : 1, so a star of any colour lights every pixel it covers.
     * Rows beyond the band are mirrored (same colour row).
     */
    private void luminance(final float[] m, final float[] lum, final float[] tmp, final int nr) {
        Parallel.forRange(nr, 16, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int j = j0; j < j1; j++) {
                    int o = j * W;
                    for (int x = 0; x < W; x++) {
                        int xl = x > 0 ? x - 1 : 1, xr = x < W - 1 ? x + 1 : W - 2;
                        tmp[o + x] = m[o + xl] + 2f * m[o + x] + m[o + xr];
                    }
                }
            }
        });
        Parallel.forRange(nr, 16, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int j = j0; j < j1; j++) {
                    int o = j * W;
                    int ou = (j > 0 ? j - 1 : Math.min(1, nr - 1)) * W;
                    int od = (j < nr - 1 ? j + 1 : Math.max(0, nr - 2)) * W;
                    for (int x = 0; x < W; x++) lum[o + x] = (tmp[ou + x] + 2f * tmp[o + x] + tmp[od + x]) * 0.0625f;
                }
            }
        });
    }

    private static float smooth(float v, float a, float b) {
        if (v <= a) return 0f;
        if (v >= b) return 1f;
        float t = (v - a) / (b - a);
        return t * t * (3f - 2f * t);
    }

    /**
     * Luminance (R + 2G + B) / 4 of the Malvar-He-Cutler demosaic at pixel x of band row j (image row y):
     * nearly the photosite itself, so it keeps trails thin, at about twice the noise of {@link #luminance}.
     * Rows outside the band are mirrored.
     */
    private float sharpLum(float[] m, int x, int y, int j, int nr) {
        int p = ((y & 1) << 1) | (x & 1);
        float c, n1, n2, diag, h2 = 0, v2 = 0, hn = 0, vn = 0;
        boolean g = spec.cfa[p] == 1;
        if (j >= 2 && j < nr - 2 && x >= 2 && x < W - 2) {
            int i = j * W + x;
            c = m[i];
            diag = m[i - W - 1] + m[i - W + 1] + m[i + W - 1] + m[i + W + 1];
            hn = m[i - 1] + m[i + 1];
            vn = m[i - W] + m[i + W];
            h2 = m[i - 2] + m[i + 2];
            v2 = m[i - 2 * W] + m[i + 2 * W];
        } else {
            c = at(m, x, j, nr);
            diag = at(m, x - 1, j - 1, nr) + at(m, x + 1, j - 1, nr) + at(m, x - 1, j + 1, nr) + at(m, x + 1, j + 1, nr);
            hn = at(m, x - 1, j, nr) + at(m, x + 1, j, nr);
            vn = at(m, x, j - 1, nr) + at(m, x, j + 1, nr);
            h2 = at(m, x - 2, j, nr) + at(m, x + 2, j, nr);
            v2 = at(m, x, j - 2, nr) + at(m, x, j + 2, nr);
        }
        n1 = hn + vn;
        n2 = h2 + v2;
        if (g) return 0.5f * c + (10f * c - 2f * diag - 0.5f * n2 + 4f * n1) * 0.03125f;
        return 0.25f * c + (4f * c + 2f * n1 - n2) * 0.0625f + (6f * c + 2f * diag - 1.5f * n2) * 0.03125f;
    }

    private float at(float[] m, int x, int j, int nr) {
        if (x < 0) x = -x;
        if (x >= W) x = 2 * (W - 1) - x;
        if (j < 0) j = -j;
        if (j >= nr) j = 2 * (nr - 1) - j;
        x = Math.max(0, Math.min(W - 1, x));
        j = Math.max(0, Math.min(nr - 1, j));
        return m[j * W + x];
    }

    private static float clampf(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Box sums (radius CRAD) of each colour's photosites: csum[c] over band rows [ra, ra + nr). Columns are
     * summed first per colour (zero at other colours), then rows.
     */
    private void colourSums(final float[] m, final float[][] csum, final float[] tmp, final int ra, final int nr) {
        final int[] cfa = spec.cfa;
        for (int c = 0; c < 3; c++) {
            final int cc = c;
            final float[] dst = csum[c];
            // horizontal: sparse plane of colour cc
            Parallel.forRange(nr, 16, new Parallel.Range() {
                @Override
                public void run(int j0, int j1) {
                    for (int j = j0; j < j1; j++) {
                        int y = ra + j;
                        int py = (y & 1) << 1;
                        int o = j * W;
                        boolean e = cfa[py] == cc, od = cfa[py | 1] == cc;
                        if (!e && !od) {
                            java.util.Arrays.fill(tmp, o, o + W, 0f);
                            continue;
                        }
                        float acc = 0;
                        for (int x = 0; x <= Math.min(CRAD, W - 1); x++) if (((x & 1) == 0 ? e : od)) acc += m[o + x];
                        for (int x = 0; x < W; x++) {
                            tmp[o + x] = acc;
                            int add = x + CRAD + 1, rem = x - CRAD;
                            if (add < W && ((add & 1) == 0 ? e : od)) acc += m[o + add];
                            if (rem >= 0 && ((rem & 1) == 0 ? e : od)) acc -= m[o + rem];
                        }
                    }
                }
            });
            // vertical
            Parallel.forRange(W, 64, new Parallel.Range() {
                @Override
                public void run(int x0, int x1) {
                    for (int x = x0; x < x1; x++) {
                        float acc = 0;
                        for (int j = 0; j <= Math.min(CRAD, nr - 1); j++) acc += tmp[j * W + x];
                        for (int j = 0; j < nr; j++) {
                            dst[j * W + x] = acc;
                            int add = j + CRAD + 1, rem = j - CRAD;
                            if (add < nr) acc += tmp[add * W + x];
                            if (rem >= 0) acc -= tmp[rem * W + x];
                        }
                    }
                }
            });
        }
    }

    /** Mean of each colour's photosites in the window around band index i (pixel x, y), into c[0..2]. */
    private void localColour(float[][] csum, int i, int x, int y, float[] c) {
        int[] cfa = spec.cfa;
        int xa = Math.max(0, x - CRAD), xb = Math.min(W - 1, x + CRAD);
        int ya = Math.max(0, y - CRAD), yb = Math.min(H - 1, y + CRAD);
        // photosites of each parity in the window, per axis
        int ce = count(xa, xb, 0), co = count(xa, xb, 1);
        int re = count(ya, yb, 0), ro = count(ya, yb, 1);
        int n0 = 0, n1 = 0, n2 = 0;
        for (int p = 0; p < 4; p++) {
            int k = ((p & 2) == 0 ? re : ro) * ((p & 1) == 0 ? ce : co);
            int q = cfa[p];
            if (q == 0) n0 += k;
            else if (q == 1) n1 += k;
            else n2 += k;
        }
        c[0] = n0 > 0 ? csum[0][i] / n0 : 0f;
        c[1] = n1 > 0 ? csum[1][i] / n1 : 0f;
        c[2] = n2 > 0 ? csum[2][i] / n2 : 0f;
    }

    private static int count(int a, int b, int parity) {
        int first = (a & 1) == parity ? a : a + 1;
        return first > b ? 0 : (b - first) / 2 + 1;
    }

    /** Output pixels whose demosaic neighbourhood (3x3) holds a clipped sample. */
    private byte[] spreadClip(final byte[] c) {
        final byte[] o = new byte[W * H];
        Parallel.forRange(H, 16, new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                for (int y = y0; y < y1; y++) {
                    for (int x = 0; x < W; x++) {
                        int v = 0;
                        for (int yy = Math.max(0, y - 1); yy <= Math.min(H - 1, y + 1) && v == 0; yy++) {
                            for (int xx = Math.max(0, x - 1); xx <= Math.min(W - 1, x + 1); xx++) {
                                if (c[yy * W + xx] != 0) {
                                    v = 255;
                                    break;
                                }
                            }
                        }
                        o[y * W + x] = (byte) v;
                    }
                }
            }
        });
        return o;
    }

    /** Lens shading (vignetting and colour shading) correction of the finished image. */
    private void applyShading(final RgbImage img) {
        final ShadingMap sh = spec.shading;
        if (sh == null || sh.isUnity()) return;
        int r = -1, g1 = -1, g2 = -1, b = -1;
        for (int p = 0; p < 4; p++) {
            int c = spec.cfa[p];
            if (c == 0 && r < 0) r = p;
            else if (c == 2 && b < 0) b = p;
            else if (c == 1) {
                if (g1 < 0) g1 = p;
                else g2 = p;
            }
        }
        final int pR = r < 0 ? 0 : r, pB = b < 0 ? 3 : b, pG1 = g1 < 0 ? 1 : g1, pG2 = g2 < 0 ? pG1 : g2;
        final int step = 16;
        final int nxs = W / step + 2;
        Parallel.forRange(H, 8, new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                float[] gr = new float[nxs], gg = new float[nxs], gb = new float[nxs];
                for (int y = y0; y < y1; y++) {
                    double yc = y + 0.5;
                    for (int i = 0; i < nxs; i++) {
                        double xc = i * step;
                        gr[i] = sh.gain(pR, xc, yc);
                        gg[i] = 0.5f * (sh.gain(pG1, xc, yc) + sh.gain(pG2, xc, yc));
                        gb[i] = sh.gain(pB, xc, yc);
                    }
                    int o = y * W;
                    for (int x = 0; x < W; x++) {
                        float fx = (x + 0.5f) / step;
                        int i = (int) fx;
                        float t = fx - i;
                        img.r[o + x] *= gr[i] + (gr[i + 1] - gr[i]) * t;
                        img.g[o + x] *= gg[i] + (gg[i + 1] - gg[i]) * t;
                        img.b[o + x] *= gb[i] + (gb[i + 1] - gb[i]) * t;
                    }
                }
            }
        });
    }
}
