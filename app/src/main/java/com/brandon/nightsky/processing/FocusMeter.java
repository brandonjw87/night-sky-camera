package com.brandon.nightsky.processing;

import java.util.Arrays;
import java.util.Locale;

/**
 * How big the stars are in a short focus frame (A5): the median half-flux radius (HFR) of the bright, isolated
 * stars in three crops, the centre and two opposite corners, so that a lens that is sharp in the middle and soft
 * at the edges is judged on both. Stars are found on the half-resolution green image with a box filter big enough
 * for out-of-focus discs, then measured at full resolution on all four colour planes. A sharp star covers only a
 * few pixels, too few to sum its radius up (and how its light falls on them depends on where in a pixel it
 * lands), so a Gaussian blurred by the pixels is fitted to it: one centre and size, a brightness for each colour,
 * the saturated pixels of a bright star left out. A star blurred into a disc of several pixels has its HFR (the
 * mean radius of its light) summed up directly, all four planes weighted by how much of the star each shows;
 * stars in between take a blend of the two. The median is weighted by how precisely each star was measured, so a
 * few bright stars count for more than many faint ones.
 */
public final class FocusMeter {
    /** Stars needed in a frame for its reading to count. */
    public static final int MIN_STARS = 8;
    /** Signal to noise a star needs (the HFR of fainter ones is mostly noise); a first rough look takes fainter. */
    static final double MIN_SNR = 25, GUESS_SNR = 15;
    /** Most stars measured per crop, and for a first rough look. */
    static final int MAX_PER_CROP = 150, GUESS_PER_CROP = 40;
    static final int TILE = 32;
    /** Fitted sizes (Gaussian sigma, RAW px) between which the HFR goes over from the fit to the summed radius. */
    static final double BLEND_FROM = 1.2, BLEND_TO = 2.2;
    /** Sharper than any lens draws a star: a hot pixel or a cosmic ray. */
    static final double MIN_SIGMA = 0.2;

    public static final class Reading {
        /** Stars measured. */
        public int stars;
        /** Median half-flux radius over all crops (RAW pixels), NaN without enough stars. */
        public double hfr = Double.NaN;
        /** The same in the centre crop and in the two corners. */
        public double hfrCentre = Double.NaN, hfrCorners = Double.NaN;
        /** Standard error of hfr. */
        public double noise = Double.NaN;
        /** Median star flux (ADU, all four colour planes), for comparing how clear the sky was. */
        public double flux = Double.NaN;
        /** Aperture radius used (RAW px). */
        public double aperture;
        /** Why there is no reading ("" when there is one). */
        public String why = "";
        public long millis;

        public boolean ok() {
            return stars >= MIN_STARS && !Double.isNaN(hfr);
        }

        public String describe() {
            if (!ok()) return why;
            return String.format(Locale.US, "%.2f px (+/-%.2f; %d stars; centre %.2f, corners %.2f)", hfr, noise, stars, hfrCentre,
                    hfrCorners);
        }
    }

    private FocusMeter() {
    }

