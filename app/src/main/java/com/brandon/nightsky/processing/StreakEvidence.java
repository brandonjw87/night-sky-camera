package com.brandon.nightsky.processing;

import java.util.Arrays;

/**
 * Where each frame had samples rejected for being too bright, as a map of 4 x 4 pixel cells per frame (one bit
 * each, about 100 kB a frame at full size): a cell counts when at least {@link #MIN_REJECTED} of its 48
 * samples (16 pixels, three colours) went. A satellite, a plane or a meteor leaves a line of such cells in its
 * frame, which predicted tracks are checked against ({@link SatMatch}); noise leaves at most a stray cell.
 * Each counted cell also keeps how much light its green samples had over the stack's ({@link #excess}): how bright
 * a streak was along its length (a few thousand cells a frame, 8 bytes each).
 * <p>
 * <p>
 * Deeper, with {@link #deep}: each frame's samples, every one of them, as far from the stack's centre as the noise
 * there makes them (standardized), a pixel's three colours taken together, summed per 8 x 8 pixel cell over the root
 * of their number, and divided by how much such sums spread in that frame (more than 1: the colour interpolation and
 * the frame's alignment share each sample's noise with its neighbours, so a cell's samples move together): a cell's
 * value is then standard normal where there is only sky, and a trail far too faint to be rejected anywhere still
 * leaves a line of cells a little above it, which {@link DeepLines} finds by adding them up along lines (half a byte
 * a cell, about 100 kB a frame at full size). A pixel's colours count as much as a white light shows in them over
 * their noise ({@link #setWhite}; with red and blue a cell has twice the photosites to go by, and a faint white trail
 * stands about a fifth higher than in green alone), which also gives what a cell's value stands for in light
 * ({@link #deepLight}).
 * <p>
 * The stacker counts per band of rows (bands start on a deep cell's boundary) and calls {@link #endBand}; when it
 * takes the frames one at a time, {@link #endFrameBand} after each.
 */
public final class StreakEvidence {
    public static final int CELL = 4;
    /** Bright rejected samples a cell needs. */
    public static final int MIN_REJECTED = 4;

    /** The deep map's cells (pixels a side): two of the rejection map's. */
    public static final int DEEP = 2 * CELL;
    /** A deep cell's pixels; fewest with samples it needs to have a value: half of them. */
    static final int DEEP_FULL = DEEP * DEEP, DEEP_MIN_PIXELS = DEEP_FULL / 2;
    /** A standardized sample is clipped to this range before it counts (a hot pixel, a star's leftovers). */
    static final float DEEP_LOW = -4f, DEEP_HIGH = 6f;
    /**
     * The deep map's values: from -2.5 to 4.5 in half steps, as codes 1 to 15 (0: nothing to go by); so few cells of
     * sky fall below the lowest that cutting them off there leaves the sky's mean as it was.
     */
    static final float DEEP_MIN = -2.5f, DEEP_STEP = 0.5f;
    static final int DEEP_CODES = 15;
    /** Fewest cells of a band a frame's spread is measured on; the histogram it's measured with (bins over +-16). */
    static final int SPREAD_MIN_CELLS = 64, SPREAD_BINS = 640;
    static final float SPREAD_RANGE = 16f;
    /** Blocks of the light map ({@link #deepLight}), in deep cells a side. */
    static final int NOISE_CELLS = 8;

