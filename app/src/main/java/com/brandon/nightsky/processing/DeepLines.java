package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.List;

/**
 * Faint straight lines in a map of cells whose values are standard normal where there is nothing (a frame's streak
 * evidence at its deepest, {@link StreakEvidence#deep}): a segment's light summed over its cells and divided by the
 * square root of their number, which for a faint trail grows with its length while for noise it doesn't. Every
 * segment of every line is scored at once by a fast Radon transform (Götz and Druckmüller 1995; Brady 1998), whose
 * sums over blocks of 2, 4, 8 ... columns are the segments (the "partial" transform of Nir, Zackay and Ofek 2018,
 * Optimal and efficient streak detection in astronomical images), in four orientations (lines rising or falling,
 * shallower or steeper than 45 degrees); the blocks are also scored in pairs half a block along, so a segment across
 * a block's end isn't lost. The strongest are then fitted exactly: the line moved a cell either way at each end, and
 * its best stretch.
 * <p>
 * Neighbouring cells of a real map move together a little (the noise of a pixel near a cell's edge is shared with the
 * next cell's), so a line's sum spreads more than the root of its cells says, most along the rows and columns: each
 * line's score is divided by that ({@link #neighbours}, {@link #setNeighbours}).
 * <p>
 * One instance per thread: it keeps its working arrays.
 */
public final class DeepLines {
    /** A line found: its ends (cells, centres at whole numbers), how strong, and over how many cells. */
    public static final class Line {
        public float x0, y0, x1, y1;
        /** Its cells' sum over the root of their number (sigma, for a map of unit noise). */
        public float score;
        public int cells;
        /** Its cells' mean (the trail's strength per cell). */
        public float mean;

        public double length() {
            return Math.hypot(x1 - x0, y1 - y0);
        }
    }

    /** Most candidates kept from the transform per call, for the exact fit. */
    static final int MAX_CANDIDATES = 48;
    /** Candidates must reach this share of the threshold in the transform (its digital lines fit a trail less well). */
    static final double CANDIDATE_SHARE = 0.75;

    private final int w, h;
    /** How much neighbouring cells move together: along rows, columns, rising and falling diagonals. */
    private double[] rho = new double[4];
    private float[] va = new float[0], vb = new float[0];
    private short[] ca = new short[0], cb = new short[0];
    // the candidates: a small heap by score (weakest on top)
    private final float[] candScore = new float[MAX_CANDIDATES];
    private final int[][] cand = new int[MAX_CANDIDATES][5];
    private int nCand;

    public DeepLines(int w, int h) {
        this.w = w;
        this.h = h;
    }

    /** How much the next maps' neighbouring cells move together ({@link #neighbours}); none at first. */
    public void setNeighbours(double[] rho) {
        this.rho = rho.clone();
    }