    /**
     * @param raw   the frame (unsigned 16-bit, values times invScale are ADU)
     * @param black per-CFA-plane black levels of this frame
     */
    public static Reading measure(final short[] raw, final float invScale, final SensorSpec spec, final float[] black) {
        long t0 = System.nanoTime();
        Reading r = new Reading();
        int W = spec.width, H = spec.height;
        final int cs = (Math.min(W, H) / 3) & ~31;
        if (cs < 128) {
            r.why = "the frame is too small to measure";
            return r;
        }
        int m = (int) (0.04 * Math.min(W, H)) & ~1;
        final int[][] crops = {{((W - cs) / 2) & ~1, ((H - cs) / 2) & ~1}, {m, m}, {(W - cs - m) & ~1, (H - cs - m) & ~1}};
        // the crops side by side, here and in what follows
        final Crop[] cr = new Crop[3];
        Parallel.forRange(3, new Parallel.Range() {
            @Override
            public void run(int s, int e) {
                for (int c = s; c < e; c++) cr[c] = new Crop(raw, invScale, spec, black, crops[c][0], crops[c][1], cs);
            }
        });
        // a rough size with a middling aperture (or a big one for badly blurred stars), then an aperture fitted
        // to it, grown once if the stars turn out bigger
        double h = guess(cr, 2, 9, GUESS_SNR);
        if (Double.isNaN(h)) h = guess(cr, 5, 24, GUESS_SNR);
        if (Double.isNaN(h)) {
            int found = 0;
            for (Crop c : cr) found += c.found;
            r.why = found == 0 ? "no stars in the frame" : String.format(Locale.US, "too few stars to measure (%d found)", found);
            r.millis = (System.nanoTime() - t0) / 1_000_000L;
            return r;
        }
        double ap = aperture(h);
        double h2 = guess(cr, box(h), ap, MIN_SNR);
        if (!Double.isNaN(h2) && aperture(h2) > ap + 1) {
            h = h2;
            ap = aperture(h2);
        } else if (!Double.isNaN(h2)) {
            h = h2;
        }
        int rd = box(h);
        float[] all = new float[3 * MAX_PER_CROP], wt = new float[3 * MAX_PER_CROP], fl = new float[3 * MAX_PER_CROP];
        float[] corner = new float[2 * MAX_PER_CROP], cw = new float[2 * MAX_PER_CROP];
        int n = 0, nc = 0;
        double centre = Double.NaN;
        long[] scratch = new long[3 * MAX_PER_CROP];
        measureAll(cr, rd, ap, MIN_SNR, MAX_PER_CROP);
        for (int c = 0; c < 3; c++) {
            Crop k = cr[c];
            for (int i = 0; i < k.n; i++) {
                all[n] = k.hfr[i];
                wt[n] = k.wt[i];
                fl[n++] = k.flux[i];
                if (c > 0) {
                    corner[nc] = k.hfr[i];
                    cw[nc++] = k.wt[i];
                }
            }
            if (c == 0 && k.n >= 3) centre = Stats.weightedMedian(Arrays.copyOf(k.hfr, k.n), Arrays.copyOf(k.wt, k.n), k.n, scratch);
        }
        r.stars = n;
        r.aperture = ap;
        r.millis = (System.nanoTime() - t0) / 1_000_000L;
        if (n < MIN_STARS) {
            r.why = String.format(Locale.US, "too few stars to measure (%d)", n);
            return r;
        }
        float med = Stats.weightedMedian(Arrays.copyOf(all, n), Arrays.copyOf(wt, n), n, scratch);
        // the error of a weighted median: from the stars' own precision, or their scatter if that's bigger
        double sw = 0, swd = 0;
        for (int i = 0; i < n; i++) {
            sw += wt[i];
            swd += wt[i] * Math.abs(all[i] - med);
        }
        double formal = 1.2533 / Math.sqrt(sw);
        double spread = 1.2533 * 1.2533 * (swd / sw) / Math.sqrt(n);
        r.hfr = med;
        r.noise = Math.max(Math.max(formal, spread), 0.005 * med);
        r.hfrCentre = centre;
        r.hfrCorners = nc >= 3 ? Stats.weightedMedian(Arrays.copyOf(corner, nc), Arrays.copyOf(cw, nc), nc, scratch) : Double.NaN;
        r.flux = Stats.median(Arrays.copyOf(fl, n), n);
        return r;
    }

    /** Aperture radius (RAW px) for stars of half-flux radius h (RAW px): about two and a half radii. */
    static double aperture(double h) {
        return Stats.clamp(2.5 * h + 2, 5, 30);
    }

    /** Detection box radius (half-res px): about half a blurred disc's radius (1.5 h), a matched filter for it. */
    static int box(double h) {
        return Stats.clamp((int) Math.round(0.375 * h), 1, 8);
    }

    /** The HFR (RAW px) of a Gaussian of the given sigma blurred by its pixels: its mean radius. */
    static double hfrOfSigma(double sigma) {
        return Math.sqrt(Math.PI / 2) * Math.sqrt(sigma * sigma + 1 / 12.0);
    }

    /** Weighted median HFR (RAW px) of the brightest stars found with box radius rd and aperture ap, NaN if too few. */
    private static double guess(Crop[] cr, int rd, double ap, double snr) {
        float[] v = new float[3 * GUESS_PER_CROP], w = new float[3 * GUESS_PER_CROP];
        int n = 0;
        measureAll(cr, rd, ap, snr, GUESS_PER_CROP);
        for (Crop c : cr) {
            for (int i = 0; i < c.n; i++) {
                v[n] = c.hfr[i];
                w[n++] = c.wt[i];
            }
        }
        if (n < Math.max(4, MIN_STARS / 2)) return Double.NaN;
        return Stats.weightedMedian(v, w, n, new long[n]);
    }

    /** Up to limit stars measured in each crop (see {@link Crop#measure}), the crops side by side. */
    private static void measureAll(final Crop[] cr, final int rd, final double ap, final double snr, final int limit) {
        Parallel.forRange(cr.length, new Parallel.Range() {
            @Override
            public void run(int s, int e) {
                for (int c = s; c < e; c++) cr[c].measure(rd, ap, snr, limit);
            }
        });
    }

    /** What measuring one star came to. */
    private static final int OK = 0, FAINT = 1, NO = 2;

