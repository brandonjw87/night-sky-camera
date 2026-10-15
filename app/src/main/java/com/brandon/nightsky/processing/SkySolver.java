package com.brandon.nightsky.processing;

import java.util.Arrays;
import java.util.Locale;

/**
 * Where a frame points on the sky (plate solving): matches its brightest stars to the bright-star catalogue
 * and fits the rotation from the camera's bearings to J2000 directions.
 * <p>
 * Angles between stars don't depend on where the camera points, so pairs of the frame's brightest stars are
 * looked up among catalogue pairs of the same separation; each candidate pair gives a pointing, and the one
 * that puts the most of the frame's stars on catalogue stars wins. With the time and the observer's place,
 * only the stars around the zenith are tried (the phone lies flat), which makes it quick and hard to fool;
 * without them, the brightest stars of the whole sky are. The winner is refined on every star that matches,
 * then the camera model too (focal length and radial distortion), if enough stars across the frame allow.
 * <p>
 * It also gives the frame's photometric zero point (the flux of a magnitude-0 star in the frame's units),
 * from which follow its limiting magnitude and, against a clear night's zero point, its transparency.
 */
public final class SkySolver {
    /** What is known before looking at the stars. */
    public static final class Prior {
        /** Time of the frame (UTC Julian date), or NaN. */
        public double jdUtc = Double.NaN;
        /** Observer (radians), or NaN. */
        public double lat = Double.NaN, lon = Double.NaN;
        /** How far the optical axis may be from the zenith (radians). */
        public double maxTilt = 20 * Astro.DEG;

        public boolean located() {
            return !Double.isNaN(jdUtc) && !Double.isNaN(lat) && !Double.isNaN(lon);
        }
    }

    public static final class Solution {
        /** Camera bearings to J2000 directions. */
        public double[] R;
        /** The model the solution was fitted with (refined if {@link #modelRefined}). */
        public CameraModel model;
        public boolean modelRefined;
        public int matched, tried;
        public double rmsPx;
        /** Matched pairs: detection (pixel) and catalogue index. */
        public float[] mx, my;
        public int[] cat;
        /** Flux (detection units) of a magnitude-0 star: 2.5 log10(flux) + V, median over matched stars; NaN if unknown. */
        public double zeroPoint = Double.NaN;

        /** J2000 direction of pixel (x, y). */
        public double[] direction(double x, double y) {
            double[] b = new double[3], o = new double[3];
            model.unproject(x, y, b);
            Rot.apply(R, b[0], b[1], b[2], o);
            return o;
        }

        /** Pixel of a J2000 direction, or null if behind the camera. */
        public double[] pixel(double[] j2000) {
            double[] b = new double[3], p = new double[2];
            Rot.apply(Rot.transpose(R), j2000[0], j2000[1], j2000[2], b);
            return model.project(b[0], b[1], b[2], p) ? p : null;
        }

        public String describe() {
            double[] c = direction(model.cx, model.cy);
            double[] rd = Astro.raDec(c);
            return String.format(Locale.US, "centre RA %.3f deg, Dec %+.3f deg; %d of %d stars matched, %.2f px rms%s",
                    rd[0] / Astro.DEG, rd[1] / Astro.DEG, matched, tried, rmsPx, modelRefined ? "; lens model refined" : "");
        }
    }

    /** Detections used to find the pointing, and pairs of them tried. */
    static final int FIND_STARS = 60, PAIR_STARS = 14;
    /** Catalogue stars tried around the zenith, or across the whole sky without a place and time. */
    static final int CAT_LOCAL = 160, CAT_SKY = 450;
    /** Matching tolerance while finding the pointing (radians), and on the final fit (pixels). */
    static final double FIND_TOL = 0.35 * Astro.DEG, FIT_TOL_PX = 2.5;
    /** Fewest matches that count as a solution. */
    static final int MIN_MATCHES = 8;

    private SkySolver() {}

