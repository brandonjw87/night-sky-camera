package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Photometric colour calibration (F4): the white balance from the stars themselves. Every catalogue star the plate
 * solution matched that is measured well and isn't clipped is a colour reference: its B-V colour index gives its
 * temperature (Ballesteros' formula), and the temperature its colour as a blackbody (the Kim et al. Planckian locus,
 * as for the fixed balance), with a Sun-like star (B-V 0.65, 5778 K) as white. Gains for red and blue over green,
 * on top of the fixed balance, are fitted so that the measured colours match: robustly (a weighted median, then a
 * biweight), in linear RGB, after each star's own background has been taken off. With fewer than
 * {@link #MIN_STARS} good stars, or gains or a scatter that don't make sense, the fixed balance stays.
 */
public final class ColorCalibration {
    private ColorCalibration() {}

    /** Good stars needed to use the stars' white balance. */
    public static final int MIN_STARS = 15;
    /** A Sun-like (G2V) star's colour index: the white. */
    public static final double SUN_BV = 0.65;
    /** White: the Sun-like star's temperature by Ballesteros' formula (5778 K). */
    public static final double WHITE_K = kelvin(SUN_BV);
    /** Colour indices the formula holds for (hotter and cooler stars are left out). */
    static final double BV_MIN = -0.35, BV_MAX = 2.0;
    /** Signal to noise each channel needs in a star's circle. */
    static final double MIN_SNR = 12;
    /** How far real stars' colours stray from a blackbody's at their B-V temperature (natural log). */
    static final double SIGMA_STAR = 0.04;
    /** Largest gain believed (beyond it the matches or the sky are suspect): about 1.5 either way. */
    static final double MAX_LN_GAIN = 0.41;
    /** Largest scatter of the stars about the fit believed (natural log). */
    static final double MAX_SCATTER = 0.25;
    /** Largest uncertainty of a gain that's used: twice it is the 3% the calibration must be good to. */
    static final double MAX_ERR = 0.015;
    /**
     * Largest scatter of the stars about the fit over what their noise explains: beyond it something else colours
     * them (a stack of very few frames, say, whose red and blue samples are too sparse for the stars' cores).
     */
    static final double MAX_EXCESS = 3;
    private static final double KR = 0.2126, KG = 0.7152, KB = 0.0722;

    /** Colour temperature of a star of colour index B-V (Ballesteros 2012). */
    public static double kelvin(double bv) {
        return 4600 * (1 / (0.92 * bv + 1.7) + 1 / (0.92 * bv + 0.62));
    }

    /** Linear RGB of a blackbody at {@code t} kelvin with {@link #WHITE_K} as white, as ratios to green. */
    public static double[] expected(double t) {
        double[] v = ColorMath.blackbodyRgb(t, WHITE_K);
        return new double[]{v[0] / v[1], 1, v[2] / v[1]};
    }

    /** One catalogue star as a colour reference. */
    public static final class Star {
        public int cat = -1;
        public String label = "";
        public double x, y, vmag;
        public double bv = Double.NaN, t = Double.NaN;
        /** Its light in linear RGB under the fixed balance, and that light's noise. */
        public double r, g, b, sr, sg, sb;
        /** ln(expected / measured) of red over green and of blue over green, and their weights. */
        public double dr = Double.NaN, db = Double.NaN, wr, wb;
        /** Why it wasn't used ("" if it was). */
        public String skip = "";
    }

    /** What the calibration found, and whether it's used. */
    public static final class Result {
        public boolean applied;
        /** Gains on linear RGB over the fixed balance (white's brightness kept); 1, 1, 1 unless applied. */
        public double gainR = 1, gainG = 1, gainB = 1;
        /** Stars matched to the catalogue, measured well, and used in the end (the rest were outliers). */
        public int matched, good, used;
        /** Scatter of the used stars about the fit, and the fit's own uncertainty (natural log). */
        public double scatterR = Double.NaN, scatterB = Double.NaN, errR = Double.NaN, errB = Double.NaN;
        /** The scatter over what the stars' noise explains (about 1 when nothing else colours them). */
        public double excessR = Double.NaN, excessB = Double.NaN;
        /** Why the fixed balance was kept ("" if it wasn't). */
        public String reason = "";
        public final List<Star> stars = new ArrayList<Star>();

        /** For the log: what was used and why. */
        public String describe(double fixedK) {
            if (!applied) {
                return String.format(Locale.US, "colour: the fixed %d K white balance, as %s", Math.round(fixedK), reason);
            }
            return String.format(Locale.US, "colour: white balance from %d catalogue stars (of %d matched, %d measured well), a "
                            + "Sun-like star (B-V %.2f, %d K) as white: red x%.3f, blue x%.3f over the fixed %d K balance "
                            + "(+/-%.1f%%, +/-%.1f%%; the stars scatter %.1f%%, %.1f%%, %.1f and %.1f times their noise)", used,
                    matched, good, SUN_BV, Math.round(WHITE_K), gainR / gainG, gainB / gainG, Math.round(fixedK), 100 * errR,
                    100 * errB, 100 * scatterR, 100 * scatterB, excessR, excessB);
        }

        /** The used stars, brightest first, for the log: what each was expected to be and what it was. */
        public List<String> starLines(int max) {
            List<String> out = new ArrayList<String>();
            List<Star> l = new ArrayList<Star>();
            for (Star s : stars) if (!Double.isNaN(s.dr)) l.add(s);
            java.util.Collections.sort(l, new java.util.Comparator<Star>() {
                @Override
                public int compare(Star a, Star b) {
                    return Double.compare(a.vmag, b.vmag);
                }
            });
            for (Star s : l) {
                if (out.size() >= max) break;
                double[] e = expected(s.t);
                out.add(String.format(Locale.US, "%s (V %.1f, B-V %.2f, %d K): red/green %.3f, blue/green %.3f, expected %.3f, %.3f%s",
                        s.label.isEmpty() ? "a star" : s.label, s.vmag, s.bv, Math.round(s.t), s.r / s.g, s.b / s.g, e[0], e[2],
                        s.skip.isEmpty() ? "" : " (" + s.skip + ")"));
            }
            return out;
        }
    }

    /**
     * Measures the solution's catalogue stars in the stack and fits the gains ({@link #measure}, {@link #fit}).
     *
     * @param ap radius of the circle a star's light is summed over (px)
     */
    public static Result calibrate(RgbImage img, Renderer.Analysis a, SkySolver.Solution sol, BrightStars cat, double ap) {
        return fit(measure(img, a, sol, cat, ap));
    }

    /**
     * Each matched catalogue star's light in a circle of radius {@code ap} px around it, less its own background (the
     * median of a ring around the circle), turned into linear RGB with the analysis' matrix (the fixed balance), with
     * its noise from the ring's scatter. Stars without a usable colour index, at the edge, clipped (in any frame, or
     * near the clip level in the stack) or too faint in a channel are kept with the reason in {@link Star#skip}.
     */
    public static List<Star> measure(RgbImage img, Renderer.Analysis a, SkySolver.Solution sol, BrightStars cat, double ap) {
        List<Star> out = new ArrayList<Star>();
        if (sol == null || sol.cat == null || cat == null) return out;
        float[] m = a.m;
        int ringIn = (int) Math.ceil(ap + 3), ringOut = (int) Math.ceil(ap + 8);
        int maxRing = (2 * ringOut + 1) * (2 * ringOut + 1);
        float[] ringR = new float[maxRing], ringG = new float[maxRing], ringB = new float[maxRing];
        double ap2 = ap * ap, in2 = (double) ringIn * ringIn, out2 = (double) ringOut * ringOut;
        for (int k = 0; k < sol.cat.length && k < sol.mx.length; k++) {
            Star s = new Star();
            int c = sol.cat[k];
            s.cat = c;
            s.x = sol.mx[k];
            s.y = sol.my[k];
            if (c < 0 || c >= cat.n) continue;
            s.label = cat.label(c);
            s.vmag = cat.vmag[c];
            s.bv = cat.bv[c];
            out.add(s);
            if (Double.isNaN(s.bv)) {
                s.skip = "no colour index";
                continue;
            }
            if (s.bv < BV_MIN || s.bv > BV_MAX) {
                s.skip = "too hot or too cool for the formula";
                continue;
            }
            s.t = kelvin(s.bv);
            int cx = (int) Math.round(s.x), cy = (int) Math.round(s.y);
            if (cx - ringOut < img.x0 || cy - ringOut < img.y0 || cx + ringOut >= img.x1 || cy + ringOut >= img.y1) {
                s.skip = "at the edge";
                continue;
            }
            double fr = 0, fg = 0, fb = 0, peak = 0;
            int na = 0, nr = 0;
            boolean clipped = false;
            double[] ic = clipInvAt(a, s.x, s.y);
            for (int dy = -ringOut; dy <= ringOut && !clipped; dy++) {
                double ddy = cy + dy - s.y;
                for (int dx = -ringOut; dx <= ringOut; dx++) {
                    double ddx = cx + dx - s.x, d2 = ddx * ddx + ddy * ddy;
                    int i = (cy + dy) * img.width + cx + dx;
                    if (d2 <= ap2) {
                        if (img.clip != null && img.clip[i] != 0) {
                            clipped = true;
                            break;
                        }
                        fr += img.r[i];
                        fg += img.g[i];
                        fb += img.b[i];
                        peak = Math.max(peak, Math.max(img.r[i] * ic[0], Math.max(img.g[i] * ic[1], img.b[i] * ic[2])));
                        na++;
                    } else if (d2 >= in2 && d2 <= out2) {
                        ringR[nr] = img.r[i];
                        ringG[nr] = img.g[i];
                        ringB[nr] = img.b[i];
                        nr++;
                    }
                }
            }
            if (clipped || peak > 0.85) {
                s.skip = "clipped";
                continue;
            }
            if (na == 0 || nr < 16) {
                s.skip = "too small to measure";
                continue;
            }
            double[] bg = new double[3], sd = new double[3];
            float[][] rings = {ringR, ringG, ringB};
            for (int ch = 0; ch < 3; ch++) {
                float[] t = Arrays.copyOf(rings[ch], nr);
                float med = Stats.median(t, nr);
                System.arraycopy(rings[ch], 0, t, 0, nr);
                bg[ch] = med;
                sd[ch] = Stats.madSigma(t, nr, med);
            }
            double[] f = {fr - na * bg[0], fg - na * bg[1], fb - na * bg[2]};
            // background noise in the circle and in the ring's median, and a floor for everything else
            double[] var = new double[3];
            for (int ch = 0; ch < 3; ch++) {
                var[ch] = na * sd[ch] * sd[ch] * (1 + 1.57 * na / nr) + sq(0.01 * f[ch]);
            }
            s.r = m[0] * f[0] + m[1] * f[1] + m[2] * f[2];
            s.g = m[3] * f[0] + m[4] * f[1] + m[5] * f[2];
            s.b = m[6] * f[0] + m[7] * f[1] + m[8] * f[2];
            s.sr = Math.sqrt(sq(m[0]) * var[0] + sq(m[1]) * var[1] + sq(m[2]) * var[2]);
            s.sg = Math.sqrt(sq(m[3]) * var[0] + sq(m[4]) * var[1] + sq(m[5]) * var[2]);
            s.sb = Math.sqrt(sq(m[6]) * var[0] + sq(m[7]) * var[1] + sq(m[8]) * var[2]);
            if (!(s.r > MIN_SNR * s.sr && s.g > MIN_SNR * s.sg && s.b > MIN_SNR * s.sb)) s.skip = "too faint";
        }
        return out;
    }

    /** 1 / clip level of each channel at a pixel, from the analysis' grid (lens shading raises it towards the corners). */
    static double[] clipInvAt(Renderer.Analysis a, double x, double y) {
        double[] out = new double[3];
        if (a.clipInv == null || a.cw <= 0 || a.ch <= 0) {
            Arrays.fill(out, 1 / Math.max(1f, a.clip));
            return out;
        }
        int gx = Renderer.CGX, gy = Renderer.CGY;
        double fx = Stats.clamp((x - a.cx0) / a.cw * (gx - 1), 0, gx - 1.000001);
        double fy = Stats.clamp((y - a.cy0) / a.ch * (gy - 1), 0, gy - 1.000001);
        int i = (int) fx, j = (int) fy;
        double tx = fx - i, ty = fy - j;
        for (int c = 0; c < 3; c++) {
            float[] g = a.clipInv;
            double v00 = g[(c * gy + j) * gx + i], v10 = g[(c * gy + j) * gx + i + 1];
            double v01 = g[(c * gy + j + 1) * gx + i], v11 = g[(c * gy + j + 1) * gx + i + 1];
            out[c] = (1 - ty) * ((1 - tx) * v00 + tx * v10) + ty * ((1 - tx) * v01 + tx * v11);
        }
        return out;
    }

    /**
     * Fits the gains to measured stars (their light, its noise and their temperature set; ones with a
     * {@link Star#skip} reason are left out). Outliers (blends, variables, mismatches) are left out by the biweight.
     */
    public static Result fit(List<Star> stars) {
        Result res = new Result();
        res.stars.addAll(stars);
        res.matched = stars.size();
        List<Star> good = new ArrayList<Star>();
        for (Star s : stars) {
            if (!s.skip.isEmpty()) continue;
            if (!(s.r > 0 && s.g > 0 && s.b > 0) || Double.isNaN(s.t)) {
                s.skip = "no light in a channel";
                continue;
            }
            double[] e = expected(s.t);
            s.dr = Math.log(e[0]) - Math.log(s.r / s.g);
            s.db = Math.log(e[2]) - Math.log(s.b / s.g);
            double vg = sq(s.sg / s.g);
            s.wr = 1 / (sq(s.sr / s.r) + vg + SIGMA_STAR * SIGMA_STAR);
            s.wb = 1 / (sq(s.sb / s.b) + vg + SIGMA_STAR * SIGMA_STAR);
            good.add(s);
        }
        res.good = good.size();
        if (good.size() < MIN_STARS) {
            res.reason = String.format(Locale.US, "only %d stars were measured well enough for their colours (%d needed)", good.size(), MIN_STARS);
            return res;
        }
        int n = good.size();
        double[] d = new double[n], w = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = good.get(i).dr;
            w[i] = good.get(i).wr;
        }
        double[] fr = biweight(d, w);
        for (int i = 0; i < n; i++) {
            d[i] = good.get(i).db;
            w[i] = good.get(i).wb;
        }
        double[] fb = biweight(d, w);
        // outliers in either: left out of the count, and marked
        int used = 0;
        double kr = 3 * Math.max(fr[1], 0.03), kb = 3 * Math.max(fb[1], 0.03);
        for (Star s : good) {
            if (Math.abs(s.dr - fr[0]) > kr || Math.abs(s.db - fb[0]) > kb) s.skip = "an outlier";
            else used++;
        }
        res.used = used;
        res.scatterR = fr[1];
        res.scatterB = fb[1];
        res.errR = fr[2];
        res.errB = fb[2];
        res.excessR = fr[3];
        res.excessB = fb[3];
        double gr = Math.exp(fr[0]), gb = Math.exp(fb[0]);
        if (used < MIN_STARS) {
            res.reason = String.format(Locale.US, "only %d stars agreed on the colour (%d needed)", used, MIN_STARS);
        } else if (Math.abs(fr[0]) > MAX_LN_GAIN || Math.abs(fb[0]) > MAX_LN_GAIN) {
            res.reason = String.format(Locale.US, "the stars asked for red x%.2f and blue x%.2f, more than a camera is ever off by "
                    + "(a mismatch, or a sky colour the stars can't be read through)", gr, gb);
        } else if (fr[1] > MAX_SCATTER || fb[1] > MAX_SCATTER) {
            res.reason = String.format(Locale.US, "the stars' colours scattered too much (%.0f%%, %.0f%%) to trust", 100 * fr[1], 100 * fb[1]);
        } else if (fr[3] > MAX_EXCESS || fb[3] > MAX_EXCESS) {
            res.reason = String.format(Locale.US, "the stars' colours disagreed %.1f and %.1f times more than their noise explains, "
                    + "so something other than the white balance coloured them", fr[3], fb[3]);
        } else if (fr[2] > MAX_ERR || fb[2] > MAX_ERR) {
            res.reason = String.format(Locale.US, "%d stars pinned the colour down only to +/-%.1f%% and +/-%.1f%% (%.1f%% needed)", used,
                    100 * fr[2], 100 * fb[2], 100 * MAX_ERR);
        } else {
            // white's brightness kept: a neutral pixel's luminance doesn't change
            double y = KR * gr + KG + KB * gb;
            res.gainR = gr / y;
            res.gainG = 1 / y;
            res.gainB = gb / y;
            res.applied = true;
        }
        return res;
    }

    /**
     * Robust weighted location of {@code d}: a weighted median, then Tukey's biweight (c = 4.685 scale). Returns
     * {location, scatter (1.4826 MAD about it), the location's uncertainty, the scatter over the noise (1.4826 MAD
     * of the residuals in units of their expected noise, 1 / sqrt(w))}.
     */
    static double[] biweight(double[] d, double[] w) {
        int n = d.length;
        double mu = weightedMedian(d, w);
        double scale = 0.02;
        double[] res = new double[n];
        for (int it = 0; it < 10; it++) {
            for (int i = 0; i < n; i++) res[i] = Math.abs(d[i] - mu);
            double mad = Stats.median(res.clone(), n);
            scale = Math.max(0.02, 1.4826 * mad);
            double c = 4.685 * scale, sw = 0, sd = 0;
            for (int i = 0; i < n; i++) {
                double u = (d[i] - mu) / c;
                if (Math.abs(u) >= 1) continue;
                double k = w[i] * sq(1 - u * u);
                sw += k;
                sd += k * d[i];
            }
            if (sw <= 0) break;
            double next = sd / sw;
            boolean done = Math.abs(next - mu) < 1e-6;
            mu = next;
            if (done) break;
        }
        // uncertainty: the scatter seen over the square root of the effective number of stars, but never less than
        // the stars' own noise allows (a few stars can happen to agree), widened for few stars as Student's t is
        double c = 4.685 * scale, s1 = 0, s2 = 0, sv = 0;
        for (int i = 0; i < n; i++) {
            double u = (d[i] - mu) / c;
            if (Math.abs(u) >= 1) continue;
            double k = w[i] * sq(1 - u * u);
            s1 += k;
            s2 += k * k;
            sv += k * k / w[i];
        }
        double nEff = s2 > 0 ? s1 * s1 / s2 : 1;
        for (int i = 0; i < n; i++) res[i] = Math.abs(d[i] - mu);
        double scatter = 1.4826 * Stats.median(res.clone(), n);
        double err = Math.max(scatter / Math.sqrt(Math.max(1, nEff)), s1 > 0 ? Math.sqrt(sv) / s1 : scatter);
        double nu = Math.max(1, nEff - 1);
        err *= 1 + (2.37 / nu + 2.82 / (nu * nu)) / 1.96;
        for (int i = 0; i < n; i++) res[i] = Math.abs(d[i] - mu) * Math.sqrt(w[i]);
        double excess = 1.4826 * Stats.median(res.clone(), n);
        return new double[]{mu, scatter, err, excess};
    }

    static double weightedMedian(double[] d, double[] w) {
        int n = d.length;
        Integer[] idx = new Integer[n];
        double total = 0;
        for (int i = 0; i < n; i++) {
            idx[i] = i;
            total += w[i];
        }
        final double[] dd = d;
        Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(dd[a], dd[b]);
            }
        });
        double acc = 0;
        for (int k = 0; k < n; k++) {
            acc += w[idx[k]];
            if (acc >= total / 2) return d[idx[k]];
        }
        return n > 0 ? d[idx[n - 1]] : 0;
    }

    /** The camera-to-linear-RGB matrix with the gains applied (each output row scaled). */
    public static float[] apply(float[] camToSrgb, Result r) {
        float[] o = camToSrgb.clone();
        double[] g = {r.gainR, r.gainG, r.gainB};
        for (int row = 0; row < 3; row++) for (int c = 0; c < 3; c++) o[row * 3 + c] = (float) (camToSrgb[row * 3 + c] * g[row]);
        return o;
    }

    /**
     * The camera RGB the calibrated matrix renders white (green = 1), for the DNG's as-shot neutral, so a raw
     * converter opens the DNG with the stars' white balance; null if it can't be had.
     */
    public static float[] neutral(float[] camToSrgb, Result r) {
        double[] m = new double[9];
        for (int i = 0; i < 9; i++) m[i] = camToSrgb[i];
        double[] inv = Rot.inverse(m);
        if (inv == null) return null;
        double[] v = Rot.mulVec(inv, new double[]{1 / r.gainR, 1 / r.gainG, 1 / r.gainB});
        if (!(v[1] > 0) || !(v[0] > 0) || !(v[2] > 0)) return null;
        return new float[]{(float) (v[0] / v[1]), 1f, (float) (v[2] / v[1])};
    }

    private static double sq(double v) {
        return v * v;
    }
}
