package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Refines the lens model (focal length, radial distortion, principal point) from the star matches of the
 * whole session. Every frame is a pure rotation of the reference, so a wrong lens model shows up as
 * position-dependent residuals that no rotation can remove. That is what matters for sharp corners when
 * the phone reports no (or slightly wrong) lens calibration.
 * <p>
 * For a candidate model each frame's rotation is solved in closed form (Horn), so the search is only over
 * the 5 lens parameters (Nelder-Mead, scaled by their prior uncertainty, with a robust loss).
 */
public final class ModelRefiner {
    private ModelRefiner() {}

    public static final class Outcome {
        public CameraModel model;
        public double rmsBefore, rmsAfter;
        public boolean changed;
        public int frames, pairs;
        public String note = "";
    }

    private static final double SIGMA_PIX = 0.3, HUBER = 1.0;

    private static final class Frame {
        int[] ref;
        float[] x, y;
    }

    public static Outcome refine(CameraModel m0, StarList ref, List<Aligner.Result> results, int refUnit) {
        Outcome out = new Outcome();
        out.model = m0;
        List<Frame> frames = new ArrayList<Frame>();
        List<Aligner.Result> ok = new ArrayList<Aligner.Result>();
        for (Aligner.Result r : results) {
            if (r.ok && r.R != null && r.unit != refUnit && r.mRef != null && r.mRef.length >= 10) ok.add(r);
        }
        if (ok.size() < 3) {
            out.note = "not enough aligned frames to refine the lens model";
            return out;
        }
        int maxFrames = 40, maxPairs = 150;
        double stepF = Math.max(1.0, ok.size() / (double) maxFrames);
        boolean[] used = new boolean[ref.count];
        for (double k = 0; k < ok.size(); k += stepF) {
            Aligner.Result r = ok.get((int) k);
            int n = r.mRef.length;
            int take = Math.min(n, maxPairs);
            Frame f = new Frame();
            f.ref = new int[take];
            f.x = new float[take];
            f.y = new float[take];
            for (int q = 0; q < take; q++) {
                int src = (int) ((long) q * n / take);
                f.ref[q] = r.mRef[src];
                f.x[q] = r.mX[src];
                f.y[q] = r.mY[src];
                used[r.mRef[src]] = true;
                out.pairs++;
            }
            frames.add(f);
        }
        out.frames = frames.size();
        boolean calibrated = m0.source != null && !m0.source.startsWith("nominal");
        final double[] sig = calibrated
                ? new double[]{0.02, 0.03, 0.03, 15, 15}
                : new double[]{0.06, 0.15, 0.15, 50, 50};
        Problem pb = new Problem(m0, ref, used, frames, sig);
        double[] z0 = new double[5];
        double c0 = pb.cost(z0);
        out.rmsBefore = pb.lastRms;
        double[] z = nelderMead(pb, z0, 0.6, 500, 1e-7);
        double c1 = pb.cost(z);
        out.rmsAfter = pb.lastRms;
        boolean sane = true;
        for (int i = 0; i < 5; i++) if (Math.abs(z[i]) > 4) sane = false;
        if (sane && c1 < c0 * 0.97 && out.rmsAfter < out.rmsBefore) {
            out.model = pb.model(z);
            out.model.source = m0.source + "+refined";
            out.changed = true;
        }
        out.note = String.format(Locale.US, "lens model %s: rms %.3f -> %.3f px over %d frames / %d pairs (%s)",
                out.changed ? "refined" : "kept", out.rmsBefore, out.rmsAfter, out.frames, out.pairs, out.model);
        return out;
    }

    private static final class Problem {
        final CameraModel m0;
        final StarList ref;
        final boolean[] used;
        final List<Frame> frames;
        final double[] sig;
        double lastRms;

        Problem(CameraModel m0, StarList ref, boolean[] used, List<Frame> frames, double[] sig) {
            this.m0 = m0;
            this.ref = ref;
            this.used = used;
            this.frames = frames;
            this.sig = sig;
        }

        CameraModel model(double[] z) {
            CameraModel m = m0.copy();
            double s = Math.exp(z[0] * sig[0]);
            m.fx = m0.fx * s;
            m.fy = m0.fy * s;
            m.k1 = m0.k1 + z[1] * sig[1];
            m.k2 = m0.k2 + z[2] * sig[2];
            m.cx = m0.cx + z[3] * sig[3];
            m.cy = m0.cy + z[4] * sig[4];
            return m;
        }