    /** One crop: its half-resolution green image, background and noise, and the stars measured in it. */
    private static final class Crop {
        final short[] raw;
        final float invScale;
        final int W, x0, y0, w;
        final float blackG;
        /** Per CFA plane: black level and the level counted as saturated. */
        final float[] blk = new float[4], sat = new float[4];
        final float[] g, bg;
        final float[] tileSig;
        final int tn;
        final float[] hfr = new float[MAX_PER_CROP], flux = new float[MAX_PER_CROP], wt = new float[MAX_PER_CROP];
        int n, found;
        final Fit fit;
        final float[][] ring = new float[4][64];
        final float[] lb = new float[4], ls = new float[4];

        Crop(short[] raw, float invScale, SensorSpec spec, float[] black, int x0, int y0, int cs) {
            this.raw = raw;
            this.invScale = invScale;
            W = spec.width;
            this.x0 = x0;
            this.y0 = y0;
            w = cs / 2;
            int gp1 = -1, gp2 = -1;
            for (int p = 0; p < 4; p++) {
                if (spec.cfa[p] == 1) {
                    if (gp1 < 0) gp1 = p;
                    else gp2 = p;
                }
            }
            if (gp1 < 0) gp1 = 0;
            if (gp2 < 0) gp2 = gp1;
            float[] bl = black != null ? black : spec.black;
            for (int p = 0; p < 4; p++) {
                blk[p] = bl[p];
                sat[p] = spec.white - 0.04f * (spec.white - bl[p]);
            }
            fit = new Fit(spec.cfa);
            blackG = 0.5f * (bl[gp1] + bl[gp2]);
            g = new float[w * w];
            int ox1 = gp1 & 1, oy1 = gp1 >> 1, ox2 = gp2 & 1, oy2 = gp2 >> 1;
            for (int j = 0; j < w; j++) {
                int r1 = (y0 + 2 * j + oy1) * W + x0 + ox1, r2 = (y0 + 2 * j + oy2) * W + x0 + ox2;
                for (int i = 0; i < w; i++) {
                    g[j * w + i] = ((raw[r1 + 2 * i] & 0xFFFF) + (raw[r2 + 2 * i] & 0xFFFF)) * 0.5f * invScale - blackG;
                }
            }
            // background: tile medians, bilinear; noise: tile MADs
            tn = w / TILE;
            float[] tb = new float[tn * tn];
            tileSig = new float[tn * tn];
            float[] buf = new float[TILE * TILE];
            for (int ty = 0; ty < tn; ty++) {
                for (int tx = 0; tx < tn; tx++) {
                    int k = 0;
                    for (int j = ty * TILE; j < (ty + 1) * TILE; j++) for (int i = tx * TILE; i < (tx + 1) * TILE; i++) buf[k++] = g[j * w + i];
                    float md = Stats.median(buf, k);
                    k = 0;
                    for (int j = ty * TILE; j < (ty + 1) * TILE; j++) for (int i = tx * TILE; i < (tx + 1) * TILE; i++) buf[k++] = g[j * w + i];
                    tb[ty * tn + tx] = md;
                    tileSig[ty * tn + tx] = Math.max(0.15f, Stats.madSigma(buf, k, md));
                }
            }
            bg = new float[w * w];
            for (int j = 0; j < w; j++) {
                double gy = (j + 0.5) / TILE - 0.5;
                int ty = Stats.clamp((int) Math.floor(gy), 0, Math.max(0, tn - 2));
                double fy = tn > 1 ? Stats.clamp(gy - ty, 0, 1) : 0;
                int ty1 = Math.min(ty + 1, tn - 1);
                for (int i = 0; i < w; i++) {
                    double gx = (i + 0.5) / TILE - 0.5;
                    int tx = Stats.clamp((int) Math.floor(gx), 0, Math.max(0, tn - 2));
                    double fx = tn > 1 ? Stats.clamp(gx - tx, 0, 1) : 0;
                    int tx1 = Math.min(tx + 1, tn - 1);
                    double a = tb[ty * tn + tx] * (1 - fx) + tb[ty * tn + tx1] * fx;
                    double b = tb[ty1 * tn + tx] * (1 - fx) + tb[ty1 * tn + tx1] * fx;
                    bg[j * w + i] = (float) (a * (1 - fy) + b * fy);
                }
            }
            for (int k = 0; k < g.length; k++) g[k] -= bg[k];
        }

        float sigmaAt(int i, int j) {
            return tileSig[Stats.clamp(j / TILE, 0, tn - 1) * tn + Stats.clamp(i / TILE, 0, tn - 1)];
        }