    public final int frames, cellsX, cellsY;
    /** The deep map's size in cells (0 x 0 when it isn't kept). */
    public final int deepX, deepY;
    /** Per plan: bit per cell, row by row. */
    final long[][] bits;
    /** Per plan: the deep map, two cells to a byte (the even cell in the low half), or null. */
    private final byte[][] deep;
    /**
     * Per plan, per block of the light map: what its pixels gained from a white light's green (their values' rise per
     * unit of it, over the cells' spread) summed, and their count.
     */
    private final float[][] noiseSum;
    private final int[][] noiseCnt;
    /** White's camera colour (red, green, blue; green 1): how much of a white light each colour's samples see. */
    private final float[] white = {1f, 1f, 1f};
    /** Where each plan's pixels lay on its sensor ({@link #setSensor}), or null. */
    private Sensor sensor;
    private final int noiseX, noiseY;
    /** Per plan: the spread its deep cells were divided by (from its bands so far), and over how many cells. */
    private final float[] deepScale;
    private final int[] deepScaleN;
    private final int[] spreadHist = new int[SPREAD_BINS];
    // per band and slot (a plan, or one for all when they come one at a time): the pixels' values summed, their gain
    // from a white light and their number per deep cell
    private float[] dSum = new float[0], dGain = new float[0];
    private short[] dCnt = new short[0];
    private int bandDeepY0, bandDeepRows, slots = 1;
    private final java.util.ArrayDeque<DeepBatch> pool = new java.util.ArrayDeque<DeepBatch>();
    /** Per plan: the counted cells' indices (ascending) and their green excess, the first exN[f] of them. */
    private final int[][] exCell;
    private final float[][] exSum;
    private final int[] exN;
    // per band: counts per plan and cell row of the band, and the green samples' excess beside them
    private byte[] counts = new byte[0];
    private float[] excess = new float[0];
    private int bandCellY0, bandCellRows;

    public StreakEvidence(int frames, int width, int height) {
        this(frames, width, height, false);
    }

    /** With {@code deep}, the deep map too ({@link #deepBytes} of memory). */
    public StreakEvidence(int frames, int width, int height, boolean deep) {
        this.frames = frames;
        cellsX = (width + CELL - 1) / CELL;
        cellsY = (height + CELL - 1) / CELL;
        bits = new long[frames][(int) (((long) cellsX * cellsY + 63) >> 6)];
        exCell = new int[frames][];
        exSum = new float[frames][];
        exN = new int[frames];
        deepX = deep ? (width + DEEP - 1) / DEEP : 0;
        deepY = deep ? (height + DEEP - 1) / DEEP : 0;
        noiseX = (deepX + NOISE_CELLS - 1) / NOISE_CELLS;
        noiseY = (deepY + NOISE_CELLS - 1) / NOISE_CELLS;
        this.deep = deep ? new byte[frames][(deepX * deepY + 1) / 2] : null;
        noiseSum = deep ? new float[frames][noiseX * noiseY] : null;
        noiseCnt = deep ? new int[frames][noiseX * noiseY] : null;
        deepScale = deep ? new float[frames] : null;
        deepScaleN = deep ? new int[frames] : null;
    }

    /** Memory the deep map of so many frames of a picture so big takes (bytes). */
    public static long deepBytes(int frames, int width, int height) {
        long cells = (long) ((width + DEEP - 1) / DEEP) * ((height + DEEP - 1) / DEEP);
        long blocks = (long) ((width + DEEP * NOISE_CELLS - 1) / (DEEP * NOISE_CELLS)) * ((height + DEEP * NOISE_CELLS - 1) / (DEEP * NOISE_CELLS));
        return frames * ((cells + 1) / 2 + 8 * blocks);
    }

    /** Whether the deep map is kept. */
    public boolean deep() {
        return deep != null;
    }

    /** Starts a band of rows [y0, y1) (y0 a multiple of {@link #DEEP}), its frames taken together. */
    void beginBand(int y0, int y1) {
        beginBand(y0, y1, false);
    }

    /**
     * Starts a band of rows [y0, y1) (y0 a multiple of {@link #DEEP}); {@code oneAtATime} when the stacker goes
     * through the frames one by one (and calls {@link #endFrameBand} after each).
     */
    void beginBand(int y0, int y1, boolean oneAtATime) {
        bandCellY0 = y0 / CELL;
        bandCellRows = (y1 - y0 + CELL - 1) / CELL;
        int n = frames * bandCellRows * cellsX;
        if (counts.length < n) {
            counts = new byte[n];
            excess = new float[n];
        } else {
            Arrays.fill(counts, 0, n, (byte) 0);
            Arrays.fill(excess, 0, n, 0f);
        }
        if (deep == null) return;
        if (y0 % DEEP != 0) throw new IllegalArgumentException("a band must start on a deep cell: " + y0);
        bandDeepY0 = y0 / DEEP;
        bandDeepRows = (y1 - y0 + DEEP - 1) / DEEP;
        slots = oneAtATime ? 1 : frames;
        int m = slots * bandDeepRows * deepX;
        if (dSum.length < m) {
            dSum = new float[m];
            dGain = new float[m];
            dCnt = new short[m];
        } else {
            Arrays.fill(dSum, 0, m, 0f);
            Arrays.fill(dGain, 0, m, 0f);
            Arrays.fill(dCnt, 0, m, (short) 0);
        }
    }

