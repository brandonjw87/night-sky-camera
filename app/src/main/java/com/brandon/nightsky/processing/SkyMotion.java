package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.List;

/**
 * The sky turns at a steady rate and the phone lies still, so the frames' rotations follow a smooth path in
 * time. A Kalman filter with a Rauch-Tung-Striebel smoother runs along that path (the rotation vector and its
 * rate, scaled to pixels), taking each frame's star alignment with the uncertainty its stars give it. A frame
 * that matched few stars (haze, thin cloud, a sparse field) leans on its neighbours, one that matched none is
 * placed between them, and an alignment that doesn't fit its neighbours at all (a wrong match) is set aside.
 * A bump the gyro saw, or a jump that two frames in a row agree on, starts a new stretch: the path is only
 * smooth while the phone stays put.
 * <p>
 * How far the phone may wander on its own sets the process noise: {@link #DRIFT_RAD} over {@link #DRIFT_TIME}
 * seconds. If the alignments disagree with that more than their uncertainties allow, the smoother loosens
 * (ten times the process noise, up to three times), so a creeping phone is followed rather than smoothed over.
 */
public final class SkyMotion {
    /** One frame: inputs, then what the smoother made of it. */
    public static final class Frame {
        public double t;
        /** Measured rotation (reference bearings to this frame's), or null. */
        public double[] R;
        /** Its covariance (see {@link Aligner.Result#cov}), or null for a default. */
        public double[] cov;
        /** Moved during the exposure (gyro): not used, and a new stretch starts after it. */
        public boolean disturbed;

        /** Smoothed rotation, or null where the frame couldn't be placed. */
        public double[] smoothed;
        /** Uncertainty of the smoothed rotation in pixels at the image centre (one sigma, worst direction). */
        public double sigmaPx = Double.NaN;
        /** The measurement didn't fit its neighbours and was set aside. */
        public boolean outlier;
        /** Placed from the neighbours alone (no measurement, or an outlier). */
        public boolean predicted;
        /** Which stretch of steady pointing the frame is in (-1: none). */
        public int segment = -1;
    }

    /** Summary for the log. */
    public static final class Result {
        public int segments, measured, outliers, placed, loosened;
        /** Largest and median shift (pixels at the image centre) between measured and smoothed rotations. */
        public double maxShift, medianShift;
    }

    /** The phone may drift this far (radians) ... */
    static final double DRIFT_RAD = 5e-5;
    /** ... over this many seconds, beyond a steady rate, before the smoother counts it as unlikely. */
    static final double DRIFT_TIME = 60;
    /** Sidereal rate (rad/s), for the prior on the rate. */
    static final double SIDEREAL = 7.2921159e-5;
    /** Innovation (squared, in sigmas, 3 degrees of freedom) beyond which a measurement doesn't fit. */
    static final double GATE = 25;
    /** Position uncertainty (pixels) assumed for a measurement that came without a covariance. */
    static final double DEFAULT_SIGMA_PX = 0.5;
    /** Smallest uncertainty (pixels) of any measurement, so the frame that defines the coordinates isn't singular. */
    static final double MIN_SIGMA_PX = 0.002;

    private SkyMotion() {}

