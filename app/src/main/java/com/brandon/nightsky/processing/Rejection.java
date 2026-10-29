package com.brandon.nightsky.processing;

import java.util.Arrays;
import java.util.Locale;

/**
 * Outlier rejection for one pixel's samples across the frames: satellites, planes, meteors, cosmic rays and
 * anything else that is in one or a few frames only. The method suits the number of frames, as Siril
 * recommends: percentile clipping for a handful, winsorized sigma clipping up to about 50, and the
 * generalized extreme studentized deviate test (GESD) above that. Linear-fit clipping takes over when the
 * sky background changed a lot during the session.
 * <p>
 * Samples are compared after normalisation and each one is measured against its own frame's noise, so a
 * noisier frame (thin cloud, brighter sky) isn't clipped more often than a clean one: the methods work on
 * standardised residuals {@code (v - centre) / s}, where {@code s} is the sample's noise.
 * <p>
 * Samples can be marked as not trusted (a frame used right next to where cloud cut it out, whose background
 * there is interpolated across the cloud's edge). When enough trusted samples remain, only they set the
 * centre and the limits, and the others are kept only if they fall within those limits; a sharp cloud edge
 * in most of the frames then can't drag the centre away from the frames that were clear.
 */
public final class Rejection {
    public enum Method {
        /** Chosen per pixel from the number of samples (the default). */
        AUTO("automatic"),
        PERCENTILE("percentile clipping"),
        WINSORIZED("winsorized sigma clipping"),
        GESD("generalized extreme studentized deviate test (GESD)"),
        LINEAR_FIT("linear-fit clipping"),
        /** Single-pass sigma clipping without keeping the samples (when memory is short). */
        SIGMA("sigma clipping");

        public final String label;

        Method(String label) {
            this.label = label;
        }

        public static Method parse(String s) {
            if (s != null) {
                for (Method m : values()) if (m.name().equalsIgnoreCase(s)) return m;
            }
            return AUTO;
        }
    }

    /** Up to this many frames, percentile clipping; up to {@link #WINSORIZED_MAX}, winsorized; above, GESD. */
    public static final int PERCENTILE_MAX = 6, WINSORIZED_MAX = 50;
    /** Fewest trusted samples that set the limits on their own; with fewer, every sample takes part. */
    public static final int MIN_TRUSTED = 3;
    /** Percentile clipping's default allowance, as a fraction of the starlight above the background. */
    public static final float PERCENTILE_FRACTION = 0.35f;

    /** The method the frame count calls for. */
    public static Method forFrames(int n) {
        if (n <= PERCENTILE_MAX) return Method.PERCENTILE;
        if (n <= WINSORIZED_MAX) return Method.WINSORIZED;
        return Method.GESD;
    }

    /** Clipping limits in sigmas (low side looser: dark outliers are rare and noise isn't). */
    public float kLow = 4f, kHigh = 3f;
    /** Percentile clipping: allowed deviation from the median, as a fraction of the starlight above the background. */
    public float percentileFraction = PERCENTILE_FRACTION;
    /** GESD: significance and the largest share of a pixel's samples that may be outliers (capped). */
    public double gesdAlpha = 0.05;
    public float gesdMaxFraction = 0.3f;
    public int gesdMaxOutliers = 12;
    /** Smallest spread of the standardised residuals the sigma methods will use. */
    public float sigmaFloor = 0.25f;

    private final int maxN;
    private final float[][] lambda;

    /** @param maxN the most samples a pixel can have (the frame count) */
    public Rejection(int maxN) {
        this.maxN = Math.max(1, maxN);
        lambda = new float[this.maxN + 1][];
    }

    /** Scratch arrays for one thread. */
    public static final class Work {
        public final float[] v, s, t, e, a;
        public final boolean[] keep;
        /** Whether a sample may help set the centre and limits; set by the caller (all true by default). */
        public final boolean[] trusted;
        /** Samples the last {@link #run} judged against the trusted ones' limits, and how many of those it rejected. */
        public int judged, judgedRejected;
        private final float[] bv, bs, bt;
        private final int[] map;
        // the limits of the last pass: centre lc + lb * t; residual (v - centre) / s - lm within
        // [-kLow ls, kHigh ls], or for percentile clipping (lp >= 0) |v - centre| within sqrt((k s)^2 + lp^2)
        private double lc, lb, lm, ls, lp;

