package com.brandon.nightsky.processing;

import java.util.Locale;

/**
 * The sky's glow taken out with the Milky Way kept (2.4). Two things the 2D quadratic background of 1.0-2.3 couldn't do:
 * <ul>
 * <li><b>Keep the Milky Way out of the fit.</b> A quadratic fitted to every cell of the picture takes a wide band of the
 * Milky Way for a slope of light pollution and takes part of it away (and leaves the sky beside it dark). Once the photo
 * is placed on the sky (the plate solution), where the Milky Way is is known: each cell's direction gives its galactic
 * longitude and latitude, and a smooth envelope of the band's light there ({@link #envelope}) says how much of the cell's
 * level may be the Milky Way's. The cells on the band are left out of the fit (all of it where the envelope is bright,
 * fading out towards the band's edges), so the background under the band is what the sky either side of it says.
 * <li><b>A glow that isn't a simple slope.</b> A town's light dome rises steeply near the horizon and isn't a quadratic.
 * What the quadratic leaves in the cells off the band is followed by a smooth surface (as GraXpert's spline and RBF
 * background models do, from the sky's own sample cells): the smoothest surface through those residuals, a thin plate
 * bent as little as it can be ({@link #smooth}); where the band's cells were left out it carries on from the cells either
 * side, as a thin plate would.
 * </ul>
 * Without a plate solution (or for star trails) neither is done: where the Milky Way is isn't known, and a surface that
 * bends would take it for light pollution.
 */
public final class SkyBackground {
    private SkyBackground() {}

    /**
     * The envelope's protection: a cell where the band's light (on the envelope's scale, about 1 along the band's middle,
     * {@link #envelope}) is ENV_LO or less is fitted as sky; from ENV_HI up it's left out; between, it counts in part.
     */
    static final double ENV_LO = 0.06, ENV_HI = 0.25;
    /** At least this share of the good cells stays in the fit: on a picture full of Milky Way only its brightest part is left out. */
    static final double MIN_SKY_SHARE = 0.4;
    /**
     * The residual surface's stiffness (the thin plate's bending against the cells' pull, per cell; about the fourth power
     * of how many cells across the smallest bump it follows is: 4, a bump about 1.4 cells or 5 degrees across) and its
     * pull towards zero where there's nothing to follow. The cells' levels are medians that leave a galaxy's or a
     * nebula's bright part out (BackgroundTest: a galaxy of Andromeda's size keeps 98% or more of its light).
     */
    static final double STIFF = 4, ZERO_PULL = 2e-3;
    /**
     * The surface's robust weights: cut at POS_CUT (above) and 4.685 (below) times the cells' scatter about it, the
     * scatter taken as SCALE_FLOOR of the sky's level at the least (a glow's bends under that are followed whatever they
     * are, only what stands clear of them is left out).
     */
    static final double POS_CUT = 4, SCALE_FLOOR = 0.02;
    /** Cells a residual surface needs, and the IRLS passes that make it robust. */
    static final int MIN_CELLS = 40, PASSES = 5;

    /** Galactic directions: rows are the J2000 directions of l = 0, l = 90 and the north galactic pole ({@link SkyGuide}). */
    private static final double[] GAL = {
            -0.0548755604, -0.8734370902, -0.4838350155,
            0.4941094279, -0.4448296300, 0.7469822445,
            -0.8676661490, -0.1980763734, 0.4559837762};