    /**
     * White's camera colour (red, green, blue, as the DNG's as-shot neutral), for what the deep map's values stand for
     * in light; {1, 1, 1} until set.
     */
    public void setWhite(float[] neutral) {
        if (neutral == null || neutral.length < 3 || !(neutral[1] > 0)) return;
        for (int c = 0; c < 3; c++) white[c] = neutral[c] > 0 ? neutral[c] / neutral[1] : 1f;
    }

    /** Where a plan's pixel in the stack lay on its sensor. */
    public interface Sensor {
        /** Plan f's stack pixel (x, y) on its sensor (pixels) into {@code out}; false if it wasn't on it. */
        boolean at(int f, double x, double y, double[] out);
    }

    /** Where each plan's pixels lay on its sensor, for {@link #sensorCells}; until set, where they lie in the stack. */
    public void setSensor(Sensor s) {
        sensor = s;
    }

    /** Deep cells apart that {@link #sensorCells} works out exactly; it draws straight between them. */
    static final int SENSOR_STEP = 8;

    /**
     * Where plan f's deep cells' centres lay on its sensor, in deep cells: its columns into {@code col}, its rows into
     * {@code row} (row by row, as {@link #deepMap}; NaN where it can't be told). Worked out every
     * {@link #SENSOR_STEP} cells and drawn straight between (the lens bends lines only slowly).
     */
    public void sensorCells(int f, float[] col, float[] row) {
        int nx = (deepX - 1 + SENSOR_STEP - 1) / SENSOR_STEP + 1, ny = (deepY - 1 + SENSOR_STEP - 1) / SENSOR_STEP + 1;
        float[] gx = new float[nx * ny], gy = new float[nx * ny];
        double[] p = new double[2];
        for (int j = 0; j < ny; j++) {
            int cy = Math.min(deepY - 1, j * SENSOR_STEP);
            for (int i = 0; i < nx; i++) {
                int cx = Math.min(deepX - 1, i * SENSOR_STEP);
                double x = (cx + 0.5) * DEEP, y = (cy + 0.5) * DEEP;
                boolean on = sensor == null ? set(p, x, y) : sensor.at(f, x, y, p);
                gx[j * nx + i] = on ? (float) (p[0] / DEEP - 0.5) : Float.NaN;
                gy[j * nx + i] = on ? (float) (p[1] / DEEP - 0.5) : Float.NaN;
            }
        }
        for (int cy = 0; cy < deepY; cy++) {
            int j = Math.min(ny - 2, cy / SENSOR_STEP);
            if (ny == 1) j = 0;
            int ya = Math.min(deepY - 1, j * SENSOR_STEP), yb = Math.min(deepY - 1, (j + 1) * SENSOR_STEP);
            float ty = yb > ya ? (cy - ya) / (float) (yb - ya) : 0f;
            int j1 = ny == 1 ? 0 : j + 1;
            for (int cx = 0; cx < deepX; cx++) {
                int i = Math.min(nx - 2, cx / SENSOR_STEP);
                if (nx == 1) i = 0;
                int xa = Math.min(deepX - 1, i * SENSOR_STEP), xb = Math.min(deepX - 1, (i + 1) * SENSOR_STEP);
                float tx = xb > xa ? (cx - xa) / (float) (xb - xa) : 0f;
                int i1 = nx == 1 ? 0 : i + 1;
                int a = j * nx + i, b = j * nx + i1, c = j1 * nx + i, d = j1 * nx + i1;
                col[cy * deepX + cx] = (1 - ty) * ((1 - tx) * gx[a] + tx * gx[b]) + ty * ((1 - tx) * gx[c] + tx * gx[d]);
                row[cy * deepX + cx] = (1 - ty) * ((1 - tx) * gy[a] + tx * gy[b]) + ty * ((1 - tx) * gy[c] + tx * gy[d]);
            }
        }
    }