        public Work(int n) {
            n = Math.max(n, 4);
            v = new float[n];
            s = new float[n];
            t = new float[n];
            e = new float[n];
            a = new float[n];
            keep = new boolean[n];
            trusted = new boolean[n];
            Arrays.fill(trusted, true);
            bv = new float[n];
            bs = new float[n];
            bt = new float[n];
            map = new int[n];
        }

        private void noLimits() {
            lc = 0;
            lb = 0;
            lm = 0;
            ls = Double.POSITIVE_INFINITY;
            lp = -1;
        }
    }

    /**
     * Decides which samples to keep. Inputs are in {@code w.v} (values), {@code w.s} (noise of each sample,
     * > 0) and, for linear-fit clipping, {@code w.t} (sample times); the verdicts go to {@code w.keep}.
     *
     * @param method AUTO picks by sample count; any other method is used when there are 7 or more samples
     * @param base   background level under this pixel (for percentile clipping's starlight allowance)
     * @return the number of samples rejected
     */
    public int run(Method method, int n, float base, Work w) {
        w.judged = 0;
        w.judgedRejected = 0;
        int nt = 0;
        for (int i = 0; i < n; i++) if (w.trusted[i]) nt++;
        if (nt == n || nt < MIN_TRUSTED) return runAll(method, n, base, w);
        // the trusted samples first, then the method on them alone
        System.arraycopy(w.v, 0, w.bv, 0, n);
        System.arraycopy(w.s, 0, w.bs, 0, n);
        System.arraycopy(w.t, 0, w.bt, 0, n);
        int k = 0;
        for (int i = 0; i < n; i++) if (w.trusted[i]) w.map[k++] = i;
        for (int i = 0; i < n; i++) if (!w.trusted[i]) w.map[k++] = i;
        for (int j = 0; j < n; j++) {
            int i = w.map[j];
            w.v[j] = w.bv[i];
            w.s[j] = w.bs[i];
            w.t[j] = w.bt[i];
        }
        int rej = runAll(method, nt, base, w);
        // keep[] in packed order: the trusted verdicts, then the others against the limits
        for (int j = nt; j < n; j++) {
            boolean in = admit(w.v[j], w.s[j], w.t[j], w);
            w.keep[j] = in;
            if (!in) rej++;
        }
        w.judged = n - nt;
        for (int j = nt; j < n; j++) if (!w.keep[j]) w.judgedRejected++;
        return unpack(n, rej, w);
    }

    /** Verdicts and samples back in the caller's order. */
    private static int unpack(int n, int rej, Work w) {
        for (int j = 0; j < n; j++) w.e[j] = w.keep[j] ? 1f : 0f;
        for (int j = 0; j < n; j++) {
            int i = w.map[j];
            w.keep[i] = w.e[j] != 0f;
        }
        System.arraycopy(w.bv, 0, w.v, 0, n);
        System.arraycopy(w.bs, 0, w.s, 0, n);
        System.arraycopy(w.bt, 0, w.t, 0, n);
        return rej;
    }

    /**
     * How far a sample lies from the last {@link #run}'s centre, in units of its noise times the spread the run
     * found there (never less than its noise alone, so a pixel quieter than its noise says doesn't count double):
     * NaN where the run found no spread (percentile clipping, or too few samples).
     */
    public static double standardized(float v, float s, float t, Work w) {
        return ((v - (w.lc + w.lb * t)) / s - w.lm) / spread(w);
    }

    /** The spread the last {@link #run} found, in units of the samples' noise and at least 1; NaN if none. */
    public static double spread(Work w) {
        if (w.lp >= 0 || !(w.ls < Double.POSITIVE_INFINITY)) return Double.NaN;
        return Math.max(1, w.ls);
    }

