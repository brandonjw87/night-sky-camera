package com.brandon.nightsky.processing;

import java.util.Arrays;
import java.util.BitSet;

/**
 * The foreground to a couple of pixels (A7, G3), for keeping the ground still. {@link EdgeMask} finds the foreground
 * in cells eight pixels square, and Brandon can paint it; here each 2x2 quad of photosites in a foreground cell is
 * judged on the session's average on the sensor: ground where it is darker than halfway from the ground's own level
 * to the sky's beside it. Gaps and lit spots a few pixels across are closed over (sky seen between leaves, a lit
 * leaf), and cells painted as ground, or lit and beside the ground, are ground whole.
 * <p>
 * The lined-up stack leaves out every frame's samples that fall on the ground or within {@link #MARGIN} quads of it
 * (its edge as the lens blurs it), as if they were off the frame: the sky beside a branch then keeps its level and its
 * sharp stars, not darkened by the frames in which the branch covered it. Over the ground itself the frames' plain
 * average, not lined up, goes in, softened over a quad at its edge.
 */
public final class GroundMap {
    /** Quads around the ground left out of the lined-up stack: the lens's blur of its edge. */
    static final int MARGIN = 2;
    /** Gaps and lit spots up to about twice this many quads across are closed over. */
    static final int CLOSE = 2;
    /** Cells around a foreground cell whose sky gives the sky's level beside it (else {@link #FAR}, else the frame's). */
    static final int NEAR_CELLS = 3, FAR = 8;

    /** The sensor's size, and the quads across and down. */
    public final int width, height, qw, qh;
    /** Per quad, the ground's weight in the picture: 0 (sky), 1 (a quad from the ground) or 2 (ground). */
    private final byte[] weight;
    /** Quads whose samples the lined-up stack leaves out, as bits. */
    private final long[] near;
    /** How many quads are ground. */
    public final int quads;

    private GroundMap(int width, int height, byte[] weight, long[] near, int quads) {
        this.width = width;
        this.height = height;
        qw = width / 2;
        qh = height / 2;
        this.weight = weight;
        this.near = near;
        this.quads = quads;
    }

    /** The share of the frame that is ground. */
    public float share() {
        return quads / (float) Math.max(1, qw * qh);
    }

    /** Whether a frame's sample at sensor position (x, y), inside the frame, is on the ground or next to it. */
    public boolean nearAt(float x, float y) {
        int i = (int) (y * 0.5f) * qw + (int) (x * 0.5f);
        return (near[i >> 6] & (1L << i)) != 0;
    }