    private static boolean set(double[] p, double x, double y) {
        p[0] = x;
        p[1] = y;
        return true;
    }

    /**
     * A thread's pixels for rows [ya, yb) of the band, added up per plan and deep cell and handed over in one go
     * ({@link DeepBatch#flush}, which also returns it for reuse).
     */
    DeepBatch deepBatch(int ya, int yb) {
        DeepBatch b;
        synchronized (pool) {
            b = pool.poll();
        }
        if (b == null) b = new DeepBatch();
        b.begin(ya, yb);
        return b;
    }

    final class DeepBatch {
        private float[] sum = new float[0], gain = new float[0];
        private short[] cnt = new short[0];
        private int row0, rows;

        private void begin(int ya, int yb) {
            row0 = ya / DEEP;
            rows = Math.max(1, (yb - 1) / DEEP - row0 + 1);
            int n = slots * rows * deepX;
            if (sum.length < n) {
                sum = new float[n];
                gain = new float[n];
                cnt = new short[n];
            } else {
                Arrays.fill(sum, 0, n, 0f);
                Arrays.fill(gain, 0, n, 0f);
                Arrays.fill(cnt, 0, n, (short) 0);
            }
        }

        /**
         * Plan f's pixel (x, y): its samples' distances from the stack's centre {@code z[o]} to {@code z[o + 2]} (red,
         * green, blue; NaN for a colour without one), each in units of the noise it was divided by, {@code noise[o]}
         * to {@code noise[o + 2]}. Each colour counts as much as a white light raises it, white's share over that
         * noise, the weights' squares adding up to 1: the pixel's value stays standard normal where there's only sky,
         * and a white trail raises it most for its noise (each colour's samples, summed over a cell, spread alike: a
         * green photosite is shared with fewer pixels than a red or blue one, but there are twice as many).
         */
        void add(int f, int x, int y, float[] z, float[] noise, int o) {
            double g2 = 0;
            for (int c = 0; c < 3; c++) {
                float s = noise[o + c];
                if (z[o + c] == z[o + c] && s > 0) g2 += (white[c] / s) * (white[c] / s);
            }
            if (!(g2 > 0)) return;
            double g = Math.sqrt(g2), v = 0;
            for (int c = 0; c < 3; c++) {
                float s = noise[o + c], zc = z[o + c];
                if (!(zc == zc) || !(s > 0)) continue;
                v += white[c] / s / g * (zc < DEEP_LOW ? DEEP_LOW : zc > DEEP_HIGH ? DEEP_HIGH : zc);
            }
            int i = ((slots == 1 ? 0 : f) * rows + y / DEEP - row0) * deepX + x / DEEP;
            sum[i] += (float) v;
            gain[i] += (float) g;
            cnt[i]++;
        }

        void flush() {
            synchronized (StreakEvidence.this) {
                for (int slot = 0; slot < slots; slot++) {
                    for (int r = 0; r < rows; r++) {
                        int br = row0 + r - bandDeepY0;
                        if (br < 0 || br >= bandDeepRows) continue;
                        int src = (slot * rows + r) * deepX, dst = (slot * bandDeepRows + br) * deepX;
                        for (int cx = 0; cx < deepX; cx++) {
                            short c = cnt[src + cx];
                            if (c == 0) continue;
                            dSum[dst + cx] += sum[src + cx];
                            dGain[dst + cx] += gain[src + cx];
                            dCnt[dst + cx] += c;
                        }
                    }
                }
            }
            synchronized (pool) {
                pool.push(this);
            }
        }
    }