    /** Whether a sample falls within the limits of the last pass. */
    private boolean admit(float v, float s, float t, Work w) {
        double d = v - (w.lc + w.lb * t);
        if (w.lp >= 0) {
            double p2 = w.lp * w.lp;
            return d <= Math.sqrt(kHigh * kHigh * s * s + p2) && -d <= Math.sqrt(kLow * kLow * s * s + p2);
        }
        double e = d / s - w.lm;
        return e <= kHigh * w.ls && e >= -kLow * w.ls;
    }

    private int runAll(Method method, int n, float base, Work w) {
        Arrays.fill(w.keep, 0, n, true);
        w.noLimits();
        if (n < 3) return 0;
        Method m = method;
        if (m == Method.AUTO || m == Method.SIGMA) m = forFrames(n);
        else if (n <= PERCENTILE_MAX) m = Method.PERCENTILE;
        switch (m) {
            case PERCENTILE:
                return percentile(n, base, w);
            case GESD:
                return n >= 8 ? gesd(n, w) : winsorized(n, w);
            case LINEAR_FIT:
                // a line through fewer points is too easily pulled by the outlier itself
                return n >= 12 ? linearFit(n, w) : winsorized(n, w);
            default:
                return winsorized(n, w);
        }
    }

    /**
     * Percentile clipping, for a handful of frames where no spread can be estimated per pixel: a sample is
     * rejected when it is further from the median than a fixed share of the starlight there, plus the noise
     * allowance of its frame.
     */
    private int percentile(int n, float base, Work w) {
        System.arraycopy(w.v, 0, w.a, 0, n);
        float med = Stats.median(w.a, n);
        double sig = Math.max(0f, med - base) * percentileFraction;
        double sig2 = sig * sig;
        w.lc = med;
        w.lp = sig;
        int rej = 0;
        for (int i = 0; i < n; i++) {
            double d = w.v[i] - med;
            double si = w.s[i];
            double hi = Math.sqrt(kHigh * kHigh * si * si + sig2), lo = Math.sqrt(kLow * kLow * si * si + sig2);
            boolean k = d <= hi && -d <= lo;
            w.keep[i] = k;
            if (!k) rej++;
        }
        if (rej * 2 > n) {
            // most samples disagree with the median: nothing to tell outliers apart by, keep them all
            Arrays.fill(w.keep, 0, n, true);
            w.lp = Double.POSITIVE_INFINITY;
            return 0;
        }
        return rej;
    }

    /**
     * Winsorized sigma clipping (Siril's form): the spread comes from the samples with the extremes pulled
     * in to 1.5 sigma (so outliers can't inflate it), then samples beyond the limits go, and it repeats.
     */
    private int winsorized(int n, Work w) {
        boolean[] keep = w.keep;
        float[] v = w.v, s = w.s, e = w.e, a = w.a;
        int m = n, total = 0;
        for (int iter = 0; iter < 10 && m > 4; iter++) {
            int k = 0;
            for (int i = 0; i < n; i++) if (keep[i]) a[k++] = v[i];
            float c = Stats.median(a, k);
            k = 0;
            for (int i = 0; i < n; i++) if (keep[i]) e[k++] = (v[i] - c) / s[i];
            System.arraycopy(e, 0, a, 0, k);
            double sigma = std(a, k);
            w.lc = c;
            w.ls = sigmaFloor;
            if (!(sigma > 0)) break;
            for (int it = 0; it < 20; it++) {
                double s0 = sigma;
                float lo = (float) (-1.5 * sigma), hi = (float) (1.5 * sigma);
                for (int j = 0; j < k; j++) a[j] = a[j] < lo ? lo : (a[j] > hi ? hi : a[j]);
                sigma = 1.134 * std(a, k);
                if (!(sigma > 0) || Math.abs(sigma - s0) <= 5e-4 * s0) break;
            }
            sigma = Math.max(sigma, sigmaFloor);
            w.ls = sigma;
            int r = clip(n, keep, e, kHigh * sigma, -kLow * sigma, m);
            if (r == 0) break;
            m -= r;
            total += r;
        }
        return total;
    }

