package com.brandon.nightsky.processing;

import java.util.Arrays;

/**
 * How many stars a picture shows (2.3: Mission Control's "stars found", which climbs as the live stack gets deeper):
 * the points that stand out of the sky's noise. The sky's glow is taken out by blocks (their medians, drawn smoothly
 * between them), each block's noise is the spread of what's left, and a star is a pixel brighter than its eight
 * neighbours by {@link #SIGMA} of that noise or more, with enough light around it to be more than one hot speck.
 */
public final class PictureStars {
    /** How far out of the noise a star's brightest pixel must stand (sigma), and its 3 x 3 sum. */
    static final double SIGMA = 5, SUM_SIGMA = 9;
    /** Blocks (pixels a side) the sky's glow and noise are measured in. */
    static final int BLOCK = 32;

    private PictureStars() {}

    /** The stars in the picture's crop ({@link RgbImage#x0} ...), counted in its luminance. */
    public static int count(RgbImage img) {
        if (img == null) return 0;
        int x0 = Math.max(0, img.x0), y0 = Math.max(0, img.y0);
        int x1 = img.x1 > x0 ? Math.min(img.width, img.x1) : img.width, y1 = img.y1 > y0 ? Math.min(img.height, img.y1) : img.height;
        int w = x1 - x0, h = y1 - y0;
        if (w < 8 || h < 8) return 0;
        float[] v = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int k = (y0 + y) * img.width + x0 + x;
                v[y * w + x] = img.r[k] + img.g[k] + img.b[k];
            }
        }
        return count(v, w, h);
    }

    /** The stars in a w x h map of brightness. */
    public static int count(float[] v, int w, int h) {
        int bx = (w + BLOCK - 1) / BLOCK, by = (h + BLOCK - 1) / BLOCK;
        float[] level = new float[bx * by], noise = new float[bx * by];
        float[] buf = new float[BLOCK * BLOCK];
        for (int j = 0; j < by; j++) {
            for (int i = 0; i < bx; i++) {
                int n = 0;
                for (int y = j * BLOCK; y < Math.min(h, (j + 1) * BLOCK); y++) {
                    for (int x = i * BLOCK; x < Math.min(w, (i + 1) * BLOCK); x++) {
                        float a = v[y * w + x];
                        if (a == a) buf[n++] = a;
                    }
                }
                if (n < 16) {
                    level[j * bx + i] = Float.NaN;
                    noise[j * bx + i] = Float.NaN;
                    continue;
                }
                Arrays.sort(buf, 0, n);
                float med = buf[n / 2];
                // the spread from the lower half, which the stars don't reach
                float q = buf[n / 4];
                level[j * bx + i] = med;
                noise[j * bx + i] = (med - q) / 0.6745f;
            }
        }
        fill(level, bx, by);
        fill(noise, bx, by);
        int stars = 0;
        for (int y = 1; y < h - 1; y++) {
            int bj = Math.min(by - 1, y / BLOCK);
            for (int x = 1; x < w - 1; x++) {
                int k = y * w + x;
                float c = v[k];
                if (!(c == c)) continue;
                int b = bj * bx + Math.min(bx - 1, x / BLOCK);
                float sd = noise[b];
                if (!(sd > 0)) continue;
                float lv = level[b];
                if (c - lv < SIGMA * sd) continue;
                // a local maximum (ties broken towards the top left, so a flat top counts once)
                boolean peak = true;
                float sum = 0;
                for (int dy = -1; dy <= 1 && peak; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        float o = v[k + dy * w + dx];
                        if (!(o == o)) continue;
                        if (o > c || (o == c && (dy < 0 || (dy == 0 && dx < 0)))) {
                            peak = false;
                            break;
                        }
                        sum += o - lv;
                    }
                }
                if (!peak) continue;
                // the light around it too: a star spreads over its neighbours, a hot pixel doesn't
                if (sum + (c - lv) < SUM_SIGMA * 3 * sd || sum < 2 * sd) continue;
                stars++;
            }
        }
        return stars;
    }

    /** Blocks without a value take their nearest neighbours' mean. */
    private static void fill(float[] a, int bx, int by) {
        float all = 0;
        int n = 0;
        for (float x : a) {
            if (x == x) {
                all += x;
                n++;
            }
        }
        float mean = n > 0 ? all / n : 0;
        for (int i = 0; i < a.length; i++) if (!(a[i] == a[i])) a[i] = mean;
    }
}
