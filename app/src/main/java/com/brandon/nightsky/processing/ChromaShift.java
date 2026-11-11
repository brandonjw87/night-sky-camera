package com.brandon.nightsky.processing;

import java.util.Arrays;
import java.util.Locale;

/**
 * Colour fringes from the lens (2.0). A lens magnifies red, green and blue light by slightly different amounts
 * (lateral chromatic aberration), so away from the picture's middle a star's red image sits a fraction of a pixel
 * further out than its green one, or further in, and its blue one somewhere else again: every star gets a red rim on
 * one side and a blue one on the other, more so towards the corners.
 * <p>
 * The stack's own stars measure it. Each bright, unclipped star standing by itself has its centre found in the three
 * colours separately (a Gaussian-windowed centroid, the window following the centre until it stays put, so each colour
 * gets its own unbiased centre). How far the red and the blue centres lie from the green one along the line from the
 * picture's middle is then fitted, robustly, with a shift that grows as {@code a r + b r^3}. A colour whose largest
 * shift is worth taking out and well above its own uncertainty gets a {@link Model}; the stacker ({@link SharpStacker})
 * then reads that colour's photosites where the lens really put its light, so the three colours land on each other
 * with nothing resampled. Shifts that are small, uncertain, or too large to be a lens's are left alone.
 */
public final class ChromaShift {
    private ChromaShift() {}

    /** A colour is corrected when its largest shift is at least this (pixels) and this many times its uncertainty. */
    public static final double MIN_SHIFT = 0.12, MIN_SIGMAS = 4;
    /** The largest uncertainty (pixels, at the corner) of a shift that's used. */
    public static final double MAX_SIGMA = 0.08;
    /** A shift beyond this isn't a lens's colour fringe: nothing is corrected (pixels). */
    public static final double MAX_SHIFT = 2.0;
    /** Stars a colour's fit needs, and how many of them in the outer part of the picture (the fit is about the corners). */
    public static final int MIN_STARS = 40, MIN_OUTER = 8;
    /** The outer part: beyond this share of the way from the middle to a corner. */
    static final double OUTER = 0.6;
    /** The brightest stars looked at. */
    static final int MAX_STARS = 1500;
    /** Signal to noise a star needs in a colour for its centre there to be worth having. */
    static final double MIN_SNR = 10;
    /** What every centre is uncertain by besides its noise (pixels): pixel sampling, a neighbour's wing. */
    static final double FLOOR = 0.03;

    /**
     * Where the lens puts a star's red and blue light against its green: along the line from the picture's middle
     * (cx, cy), {@code k = a + b rho^2} times the distance from the middle, {@code rho} being that distance over
     * {@link #halfDiagonal} (so a + b is the shift at a corner as a share of the half diagonal).
     */
    public static final class Model {
        public final float cx, cy, halfDiagonal;
        public final float aR, bR, aB, bB;

        public Model(float cx, float cy, float halfDiagonal, float aR, float bR, float aB, float bB) {
            this.cx = cx;
            this.cy = cy;
            this.halfDiagonal = halfDiagonal;
            this.aR = aR;
            this.bR = bR;
            this.aB = aB;
            this.bB = bB;
        }

        /** Channel {@code c}'s shift (0 red, 2 blue; pixels, outwards positive) at {@code rho} of the way to a corner. */
        public double shift(int c, double rho) {
            double a = c == 0 ? aR : c == 2 ? aB : 0, b = c == 0 ? bR : c == 2 ? bB : 0;
            return halfDiagonal * rho * (a + b * rho * rho);
        }

        /** The largest shift of either colour anywhere in the picture (pixels, as a size). */
        public double largest() {
            double m = 0;
            for (int i = 1; i <= 50; i++) m = Math.max(m, Math.max(Math.abs(shift(0, i / 50.0)), Math.abs(shift(2, i / 50.0))));
            return m;
        }
    }

    /** One colour's shift against green, as fitted. */
    public static final class Fit {
        /** Stars the fit kept, and how many of them in the outer part. */
        public int stars, outer;
        /** The shift is {@code a rho + b rho^3} pixels (outwards positive), rho the share of the way to a corner. */
        public double a, b;
        /** The shift at the corner, its uncertainty, and the largest shift anywhere in the picture (all pixels, signed). */
        public double corner, sigma, largest;
        /** How far the stars scatter about the fit (pixels, robust), and their mean shift across the line to the middle. */
        public double scatter, across;
        /** Worth taking out, and sure enough. */
        public boolean used;

        public double at(double rho) {
            return rho * (a + b * rho * rho);
        }
    }

    public static final class Result {
        /** Stars measured in all three colours. */
        public int measured;
        /** Red's and blue's fits; null when there weren't the stars for one. */
        public Fit red, blue;
        /** What to correct; null when nothing is. */
        public Model model;
        /** Why nothing was measured, when nothing was; null otherwise. */
        public String why;
        public long ms;

