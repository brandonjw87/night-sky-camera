package com.brandon.nightsky.processing;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Hot, warm and dead pixel map built from the session itself. In the sensor-fixed average of all frames,
 * stars smear into short trails (the sky turns) while defective pixels stay single points, so a pixel that
 * stands far above (or below) all of its same-colour neighbours is a defect.
 */
public final class HotPixels {
    public final int W, H;
    private final BitSet mask;
    private int[] list;

    private HotPixels(int W, int H, BitSet mask) {
        this.W = W;
        this.H = H;
        this.mask = mask;
        rebuildList();
    }

    public static HotPixels empty(int W, int H) {
        return new HotPixels(W, H, new BitSet(W * H));
    }

    public int count() {
        return list.length;
    }

    public boolean isBad(int x, int y) {
        return x >= 0 && y >= 0 && x < W && y < H && mask.get(y * W + x);
    }

    /** Any defective pixel within +-r pixels of (x, y). */
    public boolean nearBad(float x, float y, int r) {
        int cx = (int) x, cy = (int) y;
        for (int yy = cy - r; yy <= cy + r; yy++) {
            if (yy < 0 || yy >= H) continue;
            for (int xx = cx - r; xx <= cx + r; xx++) {
                if (xx >= 0 && xx < W && mask.get(yy * W + xx)) return true;
            }
        }
        return false;
    }

    public void add(int x, int y) {
        if (x >= 0 && y >= 0 && x < W && y < H) mask.set(y * W + x);
    }

    public void rebuildList() {
        int[] l = new int[mask.cardinality()];
        int n = 0;
        for (int i = mask.nextSetBit(0); i >= 0; i = mask.nextSetBit(i + 1)) l[n++] = i;
        list = l;
    }

    /**
     * @param mean   per-pixel average over the session, black level already subtracted (ADU)
     * @param cfa    plane colours (only used to scale thresholds per plane)
     * @param kSigma detection threshold in robust sigmas of the neighbour-difference image
     */
    public static HotPixels detect(final float[] mean, final int W, final int H, int[] cfa, final float kSigma) {
        // Robust spread of (pixel - median of same-colour neighbours), per plane, from a sample.
        final float[] sigma = new float[4];
        float[] scratch = new float[8];
        for (int p = 0; p < 4; p++) {
            int ox = p & 1, oy = p >> 1;
            int n = 0;
            float[] d = new float[60000];
            int stepY = Math.max(2, (H - 8) / 240) & ~1;
            int stepX = Math.max(2, (W - 8) / 250) & ~1;
            for (int y = 4 + oy; y < H - 4 && n < d.length; y += stepY) {
                for (int x = 4 + ox; x < W - 4 && n < d.length; x += stepX) {
                    int i = y * W + x;
                    float med = Stats.median8(mean[i - 2], mean[i + 2], mean[i - 2 * W], mean[i + 2 * W],
                            mean[i - 2 * W - 2], mean[i - 2 * W + 2], mean[i + 2 * W - 2], mean[i + 2 * W + 2], scratch);
                    d[n++] = mean[i] - med;
                }
            }
            sigma[p] = Math.max(0.05f, Stats.madSigma(d, n, 0f));
        }
        final BitSet mask = new BitSet(W * H);
        final BitSet[] partial = new BitSet[Parallel.threads() * 3];
        final int bands = partial.length;
        Parallel.forRange(bands, 1, new Parallel.Range() {
            @Override
            public void run(int b0, int b1) {
                float[] s = new float[8];
                for (int b = b0; b < b1; b++) {
                    BitSet bs = new BitSet();
                    int ya = 2 + (int) ((long) (H - 4) * b / bands);
                    int yb = 2 + (int) ((long) (H - 4) * (b + 1) / bands);
                    for (int y = ya; y < yb; y++) {
                        int oy = y & 1;
                        for (int x = 2; x < W - 2; x++) {
                            int i = y * W + x;
                            float v = mean[i];
                            float a0 = mean[i - 2], a1 = mean[i + 2], a2 = mean[i - 2 * W], a3 = mean[i + 2 * W];
                            float a4 = mean[i - 2 * W - 2], a5 = mean[i - 2 * W + 2], a6 = mean[i + 2 * W - 2], a7 = mean[i + 2 * W + 2];
                            float mx = Math.max(Math.max(Math.max(a0, a1), Math.max(a2, a3)), Math.max(Math.max(a4, a5), Math.max(a6, a7)));
                            float mn = Math.min(Math.min(Math.min(a0, a1), Math.min(a2, a3)), Math.min(Math.min(a4, a5), Math.min(a6, a7)));
                            float thr = kSigma * sigma[(oy << 1) | (x & 1)];
                            if (v > mx + thr * 0.5f) {
                                float med = Stats.median8(a0, a1, a2, a3, a4, a5, a6, a7, s);
                                float ex = v - med;
                                // a defect is a single photosite: its immediate neighbours (other colours) are
                                // not raised with it, while a star sitting on it lights them up too
                                if (ex > thr && ex > 2.5f * (mx - med) && y >= 3 && y < H - 3 && x >= 3 && x < W - 3
                                        && neighbourExcess(mean, i, W, s) < 0.3f * ex) bs.set(i);
                            } else if (v < mn - thr * 0.5f) {
                                float med = Stats.median8(a0, a1, a2, a3, a4, a5, a6, a7, s);
                                float ex = med - v;
                                if (ex > thr && ex > 2.5f * (med - mn)) bs.set(i);
                            }
                        }
                    }
                    partial[b] = bs;
                }
            }
        });
        for (BitSet bs : partial) if (bs != null) mask.or(bs);
        return new HotPixels(W, H, mask);
    }