    /** Galactic longitude and latitude (degrees, l in -180..180) of a J2000 direction. */
    static double[] galactic(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        double x = (GAL[0] * v[0] + GAL[1] * v[1] + GAL[2] * v[2]) / n;
        double y = (GAL[3] * v[0] + GAL[4] * v[1] + GAL[5] * v[2]) / n;
        double z = (GAL[6] * v[0] + GAL[7] * v[1] + GAL[8] * v[2]) / n;
        return new double[]{Math.toDegrees(Math.atan2(y, x)), Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, z))))};
    }

    /**
     * The Milky Way's light towards a J2000 direction, smoothed (no star clouds, no dark lanes): about 1 along the band's
     * middle, brightest towards the galactic centre (Sagittarius) and in Cygnus's star cloud, about a third of that towards
     * the anticentre (Auriga); as thick as the band's light is seen to reach in a deep photo (thicker at the bulge).
     */
    public static double envelope(double[] j2000) {
        double[] lb = galactic(j2000);
        double l = lb[0], b = lb[1];
        double c = Math.cos(Math.toRadians(l) / 2);
        double base = 0.3 + 0.7 * c * c + 0.25 * Math.exp(-sq((l - 75) / 15));
        double thick = 7 + 8 * Math.exp(-sq(l / 25));
        return base * Math.exp(-0.5 * sq(b / thick));
    }

    /**
     * How much each cell is the Milky Way (0: sky, 1: the band, left out of the fit), from where the solution puts the
     * cell's middle: cells at (cu, cv) in -1..1 over the crop (x0, y0, w, h) of the stack. At least
     * {@link #MIN_SKY_SHARE} of the ok cells are kept as sky (the thresholds raised until they are). Null when the photo
     * isn't placed on the sky.
     */
    static float[] bandShare(SkySolver.Solution sol, int x0, int y0, int w, int h, double[] cu, double[] cv, boolean[] ok) {
        if (sol == null || sol.R == null || sol.model == null) return null;
        int n = cu.length;
        double[] env = new double[n];
        for (int k = 0; k < n; k++) {
            double x = x0 + (cu[k] + 1) / 2 * w, y = y0 + (cv[k] + 1) / 2 * h;
            double[] d = sol.direction(x, y);
            env[k] = d != null && !Double.isNaN(d[0]) ? envelope(d) : 0;
        }
        int good = 0;
        for (int k = 0; k < n; k++) if (ok[k]) good++;
        double lo = ENV_LO, hi = ENV_HI;
        float[] share = new float[n];
        for (int pass = 0; pass < 12; pass++) {
            double kept = 0;
            for (int k = 0; k < n; k++) {
                share[k] = (float) smooth(env[k], lo, hi);
                if (ok[k]) kept += 1 - share[k];
            }
            if (kept >= MIN_SKY_SHARE * good) break;
            lo *= 1.35;
            hi *= 1.35;
        }
        return share;
    }

    /**
     * The smoothest surface through the residuals {@code r} (on a gx x gy grid of cells, row by row) with weights
     * {@code w} (0: no say): least squares against the cells plus the thin plate's bending ({@link #apply},
     * {@link #STIFF}) and a slight pull to zero ({@link #ZERO_PULL}), solved by conjugate gradients.
     */
    static double[] smooth(double[] r, double[] w, int gx, int gy) {
        int n = gx * gy;
        double[] s = new double[n], b = new double[n];
        for (int k = 0; k < n; k++) b[k] = w[k] * r[k];
        // A s = b with A = W + STIFF L'L + ZERO_PULL I (symmetric, positive definite)
        double[] res = new double[n], p = new double[n], ap = new double[n];
        apply(s, w, gx, gy, ap);
        double rr = 0;
        for (int k = 0; k < n; k++) {
            res[k] = b[k] - ap[k];
            p[k] = res[k];
            rr += res[k] * res[k];
        }
        double bb = 0;
        for (int k = 0; k < n; k++) bb += b[k] * b[k];
        if (bb == 0) return s;
        for (int it = 0; it < 4 * n && rr > 1e-20 * bb; it++) {
            apply(p, w, gx, gy, ap);
            double pap = 0;
            for (int k = 0; k < n; k++) pap += p[k] * ap[k];
            if (!(pap > 0)) break;
            double alpha = rr / pap, rn = 0;
            for (int k = 0; k < n; k++) {
                s[k] += alpha * p[k];
                res[k] -= alpha * ap[k];
                rn += res[k] * res[k];
            }
            double beta = rn / rr;
            rr = rn;
            for (int k = 0; k < n; k++) p[k] = res[k] + beta * p[k];
        }
        return s;
    }

    /**
     * out = (W + ZERO_PULL) v + STIFF B v, where v'Bv is the thin plate's bending energy on the grid: the squared second
     * differences across (where a cell has neighbours both sides), down, and twice the squared twist of each square of four.
     * A slope costs nothing, at the edges as inside, so a glow rising towards an edge is followed up to it.
     */
    private static void apply(double[] v, double[] w, int gx, int gy, double[] out) {
        double S = STIFF, S2 = 2 * STIFF;
        for (int k = 0; k < out.length; k++) out[k] = (w[k] + ZERO_PULL) * v[k];
        for (int y = 0; y < gy; y++) {
            for (int x = 1; x < gx - 1; x++) {
                int k = y * gx + x;
                double d = S * (v[k - 1] - 2 * v[k] + v[k + 1]);
                out[k - 1] += d;
                out[k] -= 2 * d;
                out[k + 1] += d;
            }
        }
        for (int y = 1; y < gy - 1; y++) {
            for (int x = 0; x < gx; x++) {
                int k = y * gx + x;
                double d = S * (v[k - gx] - 2 * v[k] + v[k + gx]);
                out[k - gx] += d;
                out[k] -= 2 * d;
                out[k + gx] += d;
            }
        }
        for (int y = 0; y < gy - 1; y++) {
            for (int x = 0; x < gx - 1; x++) {
                int k = y * gx + x;
                double d = S2 * (v[k] - v[k + 1] - v[k + gx] + v[k + gx + 1]);
                out[k] += d;
                out[k + 1] -= d;
                out[k + gx] -= d;
                out[k + gx + 1] += d;
            }
        }
    }

    /**
     * The residual surface per channel: what the quadratic {@code poly} leaves in the cells, followed robustly (positive
     * residuals, stars and glows, count for less) where the cells are sky ({@code band} share 0) and carried across the
     * band. Returned on the renderer's CGX x CGY grid over the crop ([(c * CGY + j) * CGX + i]); null when too few cells
     * are sky.
     */
    static float[] residual(double[][] poly, double[] cu, double[] cv, double[][] cval, boolean[] ok, float[] band, int gx, int gy,
                            int CGX, int CGY) {
        int n = gx * gy;
        double[] w0 = new double[n];
        int cells = 0;
        for (int k = 0; k < n; k++) {
            w0[k] = ok[k] ? 1.0 - (band != null ? band[k] : 0) : 0;
            if (w0[k] > 0.5) cells++;
        }
        if (cells < MIN_CELLS) return null;
        float[] out = new float[3 * CGX * CGY];
        double[] t = new double[6];
        for (int c = 0; c < 3; c++) {
            double[] r = new double[n], w = w0.clone(), s = new double[n];
            float[] lv = new float[n];
            int nl = 0;
            for (int k = 0; k < n; k++) {
                if (!ok[k]) continue;
                Renderer.terms(cu[k], cv[k], t);
                double f = 0;
                for (int q = 0; q < 6; q++) f += poly[c][q] * t[q];
                r[k] = cval[c][k] - f;
                if (w0[k] > 0.5) lv[nl++] = (float) cval[c][k];
            }
            // what's under SCALE_FLOOR of the sky's level is followed whatever its shape (a glow's slow bends): only
            // what stands clear of that and of the cells' scatter is taken for a galaxy, a bright star's halo
            double floor = SCALE_FLOOR * Math.max(1e-6, Stats.median(lv, nl));
            for (int pass = 0; pass < PASSES; pass++) {
                s = smooth(r, w, gx, gy);
                float[] ar = new float[n];
                int m = 0;
                for (int k = 0; k < n; k++) if (w0[k] > 0) ar[m++] = (float) Math.abs(r[k] - s[k]);
                if (m < 8) break;
                double sc = Math.max(floor, 1.4826 * Stats.median(ar, m));
                for (int k = 0; k < n; k++) {
                    if (w0[k] <= 0) continue;
                    double e = r[k] - s[k];
                    double cut = e > 0 ? POS_CUT * sc : 4.685 * sc;
                    double z = e / cut;
                    w[k] = Math.abs(z) < 1 ? w0[k] * (1 - z * z) * (1 - z * z) : 0;
                }
            }
            // the cells' surface onto the renderer's grid (its nodes span the crop corner to corner)
            for (int j = 0; j < CGY; j++) {
                double gyc = (j / (double) (CGY - 1)) * gy - 0.5;
                for (int i = 0; i < CGX; i++) {
                    double gxc = (i / (double) (CGX - 1)) * gx - 0.5;
                    out[(c * CGY + j) * CGX + i] = (float) bilinear(s, gx, gy, gxc, gyc);
                }
            }
        }
        return out;
    }

    /** v on a gx x gy grid at continuous cell position (x, y) (cell centres at integers), clamped at the edges. */
    static double bilinear(double[] v, int gx, int gy, double x, double y) {
        x = Math.max(0, Math.min(gx - 1, x));
        y = Math.max(0, Math.min(gy - 1, y));
        int i = Math.min(gx - 2, (int) x), j = Math.min(gy - 2, (int) y);
        double tx = x - i, ty = y - j;
        double a = v[j * gx + i] + tx * (v[j * gx + i + 1] - v[j * gx + i]);
        double b = v[(j + 1) * gx + i] + tx * (v[(j + 1) * gx + i + 1] - v[(j + 1) * gx + i]);
        return a + ty * (b - a);
    }

    /** The share of the picture the fit left to the Milky Way, and the residual surface's reach, for the log. */
    static String describe(float[] band, boolean[] ok, float[] res, int CGX, int CGY, float[] sky) {
        StringBuilder sb = new StringBuilder();
        if (band != null) {
            double s = 0;
            int n = 0;
            for (int k = 0; k < band.length; k++) {
                if (!ok[k]) continue;
                s += band[k];
                n++;
            }
            sb.append(String.format(Locale.US, " band=%.0f%%", n > 0 ? 100 * s / n : 0));
        }
        if (res != null) {
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = CGX * CGY; i < 2 * CGX * CGY; i++) {
                lo = Math.min(lo, res[i]);
                hi = Math.max(hi, res[i]);
            }
            float ref = sky != null && sky[1] > 0 ? sky[1] : 1f;
            sb.append(String.format(Locale.US, " surface=%+.1f..%+.1f%%", 100 * lo / ref, 100 * hi / ref));
        }
        return sb.toString();
    }

    private static double smooth(double x, double a, double b) {
        double t = (x - a) / (b - a);
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }

    private static double sq(double v) {
        return v * v;
    }
}