        /**
         * "red light 0.62 px further out and its blue 0.41 px further in": each colour's largest shift against green,
         * of the colours fitted, or with {@code usedOnly} of the ones being taken out.
         */
        public String shifts(boolean usedOnly) {
            StringBuilder s = new StringBuilder();
            if (red != null && (red.used || !usedOnly)) s.append("red light ").append(words(red.largest));
            if (blue != null && (blue.used || !usedOnly)) s.append(s.length() > 0 ? " and its blue " : "blue light ").append(words(blue.largest));
            return s.toString();
        }

        /** "red 0.02 px, blue 0.01 px": the size of each fitted colour's largest shift. */
        public String sizes() {
            StringBuilder s = new StringBuilder();
            if (red != null) s.append(String.format(Locale.US, "red %.2f px", Math.abs(red.largest)));
            if (blue != null) s.append(s.length() > 0 ? ", " : "").append(String.format(Locale.US, "blue %.2f px", Math.abs(blue.largest)));
            return s.toString();
        }

        private static String words(double px) {
            return String.format(Locale.US, "%.2f px further %s", Math.abs(px), px >= 0 ? "out" : "in");
        }

        /** The numbers for the log. */
        public String numbers() {
            StringBuilder s = new StringBuilder();
            Fit[] fs = {red, blue};
            String[] names = {"red", "blue"};
            for (int i = 0; i < 2; i++) {
                Fit f = fs[i];
                if (f == null) continue;
                if (s.length() > 0) s.append("; ");
                s.append(String.format(Locale.US, "%s %+.3f px at the corner (+-%.3f, largest %+.3f; a %+.3f b %+.3f; %d stars, %d of them "
                        + "outer; scatter %.3f px, across %+.3f)", names[i], f.corner, f.sigma, f.largest, f.a, f.b, f.stars, f.outer, f.scatter,
                        f.across));
            }
            return s.toString();
        }
    }