        /**
         * Finds the brightest isolated stars with a box of radius rd (half-res px) and measures up to limit of
         * them in an aperture of radius ap (RAW px).
         */
        void measure(int rd, double ap, double minSnr, int limit) {
            n = 0;
            // half-res reach of the aperture and its background ring
            int ra = (int) Math.ceil((ap + 8) / 2) + 1;
            int w1 = w + 1;
            double[] I = new double[w1 * w1];
            for (int j = 0; j < w; j++) {
                double row = 0;
                for (int i = 0; i < w; i++) {
                    row += g[j * w + i];
                    I[(j + 1) * w1 + i + 1] = I[j * w1 + i + 1] + row;
                }
            }
            int edge = ra + 2, side = 2 * rd + 1;
            float[] S = new float[w * w];
            for (int j = rd; j < w - rd; j++) {
                for (int i = rd; i < w - rd; i++) {
                    S[j * w + i] = (float) (I[(j + rd + 1) * w1 + i + rd + 1] - I[(j - rd) * w1 + i + rd + 1]
                            - I[(j + rd + 1) * w1 + i - rd] + I[(j - rd) * w1 + i - rd]);
                }
            }
            // local maxima of the box sums above 8 sigma, about an aperture apart (a blurred disc's flat top gives
            // only one)
            int sup = Math.max(rd + 1, (int) Math.ceil(ap / 2));
            int cap = 4 * MAX_PER_CROP;
            long[] keys = new long[cap];
            int nk = 0;
            for (int j = edge; j < w - edge; j++) {
                for (int i = edge; i < w - edge; i++) {
                    float s = S[j * w + i];
                    if (s <= 8 * sigmaAt(i, j) * side) continue;
                    boolean max = true;
                    for (int dj = -sup; dj <= sup && max; dj++) {
                        int o = (j + dj) * w;
                        if (j + dj < 0 || j + dj >= w) continue;
                        for (int di = -sup; di <= sup; di++) {
                            if (di == 0 && dj == 0) continue;
                            int x = i + di;
                            if (x < 0 || x >= w) continue;
                            float t = S[o + x];
                            if (t > s || (t == s && (dj < 0 || (dj == 0 && di < 0)))) {
                                max = false;
                                break;
                            }
                        }
                    }
                    if (!max) continue;
                    long key = ((long) Float.floatToIntBits(s) << 32) | (j * w + i);
                    if (nk < cap) {
                        keys[nk++] = key;
                    } else {
                        // keep the brightest: replace the faintest kept
                        int lo = 0;
                        for (int q = 1; q < nk; q++) if (keys[q] < keys[lo]) lo = q;
                        if (key > keys[lo]) keys[lo] = key;
                    }
                }
            }
            found = nk;
            Arrays.sort(keys, 0, nk);
            // brightest first; positions in half-res pixels
            int[] ci = new int[nk], cj = new int[nk];
            float[] cs2 = new float[nk];
            for (int q = 0; q < nk; q++) {
                long key = keys[nk - 1 - q];
                int idx = (int) (key & 0xFFFFFFFFL);
                ci[q] = idx % w;
                cj[q] = idx / w;
                cs2[q] = Float.intBitsToFloat((int) (key >>> 32));
            }
            double[] res = new double[4];
            // isolated: no other star within the aperture and its ring brighter than a tenth of this one
            int iso = (int) Math.ceil((ap + 8) / 2);
            int faint = 0;
            for (int q = 0; q < nk && n < limit; q++) {
                boolean crowded = false;
                for (int p = 0; p < nk && !crowded; p++) {
                    if (p == q) continue;
                    int dx = ci[p] - ci[q], dy = cj[p] - cj[q];
                    if (dx * dx + dy * dy < iso * iso && cs2[p] > 0.1f * cs2[q]) crowded = true;
                }
                if (crowded) continue;
                int got = star(ci[q], cj[q], ap, minSnr, res);
                // brightest first: once a run of them is too faint, so are all the rest
                if (got == FAINT && ++faint >= 12) break;
                if (got != OK) continue;
                faint = 0;
                hfr[n] = (float) res[0];
                flux[n] = (float) res[1];
                // weight: 1 / variance of this star's HFR, with a floor for what noise can't explain
                double sh = Math.max(res[3], 0.02 * res[0]);
                wt[n++] = (float) (1 / (sh * sh));
            }
        }