    /** Plan f's samples of the band are all in (frames one at a time): into its deep map. */
    void endFrameBand(int f) {
        if (deep == null) return;
        encodeDeep(0, f);
        int m = bandDeepRows * deepX;
        Arrays.fill(dSum, 0, m, 0f);
        Arrays.fill(dGain, 0, m, 0f);
        Arrays.fill(dCnt, 0, m, (short) 0);
    }

    /**
     * A slot's deep cells of the band into plan f's map, divided by the spread of the plan's cells so far (this
     * band's included), and their gain (over that spread) into its light map.
     */
    private void encodeDeep(int slot, int f) {
        // the band's cells as they are: their spread
        Arrays.fill(spreadHist, 0);
        int k = 0;
        for (int r = 0; r < bandDeepRows && bandDeepY0 + r < deepY; r++) {
            int o = (slot * bandDeepRows + r) * deepX;
            for (int cx = 0; cx < deepX; cx++) {
                int n = dCnt[o + cx];
                if (n < DEEP_MIN_PIXELS) continue;
                double z = dSum[o + cx] / Math.sqrt(n);
                int bin = (int) Math.floor((z + SPREAD_RANGE) * SPREAD_BINS / (2 * SPREAD_RANGE));
                spreadHist[bin < 0 ? 0 : bin >= SPREAD_BINS ? SPREAD_BINS - 1 : bin]++;
                k++;
            }
        }
        if (k >= SPREAD_MIN_CELLS) {
            double sd = (histQuantile(spreadHist, k, 0.75) - histQuantile(spreadHist, k, 0.25)) / 1.3490;
            if (sd > 0) {
                deepScale[f] = (float) ((deepScale[f] * (double) deepScaleN[f] + sd * k) / (deepScaleN[f] + k));
                deepScaleN[f] += k;
            }
        }
        float scale = scaleOf(f);
        byte[] m = deep[f];
        float[] ns = noiseSum[f];
        int[] nc = noiseCnt[f];
        for (int r = 0; r < bandDeepRows; r++) {
            int cy = bandDeepY0 + r;
            if (cy >= deepY) break;
            int o = (slot * bandDeepRows + r) * deepX;
            for (int cx = 0; cx < deepX; cx++) {
                int n = dCnt[o + cx];
                if (n == 0) continue;
                int b = (cy / NOISE_CELLS) * noiseX + cx / NOISE_CELLS;
                ns[b] += dGain[o + cx] / scale;
                nc[b] += n;
                if (n < DEEP_MIN_PIXELS) continue;
                double z = dSum[o + cx] / Math.sqrt(n) / scale;
                int code = 1 + (int) Math.round(Math.max(0, Math.min(DEEP_CODES - 1, (z - DEEP_MIN) / DEEP_STEP)));
                int p = cy * deepX + cx;
                m[p >> 1] |= (byte) ((p & 1) == 0 ? code : code << 4);
            }
        }
    }

    /**
     * The spread plan f's deep cells are divided by: its own so far, else that of the plans before it (a band with
     * too few of its cells to go by), else 1.
     */
    private float scaleOf(int f) {
        if (deepScaleN[f] > 0) return deepScale[f];
        double sum = 0;
        long n = 0;
        for (int g = 0; g < frames; g++) {
            sum += deepScale[g] * (double) deepScaleN[g];
            n += deepScaleN[g];
        }
        return n > 0 ? (float) (sum / n) : 1f;
    }

    /** The value below which a share q of a histogram's {@code total} values lie (bins over +-SPREAD_RANGE). */
    private static double histQuantile(int[] hist, int total, double q) {
        double want = q * total, seen = 0, bin = 2 * SPREAD_RANGE / SPREAD_BINS;
        for (int i = 0; i < SPREAD_BINS; i++) {
            if (seen + hist[i] >= want) return -SPREAD_RANGE + bin * (i + (want - seen) / Math.max(1, hist[i]));
            seen += hist[i];
        }
        return SPREAD_RANGE;
    }