    /**
     * Measures the stack {@code img}'s colour fringes on {@code stars} (their places in the stack, as detected in the
     * reference frame; the brightest {@link #MAX_STARS} unclipped ones standing by themselves are used).
     */
    public static Result measure(RgbImage img, StarList stars) {
        long t0 = System.currentTimeMillis();
        Result res = new Result();
        int W = img.width, H = img.height;
        double cx = W / 2.0, cy = H / 2.0, R0 = 0.5 * Math.hypot(W, H);
        if (stars == null || stars.count < MIN_STARS) {
            res.why = "too few stars";
            res.ms = System.currentTimeMillis() - t0;
            return res;
        }
        // brightest first
        int total = stars.count;
        long[] keys = new long[total];
        for (int i = 0; i < total; i++) keys[i] = ((long) Float.floatToIntBits(Math.max(stars.flux[i], 0f)) << 32) | (0xFFFFFFFFL & i);
        Arrays.sort(keys);
        int n = Math.min(total, MAX_STARS);
        double[] rho = new double[n], dR = new double[n], dB = new double[n], tR = new double[n], tB = new double[n];
        double[] vR = new double[n], vB = new double[n];
        int m = 0;
        float[] ring = new float[41 * 41];
        double[] cg = new double[4], cc = new double[4];
        for (int k = 0; k < n; k++) {
            int i = (int) (keys[total - 1 - k] & 0xFFFFFFFFL);
            if (stars.saturated[i]) continue;
            double sx = stars.x[i], sy = stars.y[i];
            double hfr = stars.hfr[i] > 0 ? stars.hfr[i] : 1.5;
            double sw = Stats.clamp(0.85 * hfr, 1.0, 2.5);
            int rad = (int) Math.ceil(3 * sw) + 1, ringIn = rad + 2, ringOut = rad + 6;
            int px = (int) Math.floor(sx), py = (int) Math.floor(sy);
            if (px - ringOut - 3 < img.x0 || py - ringOut - 3 < img.y0 || px + ringOut + 3 >= img.x1 || py + ringOut + 3 >= img.y1) continue;
            // by itself: a neighbour's light pulls the colours' centres by different amounts
            boolean crowded = false;
            double near = (ringOut + 1.0) * (ringOut + 1.0), fi = Math.max(stars.flux[i], 1e-6f);
            for (int j = 0; j < total && !crowded; j++) {
                if (j == i) continue;
                double ex = stars.x[j] - sx, ey = stars.y[j] - sy;
                if (ex * ex + ey * ey < near && stars.flux[j] > 0.1 * fi) crowded = true;
            }
            if (crowded) continue;
            // clipped in any frame: its top is flat, and not equally in the three colours
            boolean clipped = false;
            if (img.clip != null) {
                for (int y = py - rad; y <= py + rad && !clipped; y++) {
                    for (int x = px - rad; x <= px + rad; x++) {
                        if (img.clip[y * W + x] != 0) {
                            clipped = true;
                            break;
                        }
                    }
                }
            }
            if (clipped) continue;
            // green first, from where the star was detected; red and blue from green's centre
            double[] bgG = background(img.g, W, px, py, ringIn, ringOut, ring);
            if (!centroid(img.g, W, bgG[0], bgG[1], sx, sy, sw, rad, cg) || cg[3] < MIN_SNR) continue;
            int gx = (int) Math.floor(cg[0]), gy = (int) Math.floor(cg[1]);
            double[] bgR = background(img.r, W, gx, gy, ringIn, ringOut, ring);
            if (!centroid(img.r, W, bgR[0], bgR[1], cg[0], cg[1], sw, rad, cc) || cc[3] < MIN_SNR) continue;
            double rx = cc[0], ry = cc[1], rv = cc[2];
            double[] bgB = background(img.b, W, gx, gy, ringIn, ringOut, ring);
            if (!centroid(img.b, W, bgB[0], bgB[1], cg[0], cg[1], sw, rad, cc) || cc[3] < MIN_SNR) continue;
            double ux = cg[0] - cx, uy = cg[1] - cy, r = Math.hypot(ux, uy);
            if (r < 1) continue;
            ux /= r;
            uy /= r;
            rho[m] = r / R0;
            dR[m] = (rx - cg[0]) * ux + (ry - cg[1]) * uy;
            tR[m] = -(rx - cg[0]) * uy + (ry - cg[1]) * ux;
            dB[m] = (cc[0] - cg[0]) * ux + (cc[1] - cg[1]) * uy;
            tB[m] = -(cc[0] - cg[0]) * uy + (cc[1] - cg[1]) * ux;
            vR[m] = rv + cg[2];
            vB[m] = cc[2] + cg[2];
            m++;
        }
        res.measured = m;
        if (m < MIN_STARS) {
            res.why = "only " + m + " stars bright enough, standing by themselves and unclipped";
            res.ms = System.currentTimeMillis() - t0;
            return res;
        }
        res.red = fit(rho, dR, tR, vR, m);
        res.blue = fit(rho, dB, tB, vB, m);
        if (res.red == null && res.blue == null) {
            res.why = "too few stars towards the corners";
            res.ms = System.currentTimeMillis() - t0;
            return res;
        }
        boolean wrong = (res.red != null && Math.abs(res.red.largest) > MAX_SHIFT) || (res.blue != null && Math.abs(res.blue.largest) > MAX_SHIFT);
        if (wrong) {
            res.why = "the shifts measured are too large to be the lens's";
        } else {
            boolean ur = res.red != null && res.red.used, ub = res.blue != null && res.blue.used;
            if (ur || ub) {
                res.model = new Model((float) cx, (float) cy, (float) R0,
                        ur ? (float) (res.red.a / R0) : 0f, ur ? (float) (res.red.b / R0) : 0f,
                        ub ? (float) (res.blue.a / R0) : 0f, ub ? (float) (res.blue.b / R0) : 0f);
            }
        }
        res.ms = System.currentTimeMillis() - t0;
        return res;
    }

    /** The median of channel {@code d} in the ring around pixel (px, py), and its noise there. */
    private static double[] background(float[] d, int W, int px, int py, int ringIn, int ringOut, float[] ring) {
        int n = 0, in2 = ringIn * ringIn, out2 = ringOut * ringOut;
        for (int dy = -ringOut; dy <= ringOut; dy++) {
            for (int dx = -ringOut; dx <= ringOut; dx++) {
                int d2 = dx * dx + dy * dy;
                if (d2 >= in2 && d2 <= out2 && n < ring.length) ring[n++] = d[(py + dy) * W + px + dx];
            }
        }
        float[] t = Arrays.copyOf(ring, n);
        float med = Stats.median(t, n);
        System.arraycopy(ring, 0, t, 0, n);
        return new double[]{med, Math.max(1e-6, Stats.madSigma(t, n, med))};
    }