        /**
         * Measures one star near half-res pixel (i, j) in an aperture of radius ap (RAW px): {HFR in RAW px, flux
         * (ADU, all planes), snr, HFR error} in res. Returns OK, FAINT (below minSnr) or NO (a hot pixel or a
         * cosmic ray, at the edge, or not a single star).
         */
        int star(int i, int j, double ap, double minSnr, double[] res) {
            // centre: flux-weighted over the half-res image
            int rh = (int) Math.ceil(ap / 2);
            double cx = i, cy = j;
            for (int it = 0; it < 3; it++) {
                double sw = 0, sx = 0, sy = 0;
                int ia = (int) Math.round(cx), ja = (int) Math.round(cy);
                for (int dj = -rh; dj <= rh; dj++) {
                    for (int di = -rh; di <= rh; di++) {
                        if (di * di + dj * dj > rh * rh) continue;
                        int x = ia + di, y = ja + dj;
                        if (x < 0 || y < 0 || x >= w || y >= w) return NO;
                        float v = g[y * w + x];
                        if (v <= 0) continue;
                        sw += v;
                        sx += v * x;
                        sy += v * y;
                    }
                }
                if (sw <= 0) return NO;
                cx = sx / sw;
                cy = sy / sw;
                if (Math.abs(cx - i) > rh + 1 || Math.abs(cy - j) > rh + 1) return NO;
            }
            // full-resolution coordinates, pixel x spanning x to x + 1
            double X0 = x0 + 2 * cx + 1.0, Y0 = y0 + 2 * cy + 1.0;
            double r1 = ap + 2, r2 = ap + 7;
            int ext = (int) Math.ceil(r2) + 1;
            if (X0 - ext < x0 || Y0 - ext < y0 || X0 + ext >= x0 + 2 * w || Y0 + ext >= y0 + 2 * w) return NO;
            // each plane's local background and noise: the median and spread of a ring outside the aperture
            int cap = (int) (Math.PI * (r2 * r2 - r1 * r1) / 4 + 64);
            if (ring[0].length < cap) for (int p = 0; p < 4; p++) ring[p] = new float[cap];
            int[] nr = new int[4];
            int xa = (int) Math.floor(X0 - r2), xb = (int) Math.ceil(X0 + r2), ya = (int) Math.floor(Y0 - r2), yb = (int) Math.ceil(Y0 + r2);
            for (int y = ya; y <= yb; y++) {
                double dy = y + 0.5 - Y0;
                for (int x = xa; x <= xb; x++) {
                    double dx = x + 0.5 - X0, d2 = dx * dx + dy * dy;
                    if (d2 < r1 * r1 || d2 > r2 * r2) continue;
                    int p = ((y & 1) << 1) | (x & 1);
                    if (nr[p] < cap) ring[p][nr[p]++] = (raw[y * W + x] & 0xFFFF) * invScale - blk[p];
                }
            }
            for (int p = 0; p < 4; p++) {
                if (nr[p] < 6) return NO;
                lb[p] = Stats.median(ring[p], nr[p]);
                ls[p] = Math.max(0.1f, Stats.madSigma(ring[p], nr[p], lb[p]));
            }
            // the pixels within the aperture less their plane's background; saturated ones left out
            int xA = (int) Math.floor(X0 - ap), xB = (int) Math.ceil(X0 + ap), yA = (int) Math.floor(Y0 - ap), yB = (int) Math.ceil(Y0 + ap);
            Fit f = fit;
            f.start(xB - xA + 1, yB - yA + 1);
            int nsat = 0;
            for (int y = yA; y <= yB; y++) {
                double dy = y + 0.5 - Y0;
                for (int x = xA; x <= xB; x++) {
                    double dx = x + 0.5 - X0;
                    if (dx * dx + dy * dy > ap * ap) continue;
                    int p = ((y & 1) << 1) | (x & 1);
                    float val = (raw[y * W + x] & 0xFFFF) * invScale;
                    if (val >= sat[p]) {
                        nsat++;
                        continue;
                    }
                    f.add(x - xA, y - yA, p, val - blk[p] - lb[p], 1.0 / (ls[p] * ls[p]));
                }
            }
            if (f.n < 12) return NO;
            // a first size, the centre where the centroid put it; then the fit (not needed for a big disc)
            double s0 = f.bestSize(X0 - xA, Y0 - yA, Math.max(0.6, ap / 2));
            boolean fitted = s0 < BLEND_TO + 0.4;
            double X = X0, Y = Y0, h = Double.NaN, eh = 0, fl = 0, snr = 0, s = s0;
            if (fitted) {
                if (!f.run(X0 - xA, Y0 - yA, s0)) return NO;
                s = f.p[2];
                if (s < MIN_SIGMA) return NO;
                X = xA + f.p[0];
                Y = yA + f.p[1];
                if (Math.abs(X - X0) > 2 || Math.abs(Y - Y0) > 2 || s > ap / 2) return NO;
                double[] err = f.errors();
                fl = f.flux();
                if (!(fl > 0)) return NO;
                snr = fl / err[1];
                h = hfrOfSigma(s);
                eh = Math.sqrt(Math.PI / 2) * s / Math.sqrt(s * s + 1 / 12.0) * err[0];
            }
            // blurred over several pixels: the mean radius of the light summed up (all of it: no saturated pixels)
            double wm = fitted ? Stats.clamp((s - BLEND_FROM) / (BLEND_TO - BLEND_FROM), 0, 1) : 1;
            if (wm > 0 && nsat == 0 && f.moments(X - xA, Y - yA, ap)) {
                if (fitted) {
                    h = (1 - wm) * h + wm * f.mr;
                    eh = (1 - wm) * eh + wm * f.mrErr;
                } else {
                    h = f.mr;
                    eh = f.mrErr;
                    fl = f.mFlux;
                    snr = f.mSnr;
                }
            } else if (!fitted) {
                return NO;
            }
            if (snr < minSnr) return FAINT;
            if (!(h > 0) || h > ap) return NO;
            res[0] = h;
            res[1] = fl;
            res[2] = snr;
            // a saturated star's size comes from its wings alone: counted for less
            res[3] = eh * (1 + nsat);
            return OK;
        }
    }

