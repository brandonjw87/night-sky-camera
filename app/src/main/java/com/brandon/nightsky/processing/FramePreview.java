package com.brandon.nightsky.processing;

/**
 * One frame as a picture to tap stars on by hand (G3, F3's manual rescue): its photosites 2 x 2 at a time with the
 * black taken off, stretched so faint stars show, in grey; and a tap pulled onto the star under the finger.
 */
public final class FramePreview {
    /** The picture's size: half the sensor's each way. */
    public final int w, h;
    /** Light per picture pixel (its 4 photosites, ADU above black). */
    public final float[] level;
    /** The picture, row by row (opaque grey). */
    public final int[] argb;
    /** The sky's level and its noise (one sigma), on {@link #level}. */
    public final float background, noise;

    private FramePreview(int w, int h, float[] level, int[] argb, float background, float noise) {
        this.w = w;
        this.h = h;
        this.level = level;
        this.argb = argb;
        this.background = background;
        this.noise = noise;
    }

    /** The frame {@code raw} (values times {@code invScale} are ADU; {@code black} per photosite of the 2 x 2 pattern). */
    public static FramePreview of(short[] raw, int W, int H, float invScale, float[] black) {
        int w = W / 2, h = H / 2;
        float[] lv = new float[w * h];
        float bl = black[0] + black[1] + black[2] + black[3];
        for (int j = 0; j < h; j++) {
            int r1 = 2 * j * W, r2 = r1 + W;
            for (int i = 0; i < w; i++) {
                int x = 2 * i;
                lv[j * w + i] = ((raw[r1 + x] & 0xFFFF) + (raw[r1 + x + 1] & 0xFFFF) + (raw[r2 + x] & 0xFFFF) + (raw[r2 + x + 1] & 0xFFFF))
                        * invScale - bl;
            }
        }
        // the sky's level and noise from a sample: the median and the median step between neighbours
        int step = Math.max(1, (w * h) / 40000);
        int n = 0;
        float[] s = new float[(w * h) / step + 1], d = new float[(w * h) / step + 1];
        int nd = 0;
        for (int k = 0; k < w * h; k += step) {
            s[n++] = lv[k];
            if (k % w + 1 < w) d[nd++] = Math.abs(lv[k + 1] - lv[k]);
        }
        float bg = Stats.median(s, n);
        // the difference of two pixels has sqrt(2) times one's noise; the median of |N(0, s)| is 0.6745 s
        float sigma = Math.max(1e-3f, nd > 0 ? Stats.median(d, nd) / (0.6745f * 1.41421356f) : 1f);
        float top = Stats.quantile(s, n, 0.999);
        float hi = Math.max(20f, (top - bg) / sigma);
        double a = asinh(-2.0 / 4), b = asinh(hi / 4);
        int[] lut = new int[1024];
        for (int k = 0; k < lut.length; k++) {
            // k spans -2 .. hi sigmas
            double v = -2 + (hi + 2) * k / (lut.length - 1.0);
            double t = (asinh(v / 4) - a) / (b - a);
            int g = (int) Math.round(255 * Math.pow(Math.max(0, Math.min(1, t)), 0.8));
            lut[k] = 0xFF000000 | g << 16 | g << 8 | g;
        }
        int[] px = new int[w * h];
        float toK = (lut.length - 1) / (hi + 2);
        for (int k = 0; k < px.length; k++) {
            float v = (lv[k] - bg) / sigma;
            int q = (int) ((v + 2) * toK);
            px[k] = lut[q < 0 ? 0 : q >= lut.length ? lut.length - 1 : q];
        }
        return new FramePreview(w, h, lv, px, bg, sigma);
    }

    private static double asinh(double x) {
        return Math.log(x + Math.sqrt(x * x + 1));
    }

    /**
     * The star nearest a tap at picture pixel (x, y): the brightest point within {@code r} picture pixels, if it stands
     * clearly out of the sky, at the centre of its light; in sensor pixels. Null if there's no star there.
     */
    public float[] snap(float x, float y, int r) {
        int cx = Math.round(x), cy = Math.round(y);
        int best = -1;
        float bv = background + 5 * noise;
        for (int j = Math.max(1, cy - r); j <= Math.min(h - 2, cy + r); j++) {
            for (int i = Math.max(1, cx - r); i <= Math.min(w - 2, cx + r); i++) {
                if ((i - cx) * (i - cx) + (j - cy) * (j - cy) > r * r) continue;
                float v = level[j * w + i];
                if (v > bv) {
                    bv = v;
                    best = j * w + i;
                }
            }
        }
        if (best < 0) return null;
        int bi = best % w, bj = best / w;
        double sx = 0, sy = 0, sw = 0;
        for (int j = bj - 2; j <= bj + 2; j++) {
            for (int i = bi - 2; i <= bi + 2; i++) {
                if (i < 0 || j < 0 || i >= w || j >= h) continue;
                float v = level[j * w + i] - background;
                if (v <= 0) continue;
                sx += v * i;
                sy += v * j;
                sw += v;
            }
        }
        if (sw <= 0) return null;
        // as the star lists have it (a photosite x spans x to x + 1): picture pixel i spans 2i to 2i + 2
        return new float[]{(float) (2 * sx / sw + 1), (float) (2 * sy / sw + 1)};
    }
}
