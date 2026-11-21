package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Streaks while the session runs (2.3): satellites, planes and meteors found in each unit as it comes in, for Mission
 * Control's "Got it!". The develop step finds and names streaks far more carefully afterwards; this is the quick look.
 * <p>
 * Each unit is cut into {@link #CELL} x {@link #CELL} pixel cells on the sensor, and each cell's light is compared with
 * the same cell in the last few units (their median): the ground, the trees and the sensor's own pattern stay where
 * they are on the sensor and cancel, the sky's glow changes slowly and is taken out by blocks, and the stars, which
 * drift a pixel or two a unit, are masked where the unit found them. What's left is noise, in which a trail is a thin
 * straight line: looked for as the develop step looks for its faintest ones ({@link DeepLines}), at a bar that pure
 * noise doesn't reach in a session. A line must be thin (the cells beside it dark: a cloud's edge is a line too, but a
 * thick one), and where a satellite was predicted to cross the unit (its path in the unit's pixels) a line along it is
 * that satellite; a line along several paths one after another is a Starlink train, each piece its own satellite.
 * One along a line of the unit before goes on from it: the same satellite, or one missing from the list; a lumpy one
 * (blinking lights) a plane; a short one that starts and ends in the sky might be a meteor, but it's held back a unit
 * first, because a satellite that isn't on the list looks the same until it goes on in the next one. A line along no
 * predicted path must stand out more than one along a path: a path says where to expect one.
 * <p>
 * One instance, used from the units' thread ({@link #finish} may come from another).
 */
public final class StreakWatch {
    /** Cell size (sensor pixels). */
    public static final int CELL = 8;
    /** Earlier units whose median is the background. */
    static final int HISTORY = 4;
    /** Shortest line looked for (cells), most looked for in a unit, cells either side of one taken out before the next. */
    static final int MIN_CELLS = 10, MAX_LINES = 8;
    static final double CLAIM = 2.5;
    /** Blocks (cells a side) whose slow changes are taken out ({@link DeepLines#flatten}), and whose spread is the unit. */
    static final int FLAT = 16, SPREAD_BLOCK = 32;
    /** Cells are clipped to this (sigma) before lines are looked for, so that one bright speck can't make a line. */
    static final float LOW = -4f, HIGH = 6f;
    /** The bar is at least this (sigma); a line along no predicted path must reach the second, and more while the
     *  background is still made of fewer than {@link #SETTLED} units. */
    static final double MIN_SIGMA = 8, UNMATCHED_SIGMA = 10, EARLY_EXTRA = 1.5;
    static final int SETTLED = 3;
    /** A thin line: the cells 2 to 4 either side of it no brighter, on average, than this share of the line's. */
    static final double SIDE_SHARE = 0.35;
    /** Too much changed at once to look (a light swept over, the phone moved): this share of cells beyond 5 sigma. */
    static final double BUSY_SHARE = 0.025;
    /**
     * The sky's clearness changed (a cloud came or went): this share more or fewer stars than the units before. All
     * the picture changes then, and the edges of what's still (a branch against a sky lit and then not) look like lines.
     */
    static final double CLEARNESS = 0.15;
    /**
     * A line along a predicted path: within this many cells of it (plus the elements' uncertainty) and this angle (plus
     * what a short line's ends, known to a cell, allow: {@link #angleTol}).
     */
    static final double MATCH_CELLS = 3, MATCH_DEG = 8;
    /** A path covers a piece of a line (a train's satellite): this many cells of it at least, and this share of the
     *  path's own stretch along it. */
    static final int COVER_CELLS = 5;
    static final double COVER_SHARE = 0.5;
    /**
     * A plane: the light along the line this lumpy (its brightest stretch against its middle), in at least this many
     * separate blinks (a line that crosses a brighter one has one lump, where they cross).
     */
    static final double PLANE_LUMPS = 3.5;
    static final int PLANE_BLINKS = 3;
    /** Cells either side of a line that are left out when the lines found after it are scored on their own. */
    static final double APART = CLAIM + 2;
    /** Not a streak: a line in the same place as one the unit before (something on the sensor or a star's glow). */
    static final String ARTIFACT = "artifact";
    /** Saturated "stars" with this many others this close (sensor pixels) are pieces of a bright trail ({@link #trailPieces}). */
    static final int CHAIN = 2;
    static final float CHAIN_PX = 3 * CELL;
    /** Might be a meteor: shorter than this share of the frame's diagonal, both ends this many cells inside it. */
    static final double METEOR_SHARE = 0.3;
    static final int INSIDE_CELLS = 4;

    /** For tests: each unit's maps as lines are looked for (the excess in sigma before clipping, the map searched). */
    public interface Spy {
        void unit(int unit, float[] excess, float[] searched, int w, int h, List<DeepLines.Line> found);
    }

    public static volatile Spy spy;
    static final boolean DEBUG = Boolean.getBoolean("streak.debug");

    /** Kinds of streak. */
    public static final String SATELLITE = "satellite", UNLISTED = "unlisted satellite", PLANE = "plane", METEOR = "meteor",
            STREAK = "streak";

    /** A streak found in a unit. */
    public static final class Streak {
        public int unit;
        /** Its ends in the unit's sensor pixels, and in the reference frame's (NaN if the unit wasn't aligned). */
        public float x0, y0, x1, y1;
        public float rx0 = Float.NaN, ry0 = Float.NaN, rx1 = Float.NaN, ry1 = Float.NaN;
        /** How strong it was (sigma), its cells, and how lumpy the light along it was. */
        public float score, lumps;
        public int cells;
        /** What it probably was ({@link #SATELLITE} with {@link #sat} set, {@link #UNLISTED}, ...). */
        public String kind = STREAK;
        /** The predicted path it lay along (null if none), and how far off it (sensor pixels). */
        public SatPredict.Track track;
        public float offPx = Float.NaN;
        /** Whether it went on from a line of the unit before. */
        public boolean continued;
        /**
         * Satellites found along the same line (a Starlink train): how many, and this one's place among them (0: the
         * first along the line); with more than one, the whole line (sensor pixels: x0, y0, x1, y1).
         */
        public int train = 1, trainIndex;
        public float[] whole;
        /** A maybe-meteor that was held back a unit ({@link Look#held}), now let go. */
        public boolean held;
        /** What the line it went on from was taken for (null if it didn't go on from one). */
        public String fromKind;
        /** How strong it was away from the stronger lines found before it (sigma), and the blinks along it. */
        public float own = Float.NaN;
        public int blinks;
        /** Too faint to tell about on its own (no predicted path along it): kept only to see what goes on from it. */
        boolean weak;

        public SatCatalog.Sat sat() {
            return track != null ? track.sat : null;
        }

        public double lengthPx() {
            return Math.hypot(x1 - x0, y1 - y0);
        }
    }

    /** What was looked at in a unit (for the log and the tests). */
    public static final class Look {
        public int unit;
        public boolean looked;
        public String note = "";
        public double threshold, spread;
        /** How long the look took (ms). */
        public long ms;
        /** The streaks to report now: this unit's, and the maybe-meteors held back from the unit before. */
        public final List<Streak> streaks = new ArrayList<Streak>();
        /**
         * Maybe-meteors held back until the next unit shows whether they go on (then they're satellites): a caller that
         * wants a picture of one takes it now, from this unit, and reports it when it comes back in {@link #streaks}.
         */
        public final List<Streak> held = new ArrayList<Streak>();
    }

    private final int W, H, w, h, n;
    private final CameraModel model;
    private final double threshold;
    private final float[][] hist = new float[HISTORY][];
    private final int[] histStars = new int[HISTORY];
    private int nHist, histNext;
    private final DeepLines lines;
    private float[] cur, z, keep, raw, col, row, apart;
    private final float[] med = new float[HISTORY];
    private List<Streak> before = new ArrayList<Streak>();
    private int beforeUnit = Integer.MIN_VALUE;
    private final List<Streak> holding = new ArrayList<Streak>();
    private int holdingUnit = Integer.MIN_VALUE;

    /**
     * For W x H frames from a camera like {@code model} (null: the reference pixels aren't worked out), in a session of
     * about {@code units} units.
     */
    public StreakWatch(int W, int H, CameraModel model, int units) {
        this.W = W;
        this.H = H;
        this.model = model;
        w = W / CELL;
        h = H / CELL;
        n = w * h;
        lines = new DeepLines(w, h, true);
        threshold = Math.max(MIN_SIGMA, SatMatch.deepSigma(Math.max(1, units), n));
        col = new float[n];
        row = new float[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                col[y * w + x] = x + 0.5f;
                row[y * w + x] = y + 0.5f;
            }
        }
    }

    public int mapWidth() {
        return w;
    }

    public int mapHeight() {
        return h;
    }

    public double threshold() {
        return threshold;
    }

    /** Starts the background again (the phone moved, or something lit everything up). */
    public synchronized void reset() {
        nHist = 0;
        histNext = 0;
        before = new ArrayList<Streak>();
    }

    /** The session is over: the maybe-meteors still held back, let go as they are (meteors: nothing came after). */
    public synchronized List<Streak> finish() {
        List<Streak> out = new ArrayList<Streak>(holding);
        for (Streak s : out) s.held = true;
        holding.clear();
        return out;
    }

    /**
     * Lets go of the maybe-meteors held back from the unit before: one that goes on along a line of this unit ({@code
     * now}, null if this unit wasn't looked at) was a satellite (that line's, if it was named, else one missing from
     * the list), and that line goes on from it.
     */
    private void release(Look out, int unit, List<Streak> now) {
        if (holding.isEmpty()) return;
        boolean next = now != null && unit == holdingUnit + 1;
        List<Streak> gone = new ArrayList<Streak>();
        for (Streak h : holding) {
            h.held = true;
            if (!next) continue;
            for (Streak s : now) {
                if (!collinear(h, s)) continue;
                if (overlap(h, s) > 0.5) {
                    // the same place again: not something that flew across
                    gone.add(h);
                    if (s.track == null) s.kind = ARTIFACT;
                    break;
                }
                s.continued = true;
                if (s.track != null) {
                    h.track = s.track;
                    h.kind = SATELLITE;
                } else {
                    h.kind = UNLISTED;
                    if (!PLANE.equals(s.kind)) s.kind = UNLISTED;
                }
                s.fromKind = h.kind;
            }
        }
        holding.removeAll(gone);
        out.streaks.addAll(holding);
        holding.clear();
    }

    /** How much of the shorter of two lines along the same line lies alongside the other (0 to 1). */
    static double overlap(Streak a, Streak b) {
        double dx = a.x1 - a.x0, dy = a.y1 - a.y0, len = Math.hypot(dx, dy);
        if (len < 1) return 0;
        double ux = dx / len, uy = dy / len;
        double b0 = (b.x0 - a.x0) * ux + (b.y0 - a.y0) * uy, b1 = (b.x1 - a.x0) * ux + (b.y1 - a.y0) * uy;
        double lo = Math.max(0, Math.min(b0, b1)), hi = Math.min(len, Math.max(b0, b1));
        double shorter = Math.min(len, Math.abs(b1 - b0));
        return shorter > 0 ? Math.max(0, hi - lo) / shorter : 0;
    }

    /**
     * The samples of a predicted path a line along it is compared with: those during the exposure, and {@code marginS}
     * seconds either side (the elements' timing is a little off). Satellites one after another along the same orbit (a
     * Starlink train) are only a few seconds apart, so a path mustn't reach into its neighbours'.
     */
    static int[] span(SatPredict.Track t, double marginS) {
        int n = t.x.length;
        int i0 = Math.max(0, Math.min(n - 1, t.in0)), i1 = Math.max(i0, Math.min(n - 1, t.in1));
        double dt = i1 > i0 ? (t.jd[i1] - t.jd[i0]) * 86400 / (i1 - i0) : 0;
        int m = dt > 0 ? (int) Math.ceil(marginS / dt) : 0;
        return new int[]{Math.max(0, i0 - m), Math.min(n - 1, i1 + m)};
    }

    /** Seconds either side of the exposure a line along a path may reach, for the path to be its satellite's. */
    static final double MARGIN_S = 1.5;

    /** The angle (degrees) a line may be off a path along it: more for a short one, whose ends are known to a cell. */
    static double angleTol(double lengthPx) {
        return MATCH_DEG + Math.toDegrees(Math.atan2(CELL, Math.max(1, lengthPx)));
    }

    /**
     * Looks for streaks in a unit (its frames summed: raw values times {@code invScale}, less {@code black}), with the
     * stars found in it (masked), its rotation from the reference frame ({@code R}, null if unknown) and the satellites
     * predicted to cross it in its own pixels ({@code tracks}, may be null). The unit then becomes part of the
     * background for the next ones (unless {@code disturbed}: a unit taken while the phone moved is kept out of it).
     */
    public synchronized Look unit(final short[] rawFrame, final float invScale, float[] black, StarList stars, int unit,
                                  double[] R, List<SatPredict.Track> tracks, boolean disturbed) {
        long t0 = System.nanoTime();
        Look out = look(rawFrame, invScale, black, stars, unit, R, tracks, disturbed);
        if (!out.looked) release(out, unit, null);
        out.ms = (System.nanoTime() - t0) / 1_000_000;
        return out;
    }

    private Look look(final short[] rawFrame, final float invScale, float[] black, StarList stars, int unit, double[] R,
                      List<SatPredict.Track> tracks, boolean disturbed) {
        Look out = new Look();
        out.unit = unit;
        out.threshold = threshold;
        if (cur == null) cur = new float[n];
        final float[] c = cur;
        final float bl = (black[0] + black[1] + black[2] + black[3]) / 4;
        final float k = invScale / (CELL * CELL);
        Parallel.forRange(h, 8, new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                long[] sum = new long[w];
                for (int cy = y0; cy < y1; cy++) {
                    Arrays.fill(sum, 0);
                    for (int dy = 0; dy < CELL; dy++) {
                        int o = (cy * CELL + dy) * W;
                        for (int cx = 0; cx < w; cx++) {
                            int p = o + cx * CELL;
                            long s = 0;
                            for (int dx = 0; dx < CELL; dx++) s += rawFrame[p + dx] & 0xFFFF;
                            sum[cx] += s;
                        }
                    }
                    for (int cx = 0; cx < w; cx++) c[cy * w + cx] = sum[cx] * k - bl;
                }
            }
        });
        if (disturbed) {
            out.note = "the phone moved";
            reset();
            return out;
        }
        final int count = stars != null ? stars.count : -1;
        if (nHist == 0) {
            out.note = "first unit: nothing to compare with yet";
            remember(c, count);
            return out;
        }
        if (count >= 0) {
            float[] was = new float[nHist];
            int kept = 0;
            for (int j = 0; j < nHist; j++) if (histStars[j] >= 0) was[kept++] = histStars[j];
            float before = kept > 0 ? median(was, kept) : -1;
            if (before >= 20 && Math.abs(count - before) > CLEARNESS * before) {
                out.note = String.format(java.util.Locale.US, "the sky's clearness changed (%d stars, %.0f before)", count, before);
                reset();
                remember(c, count);
                return out;
            }
        }
        if (z == null) {
            z = new float[n];
            keep = new float[n];
            raw = new float[n];
            apart = new float[n];
        }
        // the excess over the median of the units before
        for (int i = 0; i < n; i++) {
            int m = 0;
            for (int j = 0; j < nHist; j++) med[m++] = hist[j][i];
            z[i] = c[i] - median(med, m);
        }
        maskStars(z, stars);
        double sd = normalise(z);
        out.spread = sd;
        if (!(sd > 0)) {
            out.note = "no sky to look at";
            remember(c, count);
            return out;
        }
        System.arraycopy(z, 0, raw, 0, n);
        int busy = 0, valid = 0;
        for (int i = 0; i < n; i++) {
            float v = z[i];
            if (!(v == v)) continue;
            valid++;
            if (Math.abs(v) > 5) busy++;
            z[i] = v < LOW ? LOW : v > HIGH ? HIGH : v;
        }
        if (valid < n / 4) {
            out.note = "too little sky to look at";
            remember(c, count);
            return out;
        }
        if (busy > BUSY_SHARE * valid) {
            // a car's lights, the phone nudged, a cloud lit by the town: everything changed, so nothing stands out
            out.note = String.format(java.util.Locale.US, "too much changed at once (%.1f%% of the cells)", 100.0 * busy / valid);
            reset();
            remember(c, count);
            return out;
        }
        DeepLines.flatten(z, w, h, FLAT);
        DeepLines.unband(z, w, h, col, row);
        lines.setNeighbours(DeepLines.neighbours(z, w, h));
        System.arraycopy(z, 0, keep, 0, n);
        out.looked = true;
        List<DeepLines.Line> found = lines.find(z, threshold, MIN_CELLS, MAX_LINES, CLAIM);
        Spy sp = spy;
        if (sp != null) sp.unit(unit, raw, keep, w, h, found);
        double bar = Math.max(threshold, UNMATCHED_SIGMA) + (nHist < SETTLED ? EARLY_EXTRA : 0);
        List<Streak> now = new ArrayList<Streak>(), weak = new ArrayList<Streak>();
        // each line is also scored away from the stronger ones found before it: a faint line that crosses a bright trail
        // (or runs along its glow) gets most of its light from that trail
        System.arraycopy(keep, 0, apart, 0, n);
        for (DeepLines.Line l : found) {
            double own = DeepLines.along(apart, w, h, l.x0, l.y0, l.x1, l.y1)[0];
            DeepLines.clear(apart, w, h, l.x0, l.y0, l.x1, l.y1, APART);
            if (!thin(keep, l)) continue;
            Streak s = new Streak();
            s.unit = unit;
            s.x0 = (l.x0 + 0.5f) * CELL;
            s.y0 = (l.y0 + 0.5f) * CELL;
            s.x1 = (l.x1 + 0.5f) * CELL;
            s.y1 = (l.y1 + 0.5f) * CELL;
            s.score = l.score;
            s.own = (float) own;
            s.cells = l.cells;
            s.lumps = (float) lumps(raw, l);
            s.blinks = blinks(raw, l);
            for (Streak p : classify(s, tracks, keep, bar)) {
                if (R != null && model != null) toReference(p, R);
                (p.weak ? weak : now).add(p);
            }
        }
        // one along a line of the unit before goes on from it: the same satellite if that one was named, else one that
        // isn't on the list (planes blink, meteors don't last); one in the same place as one the unit before is something
        // that doesn't move across the sky (the glow of a bright star, something on the sensor)
        if (beforeUnit == unit - 1) {
            for (Streak s : now) {
                if (s.track != null) continue;
                for (Streak b : before) {
                    if (!collinear(b, s)) continue;
                    if (overlap(b, s) > 0.5) {
                        if (!SATELLITE.equals(b.kind) && !UNLISTED.equals(b.kind)) s.kind = ARTIFACT;
                        continue;
                    }
                    s.continued = true;
                    s.fromKind = b.kind;
                    if (b.track != null) {
                        s.track = b.track;
                        s.kind = SATELLITE;
                        break;
                    }
                    if (!PLANE.equals(s.kind)) s.kind = UNLISTED;
                }
            }
        }
        // the maybe-meteors of the unit before: let go, as satellites if they go on here (even faintly)
        List<Streak> all = new ArrayList<Streak>(now);
        all.addAll(weak);
        release(out, unit, all);
        for (Streak s : now) {
            if (ARTIFACT.equals(s.kind)) continue;
            if (METEOR.equals(s.kind) && !s.continued) {
                holding.add(s);
                holdingUnit = unit;
                out.held.add(s);
            } else {
                out.streaks.add(s);
            }
        }
        before = all;
        beforeUnit = unit;
        // a unit with a bright streak still goes into the background: the median of several hardly sees it
        remember(c, count);
        if (out.note.isEmpty()) out.note = now.isEmpty() ? "nothing" : now.size() + " streak" + (now.size() == 1 ? "" : "s");
        return out;
    }

    private void remember(float[] c, int stars) {
        if (hist[histNext] == null) hist[histNext] = new float[n];
        System.arraycopy(c, 0, hist[histNext], 0, n);
        histStars[histNext] = stars;
        histNext = (histNext + 1) % HISTORY;
        nHist = Math.min(HISTORY, nHist + 1);
    }

    static float median(float[] v, int m) {
        if (m == 1) return v[0];
        if (m == 2) return 0.5f * (v[0] + v[1]);
        Arrays.sort(v, 0, m);
        return (m & 1) == 1 ? v[m / 2] : 0.5f * (v[m / 2 - 1] + v[m / 2]);
    }

    /** The cells around the unit's stars taken out: a star drifts a little from unit to unit, which would leave it in. */
    private void maskStars(float[] z, StarList s) {
        if (s == null) return;
        // the brightest stars' glow reaches further: a cell more for each 1.5 stops brighter than the 30th star
        float ref = s.flux != null && s.count > 30 ? s.flux[29] : Float.NaN;
        boolean[] piece = trailPieces(s);
        for (int i = 0; i < s.count; i++) {
            // a long thin "star" may be a piece of the very trail looked for
            if (s.elong != null && s.elong[i] > 2.5f && !s.saturated[i]) continue;
            if (piece[i]) continue;
            int r = s.saturated[i] ? 3 : i < 30 ? 2 : 1;
            if (i < 30 && ref > 0 && s.flux[i] > ref) r = Math.max(r, Math.min(6, 2 + (int) (Math.log(s.flux[i] / ref) / Math.log(2) / 1.5)));
            int cx = (int) Math.floor(s.x[i] / CELL), cy = (int) Math.floor(s.y[i] / CELL);
            for (int y = Math.max(0, cy - r); y <= Math.min(h - 1, cy + r); y++) {
                for (int x = Math.max(0, cx - r); x <= Math.min(w - 1, cx + r); x++) z[y * w + x] = Float.NaN;
            }
        }
    }

    /**
     * Saturated "stars" in a row: a trail so bright that it saturates (the Space Station's) is found by the star finder as
     * dozens of saturated stars a few pixels apart, where a real star is one. Those with {@link #CHAIN} others within
     * {@link #CHAIN_PX} are pieces of a trail: they aren't masked, so the trail can be found.
     */
    static boolean[] trailPieces(StarList s) {
        boolean[] out = new boolean[s.count];
        int[] sat = new int[s.count];
        int k = 0;
        for (int i = 0; i < s.count; i++) if (s.saturated[i]) sat[k++] = i;
        float r2 = CHAIN_PX * CHAIN_PX;
        for (int a = 0; a < k; a++) {
            int i = sat[a], near = 0;
            for (int b = 0; b < k && near < CHAIN; b++) {
                int j = sat[b];
                if (j == i) continue;
                float dx = s.x[i] - s.x[j], dy = s.y[i] - s.y[j];
                if (dx * dx + dy * dy <= r2) near++;
            }
            out[i] = near >= CHAIN;
        }
        return out;
    }

    /**
     * The map in units of its spread (in place): each block's spread (from the middle half of its cells, which a trail
     * through it hardly touches), at least half the whole map's. Returns the whole map's (NaN if there's nothing).
     */
    private double normalise(float[] z) {
        double all = spread(z, 0, 0, w, h);
        if (!(all > 0)) return Double.NaN;
        int bx = (w + SPREAD_BLOCK - 1) / SPREAD_BLOCK, by = (h + SPREAD_BLOCK - 1) / SPREAD_BLOCK;
        for (int j = 0; j < by; j++) {
            for (int i = 0; i < bx; i++) {
                int x0 = i * SPREAD_BLOCK, y0 = j * SPREAD_BLOCK;
                int x1 = Math.min(w, x0 + SPREAD_BLOCK), y1 = Math.min(h, y0 + SPREAD_BLOCK);
                double sd = spread(z, x0, y0, x1, y1);
                if (!(sd > 0.5 * all)) sd = Math.max(0.5 * all, sd == sd ? sd : all);
                float centre = centre(z, x0, y0, x1, y1);
                float k = (float) (1 / sd);
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int p = y * w + x;
                        if (z[p] == z[p]) z[p] = (z[p] - centre) * k;
                    }
                }
            }
        }
        return all;
    }

    /** Robust spread (sigma) of the cells in a box: from their inner quartiles; NaN with too few. */
    private double spread(float[] z, int x0, int y0, int x1, int y1) {
        float[] v = values(z, x0, y0, x1, y1);
        if (v.length < 32) return Double.NaN;
        Arrays.sort(v);
        double q1 = v[v.length / 4], q3 = v[(3 * v.length) / 4];
        return (q3 - q1) / 1.349;
    }

    private float centre(float[] z, int x0, int y0, int x1, int y1) {
        float[] v = values(z, x0, y0, x1, y1);
        if (v.length == 0) return 0;
        Arrays.sort(v);
        return v[v.length / 2];
    }

    private float[] values(float[] z, int x0, int y0, int x1, int y1) {
        // the whole map is sampled every few cells: plenty for a spread, and quick
        int step = (x1 - x0) * (y1 - y0) > 40000 ? 3 : 1;
        float[] v = new float[((x1 - x0 + step - 1) / step) * ((y1 - y0 + step - 1) / step)];
        int m = 0;
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                float a = z[y * w + x];
                if (a == a) v[m++] = a;
            }
        }
        return Arrays.copyOf(v, m);
    }

    /** Whether a line is thin: the cells 2 to 4 cells either side of it hold little of its light. */
    private boolean thin(float[] map, DeepLines.Line l) {
        double dx = l.x1 - l.x0, dy = l.y1 - l.y0, len = Math.hypot(dx, dy);
        if (len < 1) return false;
        double nx = -dy / len, ny = dx / len;
        double line = DeepLines.along(map, w, h, l.x0, l.y0, l.x1, l.y1)[2];
        if (!(line > 0)) return false;
        for (int side = -1; side <= 1; side += 2) {
            double sum = 0;
            int cnt = 0;
            for (int d = 2; d <= 4; d++) {
                double ox = side * d * nx, oy = side * d * ny;
                double[] a = DeepLines.along(map, w, h, l.x0 + ox, l.y0 + oy, l.x1 + ox, l.y1 + oy);
                sum += a[2] * a[1];
                cnt += (int) a[1];
            }
            if (cnt > 0 && sum / cnt > SIDE_SHARE * line) return false;
        }
        return true;
    }

    /** How lumpy the light along a line is: its brightest few cells against its median (1 for an even trail). */
    private double lumps(float[] map, DeepLines.Line l) {
        int m = Math.max(2, (int) Math.ceil(l.length()) + 1);
        float[] v = new float[m];
        int k = 0;
        for (int i = 0; i < m; i++) {
            double t = i / (double) (m - 1);
            int x = (int) Math.floor(l.x0 + t * (l.x1 - l.x0) + 0.5), y = (int) Math.floor(l.y0 + t * (l.y1 - l.y0) + 0.5);
            if (x < 0 || y < 0 || x >= w || y >= h) continue;
            float a = map[y * w + x];
            if (a == a) v[k++] = a;
        }
        if (k < 4) return 1;
        v = Arrays.copyOf(v, k);
        Arrays.sort(v);
        double mid = v[k / 2], top = v[k - 1 - k / 10];
        return mid > 0.5 ? top / mid : top > 3 ? PLANE_LUMPS * 2 : 1;
    }

    /** How many separate bright stretches (a plane's blinks) there are along a line: runs of cells well above its middle. */
    private int blinks(float[] map, DeepLines.Line l) {
        int m = Math.max(2, (int) Math.ceil(l.length()) + 1);
        float[] v = new float[m];
        int k = 0;
        for (int i = 0; i < m; i++) {
            double t = i / (double) (m - 1);
            int x = (int) Math.floor(l.x0 + t * (l.x1 - l.x0) + 0.5), y = (int) Math.floor(l.y0 + t * (l.y1 - l.y0) + 0.5);
            if (x < 0 || y < 0 || x >= w || y >= h) continue;
            float a = map[y * w + x];
            v[k++] = a == a ? a : 0;
        }
        if (k < 4) return 0;
        float[] sorted = Arrays.copyOf(v, k);
        Arrays.sort(sorted);
        double thr = Math.max(3, 2 * sorted[k / 2]);
        int runs = 0;
        boolean in = false;
        for (int i = 0; i < k; i++) {
            boolean hi = v[i] > thr;
            if (hi && !in) runs++;
            in = hi;
        }
        return runs;
    }

    /** The ends in the reference frame's pixels: the unit's bearings turned back by R (reference -> unit). */
    private void toReference(Streak s, double[] R) {
        double[] Rt = Rot.transpose(R);
        double[] b = new double[3], o = new double[3], p = new double[2];
        float[][] ends = {{s.x0, s.y0}, {s.x1, s.y1}};
        float[] out = new float[4];
        for (int e = 0; e < 2; e++) {
            model.unproject(ends[e][0], ends[e][1], b);
            Rot.apply(Rt, b[0], b[1], b[2], o);
            if (!model.project(o[0], o[1], o[2], p)) return;
            out[2 * e] = (float) p[0];
            out[2 * e + 1] = (float) p[1];
        }
        s.rx0 = out[0];
        s.ry0 = out[1];
        s.rx1 = out[2];
        s.ry1 = out[3];
    }

    /**
     * What a line probably was: the predicted satellite it lies along; else the satellites whose paths cover pieces of
     * it (a Starlink train), with whatever strong lies between them as something else; else by its looks, if it stands
     * out enough on its own ({@code bar}). One streak or more (none if it doesn't stand out).
     */
    private List<Streak> classify(Streak s, List<SatPredict.Track> tracks, float[] map, double bar) {
        List<Streak> out = new ArrayList<Streak>();
        if (tracks != null && !tracks.isEmpty()) {
            double best = Double.MAX_VALUE;
            for (SatPredict.Track t : tracks) {
                double off = offPath(s, t);
                if (off < best) {
                    best = off;
                    s.track = t;
                }
            }
            if (s.track != null) {
                s.offPx = (float) best;
                s.kind = SATELLITE;
                out.add(s);
                return out;
            }
            List<Cover> cs = covers(s, tracks);
            if (DEBUG) {
                StringBuilder b = new StringBuilder(String.format(java.util.Locale.US, "DEBUG unit %d line (%.0f,%.0f)-(%.0f,%.0f):", s.unit, s.x0, s.y0, s.x1, s.y1));
                for (SatPredict.Track t : tracks) {
                    int i0 = -1, i1 = -1;
                    for (int i = 0; i < t.x.length; i++) if (!Float.isNaN(t.x[i])) { if (i0 < 0) i0 = i; i1 = i; }
                    if (i0 >= 0) b.append(String.format(java.util.Locale.US, "\n   track %s (%.0f,%.0f)-(%.0f,%.0f) n=%d in %d..%d", t.sat.displayName(), t.x[i0], t.y[i0], t.x[i1], t.y[i1], t.x.length, t.in0, t.in1));
                }
                for (Cover c : cs) b.append(String.format(java.util.Locale.US, "\n   cover %s %.0f..%.0f off %.1f", c.t.sat.displayName(), c.a, c.b, c.off));
                System.out.println(b);
            }
            if (!cs.isEmpty()) {
                List<Streak> sats = new ArrayList<Streak>();
                double at = 0;
                for (Cover c : cs) {
                    piece(s, at, c.a, map, bar, out);
                    Streak p = part(s, c.a, c.b);
                    p.track = c.t;
                    p.offPx = (float) c.off;
                    p.kind = SATELLITE;
                    sats.add(p);
                    at = c.b;
                }
                piece(s, at, s.lengthPx(), map, bar, out);
                float[] whole = sats.size() > 1 ? new float[]{s.x0, s.y0, s.x1, s.y1} : null;
                for (int i = 0; i < sats.size(); i++) {
                    Streak p = sats.get(i);
                    p.train = sats.size();
                    p.trainIndex = i;
                    p.whole = whole;
                }
                out.addAll(0, sats);
                return out;
            }
        }
        looks(s);
        s.weak = Math.min(s.score, s.own == s.own ? s.own : s.score) < bar;
        out.add(s);
        return out;
    }

    /** A streak that isn't a listed satellite, by its looks: a plane, a maybe-meteor or a streak. */
    private void looks(Streak s) {
        double diag = Math.hypot(W, H);
        boolean inside = Math.min(Math.min(s.x0, s.x1), Math.min(s.y0, s.y1)) > INSIDE_CELLS * CELL
                && Math.max(s.x0, s.x1) < W - INSIDE_CELLS * CELL && Math.max(s.y0, s.y1) < H - INSIDE_CELLS * CELL;
        if (s.lumps >= PLANE_LUMPS && s.blinks >= PLANE_BLINKS && s.lengthPx() > 0.1 * diag) s.kind = PLANE;
        else if (inside && s.lengthPx() < METEOR_SHARE * diag) s.kind = METEOR;
        else s.kind = STREAK;
    }

    /** The piece of a line from {@code a} to {@code b} pixels along it, as a streak of its own. */
    private static Streak part(Streak s, double a, double b) {
        double len = s.lengthPx(), ux = (s.x1 - s.x0) / len, uy = (s.y1 - s.y0) / len;
        Streak p = new Streak();
        p.unit = s.unit;
        p.x0 = (float) (s.x0 + a * ux);
        p.y0 = (float) (s.y0 + a * uy);
        p.x1 = (float) (s.x0 + b * ux);
        p.y1 = (float) (s.y0 + b * uy);
        double share = Math.max(0, Math.min(1, (b - a) / Math.max(1, len)));
        p.cells = (int) Math.round(s.cells * share);
        p.score = (float) (s.score * Math.sqrt(share));
        p.lumps = s.lumps;
        p.blinks = s.blinks;
        return p;
    }

    /** What lies between a train's pieces, from a to b along the line: kept as a streak of its own if strong enough. */
    private void piece(Streak s, double a, double b, float[] map, double bar, List<Streak> out) {
        if (b - a < 2 * MIN_CELLS * CELL) return;
        Streak p = part(s, a, b);
        double[] al = DeepLines.along(map, w, h, p.x0 / CELL - 0.5, p.y0 / CELL - 0.5, p.x1 / CELL - 0.5, p.y1 / CELL - 0.5);
        p.score = (float) al[0];
        p.cells = (int) al[1];
        if (p.score < bar) return;
        p.blinks = s.blinks;
        looks(p);
        out.add(p);
    }

    /** A piece of a line a predicted path covers: from a to b (pixels along the line), how far off it on average. */
    private static final class Cover {
        SatPredict.Track t;
        double a, b, off;
    }

    /**
     * The predicted paths along a line, each with the piece of it that it covers (a Starlink train is one line of several
     * satellites, a few seconds apart), first along the line first; two on the same piece (a satellite and the rocket
     * that took it up): the nearer one. Empty if none.
     */
    private List<Cover> covers(Streak s, List<SatPredict.Track> tracks) {
        List<Cover> out = new ArrayList<Cover>();
        double len = s.lengthPx();
        if (len < 1) return out;
        double ux = (s.x1 - s.x0) / len, uy = (s.y1 - s.y0) / len, nx = -uy, ny = ux;
        double degPerPx = model != null ? 1 / (model.fx * Astro.DEG) : 0.017;
        double cosTol = Math.cos(Math.toRadians(angleTol(len)));
        for (SatPredict.Track t : tracks) {
            double tol = MATCH_CELLS * CELL + t.acrossTolDeg() / degPerPx;
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE, offSum = 0, offN = 0;
            double fx = Double.NaN, fy = 0, lx = 0, ly = 0;
            int[] sp = span(t, MARGIN_S);
            for (int i = sp[0]; i < sp[1]; i++) {
                if (Float.isNaN(t.x[i]) || Float.isNaN(t.x[i + 1])) continue;
                double pu = (t.x[i] - s.x0) * ux + (t.y[i] - s.y0) * uy, pv = (t.x[i] - s.x0) * nx + (t.y[i] - s.y0) * ny;
                double qu = (t.x[i + 1] - s.x0) * ux + (t.y[i + 1] - s.y0) * uy, qv = (t.x[i + 1] - s.x0) * nx + (t.y[i + 1] - s.y0) * ny;
                // the part of this piece of path within tol of the line, and alongside it
                double[] r = {0, 1};
                if (!clip(pv, qv, -tol, tol, r) || !clip(pu, qu, -tol, len + tol, r)) continue;
                double u0 = pu + r[0] * (qu - pu), u1 = pu + r[1] * (qu - pu);
                double v0 = pv + r[0] * (qv - pv), v1 = pv + r[1] * (qv - pv);
                lo = Math.min(lo, Math.min(u0, u1));
                hi = Math.max(hi, Math.max(u0, u1));
                offSum += Math.abs(v0) + Math.abs(v1);
                offN += 2;
                double ax = t.x[i] + r[0] * (t.x[i + 1] - t.x[i]), ay = t.y[i] + r[0] * (t.y[i + 1] - t.y[i]);
                double bx = t.x[i] + r[1] * (t.x[i + 1] - t.x[i]), by = t.y[i] + r[1] * (t.y[i + 1] - t.y[i]);
                if (Double.isNaN(fx)) {
                    fx = ax;
                    fy = ay;
                }
                lx = bx;
                ly = by;
            }
            if (offN == 0) continue;
            double chord = Math.hypot(lx - fx, ly - fy);
            if (chord > 2 * CELL && Math.abs((lx - fx) * ux + (ly - fy) * uy) / chord < cosTol) continue;
            double a = Math.max(0, lo), b = Math.min(len, hi);
            if (b - a < COVER_CELLS * CELL || b - a < COVER_SHARE * Math.min(len, hi - lo)) continue;
            Cover c = new Cover();
            c.t = t;
            c.a = a;
            c.b = b;
            c.off = offSum / offN;
            out.add(c);
        }
        Collections.sort(out, new Comparator<Cover>() {
            @Override
            public int compare(Cover p, Cover q) {
                return Double.compare(p.a, q.a);
            }
        });
        for (int i = 0; i + 1 < out.size(); ) {
            Cover p = out.get(i), q = out.get(i + 1);
            double ov = Math.min(p.b, q.b) - Math.max(p.a, q.a);
            if (ov > 0.5 * Math.min(p.b - p.a, q.b - q.a)) {
                out.remove(p.off <= q.off ? i + 1 : i);
            } else {
                if (ov > 0) {
                    double mid = 0.5 * (Math.max(p.a, q.a) + Math.min(p.b, q.b));
                    p.b = mid;
                    q.a = mid;
                }
                i++;
            }
        }
        return out;
    }

    /** Narrows r (a range of t in [0, 1]) to where p + t (q - p) lies in [lo, hi]; false if nowhere. */
    static boolean clip(double p, double q, double lo, double hi, double[] r) {
        double d = q - p;
        if (Math.abs(d) < 1e-12) return p >= lo && p <= hi && r[0] <= r[1];
        double t0 = (lo - p) / d, t1 = (hi - p) / d;
        if (t0 > t1) {
            double t = t0;
            t0 = t1;
            t1 = t;
        }
        r[0] = Math.max(r[0], t0);
        r[1] = Math.min(r[1], t1);
        return r[0] <= r[1];
    }

    /**
     * How far (sensor pixels) a streak lies from a satellite's predicted path, at its worst end or middle; MAX_VALUE if
     * not along it (too far, or at an angle).
     */
    private double offPath(Streak s, SatPredict.Track t) {
        double degPerPx = model != null ? 1 / (model.fx * Astro.DEG) : 0.017;
        double tol = MATCH_CELLS * CELL + t.acrossTolDeg() / degPerPx;
        double[][] pts = {{s.x0, s.y0}, {0.5 * (s.x0 + s.x1), 0.5 * (s.y0 + s.y1)}, {s.x1, s.y1}};
        double worst = 0;
        double sdx = s.x1 - s.x0, sdy = s.y1 - s.y0, slen = Math.hypot(sdx, sdy);
        int[] sp = span(t, MARGIN_S);
        for (double[] q : pts) {
            double bestD = Double.MAX_VALUE, segDx = 0, segDy = 0;
            for (int i = sp[0]; i < sp[1]; i++) {
                if (Float.isNaN(t.x[i]) || Float.isNaN(t.x[i + 1])) continue;
                double d = segDist(q[0], q[1], t.x[i], t.y[i], t.x[i + 1], t.y[i + 1]);
                if (d < bestD) {
                    bestD = d;
                    segDx = t.x[i + 1] - t.x[i];
                    segDy = t.y[i + 1] - t.y[i];
                }
            }
            if (bestD > tol) return Double.MAX_VALUE;
            double seg = Math.hypot(segDx, segDy);
            if (seg > 1e-6 && slen > 1e-6) {
                double cos = Math.abs(sdx * segDx + sdy * segDy) / (slen * seg);
                if (cos < Math.cos(angleTol(slen) * Astro.DEG)) return Double.MAX_VALUE;
            }
            worst = Math.max(worst, bestD);
        }
        return worst;
    }

    static double segDist(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        double t = l2 > 0 ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2)) : 0;
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /** Whether streak b lies along streak a's line (the next unit's piece of the same trail). */
    static boolean collinear(Streak a, Streak b) {
        double dx = a.x1 - a.x0, dy = a.y1 - a.y0, len = Math.hypot(dx, dy);
        double ex = b.x1 - b.x0, ey = b.y1 - b.y0, len2 = Math.hypot(ex, ey);
        if (len < 1 || len2 < 1) return false;
        double cos = Math.abs(dx * ex + dy * ey) / (len * len2);
        // a short line's direction is only known to a cell at each end
        if (cos < Math.cos((4 + Math.toDegrees(Math.atan2(CELL, Math.min(len, len2)))) * Astro.DEG)) return false;
        double nx = -dy / len, ny = dx / len;
        double d0 = Math.abs((b.x0 - a.x0) * nx + (b.y0 - a.y0) * ny), d1 = Math.abs((b.x1 - a.x0) * nx + (b.y1 - a.y0) * ny);
        return Math.max(d0, d1) < 2.5 * CELL;
    }
}