    public static Result smooth(List<Frame> frames, double focalPx) {
        Result res = new Result();
        int n = frames.size();
        double f = Math.max(1, focalPx);
        // measurements in pixels: z = f log(R), covariance mapped from a left perturbation to log coordinates
        double[][] z = new double[n][], M = new double[n][];
        for (int i = 0; i < n; i++) {
            Frame fr = frames.get(i);
            fr.smoothed = null;
            fr.outlier = false;
            fr.predicted = false;
            fr.segment = -1;
            fr.sigmaPx = Double.NaN;
            if (fr.R == null || fr.disturbed) continue;
            double[] v = Rot.toRotVec(fr.R);
            z[i] = new double[]{v[0] * f, v[1] * f, v[2] * f};
            double[] C = new double[9];
            if (fr.cov != null) {
                double[] Ji = leftJacobianInverse(v);
                double[] t = mul3(mul3(Ji, fr.cov), transpose3(Ji));
                for (int k = 0; k < 9; k++) C[k] = t[k] * f * f;
            } else {
                for (int k = 0; k < 3; k++) C[4 * k] = DEFAULT_SIGMA_PX * DEFAULT_SIGMA_PX;
            }
            for (int k = 0; k < 3; k++) C[4 * k] += MIN_SIGMA_PX * MIN_SIGMA_PX;
            M[i] = C;
            res.measured++;
        }
        double q = 3 * (DRIFT_RAD * f) * (DRIFT_RAD * f) / (DRIFT_TIME * DRIFT_TIME * DRIFT_TIME);
        double rateVar = (2 * SIDEREAL * f) * (2 * SIDEREAL * f);
        // stretches between bumps
        List<int[]> spans = new ArrayList<int[]>();
        int a = -1;
        for (int i = 0; i <= n; i++) {
            boolean brk = i == n || frames.get(i).disturbed;
            if (brk) {
                if (a >= 0) spans.add(new int[]{a, i - 1});
                a = -1;
            } else if (a < 0) {
                a = i;
            }
        }
        int seg = 0;
        for (int s = 0; s < spans.size(); s++) {
            int from = spans.get(s)[0], to = spans.get(s)[1];
            while (from <= to) {
                // loosen the path until the alignments fit it as well as their uncertainties say they should
                Pass p = null;
                double qq = q;
                for (int loosen = 0; loosen <= 3; loosen++) {
                    p = new Pass(frames, z, M, from, to, qq, rateVar);
                    if (p.split >= 0 || p.updates < 5 || p.nis / p.updates <= 2 * 3) break;
                    if (loosen < 3) {
                        qq *= 10;
                        res.loosened++;
                    }
                }
                int end = p.split >= 0 ? p.split - 1 : to;
                if (p.split >= 0) p = new Pass(frames, z, M, from, end, qq, rateVar);
                p.write(frames, f, seg, res);
                seg++;
                from = end + 1;
            }
        }
        res.segments = seg;
        double[] shifts = new double[n];
        int ns = 0;
        for (int i = 0; i < n; i++) {
            Frame fr = frames.get(i);
            if (fr.outlier) res.outliers++;
            if (fr.predicted && fr.smoothed != null) res.placed++;
            if (fr.R != null && fr.smoothed != null && !fr.outlier) {
                double d = Rot.angle(Rot.mul(fr.smoothed, Rot.transpose(fr.R))) * f;
                shifts[ns++] = d;
                res.maxShift = Math.max(res.maxShift, d);
            }
        }
        if (ns > 0) {
            java.util.Arrays.sort(shifts, 0, ns);
            res.medianShift = shifts[ns / 2];
        }
        return res;
    }

    /** One forward-backward run over frames [from, to]. */
    private static final class Pass {
        final int from, to;
        final double q;
        /** First frame of a jump (two measurements in a row that don't fit, but fit each other), or -1. */
        int split = -1;
        int updates;
        double nis;
        final double[][] xf, Pf, xp, Pp;
        final boolean[] gated, has;
        int first = -1;