    /** The ground's weight at sensor pixel (x, y), 0 to 1: bilinear between the quads' centres. */
    public float weightAt(int x, int y) {
        float fx = x * 0.5f - 0.25f, fy = y * 0.5f - 0.25f;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        int xa = clamp(x0, qw), xb = clamp(x0 + 1, qw), ya = clamp(y0, qh), yb = clamp(y0 + 1, qh);
        float a = weight[ya * qw + xa] + tx * (weight[ya * qw + xb] - weight[ya * qw + xa]);
        float b = weight[yb * qw + xa] + tx * (weight[yb * qw + xb] - weight[yb * qw + xa]);
        return 0.5f * (a + ty * (b - a));
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    /** Sets, in {@code pixels} (the sensor's, row by row), every pixel whose samples the lined-up stack leaves out. */
    public void markNear(BitSet pixels) {
        for (int qy = 0; qy < qh; qy++) {
            for (int qx = 0; qx < qw; qx++) {
                int i = qy * qw + qx;
                if ((near[i >> 6] & (1L << i)) == 0) continue;
                int o = 2 * qy * width + 2 * qx;
                pixels.set(o, o + 2);
                pixels.set(o + width, o + width + 2);
            }
        }
    }

    /**
     * The foreground to the quad: {@code cells} as {@link EdgeMask} found and painted it, {@code painting} what was
     * painted (null if nothing), judged on the session's average on the sensor ({@code avg}: black taken off, row by
     * row). Null if no quad is ground.
     */
    public static GroundMap build(final float[] avg, SensorSpec spec, final boolean[] cells, EdgeMask painting) {
        final int W = spec.width, H = spec.height, cw = EdgeMask.cells(W), ch = EdgeMask.cells(H);
        if (avg == null || avg.length != W * H || cells == null || cells.length != cw * ch || EdgeMask.count(cells) == 0) {
            return null;
        }
        final float[] v = EdgeMask.levels(avg, spec);
        float[] t = v.clone();
        float level = Stats.median(t, t.length);
        float clear = 6 * EdgeMask.noise(v, cw, ch);
        // the ground's own level: the median of its dark cells (black if none is dark)
        int nd = 0;
        for (int i = 0; i < v.length; i++) {
            if (cells[i] && v[i] < EdgeMask.DARK * level && level - v[i] > clear) t[nd++] = v[i];
        }
        final float dark = nd > 0 ? Stats.median(t, nd) : 0f;
        // cells that are ground whole: painted so, or lit beside the ground
        final boolean[] whole = new boolean[v.length];
        boolean painted = painting != null && painting.paint.length == v.length;
        for (int i = 0; i < v.length; i++) {
            whole[i] = cells[i] && ((painted && painting.paint[i] == EdgeMask.GROUND)
                    || (v[i] > EdgeMask.BRIGHT * level && v[i] - level > clear));
        }
        final float[] sky = skyBeside(v, cells, cw, ch, level);
        // each quad of a foreground cell: ground if darker than halfway from the ground's level to the sky's beside it
        final int qw = W / 2, qh = H / 2;
        final boolean[] g = new boolean[qw * qh];
        final int[] cfa = spec.cfa;
        final int gq = Preflight.greenSite(spec);
        final ShadingMap sh = spec.shading;
        Parallel.forRange(qh, 16, new Parallel.Range() {
            @Override
            public void run(int a, int b) {
                for (int qy = a; qy < b; qy++) {
                    int cy = 2 * qy / EdgeMask.CELL;
                    for (int qx = 0; qx < qw; qx++) {
                        int cx = 2 * qx / EdgeMask.CELL, c = cy * cw + cx;
                        if (!cells[c]) continue;
                        if (whole[c]) {
                            g[qy * qw + qx] = true;
                            continue;
                        }
                        // its green photosites, as the cells' levels are measured
                        double s = 0;
                        int n = 0;
                        for (int y = 2 * qy; y < 2 * qy + 2; y++) {
                            for (int x = 2 * qx; x < 2 * qx + 2; x++) {
                                if (cfa[((y & 1) << 1) | (x & 1)] != 1) continue;
                                s += avg[y * W + x];
                                n++;
                            }
                        }
                        float l = n > 0 ? (float) (s / n) * EdgeMask.gain(sh, gq, cx, cy) : 0f;
                        float sk = between(sky, cw, ch, c, (2 * qx + 1f) / EdgeMask.CELL - 0.5f, (2 * qy + 1f) / EdgeMask.CELL - 0.5f);
                        g[qy * qw + qx] = l < dark + 0.5f * (sk - dark);
                    }
                }
            }
        });
        // gaps and lit spots a few pixels across closed over, within the foreground's cells
        boolean[] closed = erode(dilate(g, qw, qh, CLOSE), qw, qh, CLOSE);
        int n = 0;
        for (int qy = 0; qy < qh; qy++) {
            int cy = 2 * qy / EdgeMask.CELL;
            for (int qx = 0; qx < qw; qx++) {
                int i = qy * qw + qx;
                g[i] = closed[i] && cells[cy * cw + 2 * qx / EdgeMask.CELL];
                if (g[i]) n++;
            }
        }
        if (n == 0) return null;
        boolean[] one = dilate(g, qw, qh, 1), margin = dilate(g, qw, qh, MARGIN);
        byte[] w = new byte[qw * qh];
        long[] bits = new long[(qw * qh + 63) >> 6];
        for (int i = 0; i < w.length; i++) {
            w[i] = (byte) (g[i] ? 2 : one[i] ? 1 : 0);
            if (margin[i]) bits[i >> 6] |= 1L << i;
        }
        return new GroundMap(W, H, w, bits, n);
    }

    /**
     * Per foreground cell and the cells next to it, the sky's level beside it: the median of the sky's cells within
     * {@link #NEAR_CELLS} cells (at least four of them), else within {@link #FAR}, else the whole frame's sky; NaN for
     * the other cells.
     */
    private static float[] skyBeside(float[] v, boolean[] cells, int cw, int ch, float level) {
        boolean[] need = EdgeMask.dilate(cells, cw, ch, 1);
        float[] out = new float[v.length];
        Arrays.fill(out, Float.NaN);
        float[] buf = new float[Math.max(v.length, (2 * FAR + 1) * (2 * FAR + 1))];
        int na = 0;
        for (int i = 0; i < v.length; i++) if (!cells[i]) buf[na++] = v[i];
        float frame = na > 0 ? Stats.median(buf, na) : level;
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                if (!need[cy * cw + cx]) continue;
                float s = Float.NaN;
                for (int r = NEAR_CELLS; r <= FAR && s != s; r += FAR - NEAR_CELLS) {
                    int n = 0;
                    for (int y = Math.max(0, cy - r); y <= Math.min(ch - 1, cy + r); y++) {
                        for (int x = Math.max(0, cx - r); x <= Math.min(cw - 1, cx + r); x++) {
                            if (!cells[y * cw + x]) buf[n++] = v[y * cw + x];
                        }
                    }
                    if (n >= 4) s = Stats.median(buf, n);
                }
                out[cy * cw + cx] = s == s ? s : frame;
            }
        }
        return out;
    }

    /** {@code f} between the cells' centres at cell coordinates (fx, fy); cell {@code own}'s where a neighbour has none. */
    private static float between(float[] f, int cw, int ch, int own, float fx, float fy) {
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy);
        float tx = fx - x0, ty = fy - y0;
        int xa = clamp(x0, cw), xb = clamp(x0 + 1, cw), ya = clamp(y0, ch), yb = clamp(y0 + 1, ch);
        float v00 = f[ya * cw + xa], v01 = f[ya * cw + xb], v10 = f[yb * cw + xa], v11 = f[yb * cw + xb];
        if (v00 != v00 || v01 != v01 || v10 != v10 || v11 != v11) return f[own];
        float a = v00 + tx * (v01 - v00), b = v10 + tx * (v11 - v10);
        return a + ty * (b - a);
    }

    /** Square dilation by r (out of the grid counts as empty). */
    static boolean[] dilate(boolean[] m, int w, int h, int r) {
        return morph(m, w, h, r, true);
    }

    /** Square erosion by r (out of the grid counts as set, so what touches the frame's edge stays there). */
    static boolean[] erode(boolean[] m, int w, int h, int r) {
        return morph(m, w, h, r, false);
    }

    private static boolean[] morph(final boolean[] m, final int w, final int h, final int r, final boolean grow) {
        final boolean[] t = new boolean[m.length], o = new boolean[m.length];
        Parallel.forRange(h, 32, new Parallel.Range() {
            @Override
            public void run(int a, int b) {
                int[] pre = new int[w + 1];
                for (int y = a; y < b; y++) {
                    for (int x = 0; x < w; x++) pre[x + 1] = pre[x] + (m[y * w + x] ? 1 : 0);
                    for (int x = 0; x < w; x++) {
                        int lo = Math.max(0, x - r), hi = Math.min(w, x + r + 1), k = pre[hi] - pre[lo];
                        t[y * w + x] = grow ? k > 0 : k == hi - lo;
                    }
                }
            }
        });
        Parallel.forRange(w, 32, new Parallel.Range() {
            @Override
            public void run(int a, int b) {
                int[] pre = new int[h + 1];
                for (int x = a; x < b; x++) {
                    for (int y = 0; y < h; y++) pre[y + 1] = pre[y] + (t[y * w + x] ? 1 : 0);
                    for (int y = 0; y < h; y++) {
                        int lo = Math.max(0, y - r), hi = Math.min(h, y + r + 1), k = pre[hi] - pre[lo];
                        o[y * w + x] = grow ? k > 0 : k == hi - lo;
                    }
                }
            }
        });
        return o;
    }
}