    /**
     * Linear-fit clipping: as winsorized clipping, but the centre is a straight line over time, fitted
     * robustly (residuals winsorized at 1.5 sigma and the line refitted until it settles), so a sky that
     * brightens or fades through the session (twilight, moonrise) isn't mistaken for outliers.
     */
    private int linearFit(int n, Work w) {
        boolean[] keep = w.keep;
        float[] v = w.v, s = w.s, t = w.t, e = w.e, a = w.a;
        int m = n, total = 0;
        for (int iter = 0; iter < 10 && m > 4; iter++) {
            int k = 0;
            for (int i = 0; i < n; i++) if (keep[i]) a[k++] = v[i];
            double fa = Stats.median(a, k), fb = 0;
            k = 0;
            for (int i = 0; i < n; i++) if (keep[i]) a[k++] = (float) Math.abs((v[i] - fa) / s[i]);
            double sigma = 1.4826 * Stats.median(a, k);
            if (!(sigma > 0)) sigma = sigmaFloor;
            for (int it = 0; it < 30; it++) {
                double sw = 0, st = 0, sv = 0, stt = 0, stv = 0, r2 = 0;
                double lim = 1.5 * sigma;
                for (int i = 0; i < n; i++) {
                    if (!keep[i]) continue;
                    double line = fa + fb * t[i];
                    double ei = (v[i] - line) / s[i];
                    double c = ei < -lim ? -lim : (ei > lim ? lim : ei);
                    double vw = line + c * s[i];
                    double wi = 1.0 / (s[i] * (double) s[i]);
                    sw += wi;
                    st += wi * t[i];
                    sv += wi * vw;
                    stt += wi * t[i] * t[i];
                    stv += wi * t[i] * vw;
                    r2 += c * c;
                }
                double s0 = sigma, a0 = fa, b0 = fb;
                sigma = Math.max(1e-6, 1.134 * Math.sqrt(r2 / Math.max(1, m - 2)));
                double den = sw * stt - st * st;
                fb = den > 1e-12 * sw * sw ? (sw * stv - st * sv) / den : 0;
                fa = (sv - fb * st) / sw;
                double tm = st / sw;
                double moved = Math.abs((fa + fb * tm) - (a0 + b0 * tm)) + Math.abs(fb - b0) * Math.sqrt(Math.max(0, stt / sw - tm * tm));
                if (Math.abs(sigma - s0) <= 5e-4 * s0 && moved <= 1e-3 * sigma * Math.sqrt(1 / sw)) break;
            }
            sigma = Math.max(sigma, sigmaFloor);
            w.lc = fa;
            w.lb = fb;
            w.ls = sigma;
            k = 0;
            for (int i = 0; i < n; i++) if (keep[i]) e[k++] = (float) ((v[i] - (fa + fb * t[i])) / s[i]);
            int r = clip(n, keep, e, kHigh * sigma, -kLow * sigma, m);
            if (r == 0) break;
            m -= r;
            total += r;
        }
        return total;
    }

