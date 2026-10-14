package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.List;

/**
 * Live star tracker. The phone is still and the stars are at infinity, so every frame differs from the
 * reference by a pure 3D rotation of the sky. Stars are matched to the reference list using a prediction
 * from the recent rotation rate, and the rotation is solved in closed form (Horn) with outlier rejection.
 */
public final class Aligner {
    public static final class Result {
        public int unit;
        public double t;
        /** Rotation taking reference-frame bearings to this frame's bearings, or null if unsolved. */
        public double[] R;
        public boolean ok;
        public int nStars, nMatched, nInliers;
        public double rms;
        public double transparency = 1;
        public float medianHfr, background, noise;
        /** Inlier matches: reference star index and the star's position in this frame. */
        public int[] mRef;
        public float[] mX, mY;
        public String note = "";
    }

    public static final int MIN_REF_STARS = 12;
    public static final int MIN_PAIRS = 6;

    private final CameraModel model;
    private final int W, H;
    private StarList ref;
    private double[] refB;
    private boolean[] refStatic;
    private double refT;
    private int refUnit = -1;
    private Grid refGrid;
    private final List<Result> results = new ArrayList<Result>();
    private final List<double[]> okRv = new ArrayList<double[]>();
    private final List<Double> okTau = new ArrayList<Double>();

    public Aligner(CameraModel model, int width, int height) {
        this.model = model;
        this.W = width;
        this.H = height;
    }

    public List<Result> results() {
        return results;
    }

    public StarList reference() {
        return ref;
    }

    public int referenceUnit() {
        return refUnit;
    }

    public double referenceTime() {
        return refT;
    }

    public CameraModel model() {
        return model;
    }

    /**
     * Forgets the motion history (keeps the reference). Used when switching from processing frames after the
     * reference to frames before it.
     */
    public void resetPrediction() {
        okRv.clear();
        okTau.clear();
        if (ref != null) {
            okRv.add(new double[3]);
            okTau.add(0.0);
        }
    }

    public Result process(int unit, StarList s, double t) {
        Result r = new Result();
        r.unit = unit;
        r.t = t;
        r.nStars = s.count;
        r.medianHfr = s.medianHfr(80);
        r.background = s.background;
        r.noise = s.noise;
        if (ref == null) {
            if (s.count >= MIN_REF_STARS) {
                setReference(unit, s, t);
                r.R = Rot.identity();
                r.ok = true;
                r.nMatched = r.nInliers = s.count;
                r.mRef = new int[s.count];
                r.mX = new float[s.count];
                r.mY = new float[s.count];
                for (int i = 0; i < s.count; i++) {
                    r.mRef[i] = i;
                    r.mX[i] = s.x[i];
                    r.mY[i] = s.y[i];
                }
                r.note = "reference";
            } else {
                r.note = "too few stars (" + s.count + ")";
            }
            results.add(r);
            return r;
        }
        double tau = t - refT;
        double[] pred = predict(tau);
        double radius = okRv.size() >= 4 ? 5.0 : 12.0;
        boolean ok = match(s, pred, 0, 0, radius, r);
        if (!ok) ok = reacquire(s, pred, r);
        if (ok) {
            okRv.add(Rot.toRotVec(r.R));
            okTau.add(tau);
        } else if (r.note.isEmpty()) {
            r.note = "not aligned";
        }
        results.add(r);
        return r;
    }

    private void setReference(int unit, StarList s, double t) {
        ref = s;
        refUnit = unit;
        refT = t;
        refB = new double[3 * s.count];
        refStatic = new boolean[s.count];
        double[] b = new double[3];
        for (int i = 0; i < s.count; i++) {
            model.unproject(s.x[i], s.y[i], b);
            refB[3 * i] = b[0];
            refB[3 * i + 1] = b[1];
            refB[3 * i + 2] = b[2];
        }
        refGrid = new Grid(s.x, s.y, s.count, W, H, 16f);
        okRv.add(new double[3]);
        okTau.add(0.0);
    }

    /** Predicted rotation at time tau after the reference: last good fit plus the recent rotation rate. */
    public double[] predict(double tau) {
        int n = okRv.size();
        if (n == 0) return Rot.identity();
        double[] last = okRv.get(n - 1);
        if (n == 1) return Rot.fromRotVec(last);
        int from = Math.max(0, n - 12);
        int m = n - from;
        double mt = 0;
        double[] mr = new double[3];
        for (int k = from; k < n; k++) {
            mt += okTau.get(k);
            double[] rv = okRv.get(k);
            mr[0] += rv[0];
            mr[1] += rv[1];
            mr[2] += rv[2];
        }
        mt /= m;
        mr[0] /= m;
        mr[1] /= m;
        mr[2] /= m;
        double stt = 0;
        double[] str = new double[3];
        for (int k = from; k < n; k++) {
            double dt = okTau.get(k) - mt;
            double[] rv = okRv.get(k);
            stt += dt * dt;
            str[0] += dt * (rv[0] - mr[0]);
            str[1] += dt * (rv[1] - mr[1]);
            str[2] += dt * (rv[2] - mr[2]);
        }
        if (stt < 1e-9) return Rot.fromRotVec(last);
        double dtau = tau - okTau.get(n - 1);
        return Rot.fromRotVec(last[0] + str[0] / stt * dtau, last[1] + str[1] / stt * dtau, last[2] + str[2] / stt * dtau);
    }

