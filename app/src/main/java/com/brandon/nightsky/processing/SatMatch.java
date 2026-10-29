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
        /**
         * What it is: for an unidentified streak "plane" (blinking), "meteor" (in one frame, where no satellite could
         * have left it) or "" (unknown); for a named one "glint" when only its flash of sunlight was seen, else "".
         */
        public String kind = "";
        /** Where the evidence of an unidentified streak begins and ends (stack pixels; its corridor reaches beyond). */
        public float sx0 = Float.NaN, sy0 = Float.NaN, sx1 = Float.NaN, sy1 = Float.NaN;
        /**
         * Its light along it (unidentified streaks): the excess over the stack of its frame's green samples rejected
         * there, summed across it, per cell's length ({@link StreakEvidence#CELL} px) from (sx0, sy0) to (sx1, sy1);
         * only the samples bright enough to be rejected count (a deep streak's: from its cells, as a white trail's
         * green). Null if not measured.
         */
        public float[] light;
        /**
         * Found in its frame's deep map ({@link StreakEvidence#deep}): too faint for the rejections to show; how
         * strong its line was there (sigma), and whether the same line shows in the frame before or after (a
         * satellite or a plane going on).
         */
        public boolean deep;
        public float score;
        boolean onward;

        public String name() {
            return sat != null ? sat.displayName() : "unidentified streak";
        }

        public double lengthPx() {
            return Math.hypot(x1 - x0, y1 - y0);
        }

        /** Length of the part the evidence shows (the corridor's for a named streak). */
        public double seenLengthPx() {
            return Float.isNaN(sx0) ? lengthPx() : Math.hypot(sx1 - sx0, sy1 - sy0);
        }

        /** Its light summed along it (NaN if not measured). */
        public double totalLight() {
            if (light == null) return Double.NaN;
            double t = 0;
            for (float v : light) t += v;
            return t;
        }
    }

    /**
     * What the kinds of unidentified streaks are judged by: when each stacked frame was taken, where its own
     * picture ends, and where a satellite could have passed into or out of the Earth's shadow.
     */
    public interface Frames {
        /** Frames (plans) in the stack. */
        int count();

        /** When plan f's exposure began (seconds from a fixed moment), and how long it was. */
        double start(int f);

        double exposure(int f);

        /**
         * How far stack pixel (x, y) lies inside plan f's own picture (pixels): from the nearest of its edges and of
         * anything in it that isn't clear sky (a roof, a branch, a cloud); 0 or less on or outside them.
         */
        double inside(int f, double x, double y);

        /**
         * The fastest (degrees a second) a satellite could have crossed the sky where it passed into or out of the
         * Earth's shadow at stack pixel (x, y) during plan f; 0 where none could have.
         */
        double shadowSpeed(int f, double x, double y);

        /**
         * How high (km) the Earth's shadow reaches along the line of sight through stack pixel (x, y) during plan f
         * (a satellite higher up is in sunlight); NaN where that isn't known.
         */
        double shadowHeight(int f, double x, double y);

        /** The angle (degrees) on the sky between two stack pixels. */
        double degrees(double x0, double y0, double x1, double y1);
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
    /** Farthest either side of the fitted offset that the band of equally good offsets is followed (pixels). */
    static final int MAX_BAND_PX = 64;
    /** Fewest cells an unidentified streak needs, and the largest gap (cells) inside one. */
    static final int MIN_FREE_CELLS = 14, MAX_GAP = 4;

    private SatMatch() {}

    /**
     * @param tracks    predicted tracks (in the stack's pixels)
     * @param planOf    plan index of each unit (index = unit; -1 if the unit isn't in the stack)
     * @param ev        the first stack's evidence (not changed)
     * @param degPerPx  the picture's scale at its centre
     * @param width     the picture's width (pixels)
     * @param frames    the frames' times and pictures, for telling meteors from satellites; null for a simpler guess
     */
    public static Result match(List<SatPredict.Track> tracks, int[] planOf, StreakEvidence ev, double degPerPx, int width,
                               Frames frames) {
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
        if (ev.deep()) deep(r, ev, planOf);
        guessKinds(r.streaks, width, degPerPx, frames, tracks, planOf);
        return r;
    }

    // ------------------------------------------------------------------------------------------------
    // Fainter streaks

    /**
     * How strong (sigma) a line in a frame's deep map must be: the strongest line pure noise makes in a map of
     * {@link #DEEP_REF_CELLS} cells (half size) is about {@link #DEEP_MODE}, and the chance of a stronger one falls
     * e-fold every {@link #DEEP_TAIL} (the simulator's DeepLinesTest, with some room for real skies); the bar is set
     * where a session's frames, however many and however big, make a false line about once in
     * 1 / {@link #DEEP_FALSE} sessions, and never below {@link #DEEP_SIGMA_MIN}.
     */
    public static final double DEEP_MODE = 5.6, DEEP_TAIL = 0.25, DEEP_REF_CELLS = 255 * 192, DEEP_FALSE = 0.01;
    static final double DEEP_SIGMA_MIN = 7.5;
    /** Blocks (deep cells a side) whose slow changes are taken out of each deep map ({@link DeepLines#flatten}). */
    static final int DEEP_FLAT = 16;
    /** Fewest deep cells such a line has, and most looked for in a frame. */
    static final int DEEP_MIN_CELLS = 8, DEEP_MAX_LINES = 4;
    /** How strong the same line in the frame before or after must be for the streak to go on there. */
    static final double DEEP_ONWARD_SIGMA = 5;
    /** Deep cells either side of a found line taken out before the next is looked for. */
    static final double DEEP_CLAIM = 2;
    /** How much further from an edge a deep streak's end may lie and still have run out of the picture (pixels). */
    static final double DEEP_END_SLACK = 2 * StreakEvidence.DEEP;

    /**
     * The streaks too faint for the rejections: lines in each frame's deep map (its own mean taken out, its spread
     * taken as the unit, and the sensor's banding in that frame taken out) away from the streaks already found there,
     * each an unidentified streak marked {@link Streak#deep}, its light measured from the map, and whether the same
     * line goes on in the frame before or after. One along a streak of its own frame (the fainter start or end of a
     * meteor or a satellite, past where the rejections showed it) becomes part of that streak.
     */
    static void deep(Result r, final StreakEvidence ev, int[] planOf) {
        final int w = ev.deepX, h = ev.deepY, nf = ev.frames;
        final List<List<Streak>> found = new ArrayList<List<Streak>>();
        for (int f = 0; f < nf; f++) found.add(new ArrayList<Streak>());
        for (Streak s : r.streaks) if (s.plan >= 0 && s.plan < nf) found.get(s.plan).add(s);
        final List<List<DeepLines.Line>> lines = new ArrayList<List<DeepLines.Line>>();
        for (int f = 0; f < nf; f++) lines.add(null);
        final float[] unit = new float[nf], offset = new float[nf];
        final double[][] rho = new double[nf][];
        final double sigma = deepSigma(nf, w * h);
        Parallel.forRange(nf, 1, new Parallel.Range() {
            @Override
            public void run(int a, int b) {
                DeepLines dl = new DeepLines(w, h);
                float[] z = null, col = new float[w * h], row = new float[w * h];
                for (int f = a; f < b; f++) {
                    double sd = ev.deepSpread(f);
                    if (!(sd > 0)) continue;
                    z = ev.deepMap(f, z);
                    for (Streak s : found.get(f)) mask(z, w, h, s);
                    float mean = deepMean(z, w * h);
                    prepare(z, w, h, mean, (float) sd, ev, f, col, row);
                    double[] nb = DeepLines.neighbours(z, w, h);
                    dl.setNeighbours(nb);
                    List<DeepLines.Line> got = dl.find(z, sigma, DEEP_MIN_CELLS, DEEP_MAX_LINES, DEEP_CLAIM);
                    synchronized (lines) {
                        lines.set(f, got);
                        unit[f] = (float) sd;
                        offset[f] = mean;
                        rho[f] = nb;
                    }
                }
            }
        });
        float[] za = null, zb = null, zf = null, col = new float[w * h], row = new float[w * h];
        for (int f = 0; f < nf; f++) {
            List<DeepLines.Line> got = lines.get(f);
            if (got == null || got.isEmpty()) continue;
            // the frames either side as they are (a satellite bright enough there to be found is still the same line)
            za = f > 0 && unit[f - 1] > 0 ? prepare(ev.deepMap(f - 1, za), w, h, offset[f - 1], unit[f - 1], ev, f - 1, col, row) : null;
            zb = f + 1 < nf && unit[f + 1] > 0 ? prepare(ev.deepMap(f + 1, zb), w, h, offset[f + 1], unit[f + 1], ev, f + 1, col, row) : null;
            // and the frame's own map as it was searched, for the light along its lines
            zf = ev.deepMap(f, zf);
            for (Streak s : found.get(f)) mask(zf, w, h, s);
            prepare(zf, w, h, offset[f], unit[f], ev, f, col, row);
            List<Streak> here = new ArrayList<Streak>(found.get(f));
            for (DeepLines.Line l : got) {
                Streak s = deepStreak(ev, f, l, zf, unit[f]);
                for (int u = 0; u < planOf.length; u++) if (planOf[u] == f) s.unit = u;
                for (int side = -1; side <= 1; side += 2) {
                    float[] zz = side < 0 ? za : zb;
                    if (zz == null) continue;
                    DeepLines.Line on = DeepLines.bestStretch(zz, w, h, l.x0, l.y0, l.x1, l.y1, DEEP_MIN_CELLS);
                    if (on == null) continue;
                    if (on.score * DeepLines.neighbourFactor(rho[f + side], on.x0, on.y0, on.x1, on.y1) >= DEEP_ONWARD_SIGMA) s.onward = true;
                }
                Streak part = null;
                for (Streak o : here) if (o.sat == null && liesAlong(s, o)) part = o;
                if (part != null) {
                    absorb(part, s);
                    continue;
                }
                here.add(s);
                r.streaks.add(s);
            }
        }
    }

    /** Whether deep streak d's evidence lies along streak o's line, both its ends within a deep cell of it. */
    static boolean liesAlong(Streak d, Streak o) {
        boolean ev = !Float.isNaN(o.sx0);
        double ax = ev ? o.sx0 : o.x0, ay = ev ? o.sy0 : o.y0, bx = ev ? o.sx1 : o.x1, by = ev ? o.sy1 : o.y1;
        double len = Math.hypot(bx - ax, by - ay);
        if (len < 1) return false;
        double nx = -(by - ay) / len, ny = (bx - ax) / len, tol = StreakEvidence.DEEP + o.halfWidth;
        return Math.abs((d.sx0 - ax) * nx + (d.sy0 - ay) * ny) <= tol && Math.abs((d.sx1 - ax) * nx + (d.sy1 - ay) * ny) <= tol;
    }

    /**
     * Streak o taken on over deep streak d along its line: its evidence and its corridor reach over d's too, its
     * light runs on with d's (none where neither shows), and d's cells count as its own.
     */
    static void absorb(Streak o, Streak d) {
        boolean ev = !Float.isNaN(o.sx0);
        double ax = ev ? o.sx0 : o.x0, ay = ev ? o.sy0 : o.y0, bx = ev ? o.sx1 : o.x1, by = ev ? o.sy1 : o.y1;
        double len = Math.hypot(bx - ax, by - ay);
        if (len < 1) return;
        double ux = (bx - ax) / len, uy = (by - ay) / len;
        // along o's line from its evidence's start: d's evidence, and both corridors
        double d0 = (d.sx0 - ax) * ux + (d.sy0 - ay) * uy, d1 = (d.sx1 - ax) * ux + (d.sy1 - ay) * uy;
        double lo = Math.min(0, Math.min(d0, d1)), hi = Math.max(len, Math.max(d0, d1));
        double c0 = Math.min(along(o.x0, o.y0, ax, ay, ux, uy), along(o.x1, o.y1, ax, ay, ux, uy));
        double c1 = Math.max(along(o.x0, o.y0, ax, ay, ux, uy), along(o.x1, o.y1, ax, ay, ux, uy));
        c0 = Math.min(c0, Math.min(along(d.x0, d.y0, ax, ay, ux, uy), along(d.x1, d.y1, ax, ay, ux, uy)));
        c1 = Math.max(c1, Math.max(along(d.x0, d.y0, ax, ay, ux, uy), along(d.x1, d.y1, ax, ay, ux, uy)));
        // the light, per cell's length from the new start
        double c = StreakEvidence.CELL;
        int bins = Math.max(1, (int) Math.round((hi - lo) / c));
        float[] light = new float[bins];
        boolean dUp = d1 >= d0;
        for (int i = 0; i < bins; i++) {
            double t = lo + (i + 0.5) * c;
            if (t >= 0 && t < len && o.light != null) {
                int j = (int) (t / c);
                if (j < o.light.length) light[i] = o.light[j];
            } else if (t >= Math.min(d0, d1) && t <= Math.max(d0, d1) && d.light != null) {
                int j = (int) ((dUp ? t - d0 : d0 - t) / c);
                if (j >= 0 && j < d.light.length) light[i] = d.light[j];
            }
        }
        o.light = light;
        if (ev) {
            o.sx0 = (float) (ax + ux * lo);
            o.sy0 = (float) (ay + uy * lo);
            o.sx1 = (float) (ax + ux * hi);
            o.sy1 = (float) (ay + uy * hi);
        }
        // the corridor along its own line, so it keeps its place across
        boolean up = along(o.x1, o.y1, ax, ay, ux, uy) >= along(o.x0, o.y0, ax, ay, ux, uy);
        double ox = o.x0 - ux * along(o.x0, o.y0, ax, ay, ux, uy), oy = o.y0 - uy * along(o.x0, o.y0, ax, ay, ux, uy);
        o.x0 = (float) (ox + ux * (up ? c0 : c1));
        o.y0 = (float) (oy + uy * (up ? c0 : c1));
        o.x1 = (float) (ox + ux * (up ? c1 : c0));
        o.y1 = (float) (oy + uy * (up ? c1 : c0));
        o.cells += d.cells;
        if (o.deep) o.score = Math.max(o.score, d.score);
        o.onward |= d.onward;
    }

    /** How far along the line from (ax, ay) in direction (ux, uy) the point (x, y) lies. */
    private static double along(double x, double y, double ax, double ay, double ux, double uy) {
        return (x - ax) * ux + (y - ay) * uy;
    }

    /**
     * A deep map's mean where there's only sky (cells beyond -2.5 and 3.5 taken as those): the frame's samples sit a
     * little off the stack's centre on the whole (the centre is a median, and the noise isn't quite symmetric).
     */
    static float deepMean(float[] z, int n) {
        double sum = 0;
        int k = 0;
        for (int i = 0; i < n; i++) {
            float v = z[i];
            if (!(v == v)) continue;
            sum += v < -2.5f ? -2.5f : v > 3.5f ? 3.5f : v;
            k++;
        }
        return k > 0 ? (float) (sum / k) : 0f;
    }

    /**
     * Plan f's deep map as it's searched (in place): less its mean, in units of its spread, its slow changes taken out
     * ({@link DeepLines#flatten}), and the sensor's banding in that frame ({@link DeepLines#unband}, with where its
     * cells lay on the sensor: worked out into {@code col} and {@code row}).
     */
    static float[] prepare(float[] z, int w, int h, float mean, float unit, StreakEvidence ev, int f, float[] col, float[] row) {
        float k = 1 / unit;
        for (int i = 0; i < w * h; i++) z[i] = (z[i] - mean) * k;
        DeepLines.flatten(z, w, h, DEEP_FLAT);
        ev.sensorCells(f, col, row);
        DeepLines.unband(z, w, h, col, row);
        return z;
    }

    /** The bar a deep line must reach in a session of so many frames of so many deep cells each ({@link #DEEP_MODE}). */
    public static double deepSigma(int frames, int cells) {
        double trials = Math.max(1, frames) * (double) cells / DEEP_REF_CELLS / DEEP_FALSE;
        return Math.max(DEEP_SIGMA_MIN, DEEP_MODE + DEEP_TAIL * Math.log(trials));
    }

    /** Takes a streak's corridor (a deep cell beyond it) out of a deep map. */
    static void mask(float[] z, int w, int h, Streak s) {
        if (Float.isNaN(s.x0)) return;
        double c = StreakEvidence.DEEP;
        DeepLines.clear(z, w, h, s.x0 / c - 0.5, s.y0 / c - 0.5, s.x1 / c - 0.5, s.y1 / c - 0.5, s.halfWidth / c + 1);
    }

    /**
     * A deep line as a streak in stack pixels: where its evidence begins and ends, a corridor a cell longer at both
     * ends and a cell and a half either side, and its light along it: a white trail's green excess over the stack in
     * the line's cell and the cells either side of it (the map as searched, {@code z}, back in the units of the
     * frame's spread {@code unit}, times what a cell's 1 stands for there, {@link StreakEvidence#deepLight}), per
     * {@link StreakEvidence#CELL} of its length.
     */
    static Streak deepStreak(StreakEvidence ev, int f, DeepLines.Line l, float[] z, float unit) {
        double c = StreakEvidence.DEEP;
        Streak s = new Streak();
        s.plan = f;
        s.deep = true;
        s.score = l.score;
        s.cells = l.cells;
        s.sx0 = (float) ((l.x0 + 0.5) * c);
        s.sy0 = (float) ((l.y0 + 0.5) * c);
        s.sx1 = (float) ((l.x1 + 0.5) * c);
        s.sy1 = (float) ((l.y1 + 0.5) * c);
        double dx = s.sx1 - s.sx0, dy = s.sy1 - s.sy0, len = Math.max(1e-6, Math.hypot(dx, dy));
        s.x0 = (float) (s.sx0 - dx / len * c);
        s.y0 = (float) (s.sy0 - dy / len * c);
        s.x1 = (float) (s.sx1 + dx / len * c);
        s.y1 = (float) (s.sy1 + dy / len * c);
        s.halfWidth = (float) (1.5 * c);
        // the light: a step of a cell along the line's main direction is that much longer along the line
        int w = ev.deepX, h = ev.deepY;
        boolean steep = Math.abs(l.y1 - l.y0) > Math.abs(l.x1 - l.x0);
        double a0 = steep ? l.y0 : l.x0, a1 = steep ? l.y1 : l.x1;
        double slope = Math.abs(a1 - a0) < 1e-6 ? 0 : (steep ? (l.x1 - l.x0) / (l.y1 - l.y0) : (l.y1 - l.y0) / (l.x1 - l.x0));
        double stepPx = c * Math.sqrt(1 + slope * slope);
        int t0 = (int) Math.round(Math.min(a0, a1)), t1 = (int) Math.round(Math.max(a0, a1));
        int bins = Math.max(1, (int) Math.round((t1 - t0 + 1) * stepPx / StreakEvidence.CELL));
        float[] light = new float[bins];
        for (int t = t0; t <= t1; t++) {
            double o = (steep ? l.x0 : l.y0) + slope * (t - a0);
            int q = (int) Math.floor(o + 0.5);
            double sum = 0;
            for (int k = -1; k <= 1; k++) {
                int x = steep ? q + k : t, y = steep ? t : q + k;
                if (x < 0 || y < 0 || x >= w || y >= h) continue;
                float v = z[y * w + x];
                double per = ev.deepLight(f, (x + 0.5) * c, (y + 0.5) * c);
                if (v == v && per == per) sum += v * unit * per;
            }
            // the light of this step, spread over the cells' lengths it covers (from the line's start)
            double from = (t - t0) * stepPx / StreakEvidence.CELL, to = (t - t0 + 1) * stepPx / StreakEvidence.CELL;
            for (int b = (int) Math.floor(from); b < Math.min(bins, (int) Math.ceil(to)); b++) {
                double share = Math.min(to, b + 1) - Math.max(from, b);
                if (share > 0) light[b] += (float) (sum * share / (to - from));
            }
        }
        // the line runs from (x0, y0): if that's its far end along the main direction, the light runs the other way
        if (a0 > a1) {
            for (int i = 0, j = bins - 1; i < j; i++, j--) {
                float t = light[i];
                light[i] = light[j];
                light[j] = t;
            }
        }
        s.light = light;
        return s;
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
        // refine across to the pixel: the hit test takes the path's neighbours too, and a bright streak lights a
        // band of cells, so a range of offsets fits (almost) equally well; the streak's centre is its middle
        int a = t.in0 + bestShift, b = t.in1 + bestShift;
        int span = (WIDTH_CELLS + 1) * (int) c, reach = MAX_BAND_PX;
        int[] sc = new int[2 * reach + 1];
        Arrays.fill(sc, -1);
        int top = -1, at = reach;
        for (int k = reach - span; k <= reach + span; k++) {
            sc[k] = walk(t, f, ev, a, b, bestOff * c - reach + k, null, null, null)[0];
            if (sc[k] > top || sc[k] == top && Math.abs(k - reach) < Math.abs(at - reach)) {
                top = sc[k];
                at = k;
            }
        }
        int near = top - Math.max(1, Math.round(0.03f * top)), lo = at, hi = at;
        while (lo > 0) {
            if (sc[lo - 1] < 0) sc[lo - 1] = walk(t, f, ev, a, b, bestOff * c - reach + lo - 1, null, null, null)[0];
            if (sc[lo - 1] < near) break;
            lo--;
        }
        while (hi < 2 * reach) {
            if (sc[hi + 1] < 0) sc[hi + 1] = walk(t, f, ev, a, b, bestOff * c - reach + hi + 1, null, null, null)[0];
            if (sc[hi + 1] < near) break;
            hi++;
        }
        double bestPx = bestOff * c - reach + (lo + hi) / 2.0;
        bestScore = Math.max(bestScore, top);
        // finer: the middle of the bright run across the path, cell by cell along it
        double mid = centre(t, f, ev, a, b, bestPx);
        if (!Double.isNaN(mid)) bestPx = mid;
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
     * The streak's centre across the path (pixels, to its left), near offset {@code offPx}: at each cell along the
     * path, the run of bright cells across it, and the middle of each run that ends inside the picture. A run's
     * ends are only a cell sharp, but the cells fall differently along a slanting path, so the trimmed mean of
     * the middles is finer. NaN if too few runs could be measured.
     */
    static double centre(SatPredict.Track t, int f, StreakEvidence ev, int a, int b, double offPx) {
        double c = StreakEvidence.CELL;
        int m = (int) Math.ceil(MAX_BAND_PX / c);
        List<Double> mids = new ArrayList<Double>();
        int lastCx = Integer.MIN_VALUE, lastCy = Integer.MIN_VALUE;
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
                int k0 = cell(ev, f, px, py, nx, ny, 0) == 1 ? 0 : cell(ev, f, px, py, nx, ny, -1) == 1 ? -1
                        : cell(ev, f, px, py, nx, ny, 1) == 1 ? 1 : Integer.MIN_VALUE;
                if (k0 == Integer.MIN_VALUE) continue;
                int lo = k0, hi = k0;
                while (lo > -m && cell(ev, f, px, py, nx, ny, lo - 1) == 1) lo--;
                while (hi < m && cell(ev, f, px, py, nx, ny, hi + 1) == 1) hi++;
                // wider than looked at, or running out of the picture: its middle is unknown
                if (lo == -m || hi == m || cell(ev, f, px, py, nx, ny, lo - 1) < 0 || cell(ev, f, px, py, nx, ny, hi + 1) < 0) continue;
                mids.add(offPx + (lo + hi) / 2.0 * c);
            }
        }
        if (mids.size() < MIN_CELLS) return Double.NaN;
        Collections.sort(mids);
        int cut = mids.size() / 5;
        double sum = 0;
        for (int i = cut; i < mids.size() - cut; i++) sum += mids.get(i);
        return sum / (mids.size() - 2 * cut);
    }

    /** The cell {@code k} cells across from (px, py) along (nx, ny): 1 bright, 0 dark, -1 outside the picture. */
    private static int cell(StreakEvidence ev, int f, double px, double py, double nx, double ny, int k) {
        double c = StreakEvidence.CELL;
        int qx = (int) Math.floor((px + k * c * nx) / c), qy = (int) Math.floor((py + k * c * ny) / c);
        if (qx < 0 || qy < 0 || qx >= ev.cellsX || qy >= ev.cellsY) return -1;
        return ev.get(f, qx, qy) ? 1 : 0;
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
            // width: how far the cells near the line spread across it (a bright trail is several cells wide)
            double spread = 0;
            for (int i = 0; i < n; i++) {
                if (gone[i]) continue;
                double d = (px[i] + 0.5) * cs[a] + (py[i] + 0.5) * sn[a] - rho;
                double t = (px[i] + 0.5) * -sn[a] + (py[i] + 0.5) * cs[a];
                if (Math.abs(d) <= 4 && t >= t0 - 1 && t <= t1 + 1) spread = Math.max(spread, Math.abs(d));
            }
            s.halfWidth = (float) ((spread + 1.5) * c + 2);
            s.kind = blinks(on, bestA, bestB) ? "plane" : "";
            // the light along it: the corridor's cells, cell by cell along the line
            int bins = (int) Math.floor(t1 - t0) + 1;
            float[] light = new float[bins];
            int[] lit = new int[bins];
            for (int i = 0; i < n; i++) {
                if (gone[i]) continue;
                double d = (px[i] + 0.5) * cs[a] + (py[i] + 0.5) * sn[a] - rho;
                double t = (px[i] + 0.5) * -sn[a] + (py[i] + 0.5) * cs[a];
                if (Math.abs(d) > spread + 1.5 || t < t0 - 0.5 || t > t1 + 0.5) continue;
                int bin = Math.max(0, Math.min(bins - 1, (int) Math.floor(t - t0 + 0.5)));
                light[bin] += ev.excess(f, px[i], py[i]);
                lit[bin]++;
            }
            // and where the evidence ends: without a lone faint cell beyond either end, noise the run's gaps let in
            int[] ends = evidenceEnds(light, lit);
            s.sx0 = (float) ((ox + ux * (t0 + ends[0])) * c);
            s.sy0 = (float) ((oy + uy * (t0 + ends[0])) * c);
            s.sx1 = (float) ((ox + ux * (t0 + ends[1])) * c);
            s.sy1 = (float) ((oy + uy * (t0 + ends[1])) * c);
            s.light = Arrays.copyOfRange(light, ends[0], ends[1] + 1);
            out.add(s);
            // the whole corridor's cells go (a thick trail's edge cells would otherwise come back as a second line)
            for (int i = 0; i < n; i++) {
                if (gone[i]) continue;
                double d = (px[i] + 0.5) * cs[a] + (py[i] + 0.5) * sn[a] - rho;
                double t = (px[i] + 0.5) * -sn[a] + (py[i] + 0.5) * cs[a];
                if (Math.abs(d) > spread + 1.5 || t < t0 - ext || t > t1 + ext) continue;
                gone[i] = true;
                vote(acc, rhos, diag, cs, sn, px[i], py[i], -1);
            }
        }
        return out;
    }

    /**
     * The first and last stretch of a streak's evidence ({@code light} and {@code cells} per cell's length along it):
     * all of it, except a lone stretch at either end, two or more empty ones away from the rest, with under a
     * quarter of the light of the streak's typical stretch (the median of those with cells). That is noise: a cell
     * of it now and then lies on the line a little beyond the streak, and the run, which bridges the gaps in a
     * faint streak's patchy light, takes it in. A faint streak's own stretches are about as faint as each other,
     * so its ends stay.
     */
    static int[] evidenceEnds(float[] light, int[] cells) {
        int bins = light.length, k = 0;
        float[] v = new float[bins];
        for (int i = 0; i < bins; i++) if (cells[i] > 0) v[k++] = light[i];
        int[] ends = {0, bins - 1};
        if (k < 3) return ends;
        Arrays.sort(v, 0, k);
        float faint = v[k / 2] / 4;
        int next = 1, prev = bins - 2;
        while (next < bins && cells[next] == 0) next++;
        while (prev >= 0 && cells[prev] == 0) prev--;
        if (next >= 3 && next < bins - 1 && light[0] < faint) ends[0] = next;
        if (bins - 1 - prev >= 3 && prev > ends[0] && light[bins - 1] < faint) ends[1] = prev;
        return ends;
    }

    /**
     * Whether a line's cells, laid along it, leave regular dark stretches: a plane's flashing lights. The run's
     * cells are projected onto the line in steps of a cell; three or more empty stretches of two cells or longer,
     * a quarter of the length dark in all, and stretches alike in length (within a factor of two of their median).
     */
    static boolean blinks(List<double[]> on, int a, int b) {
        double t0 = on.get(a)[0], t1 = on.get(b)[0];
        int bins = (int) Math.floor(t1 - t0) + 1;
        if (bins < 12) return false;
        boolean[] occ = new boolean[bins];
        for (int i = a; i <= b; i++) occ[Math.min(bins - 1, (int) Math.floor(on.get(i)[0] - t0))] = true;
        List<Integer> gaps = new ArrayList<Integer>();
        int run = 0, dark = 0;
        for (int k = 0; k < bins; k++) {
            if (!occ[k]) {
                run++;
                dark++;
            } else {
                if (run >= 2) gaps.add(run);
                run = 0;
            }
        }
        if (gaps.size() < 3 || dark < 0.25 * bins) return false;
        Collections.sort(gaps);
        int med = gaps.get(gaps.size() / 2), alike = 0;
        for (int g : gaps) if (g * 2 >= med && g <= med * 2) alike++;
        return alike >= 0.75 * gaps.size();
    }

    private static void vote(int[] acc, int rhos, double diag, double[] cs, double[] sn, int x, int y, int v) {
        double cx = x + 0.5, cy = y + 0.5;
        for (int a = 0; a < ANGLES; a++) {
            int r = (int) Math.round(cx * cs[a] + cy * sn[a] + diag);
            if (r >= 0 && r < rhos) acc[a * rhos + r] += v;
        }
    }

    /** Fastest a satellite crosses the sky (degrees a second): a low one overhead. */
    static final double SAT_MAX_DEG_S = 2.5;
    /** Frames further apart than this (seconds) don't show what crossed between them. */
    static final double MAX_GAP_S = 3;
    /** An end this close to the edge of its frame's picture (pixels, beyond the streak's half width) may run out of it. */
    static final double EDGE_PX = 3 * StreakEvidence.CELL;
    /**
     * How much lower (km) the shadow may reach at a satellite's end where it goes into it than at its other end: the
     * satellite's own climb in an exposure, and the shadow's soft edge (the Sun isn't a point).
     */
    static final double SHADOW_SLACK_KM = 10;

    /**
     * Unidentified streaks (a blinking plane is found with the line). One that continues into the next or the last
     * frame along the same line is a satellite missing from the data, or a plane. One along a satellite's predicted
     * path is that satellite flashing in the sunlight (a glint) and is named after it. What is left of a single frame
     * is a meteor where no satellite could have left it: longer than the fastest satellite goes in the exposure, or
     * with ends no satellite's could have: a satellite's light begins and ends at an edge of the picture (or at
     * something in it that isn't clear sky: a roof, a branch, a cloud), where it passes into or out of the Earth's
     * shadow (at most once in an exposure, and at the end where the shadow reaches higher), or as the exposure
     * begins or ends, which only a session's first or last frame (or one beside a gap) leaves unseen: otherwise the
     * frame next to it would show the satellite too. A plane's blinking lights are found with the line; but a
     * broken line alone in its frame that begins and ends in open sky is a faint meteor the noise broke up (a plane
     * doesn't light up in mid-sky). Without the frames: short ones are meteors.
     */
    static void guessKinds(List<Streak> all, int width, double degPerPx, Frames frames, List<SatPredict.Track> tracks,
                           int[] planOf) {
        for (Streak s : all) {
            // a broken line on its own (nothing along it in its frame or the next or the last) that begins and ends in
            // open sky: a plane's lights don't, so it's a faint meteor whose trail the noise broke up
            if ("plane".equals(s.kind) && s.sat == null && frames != null && !partnered(s, all) && !endsPossible(s, frames, false)) {
                s.kind = "meteor";
                continue;
            }
            if (s.sat != null || !s.kind.isEmpty()) continue;
            boolean continues = s.onward;
            for (Streak o : all) {
                if (o == s || Math.abs(o.plan - s.plan) != 1) continue;
                if (collinear(s, o)) continues = true;
            }
            if (continues) continue;
            SatPredict.Track g = glint(s, tracks, planOf, degPerPx);
            if (g != null) {
                s.sat = g.sat;
                s.track = g;
                // a deep streak along its path is the satellite itself, too faint for the rejections
                s.kind = s.deep ? "" : "glint";
                s.mag = g.brightest();
                continue;
            }
            if (frames == null) {
                if (s.lengthPx() < 0.15 * width) s.kind = "meteor";
                continue;
            }
            if (!satellitePossible(s, frames)) s.kind = "meteor";
        }
    }

    /** Whether another streak lies along the same line in s's frame, the one before or the one after. */
    static boolean partnered(Streak s, List<Streak> all) {
        for (Streak o : all) if (o != s && Math.abs(o.plan - s.plan) <= 1 && collinear(s, o)) return true;
        return false;
    }

    /** Whether a satellite could have left streak s in its frame alone (see {@link #guessKinds}). */
    public static boolean satellitePossible(Streak s, Frames fr) {
        double ax = Float.isNaN(s.sx0) ? s.x0 : s.sx0, ay = Float.isNaN(s.sx0) ? s.y0 : s.sy0;
        double bx = Float.isNaN(s.sx0) ? s.x1 : s.sx1, by = Float.isNaN(s.sx0) ? s.y1 : s.sy1;
        if (fr.degrees(ax, ay, bx, by) > SAT_MAX_DEG_S * fr.exposure(s.plan)) return false;
        return endsPossible(s, fr, true);
    }

    /**
     * Whether something that shines steadily across the sky could have begun and ended where streak s does in its
     * frame: a satellite (with {@code shadow}: it may go into or come out of the Earth's shadow), or a plane's lights.
     */
    static boolean endsPossible(Streak s, Frames fr, boolean shadow) {
        int f = s.plan;
        double ax = Float.isNaN(s.sx0) ? s.x0 : s.sx0, ay = Float.isNaN(s.sx0) ? s.y0 : s.sy0;
        double bx = Float.isNaN(s.sx0) ? s.x1 : s.sx1, by = Float.isNaN(s.sx0) ? s.y1 : s.sy1;
        double deg = fr.degrees(ax, ay, bx, by), t = fr.exposure(f);
        // a stacked frame just before it, or just after it, would show the satellite as this one's exposure began or ended
        double s0 = fr.start(f), s1 = s0 + fr.exposure(f);
        boolean before = false, after = false;
        for (int g = 0; g < fr.count(); g++) {
            if (g == f) continue;
            double g0 = fr.start(g), g1 = g0 + fr.exposure(g);
            before |= g0 < s0 && g1 >= s0 - MAX_GAP_S;
            after |= g1 > s1 && g0 <= s1 + MAX_GAP_S;
        }
        double near = EDGE_PX + s.halfWidth + (s.deep ? DEEP_END_SLACK : 0);
        // how each end could be a satellite's: 0 an edge, 1 the shadow, 2 the exposure's start, 3 its end. In the
        // shadow's case, a satellite as high as the shadow reaches there, fast enough to have left the streak; and
        // lit all along it, so the shadow reaches no higher at its other end (a satellite keeps its height)
        double ha = shadow ? fr.shadowHeight(f, ax, ay) : Double.NaN, hb = shadow ? fr.shadowHeight(f, bx, by) : Double.NaN;
        boolean[] a = {fr.inside(f, ax, ay) <= near,
                shadow && deg <= fr.shadowSpeed(f, ax, ay) * t && !(ha < hb - SHADOW_SLACK_KM), !before, !after};
        boolean[] b = {fr.inside(f, bx, by) <= near,
                shadow && deg <= fr.shadowSpeed(f, bx, by) * t && !(hb < ha - SHADOW_SLACK_KM), !before, !after};
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                // both ends at edges, yes; but one shadow, one start and one end at most
                if (a[i] && b[j] && (i != j || i == 0)) return true;
            }
        }
        return false;
    }

    /**
     * The predicted track, in the streak's frame, whose path it lies along (within the track's uncertainty across,
     * in direction, and between where the satellite was as the exposure began and ended, give or take its timing
     * uncertainty): a satellite flashing in the sunlight, which the fit to the whole path couldn't name. Null if none.
     */
    static SatPredict.Track glint(Streak s, List<SatPredict.Track> tracks, int[] planOf, double degPerPx) {
        if (tracks == null) return null;
        double ax = Float.isNaN(s.sx0) ? s.x0 : s.sx0, ay = Float.isNaN(s.sx0) ? s.y0 : s.sy0;
        double bx = Float.isNaN(s.sx0) ? s.x1 : s.sx1, by = Float.isNaN(s.sx0) ? s.y1 : s.sy1;
        double len = Math.hypot(bx - ax, by - ay);
        if (len < 1) return null;
        double ux = (bx - ax) / len, uy = (by - ay) / len, mx = (ax + bx) / 2, my = (ay + by) / 2;
        SatPredict.Track best = null;
        double bestD = Double.MAX_VALUE;
        for (SatPredict.Track t : tracks) {
            if (t.unit != s.unit || t.unit < 0 || t.unit >= planOf.length || planOf[t.unit] != s.plan) continue;
            int shifts = (int) Math.ceil(Math.min(t.alongTol(), t.margin) / SatPredict.FINE_S);
            int i0 = Math.max(0, t.in0 - shifts), i1 = Math.min(t.jd.length - 1, t.in1 + shifts);
            double tol = t.acrossTolDeg() / degPerPx + s.halfWidth + 2 * StreakEvidence.CELL;
            // the nearest point of the path to the streak's middle, and the path's direction there
            double d = Double.MAX_VALUE, cosA = 0;
            boolean aOn = false, bOn = false;
            for (int i = i0; i < i1; i++) {
                float x0 = t.x[i], y0 = t.y[i], x1 = t.x[i + 1], y1 = t.y[i + 1];
                if (Float.isNaN(x0) || Float.isNaN(x1)) continue;
                double dx = x1 - x0, dy = y1 - y0, l2 = dx * dx + dy * dy;
                if (l2 < 1e-9) continue;
                double e = distance(mx, my, x0, y0, dx, dy, l2);
                if (e < d) {
                    d = e;
                    cosA = Math.abs(ux * dx + uy * dy) / Math.sqrt(l2);
                }
                aOn |= distance(ax, ay, x0, y0, dx, dy, l2) <= tol;
                bOn |= distance(bx, by, x0, y0, dx, dy, l2) <= tol;
            }
            if (d <= tol && aOn && bOn && cosA >= Math.cos(Math.toRadians(5)) && d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    /** Distance from (px, py) to the segment from (x0, y0) along (dx, dy), of squared length l2. */
    private static double distance(double px, double py, double x0, double y0, double dx, double dy, double l2) {
        double u = Math.max(0, Math.min(1, ((px - x0) * dx + (py - y0) * dy) / l2));
        return Math.hypot(px - x0 - u * dx, py - y0 - u * dy);
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