    /**
     * A star's pixels on all four planes and what's fitted to them: a Gaussian blurred by the pixels (each pixel
     * holds the Gaussian's integral over its square), with one centre and size and a brightness for each colour,
     * by Levenberg-Marquardt; or, for a star blurred over many pixels, the mean radius of its light.
     */
    private static final class Fit {
        static final double RT2PI = Math.sqrt(2 * Math.PI), RT2 = Math.sqrt(2);
        /** Each plane's colour (index of its brightness), how many colours, and how many of the planes each covers. */
        final int[] colour = new int[4];
        final int colours, np;
        final double[] share = new double[4];
        int n, cols, rows;
        int[] px = new int[256], py = new int[256], pl = new int[256];
        double[] v = new double[256], iw = new double[256];
        double[] ex = new double[64], dxe = new double[64], dse = new double[64];
        double[] ey = new double[64], dye = new double[64], dsy = new double[64];
        /** Centre X and Y (RAW px from the window's corner), sigma, then a brightness per colour (each plane's total if it covered every pixel). */
        final double[] p = new double[7];
        final double[] A, b, M, step, trial;
        final double[] sve = new double[4], see = new double[4];
        double chi2;
        /** From moments(): the mean radius, its error, the flux and its signal to noise. */
        double mr, mrErr, mFlux, mSnr;

        Fit(int[] cfa) {
            int nc = 0;
            int[] seen = new int[4];
            for (int q = 0; q < 4; q++) {
                int k = 0;
                while (k < nc && seen[k] != cfa[q]) k++;
                if (k == nc) seen[nc++] = cfa[q];
                colour[q] = k;
                share[k] += 0.25;
            }
            colours = nc;
            np = 3 + nc;
            A = new double[np * np];
            M = new double[np * np];
            b = new double[np];
            step = new double[np];
            trial = new double[np];
        }

        void start(int cols, int rows) {
            n = 0;
            this.cols = cols;
            this.rows = rows;
            int m = Math.max(cols, rows);
            if (ex.length < m) {
                ex = new double[m];
                dxe = new double[m];
                dse = new double[m];
                ey = new double[m];
                dye = new double[m];
                dsy = new double[m];
            }
            if (px.length < cols * rows) {
                int k = cols * rows;
                px = new int[k];
                py = new int[k];
                pl = new int[k];
                v = new double[k];
                iw = new double[k];
            }
        }

        void add(int x, int y, int plane, double value, double weight) {
            px[n] = x;
            py[n] = y;
            pl[n] = plane;
            v[n] = value;
            iw[n++] = weight;
        }

        /** The share of a unit Gaussian at c with sigma s in each of m pixels from 0, and its derivatives. */
        static void side(double c, double s, int m, double[] e, double[] de, double[] ds, boolean derivs) {
            double inv = 1 / s;
            double t0 = -c, z0 = t0 * inv;
            double cdf0 = cdf(z0), g0 = Math.exp(-0.5 * z0 * z0);
            for (int k = 0; k < m; k++) {
                double t1 = k + 1 - c, z1 = t1 * inv;
                double cdf1 = cdf(z1), g1 = Math.exp(-0.5 * z1 * z1);
                e[k] = cdf1 - cdf0;
                if (derivs) {
                    // d/dc and d/ds of the integral from t0 to t1
                    de[k] = (g0 - g1) * inv / RT2PI;
                    ds[k] = -(t1 * g1 - t0 * g0) * inv * inv / RT2PI;
                }
                t0 = t1;
                cdf0 = cdf1;
                g0 = g1;
            }
        }

        void profile(double X, double Y, double s, boolean derivs) {
            side(X, s, cols, ex, dxe, dse, derivs);
            side(Y, s, rows, ey, dye, dsy, derivs);
        }

        /** With the centre and size given, the best brightness per colour (into p); returns how much of the pixels' variance it explains. */
        double project(double X, double Y, double s) {
            profile(X, Y, s, false);
            Arrays.fill(sve, 0);
            Arrays.fill(see, 0);
            for (int k = 0; k < n; k++) {
                double e = ex[px[k]] * ey[py[k]], we = iw[k] * e;
                int c = colour[pl[k]];
                sve[c] += we * v[k];
                see[c] += we * e;
            }
            double q = 0;
            for (int c = 0; c < colours; c++) {
                double a = see[c] > 0 ? sve[c] / see[c] : 0;
                p[3 + c] = a;
                q += a * sve[c];
            }
            return q;
        }