    /** Largest excess of the 8 adjacent photosites over their own same-colour surroundings. */
    private static float neighbourExcess(float[] m, int i, int W, float[] s) {
        float best = -Float.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int j = i + dy * W + dx;
                float med = Stats.median8(m[j - 2], m[j + 2], m[j - 2 * W], m[j + 2 * W],
                        m[j - 2 * W - 2], m[j - 2 * W + 2], m[j + 2 * W - 2], m[j + 2 * W + 2], s);
                best = Math.max(best, m[j] - med);
            }
        }
        return best;
    }

    /** Replaces defective pixels with the median of their usable same-colour neighbours (rows y0..y1). */
    public void fix(short[] raw, int y0, int y1) {
        fix(raw, y0, y1, 0);
    }

    /**
     * Same as {@link #fix(short[], int, int)} for a buffer that holds image rows starting at {@code bufRow0}
     * (pixel (x, y) is at {@code (y - bufRow0) * W + x}); only rows y0..y1 are read or written.
     */
    public void fix(short[] buf, int y0, int y1, int bufRow0) {
        int[] vals = new int[8];
        y0 = Math.max(0, y0);
        y1 = Math.min(H, y1);
        int start = Arrays.binarySearch(list, y0 * W);
        if (start < 0) start = -start - 1;
        int end = y1 * W;
        int shift = bufRow0 * W;
        for (int q = start; q < list.length && list[q] < end; q++) {
            int i = list[q];
            int y = i / W, x = i - y * W;
            int n = 0;
            for (int dy = -2; dy <= 2; dy += 2) {
                int yy = y + dy;
                if (yy < y0 || yy >= y1) continue;
                for (int dx = -2; dx <= 2; dx += 2) {
                    if (dx == 0 && dy == 0) continue;
                    int xx = x + dx;
                    if (xx < 0 || xx >= W) continue;
                    int j = yy * W + xx;
                    if (mask.get(j)) continue;
                    vals[n++] = buf[j - shift] & 0xFFFF;
                }
            }
            if (n == 0) continue;
            Arrays.sort(vals, 0, n);
            int med = (n & 1) == 1 ? vals[n / 2] : (vals[n / 2 - 1] + vals[n / 2] + 1) / 2;
            buf[i - shift] = (short) med;
        }
    }

    public void fix(short[] raw) {
        fix(raw, 0, H);
    }

    /** Same as {@link #fix(short[], int, int)} for a float image (e.g. an accumulated average). */
    public void fix(float[] img) {
        float[] vals = new float[8];
        for (int i : list) {
            int y = i / W, x = i - y * W;
            int n = 0;
            for (int dy = -2; dy <= 2; dy += 2) {
                int yy = y + dy;
                if (yy < 0 || yy >= H) continue;
                for (int dx = -2; dx <= 2; dx += 2) {
                    if (dx == 0 && dy == 0) continue;
                    int xx = x + dx;
                    if (xx < 0 || xx >= W) continue;
                    int j = yy * W + xx;
                    if (mask.get(j)) continue;
                    vals[n++] = img[j];
                }
            }
            if (n == 0) continue;
            Arrays.sort(vals, 0, n);
            img[i] = (n & 1) == 1 ? vals[n / 2] : 0.5f * (vals[n / 2 - 1] + vals[n / 2]);
        }
    }
}
