package com.brandon.nightsky.processing;

/**
 * Malvar-He-Cutler (gradient-corrected linear) demosaicing of a float CFA mosaic. Much cleaner star colours
 * than bilinear, and cheap.
 */
public final class Demosaic {
    private Demosaic() {}

    public static RgbImage mhc(final float[] m, final int W, final int H, final int[] cfa) {
        final RgbImage out = new RgbImage(W, H);
        run(m, W, H, cfa, out, false);
        return out;
    }

    /** Demosaics m and adds the result into out (same size), without allocating another image. */
    public static void mhcAdd(final float[] m, final int W, final int H, final int[] cfa, final RgbImage out) {
        run(m, W, H, cfa, out, true);
    }

    private static void run(final float[] m, final int W, final int H, final int[] cfa, final RgbImage out, final boolean add) {
        Parallel.forRange(H, 8, new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                float[] rgb = new float[3];
                float[] or = out.r, og = out.g, ob = out.b;
                for (int y = y0; y < y1; y++) {
                    boolean inner = y >= 2 && y < H - 2;
                    int py = (y & 1) << 1;
                    for (int x = 0; x < W; x++) {
                        int p = py | (x & 1);
                        int col = cfa[p];
                        int i = y * W + x;
                        if (inner && x >= 2 && x < W - 2) {
                            interior(m, W, i, col, cfa[p ^ 1], rgb);
                        } else {
                            border(m, W, H, x, y, col, cfa[p ^ 1], rgb);
                        }
                        if (add) {
                            or[i] += rgb[0];
                            og[i] += rgb[1];
                            ob[i] += rgb[2];
                        } else {
                            or[i] = rgb[0];
                            og[i] = rgb[1];
                            ob[i] = rgb[2];
                        }
                    }
                }
            }
        });
    }

    /**
     * @param col      colour at this site
     * @param rowOther colour of the horizontal neighbours (decides which way R and B lie at green sites)
     */
    private static void interior(float[] m, int W, int i, int col, int rowOther, float[] rgb) {
        float c = m[i];
        float diag = m[i - W - 1] + m[i - W + 1] + m[i + W - 1] + m[i + W + 1];
        if (col == 1) {
            float h2 = m[i - 2] + m[i + 2], v2 = m[i - 2 * W] + m[i + 2 * W];
            float hn = m[i - 1] + m[i + 1], vn = m[i - W] + m[i + W];
            green(c, diag, h2, v2, hn, vn, rowOther, rgb);
        } else {
            float n1 = m[i - 1] + m[i + 1] + m[i - W] + m[i + W];
            float n2 = m[i - 2] + m[i + 2] + m[i - 2 * W] + m[i + 2 * W];
            redBlue(c, n1, n2, diag, col, rgb);
        }
    }

    private static void green(float c, float diag, float h2, float v2, float hn, float vn, int rowOther, float[] rgb) {
        // colour of horizontal neighbours from the row kernel, vertical from the column kernel
        float hc = (5 * c - diag - h2 + 0.5f * v2 + 4 * hn) * 0.125f;
        float vc = (5 * c - diag + 0.5f * h2 - v2 + 4 * vn) * 0.125f;
        rgb[1] = c;
        if (rowOther == 0) {
            rgb[0] = hc;
            rgb[2] = vc;
        } else {
            rgb[2] = hc;
            rgb[0] = vc;
        }
    }

    private static void redBlue(float c, float n1, float n2, float diag, int col, float[] rgb) {
        float g = (4 * c + 2 * n1 - n2) * 0.125f;
        float other = (6 * c + 2 * diag - 1.5f * n2) * 0.125f;
        rgb[1] = g;
        if (col == 0) {
            rgb[0] = c;
            rgb[2] = other;
        } else {
            rgb[2] = c;
            rgb[0] = other;
        }
    }

    private static int refl(int v, int n) {
        if (v < 0) v = -v;
        if (v >= n) v = 2 * (n - 1) - v;
        return Math.max(0, Math.min(n - 1, v));
    }

    private static float at(float[] m, int W, int H, int x, int y) {
        return m[refl(y, H) * W + refl(x, W)];
    }

    private static void border(float[] m, int W, int H, int x, int y, int col, int rowOther, float[] rgb) {
        float c = m[y * W + x];
        float diag = at(m, W, H, x - 1, y - 1) + at(m, W, H, x + 1, y - 1) + at(m, W, H, x - 1, y + 1) + at(m, W, H, x + 1, y + 1);
        if (col == 1) {
            float h2 = at(m, W, H, x - 2, y) + at(m, W, H, x + 2, y), v2 = at(m, W, H, x, y - 2) + at(m, W, H, x, y + 2);
            float hn = at(m, W, H, x - 1, y) + at(m, W, H, x + 1, y), vn = at(m, W, H, x, y - 1) + at(m, W, H, x, y + 1);
            green(c, diag, h2, v2, hn, vn, rowOther, rgb);
        } else {
            float n1 = at(m, W, H, x - 1, y) + at(m, W, H, x + 1, y) + at(m, W, H, x, y - 1) + at(m, W, H, x, y + 1);
            float n2 = at(m, W, H, x - 2, y) + at(m, W, H, x + 2, y) + at(m, W, H, x, y - 2) + at(m, W, H, x, y + 2);
            redBlue(c, n1, n2, diag, col, rgb);
        }
    }
}