    /**
     * Plan f's deep map as values (row by row, {@link #deepX} x {@link #deepY}; NaN where there's nothing to go by),
     * into {@code out} if big enough.
     */
    public float[] deepMap(int f, float[] out) {
        int n = deepX * deepY;
        if (out == null || out.length < n) out = new float[n];
        byte[] m = deep[f];
        for (int p = 0; p < n; p++) {
            int code = (p & 1) == 0 ? m[p >> 1] & 15 : (m[p >> 1] >> 4) & 15;
            out[p] = code == 0 ? Float.NaN : DEEP_MIN + (code - 1) * DEEP_STEP;
        }
        return out;
    }

    /**
     * The spread of plan f's deep map where there's only sky: the cells' interquartile range over that of a
     * standard normal (the counted values can only be in half steps, so the quartiles are interpolated within them).
     * NaN without enough cells.
     */
    public double deepSpread(int f) {
        int[] hist = new int[DEEP_CODES + 1];
        byte[] m = deep[f];
        int n = deepX * deepY, total = 0;
        for (int p = 0; p < n; p++) {
            int code = (p & 1) == 0 ? m[p >> 1] & 15 : (m[p >> 1] >> 4) & 15;
            hist[code]++;
            if (code != 0) total++;
        }
        if (total < 1000) return Double.NaN;
        double q1 = quantile(hist, total, 0.25), q3 = quantile(hist, total, 0.75);
        return (q3 - q1) / 1.3490;
    }

    /** The value below which a share q of the coded cells lie, each code spread evenly over its half step. */
    private static double quantile(int[] hist, int total, double q) {
        double want = q * total, seen = 0;
        for (int code = 1; code <= DEEP_CODES; code++) {
            if (seen + hist[code] >= want) {
                double lo = DEEP_MIN + (code - 1.5) * DEEP_STEP;
                return lo + DEEP_STEP * (want - seen) / Math.max(1, hist[code]);
            }
            seen += hist[code];
        }
        return DEEP_MIN + (DEEP_CODES - 0.5) * DEEP_STEP;
    }

    /**
     * The light (the stack's green units, summed over the cell's pixels) of a white trail that raises a deep cell of
     * plan f near stack pixel (x, y) by 1: a pixel's value rises by its gain for each unit of a white light's green
     * ({@link DeepBatch#add}), a full cell's sum by their mean gain times its light; NaN where it had none.
     */
    public double deepLight(int f, double x, double y) {
        int bx = (int) Math.floor(x / (DEEP * NOISE_CELLS)), by = (int) Math.floor(y / (DEEP * NOISE_CELLS));
        if (bx < 0 || by < 0 || bx >= noiseX || by >= noiseY) return Double.NaN;
        int b = by * noiseX + bx;
        float g = noiseSum[f][b];
        return g > 0 ? Math.sqrt(DEEP_FULL) * noiseCnt[f][b] / g : Double.NaN;
    }

    /** A thread's rejections, to hand over in one go. */
    final class Batch {
        private int[] cells = new int[256];
        private float[] over = new float[256];
        private int n;

        /**
         * Plan f's sample at pixel (x, y) of the band was rejected as too bright, by {@code green} over the stack's
         * centre if it is a green sample (0 for red and blue).
         */
        void add(int f, int x, int y, float green) {
            if (n == cells.length) {
                cells = Arrays.copyOf(cells, n * 2);
                over = Arrays.copyOf(over, n * 2);
            }
            over[n] = green;
            cells[n++] = (f * bandCellRows + (y / CELL - bandCellY0)) * cellsX + x / CELL;
        }

        void flush() {
            if (n == 0) return;
            synchronized (StreakEvidence.this) {
                for (int i = 0; i < n; i++) {
                    int c = cells[i];
                    if (counts[c] < 127) counts[c]++;
                    excess[c] += over[i];
                }
            }
            n = 0;
        }
    }

    Batch batch() {
        return new Batch();
    }

