package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Detections fixed to the sensor rather than the sky: warm pixels, gaps between the leaves of a branch, a lit
 * roof edge. At the zenith the sky drifts several pixels a minute, so a detection that comes back at the same
 * sensor position in frames far apart in time is not a star. Where several of them gather (a branch, a roof
 * line) the whole neighbourhood is left out of star matching, so the alignment can't latch onto it.
 */
public final class StaticSources {
    /** Same position: within this many pixels. */
    static final float SAME_PX = 0.8f;
    /** Side of the cells (pixels) that are masked where fixed points crowd. */
    static final int CELL = 64;
    /** Fixed points in a 3 x 3 block of cells that mask its centre cell, at least, and above what scattered
     *  warm pixels put there by chance (their mean plus 4 standard deviations). */
    static final int CROWD = 4;
    /** Slowest drift at the zenith as a fraction of the focal length per second: Earth's rotation rate times
     *  cos(65 deg), so it holds up to 65 degrees latitude. */
    static final double MIN_DRIFT_RATE = 7.2921159e-5 * 0.42;
    /** Drift (pixels) between two frames before a detection at the same place counts against it. */
    static final double MIN_DRIFT_PX = 3.0;

    private final int W, H, cnx, cny;
    /** Fixed points (x, y pairs). */
    public final float[] points;
    public final int count;
    private final boolean[] masked;
    private final int maskedCells;

    private StaticSources(int width, int height, float[] pts, int n, boolean[] masked, int maskedCells) {
        W = width;
        H = height;
        cnx = (width + CELL - 1) / CELL;
        cny = (height + CELL - 1) / CELL;
        points = pts;
        count = n;
        this.masked = masked;
        this.maskedCells = maskedCells;
    }

    /** Nothing fixed (too few frames, or too short a session to tell). */
    public static StaticSources none(int width, int height) {
        int cnx = (width + CELL - 1) / CELL, cny = (height + CELL - 1) / CELL;
        return new StaticSources(width, height, new float[0], 0, new boolean[cnx * cny], 0);
    }