    /**
     * Null if the stars don't give a pointing. The pointing is found from {@code find}, brightest first, best
     * only stars that other frames saw too (a plane or a satellite in the frame would otherwise be among the
     * brightest); it is refined on {@code refine} (every detection, or null for {@code find}), where anything
     * that isn't a star simply finds no catalogue star to match.
     */
    public static Solution solve(StarList find, StarList refine, CameraModel model, BrightStars catalog, Prior prior) {
        if (find == null || find.count < 5 || catalog == null) return null;
        StarList stars = refine != null && refine.count >= find.count / 2 ? refine : find;
        // the brightest point-like stars, as bearings, taken in turns from across the frame so that no one patch
        // (a plane's lights, a bright cluster) can fill the list
        int nd = 0;
        int[] di = spread(find, model.width, model.height, FIND_STARS);
        nd = di.length;
        if (nd < 5) return null;
        double[] db = new double[3 * nd];
        double[] b = new double[3];
        for (int k = 0; k < nd; k++) {
            model.unproject(find.x[di[k]], find.y[di[k]], b);
            db[3 * k] = b[0];
            db[3 * k + 1] = b[1];
            db[3 * k + 2] = b[2];
        }
        // half the frame's diagonal field
        double[] c0 = new double[3], c1 = new double[3];
        model.unproject(model.width / 2.0, model.height / 2.0, c0);
        model.unproject(0, 0, c1);
        double halfDiag = Astro.sep(c0, c1);
        // catalogue candidates
        double[] zenith = null;
        int[] cand;
        if (prior != null && prior.located()) {
            double[] M = Astro.j2000ToEnu(prior.jdUtc, prior.lat, prior.lon);
            zenith = new double[3];
            Rot.apply(Rot.transpose(M), 0, 0, 1, zenith);
            cand = catalog.within(zenith, prior.maxTilt + halfDiag + 1 * Astro.DEG, 6.5);
            if (cand.length > CAT_LOCAL) cand = Arrays.copyOf(cand, CAT_LOCAL);
        } else {
            cand = catalog.within(new double[]{0, 0, 1}, Math.PI, 5.0);
            if (cand.length > CAT_SKY) cand = Arrays.copyOf(cand, CAT_SKY);
        }
        if (cand.length < 5) return null;
        // catalogue pairs no wider than the frame, sorted by separation
        double maxSep = 2 * halfDiag + 1 * Astro.DEG;
        int nc = cand.length;
        double[] cv = new double[3 * nc];
        for (int k = 0; k < nc; k++) System.arraycopy(catalog.dir, 3 * cand[k], cv, 3 * k, 3);
        double cosMax = Math.cos(maxSep);
        int np = 0;
        long[] pairs = new long[nc * (nc - 1) / 2];
        double[] psep = new double[pairs.length];
        for (int i = 0; i < nc; i++) {
            for (int j = i + 1; j < nc; j++) {
                double d = cv[3 * i] * cv[3 * j] + cv[3 * i + 1] * cv[3 * j + 1] + cv[3 * i + 2] * cv[3 * j + 2];
                if (d < cosMax) continue;
                psep[np] = Math.acos(Math.min(1, d));
                pairs[np] = ((long) i << 32) | j;
                np++;
            }
        }
        Integer[] order = new Integer[np];
        for (int i = 0; i < np; i++) order[i] = i;
        final double[] ps = psep;
        Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer c) {
                return Double.compare(ps[a], ps[c]);
            }
        });
        double[] sepSorted = new double[np];
        long[] pairSorted = new long[np];
        for (int i = 0; i < np; i++) {
            sepSorted[i] = psep[order[i]];
            pairSorted[i] = pairs[order[i]];
        }
        // the stars that verify a pointing: everything bright enough near the candidates
        int[] verify = zenith != null ? catalog.within(zenith, prior.maxTilt + halfDiag + 2 * Astro.DEG, 7.0)
                : catalog.within(new double[]{0, 0, 1}, Math.PI, 6.0);
        SphereGrid grid = new SphereGrid(catalog, verify, FIND_TOL);
        // try pairs of the brightest detections against catalogue pairs of the same separation
        int pn = Math.min(nd, PAIR_STARS);
        double[] bestR = null;
        int bestScore = 0;
        double cosTilt = prior != null && prior.located() ? Math.cos(prior.maxTilt + 0.5 * Astro.DEG) : -2;
        outer:
        for (int i = 0; i < pn; i++) {
            for (int j = i + 1; j < pn; j++) {
                double[] a1 = sub(db, i), a2 = sub(db, j);
                double s = Astro.sep(a1, a2);
                if (s < 1.5 * Astro.DEG) continue;
                double tol = 0.12 * Astro.DEG + 0.006 * s;
                int lo = lowerBound(sepSorted, s - tol);
                for (int k = lo; k < np && sepSorted[k] <= s + tol; k++) {
                    int ci = (int) (pairSorted[k] >>> 32), cj = (int) (pairSorted[k] & 0xFFFFFFFFL);
                    for (int flip = 0; flip < 2; flip++) {
                        double[] b1 = sub(cv, flip == 0 ? ci : cj), b2 = sub(cv, flip == 0 ? cj : ci);
                        double[] R = triad(a1, a2, b1, b2);
                        if (R == null) continue;
                        if (zenith != null) {
                            // the optical axis must point near the zenith
                            double ax = R[2] * zenith[0] + R[5] * zenith[1] + R[8] * zenith[2];
                            if (ax < cosTilt) continue;
                        }
                        int score = grid.count(R, db, nd);
                        if (score > bestScore) {
                            bestScore = score;
                            bestR = R;
                            if (score >= Math.max(20, (int) (0.7 * nd))) break outer;
                        }
                    }
                }
            }
        }
        if (bestR == null || bestScore < Math.max(MIN_MATCHES, Math.min(nd / 3, 15))) return null;
        // refine on every detection: rotation, then the lens, then the rotation again with tight matching
        Solution sol = refine(stars, model, catalog, bestR, zenith != null ? zenith : null,
                zenith != null ? prior.maxTilt + halfDiag + 2 * Astro.DEG : Math.PI);
        if (sol == null || sol.matched < MIN_MATCHES) return null;
        return sol;
    }

    /**
     * Up to max point-like detections, brightest first within each cell of a 4 x 4 grid over the frame, the cells
     * taking turns: the brightest of every cell, then the second brightest of every cell, and so on (each round
     * in order of brightness).
     */
    static int[] spread(StarList s, int W, int H, int max) {
        int G = 4;
        int[][] lists = new int[G * G][];
        int[] len = new int[G * G];
        for (int c = 0; c < lists.length; c++) lists[c] = new int[Math.min(s.count, max)];
        for (int i = 0; i < s.count; i++) {
            if (s.elong[i] > 2f) continue;
            int cx = Math.min(G - 1, Math.max(0, (int) (s.x[i] * G / W))), cy = Math.min(G - 1, Math.max(0, (int) (s.y[i] * G / H)));
            int c = cy * G + cx;
            if (len[c] < lists[c].length) lists[c][len[c]++] = i;
        }
        int[] out = new int[max];
        int n = 0;
        int[] round = new int[G * G];
        for (int r = 0; n < max; r++) {
            int m = 0;
            for (int c = 0; c < G * G; c++) if (r < len[c]) round[m++] = lists[c][r];
            if (m == 0) break;
            // within a round, brightest first (the list is sorted by flux, so by index)
            Arrays.sort(round, 0, m);
            for (int k = 0; k < m && n < max; k++) out[n++] = round[k];
        }
        return Arrays.copyOf(out, n);
    }

    /** Matches all detections against the catalogue around the frame and fits; then the lens model if it helps. */
    static Solution refine(StarList stars, CameraModel model, BrightStars catalog, double[] R0, double[] zenith, double radius) {
        double[] c = new double[3];
        Rot.apply(R0, 0, 0, 1, c);
        double[] b0 = new double[3], b1 = new double[3];
        model.unproject(model.width / 2.0, model.height / 2.0, b0);
        model.unproject(0, 0, b1);
        int[] near = catalog.within(c, Astro.sep(b0, b1) + 1.5 * Astro.DEG, 8.5);
        SphereGrid grid = new SphereGrid(catalog, near, 0.5 * Astro.DEG);
        double[] R = R0;
        CameraModel m = model;
        Match mt = null;
        double[] tols = {0.30 * Astro.DEG, 12 / model.fx, 5 / model.fx, FIT_TOL_PX / model.fx};
        for (double tol : tols) {
            mt = match(stars, m, R, grid, catalog, tol);
            if (mt.n < MIN_MATCHES) return null;
            R = Rot.solve(mt.bearings, mt.dirs, null, null, mt.n);
        }
        boolean refined = false;
        if (mt.n >= 40) {
            CameraModel better = LensFit.fit(m, R, mt, catalog);
            if (better != null) {
                Match m2 = match(stars, better, R, grid, catalog, FIT_TOL_PX / better.fx);
                double[] R2 = Rot.solve(m2.bearings, m2.dirs, null, null, m2.n);
                double before = rms(m, R, mt), after = rms(better, R2, m2);
                if (m2.n >= mt.n && after < 0.8 * before) {
                    m = better;
                    R = R2;
                    mt = m2;
                    refined = true;
                }
            }
        }
        Solution s = new Solution();
        s.R = R;
        s.model = m;
        s.modelRefined = refined;
        s.matched = mt.n;
        s.tried = Math.min(stars.count, 600);
        s.rmsPx = rms(m, R, mt);
        s.mx = Arrays.copyOf(mt.x, mt.n);
        s.my = Arrays.copyOf(mt.y, mt.n);
        s.cat = Arrays.copyOf(mt.cat, mt.n);
        // zero point from unsaturated matched stars
        float[] zp = new float[mt.n];
        int nz = 0;
        for (int k = 0; k < mt.n; k++) {
            int i = mt.det[k];
            if (stars.saturated[i] || !(stars.flux[i] > 0)) continue;
            zp[nz++] = (float) (2.5 * Math.log10(stars.flux[i]) + catalog.vmag[mt.cat[k]]);
        }
        if (nz >= 5) s.zeroPoint = Stats.median(zp, nz);
        return s;
    }

    /** Pairs of detections and catalogue stars within tol (radians) of each other under R. */
    static final class Match {
        int n;
        double[] bearings, dirs;
        float[] x, y;
        int[] cat, det;
    }

    static Match match(StarList stars, CameraModel m, double[] R, SphereGrid grid, BrightStars catalog, double tol) {
        int cap = Math.min(stars.count, 600);
        Match mt = new Match();
        mt.bearings = new double[3 * cap];
        mt.dirs = new double[3 * cap];
        mt.x = new float[cap];
        mt.y = new float[cap];
        mt.cat = new int[cap];
        mt.det = new int[cap];
        boolean[] taken = new boolean[catalog.n];
        double[] b = new double[3], w = new double[3];
        for (int i = 0; i < cap; i++) {
            m.unproject(stars.x[i], stars.y[i], b);
            Rot.apply(R, b[0], b[1], b[2], w);
            int k = grid.nearest(w, tol);
            if (k < 0 || taken[k]) continue;
            taken[k] = true;
            int n = mt.n++;
            System.arraycopy(b, 0, mt.bearings, 3 * n, 3);
            System.arraycopy(catalog.dir, 3 * k, mt.dirs, 3 * n, 3);
            mt.x[n] = stars.x[i];
            mt.y[n] = stars.y[i];
            mt.cat[n] = k;
            mt.det[n] = i;
        }
        return mt;
    }

    /** Root-mean-square distance (pixels) between matched detections and their catalogue stars. */
    static double rms(CameraModel m, double[] R, Match mt) {
        double[] Rt = Rot.transpose(R), b = new double[3], p = new double[2];
        double s = 0;
        int n = 0;
        for (int k = 0; k < mt.n; k++) {
            Rot.apply(Rt, mt.dirs[3 * k], mt.dirs[3 * k + 1], mt.dirs[3 * k + 2], b);
            if (!m.project(b[0], b[1], b[2], p)) continue;
            double dx = p[0] - mt.x[k], dy = p[1] - mt.y[k];
            s += dx * dx + dy * dy;
            n++;
        }
        return n > 0 ? Math.sqrt(s / n) : Double.NaN;
    }

    /** Rotation taking a1 to b1 exactly and a2 toward b2 (TRIAD). */
    static double[] triad(double[] a1, double[] a2, double[] b1, double[] b2) {
        double[] ta2 = cross(a1, a2), tb2 = cross(b1, b2);
        if (!normalize(ta2) || !normalize(tb2)) return null;
        double[] ta3 = cross(a1, ta2), tb3 = cross(b1, tb2);
        // R = [b1 tb2 tb3] [a1 ta2 ta3]^T
        double[] R = new double[9];
        for (int r = 0; r < 3; r++) {
            for (int col = 0; col < 3; col++) {
                R[3 * r + col] = b1[r] * a1[col] + tb2[r] * ta2[col] + tb3[r] * ta3[col];
            }
        }
        return R;
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static boolean normalize(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (!(n > 1e-12)) return false;
        v[0] /= n;
        v[1] /= n;
        v[2] /= n;
        return true;
    }

    private static double[] sub(double[] a, int i) {
        return new double[]{a[3 * i], a[3 * i + 1], a[3 * i + 2]};
    }

    private static int lowerBound(double[] a, double v) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < v) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** Catalogue stars hashed on a grid over the unit sphere, for nearest-star lookups within a tolerance. */
    static final class SphereGrid {
        private static final long EMPTY = Long.MIN_VALUE;
        private final BrightStars cat;
        private final double cell;
        private final long[] keys;
        private final int[] start, len, flat;
        private final int mask;

        SphereGrid(BrightStars cat, int[] stars, double tol) {
            this.cat = cat;
            cell = Math.max(tol, 1e-4);
            int n = stars.length;
            long[] k = new long[n];
            Integer[] ord = new Integer[n];
            for (int i = 0; i < n; i++) {
                int s = stars[i];
                k[i] = key(cat.dir[3 * s], cat.dir[3 * s + 1], cat.dir[3 * s + 2], 0, 0, 0);
                ord[i] = i;
            }
            final long[] kk = k;
            Arrays.sort(ord, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return Long.compare(kk[a], kk[b]);
                }
            });
            flat = new int[n];
            for (int i = 0; i < n; i++) flat[i] = stars[ord[i]];
            int cap = Integer.highestOneBit(Math.max(4, 4 * n)) << 1;
            mask = cap - 1;
            keys = new long[cap];
            start = new int[cap];
            len = new int[cap];
            Arrays.fill(keys, EMPTY);
            for (int i = 0; i < n; ) {
                long key = k[ord[i]];
                int j = i;
                while (j < n && k[ord[j]] == key) j++;
                int h = slot(key);
                while (keys[h] != EMPTY) h = (h + 1) & mask;
                keys[h] = key;
                start[h] = i;
                len[h] = j - i;
                i = j;
            }
        }

        private int slot(long key) {
            long x = key * 0x9E3779B97F4A7C15L;
            return (int) (x >>> 40) & mask;
        }

        private long key(double x, double y, double z, int dx, int dy, int dz) {
            long ix = (long) Math.floor((x + 1) / cell) + dx, iy = (long) Math.floor((y + 1) / cell) + dy,
                    iz = (long) Math.floor((z + 1) / cell) + dz;
            return (ix * 1000003L + iy) * 1000003L + iz;
        }

        /** Index of the catalogue star nearest v within tol (radians, at most the grid's cell), or -1. */
        int nearest(double[] v, double tol) {
            double best = Math.cos(tol);
            int bi = -1;
            long ix = (long) Math.floor((v[0] + 1) / cell), iy = (long) Math.floor((v[1] + 1) / cell),
                    iz = (long) Math.floor((v[2] + 1) / cell);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        long key = ((ix + dx) * 1000003L + iy + dy) * 1000003L + iz + dz;
                        int h = slot(key);
                        while (keys[h] != EMPTY && keys[h] != key) h = (h + 1) & mask;
                        if (keys[h] == EMPTY) continue;
                        for (int q = start[h], e = start[h] + len[h]; q < e; q++) {
                            int s = flat[q];
                            double d = cat.dir[3 * s] * v[0] + cat.dir[3 * s + 1] * v[1] + cat.dir[3 * s + 2] * v[2];
                            if (d > best) {
                                best = d;
                                bi = s;
                            }
                        }
                    }
                }
            }
            return bi;
        }

        /**
         * How many of the bearings land on a catalogue star under R; 0 early if few of the first ten do (a wrong
         * pointing, the usual case, is given up quickly).
         */
        int count(double[] R, double[] bearings, int n) {
            double[] w = new double[3];
            int c = 0;
            for (int i = 0; i < n; i++) {
                Rot.apply(R, bearings[3 * i], bearings[3 * i + 1], bearings[3 * i + 2], w);
                if (nearest(w, cell) >= 0) c++;
                if (i == 9 && c < 5) return 0;
            }
            return c;
        }
    }

    /**
     * The lens model from catalogue matches: focal length (one scale for both axes, keeping their ratio)
     * and radial distortion k1, k2, with the rotation, by Gauss-Newton on the pixel residuals.
     */
    static final class LensFit {
        static CameraModel fit(CameraModel m0, double[] R0, Match mt, BrightStars catalog) {
            // parameters: rotation increment (3), log scale of f, k1, k2
            double[] q = new double[6];
            CameraModel m = m0.copy();
            double[] R = R0.clone();
            double prev = Double.MAX_VALUE;
            for (int it = 0; it < 12; it++) {
                double[] JtJ = new double[36], Jtr = new double[6];
                double cost = 0;
                double[] r0 = new double[2], r1 = new double[2];
                for (int k = 0; k < mt.n; k++) {
                    if (!residual(m, R, mt, k, q, r0)) continue;
                    cost += r0[0] * r0[0] + r0[1] * r0[1];
                    double[][] J = new double[6][];
                    for (int p = 0; p < 6; p++) {
                        double h = p < 3 ? 1e-6 : p == 3 ? 1e-6 : 1e-5;
                        double[] qq = q.clone();
                        qq[p] += h;
                        if (!residual(m, R, mt, k, qq, r1)) {
                            J = null;
                            break;
                        }
                        J[p] = new double[]{(r1[0] - r0[0]) / h, (r1[1] - r0[1]) / h};
                    }
                    if (J == null) continue;
                    for (int a = 0; a < 6; a++) {
                        for (int c = 0; c < 6; c++) JtJ[6 * a + c] += J[a][0] * J[c][0] + J[a][1] * J[c][1];
                        Jtr[a] += J[a][0] * r0[0] + J[a][1] * r0[1];
                    }
                }
                if (cost > prev * 0.9999 && it > 0) break;
                prev = cost;
                for (int a = 0; a < 6; a++) JtJ[7 * a] *= 1.0001;
                double[] inv = SkyMotion.inverse6(JtJ);
                if (inv == null) return null;
                double[] dq = new double[6];
                for (int a = 0; a < 6; a++) for (int c = 0; c < 6; c++) dq[a] -= inv[6 * a + c] * Jtr[c];
                for (int a = 0; a < 6; a++) q[a] += dq[a];
            }
            CameraModel out = apply(m, q);
            if (out == null || Math.abs(q[3]) > 0.05 || Math.abs(q[4]) > 0.2 || Math.abs(q[5]) > 0.2) return null;
            out.source = m0.source + "+stars";
            return out;
        }

        static CameraModel apply(CameraModel m, double[] q) {
            CameraModel o = m.copy();
            double s = Math.exp(q[3]);
            o.fx = m.fx * s;
            o.fy = m.fy * s;
            o.k1 = m.k1 + q[4];
            o.k2 = m.k2 + q[5];
            return o;
        }

        private static boolean residual(CameraModel m, double[] R, Match mt, int k, double[] q, double[] out) {
            CameraModel mm = apply(m, q);
            double[] Rq = Rot.mul(R, Rot.fromRotVec(q[0], q[1], q[2]));
            double[] b = new double[3], p = new double[2];
            Rot.apply(Rot.transpose(Rq), mt.dirs[3 * k], mt.dirs[3 * k + 1], mt.dirs[3 * k + 2], b);
            if (!mm.project(b[0], b[1], b[2], p)) return false;
            out[0] = p[0] - mt.x[k];
            out[1] = p[1] - mt.y[k];
            return true;
        }
    }
}