    private boolean match(StarList s, double[] R, double du, double dv, double radius, Result r) {
        int n = ref.count;
        float[] pu = new float[n], pv = new float[n];
        boolean[] valid = new boolean[n];
        double[] b = new double[3], p = new double[2];
        for (int i = 0; i < n; i++) {
            if (refStatic[i]) continue;
            Rot.apply(R, refB[3 * i], refB[3 * i + 1], refB[3 * i + 2], b);
            if (!model.project(b[0], b[1], b[2], p)) continue;
            double x = p[0] + du, y = p[1] + dv;
            if (x < 2 || y < 2 || x > W - 2 || y > H - 2) continue;
            pu[i] = (float) x;
            pv[i] = (float) y;
            valid[i] = true;
        }
        Grid g = new Grid(s.x, s.y, s.count, W, H, (float) Math.max(16, radius * 2));
        int[] owner = new int[s.count];
        float[] ownerD = new float[s.count];
        java.util.Arrays.fill(owner, -1);
        for (int i = 0; i < n; i++) {
            if (!valid[i]) continue;
            int j = g.nearest(pu[i], pv[i], radius);
            if (j < 0) continue;
            float d = (float) Math.hypot(s.x[j] - pu[i], s.y[j] - pv[i]);
            if (owner[j] < 0 || d < ownerD[j]) {
                owner[j] = i;
                ownerD[j] = d;
            }
        }
        int m = 0;
        int[] pi = new int[s.count], pj = new int[s.count];
        for (int j = 0; j < s.count; j++) {
            int i = owner[j];
            if (i < 0) continue;
            // A detection sitting exactly where a reference star was, while the sky has clearly moved,
            // is fixed to the sensor (hot pixel), not a star.
            double moved = Math.hypot(pu[i] - ref.x[i], pv[i] - ref.y[i]);
            if (moved > 2.5) {
                int k = refGrid.nearest(s.x[j], s.y[j], 0.8);
                if (k >= 0) {
                    refStatic[k] = true;
                    continue;
                }
            }
            pi[m] = i;
            pj[m] = j;
            m++;
        }
        r.nMatched = m;
        if (m < MIN_PAIRS) {
            r.note = "only " + m + " matches";
            return false;
        }
        double[] A = new double[3 * m], B = new double[3 * m];
        for (int k = 0; k < m; k++) {
            int i = pi[k], j = pj[k];
            A[3 * k] = refB[3 * i];
            A[3 * k + 1] = refB[3 * i + 1];
            A[3 * k + 2] = refB[3 * i + 2];
            model.unproject(s.x[j], s.y[j], b);
            B[3 * k] = b[0];
            B[3 * k + 1] = b[1];
            B[3 * k + 2] = b[2];
        }
        double[] fitR = null;
        int[] use = new int[m];
        int nUse = m;
        for (int k = 0; k < m; k++) use[k] = k;
        double[] res = new double[m];
        float[] tmp = new float[m];
        for (int it = 0; it < 6; it++) {
            fitR = Rot.solve(A, B, null, use, nUse);
            for (int k = 0; k < m; k++) {
                Rot.apply(fitR, A[3 * k], A[3 * k + 1], A[3 * k + 2], b);
                if (!model.project(b[0], b[1], b[2], p)) {
                    res[k] = 1e9;
                    continue;
                }
                int j = pj[k];
                res[k] = Math.hypot(p[0] - s.x[j], p[1] - s.y[j]);
            }
            for (int q = 0; q < nUse; q++) tmp[q] = (float) res[use[q]];
            double med = Stats.median(tmp, nUse);
            double thr = Math.max(3.0 * med, 0.8);
            int[] next = new int[m];
            int nn = 0;
            for (int k = 0; k < m; k++) if (res[k] < thr) next[nn++] = k;
            boolean same = nn == nUse;
            if (same) {
                for (int q = 0; q < nn; q++) {
                    if (next[q] != use[q]) {
                        same = false;
                        break;
                    }
                }
            }
            use = next;
            nUse = nn;
            if (same || nUse < MIN_PAIRS) break;
        }
        if (nUse < MIN_PAIRS || nUse < 0.25 * m) {
            r.note = "inconsistent matches";
            return false;
        }
        double ss = 0, fluxNow = 0, fluxRef = 0;
        r.mRef = new int[nUse];
        r.mX = new float[nUse];
        r.mY = new float[nUse];
        for (int q = 0; q < nUse; q++) {
            int k = use[q];
            ss += res[k] * res[k];
            int i = pi[k], j = pj[k];
            r.mRef[q] = i;
            r.mX[q] = s.x[j];
            r.mY[q] = s.y[j];
            if (!s.saturated[j] && !ref.saturated[i]) {
                fluxNow += s.flux[j];
                fluxRef += ref.flux[i];
            }
        }
        r.rms = Math.sqrt(ss / nUse);
        if (r.rms > 1.5) {
            r.note = String.format(java.util.Locale.US, "poor fit (%.2f px)", r.rms);
            return false;
        }
        r.R = fitR;
        r.ok = true;
        r.nInliers = nUse;
        r.transparency = fluxRef > 0 ? fluxNow / fluxRef : 1;
        r.note = "";
        return true;
    }

