package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Names the streaks: each frame's predicted satellite tracks ({@link SatPredict}) against the cells where
 * that frame had samples rejected as too bright ({@link StreakEvidence}).
 * <p>
 * A track is tried at every offset across its path within the elements' across-track uncertainty (tight:
 * 0.15 degrees plus 0.05 a day of age) and every shift along it within the timing uncertainty (looser: 2
 * seconds plus 1 a day), and counts the bright cells it then runs through during the exposure. The best fit
 * that runs through enough of them is a match; the cells it explains are taken away, the brightest
 * predicted satellites first, so a docked capsule doesn't claim its station's streak and a train of satellites
 * shares out its streaks. Whatever lines are left (a plane, a meteor, a satellite that isn't in the data) are
 * found by a Hough transform as unidentified streaks.
 * <p>
 * Every streak, named or not, becomes a corridor to leave out of its frame when the stack is made again: as
 * long as the line of evidence (plus a few cells at the ends for its faint start and end) and as wide as its
 * brightest part needs.
 */
public final class SatMatch {
    /** A streak in one frame. */
    public static final class Streak {
        /** The satellite, or null for an unidentified streak. */
        public SatCatalog.Sat sat;
        public int unit, plan;
        /** End points in the stack's pixels (the part the evidence shows), and the corridor's half width. */
        public float x0, y0, x1, y1, halfWidth;
        /** Bright cells on it, and of those expected along its predicted path (named streaks). */
        public int cells, expected;
        /** Offset across the predicted path (pixels) and along it (seconds) at the best fit; named streaks. */
        public float across, alongSec;
        /** When it was in the picture (UTC Julian dates: the exposure's part it is seen in), and how bright. */
        public double jd0 = Double.NaN, jd1 = Double.NaN;
        public float mag = Float.NaN;
        /** The predicted track (named streaks; for drawing), else null. */
        public SatPredict.Track track;
        /** A guess for an unidentified streak: "plane" (blinking), "meteor" (short, one frame) or "" (unknown). */
        public String kind = "";

        public String name() {
            return sat != null ? sat.displayName() : "unidentified streak";
        }

        public double lengthPx() {
            return Math.hypot(x1 - x0, y1 - y0);
        }
    }

    /** What was found in a session. */
    public static final class Result {
        public final List<Streak> streaks = new ArrayList<Streak>();
        /** Predicted tracks through the picture that showed no streak (too faint, or in the Earth's shadow). */
        public int quiet;
        /** Tracks predicted. */
        public int predicted;
        /** Mean offsets of the named streaks of fresh elements (pixels across, seconds along), NaN if none. */
        public double meanAcross = Double.NaN, meanAlongSec = Double.NaN;

        public int named() {
            int n = 0;
            for (Streak s : streaks) if (s.sat != null) n++;
            return n;
        }

        public int unidentified() {
            return streaks.size() - named();
        }
    }

    /** Fewest bright cells for a streak, and the share of its predicted path it must show to be named. */
    static final int MIN_CELLS = 10;
    static final float MIN_SHARE = 0.25f;
    /** Cells either side of a path that count as on it. */
    static final int WIDTH_CELLS = 1;
    /** Fewest cells an unidentified streak needs, and the largest gap (cells) inside one. */
    static final int MIN_FREE_CELLS = 14, MAX_GAP = 4;

    private SatMatch() {}

    /**
     * @param tracks    predicted tracks (in the stack's pixels)
     * @param planOf    plan index of each unit (index = unit; -1 if the unit isn't in the stack)
     * @param ev        the first stack's evidence (not changed)
     * @param degPerPx  the picture's scale at its centre
     * @param width     the picture's width (pixels)
     */
    public static Result match(List<SatPredict.Track> tracks, int[] planOf, StreakEvidence ev, double degPerPx, int width) {
        Result r = new Result();
        StreakEvidence left = ev.copy();
        List<SatPredict.Track> order = new ArrayList<SatPredict.Track>(tracks);
        // brightest predicted first: they claim their cells before a fainter satellite near them can
        Collections.sort(order, new Comparator<SatPredict.Track>() {
            @Override
            public int compare(SatPredict.Track a, SatPredict.Track b) {
                return Float.compare(a.brightest(), b.brightest());
            }
        });
        double sumA = 0, sumT = 0;
        int nA = 0;
        for (SatPredict.Track t : order) {
            if (t.unit < 0 || t.unit >= planOf.length || planOf[t.unit] < 0) continue;
            int f = planOf[t.unit];
            r.predicted++;
            Streak s = fit(t, f, left, degPerPx);
            if (s == null) {
                r.quiet++;
                continue;
            }
            claim(left, f, s);
            r.streaks.add(s);
            if (Math.abs(t.ageDays) < 1) {
                sumA += s.across;
                sumT += s.alongSec;
                nA++;
            }
        }
        if (nA > 0) {
            r.meanAcross = sumA / nA;
            r.meanAlongSec = sumT / nA;
        }
        for (int f = 0; f < ev.frames; f++) {
            for (Streak s : unidentified(left, f)) {
                s.plan = f;
                for (int u = 0; u < planOf.length; u++) if (planOf[u] == f) s.unit = u;
                claim(left, f, s);
                r.streaks.add(s);
            }
        }
        guessKinds(r.streaks, width);
        return r;
    }

    // ------------------------------------------------------------------------------------------------
    // Named streaks

    /**
     * The best fit of one track to frame f's evidence, or null if no offset and shift runs through enough
     * bright cells.
     */
    static Streak fit(SatPredict.Track t, int f, StreakEvidence ev, double degPerPx) {
        int n = t.jd.length;
        // the path in cells, sample by sample (NaN where not seen)
        double c = StreakEvidence.CELL;
        double acrossPx = t.acrossTolDeg() / degPerPx;
        int acrossCells = Math.max(1, (int) Math.ceil(acrossPx / c));
        int shifts = (int) Math.ceil(Math.min(t.alongTol(), t.margin) / SatPredict.FINE_S);
        int bestScore = -1, bestOff = 0, bestShift = 0, bestExp = 0;
        for (int sh = -shifts; sh <= shifts; sh++) {
            int a = t.in0 + sh, b = t.in1 + sh;
            if (a < 0 || b >= n) continue;
            for (int off = -acrossCells; off <= acrossCells; off++) {
                int[] score = walk(t, f, ev, a, b, off * c, null, null, null);
                // ties: the smaller offset and shift (the prediction as it stands)
                int sc = score[0];
                if (sc > bestScore || sc == bestScore && Math.abs(off) + Math.abs(sh) < Math.abs(bestOff) + Math.abs(bestShift)) {
                    bestScore = sc;
                    bestOff = off;
                    bestShift = sh;
                    bestExp = score[1];
                }
            }
        }
        if (bestScore < MIN_CELLS || bestScore < MIN_SHARE * bestExp) return null;
        // refine across to the pixel around the best cell offset
        double bestPx = bestOff * c;
        int a = t.in0 + bestShift, b = t.in1 + bestShift;
        for (double d = bestOff * c - c; d <= bestOff * c + c + 1e-9; d += 1) {
            int sc = walk(t, f, ev, a, b, d, null, null, null)[0];
            if (sc > bestScore) {
                bestScore = sc;
                bestPx = d;
            }
        }
        float[] ends = new float[4];
        double[] times = new double[2];
        float[] width = new float[1];
        walk(t, f, ev, a, b, bestPx, ends, times, width);
        // the path was walked at the predicted times of the shifted samples: the satellite was there that much earlier or later
        double shiftDays = bestShift * SatPredict.FINE_S / 86400;
        Streak s = new Streak();
        s.sat = t.sat;
        s.unit = t.unit;
        s.plan = f;
        s.track = t;
        s.cells = bestScore;
        s.expected = bestExp;
        s.across = (float) bestPx;
        s.alongSec = (float) (bestShift * SatPredict.FINE_S);
        s.x0 = ends[0];
        s.y0 = ends[1];
        s.x1 = ends[2];
        s.y1 = ends[3];
        s.jd0 = times[0] - shiftDays;
        s.jd1 = times[1] - shiftDays;
        s.mag = t.brightest();
        s.halfWidth = width[0];
        return s;
    }

    /**
     * Walks the path from sample a to b, moved {@code offPx} pixels across it (to its left), through frame f's
     * evidence: {bright cells met, cells walked}. With {@code ends}, also the first and last bright cell's
     * points on the path, their times, and the corridor's half width.
     */
    private static int[] walk(SatPredict.Track t, int f, StreakEvidence ev, int a, int b, double offPx, float[] ends,
                              double[] times, float[] width) {
        double c = StreakEvidence.CELL;
        int hits = 0, walked = 0;
        int lastCx = Integer.MIN_VALUE, lastCy = Integer.MIN_VALUE;
        double fx = Double.NaN, fy = 0, lx = 0, ly = 0, ft = 0, lt = 0;
        int widest = 0;
        for (int i = a; i < b; i++) {
            float x0 = t.x[i], y0 = t.y[i], x1 = t.x[i + 1], y1 = t.y[i + 1];
            if (Float.isNaN(x0) || Float.isNaN(x1)) continue;
            double dx = x1 - x0, dy = y1 - y0, len = Math.hypot(dx, dy);
            if (len < 1e-6) continue;
            double nx = -dy / len, ny = dx / len;
            int steps = Math.max(1, (int) Math.ceil(len / c));
            for (int k = 0; k < steps; k++) {
                double u = (k + 0.5) / steps;
                double px = x0 + dx * u + nx * offPx, py = y0 + dy * u + ny * offPx;
                int cx = (int) Math.floor(px / c), cy = (int) Math.floor(py / c);
                if (cx == lastCx && cy == lastCy) continue;
                lastCx = cx;
                lastCy = cy;
                if (cx < 0 || cy < 0 || cx >= ev.cellsX || cy >= ev.cellsY) continue;
                walked++;
                // the path's cell or its neighbours across the path
                int hitW = -1;
                for (int w = 0; w <= WIDTH_CELLS && hitW < 0; w++) {
                    for (int sgn = -1; sgn <= 1; sgn += 2) {
                        int qx = (int) Math.floor((px + sgn * w * c * nx) / c), qy = (int) Math.floor((py + sgn * w * c * ny) / c);
                        if (ev.get(f, qx, qy)) {
                            hitW = w;
                            break;
                        }
                        if (w == 0) break;
                    }
                }
                if (hitW < 0) continue;
                hits++;
                if (ends != null) {
                    double tt = t.jd[i] + (t.jd[i + 1] - t.jd[i]) * u;
                    if (Double.isNaN(fx)) {
                        fx = px;
                        fy = py;
                        ft = tt;
                    }
                    lx = px;
                    ly = py;
                    lt = tt;
                    // how wide the bright cells run across the path here
                    int wc = 0;
                    for (int w = 1; w <= 4; w++) {
                        boolean any = false;
                        for (int sgn = -1; sgn <= 1; sgn += 2) {
                            int qx = (int) Math.floor((px + sgn * w * c * nx) / c), qy = (int) Math.floor((py + sgn * w * c * ny) / c);
                            any |= ev.get(f, qx, qy);
                        }
                        if (!any) break;
                        wc = w;
                    }
                    widest = Math.max(widest, wc);
                }
            }
        }
        if (ends != null) {
            if (Double.isNaN(fx)) {
                Arrays.fill(ends, Float.NaN);
            } else {
                // a few cells beyond the evidence at both ends: the streak fades in and out below the rejection
                double dx = lx - fx, dy = ly - fy, len = Math.max(1e-6, Math.hypot(dx, dy)), ext = 3 * c;
                ends[0] = (float) (fx - dx / len * ext);
                ends[1] = (float) (fy - dy / len * ext);
                ends[2] = (float) (lx + dx / len * ext);
                ends[3] = (float) (ly + dy / len * ext);
            }
            times[0] = ft;
            times[1] = lt;
            width[0] = (float) ((widest + 1.5) * c + 2);
        }
        return new int[]{hits, walked};
    }

    /** Clears frame f's cells along a streak's corridor, so they explain nothing else. */
    static void claim(StreakEvidence ev, int f, Streak s) {
        if (Float.isNaN(s.x0)) return;
        double c = StreakEvidence.CELL;
        double dx = s.x1 - s.x0, dy = s.y1 - s.y0, len = Math.hypot(dx, dy);
        int steps = Math.max(1, (int) Math.ceil(len / (c * 0.5)));
        double nx = len > 0 ? -dy / len : 0, ny = len > 0 ? dx / len : 1;
        double hw = s.halfWidth + c;
        int across = (int) Math.ceil(hw / (c * 0.5));
        for (int k = 0; k <= steps; k++) {
            double px = s.x0 + dx * k / steps, py = s.y0 + dy * k / steps;
            for (int w = -across; w <= across; w++) {
                double qx = px + nx * w * c * 0.5, qy = py + ny * w * c * 0.5;
                ev.clear(f, (int) Math.floor(qx / c), (int) Math.floor(qy / c));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Unidentified streaks

    /** Angles in the Hough transform. */
    static final int ANGLES = 360;

    /**
     * Lines of bright cells left in frame f after the named streaks: a Hough transform of the cells, the
     * strongest line first, kept as a streak when its longest run of cells (gaps of up to {@link #MAX_GAP}
     * allowed) has {@link #MIN_FREE_CELLS} or more; its cells are then taken away and the next line is looked
     * for. Gives up after a few lines or when there are too few cells.
     */
    static List<Streak> unidentified(StreakEvidence ev, int f) {
        List<Streak> out = new ArrayList<Streak>();
        int n = ev.count(f);
        // too few cells for a line; or too many scattered ones (a frame full of rejected stars, or noise), where
        // no line can be told apart
        if (n < MIN_FREE_CELLS || n > 20000) return out;
        // the cells, as points
        int[] px = new int[n], py = new int[n];
        int k = 0;
        long[] bits = ev.bits[f];
        for (int w = 0; w < bits.length; w++) {
            long v = bits[w];
            while (v != 0) {
                int b = Long.numberOfTrailingZeros(v);
                v &= v - 1;
                long p = ((long) w << 6) + b;
                if (k < n) {
                    px[k] = (int) (p % ev.cellsX);
                    py[k] = (int) (p / ev.cellsX);
                    k++;
                }
            }
        }
        double diag = Math.hypot(ev.cellsX, ev.cellsY);
        int rhos = (int) Math.ceil(2 * diag) + 2;
        int[] acc = new int[ANGLES * rhos];
        double[] cs = new double[ANGLES], sn = new double[ANGLES];
        for (int a = 0; a < ANGLES; a++) {
            cs[a] = Math.cos(Math.PI * a / ANGLES);
            sn[a] = Math.sin(Math.PI * a / ANGLES);
        }
        boolean[] gone = new boolean[n];
        for (int i = 0; i < n; i++) vote(acc, rhos, diag, cs, sn, px[i], py[i], 1);
        for (int tries = 0; tries < 12; tries++) {
            int best = -1, bestV = MIN_FREE_CELLS - 1;
            for (int i = 0; i < acc.length; i++) {
                if (acc[i] > bestV) {
                    bestV = acc[i];
                    best = i;
                }
            }
            if (best < 0) break;
            int a = best / rhos;
            double rho = best % rhos - diag;
            // the cells within a cell and a half of the line, ordered along it
            List<double[]> on = new ArrayList<double[]>();
            for (int i = 0; i < n; i++) {
                if (gone[i]) continue;
                double d = (px[i] + 0.5) * cs[a] + (py[i] + 0.5) * sn[a] - rho;
                if (Math.abs(d) <= 1.5) on.add(new double[]{(px[i] + 0.5) * -sn[a] + (py[i] + 0.5) * cs[a], i, d});
            }
            Collections.sort(on, new Comparator<double[]>() {
                @Override
                public int compare(double[] p, double[] q) {
                    return Double.compare(p[0], q[0]);
                }
            });
            // the longest run with small gaps
            int runA = 0, bestA = 0, bestB = -1;
            for (int i = 1; i <= on.size(); i++) {
                if (i == on.size() || on.get(i)[0] - on.get(i - 1)[0] > MAX_GAP + 1) {
                    if (i - runA > bestB - bestA + 1) {
                        bestA = runA;
                        bestB = i - 1;
                    }
                    runA = i;
                }
            }
            // whatever the outcome, this line's votes go
            acc[best] = 0;
            if (bestB - bestA + 1 < MIN_FREE_CELLS) continue;
            double t0 = on.get(bestA)[0], t1 = on.get(bestB)[0];
            int cells = bestB - bestA + 1;
            // a line, not a crowd: the run must be mostly filled (gaps aside, a streak hits most cells along it)
            if (cells < 0.4 * (t1 - t0 + 1)) continue;
            double c = StreakEvidence.CELL;
            Streak s = new Streak();
            double ux = -sn[a], uy = cs[a], ox = cs[a] * rho, oy = sn[a] * rho;
            double ext = 3;
            s.x0 = (float) ((ox + ux * (t0 - ext)) * c);
            s.y0 = (float) ((oy + uy * (t0 - ext)) * c);
            s.x1 = (float) ((ox + ux * (t1 + ext)) * c);
            s.y1 = (float) ((oy + uy * (t1 + ext)) * c);
            s.cells = cells;
            // width: how far the run's cells spread across the line
            double spread = 0;
            for (int i = bestA; i <= bestB; i++) spread = Math.max(spread, Math.abs(on.get(i)[2]));
            s.halfWidth = (float) ((spread + 2) * c + 2);
            s.kind = "";
            // gaps: a blinking light leaves regular gaps
            int gaps = 0;
            for (int i = bestA + 1; i <= bestB; i++) if (on.get(i)[0] - on.get(i - 1)[0] > 1.5) gaps++;
            if (gaps >= 4 && gaps > 0.2 * cells) s.kind = "plane";
            out.add(s);
            for (int i = bestA; i <= bestB; i++) {
                int q = (int) on.get(i)[1];
                gone[q] = true;
                vote(acc, rhos, diag, cs, sn, px[q], py[q], -1);
            }
        }
        return out;
    }

    private static void vote(int[] acc, int rhos, double diag, double[] cs, double[] sn, int x, int y, int v) {
        double cx = x + 0.5, cy = y + 0.5;
        for (int a = 0; a < ANGLES; a++) {
            int r = (int) Math.round(cx * cs[a] + cy * sn[a] + diag);
            if (r >= 0 && r < rhos) acc[a * rhos + r] += v;
        }
    }

    /**
     * Unidentified streaks: one that continues into the next or the last frame along the same line is a plane
     * or a satellite missing from the data; a short one in a single frame, a meteor.
     */
    static void guessKinds(List<Streak> all, int width) {
        for (Streak s : all) {
            if (s.sat != null || !s.kind.isEmpty()) continue;
            boolean continues = false;
            for (Streak o : all) {
                if (o == s || o.sat != null || Math.abs(o.plan - s.plan) != 1) continue;
                if (collinear(s, o)) continues = true;
            }
            if (!continues && s.lengthPx() < 0.15 * width) s.kind = "meteor";
        }
    }

    private static boolean collinear(Streak a, Streak b) {
        double dx = a.x1 - a.x0, dy = a.y1 - a.y0, len = Math.hypot(dx, dy);
        double ex = b.x1 - b.x0, ey = b.y1 - b.y0, len2 = Math.hypot(ex, ey);
        if (len < 1 || len2 < 1) return false;
        double cosA = Math.abs((dx * ex + dy * ey) / (len * len2));
        if (cosA < Math.cos(Math.toRadians(3))) return false;
        double d0 = Math.abs((b.x0 - a.x0) * -dy / len + (b.y0 - a.y0) * dx / len);
        return d0 < 3 * StreakEvidence.CELL + a.halfWidth;
    }

    /** One line for the log. */
    public static String describe(Result r) {
        StringBuilder b = new StringBuilder(String.format(Locale.US, "%d tracks predicted, %d named, %d quiet, %d unidentified",
                r.predicted, r.named(), r.quiet, r.unidentified()));
        if (!Double.isNaN(r.meanAcross)) {
            b.append(String.format(Locale.US, "; fresh elements off by %.1f px across, %.2f s along", r.meanAcross, r.meanAlongSec));
        }
        return b.toString();
    }
}
