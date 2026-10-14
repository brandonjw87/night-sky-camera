package com.brandon.nightsky.processing;

/**
 * The smooth sky background of one frame on a coarse grid of regions, per colour (R, G, B), with its noise.
 * A region's level is the clipped mean of its pixels (stars, hot pixels and other outliers left out by
 * clipping at 3 sigma), its noise the clipped standard deviation. Values between region centres are
 * interpolated bilinearly, so the model has no steps.
 * <p>
 * Faint starlight that survives the clipping doesn't matter for normalisation: it dims with the clouds
 * exactly like the stars do, so it cancels when frames are matched (see {@link SharpStacker}).
 */
public final class BackgroundGrid {
    public final int gx, gy, width, height;
    /** [channel R, G, B][cell]: background level and noise (black-subtracted per-frame ADU). */
    public final float[][] level, sigma;

    public BackgroundGrid(int gx, int gy, int width, int height) {
        this.gx = gx;
        this.gy = gy;
        this.width = width;
        this.height = height;
        level = new float[3][gx * gy];
        sigma = new float[3][gx * gy];
    }

    /**
     * Measures a RAW unit.
     *
     * @param cfa colour of each of the four CFA positions (0 = R, 1 = G, 2 = B)
     * @param hot defective pixels to leave out, may be null
     */
    public static BackgroundGrid measure(final short[] raw, final float invScale, final float[] black, final int[] cfa,
                                         final HotPixels hot, final int gx, final int gy, final int W, final int H) {
        final BackgroundGrid g = new BackgroundGrid(gx, gy, W, H);
        Parallel.forRange(gx * gy, 1, new Parallel.Range() {
            @Override
            public void run(int c0, int c1) {
                float[][] vals = null;
                float[] scratch = null;
                float[] est = new float[2];
                for (int cell = c0; cell < c1; cell++) {
                    int cx = cell % gx, cy = cell / gx;
                    int x0 = (cx * W / gx) & ~1, x1 = ((cx + 1) * W / gx) & ~1;
                    int y0 = (cy * H / gy) & ~1, y1 = ((cy + 1) * H / gy) & ~1;
                    if (cx == gx - 1) x1 = W & ~1;
                    if (cy == gy - 1) y1 = H & ~1;
                    // every other 2x2 block in both directions: ~16k samples of R and B, ~32k of G per region
                    int cap = ((x1 - x0) / 4 + 1) * ((y1 - y0) / 4 + 1) * 2 + 8;
                    if (vals == null || vals[0].length < cap) {
                        vals = new float[3][cap];
                        scratch = new float[cap];
                    }
                    int[] n = new int[3];
                    for (int y = y0; y < y1; y += 4) {
                        for (int x = x0; x < x1; x += 4) {
                            for (int p = 0; p < 4; p++) {
                                int xx = x + (p & 1), yy = y + (p >> 1);
                                if (hot != null && hot.isBad(xx, yy)) continue;
                                int c = cfa[p];
                                vals[c][n[c]++] = (raw[yy * W + xx] & 0xFFFF) * invScale - black[p];
                            }
                        }
                    }
                    for (int c = 0; c < 3; c++) {
                        clippedMean(vals[c], n[c], scratch, est);
                        g.level[c][cell] = est[0];
                        g.sigma[c][cell] = est[1];
                    }
                }
            }
        });
        return g;
    }

    /**
     * Clipped mean and standard deviation: start from the median and the MAD, then twice the mean and
     * standard deviation of the values within 3 sigma (corrected for the clipping).
     */
    static void clippedMean(float[] a, int n, float[] scratch, float[] out) {
        if (n == 0) {
            out[0] = 0f;
            out[1] = 1f;
            return;
        }
        System.arraycopy(a, 0, scratch, 0, n);
        float med = Stats.median(scratch, n);
        for (int i = 0; i < n; i++) scratch[i] = Math.abs(a[i] - med);
        double sig = 1.4826 * Stats.median(scratch, n);
        double c = med;
        if (!(sig > 0)) {
            // quantised data with tiny noise: plain standard deviation instead
            double s = 0, s2 = 0;
            for (int i = 0; i < n; i++) {
                s += a[i];
                s2 += (double) a[i] * a[i];
            }
            double m = s / n;
            sig = Math.sqrt(Math.max(1e-6, s2 / n - m * m));
        }
        for (int it = 0; it < 3; it++) {
            double lo = c - 3 * sig, hi = c + 3 * sig;
            double s = 0, s2 = 0;
            int m = 0;
            for (int i = 0; i < n; i++) {
                float v = a[i];
                if (v < lo || v > hi) continue;
                s += v;
                s2 += (double) v * v;
                m++;
            }
            if (m < 8) break;
            double mean = s / m;
            double var = Math.max(0, (s2 - s * mean) / (m - 1));
            c = mean;
            // a normal distribution cut at +-3 sigma keeps 98.66% of its standard deviation
            sig = Math.max(1e-3, Math.sqrt(var) / 0.9866);
        }
        out[0] = (float) c;
        out[1] = (float) sig;
    }

    public float level(int c, double x, double y) {
        return interp(level[c], x, y);
    }

    public float sigma(int c, double x, double y) {
        return interp(sigma[c], x, y);
    }

    /** Bilinear between cell centres, constant beyond the outer centres. (x, y) are continuous pixel coordinates. */
    public float interp(float[] v, double x, double y) {
        return interp(v, gx, gy, width, height, x, y);
    }

    static float interp(float[] v, int gx, int gy, int width, int height, double x, double y) {
        double fx = x * gx / width - 0.5, fy = y * gy / height - 0.5;
        int xa = (int) Math.floor(fx), ya = (int) Math.floor(fy);
        double tx = fx - xa, ty = fy - ya;
        if (xa < 0) {
            xa = 0;
            tx = 0;
        } else if (xa >= gx - 1) {
            xa = gx - 1;
            tx = 0;
        }
        if (ya < 0) {
            ya = 0;
            ty = 0;
        } else if (ya >= gy - 1) {
            ya = gy - 1;
            ty = 0;
        }
        int xb = Math.min(xa + 1, gx - 1), yb = Math.min(ya + 1, gy - 1);
        double top = v[ya * gx + xa] + (v[ya * gx + xb] - v[ya * gx + xa]) * tx;
        double bot = v[yb * gx + xa] + (v[yb * gx + xb] - v[yb * gx + xa]) * tx;
        return (float) (top + (bot - top) * ty);
    }

    /** Median level of channel c over the cells, for logs. */
    public float medianLevel(int c) {
        float[] t = level[c].clone();
        return Stats.median(t, t.length);
    }
}