    /** Rejects the kept samples whose residual {@code e} (packed, kept samples only) is outside [lo, hi], leaving at least 4. */
    private static int clip(int n, boolean[] keep, float[] e, double hi, double lo, int m) {
        int r = 0, j = 0;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            float ei = e[j++];
            if (m - r <= 4) continue;
            if (ei > hi || ei < lo) {
                keep[i] = false;
                r++;
            }
        }
        return r;
    }

    private static double std(float[] a, int k) {
        if (k < 2) return 0;
        double s = 0, s2 = 0;
        for (int i = 0; i < k; i++) {
            s += a[i];
            s2 += (double) a[i] * a[i];
        }
        double mean = s / k;
        return Math.sqrt(Math.max(0, (s2 - s * mean) / (k - 1)));
    }

    /**
     * Generalized extreme studentized deviate test (Rosner 1983): up to r candidates are taken out one at a
     * time, always the one furthest from the mean of those left; the outliers are the first i candidates
     * for the largest i whose deviation beats the critical value lambda_i.
     */
    private int gesd(int n, Work w) {
        float[] v = w.v, s = w.s, e = w.e, a = w.a;
        System.arraycopy(v, 0, a, 0, n);
        float c = Stats.median(a, n);
        double s1 = 0, s2 = 0;
        for (int i = 0; i < n; i++) {
            float ei = (v[i] - c) / s[i];
            e[i] = ei;
            a[i] = ei;
            s1 += ei;
            s2 += (double) ei * ei;
        }
        int r = Math.min(gesdMaxOutliers, (int) (gesdMaxFraction * n));
        if (r < 1) {
            gesdLimits(n, c, e, -Float.MAX_VALUE, Float.MAX_VALUE, w);
            return 0;
        }
        // the r smallest at a[0..r-1] and the r largest at a[n-r..n-1], each sorted
        Stats.select(a, 0, n - 1, r);
        Stats.select(a, r, n - 1, n - 1 - r);
        Arrays.sort(a, 0, r);
        Arrays.sort(a, n - r, n);
        float[] lam = lambdas(n);
        int lo = 0, hi = n - 1, m = n, nOut = 0, nLo = 0, nHi = 0, cLo = 0, cHi = 0;
        for (int i = 1; i <= r; i++) {
            double mean = s1 / m;
            double var = (s2 - s1 * mean) / (m - 1);
            double sd = Math.max(sigmaFloor, Math.sqrt(Math.max(0, var)));
            double dl = mean - a[lo], dh = a[hi] - mean;
            double x;
            if (dh >= dl) {
                x = a[hi--];
                cHi++;
            } else {
                x = a[lo++];
                cLo++;
            }
            if (Math.max(dl, dh) / sd > lam[i]) {
                nOut = i;
                nLo = cLo;
                nHi = cHi;
            }
            s1 -= x;
            s2 -= x * x;
            m--;
        }
        float loThr = nLo > 0 ? a[nLo] : -Float.MAX_VALUE;
        float hiThr = nHi > 0 ? a[n - 1 - nHi] : Float.MAX_VALUE;
        gesdLimits(n, c, e, loThr, hiThr, w);
        if (nOut == 0) return 0;
        int rej = 0;
        for (int i = 0; i < n; i++) {
            if (e[i] < loThr || e[i] > hiThr) {
                w.keep[i] = false;
                rej++;
            }
        }
        return rej;
    }

    /** GESD's limits for samples judged afterwards: the mean and spread of the residuals it keeps. */
    private void gesdLimits(int n, float c, float[] e, float lo, float hi, Work w) {
        double s1 = 0, s2 = 0;
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (e[i] < lo || e[i] > hi) continue;
            s1 += e[i];
            s2 += (double) e[i] * e[i];
            k++;
        }
        w.lc = c;
        w.lm = k > 0 ? s1 / k : 0;
        w.ls = k > 1 ? Math.max(sigmaFloor, Math.sqrt(Math.max(0, (s2 - s1 * w.lm) / (k - 1)))) : Double.POSITIVE_INFINITY;
    }

    /**
     * Works out what {@code method} will need for up to {@link #maxN} samples (GESD's critical values).
     * Call once before using this object from several threads.
     */
    public void prepare(Method method) {
        boolean gesd = method == Method.GESD || ((method == Method.AUTO || method == Method.SIGMA) && maxN > WINSORIZED_MAX);
        if (!gesd) return;
        int from = method == Method.GESD ? 8 : WINSORIZED_MAX + 1;
        for (int n = from; n <= maxN; n++) lambda[n] = computeLambdas(n);
    }

    /** GESD critical values lambda_1..lambda_r for n samples (index 0 unused). */
    private float[] lambdas(int n) {
        float[] l = n <= maxN ? lambda[n] : null;
        return l != null ? l : computeLambdas(n);
    }

    private float[] computeLambdas(int n) {
        int r = Math.min(gesdMaxOutliers, (int) (gesdMaxFraction * n));
        float[] l = new float[r + 1];
        for (int i = 1; i <= r; i++) l[i] = (float) gesdLambda(n, i, gesdAlpha);
        return l;
    }

    /** lambda_i = (n - i) t / sqrt((n - i - 1 + t^2)(n - i + 1)), t = t-quantile at 1 - alpha / (2 (n - i + 1)), n - i - 1 dof. */
    public static double gesdLambda(int n, int i, double alpha) {
        double p = 1 - alpha / (2.0 * (n - i + 1));
        double nu = n - i - 1;
        double t = tQuantile(p, nu);
        return (n - i) * t / Math.sqrt((nu + t * t) * (n - i + 1));
    }

    // ------------------------------------------------------------------------------------------------
    // Student's t distribution

    /** Quantile of Student's t for p > 0.5 and nu degrees of freedom. */
    public static double tQuantile(double p, double nu) {
        if (p <= 0.5) return p == 0.5 ? 0 : -tQuantile(1 - p, nu);
        double lo = 0, hi = 2;
        while (tCdf(hi, nu) < p && hi < 1e7) hi *= 2;
        for (int it = 0; it < 100; it++) {
            double mid = 0.5 * (lo + hi);
            if (tCdf(mid, nu) < p) lo = mid;
            else hi = mid;
            if (hi - lo < 1e-12 * hi) break;
        }
        return 0.5 * (lo + hi);
    }

    public static double tCdf(double t, double nu) {
        double x = nu / (nu + t * t);
        double tail = 0.5 * betaRegularized(0.5 * nu, 0.5, x);
        return t > 0 ? 1 - tail : tail;
    }

    /** Regularized incomplete beta function I_x(a, b) (continued fraction, Numerical Recipes). */
    static double betaRegularized(double a, double b, double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        double bt = Math.exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * Math.log(x) + b * Math.log(1 - x));
        if (x < (a + 1) / (a + b + 2)) return bt * betaCf(a, b, x) / a;
        return 1 - bt * betaCf(b, a, 1 - x) / b;
    }

    private static double betaCf(double a, double b, double x) {
        final double fpmin = 1e-300, eps = 1e-15;
        double qab = a + b, qap = a + 1, qam = a - 1;
        double c = 1, d = 1 - qab * x / qap;
        if (Math.abs(d) < fpmin) d = fpmin;
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 300; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < fpmin) d = fpmin;
            c = 1 + aa / c;
            if (Math.abs(c) < fpmin) c = fpmin;
            d = 1 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1 + aa * d;
            if (Math.abs(d) < fpmin) d = fpmin;
            c = 1 + aa / c;
            if (Math.abs(c) < fpmin) c = fpmin;
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < eps) break;
        }
        return h;
    }

    private static final double[] LANCZOS = {0.99999999999980993, 676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059, 12.507343278686905, -0.13857109526572012,
            9.9843695780195716e-6, 1.5056327351493116e-7};

    static double lnGamma(double x) {
        if (x < 0.5) return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lnGamma(1 - x);
        x -= 1;
        double a = LANCZOS[0];
        double t = x + 7.5;
        for (int i = 1; i < 9; i++) a += LANCZOS[i] / (x + i);
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** One line for the log: the method and its settings. */
    public String describe(Method m) {
        switch (m) {
            case PERCENTILE:
                return String.format(Locale.US, "%s (median +- %.0f%% of the starlight + %.0f/%.0f sigma of the noise)",
                        m.label, 100 * percentileFraction, kLow, kHigh);
            case GESD:
                return String.format(Locale.US, "%s (alpha %.2f, up to %d outliers per pixel)", m.label, gesdAlpha, gesdMaxOutliers);
            case WINSORIZED:
            case LINEAR_FIT:
            case SIGMA:
                return String.format(Locale.US, "%s (%.0f sigma low, %.0f high)", m.label, kLow, kHigh);
            default:
                return m.label;
        }
    }
}