    /** The band is complete: its cells with enough rejections into the frames' maps, and its deep cells. */
    void endBand() {
        if (deep != null && slots > 1) for (int f = 0; f < frames; f++) encodeDeep(f, f);
        for (int f = 0; f < frames; f++) {
            long[] b = bits[f];
            for (int r = 0; r < bandCellRows; r++) {
                int cy = bandCellY0 + r;
                if (cy >= cellsY) break;
                int o = (f * bandCellRows + r) * cellsX;
                for (int cx = 0; cx < cellsX; cx++) {
                    if (counts[o + cx] < MIN_REJECTED) continue;
                    long p = (long) cy * cellsX + cx;
                    b[(int) (p >> 6)] |= 1L << (p & 63);
                    keepExcess(f, (int) p, excess[o + cx]);
                }
            }
        }
    }

    /** Bands come top to bottom, so each plan's cells arrive in ascending order. */
    private void keepExcess(int f, int cell, float v) {
        int n = exN[f];
        if (exCell[f] == null) {
            exCell[f] = new int[256];
            exSum[f] = new float[256];
        } else if (n == exCell[f].length) {
            exCell[f] = Arrays.copyOf(exCell[f], n * 2);
            exSum[f] = Arrays.copyOf(exSum[f], n * 2);
        }
        exCell[f][n] = cell;
        exSum[f][n] = v;
        exN[f] = n + 1;
    }

    /**
     * How much brighter than the stack plan f's green samples rejected in cell (cx, cy) were, summed (the stack's
     * units: a frame's light scaled to the reference frame's); 0 for a cell that didn't count.
     */
    public float excess(int f, int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= cellsX || cy >= cellsY || exCell[f] == null) return 0f;
        int i = Arrays.binarySearch(exCell[f], 0, exN[f], cy * cellsX + cx);
        return i >= 0 ? exSum[f][i] : 0f;
    }

    /** Whether cell (cx, cy) of plan f had a streak's worth of bright rejections. */
    public boolean get(int f, int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= cellsX || cy >= cellsY) return false;
        long p = (long) cy * cellsX + cx;
        return (bits[f][(int) (p >> 6)] & (1L << (p & 63))) != 0;
    }

    /** Clears cell (cx, cy) of plan f (once it is explained). */
    public void clear(int f, int cx, int cy) {
        if (cx < 0 || cy < 0 || cx >= cellsX || cy >= cellsY) return;
        long p = (long) cy * cellsX + cx;
        bits[f][(int) (p >> 6)] &= ~(1L << (p & 63));
    }

    /** The set cells of plan f, as indices (row by row). */
    public int[] cells(int f) {
        int[] out = new int[count(f)];
        int k = 0;
        long[] b = bits[f];
        for (int w = 0; w < b.length; w++) {
            long v = b[w];
            while (v != 0) {
                int i = Long.numberOfTrailingZeros(v);
                v &= v - 1;
                out[k++] = (w << 6) + i;
            }
        }
        return out;
    }

    /** Set cells of plan f. */
    public int count(int f) {
        int n = 0;
        for (long w : bits[f]) n += Long.bitCount(w);
        return n;
    }

    /** A copy (matching clears cells as it explains them). */
    public StreakEvidence copy() {
        return new StreakEvidence(this);
    }

    private StreakEvidence(StreakEvidence o) {
        frames = o.frames;
        cellsX = o.cellsX;
        cellsY = o.cellsY;
        // the deep map stays as measured too (whoever reads it decodes a copy)
        deepX = o.deepX;
        deepY = o.deepY;
        deep = o.deep;
        noiseSum = o.noiseSum;
        noiseCnt = o.noiseCnt;
        System.arraycopy(o.white, 0, white, 0, 3);
        sensor = o.sensor;
        noiseX = o.noiseX;
        noiseY = o.noiseY;
        deepScale = o.deepScale;
        deepScaleN = o.deepScaleN;
        bits = new long[frames][];
        for (int f = 0; f < frames; f++) bits[f] = o.bits[f].clone();
        // the light stays as measured: only the cells get cleared
        exCell = o.exCell;
        exSum = o.exSum;
        exN = o.exN;
    }
}