    /**
     * The centre of the star near (x0, y0) in channel {@code d} (pixel centres at +0.5): a Gaussian window of sigma
     * {@code sw} follows the windowed centre until it stays put. Into {@code out}: x, y, the variance of each
     * coordinate from the background's noise {@code sd}, and the windowed light's signal to noise. False if it has no
     * light, wanders off or doesn't settle.
     */
    static boolean centroid(float[] d, int W, double bg, double sd, double x0, double y0, double sw, int rad, double[] out) {
        double cx = x0, cy = y0, inv = 1 / (2 * sw * sw), r2max = (double) rad * rad;
        for (int it = 0; it < 30; it++) {
            int ix = (int) Math.floor(cx), iy = (int) Math.floor(cy);
            double s = 0, sx = 0, sy = 0, w2 = 0, w2x = 0;
            for (int y = iy - rad; y <= iy + rad; y++) {
                double dy = y + 0.5 - cy;
                int row = y * W;
                for (int x = ix - rad; x <= ix + rad; x++) {
                    double dx = x + 0.5 - cx, r2 = dx * dx + dy * dy;
                    if (r2 > r2max) continue;
                    double w = Math.exp(-r2 * inv), v = (d[row + x] - bg) * w;
                    s += v;
                    sx += v * dx;
                    sy += v * dy;
                    w2 += w * w;
                    w2x += w * w * dx * dx;
                }
            }
            if (!(s > 0)) return false;
            double mx = 2 * sx / s, my = 2 * sy / s;
            cx += mx;
            cy += my;
            if (Math.abs(cx - x0) > 2.5 || Math.abs(cy - y0) > 2.5) return false;
            if (mx * mx + my * my < 1e-8) {
                out[0] = cx;
                out[1] = cy;
                out[2] = 4 * sd * sd * w2x / (s * s);
                out[3] = s / (sd * Math.sqrt(w2));
                return true;
            }
        }
        return false;
    }

    /**
     * Fits {@code d} (pixels along the line from the middle) with {@code a rho + b rho^3}: weighted by each star's
     * noise, outliers (a blend, a star with a companion, a hot pixel beside it) left out by Tukey's biweight. Null
     * with too few stars, or too few in the outer part.
     */
    static Fit fit(double[] rho, double[] d, double[] t, double[] var, int n) {
        if (n < MIN_STARS) return null;
        double[] w = new double[n], rw = new double[n], u = new double[n];
        for (int i = 0; i < n; i++) {
            w[i] = 1 / (var[i] + FLOOR * FLOOR);
            rw[i] = 1;
        }
        double a = 0, b = 0, scale = 1;
        double[] cov = new double[3];
        for (int it = 0; it < 12; it++) {
            double s11 = 0, s12 = 0, s22 = 0, y1 = 0, y2 = 0;
            for (int i = 0; i < n; i++) {
                double ww = w[i] * rw[i];
                if (ww <= 0) continue;
                double p = rho[i], q = p * p * p;
                s11 += ww * p * p;
                s12 += ww * p * q;
                s22 += ww * q * q;
                y1 += ww * p * d[i];
                y2 += ww * q * d[i];
            }
            double det = s11 * s22 - s12 * s12;
            if (!(det > 1e-12 * s11 * s22) || !(s11 > 0)) return null;
            a = (s22 * y1 - s12 * y2) / det;
            b = (s11 * y2 - s12 * y1) / det;
            cov[0] = s22 / det;
            cov[1] = -s12 / det;
            cov[2] = s11 / det;
            for (int i = 0; i < n; i++) u[i] = Math.abs(d[i] - rho[i] * (a + b * rho[i] * rho[i])) * Math.sqrt(w[i]);
            scale = Math.max(1e-6, 1.4826 * median(u, n));
            double c = 4.685 * Math.max(scale, 1);
            for (int i = 0; i < n; i++) {
                double x = u[i] / c;
                rw[i] = x < 1 ? (1 - x * x) * (1 - x * x) : 0;
            }
        }
        Fit f = new Fit();
        f.a = a;
        f.b = b;
        double sumT = 0, sumW = 0;
        double[] res = new double[n];
        int kept = 0;
        for (int i = 0; i < n; i++) {
            if (rw[i] <= 0) continue;
            f.stars++;
            if (rho[i] > OUTER) f.outer++;
            sumT += w[i] * rw[i] * t[i];
            sumW += w[i] * rw[i];
            res[kept++] = Math.abs(d[i] - rho[i] * (a + b * rho[i] * rho[i]));
        }
        if (f.stars < MIN_STARS || f.outer < MIN_OUTER) return null;
        f.across = sumW > 0 ? sumT / sumW : 0;
        f.scatter = 1.4826 * median(res, kept);
        double s2 = Math.max(1, scale * scale);
        f.corner = a + b;
        f.sigma = Math.sqrt(Math.max(0, s2 * (cov[0] + 2 * cov[1] + cov[2])));
        f.largest = f.corner;
        for (int i = 1; i < 50; i++) {
            double v = f.at(i / 50.0);
            if (Math.abs(v) > Math.abs(f.largest)) f.largest = v;
        }
        f.used = Math.abs(f.largest) >= MIN_SHIFT && Math.abs(f.largest) >= MIN_SIGMAS * f.sigma && f.sigma <= MAX_SIGMA;
        return f;
    }

    private static double median(double[] v, int n) {
        double[] t = Arrays.copyOf(v, n);
        Arrays.sort(t);
        return n == 0 ? 0 : (n & 1) == 1 ? t[n / 2] : 0.5 * (t[n / 2 - 1] + t[n / 2]);
    }
}
