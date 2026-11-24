package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Cosmic Ray Hunter (2.6): the particles from space that shot through the camera's chip while it stared at the sky.
 * Cosmic rays (atoms flung across the galaxy by exploding stars) smash into the air high up and make showers of muons,
 * about one a minute through every square centimetre at sea level; a muon going through the chip knocks loose a few
 * hundred electrons, and the pixels it crossed light up in that one frame only. Straight down it lights a spot, at an
 * angle a short straight track; an electron knocked loose by a gamma ray (from the rocks, the concrete, the phone's own
 * glass) wanders, and lights a curly worm.
 * <p>
 * Found on the frames as they were stored, before they're deleted: a pixel well above its patch of sky (8 sigma) that
 * the frames before and after don't have, neither where the sky's drift has moved that spot of sky (so it isn't a star)
 * nor at the same pixel (so it isn't a hot pixel or a satellite parked over the equator); grown into the pixels round
 * it that are lit too. Then the things that aren't particles are taken out: anything long (satellites, planes,
 * meteors), anything on a streak the stack found, a pixel that does it twice (it flickers), three in a line (a plane's
 * blinking lights), and every candidate of a frame that has too many (a cloud passing, a knock).
 * <p>
 * The drift between frames comes from their stored stars (an affine fit to the matched pairs), and how much the sky
 * dimmed or brightened from the same pairs' brightness.
 */
public final class CosmicRays {
    private CosmicRays() {}

    /** A seed is this many sigma over its patch of sky; a lone lit pixel needs this many (a flickering pixel jumps alone). */
    static final float SEED_SIGMA = 8f, LONE_SIGMA = 12f;
    /** The pixels round a seed that are lit too: this many sigma. */
    static final float GROW_SIGMA = 3f;
    /** Over the neighbouring frames' brightest there by this many sigma, and they at most this share of it. */
    static final float OVER_SIGMA = 6f, NEIGHBOUR_SHARE = 0.4f;
    /** Pixels either way searched round the drifted place (the sky), and round the same pixel (the sensor). */
    static final int SKY_WINDOW = 1, PIXEL_WINDOW = 1;
    /**
     * A star in the frames beside: their 3 x 3 box of light there is this many times its noise. A star's light hops
     * between pixels (and colours) as it drifts, so its brightest pixel can be three times brighter in one frame than
     * the next; its box of light doesn't. Where there's one, the box here must be over theirs by {@link #BOX_SIGMA}
     * times the noise (the star's own too), and theirs at most {@link #STAR_SHARE} of it.
     */
    static final float STAR_SIGMA = 4f, STAR_SHARE = 0.5f;
    /** Or the two frames' boxes together over this many times one box's noise: a faint star each shows a little of. */
    static final float PAIR_SIGMA = 2.5f;
    /** The noise floor kept as it is when another frame's light is scaled to this one's sky (sigma). */
    static final float FLOOR = 2f;
    /** A patch's noise is taken as at least this share of the band's usual noise for its colour. */
    static final float NOISE_FLOOR = 0.75f;
    /** Over the star's light by this many times the noise (the box test: a box is more pixels, so less noise each). */
    static final float BOX_SIGMA = 5f;
    /** Longer than this, or more pixels than this: not a particle (a satellite, a plane, a meteor, a bug). */
    static final int MAX_LENGTH = 48, MAX_PIXELS = 160;
    /** A frame with more candidates than this has something else going on: none of them is kept. */
    static final int MAX_PER_UNIT = 12;
    /** Drift between neighbouring frames beyond this (pixels): they aren't compared. */
    static final double MAX_DRIFT = 60;
    /** Sky patches (pixels a side) for the background and noise, and rows read at a time. */
    static final int TILE = 64, BAND = 512;
    /** Rows read beyond a band: room to grow a hit and to cut its picture out. */
    static final int MARGIN = 64;
    /** Muons through a level surface at sea level, per square centimetre per minute. */
    public static final double MUONS_PER_CM2_MIN = 1.0;
    /** Energy to free one electron in silicon (eV). */
    public static final double EV_PER_ELECTRON = 3.6;

    /** What a hit looked like. */
    public enum Kind {
        SPOT("spot", "spots", "a particle that came straight down"),
        TRACK("track", "tracks", "a muon that cut through at an angle"),
        WORM("worm", "worms", "an electron knocked loose by a gamma ray, wandering as it slowed");

        public final String one, many, what;

        Kind(String one, String many, String what) {
            this.one = one;
            this.many = many;
            this.what = what;
        }
    }

    /** One particle's mark. */
    public static final class Hit {
        public int unit;
        /** Seconds from the session's start to the middle of its frame. */
        public double t;
        /** Its middle (sensor pixels), how many pixels it lit, and how long it is (pixels). */
        public float x, y, length;
        public int pixels;
        public Kind kind = Kind.SPOT;
        /** How far over its patch of sky its brightest pixel was (sigma), and all its light (raw levels). */
        public float sigma;
        public double levels;
        /** Electrons it freed and the energy it left (keV); NaN when the camera's noise profile isn't known. */
        public double electrons = Double.NaN, keV = Double.NaN;
        /** Its picture: the light over the sky round it (levels), side x side pixels centred on it. */
        public float[] crop;
        public int side;
        /** Its brightest pixel's row. */
        int peakY;
    }

    /** What the search found. */
    public static final class Result {
        public final List<Hit> hits = new ArrayList<Hit>();
        /** Frames searched, and their exposure (seconds). */
        public int units;
        public double seconds;
        /** The chip's area (cm²) and the muons expected through it in that time. */
        public double cm2 = Double.NaN, expected = Double.NaN;
        /** Candidates taken out, by why. */
        public int notAlone, onStar, onStreak, inALine, crowded, tooBig, hot;
        /** Why nothing was searched ("" when something was). */
        public String why = "";

        public int count(Kind k) {
            int n = 0;
            for (Hit h : hits) if (h.kind == k) n++;
            return n;
        }

        /** "3 tracks, 2 spots and a worm" ("" for none). */
        public String words() {
            List<String> parts = new ArrayList<String>();
            for (Kind k : new Kind[]{Kind.TRACK, Kind.SPOT, Kind.WORM}) {
                int n = count(k);
                if (n == 1) parts.add("a " + k.one);
                else if (n > 1) parts.add(n + " " + k.many);
            }
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) b.append(i == parts.size() - 1 ? " and " : ", ");
                b.append(parts.get(i));
            }
            return b.toString();
        }

        /** For the log. */
        public String summary() {
            return String.format(Locale.US, "%d found in %d frames (%.0f s; %.1f muons expected through %.2f cm²): %s; left out: %d in "
                            + "the frames beside, %d stars, %d on streaks, %d in a line, %d in crowded frames, %d too big, %d hot pixels",
                    hits.size(), units, seconds, expected, cm2, hits.isEmpty() ? "none" : words(), notAlone, onStar, onStreak, inALine,
                    crowded, tooBig, hot);
        }
    }

    /** One frame (unit) to search, or to compare with: what it is and what's known of it. */
    public static final class Unit {
        public int index;
        public double t, exposure;
        public float invScale = 1f;
        /** Its stars as stored (null: none, so it can't be compared with its neighbours). */
        public StarList stars;
        /** Shaken while exposed (left out), and the streaks the stack found in it (x0, y0, x1, y1, half width: its sensor pixels). */
        public boolean disturbed;
        public final List<float[]> streaks = new ArrayList<float[]>();
        /** Its rotation into the stack's frame (its camera to the reference's; null: the stack couldn't place it). */
        public double[] toStack;
    }

    /**
     * The night's stack, where even the faint stars stand out of the noise: a candidate on one of them has to be far
     * brighter than the star is, or it's only the star come out brighter than usual in that frame (its noise).
     */
    public static final class Stack {
        final RgbImage image;
        final CameraModel model;
        /** A star: the stack's 3 x 3 box of light there this many times the box's noise. */
        static final float SIGMA = 4f;
        static final int TILE = 64;
        /** The stack's noise per patch (green), from the darker half of its pixels: faint stars only ever add light. */
        private float[] noise;
        private int tx, ty;

        public Stack(RgbImage image, CameraModel model) {
            this.image = image;
            this.model = model;
        }

        /** The developed stack when it was lined up on the stars (null otherwise: star trails, or nothing placed). */
        public static Stack of(PostProcessor.Output o) {
            if (o == null || o.image == null || o.model == null || !o.aligned || o.trails) return null;
            return new Stack(o.image, o.model);
        }

        private synchronized void measure() {
            if (noise != null) return;
            final int w = image.width, h = image.height;
            tx = Math.max(1, w / TILE);
            ty = Math.max(1, h / TILE);
            final float[] out = new float[tx * ty];
            final float[] g = image.g;
            Parallel.forRange(ty, 1, new Parallel.Range() {
                @Override
                public void run(int j0, int j1) {
                    float[] s = new float[(2 * TILE / 2 + 1) * (2 * TILE / 2 + 1)];
                    for (int j = j0; j < j1; j++) {
                        int ya = j * TILE, yb = j == ty - 1 ? h : ya + TILE;
                        for (int i = 0; i < tx; i++) {
                            int xa = i * TILE, xb = i == tx - 1 ? w : xa + TILE, n = 0;
                            for (int y = ya; y < yb; y += 2) for (int x = xa; x < xb; x += 2) s[n++] = g[y * w + x];
                            float med = Stats.median(s, n), q25 = Stats.quantile(s, n, 0.25);
                            out[j * tx + i] = (med - q25) / 0.6745f;
                        }
                    }
                }
            });
            noise = out;
        }

        /** Whether the stack has a star where unit {@code u}'s pixel (x, y) is (false where it can't tell). */
        boolean star(Unit u, double x, double y) {
            if (u.toStack == null) return false;
            double[] d = new double[3], e = new double[3], q = new double[2];
            model.unproject(x, y, d);
            Rot.apply(u.toStack, d[0], d[1], d[2], e);
            if (!model.project(e[0], e[1], e[2], q)) return false;
            int cx = (int) Math.round(q[0]), cy = (int) Math.round(q[1]), w = image.width, h = image.height;
            if (cx < 1 || cy < 1 || cx >= w - 1 || cy >= h - 1) return false;
            measure();
            // the sky round it: the ring of pixels 4 and 5 away (its level), what of it is on the picture near an edge
            float[] g = image.g, ring = new float[72];
            int n = 0;
            for (int dy = -5; dy <= 5; dy++) {
                for (int dx = -5; dx <= 5; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) < 4) continue;
                    int xx = cx + dx, yy = cy + dy;
                    if (xx >= 0 && yy >= 0 && xx < w && yy < h) ring[n++] = g[yy * w + xx];
                }
            }
            if (n < 16) return false;
            float level = Stats.median(ring, n);
            float sd = noise[Math.min(ty - 1, cy / TILE) * tx + Math.min(tx - 1, cx / TILE)];
            float box = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) box += g[(cy + dy) * w + cx + dx] - level;
            }
            return sd > 0 && box > SIGMA * 3 * sd;
        }
    }

    /** Reads rows [y0, y1) of unit {@code index}'s frame into {@code out} (row y0 at out[0]). */
    public interface Frames {
        void readBand(int index, int y0, int y1, short[] out) throws IOException;
    }

    /**
     * The search over the frames of {@code units} (W x H raw, the sensor's colour pattern {@code cfa}, in time order).
     * {@code hot}: the sensor's known defective pixels (null: none); {@code electronsPerLevel}: from the camera's noise
     * profile (NaN: not known); {@code cm2}: the chip's area (NaN: not known).
     */
    public static Result find(Frames frames, int W, int H, int[] cfa, List<Unit> units, HotPixels hot, Stack stack,
                              double electronsPerLevel, double cm2, Progress progress) throws IOException {
        Result res = new Result();
        res.cm2 = cm2;
        // the stack as big as the frames (its pixels the frames' own), or it can't say where the stars are
        if (stack != null && (stack.image.width != W || stack.image.height != H)) stack = null;
        List<Unit> ok = new ArrayList<Unit>();
        for (Unit u : units) if (!u.disturbed && u.stars != null && u.stars.count >= 8) ok.add(u);
        if (ok.size() < 2) {
            res.why = "too few steady frames with stars to compare";
            return res;
        }
        // each frame's drift to the frames before and after it, from their stars
        int n = ok.size();
        Drift[] prev = new Drift[n], next = new Drift[n];
        for (int i = 0; i + 1 < n; i++) {
            next[i] = Drift.fit(ok.get(i).stars, ok.get(i + 1).stars);
            prev[i + 1] = next[i] != null ? Drift.fit(ok.get(i + 1).stars, ok.get(i).stars) : null;
        }
        double reach = 0;
        List<Integer> searched = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            if (prev[i] == null && next[i] == null) continue;
            searched.add(i);
            res.seconds += ok.get(i).exposure;
            for (Drift d : new Drift[]{prev[i], next[i]}) if (d != null) reach = Math.max(reach, d.reach(W, H));
        }
        res.units = searched.size();
        if (searched.isEmpty()) {
            res.why = "the frames' stars couldn't be matched to their neighbours'";
            return res;
        }
        if (!Double.isNaN(cm2)) res.expected = MUONS_PER_CM2_MIN * cm2 * res.seconds / 60;
        int extra = MARGIN + (int) Math.ceil(reach) + SKY_WINDOW + 4;
        List<Hit> found = new ArrayList<Hit>();
        int bands = (H + BAND - 1) / BAND;
        Band[] cache = new Band[n];
        for (int b = 0; b < bands; b++) {
            int y0 = b * BAND, y1 = Math.min(H, y0 + BAND);
            for (int s = 0; s < searched.size(); s++) {
                if (progress != null) {
                    if (progress.cancelled()) throw new CancelledException();
                    progress.update("Cosmic Ray Hunter", (b * searched.size() + s) / (float) (bands * searched.size()));
                }
                int i = searched.get(s);
                // the bands this frame and its neighbours need; the rest let go
                for (int k = 0; k < n; k++) if (cache[k] != null && (k < i - 1 || k > i + 1)) cache[k] = null;
                Band me = band(cache, ok, i, frames, W, H, cfa, y0 - extra, y1 + extra);
                Band before = prev[i] != null ? band(cache, ok, i - 1, frames, W, H, cfa, y0 - extra, y1 + extra) : null;
                Band after = next[i] != null ? band(cache, ok, i + 1, frames, W, H, cfa, y0 - extra, y1 + extra) : null;
                search(me, before, prev[i], after, next[i], ok.get(i), y0, y1, W, H, cfa, hot, stack, shot(electronsPerLevel, ok.get(i)),
                        res, found);
            }
            for (int k = 0; k < n; k++) cache[k] = null;
        }
        weedOut(found, res);
        for (Hit h : found) {
            if (!Double.isNaN(electronsPerLevel) && electronsPerLevel > 0) {
                h.electrons = h.levels * electronsPerLevel;
                h.keV = h.electrons * EV_PER_ELECTRON / 1000;
            }
        }
        Collections.sort(found, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                return Double.compare(a.t, b.t);
            }
        });
        res.hits.addAll(found);
        return res;
    }

    /** The band of rows [ya, yb) of frame {@code i} (read once, then kept while it's needed). */
    private static Band band(Band[] cache, List<Unit> ok, int i, Frames frames, int W, int H, int[] cfa, int ya, int yb) throws IOException {
        if (cache[i] != null) return cache[i];
        Band b = new Band(ok.get(i), W, Math.max(0, ya) & ~1, Math.min(H, yb), cfa);
        frames.readBand(ok.get(i).index, b.y0, b.y1, b.raw);
        b.measure();
        return cache[i] = b;
    }

    /** Rows of one frame, with each patch of sky's level and noise per colour. */
    static final class Band {
        final Unit unit;
        final int W, y0, y1, tx, ty;
        final short[] raw;
        final float sc;
        final int[] cfa;
        /** Per patch (TILE x TILE, from row y0) and colour plane (0-3): the sky's level and noise (scaled levels). */
        final float[] level, noise;

        Band(Unit u, int W, int y0, int y1, int[] cfa) {
            unit = u;
            this.W = W;
            this.y0 = y0;
            this.y1 = Math.max(y0, y1);
            this.cfa = cfa;
            raw = new short[W * (this.y1 - y0)];
            sc = u.invScale;
            // whole patches; the last in each direction takes the pixels left over (a sliver has too few to measure)
            tx = Math.max(1, W / TILE);
            ty = Math.max(1, (this.y1 - y0) / TILE);
            level = new float[tx * ty * 4];
            noise = new float[tx * ty * 4];
        }

        float v(int x, int y) {
            return (raw[(y - y0) * W + x] & 0xFFFF) * sc;
        }

        boolean has(int x, int y) {
            return x >= 0 && x < W && y >= y0 && y < y1;
        }

        int tile(int x, int y) {
            return Math.min(ty - 1, (y - y0) / TILE) * tx + Math.min(tx - 1, x / TILE);
        }

        static int plane(int x, int y) {
            return ((y & 1) << 1) | (x & 1);
        }

        /** Each patch's level (median) and noise (MAD) per colour plane, from every other pixel of the plane. */
        void measure() {
            Parallel.forRange(ty, 1, new Parallel.Range() {
                @Override
                public void run(int t0, int t1) {
                    float[] s = new float[(2 * TILE / 4 + 1) * (2 * TILE / 4 + 1)];
                    for (int j = t0; j < t1; j++) {
                        int ya = y0 + j * TILE, yb = j == ty - 1 ? y1 : ya + TILE;
                        for (int i = 0; i < tx; i++) {
                            int xa = i * TILE, xb = i == tx - 1 ? W : xa + TILE;
                            for (int p = 0; p < 4; p++) {
                                int n = 0;
                                // the plane's own pixels: its column and row parity (a band can start on an odd row)
                                int xs = xa + (p & 1), ys = ya;
                                if ((ys & 1) != (p >> 1)) ys++;
                                for (int y = ys; y < yb; y += 4) {
                                    for (int x = xs; x < xb; x += 4) s[n++] = v(x, y);
                                }
                                int k = (j * tx + i) * 4 + p;
                                if (n < 4) {
                                    level[k] = Float.NaN;
                                    noise[k] = Float.NaN;
                                    continue;
                                }
                                float m = Stats.median(s, n);
                                level[k] = m;
                                noise[k] = Math.max(0.5f * sc, Stats.madSigma(s, n, m));
                            }
                        }
                    }
                }
            });
            // a patch's noise from a few hundred pixels can come out low by chance (whole levels round it): no lower than
            // most of the band's, for its colour
            float[] all = new float[tx * ty];
            for (int p = 0; p < 4; p++) {
                int n = 0;
                for (int k = 0; k < tx * ty; k++) if (noise[k * 4 + p] > 0) all[n++] = noise[k * 4 + p];
                if (n == 0) continue;
                float floor = NOISE_FLOOR * Stats.median(all, n);
                for (int k = 0; k < tx * ty; k++) if (noise[k * 4 + p] < floor) noise[k * 4 + p] = floor;
            }
        }

        /** Light over the sky at (x, y): its value less the median of the 8 same-colour pixels two away (its own ring). */
        float over(int x, int y, float[] scratch) {
            int xa = Math.max(0, x - 2) == x - 2 ? x - 2 : x + 2, xb = x + 2 < W ? x + 2 : x - 2;
            int ya = y - 2 >= y0 ? y - 2 : y + 2, yb = y + 2 < y1 ? y + 2 : y - 2;
            return v(x, y) - Stats.median8(v(xa, y), v(xb, y), v(x, ya), v(x, yb), v(xa, ya), v(xb, ya), v(xa, yb), v(xb, yb), scratch);
        }

        float sigma(int x, int y) {
            return noise[tile(x, y) * 4 + plane(x, y)];
        }

        /** The light over the sky in the 3 x 3 box round (x, y): each pixel less its patch's level for its colour. */
        float box(int x, int y) {
            float s = 0;
            for (int yy = y - 1; yy <= y + 1; yy++) {
                for (int xx = x - 1; xx <= x + 1; xx++) s += v(xx, yy) - level[tile(xx, yy) * 4 + plane(xx, yy)];
            }
            return s;
        }
    }

    /** Where a frame's sky lies in another's: an affine map of sensor pixels, and how the brightness changed (other / this). */
    static final class Drift {
        final double[] a = new double[6];
        double flux = 1;

        /**
         * Light in the other frame as it would be in this one's sky: over its noise floor scaled by how much brighter
         * this frame's sky was, the floor as it is (a frame under a cloud has its stars dimmed, not its noise; scaling the
         * noise up would hide a particle behind it).
         */
        float scaled(float v, float floor) {
            return (float) ((v - floor) / flux + floor);
        }
        int pairs;

        double[] at(double x, double y) {
            return new double[]{a[0] + a[1] * x + a[2] * y, a[3] + a[4] * x + a[5] * y};
        }

        /** How far it moves anything in a W x H frame, at most (pixels). */
        double reach(int W, int H) {
            double m = 0;
            for (int cy = 0; cy <= 1; cy++) {
                for (int cx = 0; cx <= 1; cx++) {
                    double[] q = at(cx * (W - 1), cy * (H - 1));
                    m = Math.max(m, Math.hypot(q[0] - cx * (W - 1), q[1] - cy * (H - 1)));
                }
            }
            return m;
        }

        /** From {@code a}'s stars to {@code b}'s: the commonest shift, then the matched pairs' affine fit (null: no match). */
        static Drift fit(StarList a, StarList b) {
            int na = Math.min(a.count, 150), nb = Math.min(b.count, 150);
            if (na < 8 || nb < 8) return null;
            int[] order = brightest(a, na), ob = brightest(b, nb);
            int bins = (int) (2 * MAX_DRIFT / 2) + 1;
            int[] votes = new int[bins * bins];
            for (int i : order) {
                for (int j : ob) {
                    double dx = b.x[j] - a.x[i], dy = b.y[j] - a.y[i];
                    if (Math.abs(dx) >= MAX_DRIFT || Math.abs(dy) >= MAX_DRIFT) continue;
                    int bx = (int) ((dx + MAX_DRIFT) / 2), by = (int) ((dy + MAX_DRIFT) / 2);
                    votes[by * bins + bx]++;
                }
            }
            int best = 0;
            for (int k = 1; k < votes.length; k++) if (votes[k] > votes[best]) best = k;
            if (votes[best] < 6) return null;
            double sx = (best % bins) * 2 - MAX_DRIFT + 1, sy = (best / bins) * 2 - MAX_DRIFT + 1;
            // pairs: each of a's stars with b's nearest to where the shift (then the last round's fit) puts it
            double tol = 3;
            List<double[]> pairs = new ArrayList<double[]>();
            Drift cur = null;
            for (int round = 0; round < 3; round++) {
                pairs.clear();
                for (int i : order) {
                    double px = sx + a.x[i], py = sy + a.y[i];
                    if (cur != null) {
                        double[] q = cur.at(a.x[i], a.y[i]);
                        px = q[0];
                        py = q[1];
                    }
                    int jb = -1;
                    double db = tol;
                    for (int j : ob) {
                        double d = Math.hypot(b.x[j] - px, b.y[j] - py);
                        if (d < db) {
                            db = d;
                            jb = j;
                        }
                    }
                    if (jb >= 0) pairs.add(new double[]{a.x[i], a.y[i], b.x[jb], b.y[jb], a.flux[i], b.flux[jb]});
                }
                if (pairs.size() < 6) return null;
                cur = solve(pairs);
                if (cur == null) return null;
                tol = 1.5;
            }
            Drift d = cur;
            // how the sky's brightness changed: the pairs' median flux ratio
            float[] r = new float[pairs.size()];
            int k = 0;
            for (double[] p : pairs) if (p[4] > 0 && p[5] > 0) r[k++] = (float) (p[5] / p[4]);
            d.flux = k >= 3 ? Math.max(0.25, Math.min(4, Stats.median(r, k))) : 1;
            d.pairs = pairs.size();
            return d;
        }

        private static int[] brightest(final StarList s, int n) {
            Integer[] idx = new Integer[s.count];
            for (int i = 0; i < s.count; i++) idx[i] = i;
            java.util.Arrays.sort(idx, new Comparator<Integer>() {
                @Override
                public int compare(Integer p, Integer q) {
                    return Float.compare(s.flux[q], s.flux[p]);
                }
            });
            int[] out = new int[n];
            for (int i = 0; i < n; i++) out[i] = idx[i];
            return out;
        }

        /** Least squares x' = a0 + a1 x + a2 y, y' = a3 + a4 x + a5 y (x and y taken about their means). */
        private static Drift solve(List<double[]> pairs) {
            double mx = 0, my = 0;
            for (double[] p : pairs) {
                mx += p[0];
                my += p[1];
            }
            mx /= pairs.size();
            my /= pairs.size();
            double sxx = 0, sxy = 0, syy = 0, bx0 = 0, bx1 = 0, bx2 = 0, by0 = 0, by1 = 0, by2 = 0;
            for (double[] p : pairs) {
                double x = p[0] - mx, y = p[1] - my;
                sxx += x * x;
                sxy += x * y;
                syy += y * y;
                bx0 += p[2];
                bx1 += p[2] * x;
                bx2 += p[2] * y;
                by0 += p[3];
                by1 += p[3] * x;
                by2 += p[3] * y;
            }
            double det = sxx * syy - sxy * sxy;
            Drift d = new Drift();
            int n = pairs.size();
            double cx0 = bx0 / n, cy0 = by0 / n;
            double c1, c2, e1, e2;
            if (det > 1e-6 * (sxx * syy + 1)) {
                c1 = (bx1 * syy - bx2 * sxy) / det;
                c2 = (bx2 * sxx - bx1 * sxy) / det;
                e1 = (by1 * syy - by2 * sxy) / det;
                e2 = (by2 * sxx - by1 * sxy) / det;
            } else {
                c1 = 1;
                c2 = 0;
                e1 = 0;
                e2 = 1;
            }
            // a sky drifting between two frames barely turns or stretches: anything else is a bad match
            if (Math.abs(c1 - 1) > 0.02 || Math.abs(e2 - 1) > 0.02 || Math.abs(c2) > 0.02 || Math.abs(e1) > 0.02) return null;
            d.a[1] = c1;
            d.a[2] = c2;
            d.a[0] = cx0 - c1 * mx - c2 * my;
            d.a[4] = e1;
            d.a[5] = e2;
            d.a[3] = cy0 - e1 * mx - e2 * my;
            return d;
        }
    }

    /** One band of one frame: its seeds, checked against the frames beside it, grown into hits. */
    private static void search(final Band me, final Band before, final Drift toBefore, final Band after, final Drift toAfter, final Unit u,
                               final int y0, final int y1, final int W, final int H, int[] cfa, final HotPixels hot, Stack stack,
                               double shot, Result res, List<Hit> found) {
        final List<int[]> seeds = Collections.synchronizedList(new ArrayList<int[]>());
        final int edge = 4;
        // seeds: well over their patch's level (cheap), then over their own ring of sky (to be sure)
        Parallel.forRange(y1 - y0, 16, new Parallel.Range() {
            @Override
            public void run(int r0, int r1) {
                float[] scratch = new float[8];
                for (int y = y0 + r0; y < y0 + r1; y++) {
                    if (y < edge || y >= H - edge) continue;
                    for (int x = edge; x < W - edge; x++) {
                        int k = me.tile(x, y) * 4 + Band.plane(x, y);
                        float lv = me.level[k], sg = me.noise[k];
                        if (!(me.v(x, y) - lv > SEED_SIGMA * sg)) continue;
                        float e = me.over(x, y, scratch);
                        if (e > SEED_SIGMA * sg) seeds.add(new int[]{x, y, Float.floatToIntBits(e / sg)});
                    }
                }
            }
        });
        if (seeds.isEmpty()) return;
        // brightest first, so a hit grows from its brightest pixel
        Collections.sort(seeds, new Comparator<int[]>() {
            @Override
            public int compare(int[] a, int[] b) {
                return Float.compare(Float.intBitsToFloat(b[2]), Float.intBitsToFloat(a[2]));
            }
        });
        float[] scratch = new float[8];
        java.util.Set<Long> taken = new java.util.HashSet<Long>();
        List<Hit> mine = new ArrayList<Hit>();
        for (int[] s : seeds) {
            int x = s[0], y = s[1];
            if (taken.contains(key(x, y))) continue;
            float sg = me.sigma(x, y), e = me.over(x, y, scratch);
            // the frames beside it: the same patch of sky (drifted), and the same pixel
            float most = 0;
            if (before != null) most = Math.max(most, brightest(before, toBefore, x, y, scratch));
            if (after != null) most = Math.max(most, brightest(after, toAfter, x, y, scratch));
            if (e - most < OVER_SIGMA * sg || most > NEIGHBOUR_SHARE * e) {
                res.notAlone++;
                continue;
            }
            if (onStar(me, before, toBefore, after, toAfter, stack, u, x, y, sg, shot)) {
                res.onStar++;
                continue;
            }
            Hit h = grow(me, x, y, sg, taken, scratch, before, toBefore, after, toAfter);
            if (h == null) {
                res.tooBig++;
                continue;
            }
            // one that crosses into the next band is that band's if its brightest pixel is there (found once, not twice)
            if (h.peakY < y0 || h.peakY >= y1) continue;
            h.unit = u.index;
            h.t = u.t;
            h.sigma = e / sg;
            if (h.pixels == 1 && h.sigma < LONE_SIGMA) {
                res.hot++;
                continue;
            }
            if (hot != null && hot.nearBad(h.x, h.y, 1)) {
                res.hot++;
                continue;
            }
            if (onStreak(u, h)) {
                res.onStreak++;
                continue;
            }
            mine.add(h);
        }
        synchronized (found) {
            found.addAll(mine);
        }
    }

    private static long key(int x, int y) {
        return ((long) y << 32) | (x & 0xFFFFFFFFL);
    }

    /**
     * The most light over the sky in frame {@code b} near where frame {@code me}'s (x, y) is: round the drifted place
     * (scaled by how much brighter the sky was there) and round the same pixel.
     */
    private static float brightest(Band b, Drift d, int x, int y, float[] scratch) {
        float most = 0;
        double[] q = d.at(x, y);
        int qx = (int) Math.round(q[0]), qy = (int) Math.round(q[1]);
        for (int yy = qy - SKY_WINDOW; yy <= qy + SKY_WINDOW; yy++) {
            for (int xx = qx - SKY_WINDOW; xx <= qx + SKY_WINDOW; xx++) {
                if (!inner(b, xx, yy)) continue;
                most = Math.max(most, d.scaled(b.over(xx, yy, scratch), FLOOR * b.sigma(xx, yy)));
            }
        }
        for (int yy = y - PIXEL_WINDOW; yy <= y + PIXEL_WINDOW; yy++) {
            for (int xx = x - PIXEL_WINDOW; xx <= x + PIXEL_WINDOW; xx++) {
                if (!inner(b, xx, yy)) continue;
                most = Math.max(most, b.over(xx, yy, scratch));
            }
        }
        return most;
    }

    /**
     * A star's light varies between frames by its noise, and a frame where a faint one came out bright looks like a
     * particle to the brightest-pixel test: true when there's a star round (x, y) in the frames beside (their box of light
     * there, the best of the places a pixel either way of where the drift puts it) and this frame's box isn't far enough
     * over it for its noise, the star's own included ({@code shot}: variance a level of light adds, scaled levels).
     */
    private static boolean onStar(Band me, Band before, Drift toBefore, Band after, Drift toAfter, Stack stack, Unit u, int x, int y,
                                  float sg, double shot) {
        float star = Float.NEGATIVE_INFINITY, sum = 0;
        int n = 0;
        for (int k = 0; k < 2; k++) {
            Band b = k == 0 ? before : after;
            if (b == null) continue;
            float v = starBox(b, k == 0 ? toBefore : toAfter, x, y);
            if (v == Float.NEGATIVE_INFINITY) continue;
            star = Math.max(star, v);
            sum += v;
            n++;
        }
        float noise = 3 * sg;
        // a star one of the frames beside shows, or both a little (a faint one), or only the stack (too faint for a frame)
        boolean seen = star > STAR_SIGMA * noise || (n == 2 && sum / 2 > PAIR_SIGMA * noise);
        if (!seen && (stack == null || !stack.star(u, x, y))) return false;
        star = Math.max(0, star);
        float here = me.box(x, y);
        double need = BOX_SIGMA * Math.sqrt(2 * noise * noise + 2 * star * shot);
        return here - star < need || star > STAR_SHARE * here;
    }

    /**
     * The most light in a 3 x 3 box where the drift puts (x, y) in frame {@code b}, or a pixel either way (scaled to
     * this frame's sky). One box at the rounded place can miss half a faint star's light: rounding moves it up to half
     * a pixel each way, and the candidate's own brightest pixel needn't be the star's middle either.
     */
    private static float starBox(Band b, Drift d, int x, int y) {
        double[] q = d.at(x, y);
        int qx = (int) Math.round(q[0]), qy = (int) Math.round(q[1]);
        float most = Float.NEGATIVE_INFINITY;
        for (int yy = qy - 1; yy <= qy + 1; yy++) {
            for (int xx = qx - 1; xx <= qx + 1; xx++) {
                if (xx < 1 || yy < b.y0 + 1 || xx >= b.W - 1 || yy >= b.y1 - 1) continue;
                most = Math.max(most, d.scaled(b.box(xx, yy), FLOOR * 3 * b.sigma(xx, yy)));
            }
        }
        return most;
    }

    /** Variance one scaled level of light adds through its shot noise (electrons a level from the noise profile; 1 if not known). */
    static double shot(double electronsPerLevel, Unit u) {
        double epl = !Double.isNaN(electronsPerLevel) && electronsPerLevel > 0 ? electronsPerLevel : 1;
        return u.invScale / epl;
    }

    /** Inside the band with room for a ring of sky round it. */
    private static boolean inner(Band b, int x, int y) {
        return x >= 2 && x < b.W - 2 && y >= b.y0 + 2 && y < b.y1 - 2;
    }

    /**
     * The lit pixels joined to the seed (8 ways, each over {@link #GROW_SIGMA}), as a hit: its middle, size, length,
     * shape and light, and its picture. Null when it's too big to be a particle (or reaches past the band).
     */
    private static Hit grow(Band me, int sx, int sy, float sg, java.util.Set<Long> taken, float[] scratch, Band before, Drift toBefore,
                            Band after, Drift toAfter) {
        List<int[]> px = new ArrayList<int[]>();
        List<Float> lit = new ArrayList<Float>();
        java.util.ArrayDeque<int[]> todo = new java.util.ArrayDeque<int[]>();
        java.util.Set<Long> seen = new java.util.HashSet<Long>();
        todo.add(new int[]{sx, sy});
        seen.add(key(sx, sy));
        boolean big = false;
        while (!todo.isEmpty()) {
            int[] p = todo.poll();
            float e = me.over(p[0], p[1], scratch);
            px.add(p);
            lit.add(e);
            if (px.size() > MAX_PIXELS) {
                big = true;
                break;
            }
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) continue;
                    int x = p[0] + dx, y = p[1] + dy;
                    if (!inner(me, x, y)) {
                        continue;
                    }
                    long k = key(x, y);
                    if (seen.contains(k)) continue;
                    seen.add(k);
                    float s = me.sigma(x, y);
                    if (me.over(x, y, scratch) > GROW_SIGMA * s) todo.add(new int[]{x, y});
                }
            }
        }
        for (int[] p : px) taken.add(key(p[0], p[1]));
        if (big) return null;
        // the particle's own light: less what the frames beside have there (a faint star it landed on), as this frame's sky
        for (int i = 0; i < px.size(); i++) {
            float still = 0;
            int n = 0;
            for (int k = 0; k < 2; k++) {
                Band b = k == 0 ? before : after;
                Drift d = k == 0 ? toBefore : toAfter;
                if (b == null) continue;
                double[] q = d.at(px.get(i)[0], px.get(i)[1]);
                int qx = (int) Math.round(q[0]), qy = (int) Math.round(q[1]);
                if (!inner(b, qx, qy)) continue;
                still += (float) (b.over(qx, qy, scratch) / d.flux);
                n++;
            }
            if (n > 0) lit.set(i, lit.get(i) - still / n);
        }
        // its middle (light-weighted), light, brightest pixel and shape
        double sw = 0, mx = 0, my = 0;
        int peak = 0;
        for (int i = 0; i < px.size(); i++) {
            double w = Math.max(0, lit.get(i));
            sw += w;
            mx += w * px.get(i)[0];
            my += w * px.get(i)[1];
            if (lit.get(i) > lit.get(peak)) peak = i;
        }
        if (sw <= 0) return null;
        mx /= sw;
        my /= sw;
        double cxx = 0, cyy = 0, cxy = 0, len = 0;
        for (int i = 0; i < px.size(); i++) {
            double w = Math.max(0, lit.get(i)), dx = px.get(i)[0] - mx, dy = px.get(i)[1] - my;
            cxx += w * dx * dx;
            cyy += w * dy * dy;
            cxy += w * dx * dy;
            for (int j = i + 1; j < px.size(); j++) {
                len = Math.max(len, Math.hypot(px.get(i)[0] - px.get(j)[0], px.get(i)[1] - px.get(j)[1]));
            }
        }
        if (len + 1 > MAX_LENGTH) return null;
        cxx /= sw;
        cyy /= sw;
        cxy /= sw;
        double tr = cxx + cyy, det = cxx * cyy - cxy * cxy, disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
        double l1 = tr / 2 + disc, l2 = Math.max(0, tr / 2 - disc);
        Hit h = new Hit();
        h.peakY = px.get(peak)[1];
        h.x = (float) mx;
        h.y = (float) my;
        h.pixels = px.size();
        h.length = (float) (len + 1);
        h.levels = sw;
        if (h.length < 4) h.kind = Kind.SPOT;
        else if (Math.sqrt(l2) <= 0.9 || l1 >= 8 * l2) h.kind = Kind.TRACK;
        else h.kind = Kind.WORM;
        // its picture: the light over the sky round it
        int side = Math.max(21, Math.min(61, ((int) Math.ceil(h.length) + 12) | 1)), half = side / 2;
        h.side = side;
        h.crop = new float[side * side];
        int cx = Math.round(h.x), cy = Math.round(h.y);
        for (int j = 0; j < side; j++) {
            for (int i = 0; i < side; i++) {
                int x = cx - half + i, y = cy - half + j;
                h.crop[j * side + i] = inner(me, x, y) ? me.over(x, y, scratch) : 0;
            }
        }
        return h;
    }

    /** On (or close to) one of the streaks the stack found in its frame. */
    private static boolean onStreak(Unit u, Hit h) {
        for (float[] s : u.streaks) {
            double dx = s[2] - s[0], dy = s[3] - s[1], l2 = dx * dx + dy * dy;
            double t = l2 > 0 ? ((h.x - s[0]) * dx + (h.y - s[1]) * dy) / l2 : 0;
            double l = Math.sqrt(l2), pad = l > 0 ? 30 / l : 0;
            t = Math.max(-pad, Math.min(1 + pad, t));
            double d = Math.hypot(s[0] + t * dx - h.x, s[1] + t * dy - h.y);
            if (d < s[4] + 8) return true;
        }
        return false;
    }

    /** Takes out what isn't a particle after all: crowded frames, three or more in a line, and pixels that did it twice. */
    static void weedOut(List<Hit> hits, Result res) {
        // a frame with too many
        java.util.Map<Integer, Integer> per = new java.util.HashMap<Integer, Integer>();
        for (Hit h : hits) per.put(h.unit, per.containsKey(h.unit) ? per.get(h.unit) + 1 : 1);
        for (java.util.Iterator<Hit> it = hits.iterator(); it.hasNext(); ) {
            Hit h = it.next();
            if (per.get(h.unit) > MAX_PER_UNIT) {
                it.remove();
                res.crowded++;
            }
        }
        // three or more in a line in one frame (a plane's blinking lights, a satellite's glints)
        java.util.Set<Hit> lined = new java.util.HashSet<Hit>();
        for (int i = 0; i < hits.size(); i++) {
            for (int j = i + 1; j < hits.size(); j++) {
                Hit a = hits.get(i), b = hits.get(j);
                if (a.unit != b.unit) continue;
                double dx = b.x - a.x, dy = b.y - a.y, l = Math.hypot(dx, dy);
                if (l < 1) continue;
                for (int k = 0; k < hits.size(); k++) {
                    Hit c = hits.get(k);
                    if (k == i || k == j || c.unit != a.unit) continue;
                    double off = Math.abs((c.x - a.x) * dy - (c.y - a.y) * dx) / l;
                    if (off < 4) {
                        lined.add(a);
                        lined.add(b);
                        lined.add(c);
                    }
                }
            }
        }
        res.inALine += lined.size();
        hits.removeAll(lined);
        // the same pixel twice (in any frames): it flickers
        java.util.Set<Hit> twice = new java.util.HashSet<Hit>();
        for (int i = 0; i < hits.size(); i++) {
            for (int j = i + 1; j < hits.size(); j++) {
                Hit a = hits.get(i), b = hits.get(j);
                if (a.unit != b.unit && Math.hypot(a.x - b.x, a.y - b.y) < 2.5) {
                    twice.add(a);
                    twice.add(b);
                }
            }
        }
        res.hot += twice.size();
        hits.removeAll(twice);
    }

    /**
     * The frames of a developed session to search, in time order: each unit with its stored stars, whether it was shaken,
     * and the streaks and meteors the stack found in it, moved from the stack's pixels into the unit's own (through the
     * lens model and the unit's rotation from the reference frame; a unit the stack couldn't place keeps none, and its
     * other defences have to do).
     */
    public static List<Unit> units(SessionStore st, List<UnitRecord> recs, PostProcessor.Output o) {
        java.util.Map<Integer, PostProcessor.FrameReport> rep = new java.util.HashMap<Integer, PostProcessor.FrameReport>();
        if (o != null) for (PostProcessor.FrameReport f : o.frames) rep.put(f.index, f);
        List<Unit> out = new ArrayList<Unit>();
        for (UnitRecord r : recs) {
            Unit u = new Unit();
            u.index = r.index;
            u.t = r.t;
            u.exposure = r.exposure;
            u.invScale = r.invScale > 0 ? r.invScale : 1f;
            PostProcessor.FrameReport f = rep.get(r.index);
            u.disturbed = r.disturbed || (f != null && f.disturbed);
            u.stars = st.readStars(r.index);
            if (o != null && o.model != null && f != null && f.R != null) {
                u.toStack = Rot.transpose(f.R);
                if (o.streaks != null) {
                    for (SatMatch.Streak s : o.streaks.streaks) {
                        if (s.unit == r.index) addStreak(u, o.model, f.R, s.x0, s.y0, s.x1, s.y1, Math.max(4, s.halfWidth));
                    }
                }
                for (MeteorLog.Meteor m : o.meteors) {
                    if (m.unit == r.index) addStreak(u, o.model, f.R, m.x0, m.y0, m.x1, m.y1, 8);
                }
            }
            out.add(u);
        }
        Collections.sort(out, new Comparator<Unit>() {
            @Override
            public int compare(Unit a, Unit b) {
                return Double.compare(a.t, b.t);
            }
        });
        return out;
    }

    /** A streak from the stack's pixels (the reference frame's) into unit {@code u}'s, by its rotation {@code R} from the reference. */
    private static void addStreak(Unit u, CameraModel m, double[] R, float x0, float y0, float x1, float y1, float half) {
        double[] a = toUnit(m, R, x0, y0), b = toUnit(m, R, x1, y1);
        if (a != null && b != null) u.streaks.add(new float[]{(float) a[0], (float) a[1], (float) b[0], (float) b[1], half});
    }

    private static double[] toUnit(CameraModel m, double[] R, double x, double y) {
        double[] d = new double[3], e = new double[3], q = new double[2];
        m.unproject(x, y, d);
        Rot.apply(R, d[0], d[1], d[2], e);
        return m.project(e[0], e[1], e[2], q) ? q : null;
    }

    /** The chip's area (cm²) from its pixel pitch (µm) and size; NaN if the pitch isn't known. */
    public static double area(int W, int H, double pitchUm) {
        if (!(pitchUm > 0)) return Double.NaN;
        return W * pitchUm * 1e-4 * H * pitchUm * 1e-4;
    }

    // ------------------------------------------------------------------------------------------------
    // The picture: an event display, as particle physicists draw theirs

    /** The heat colours of the hits' pictures: black, deep purple, red, orange, yellow, white. */
    private static final int[][] HEAT = {{0, 0, 0}, {40, 8, 70}, {150, 20, 60}, {235, 80, 20}, {255, 200, 40}, {255, 255, 235}};

    static int heat(float t) {
        t = Math.max(0, Math.min(1, t));
        float f = t * (HEAT.length - 1);
        int i = Math.min(HEAT.length - 2, (int) f);
        float u = f - i;
        int r = Math.round(HEAT[i][0] + (HEAT[i + 1][0] - HEAT[i][0]) * u);
        int g = Math.round(HEAT[i][1] + (HEAT[i + 1][1] - HEAT[i][1]) * u);
        int b = Math.round(HEAT[i][2] + (HEAT[i + 1][2] - HEAT[i][2]) * u);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    /** Where things go on a w x h picture: the grid of hits (up to {@link #SHOWN}) and the chip's map. */
    public static final class Layout {
        public final int w, h, cols, rows, shown;
        /** Each hit's place: cell width and height, the round close-up's diameter; the grid's top and bottom. */
        public final float unit, cellW, cellH, disc, gx0, gy0, gy1, mapX0, mapY0, mapW, mapH;

        Layout(int w, int h, int hits, int sensorW, int sensorH) {
            this.w = w;
            this.h = h;
            unit = Math.min(w / 1600f, h / 1600f);
            shown = Math.min(SHOWN, hits);
            cols = shown <= 4 ? Math.max(1, shown) : shown <= 6 ? 3 : 4;
            rows = Math.max(1, (shown + cols - 1) / cols);
            gx0 = 60 * unit;
            gy0 = 250 * unit;
            gy1 = h - 440 * unit;
            cellW = (w - 2 * gx0) / cols;
            cellH = (gy1 - gy0) / rows;
            disc = Math.min(Math.min(cellW - 50 * unit, cellH - 84 * unit), 360 * unit);
            mapW = 400 * unit;
            mapH = mapW * sensorH / Math.max(1, sensorW);
            mapX0 = w - 60 * unit - mapW;
            mapY0 = h - 60 * unit - mapH;
        }
    }

    /** The most hits pictured. */
    public static final int SHOWN = 12;

    public static Layout layout(int w, int h, Result r, int sensorW, int sensorH) {
        return new Layout(w, h, r.hits.size(), sensorW, sensorH);
    }

    /** The hits to picture: the strongest {@link #SHOWN}, in time order. */
    public static List<Hit> shown(Result r) {
        List<Hit> l = new ArrayList<Hit>(r.hits);
        Collections.sort(l, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                return Double.compare(b.levels, a.levels);
            }
        });
        if (l.size() > SHOWN) l = new ArrayList<Hit>(l.subList(0, SHOWN));
        Collections.sort(l, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                return Double.compare(a.t, b.t);
            }
        });
        return l;
    }

    /**
     * The picture's pixels before the words: deep black, each hit's close-up magnified in a round window in heat
     * colours (its light over the sky, brightest white), as a microscope would show it.
     */
    public static int[] background(Layout l, Result r, int sensorW, int sensorH) {
        int w = l.w, h = l.h;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            float t = y / (float) h;
            int c = Math.round(10 + 8 * (1 - t));
            for (int x = 0; x < w; x++) px[y * w + x] = 0xFF000000 | (c / 2) << 16 | (c / 3) << 8 | c;
        }
        List<Hit> sh = shown(r);
        for (int k = 0; k < sh.size(); k++) {
            Hit hh = sh.get(k);
            float[] c = centre(l, k);
            float rad = l.disc / 2, peak = 0;
            for (float v : hh.crop) peak = Math.max(peak, v);
            if (peak <= 0) continue;
            // the close-up fills the window: its square crop's middle part, the hit in the middle
            float span = hh.side * 0.86f, scale = span / l.disc;
            int x0 = (int) Math.floor(c[0] - rad - 1), x1 = (int) Math.ceil(c[0] + rad + 1);
            int y0 = (int) Math.floor(c[1] - rad - 1), y1 = (int) Math.ceil(c[1] + rad + 1);
            for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
                for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
                    float dx = x + 0.5f - c[0], dy = y + 0.5f - c[1], d = (float) Math.sqrt(dx * dx + dy * dy);
                    float a = Math.max(0, Math.min(1, rad - d + 0.5f));
                    if (a <= 0) continue;
                    int cxi = (int) Math.floor(hh.side / 2f + dx * scale), cyi = (int) Math.floor(hh.side / 2f + dy * scale);
                    cxi = Math.max(0, Math.min(hh.side - 1, cxi));
                    cyi = Math.max(0, Math.min(hh.side - 1, cyi));
                    float v = hh.crop[cyi * hh.side + cxi] / peak;
                    // a gentle stretch so the faint edges show, as event displays do
                    int col = heat((float) Math.sqrt(Math.max(0, v)) * 1.02f);
                    px[y * w + x] = a >= 1 ? col : mix(px[y * w + x], col, a);
                }
            }
        }
        return px;
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /** The middle of the k-th hit's round window (pixels); a short last row is centred. */
    static float[] centre(Layout l, int k) {
        int c = k % l.cols, row = k / l.cols;
        int inRow = Math.min(l.cols, l.shown - row * l.cols);
        float x0 = (l.w - inRow * l.cellW) / 2;
        return new float[]{x0 + (c + 0.5f) * l.cellW, l.gy0 + row * l.cellH + 8 * l.unit + l.disc / 2};
    }

    /** "21:43:07" for a hit {@code t} seconds into a session started at {@code startMs} (local time; "" if not known). */
    public static String clock(long startMs, double t, java.util.TimeZone zone) {
        if (startMs <= 0) return "";
        java.util.Calendar c = java.util.Calendar.getInstance(zone != null ? zone : java.util.TimeZone.getTimeZone("UTC"), Locale.US);
        c.setTimeInMillis(startMs + Math.round(t * 1000));
        return String.format(Locale.US, "%02d:%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND));
    }

    /** One hit's caption: "track · 14 px · 2.1 keV". */
    public static String caption(Hit h) {
        String s = h.kind.one + " · " + Math.round(h.length) + " px";
        if (!Double.isNaN(h.keV)) s += String.format(Locale.US, h.keV >= 10 ? " · %.0f keV" : " · %.1f keV", h.keV);
        return s;
    }

    /** The words on the picture: title, what it is, each hit's caption and time, the chip's map and its key. */
    public static void paint(Painter p, Layout l, Result r, int sensorW, int sensorH, long startMs, java.util.TimeZone zone) {
        float u = l.unit;
        p.text("COSMIC RAY HUNTER", 60 * u, 112 * u, 62 * u, true, 0xFFFFE9C7);
        String sub = r.hits.isEmpty() ? "No particles caught this time"
                : r.hits.size() == 1 ? "1 particle from space went through your camera's chip"
                : r.hits.size() + " particles from space went through your camera's chip";
        p.text(sub, 62 * u, 158 * u, 28 * u, false, 0xFFFFC27A);
        p.text(expectedWords(r) + (r.hits.size() > SHOWN ? "; the " + SHOWN + " brightest are here" : ""), 62 * u, 198 * u, 22 * u,
                false, 0xFFCDB8A0);
        List<Hit> sh = shown(r);
        float size = Math.min(21 * u, l.cellW * 0.075f);
        for (int k = 0; k < sh.size(); k++) {
            Hit h = sh.get(k);
            float[] c = centre(l, k);
            p.ring(c[0], c[1], l.disc / 2 + 2 * u, 1.6f * u, 0x55FFC27A);
            String cap = caption(h), when = clock(startMs, h.t, zone);
            float y = c[1] + l.disc / 2 + size * 1.55f;
            p.text(cap, c[0] - p.width(cap, size, true) / 2, y, size, true, 0xFFFFE2B0);
            if (!when.isEmpty()) {
                float s2 = size * 0.86f;
                p.text(when, c[0] - p.width(when, s2, false) / 2, y + size * 1.3f, s2, false, 0xFFB79F86);
            }
        }
        if (sh.isEmpty()) {
            float cy = (l.gy0 + l.gy1) / 2, rad = Math.min(220 * u, (l.gy1 - l.gy0) / 2 - 20 * u);
            p.ring(l.w / 2f, cy, rad, 1.6f * u, 0x55FFC27A);
            String a = "Nothing this time.", b = "Particles come when they like:", c2 = "the longer the night, the more you catch.";
            float s1 = 30 * u, s2 = 22 * u;
            p.text(a, l.w / 2f - p.width(a, s1, true) / 2, cy - 14 * u, s1, true, 0xFFFFE2B0);
            p.text(b, l.w / 2f - p.width(b, s2, false) / 2, cy + 28 * u, s2, false, 0xFFCDB8A0);
            p.text(c2, l.w / 2f - p.width(c2, s2, false) / 2, cy + 58 * u, s2, false, 0xFFCDB8A0);
        }
        // the chip, and where each hit struck it
        p.gradient(l.mapX0, l.mapY0, l.mapX0 + l.mapW, l.mapY0 + l.mapH, 0xFF15101C, 0xFF0E0A14);
        p.line(l.mapX0, l.mapY0, l.mapX0 + l.mapW, l.mapY0, 2 * u, 0xFF8C7A66);
        p.line(l.mapX0 + l.mapW, l.mapY0, l.mapX0 + l.mapW, l.mapY0 + l.mapH, 2 * u, 0xFF8C7A66);
        p.line(l.mapX0 + l.mapW, l.mapY0 + l.mapH, l.mapX0, l.mapY0 + l.mapH, 2 * u, 0xFF8C7A66);
        p.line(l.mapX0, l.mapY0 + l.mapH, l.mapX0, l.mapY0, 2 * u, 0xFF8C7A66);
        for (Hit h : r.hits) {
            float x = l.mapX0 + h.x / sensorW * l.mapW, y = l.mapY0 + h.y / sensorH * l.mapH;
            p.disc(x, y, 7 * u, 0x66FF8A3D);
            p.disc(x, y, 3.2f * u, 0xFFFFE08A);
        }
        String chip = "your camera's chip, every hit where it struck";
        p.text(chip, l.mapX0, l.mapY0 - 14 * u, 18 * u, false, 0xFFCDB8A0);
        // what they are
        float y = l.gy1 + 64 * u, room = l.mapX0 - 120 * u, tsize = 22 * u;
        String[] why = {
                "Cosmic rays are atoms thrown across the galaxy by exploding stars. When one hits the top of the air it bursts into a "
                        + "shower of particles; the muons in it reach the ground, about one through your fingernail every minute.",
                "Each one that passes through the chip knocks loose a few hundred electrons, lighting up the pixels it crossed in "
                        + "that one frame: a spot if it came straight down, a track at an angle, a curly worm for an electron a gamma ray knocked loose.",
                "Astronauts see them too: flashes of light with their eyes closed.",
        };
        for (String para : why) {
            for (String t : XRayVision.wrap(p, para, tsize, room)) {
                p.text(t, 60 * u, y, tsize, false, 0xFFE8DCCB);
                y += tsize * 1.32f;
            }
            y += tsize * 0.45f;
        }
    }

    /** "while it looked at the sky for 5 minutes (about 4 muons expected through the chip)". */
    public static String expectedWords(Result r) {
        String s = "while it looked at the sky for " + duration(r.seconds);
        if (!Double.isNaN(r.expected)) {
            long n = Math.max(1, Math.round(r.expected));
            s += " (about " + n + (n == 1 ? " muon" : " muons") + " expected)";
        }
        return s;
    }

    /** "5 minutes", "1 minute 20 seconds", "45 seconds". */
    static String duration(double s) {
        long m = Math.round(s) / 60, sec = Math.round(s) % 60;
        if (m == 0) return sec + " seconds";
        String out = m + (m == 1 ? " minute" : " minutes");
        if (sec >= 5 && m < 10) out += " " + sec + " seconds";
        return out;
    }
}