        double cost(double[] z) {
            CameraModel m = model(z);
            int nr = ref.count;
            double[] rb = new double[3 * nr];
            double[] b = new double[3], p = new double[2];
            for (int i = 0; i < nr; i++) {
                if (!used[i]) continue;
                m.unproject(ref.x[i], ref.y[i], b);
                rb[3 * i] = b[0];
                rb[3 * i + 1] = b[1];
                rb[3 * i + 2] = b[2];
            }
            double total = 0, ss = 0;
            int count = 0;
            for (Frame f : frames) {
                int n = f.ref.length;
                double[] A = new double[3 * n], B = new double[3 * n];
                for (int q = 0; q < n; q++) {
                    int i = f.ref[q];
                    A[3 * q] = rb[3 * i];
                    A[3 * q + 1] = rb[3 * i + 1];
                    A[3 * q + 2] = rb[3 * i + 2];
                    m.unproject(f.x[q], f.y[q], b);
                    B[3 * q] = b[0];
                    B[3 * q + 1] = b[1];
                    B[3 * q + 2] = b[2];
                }
                double[] R = Rot.solve(A, B, null, null, n);
                for (int q = 0; q < n; q++) {
                    Rot.apply(R, A[3 * q], A[3 * q + 1], A[3 * q + 2], b);
                    double r;
                    if (!m.project(b[0], b[1], b[2], p)) r = 10;
                    else r = Math.hypot(p[0] - f.x[q], p[1] - f.y[q]);
                    total += r <= HUBER ? r * r : 2 * HUBER * r - HUBER * HUBER;
                    ss += Math.min(r, 3) * Math.min(r, 3);
                    count++;
                }
            }
            lastRms = count > 0 ? Math.sqrt(ss / count) : 0;
            double prior = 0;
            for (double v : z) prior += v * v;
            return total / (SIGMA_PIX * SIGMA_PIX) + prior;
        }
    }

    /** Plain Nelder-Mead minimiser. */
    static double[] nelderMead(Problem f, double[] x0, double step, int maxEval, double tol) {
        int n = x0.length;
        double[][] s = new double[n + 1][];
        double[] fv = new double[n + 1];
        s[0] = x0.clone();
        fv[0] = f.cost(s[0]);
        for (int i = 0; i < n; i++) {
            s[i + 1] = x0.clone();
            s[i + 1][i] += step;
            fv[i + 1] = f.cost(s[i + 1]);
        }
        int evals = n + 1;
        while (evals < maxEval) {
            // order
            for (int i = 0; i < n + 1; i++) {
                for (int j = i + 1; j < n + 1; j++) {
                    if (fv[j] < fv[i]) {
                        double t = fv[i];
                        fv[i] = fv[j];
                        fv[j] = t;
                        double[] ts = s[i];
                        s[i] = s[j];
                        s[j] = ts;
                    }
                }
            }
            if (Math.abs(fv[n] - fv[0]) <= tol * (Math.abs(fv[0]) + 1e-12)) break;
            double[] c = new double[n];
            for (int i = 0; i < n; i++) for (int k = 0; k < n; k++) c[k] += s[i][k] / n;
            double[] xr = new double[n];
            for (int k = 0; k < n; k++) xr[k] = c[k] + (c[k] - s[n][k]);
            double fr = f.cost(xr);
            evals++;
            if (fr < fv[0]) {
                double[] xe = new double[n];
                for (int k = 0; k < n; k++) xe[k] = c[k] + 2 * (c[k] - s[n][k]);
                double fe = f.cost(xe);
                evals++;
                if (fe < fr) {
                    s[n] = xe;
                    fv[n] = fe;
                } else {
                    s[n] = xr;
                    fv[n] = fr;
                }
            } else if (fr < fv[n - 1]) {
                s[n] = xr;
                fv[n] = fr;
            } else {
                boolean outside = fr < fv[n];
                double[] xc = new double[n];
                for (int k = 0; k < n; k++) {
                    xc[k] = outside ? c[k] + 0.5 * (xr[k] - c[k]) : c[k] + 0.5 * (s[n][k] - c[k]);
                }
                double fc = f.cost(xc);
                evals++;
                if (fc < (outside ? fr : fv[n])) {
                    s[n] = xc;
                    fv[n] = fc;
                } else {
                    for (int i = 1; i < n + 1; i++) {
                        for (int k = 0; k < n; k++) s[i][k] = s[0][k] + 0.5 * (s[i][k] - s[0][k]);
                        fv[i] = f.cost(s[i]);
                        evals++;
                    }
                }
            }
        }
        int best = 0;
        for (int i = 1; i < n + 1; i++) if (fv[i] < fv[best]) best = i;
        return s[best];
    }
}