    /** Recovers after a bump or a cloudy gap: vote for the image shift between predicted and detected stars. */
    private boolean reacquire(StarList s, double[] R, Result r) {
        int nr = Math.min(ref.count, 80), ns = Math.min(s.count, 80);
        if (ns < MIN_PAIRS) return false;
        double[] b = new double[3], p = new double[2];
        float[] pu = new float[nr], pv = new float[nr];
        boolean[] valid = new boolean[nr];
        for (int i = 0; i < nr; i++) {
            if (refStatic[i]) continue;
            Rot.apply(R, refB[3 * i], refB[3 * i + 1], refB[3 * i + 2], b);
            if (!model.project(b[0], b[1], b[2], p)) continue;
            pu[i] = (float) p[0];
            pv[i] = (float) p[1];
            valid[i] = true;
        }
        final int maxShift = 400, bin = 4, nb = 2 * maxShift / bin + 1;
        int[] votes = new int[nb * nb];
        for (int i = 0; i < nr; i++) {
            if (!valid[i]) continue;
            for (int j = 0; j < ns; j++) {
                double dx = s.x[j] - pu[i], dy = s.y[j] - pv[i];
                if (Math.abs(dx) >= maxShift || Math.abs(dy) >= maxShift) continue;
                int bx = (int) ((dx + maxShift) / bin), by = (int) ((dy + maxShift) / bin);
                votes[by * nb + bx]++;
            }
        }
        int best = -1, bestV = 0;
        for (int by = 1; by < nb - 1; by++) {
            for (int bx = 1; bx < nb - 1; bx++) {
                int v = 0;
                for (int yy = -1; yy <= 1; yy++)
                    for (int xx = -1; xx <= 1; xx++) v += votes[(by + yy) * nb + bx + xx];
                if (v > bestV) {
                    bestV = v;
                    best = by * nb + bx;
                }
            }
        }
        if (best < 0 || bestV < 5) return false;
        double du = (best % nb) * bin - maxShift + bin * 0.5;
        double dv = (best / nb) * bin - maxShift + bin * 0.5;
        Result trial = new Result();
        if (!match(s, R, du, dv, 10, trial)) return false;
        // second pass from the solved rotation, tight radius
        Result fin = new Result();
        if (!match(s, trial.R, 0, 0, 4, fin)) fin = trial;
        r.R = fin.R;
        r.ok = true;
        r.nMatched = fin.nMatched;
        r.nInliers = fin.nInliers;
        r.rms = fin.rms;
        r.transparency = fin.transparency;
        r.mRef = fin.mRef;
        r.mX = fin.mX;
        r.mY = fin.mY;
        r.note = String.format(java.util.Locale.US, "re-acquired (shift %.0f,%.0f)", du, dv);
        return true;
    }

    /** Fixed-cell spatial hash for nearest-neighbour lookups. */
    static final class Grid {
        final float cell;
        final int nx, ny;
        final int[] head, next;
        final float[] xs, ys;

        Grid(float[] xs, float[] ys, int n, int w, int h, float cell) {
            this.cell = cell;
            this.xs = xs;
            this.ys = ys;
            nx = Math.max(1, (int) Math.ceil(w / cell));
            ny = Math.max(1, (int) Math.ceil(h / cell));
            head = new int[nx * ny];
            next = new int[Math.max(1, n)];
            java.util.Arrays.fill(head, -1);
            for (int i = 0; i < n; i++) {
                int cx = Stats.clamp((int) (xs[i] / cell), 0, nx - 1);
                int cy = Stats.clamp((int) (ys[i] / cell), 0, ny - 1);
                int c = cy * nx + cx;
                next[i] = head[c];
                head[c] = i;
            }
        }

        int nearest(double x, double y, double radius) {
            int c0x = (int) Math.floor((x - radius) / cell), c1x = (int) Math.floor((x + radius) / cell);
            int c0y = (int) Math.floor((y - radius) / cell), c1y = (int) Math.floor((y + radius) / cell);
            c0x = Math.max(0, c0x);
            c0y = Math.max(0, c0y);
            c1x = Math.min(nx - 1, c1x);
            c1y = Math.min(ny - 1, c1y);
            int best = -1;
            double bestD = radius * radius;
            for (int cy = c0y; cy <= c1y; cy++) {
                for (int cx = c0x; cx <= c1x; cx++) {
                    for (int i = head[cy * nx + cx]; i >= 0; i = next[i]) {
                        double dx = xs[i] - x, dy = ys[i] - y;
                        double d = dx * dx + dy * dy;
                        if (d < bestD) {
                            bestD = d;
                            best = i;
                        }
                    }
                }
            }
            return best;
        }
    }
}
