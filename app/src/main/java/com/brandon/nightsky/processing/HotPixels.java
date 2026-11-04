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

    /** How many of them {@link #detect} found in clusters of two or three side by side (1.9). */
    private int clustered;

    public int clustered() {
        return clustered;
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
                float[] s = new float[8], rs = new float[4];
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
                                // not raised with it, while a star sitting on it lights them up too: one of them a
                                // lot, or all of them some, on every side (a sharp star that hardly moves, 1.9; a
                                // star's trail passing by lights one side, or two opposite)
                                if (ex > thr && ex > 2.5f * (mx - med) && y >= 3 && y < H - 3 && x >= 3 && x < W - 3) {
                                    ring(mean, i, W, s, sigma, null, 0, rs);
                                    if (rs[0] < 0.3f * ex && (rs[1] < RING * ex + NOISE * rs[2] || rs[3] < AROUND * ex)) bs.set(i);
                                }
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
        int n = clusters(mean, W, H, sigma, kSigma, mask);
        HotPixels hp = new HotPixels(W, H, mask);
        hp.clustered = n;
        return hp;
    }

    /**
     * What a defect's immediate neighbours may carry, summed, as a share of its own excess (and in sigmas of their noise
     * on top): a star's light reaches them, all eight together about as much as the brightest photosite under it holds
     * even when the star is sharp, while next to a defect they hold only noise (1.9).
     */
    static final float RING = 0.4f, NOISE = 1.5f;
    /** A photosite near a strong one counts as part of its cluster when it is raised this much of the strongest's excess. */
    static final float REL = 0.1f;
    /**
     * A star lights a single defect's neighbours on every side: along each of the four lines through it (across, down,
     * the two diagonals) the two neighbours hold at least this share of its excess together, even the diagonals of a
     * star as sharp as the half-size simulations draw them; a trail passing by leaves a line across it dark.
     */
    static final float AROUND = 0.05f;
    /**
     * A cluster is found from a photosite raised SEED times the single test's threshold: two or three photosites side by
     * side just over the noise are as often a faint star's image (from a frame the phone moved in, say) as defects, and
     * defects that faint hardly show in the photo.
     */
    static final float SEED = 2f;

    /**
     * Two or three defective photosites side by side, same-colour neighbours or adjacent ones (1.9). Each stands among
     * the others' surroundings, so the single-photosite test, which takes a raised neighbour for a star's light, leaves
     * them all, and they stack into a coloured blotch. From every photosite raised by {@link #SEED} times the threshold:
     * the photosites within two pixels of it raised by half the threshold and by a tenth of the strongest of them make the cluster (a
     * faint star's trail passing by is far fainter than a defect strong enough to matter). Two or three of them, each
     * with its own immediate neighbours (those outside the cluster) holding no more light than {@link #RING} allows, are
     * defects; any more, or neighbours lit up, are a star's. Sets them in {@code mask}; returns how many it added.
     */
    static int clusters(final float[] mean, final int W, final int H, final float[] sigma, final float kSigma, BitSet mask) {
        if (W < 16 || H < 16) return 0;
        final int bands = Parallel.threads() * 3;
        final BitSet[] pr = new BitSet[bands], ps = new BitSet[bands];
        Parallel.forRange(bands, 1, new Parallel.Range() {
            @Override
            public void run(int b0, int b1) {
                float[] s = new float[8];
                for (int b = b0; b < b1; b++) {
                    BitSet r = new BitSet(), st = new BitSet();
                    int ya = 2 + (int) ((long) (H - 4) * b / bands);
                    int yb = 2 + (int) ((long) (H - 4) * (b + 1) / bands);
                    for (int y = ya; y < yb; y++) {
                        int oy = y & 1;
                        for (int x = 2; x < W - 2; x++) {
                            int i = y * W + x;
                            float v = mean[i];
                            float a0 = mean[i - 2], a1 = mean[i + 2], a2 = mean[i - 2 * W], a3 = mean[i + 2 * W];
                            float a4 = mean[i - 2 * W - 2], a5 = mean[i - 2 * W + 2], a6 = mean[i + 2 * W - 2], a7 = mean[i + 2 * W + 2];
                            float mn = Math.min(Math.min(Math.min(a0, a1), Math.min(a2, a3)), Math.min(Math.min(a4, a5), Math.min(a6, a7)));
                            float half = 0.5f * kSigma * sigma[(oy << 1) | (x & 1)];
                            // the median of the eight is at least their least: most of the sky is done here
                            if (!(v - mn > half)) continue;
                            float ex = v - Stats.median8(a0, a1, a2, a3, a4, a5, a6, a7, s);
                            if (ex > half) {
                                r.set(i);
                                if (ex > 2 * SEED * half) st.set(i);
                            }
                        }
                    }
                    pr[b] = r;
                    ps[b] = st;
                }
            }
        });
        BitSet raised = new BitSet(W * H), strong = new BitSet(W * H);
        for (int b = 0; b < bands; b++) {
            if (pr[b] != null) raised.or(pr[b]);
            if (ps[b] != null) strong.or(ps[b]);
        }
        int added = 0;
        float[] s = new float[8], rs = new float[4], cex = new float[25], mex = new float[3];
        int[] cand = new int[25], mem = new int[3];
        // from every strong photosite: a row of three is found from whichever of them is strong
        for (int i = strong.nextSetBit(0); i >= 0; i = strong.nextSetBit(i + 1)) {
            int y = i / W, x = i - y * W;
            // the members' neighbours' own surroundings must be inside the picture
            if (x < 6 || y < 6 || x >= W - 6 || y >= H - 6) continue;
            int nc = 0;
            float peak = 0;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int j = i + dy * W + dx;
                    if (!raised.get(j)) continue;
                    cand[nc] = j;
                    cex[nc] = excess(mean, j, W, s);
                    peak = Math.max(peak, cex[nc]);
                    nc++;
                }
            }
            float rel = REL * peak;
            int n = 0;
            boolean ok = true, self = false;
            for (int c = 0; c < nc; c++) {
                if (!(cex[c] > rel)) continue;
                if (n == 3) {
                    ok = false;
                    break;
                }
                mem[n] = cand[c];
                mex[n++] = cex[c];
                self |= cand[c] == i;
            }
            if (!ok || n < 2 || !self) continue;
            // a member whose own neighbours are lit up makes it a star's light, or a star's beside the defects: leave it
            for (int m = 0; m < n && ok; m++) {
                ring(mean, mem[m], W, s, sigma, mem, n, rs);
                ok = rs[1] < RING * mex[m] + NOISE * rs[2];
            }
            if (!ok) continue;
            for (int m = 0; m < n; m++) {
                if (!mask.get(mem[m])) {
                    mask.set(mem[m]);
                    added++;
                }
            }
        }
        return added;
    }

    /** A photosite's excess over the median of its eight same-colour surroundings. */
    private static float excess(float[] m, int j, int W, float[] s) {
        return m[j] - Stats.median8(m[j - 2], m[j + 2], m[j - 2 * W], m[j + 2 * W],
                m[j - 2 * W - 2], m[j - 2 * W + 2], m[j + 2 * W - 2], m[j + 2 * W + 2], s);
    }

    /**
     * The 8 adjacent photosites of {@code i}, leaving out the first {@code n} of {@code skip}: their largest excess over
     * their own same-colour surroundings (out[0]), the sum of their excesses, those below their surroundings counted as
     * none (out[1]: beside a star's trail they dip, and must not hide the light of the others), less what noise alone
     * puts in it, and that sum's noise (out[2]); and the least, over the four lines through i, of its two neighbours'
     * excess on that line (out[3]).
     */
    private static void ring(float[] m, int i, int W, float[] s, float[] sigma, int[] skip, int n, float[] out) {
        float best = -Float.MAX_VALUE, sum = 0, var = 0;
        // per line through i (across, down, and the diagonals), its two neighbours' excess
        float[] line = new float[4];
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int j = i + dy * W + dx;
                boolean member = false;
                for (int k = 0; k < n; k++) member |= skip[k] == j;
                if (member) continue;
                float e = excess(m, j, W, s);
                line[dy == 0 ? 0 : dx == 0 ? 1 : dx == dy ? 2 : 3] += Math.max(0f, e);
                best = Math.max(best, e);
                int y = j / W;
                float sg = sigma[((y & 1) << 1) | ((j - y * W) & 1)];
                // noise alone, counted only where above: on average 0.4 sigma each, varying by 0.58 sigma
                sum += Math.max(0f, e) - 0.4f * sg;
                var += 0.34f * sg * sg;
            }
        }
        out[0] = best;
        out[1] = sum;
        out[2] = (float) Math.sqrt(var);
        out[3] = Math.min(Math.min(line[0], line[1]), Math.min(line[2], line[3]));
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