        /** The best of a range of sizes up to smax with the centre held, its brightnesses left in p. */
        double bestSize(double X, double Y, double smax) {
            double best = -Double.MAX_VALUE, bs = 0.5;
            for (double s = 0.3; s <= smax * 1.0001; s *= 1.35) {
                double q = project(X, Y, s);
                if (q > best) {
                    best = q;
                    bs = s;
                }
            }
            project(X, Y, bs);
            return bs;
        }

        double chi(double[] q) {
            profile(q[0], q[1], q[2], false);
            double c2 = 0;
            for (int k = 0; k < n; k++) {
                double r = v[k] - q[3 + colour[pl[k]]] * ex[px[k]] * ey[py[k]];
                c2 += iw[k] * r * r;
            }
            return c2;
        }

        /** The normal equations at q: A = J'WJ, b = J'W(v - model). */
        void normal(double[] q) {
            profile(q[0], q[1], q[2], true);
            Arrays.fill(A, 0);
            Arrays.fill(b, 0);
            for (int k = 0; k < n; k++) {
                int x = px[k], y = py[k], ia = 3 + colour[pl[k]];
                double Ex = ex[x], Ey = ey[y], e = Ex * Ey, a = q[ia], w = iw[k];
                double jx = a * dxe[x] * Ey, jy = a * Ex * dye[y], js = a * (dse[x] * Ey + Ex * dsy[y]);
                double r = v[k] - a * e;
                double wx = w * jx, wy = w * jy, ws = w * js, wa = w * e;
                A[0] += wx * jx;
                A[1] += wx * jy;
                A[2] += wx * js;
                A[ia] += wx * e;
                A[np + 1] += wy * jy;
                A[np + 2] += wy * js;
                A[np + ia] += wy * e;
                A[2 * np + 2] += ws * js;
                A[2 * np + ia] += ws * e;
                A[ia * np + ia] += wa * e;
                b[0] += wx * r;
                b[1] += wy * r;
                b[2] += ws * r;
                b[ia] += wa * r;
            }
            for (int i = 0; i < np; i++) {
                for (int k = i + 1; k < np; k++) A[k * np + i] = A[i * np + k];
                // a colour with no pixels (all saturated): its brightness stays put
                if (A[i * np + i] <= 0) A[i * np + i] = 1;
            }
        }

        /** Levenberg-Marquardt from centre (X, Y) and size s, brightnesses as project() left them; false if it doesn't settle. */
        boolean run(double X, double Y, double s) {
            p[0] = X;
            p[1] = Y;
            p[2] = s;
            double c0 = chi(p), lambda = 1e-3;
            for (int it = 0; it < 40; it++) {
                normal(p);
                boolean moved = false, small = false;
                while (lambda < 1e9) {
                    for (int i = 0; i < np * np; i++) M[i] = A[i];
                    for (int i = 0; i < np; i++) M[i * np + i] *= 1 + lambda;
                    if (!cholesky(M, np)) {
                        lambda *= 10;
                        continue;
                    }
                    solve(M, np, b, step);
                    // at most a pixel for the centre and a factor of two for the size at a time
                    for (int i = 0; i < np; i++) trial[i] = p[i] + step[i];
                    trial[0] = p[0] + Stats.clamp(step[0], -1, 1);
                    trial[1] = p[1] + Stats.clamp(step[1], -1, 1);
                    trial[2] = Stats.clamp(p[2] + step[2], Math.max(0.05, 0.5 * p[2]), 2 * p[2]);
                    double c1 = chi(trial);
                    if (c1 < c0) {
                        small = Math.abs(trial[0] - p[0]) < 1e-3 && Math.abs(trial[1] - p[1]) < 1e-3 && Math.abs(trial[2] - p[2]) < 1e-3 * p[2];
                        System.arraycopy(trial, 0, p, 0, np);
                        c0 = c1;
                        lambda = Math.max(lambda * 0.1, 1e-9);
                        moved = true;
                        break;
                    }
                    lambda *= 10;
                }
                chi2 = c0;
                // nothing lowers chi-square any more, or it barely moves: there; or shrinking to a point
                if (!moved || small || p[2] < 0.1) return true;
                if (p[0] < -2 || p[1] < -2 || p[0] > cols + 2 || p[1] > rows + 2) return false;
            }
            return false;
        }

        /** The total flux: each colour's brightness times the share of the pixels it covers. */
        double flux() {
            double f = 0;
            for (int c = 0; c < colours; c++) f += share[c] * p[3 + c];
            return f;
        }

