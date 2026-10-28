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
 * The stacker counts per band of rows (bands start on a cell boundary) and calls {@link #endBand}.
 */
public final class StreakEvidence {
    public static final int CELL = 4;
    /** Bright rejected samples a cell needs. */
    public static final int MIN_REJECTED = 4;

    public final int frames, cellsX, cellsY;
    /** Per plan: bit per cell, row by row. */
    final long[][] bits;
    /** Per plan: the counted cells' indices (ascending) and their green excess, the first exN[f] of them. */
    private final int[][] exCell;
    private final float[][] exSum;
    private final int[] exN;
    // per band: counts per plan and cell row of the band, and the green samples' excess beside them
    private byte[] counts = new byte[0];
    private float[] excess = new float[0];
    private int bandCellY0, bandCellRows;

    public StreakEvidence(int frames, int width, int height) {
        this.frames = frames;
        cellsX = (width + CELL - 1) / CELL;
        cellsY = (height + CELL - 1) / CELL;
        bits = new long[frames][(int) (((long) cellsX * cellsY + 63) >> 6)];
        exCell = new int[frames][];
        exSum = new float[frames][];
        exN = new int[frames];
    }

    /** Starts a band of rows [y0, y1) (y0 a multiple of {@link #CELL}). */
    void beginBand(int y0, int y1) {
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

    /** The band is complete: its cells with enough rejections into the frames' maps. */
    void endBand() {
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
        bits = new long[frames][];
        for (int f = 0; f < frames; f++) bits[f] = o.bits[f].clone();
        // the light stays as measured: only the cells get cleared
        exCell = o.exCell;
        exSum = o.exSum;
        exN = o.exN;
    }
}