    /**
     * Takes a map's slow changes out (in place): the mean of each block of {@code block} x {@code block} cells (cells
     * beyond +-2.5 taken as that, so a trail through it hardly counts), drawn smoothly between the blocks' centres,
     * is subtracted. What the stack's centre doesn't follow (the sky's own glow moving over the frames, a frame's
     * edges matched less well) is broad, and a line along it would add it up.
     */
    public static void flatten(float[] z, int w, int h, int block) {
        int bx = (w + block - 1) / block, by = (h + block - 1) / block;
        double[] mean = new double[bx * by];
        int[] cnt = new int[bx * by];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = z[y * w + x];
                if (!(v == v)) continue;
                int b = (y / block) * bx + x / block;
                mean[b] += v < -2.5f ? -2.5f : v > 2.5f ? 2.5f : v;
                cnt[b]++;
            }
        }
        // a block with too few cells to go by takes its neighbours' level (none: 0)
        int need = block * block / 4;
        double[] lvl = new double[bx * by];
        for (int b = 0; b < bx * by; b++) lvl[b] = cnt[b] >= need ? mean[b] / cnt[b] : Double.NaN;
        for (int b = 0; b < bx * by; b++) {
            if (lvl[b] == lvl[b]) continue;
            double s = 0;
            int n = 0;
            int cx = b % bx, cy = b / bx;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = cx + dx, y = cy + dy;
                    if (x < 0 || y < 0 || x >= bx || y >= by || cnt[y * bx + x] < need) continue;
                    s += mean[y * bx + x] / cnt[y * bx + x];
                    n++;
                }
            }
            lvl[b] = n > 0 ? s / n : 0;
        }
        for (int y = 0; y < h; y++) {
            double gy = Math.max(0, Math.min(by - 1, (y + 0.5) / block - 0.5));
            int y0 = Math.min(by - 2, (int) gy);
            double fy = by > 1 ? gy - y0 : 0;
            if (by == 1) y0 = 0;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!(z[i] == z[i])) continue;
                double gx = Math.max(0, Math.min(bx - 1, (x + 0.5) / block - 0.5));
                int x0 = bx > 1 ? Math.min(bx - 2, (int) gx) : 0;
                double fx = bx > 1 ? gx - x0 : 0;
                int x1 = bx > 1 ? x0 + 1 : 0, y1 = by > 1 ? y0 + 1 : 0;
                double v = (1 - fy) * ((1 - fx) * lvl[y0 * bx + x0] + fx * lvl[y0 * bx + x1])
                        + fy * ((1 - fx) * lvl[y1 * bx + x0] + fx * lvl[y1 * bx + x1]);
                z[i] -= (float) v;
            }
        }
    }

    /** Bins a sensor's row (or column) of cells is cut into across ({@link #unband}): one per row of pixels. */
    static final int BAND_BINS = StreakEvidence.DEEP;
    /** Fewest cells a bin's level is taken from (else its neighbours' too). */
    static final int BAND_MIN_CELLS = 16;

    /**
     * Takes the sensor's banding out of a frame's map (in place): an offset a whole row of the sensor shares in that
     * frame (or a whole column) makes a line along it, which a faint trail would be looked for in. The cells are put in
     * bins by where they lay across the sensor's rows ({@code row}, in cells; {@link #BAND_BINS} bins to a cell, one
     * per row of pixels), and each bin's mean (cells beyond +-2.5 taken as that) is taken out; then the same across its
     * columns ({@code col}). A trail loses next to nothing (its cells are a few of a bin's), unless it runs within a
     * degree or so along the sensor's rows or columns.
     */
    public static void unband(float[] z, int w, int h, float[] col, float[] row) {
        unbandAcross(z, w * h, row);
        unbandAcross(z, w * h, col);
    }

    private static void unbandAcross(float[] z, int n, float[] at) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float a = at[i];
            if (!(z[i] == z[i]) || !(a == a)) continue;
            if (a < lo) lo = a;
            if (a > hi) hi = a;
        }
        if (lo > hi) return;
        int base = (int) Math.floor(lo * BAND_BINS), bins = (int) Math.floor(hi * BAND_BINS) - base + 1;
        double[] sum = new double[bins];
        int[] cnt = new int[bins];
        for (int i = 0; i < n; i++) {
            float v = z[i], a = at[i];
            if (!(v == v) || !(a == a)) continue;
            int b = (int) Math.floor(a * BAND_BINS) - base;
            sum[b] += v < -2.5f ? -2.5f : v > 2.5f ? 2.5f : v;
            cnt[b]++;
        }
        // a bin with too few cells (a corner of a turned frame) takes its neighbours' too
        float[] lvl = new float[bins];
        for (int b = 0; b < bins; b++) {
            double s = sum[b];
            int c = cnt[b];
            for (int r = 1; c < BAND_MIN_CELLS && r <= 2; r++) {
                if (b - r >= 0) {
                    s += sum[b - r];
                    c += cnt[b - r];
                }
                if (b + r < bins) {
                    s += sum[b + r];
                    c += cnt[b + r];
                }
            }
            lvl[b] = c >= BAND_MIN_CELLS ? (float) (s / c) : 0f;
        }
        for (int i = 0; i < n; i++) {
            float a = at[i];
            if (!(z[i] == z[i]) || !(a == a)) continue;
            z[i] -= lvl[(int) Math.floor(a * BAND_BINS) - base];
        }
    }

    /**
     * How much neighbouring cells of a map of unit noise move together: their correlation along rows, along columns,
     * along rising diagonals (x and y both up a cell) and falling ones; cells beyond +-3 are taken as +-3.
     */
    public static double[] neighbours(float[] z, int w, int h) {
        int[][] steps = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
        double[] out = new double[4];
        for (int k = 0; k < 4; k++) {
            int dx = steps[k][0], dy = steps[k][1];
            double ab = 0, aa = 0, bb = 0;
            for (int y = Math.max(0, -dy); y < Math.min(h, h - dy); y++) {
                for (int x = 0; x + dx < w; x++) {
                    float a = z[y * w + x], b = z[(y + dy) * w + x + dx];
                    if (!(a == a) || !(b == b)) continue;
                    a = a < -3 ? -3 : a > 3 ? 3 : a;
                    b = b < -3 ? -3 : b > 3 ? 3 : b;
                    ab += a * b;
                    aa += a * a;
                    bb += b * b;
                }
            }
            out[k] = aa > 0 && bb > 0 ? ab / Math.sqrt(aa * bb) : 0;
        }
        return out;
    }

    /**
     * What a line's score is multiplied by for its neighbouring cells moving together ({@code rho} as from
     * {@link #neighbours}): one over the root of 1 + 2 rho along it, the next cell being in the same row (column, if
     * steeper than 45 degrees) or on a diagonal as often as its slope says.
     */
    public static double neighbourFactor(double[] rho, double x0, double y0, double x1, double y1) {
        double dx = x1 - x0, dy = y1 - y0;
        boolean steep = Math.abs(dy) > Math.abs(dx);
        double m = steep ? Math.abs(dx / dy) : (Math.abs(dx) < 1e-9 ? 0 : Math.abs(dy / dx));
        double diag = dx * dy >= 0 ? rho[2] : rho[3];
        double along = (1 - m) * (steep ? rho[1] : rho[0]) + m * diag;
        return 1 / Math.sqrt(Math.max(0.25, 1 + 2 * along));
    }

    /**
     * The lines in map {@code z} ({@code w} x {@code h} cells, row by row; NaN where there's nothing to go by) of
     * {@code minCells} cells or more scoring {@code threshold} or more, the strongest first, at most {@code maxLines};
     * each one's corridor ({@code claim} cells either side) is taken out of the map for the next (z is changed).
     */
    public List<Line> find(float[] z, double threshold, int minCells, int maxLines, double claim) {
        List<Line> out = new ArrayList<Line>();
        for (int round = 0; round < maxLines; round++) {
            nCand = 0;
            double low = threshold * CANDIDATE_SHARE;
            for (int o = 0; o < 4; o++) transform(z, o, low, minCells);
            // the candidates fitted exactly: the strongest that holds up is the line
            Line best = null;
            for (int i = 0; i < nCand; i++) {
                float[] e = ends(cand[i]);
                Line l = fit(z, e[0], e[1], e[2], e[3], minCells);
                if (l != null && l.score >= threshold && (best == null || l.score > best.score)) best = l;
            }
            if (best == null) break;
            out.add(best);
            clear(z, best, claim);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // The transform

    /** Width and height of orientation o's map: 0 and 1 the map as it is (1 upside down), 2 and 3 transposed. */
    private int ow(int o) {
        return o < 2 ? w : h;
    }

    private int oh(int o) {
        return o < 2 ? h : w;
    }

    /** The map's index of orientation o's cell (x, y). */
    private int at(int o, int x, int y) {
        switch (o) {
            case 0:
                return y * w + x;
            case 1:
                return (h - 1 - y) * w + x;
            case 2:
                return x * w + y;
            default:
                return x * w + (w - 1 - y);
        }
    }

    /** Cell (x, y) of orientation o in the map's own cells: {x, y}. */
    private float[] back(int o, float x, float y) {
        switch (o) {
            case 0:
                return new float[]{x, y};
            case 1:
                return new float[]{x, h - 1 - y};
            case 2:
                return new float[]{y, x};
            default:
                return new float[]{w - 1 - y, x};
        }
    }

    /**
     * The transform of orientation o, for lines rising 0 to 45 degrees in it: level j holds, for each block of 2^j
     * columns, each rise s (0 to 2^j - 1 cells across the block) and each starting row (from 2^j - 1 below the map
     * to its top), the sum of the digital line's cells and their number. Level j + 1's line is level j's with half
     * the rise in the left half, and in the right half from as far up as the left one's rise rounded up.
     */
    private void transform(float[] z, int o, double low, int minCells) {
        int W = ow(o), H = oh(o);
        int n = 1, levels = 0;
        while (n < W) {
            n <<= 1;
            levels++;
        }
        final int N = n, R = H + N - 1, size = N * R;
        if (va.length < size) {
            va = new float[size];
            vb = new float[size];
            ca = new short[size];
            cb = new short[size];
        }
        float[] vi = va, vo = vb;
        short[] ci = ca, co = cb;
        // level 0: the cells themselves (row yy is the map's row yy - (N - 1))
        for (int x = 0; x < N; x++) {
            int base = x * R;
            for (int yy = 0; yy < R; yy++) {
                int y = yy - (N - 1);
                float v = x < W && y >= 0 && y < H ? z[at(o, x, y)] : Float.NaN;
                boolean ok = v == v;
                vi[base + yy] = ok ? v : 0f;
                ci[base + yy] = (short) (ok ? 1 : 0);
            }
        }
        double low2 = low * low;
        for (int j = 0; j < levels; j++) {
            int half = 1 << j, blocks = N >> (j + 1);
            boolean score = 2 * half >= minCells;
            for (int b = 0; b < blocks; b++) {
                for (int s = 0; s < 2 * half; s++) {
                    int kL = (b << (j + 1)) + (s >> 1), kR = kL + half, kO = (b << (j + 1)) + s, sh = (s + 1) >> 1;
                    int bL = kL * R, bR = kR * R, bO = kO * R;
                    int lim = R - sh;
                    for (int yy = 0; yy < lim; yy++) {
                        vo[bO + yy] = vi[bL + yy] + vi[bR + yy + sh];
                        co[bO + yy] = (short) (ci[bL + yy] + ci[bR + yy + sh]);
                    }
                    for (int yy = lim; yy < R; yy++) {
                        vo[bO + yy] = vi[bL + yy];
                        co[bO + yy] = ci[bL + yy];
                    }
                    if (!score) continue;
                    for (int yy = 0; yy < R; yy++) {
                        float v = vo[bO + yy];
                        int c = co[bO + yy];
                        if (v > 0 && c >= minCells && v * v >= low2 * c) offer((float) (v / Math.sqrt(c)), o, j + 1, b << (j + 1), s, yy - (N - 1));
                    }
                    // the pair half a block along: this block's right half and the next one's left half
                    if (b + 1 < blocks) {
                        int kN = ((b + 1) << (j + 1)) + (s >> 1), bN = kN * R;
                        for (int yy = 0; yy < lim; yy++) {
                            float v = vi[bR + yy] + vi[bN + yy + sh];
                            if (!(v > 0)) continue;
                            int c = ci[bR + yy] + ci[bN + yy + sh];
                            if (c >= minCells && v * v >= low2 * c) offer((float) (v / Math.sqrt(c)), o, j + 1, (b << (j + 1)) + half, s, yy - (N - 1));
                        }
                    }
                }
            }
            float[] tv = vi;
            vi = vo;
            vo = tv;
            short[] tc = ci;
            ci = co;
            co = tc;
        }
    }

    /** A candidate: orientation, level, first column, rise and starting row, kept if among the strongest. */
    private void offer(float score, int o, int level, int x0, int rise, int y0) {
        if (nCand == MAX_CANDIDATES && score <= candScore[0]) return;
        // a near copy of one kept (the same line a row or a rise off) keeps only the stronger
        for (int i = 0; i < nCand; i++) {
            int[] c = cand[i];
            if (c[0] == o && c[1] == level && c[2] == x0 && Math.abs(c[3] - rise) <= 1 && Math.abs(c[4] - y0) <= 1) {
                if (score > candScore[i]) {
                    candScore[i] = score;
                    c[3] = rise;
                    c[4] = y0;
                    siftDown(i);
                }
                return;
            }
        }
        int i;
        if (nCand < MAX_CANDIDATES) {
            i = nCand++;
            candScore[i] = score;
            set(cand[i], o, level, x0, rise, y0);
            while (i > 0 && candScore[(i - 1) / 2] > candScore[i]) {
                swap(i, (i - 1) / 2);
                i = (i - 1) / 2;
            }
        } else {
            candScore[0] = score;
            set(cand[0], o, level, x0, rise, y0);
            siftDown(0);
        }
    }

    private static void set(int[] c, int o, int level, int x0, int rise, int y0) {
        c[0] = o;
        c[1] = level;
        c[2] = x0;
        c[3] = rise;
        c[4] = y0;
    }

    private void siftDown(int i) {
        while (true) {
            int l = 2 * i + 1, r = l + 1, m = i;
            if (l < nCand && candScore[l] < candScore[m]) m = l;
            if (r < nCand && candScore[r] < candScore[m]) m = r;
            if (m == i) return;
            swap(i, m);
            i = m;
        }
    }

    private void swap(int a, int b) {
        float t = candScore[a];
        candScore[a] = candScore[b];
        candScore[b] = t;
        int[] c = cand[a];
        cand[a] = cand[b];
        cand[b] = c;
    }

    /** A candidate's ends in the map's cells: {x0, y0, x1, y1}. */
    private float[] ends(int[] c) {
        int o = c[0], len = 1 << c[1];
        float[] a = back(o, c[2], c[4]), b = back(o, c[2] + len - 1, c[4] + c[3]);
        return new float[]{a[0], a[1], b[0], b[1]};
    }

    // ------------------------------------------------------------------------------------------------
    // The exact fit

    /**
     * The best line near the one from (x0, y0) to (x1, y1): each end moved across it by half a cell either way, then
     * by a quarter around the best, the line run from edge to edge of the map, and its best stretch of
     * {@code minCells} cells or more (its score for neighbouring cells moving together). Null if none.
     */
    Line fit(float[] z, float x0, float y0, float x1, float y1, int minCells) {
        double dx = x1 - x0, dy = y1 - y0, len = Math.hypot(dx, dy);
        if (len < 1) return null;
        double nx = -dy / len, ny = dx / len;
        Line best = null;
        float ca = 0, cb = 0;
        for (float step = 0.5f; step >= 0.25f; step /= 2) {
            float ba = ca, bb = cb;
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    if (best != null && a == 0 && b == 0) continue;
                    float oa = ca + a * step, ob = cb + b * step;
                    Line l = bestStretch(z, w, h, (float) (x0 + nx * oa), (float) (y0 + ny * oa), (float) (x1 + nx * ob),
                            (float) (y1 + ny * ob), minCells);
                    if (l != null) l.score *= (float) neighbourFactor(rho, l.x0, l.y0, l.x1, l.y1);
                    if (l != null && (best == null || l.score > best.score)) {
                        best = l;
                        ba = oa;
                        bb = ob;
                    }
                }
            }
            ca = ba;
            cb = bb;
        }
        return best;
    }

    /**
     * The best stretch of the whole line through (x0, y0) and (x1, y1) across a {@code w} x {@code h} map: the cells
     * it meets one per column (rows, if steeper than 45 degrees), and of their runs the one with the highest sum over
     * the root of the number of cells. Null if the line meets fewer than {@code minCells} cells.
     */
    public static Line bestStretch(float[] z, int w, int h, float x0, float y0, float x1, float y1, int minCells) {
        double dx = x1 - x0, dy = y1 - y0;
        boolean steep = Math.abs(dy) > Math.abs(dx);
        if (Math.abs(steep ? dy : dx) < 1e-6) return null;
        double slope = steep ? dx / dy : dy / dx;
        int span = steep ? h : w;
        // along the line: the value at each step (NaN off the map or where there's nothing)
        float[] v = new float[span];
        int first = -1, last = -1;
        for (int t = 0; t < span; t++) {
            double other = (steep ? x0 : y0) + slope * (t - (steep ? y0 : x0));
            int q = (int) Math.floor(other + 0.5);
            int x = steep ? q : t, y = steep ? t : q;
            if (x < 0 || y < 0 || x >= w || y >= h) {
                v[t] = Float.NaN;
                continue;
            }
            v[t] = z[y * w + x];
            if (first < 0) first = t;
            last = t;
        }
        if (first < 0) return null;
        // prefix sums of the values and of the cells with one
        int m = last - first + 1;
        double[] sum = new double[m + 1];
        int[] cnt = new int[m + 1];
        for (int i = 0; i < m; i++) {
            float val = v[first + i];
            boolean ok = val == val;
            sum[i + 1] = sum[i] + (ok ? val : 0);
            cnt[i + 1] = cnt[i] + (ok ? 1 : 0);
        }
        if (cnt[m] < minCells) return null;
        // the best run: every start, over lengths in steps of 15 percent, then both ends tuned cell by cell
        double bestS = -Double.MAX_VALUE;
        int bi = 0, bj = 0;
        for (int L = minCells; ; L = Math.max(L + 1, (int) Math.round(L * 1.15))) {
            boolean any = false;
            for (int i = 0; i + L <= m; i++) {
                int c = cnt[i + L] - cnt[i];
                if (c < minCells) continue;
                any = true;
                double s = (sum[i + L] - sum[i]) / Math.sqrt(c);
                if (s > bestS) {
                    bestS = s;
                    bi = i;
                    bj = i + L;
                }
            }
            if (!any && L > m) break;
            if (L >= m) break;
        }
        if (bestS == -Double.MAX_VALUE) return null;
        // tune the ends: each moved while it helps
        for (int pass = 0; pass < 4; pass++) {
            boolean moved = false;
            for (int d = -8; d <= 8; d++) {
                int i = bi + d;
                if (d == 0 || i < 0 || i >= bj) continue;
                int c = cnt[bj] - cnt[i];
                if (c < minCells) continue;
                double s = (sum[bj] - sum[i]) / Math.sqrt(c);
                if (s > bestS) {
                    bestS = s;
                    bi = i;
                    moved = true;
                }
            }
            for (int d = -8; d <= 8; d++) {
                int j = bj + d;
                if (d == 0 || j <= bi || j > m) continue;
                int c = cnt[j] - cnt[bi];
                if (c < minCells) continue;
                double s = (sum[j] - sum[bi]) / Math.sqrt(c);
                if (s > bestS) {
                    bestS = s;
                    bj = j;
                    moved = true;
                }
            }
            if (!moved) break;
        }
        // the run's first and last cells with a value
        while (bi < bj && !(v[first + bi] == v[first + bi])) bi++;
        while (bj > bi && !(v[first + bj - 1] == v[first + bj - 1])) bj--;
        Line l = new Line();
        int t0 = first + bi, t1 = first + bj - 1;
        double o0 = (steep ? x0 : y0) + slope * (t0 - (steep ? y0 : x0)), o1 = (steep ? x0 : y0) + slope * (t1 - (steep ? y0 : x0));
        l.x0 = (float) (steep ? o0 : t0);
        l.y0 = (float) (steep ? t0 : o0);
        l.x1 = (float) (steep ? o1 : t1);
        l.y1 = (float) (steep ? t1 : o1);
        l.cells = cnt[bj] - cnt[bi];
        l.score = (float) bestS;
        l.mean = (float) ((sum[bj] - sum[bi]) / Math.max(1, l.cells));
        return l;
    }

    /**
     * The sum over the root of the number of cells along the segment from (x0, y0) to (x1, y1) (one cell per column,
     * or row if steeper), and its cell count: {score, cells, mean}. Cells off the map or without a value don't count.
     */
    public static double[] along(float[] z, int w, int h, double x0, double y0, double x1, double y1) {
        double dx = x1 - x0, dy = y1 - y0;
        boolean steep = Math.abs(dy) > Math.abs(dx);
        double a = steep ? y0 : x0, b = steep ? y1 : x1;
        if (a > b) {
            double t = a;
            a = b;
            b = t;
        }
        double slope = Math.abs(steep ? dy : dx) < 1e-9 ? 0 : (steep ? dx / dy : dy / dx);
        double sum = 0;
        int n = 0;
        for (int t = (int) Math.ceil(a - 0.5); t <= (int) Math.floor(b + 0.5); t++) {
            double other = (steep ? x0 : y0) + slope * (t - (steep ? y0 : x0));
            int q = (int) Math.floor(other + 0.5);
            int x = steep ? q : t, y = steep ? t : q;
            if (x < 0 || y < 0 || x >= w || y >= h) continue;
            float v = z[y * w + x];
            if (!(v == v)) continue;
            sum += v;
            n++;
        }
        return new double[]{n > 0 ? sum / Math.sqrt(n) : 0, n, n > 0 ? sum / n : 0};
    }

    /** Takes a line's corridor ({@code claim} cells either side of it) out of the map. */
    void clear(float[] z, Line l, double claim) {
        clear(z, w, h, l.x0, l.y0, l.x1, l.y1, claim);
    }

    /** Takes the corridor from (x0, y0) to (x1, y1), {@code claim} cells either side, out of a map. */
    public static void clear(float[] z, int w, int h, double x0, double y0, double x1, double y1, double claim) {
        double dx = x1 - x0, dy = y1 - y0, len = Math.hypot(dx, dy);
        double ux = len > 0 ? dx / len : 1, uy = len > 0 ? dy / len : 0;
        int xa = (int) Math.floor(Math.min(x0, x1) - claim - 1), xb = (int) Math.ceil(Math.max(x0, x1) + claim + 1);
        int ya = (int) Math.floor(Math.min(y0, y1) - claim - 1), yb = (int) Math.ceil(Math.max(y0, y1) + claim + 1);
        for (int y = Math.max(0, ya); y <= Math.min(h - 1, yb); y++) {
            for (int x = Math.max(0, xa); x <= Math.min(w - 1, xb); x++) {
                double px = x - x0, py = y - y0;
                double t = px * ux + py * uy, d = Math.abs(-px * uy + py * ux);
                if (t >= -claim && t <= len + claim && d <= claim) z[y * w + x] = Float.NaN;
            }
        }
    }
}