        Pass(List<Frame> frames, double[][] z, double[][] M, int from, int to, double q, double rateVar) {
            this.from = from;
            this.to = to;
            this.q = q;
            int m = to - from + 1;
            xf = new double[m][];
            Pf = new double[m][];
            xp = new double[m][];
            Pp = new double[m][];
            gated = new boolean[m];
            has = new boolean[m];
            double[] x = null, P = null;
            double tPrev = 0;
            int streak = 0, streakStart = -1;
            for (int k = 0; k < m; k++) {
                int i = from + k;
                double t = frames.get(i).t;
                has[k] = z[i] != null;
                if (x == null) {
                    if (!has[k]) continue;
                    x = new double[6];
                    P = new double[36];
                    for (int c = 0; c < 3; c++) {
                        x[c] = z[i][c];
                        for (int d = 0; d < 3; d++) P[6 * c + d] = M[i][3 * c + d];
                        P[6 * (c + 3) + c + 3] = rateVar;
                    }
                    xp[k] = x.clone();
                    Pp[k] = P.clone();
                    xf[k] = x.clone();
                    Pf[k] = P.clone();
                    first = k;
                    tPrev = t;
                    continue;
                }
                double dt = t - tPrev;
                tPrev = t;
                double[] xpk = predictState(x, dt);
                double[] Ppk = predictCov(P, dt, q);
                xp[k] = xpk;
                Pp[k] = Ppk;
                x = xpk;
                P = Ppk;
                if (has[k]) {
                    double[] y = new double[3], S = new double[9];
                    for (int c = 0; c < 3; c++) {
                        y[c] = z[i][c] - xpk[c];
                        for (int d = 0; d < 3; d++) S[3 * c + d] = Ppk[6 * c + d] + M[i][3 * c + d];
                    }
                    double[] Si = Rot.inverse(S);
                    double d2 = Si == null ? Double.POSITIVE_INFINITY : quad(Si, y);
                    if (d2 > GATE) {
                        gated[k] = true;
                        if (streak == 0) streakStart = k;
                        streak++;
                        if (streak >= 2 && fitEachOther(z, M, from + streakStart, i, x, P,
                                t - frames.get(from + streakStart).t)) {
                            split = from + streakStart;
                            break;
                        }
                    } else {
                        streak = 0;
                        // K = P H' S^-1 (6 x 3); x += K y; P -= K H P
                        double[] K = new double[18];
                        for (int r = 0; r < 6; r++) {
                            for (int c = 0; c < 3; c++) {
                                double v = 0;
                                for (int d = 0; d < 3; d++) v += Ppk[6 * r + d] * Si[3 * d + c];
                                K[3 * r + c] = v;
                            }
                        }
                        double[] xn = xpk.clone();
                        for (int r = 0; r < 6; r++) for (int c = 0; c < 3; c++) xn[r] += K[3 * r + c] * y[c];
                        double[] Pn = Ppk.clone();
                        for (int r = 0; r < 6; r++) {
                            for (int c = 0; c < 6; c++) {
                                double v = 0;
                                for (int d = 0; d < 3; d++) v += K[3 * r + d] * Ppk[6 * d + c];
                                Pn[6 * r + c] -= v;
                            }
                        }
                        symmetrize(Pn);
                        x = xn;
                        P = Pn;
                        updates++;
                        nis += d2;
                    }
                }
                xf[k] = x;
                Pf[k] = P;
            }
            if (split >= 0 || first < 0) return;
            // backward pass over [first, end of span]: trailing frames without measurements are extrapolated
            int end = m - 1;
            double[][] xsAll = new double[m][], PsAll = new double[m][];
            xsAll[end] = xf[end];
            PsAll[end] = Pf[end];
            for (int k = end - 1; k >= first; k--) {
                double dt = frames.get(from + k + 1).t - frames.get(from + k).t;
                double[] F = transition(dt);
                double[] Ppi = inverse6(Pp[k + 1]);
                if (Ppi == null) {
                    xsAll[k] = xf[k];
                    PsAll[k] = Pf[k];
                    continue;
                }
                double[] G = mul6(mul6(Pf[k], transpose6(F)), Ppi);
                double[] dx = new double[6];
                for (int r = 0; r < 6; r++) dx[r] = xsAll[k + 1][r] - xp[k + 1][r];
                double[] xn = xf[k].clone();
                for (int r = 0; r < 6; r++) for (int c = 0; c < 6; c++) xn[r] += G[6 * r + c] * dx[c];
                double[] dP = new double[36];
                for (int r = 0; r < 36; r++) dP[r] = PsAll[k + 1][r] - Pp[k + 1][r];
                double[] Pn = add6(Pf[k], mul6(mul6(G, dP), transpose6(G)));
                symmetrize(Pn);
                xsAll[k] = xn;
                PsAll[k] = Pn;
            }
            // leading frames without measurements: extrapolated back from the first smoothed state
            for (int k = first - 1; k >= 0; k--) {
                double dt = frames.get(from + k).t - frames.get(from + first).t;
                xsAll[k] = predictState(xsAll[first], dt);
                PsAll[k] = predictCov(PsAll[first], dt, q);
            }
            this.xsAll = xsAll;
            this.PsAll = PsAll;
        }