    /**
     * @param lists       star lists of the frames (null entries are skipped)
     * @param t           frame times, seconds
     * @param focalPx     focal length in pixels, to know how fast the sky drifts across the sensor
     */
    public static StaticSources find(List<StarList> lists, double[] t, double focalPx, int width, int height) {
        int nf = lists.size();
        double minGap = MIN_DRIFT_PX / (MIN_DRIFT_RATE * Math.max(1, focalPx));
        // every detection in a spatial hash of 2-px cells: frame and index
        int total = 0;
        for (StarList s : lists) if (s != null) total += s.count;
        if (nf < 3 || total == 0) return none(width, height);
        float[] xs = new float[total], ys = new float[total];
        int[] frame = new int[total];
        int k = 0;
        for (int f = 0; f < nf; f++) {
            StarList s = lists.get(f);
            if (s == null) continue;
            for (int i = 0; i < s.count; i++) {
                xs[k] = s.x[i];
                ys[k] = s.y[i];
                frame[k] = f;
                k++;
            }
        }
        Aligner.Grid grid = new Aligner.Grid(xs, ys, total, width, height, 2f);
        // frames far enough in time from each frame for the sky to have moved
        int[] far = new int[nf];
        for (int f = 0; f < nf; f++) {
            if (lists.get(f) == null) continue;
            for (int g = 0; g < nf; g++) if (lists.get(g) != null && Math.abs(t[g] - t[f]) >= minGap) far[f]++;
        }
        boolean[] isStatic = new boolean[total];
        boolean[] seen = new boolean[nf];
        int[] near = new int[Math.max(64, 3 * nf)];
        for (int q = 0; q < total; q++) {
            int f = frame[q];
            if (far[f] < 2) continue;
            int n = grid.within(xs[q], ys[q], SAME_PX, near);
            int hits = 0;
            java.util.Arrays.fill(seen, false);
            for (int j = 0; j < n; j++) {
                int g = frame[near[j]];
                if (g == f || seen[g] || Math.abs(t[g] - t[f]) < minGap) continue;
                seen[g] = true;
                hits++;
            }
            if (hits >= Math.max(2, (int) Math.ceil(0.3 * far[f]))) isStatic[q] = true;
        }
        // merge repeats of the same fixed point
        float[] pts = new float[64];
        int np = 0;
        boolean[] done = new boolean[total];
        for (int q = 0; q < total; q++) {
            if (!isStatic[q] || done[q]) continue;
            int n = grid.within(xs[q], ys[q], 1.5f, near);
            double sx = 0, sy = 0;
            int m = 0;
            for (int j = 0; j < n; j++) {
                int o = near[j];
                if (!isStatic[o] || done[o]) continue;
                done[o] = true;
                sx += xs[o];
                sy += ys[o];
                m++;
            }
            if (m == 0) continue;
            if (2 * np + 2 > pts.length) pts = java.util.Arrays.copyOf(pts, pts.length * 2);
            pts[2 * np] = (float) (sx / m);
            pts[2 * np + 1] = (float) (sy / m);
            np++;
        }
        // cells where fixed points crowd: a branch or a roof line, not a stray warm pixel
        int cnx = (width + CELL - 1) / CELL, cny = (height + CELL - 1) / CELL;
        int[] per = new int[cnx * cny];
        for (int p = 0; p < np; p++) {
            int cx = Stats.clamp((int) (pts[2 * p] / CELL), 0, cnx - 1), cy = Stats.clamp((int) (pts[2 * p + 1] / CELL), 0, cny - 1);
            per[cy * cnx + cx]++;
        }
        double lambda = 9.0 * np / (cnx * cny);
        double crowd = Math.max(CROWD, lambda + 4 * Math.sqrt(lambda));
        boolean[] masked = new boolean[cnx * cny];
        int nm = 0;
        for (int cy = 0; cy < cny; cy++) {
            for (int cx = 0; cx < cnx; cx++) {
                int s = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int x = cx + dx, y = cy + dy;
                        if (x >= 0 && y >= 0 && x < cnx && y < cny) s += per[y * cnx + x];
                    }
                }
                if (s >= crowd && per[cy * cnx + cx] > 0) {
                    masked[cy * cnx + cx] = true;
                    nm++;
                }
            }
        }
        return new StaticSources(width, height, java.util.Arrays.copyOf(pts, 2 * np), np, masked, nm);
    }

    /** True for a position on or next to a fixed point, or in a cell where they crowd. */
    public boolean isFixed(float x, float y) {
        int cx = Stats.clamp((int) (x / CELL), 0, cnx - 1), cy = Stats.clamp((int) (y / CELL), 0, cny - 1);
        if (masked[cy * cnx + cx]) return true;
        for (int p = 0; p < count; p++) {
            float dx = points[2 * p] - x, dy = points[2 * p + 1] - y;
            if (dx * dx + dy * dy <= 1.5f * 1.5f) return true;
        }
        return false;
    }

    /** The list without fixed detections (the same list when nothing is fixed). */
    public StarList filter(StarList s) {
        if (s == null || (count == 0 && maskedCells == 0)) return s;
        StarList out = new StarList(s.count);
        out.background = s.background;
        out.noise = s.noise;
        for (int i = 0; i < s.count; i++) {
            if (!isFixed(s.x[i], s.y[i])) out.add(s.x[i], s.y[i], s.flux[i], s.hfr[i], s.elong[i], s.saturated[i]);
        }
        return out;
    }

    public int maskedCells() {
        return maskedCells;
    }

    /** Where the crowded cells are, in words: "the left edge", "the top and left edges", "the middle". */
    public String where() {
        if (maskedCells == 0) return "";
        double sx = 0, sy = 0;
        int n = 0;
        boolean left = false, right = false, top = false, bottom = false;
        for (int cy = 0; cy < cny; cy++) {
            for (int cx = 0; cx < cnx; cx++) {
                if (!masked[cy * cnx + cx]) continue;
                double x = (cx + 0.5) * CELL / W, y = (cy + 0.5) * CELL / H;
                sx += x;
                sy += y;
                n++;
                left |= x < 0.2;
                right |= x > 0.8;
                top |= y < 0.2;
                bottom |= y > 0.8;
            }
        }
        List<String> sides = new ArrayList<String>();
        if (top) sides.add("top");
        if (bottom) sides.add("bottom");
        if (left) sides.add("left");
        if (right) sides.add("right");
        if (sides.isEmpty()) return "the middle";
        return "the " + PostProcessor.join(sides) + (sides.size() > 1 ? " edges" : " edge");
    }

    public String describe() {
        if (count == 0) return "no fixed points";
        return String.format(Locale.US, "%d fixed point%s (warm pixels, a branch or a roof edge)%s", count,
                count == 1 ? "" : "s", maskedCells > 0 ? ", crowded near " + where() : "");
    }
}
