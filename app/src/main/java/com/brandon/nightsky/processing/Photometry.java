package com.brandon.nightsky.processing;

/**
 * Aperture photometry on a RAW unit: the flux of a star is the sum of all CFA pixels within a small
 * radius, minus the per-plane median of a surrounding annulus. Comparing the same stars between frames
 * measures how much light got through (clouds, haze, dew) even when a frame has too few detectable stars
 * to align on its own.
 */
public final class Photometry {
    public float rAp = 3.5f, rIn = 6.5f, rOut = 10.5f;
    private final int W, H;

    public Photometry(int width, int height) {
        W = width;
        H = height;
    }

    /**
     * @param satLevel per-frame ADU above black at which a pixel counts as saturated
     * @return flux per position, NaN where it can't be measured (edge, saturated, hot pixel in aperture)
     */
    public float[] measure(final short[] raw, final float invScale, final float[] black, final float[] xs,
                           final float[] ys, final int n, final HotPixels hot, final float satLevel) {
        final float[] out = new float[n];
        final int ro = (int) Math.ceil(rOut);
        Parallel.forRange(n, 16, new Parallel.Range() {
            @Override
            public void run(int a, int b) {
                float[][] ann = new float[4][(2 * ro + 1) * (2 * ro + 1) / 2 + 8];
                int[] na = new int[4];
                for (int k = a; k < b; k++) {
                    float cx = xs[k], cy = ys[k];
                    if (!(cx - ro - 1 >= 0 && cy - ro - 1 >= 0 && cx + ro + 1 < W && cy + ro + 1 < H)) {
                        out[k] = Float.NaN;
                        continue;
                    }
                    na[0] = na[1] = na[2] = na[3] = 0;
                    int x0 = (int) Math.floor(cx - ro), x1 = (int) Math.ceil(cx + ro);
                    int y0 = (int) Math.floor(cy - ro), y1 = (int) Math.ceil(cy + ro);
                    // annulus medians per plane
                    for (int y = y0; y <= y1; y++) {
                        float dy = y + 0.5f - cy;
                        for (int x = x0; x <= x1; x++) {
                            float dx = x + 0.5f - cx;
                            float r2 = dx * dx + dy * dy;
                            if (r2 < rIn * rIn || r2 > rOut * rOut) continue;
                            if (hot != null && hot.isBad(x, y)) continue;
                            int p = ((y & 1) << 1) | (x & 1);
                            if (na[p] < ann[p].length) ann[p][na[p]++] = (raw[y * W + x] & 0xFFFF) * invScale - black[p];
                        }
                    }
                    float[] bg = new float[4];
                    boolean okBg = true;
                    for (int p = 0; p < 4; p++) {
                        if (na[p] < 5) {
                            okBg = false;
                            break;
                        }
                        bg[p] = Stats.median(ann[p], na[p]);
                    }
                    if (!okBg) {
                        out[k] = Float.NaN;
                        continue;
                    }
                    double flux = 0;
                    boolean bad = false;
                    int ra = (int) Math.ceil(rAp);
                    for (int y = (int) Math.floor(cy - ra); y <= (int) Math.ceil(cy + ra) && !bad; y++) {
                        float dy = y + 0.5f - cy;
                        for (int x = (int) Math.floor(cx - ra); x <= (int) Math.ceil(cx + ra); x++) {
                            float dx = x + 0.5f - cx;
                            if (dx * dx + dy * dy > rAp * rAp) continue;
                            if (hot != null && hot.isBad(x, y)) {
                                bad = true;
                                break;
                            }
                            int p = ((y & 1) << 1) | (x & 1);
                            float v = (raw[y * W + x] & 0xFFFF) * invScale - black[p];
                            if (v >= satLevel) {
                                bad = true;
                                break;
                            }
                            flux += v - bg[p];
                        }
                    }
                    out[k] = bad ? Float.NaN : (float) flux;
                }
            }
        });
        return out;
    }
}