        /**
         * Standard errors of sigma and of the flux at the fit, scaled up by the scatter about it when that's
         * more than the background noise explains (the star's own noise, a shape that isn't quite Gaussian).
         */
        double[] errors() {
            normal(p);
            System.arraycopy(A, 0, M, 0, np * np);
            if (!cholesky(M, np)) return new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double scale = Math.max(1, chi2 / Math.max(1, n - np));
            Arrays.fill(trial, 0);
            trial[2] = 1;
            solve(M, np, trial, step);
            double vs = step[2];
            Arrays.fill(trial, 0);
            for (int c = 0; c < colours; c++) trial[3 + c] = share[c];
            solve(M, np, trial, step);
            double vf = 0;
            for (int i = 0; i < np; i++) vf += trial[i] * step[i];
            return new double[]{Math.sqrt(Math.max(0, vs * scale)), Math.sqrt(Math.max(0, vf * scale))};
        }

        /**
         * The mean radius of the light within ap of (X, Y) (window px), each plane weighted by its colour's
         * brightness over its noise (a plane that hardly shows the star adds only noise), after recentring on
         * the light; with its error, flux and signal to noise. False if there's no light.
         */
        boolean moments(double X, double Y, double ap) {
            double[] kap = new double[4];
            for (int q = 0; q < 4; q++) kap[q] = Math.max(0, p[3 + colour[q]]);
            double ap2 = ap * ap;
            for (int it = 0; it < 2; it++) {
                double sw = 0, sx = 0, sy = 0;
                for (int k = 0; k < n; k++) {
                    double dx = px[k] + 0.5 - X, dy = py[k] + 0.5 - Y;
                    if (v[k] <= 0 || dx * dx + dy * dy > ap2) continue;
                    double kv = kap[pl[k]] * iw[k] * v[k];
                    sw += kv;
                    sx += kv * dx;
                    sy += kv * dy;
                }
                if (!(sw > 0)) return false;
                X += sx / sw;
                Y += sy / sw;
            }
            double sv = 0, svr = 0, sn = 0, fsum = 0;
            for (int k = 0; k < n; k++) {
                double dx = px[k] + 0.5 - X, dy = py[k] + 0.5 - Y, d2 = dx * dx + dy * dy;
                if (d2 > ap2) continue;
                double kw = kap[pl[k]] * iw[k], kv = kw * v[k];
                sv += kv;
                svr += kv * Math.sqrt(d2);
                sn += kw * kw / iw[k];
                fsum += v[k];
            }
            if (!(sv > 0)) return false;
            mr = svr / sv;
            // d(mr) = sum(k (r - mr) dv) / sum(k v)
            double e2 = 0;
            for (int k = 0; k < n; k++) {
                double dx = px[k] + 0.5 - X, dy = py[k] + 0.5 - Y, d2 = dx * dx + dy * dy;
                if (d2 > ap2) continue;
                double kw = kap[pl[k]] * iw[k], dr = Math.sqrt(d2) - mr;
                e2 += kw * kw / iw[k] * dr * dr;
            }
            mrErr = Math.sqrt(e2) / sv;
            mFlux = fsum;
            mSnr = sv / Math.sqrt(sn);
            return true;
        }

        /** The normal CDF (Abramowitz and Stegun 7.1.26 for erf, good to 1.5e-7). */
        static double cdf(double z) {
            double x = Math.abs(z) / RT2;
            double t = 1 / (1 + 0.3275911 * x);
            double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
            return z >= 0 ? 0.5 * (1 + y) : 0.5 * (1 - y);
        }

        /** In-place Cholesky factor (lower triangle) of the n x n matrix m; false if it isn't positive definite. */
        static boolean cholesky(double[] m, int n) {
            for (int j = 0; j < n; j++) {
                double d = m[j * n + j];
                for (int k = 0; k < j; k++) d -= m[j * n + k] * m[j * n + k];
                if (!(d > 0)) return false;
                d = Math.sqrt(d);
                m[j * n + j] = d;
                for (int i = j + 1; i < n; i++) {
                    double s = m[i * n + j];
                    for (int k = 0; k < j; k++) s -= m[i * n + k] * m[j * n + k];
                    m[i * n + j] = s / d;
                }
            }
            return true;
        }

        /** Solves L L' x = r with the factor from cholesky(). */
        static void solve(double[] m, int n, double[] r, double[] x) {
            for (int i = 0; i < n; i++) {
                double s = r[i];
                for (int k = 0; k < i; k++) s -= m[i * n + k] * x[k];
                x[i] = s / m[i * n + i];
            }
            for (int i = n - 1; i >= 0; i--) {
                double s = x[i];
                for (int k = i + 1; k < n; k++) s -= m[k * n + i] * x[k];
                x[i] = s / m[i * n + i];
            }
        }
    }
}
