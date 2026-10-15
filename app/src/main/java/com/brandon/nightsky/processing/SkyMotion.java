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
 * No single frame is trusted to start a stretch: a frame matched wrongly, or taken while the phone was still
 * settling, would set a wrong rate and make every later frame look wrong. The filter starts instead from
 * three frames that agree with a steady turn, tried from the start, the middle and the end of the stretch
 * (and around the reference frame), and keeps the start that sets the fewest frames aside. Which frames
 * are set aside and where stretches split is decided that way first; the path is then smoothed through the
 * rest.
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
        /** The frame every other was aligned to: a start that sets it aside is only taken if no other start fits. */
        public boolean reference;

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
    /** Measured frames searched for three that agree, from each place a search starts. */
    static final int TRIPLE_WINDOW = 6;
    /** Measurements set aside after the path is smoothed, at most, per stretch. */
    static final int RECHECKS = 5;

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
                // which frames to set aside, and where the stretch ends; then the path through the others
                Decision d = decide(frames, z, M, from, to, q, rateVar);
                // frames before a jump the chosen start saw looking back are a stretch of their own
                while (d.start > from) d = decide(frames, z, M, from, d.start - 1, q, rateVar);
                res.loosened += d.loosened;
                // each measurement was judged from one side only: one the whole path disagrees with goes too, if
                // the path without it fits its neighbours (otherwise the path bends, and they all stay)
                Pass p = new Pass(frames, z, M, from, d.end, d.q, rateVar, d.out);
                for (int round = 0; round < RECHECKS; round++) {
                    int w = p.worst(frames, z, M);
                    if (w < 0) break;
                    d.out[w - from] = true;
                    Pass without = new Pass(frames, z, M, from, d.end, d.q, rateVar, d.out);
                    if (!without.isolated(z, M, w)) {
                        d.out[w - from] = false;
                        break;
                    }
                    p = without;
                }
                p.write(frames, f, seg, res);
                seg++;
                from = d.end + 1;
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

    /** Where a stretch starts and ends, which of its frames are set aside, and the process noise that fits it. */
    private static final class Decision {
        int start, end;
        /** Set aside, from the first frame decided on (not {@link #start}) to {@link #end}. */
        boolean[] out;
        double q;
        int loosened;
    }

    /**
     * Decides frames [from, to]. From each start that three agreeing frames give (at the beginning, the middle
     * and the end of the stretch, and around the reference frame) the path is followed forward to the end and
     * back to the beginning ({@link Sweeps}), each start loosened while the alignments disagree with the path
     * more than their uncertainties allow. The start that sets the fewest frames aside wins, one that sets the
     * reference frame aside only if all do. Its stretch begins after {@code from} if it saw a jump looking back:
     * the frames before that are a stretch of their own.
     */
    private static Decision decide(List<Frame> frames, double[][] z, double[][] M, int from, int to, double q, double rateVar) {
        List<Integer> idx = new ArrayList<Integer>();
        int refPos = -1;
        for (int i = from; i <= to; i++) {
            if (z[i] == null) continue;
            if (frames.get(i).reference) refPos = idx.size();
            idx.add(i);
        }
        List<Prior> starts = new ArrayList<Prior>();
        int n = idx.size();
        if (n >= 3) {
            int[] at = {0, n / 2 - TRIPLE_WINDOW / 2, n - TRIPLE_WINDOW, refPos - 2};
            for (int k = 0; k < at.length; k++) {
                if (k == 3 && refPos < 0) continue;
                Prior pr = consistentTriple(frames, z, M, idx, Math.max(0, Math.min(n - 3, at[k])), q);
                if (pr == null) continue;
                boolean dup = false;
                for (Prior o : starts) dup |= java.util.Arrays.equals(o.frames, pr.frames);
                if (!dup) starts.add(pr);
            }
        }
        if (starts.isEmpty()) starts.add(null);
        Decision best = null;
        long bestScore = Long.MIN_VALUE;
        for (Prior pr : starts) {
            Sweeps s = null;
            double qq = q;
            int loosened = 0;
            for (int loosen = 0; loosen <= 3; loosen++) {
                s = new Sweeps(frames, z, M, from, to, qq, rateVar, pr);
                if (s.lo > from || s.hi < to || s.updates < 5 || s.nis / s.updates <= 2 * 3) break;
                if (loosen < 3) {
                    qq *= 10;
                    loosened++;
                }
            }
            int accepted = 0, gated = 0;
            boolean refOut = false;
            for (int i = s.lo; i <= s.hi; i++) {
                if (z[i] == null) continue;
                if (s.gated[i - from]) {
                    gated++;
                    refOut |= frames.get(i).reference;
                } else {
                    accepted++;
                }
            }
            // the reference set aside counts against a start above all else; then frames kept, then length
            long score = (refOut ? 0L : 1L << 40) + ((long) (accepted - gated + (1 << 20)) << 16) + (s.hi - s.lo);
            if (score > bestScore) {
                bestScore = score;
                best = new Decision();
                best.start = s.lo;
                best.end = s.hi;
                best.out = java.util.Arrays.copyOf(s.gated, s.hi - from + 1);
                best.q = qq;
                best.loosened = loosened;
            }
        }
        return best;
    }

    /** A start for a pass: the path's state (position, rate) at time t from three frames, and which they are. */
    private static final class Prior {
        double[] x, P;
        double t;
        int[] frames;

        boolean has(int i) {
            for (int f : frames) if (f == i) return true;
            return false;
        }
    }

    /**
     * The first three measured frames (searching from position {@code start} in idx, each within
     * {@link #TRIPLE_WINDOW} of the first) whose middle one lies where the other two put it, given their
     * uncertainties and how far the phone may drift between them; the path they give (weighted least squares
     * of position and rate) as a start. Null if no three agree.
     */
    private static Prior consistentTriple(List<Frame> frames, double[][] z, double[][] M, List<Integer> idx, int start, double q) {
        int n = idx.size();
        for (int a = start; a < n; a++) {
            for (int c = a + 2; c < Math.min(n, a + TRIPLE_WINDOW); c++) {
                for (int b = a + 1; b < c; b++) {
                    int i = idx.get(a), j = idx.get(b), k = idx.get(c);
                    double ti = frames.get(i).t, tj = frames.get(j).t, tk = frames.get(k).t;
                    double T = tk - ti;
                    if (!(T > 0)) continue;
                    double l = (tj - ti) / T, drift = q * T * T * T / 3;
                    double[] y = new double[3], S = new double[9];
                    for (int r = 0; r < 3; r++) {
                        y[r] = z[j][r] - ((1 - l) * z[i][r] + l * z[k][r]);
                        for (int e = 0; e < 3; e++) {
                            S[3 * r + e] = M[j][3 * r + e] + (1 - l) * (1 - l) * M[i][3 * r + e] + l * l * M[k][3 * r + e];
                        }
                        S[4 * r] += drift;
                    }
                    double[] Si = Rot.inverse(S);
                    if (Si == null || quad(Si, y) > GATE) continue;
                    return fitLine(frames, z, M, new int[]{i, j, k}, q);
                }
            }
        }
        return null;
    }

    /** Weighted least squares of position (at the first frame's time) and rate through the given frames. */
    private static Prior fitLine(List<Frame> frames, double[][] z, double[][] M, int[] fr, double q) {
        double t0 = frames.get(fr[0]).t, T = frames.get(fr[fr.length - 1]).t - t0;
        double drift = q * T * T * T / 3;
        double[] J = new double[36], b = new double[6];
        for (int m : fr) {
            double tau = frames.get(m).t - t0;
            double[] C = M[m].clone();
            for (int r = 0; r < 3; r++) C[4 * r] += drift;
            double[] Wm = Rot.inverse(C);
            if (Wm == null) return null;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    double w = Wm[3 * r + c];
                    J[6 * r + c] += w;
                    J[6 * r + c + 3] += tau * w;
                    J[6 * (r + 3) + c] += tau * w;
                    J[6 * (r + 3) + c + 3] += tau * tau * w;
                    b[r] += w * z[m][c];
                    b[r + 3] += tau * w * z[m][c];
                }
            }
        }
        double[] P = inverse6(J);
        if (P == null) return null;
        Prior p = new Prior();
        p.x = new double[6];
        for (int r = 0; r < 6; r++) for (int c = 0; c < 6; c++) p.x[r] += P[6 * r + c] * b[c];
        symmetrize(P);
        p.P = P;
        p.t = t0;
        p.frames = fr;
        return p;
    }

    /**
     * The deciding run from one start: forward from it to {@code to} and back from it to {@code from}, each
     * measurement checked against the path so far and set aside if it doesn't fit. [lo, hi] is the stretch it
     * saw: hi is the frame before a jump ahead, lo the frame after a jump behind.
     */
    private static final class Sweeps {
        /** Set aside, from {@code from}. */
        final boolean[] gated;
        int lo, hi, updates;
        double nis;

        Sweeps(List<Frame> frames, double[][] z, double[][] M, int from, int to, double q, double rateVar, Prior prior) {
            gated = new boolean[to - from + 1];
            lo = from;
            hi = to;
            int a = -1;
            double[] x, P;
            if (prior != null) {
                a = prior.frames[0];
                x = prior.x;
                P = prior.P;
            } else {
                // no three frames agree: the first measured one starts, with the sidereal rate as a loose prior
                for (int i = from; i <= to && a < 0; i++) if (z[i] != null) a = i;
                if (a < 0) return;
                x = new double[6];
                P = new double[36];
                for (int c = 0; c < 3; c++) {
                    x[c] = z[a][c];
                    for (int d = 0; d < 3; d++) P[6 * c + d] = M[a][3 * c + d];
                    P[6 * (c + 3) + c + 3] = rateVar;
                }
            }
            int ahead = sweep(frames, z, M, from, a, to, 1, x, P, q, prior);
            if (ahead >= 0) hi = ahead - 1;
            int behind = sweep(frames, z, M, from, a, from, -1, x, P, q, prior);
            if (behind >= 0) lo = behind + 1;
        }

        /**
         * From frame a (state x, P) to frame stop in direction dir. Returns the nearest frame beyond a jump
         * (measurements in a row that don't fit the path but fit each other, not just the first of them, which
         * may be a wrong match of its own), or -1.
         */
        private int sweep(List<Frame> frames, double[][] z, double[][] M, int from, int a, int stop, int dir, double[] x,
                          double[] P, double q, Prior prior) {
            double tPrev = frames.get(a).t;
            List<Integer> streak = new ArrayList<Integer>();
            for (int i = a + dir; dir > 0 ? i <= stop : i >= stop; i += dir) {
                if (z[i] == null) continue;
                double t = frames.get(i).t;
                x = predictState(x, t - tPrev);
                P = predictCov(P, t - tPrev, q);
                tPrev = t;
                if (prior != null && prior.has(i)) {
                    // its measurement is in the start already
                    streak.clear();
                    continue;
                }
                double[] y = new double[3], S = new double[9];
                for (int c = 0; c < 3; c++) {
                    y[c] = z[i][c] - x[c];
                    for (int d = 0; d < 3; d++) S[3 * c + d] = P[6 * c + d] + M[i][3 * c + d];
                }
                double[] Si = Rot.inverse(S);
                double d2 = Si == null ? Double.POSITIVE_INFINITY : quad(Si, y);
                if (d2 > GATE) {
                    gated[i - from] = true;
                    for (int e : streak) if (fitEachOther(z, M, e, i, x, P, t - frames.get(e).t, q)) return e;
                    streak.add(i);
                    continue;
                }
                streak.clear();
                double[][] xp = update(x, P, y, Si);
                x = xp[0];
                P = xp[1];
                updates++;
                nis += d2;
            }
            return -1;
        }
    }

    /** Kalman update with innovation y and its inverse covariance Si: {x, P}. */
    private static double[][] update(double[] x, double[] P, double[] y, double[] Si) {
        // K = P H' S^-1 (6 x 3); x += K y; P -= K H P
        double[] K = new double[18];
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 3; c++) {
                double v = 0;
                for (int d = 0; d < 3; d++) v += P[6 * r + d] * Si[3 * d + c];
                K[3 * r + c] = v;
            }
        }
        double[] xn = x.clone();
        for (int r = 0; r < 6; r++) for (int c = 0; c < 3; c++) xn[r] += K[3 * r + c] * y[c];
        double[] Pn = P.clone();
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 6; c++) {
                double v = 0;
                for (int d = 0; d < 3; d++) v += K[3 * r + d] * P[6 * d + c];
                Pn[6 * r + c] -= v;
            }
        }
        symmetrize(Pn);
        return new double[][]{xn, Pn};
    }

    /**
     * The path through frames [from, to] (Kalman filter forward, Rauch-Tung-Striebel smoother back), leaving
     * out the frames marked in out; frames without a measurement before the first one are extrapolated back.
     */
    private static final class Pass {
        final int from, to;
        final boolean[] gated, has;
        double[][] xsAll, PsAll;

        Pass(List<Frame> frames, double[][] z, double[][] M, int from, int to, double q, double rateVar, boolean[] out) {
            this.from = from;
            this.to = to;
            int m = to - from + 1;
            double[][] xf = new double[m][], Pf = new double[m][], xp = new double[m][], Pp = new double[m][];
            gated = new boolean[m];
            has = new boolean[m];
            double[] x = null, P = null;
            double tPrev = 0;
            int first = -1;
            for (int k = 0; k < m; k++) {
                int i = from + k;
                double t = frames.get(i).t;
                has[k] = z[i] != null && !out[k];
                gated[k] = z[i] != null && out[k];
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
                    xf[k] = x;
                    Pf[k] = P;
                    first = k;
                    tPrev = t;
                    continue;
                }
                double dt = t - tPrev;
                x = predictState(x, dt);
                P = predictCov(P, dt, q);
                tPrev = t;
                xp[k] = x.clone();
                Pp[k] = P.clone();
                if (has[k]) {
                    double[] y = new double[3], S = new double[9];
                    for (int c = 0; c < 3; c++) {
                        y[c] = z[i][c] - x[c];
                        for (int d = 0; d < 3; d++) S[3 * c + d] = P[6 * c + d] + M[i][3 * c + d];
                    }
                    double[] Si = Rot.inverse(S);
                    if (Si != null) {
                        double[][] u = update(x, P, y, Si);
                        x = u[0];
                        P = u[1];
                    }
                }
                xf[k] = x;
                Pf[k] = P;
            }
            if (first < 0) return;
            // backward pass over [first, end of span]: trailing frames without measurements are extrapolated
            int end = m - 1;
            xsAll = new double[m][];
            PsAll = new double[m][];
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
        }

        /**
         * The measurement that disagrees most with the path through all of them, if beyond {@link #GATE}: its
         * residual against its uncertainty less the path's (the path leans on it too). Not the reference frame,
         * nor one the path rests on almost alone. The frame's index, or -1.
         */
        int worst(List<Frame> frames, double[][] z, double[][] M) {
            int w = -1;
            double wd = GATE;
            for (int k = 0; k < has.length; k++) {
                if (frames.get(from + k).reference) continue;
                double d2 = fit(z, M, k);
                if (d2 > wd) {
                    wd = d2;
                    w = from + k;
                }
            }
            return w;
        }

        /**
         * True if frame i, left out of this path, doesn't fit it while its nearest measured neighbours on
         * either side do: a wrong match on its own, rather than a path that bends away from the model.
         */
        boolean isolated(double[][] z, double[][] M, int i) {
            int k = i - from;
            if (xsAll == null || xsAll[k] == null || z[i] == null) return false;
            double[] y = new double[3], S = new double[9];
            for (int c = 0; c < 3; c++) {
                y[c] = z[i][c] - xsAll[k][c];
                for (int d = 0; d < 3; d++) S[3 * c + d] = M[i][3 * c + d] + PsAll[k][6 * c + d];
            }
            double[] Si = Rot.inverse(S);
            if (Si == null || !(quad(Si, y) > GATE)) return false;
            for (int dir = -1; dir <= 1; dir += 2) {
                for (int j = k + dir; j >= 0 && j < has.length; j += dir) {
                    if (!has[j]) continue;
                    if (fit(z, M, j) > GATE / 2.5) return false;
                    break;
                }
            }
            return true;
        }

        /** How far measurement k (one the path uses) is from the path, in sigmas squared; NaN if it can't tell. */
        private double fit(double[][] z, double[][] M, int k) {
            int i = from + k;
            if (!has[k] || xsAll == null || xsAll[k] == null) return Double.NaN;
            double[] y = new double[3], C = new double[9];
            for (int c = 0; c < 3; c++) {
                y[c] = z[i][c] - xsAll[k][c];
                for (int d = 0; d < 3; d++) C[3 * c + d] = M[i][3 * c + d] - PsAll[k][6 * c + d];
                if (!(C[4 * c] > 0.1 * M[i][4 * c])) return Double.NaN;
            }
            double[] Ci = Rot.inverse(C);
            return Ci == null ? Double.NaN : quad(Ci, y);
        }

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
     * between them at the current rate estimate (state x, covariance P) and how far the phone may drift in
     * that time: a jump of the phone rather than two wrong matches.
     */
    private static boolean fitEachOther(double[][] z, double[][] M, int i, int j, double[] x, double[] P, double dt, double q) {
        double[] y = new double[3], S = new double[9];
        double a = Math.abs(dt), drift = q * a * a * a / 3;
        for (int c = 0; c < 3; c++) {
            y[c] = z[j][c] - z[i][c] - dt * x[c + 3];
            for (int d = 0; d < 3; d++) S[3 * c + d] = M[i][3 * c + d] + M[j][3 * c + d] + dt * dt * P[6 * (c + 3) + d + 3];
            S[4 * c] += drift;
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