        double[][] xsAll, PsAll;

        void write(List<Frame> frames, double f, int seg, Result res) {
            if (xsAll == null) return;
            for (int k = 0; k < xsAll.length; k++) {
                if (xsAll[k] == null) continue;
                Frame fr = frames.get(from + k);
                double[] x = xsAll[k];
                fr.smoothed = Rot.fromRotVec(x[0] / f, x[1] / f, x[2] / f);
                fr.segment = seg;
                fr.outlier = gated[k];
                fr.predicted = !has[k] || gated[k];
                double[] P3 = new double[9];
                for (int c = 0; c < 3; c++) for (int d = 0; d < 3; d++) P3[3 * c + d] = PsAll[k][6 * c + d];
                fr.sigmaPx = Math.sqrt(Math.max(0, maxEigen3(P3)));
            }
        }
    }

    /**
     * True if measurements i and j (both set, j later by dt) agree with each other, given the sky's turn
     * between them at the current rate estimate (state x, covariance P): a jump of the phone rather than two
     * wrong matches.
     */
    private static boolean fitEachOther(double[][] z, double[][] M, int i, int j, double[] x, double[] P, double dt) {
        double[] y = new double[3], S = new double[9];
        for (int c = 0; c < 3; c++) {
            y[c] = z[j][c] - z[i][c] - dt * x[c + 3];
            for (int d = 0; d < 3; d++) S[3 * c + d] = M[i][3 * c + d] + M[j][3 * c + d] + dt * dt * P[6 * (c + 3) + d + 3];
        }
        double[] Si = Rot.inverse(S);
        return Si != null && quad(Si, y) <= GATE;
    }

    // ------------------------------------------------------------------------------------------------
    // Integrated random walk: position (3) and rate (3)

    private static double[] transition(double dt) {
        double[] F = new double[36];
        for (int i = 0; i < 6; i++) F[7 * i] = 1;
        for (int i = 0; i < 3; i++) F[6 * i + i + 3] = dt;
        return F;
    }

    private static double[] predictState(double[] x, double dt) {
        double[] y = x.clone();
        for (int i = 0; i < 3; i++) y[i] += dt * x[i + 3];
        return y;
    }

    private static double[] predictCov(double[] P, double dt, double q) {
        double[] F = transition(dt);
        double[] t = mul6(mul6(F, P), transpose6(F));
        double a = Math.abs(dt);
        for (int i = 0; i < 3; i++) {
            t[6 * i + i] += q * a * a * a / 3;
            t[6 * i + i + 3] += q * a * a / 2 * Math.signum(dt);
            t[6 * (i + 3) + i] += q * a * a / 2 * Math.signum(dt);
            t[6 * (i + 3) + i + 3] += q * a;
        }
        return t;
    }

    // ------------------------------------------------------------------------------------------------
    // Small dense matrices (row-major)

    private static double[] mul6(double[] a, double[] b) {
        double[] c = new double[36];
        for (int i = 0; i < 6; i++) {
            for (int k = 0; k < 6; k++) {
                double v = a[6 * i + k];
                if (v == 0) continue;
                for (int j = 0; j < 6; j++) c[6 * i + j] += v * b[6 * k + j];
            }
        }
        return c;
    }

    private static double[] add6(double[] a, double[] b) {
        double[] c = new double[36];
        for (int i = 0; i < 36; i++) c[i] = a[i] + b[i];
        return c;
    }

    private static double[] transpose6(double[] a) {
        double[] t = new double[36];
        for (int i = 0; i < 6; i++) for (int j = 0; j < 6; j++) t[6 * j + i] = a[6 * i + j];
        return t;
    }

    private static void symmetrize(double[] P) {
        for (int i = 0; i < 6; i++) {
            for (int j = i + 1; j < 6; j++) {
                double v = 0.5 * (P[6 * i + j] + P[6 * j + i]);
                P[6 * i + j] = v;
                P[6 * j + i] = v;
            }
        }
    }

    /** Gauss-Jordan inverse with partial pivoting, or null if singular. */
    static double[] inverse6(double[] m) {
        int n = 6;
        double[] a = m.clone(), inv = new double[36];
        for (int i = 0; i < n; i++) inv[7 * i] = 1;
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(a[6 * r + c]) > Math.abs(a[6 * p + c])) p = r;
            double pv = a[6 * p + c];
            if (!(Math.abs(pv) > 1e-300)) return null;
            if (p != c) {
                for (int j = 0; j < n; j++) {
                    double t = a[6 * c + j];
                    a[6 * c + j] = a[6 * p + j];
                    a[6 * p + j] = t;
                    t = inv[6 * c + j];
                    inv[6 * c + j] = inv[6 * p + j];
                    inv[6 * p + j] = t;
                }
            }
            double s = 1 / pv;
            for (int j = 0; j < n; j++) {
                a[6 * c + j] *= s;
                inv[6 * c + j] *= s;
            }
            for (int r = 0; r < n; r++) {
                if (r == c) continue;
                double g = a[6 * r + c];
                if (g == 0) continue;
                for (int j = 0; j < n; j++) {
                    a[6 * r + j] -= g * a[6 * c + j];
                    inv[6 * r + j] -= g * inv[6 * c + j];
                }
            }
        }
        return inv;
    }

    private static double[] mul3(double[] a, double[] b) {
        double[] c = new double[9];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) c[3 * i + j] = a[3 * i] * b[j] + a[3 * i + 1] * b[3 + j] + a[3 * i + 2] * b[6 + j];
        return c;
    }

    private static double[] transpose3(double[] a) {
        return new double[]{a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8]};
    }

    private static double quad(double[] Si, double[] y) {
        double s = 0;
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) s += y[i] * Si[3 * i + j] * y[j];
        return s;
    }

    /** Largest eigenvalue of a symmetric 3 x 3 matrix. */
    static double maxEigen3(double[] A) {
        double[][] a = {{A[0], A[1], A[2]}, {A[3], A[4], A[5]}, {A[6], A[7], A[8]}};
        double[] d = new double[3];
        double[][] v = new double[3][3];
        Rot.jacobi(a, d, v);
        return Math.max(d[0], Math.max(d[1], d[2]));
    }

    /**
     * Inverse of the left Jacobian of SO(3) at rotation vector v: maps a small rotation applied after exp(v)
     * to the change of v.
     */
    static double[] leftJacobianInverse(double[] v) {
        double th = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        double[] K = {0, -v[2], v[1], v[2], 0, -v[0], -v[1], v[0], 0};
        double[] K2 = mul3(K, K);
        double c2 = th < 1e-4 ? 1.0 / 12 : (1 / (th * th) - (1 + Math.cos(th)) / (2 * th * Math.sin(th)));
        double[] J = new double[9];
        for (int i = 0; i < 9; i++) J[i] = (i % 4 == 0 ? 1 : 0) - 0.5 * K[i] + c2 * K2[i];
        return J;
    }
}
