package com.brandon.nightsky.processing;

import java.util.Arrays;
import java.util.Locale;

/**
 * How big the stars are in a short focus frame (A5): the median half-flux radius (HFR) of the bright, isolated,
 * unsaturated stars in three crops, the centre and two opposite corners, so that a lens that is sharp in the
 * middle and soft at the edges is judged on both. Stars are found on the half-resolution green image with a box
 * filter big enough for out-of-focus discs, then measured on the green pixels at full resolution (finer sampling
 * near focus): HFR = sum(v r) / sum(v) inside an aperture of about two and a half star radii, the local
 * background from a ring around it. The median is weighted by how precisely each star was measured, so a few
 * bright stars count for more than many faint ones. Works for sharp stars and for stars blurred into discs
 * several times their size.
 */
public final class FocusMeter {
    /** Stars needed in a frame for its reading to count. */
    public static final int MIN_STARS = 8;
    /** Signal to noise a star needs (the HFR of fainter ones is mostly noise); a first rough look takes fainter. */
    static final double MIN_SNR = 25, GUESS_SNR = 15;
    /** Most stars measured per crop. */
    static final int MAX_PER_CROP = 150;
    static final int TILE = 32;

    public static final class Reading {
        /** Stars measured. */
        public int stars;
        /** Median half-flux radius over all crops (RAW pixels), NaN without enough stars. */
        public double hfr = Double.NaN;
        /** The same in the centre crop and in the two corners. */
        public double hfrCentre = Double.NaN, hfrCorners = Double.NaN;
        /** Standard error of hfr. */
        public double noise = Double.NaN;
        /** Median star flux (ADU), for comparing how clear the sky was. */
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
    public static Reading measure(short[] raw, float invScale, SensorSpec spec, float[] black) {
        long t0 = System.nanoTime();
        Reading r = new Reading();
        int W = spec.width, H = spec.height;
        int cs = (Math.min(W, H) / 3) & ~31;
        if (cs < 128) {
            r.why = "the frame is too small to measure";
            return r;
        }
        int m = (int) (0.04 * Math.min(W, H)) & ~1;
        int[][] crops = {{((W - cs) / 2) & ~1, ((H - cs) / 2) & ~1}, {m, m}, {(W - cs - m) & ~1, (H - cs - m) & ~1}};
        Crop[] cr = new Crop[3];
        for (int c = 0; c < 3; c++) cr[c] = new Crop(raw, invScale, spec, black, crops[c][0], crops[c][1], cs);
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
        for (int c = 0; c < 3; c++) {
            Crop k = cr[c];
            k.measure(rd, ap, MIN_SNR);
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

    /** Weighted median HFR (RAW px) of the stars found with box radius rd and aperture ap, NaN if too few. */
    private static double guess(Crop[] cr, int rd, double ap, double snr) {
        float[] v = new float[3 * MAX_PER_CROP], w = new float[3 * MAX_PER_CROP];
        int n = 0;
        for (Crop c : cr) {
            c.measure(rd, ap, snr);
            for (int i = 0; i < c.n; i++) {
                v[n] = c.hfr[i];
                w[n++] = c.wt[i];
            }
        }
        if (n < Math.max(4, MIN_STARS / 2)) return Double.NaN;
        return Stats.weightedMedian(v, w, n, new long[n]);
    }

    /** One crop: its half-resolution green image, background and noise, and the stars measured in it. */
    private static final class Crop {
        final short[] raw;
        final float invScale;
        final int W, x0, y0, w;
        final int gp1, gp2;
        final float blackG, satLevel;
        final float[] g, bg;
        final float[] tileSig;
        final int tn;
        final float[] hfr = new float[MAX_PER_CROP], flux = new float[MAX_PER_CROP], wt = new float[MAX_PER_CROP];
        int n, found;

        Crop(short[] raw, float invScale, SensorSpec spec, float[] black, int x0, int y0, int cs) {
            this.raw = raw;
            this.invScale = invScale;
            W = spec.width;
            this.x0 = x0;
            this.y0 = y0;
            w = cs / 2;
            int g1 = -1, g2 = -1;
            for (int p = 0; p < 4; p++) {
                if (spec.cfa[p] == 1) {
                    if (g1 < 0) g1 = p;
                    else g2 = p;
                }
            }
            if (g1 < 0) g1 = 0;
            if (g2 < 0) g2 = g1;
            gp1 = g1;
            gp2 = g2;
            float[] bl = black != null ? black : spec.black;
            blackG = 0.5f * (bl[gp1] + bl[gp2]);
            satLevel = spec.white - 0.04f * (spec.white - blackG);
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
         * Finds the brightest isolated stars with a box of radius rd (half-res px) and measures each in an
         * aperture of radius ap (RAW px).
         */
        void measure(int rd, double ap, double minSnr) {
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
            for (int q = 0; q < nk && n < MAX_PER_CROP; q++) {
                boolean crowded = false;
                for (int p = 0; p < nk && !crowded; p++) {
                    if (p == q) continue;
                    int dx = ci[p] - ci[q], dy = cj[p] - cj[q];
                    if (dx * dx + dy * dy < iso * iso && cs2[p] > 0.1f * cs2[q]) crowded = true;
                }
                if (crowded) continue;
                if (!star(ci[q], cj[q], ap, minSnr, res)) continue;
                hfr[n] = (float) res[0];
                flux[n] = (float) res[1];
                // weight: 1 / variance of this star's HFR, with a floor for what noise can't explain
                double sh = Math.max(res[3], 0.02 * res[0]);
                wt[n++] = (float) (1 / (sh * sh));
            }
        }

        /**
         * Measures one star near half-res pixel (i, j) in an aperture of radius ap (RAW px): {HFR in RAW px, flux,
         * snr, HFR error} in res; false if it can't be measured (saturated, too faint, a hot pixel, at the edge).
         */
        boolean star(int i, int j, double ap, double minSnr, double[] res) {
            // centre: flux-weighted over the half-res image, then on the green pixels at full resolution
            int rh = (int) Math.ceil(ap / 2);
            double cx = i, cy = j;
            for (int it = 0; it < 3; it++) {
                double sw = 0, sx = 0, sy = 0;
                int ia = (int) Math.round(cx), ja = (int) Math.round(cy);
                for (int dj = -rh; dj <= rh; dj++) {
                    for (int di = -rh; di <= rh; di++) {
                        if (di * di + dj * dj > rh * rh) continue;
                        int x = ia + di, y = ja + dj;
                        if (x < 0 || y < 0 || x >= w || y >= w) return false;
                        float v = g[y * w + x];
                        if (v <= 0) continue;
                        sw += v;
                        sx += v * x;
                        sy += v * y;
                    }
                }
                if (sw <= 0) return false;
                cx = sx / sw;
                cy = sy / sw;
                if (Math.abs(cx - i) > rh + 1 || Math.abs(cy - j) > rh + 1) return false;
            }
            double X = x0 + 2 * cx + 1.0, Y = y0 + 2 * cy + 1.0;
            double r1 = ap + 2, r2 = ap + 7;
            int ext = (int) Math.ceil(r2) + 1;
            if (X - ext < x0 || Y - ext < y0 || X + ext >= x0 + 2 * w || Y + ext >= y0 + 2 * w) return false;
            // local background: the median of the green pixels in a ring outside the aperture
            float[] ring = new float[(int) (Math.PI * (r2 * r2 - r1 * r1) / 2 + 64)];
            int nr = 0;
            int xa = (int) Math.floor(X - r2), xb = (int) Math.ceil(X + r2), ya = (int) Math.floor(Y - r2), yb = (int) Math.ceil(Y + r2);
            for (int y = ya; y <= yb; y++) {
                int par = parity(y);
                if (par < 0) continue;
                int x = xa + (((xa & 1) == par) ? 0 : 1);
                double dy = y + 0.5 - Y;
                for (; x <= xb; x += 2) {
                    double dx = x + 0.5 - X, d2 = dx * dx + dy * dy;
                    if (d2 < r1 * r1 || d2 > r2 * r2 || nr >= ring.length) continue;
                    ring[nr++] = (raw[y * W + x] & 0xFFFF) * invScale - blackG - bgFull(x, y);
                }
            }
            if (nr < 12) return false;
            float lb = Stats.median(ring, nr);
            float sig = Stats.madSigma(ring, nr, lb);
            // the centre once more on the full-resolution green pixels (an error e adds about e^2 / 2h to the HFR)
            for (int it = 0; it < 2; it++) {
                double sw = 0, sx = 0, sy = 0;
                for (int y = (int) Math.floor(Y - ap); y <= (int) Math.ceil(Y + ap); y++) {
                    int par = parity(y);
                    if (par < 0) continue;
                    int x0g = (int) Math.floor(X - ap);
                    int x = x0g + (((x0g & 1) == par) ? 0 : 1);
                    double dy = y + 0.5 - Y;
                    for (; x <= (int) Math.ceil(X + ap); x += 2) {
                        double dx = x + 0.5 - X;
                        if (dx * dx + dy * dy > ap * ap) continue;
                        double v = (raw[y * W + x] & 0xFFFF) * invScale - blackG - bgFull(x, y) - lb;
                        if (v <= 0) continue;
                        sw += v;
                        sx += v * dx;
                        sy += v * dy;
                    }
                }
                if (sw <= 0) return false;
                X += sx / sw;
                Y += sy / sw;
            }
            double sv = 0, svr = 0, peak = 0;
            int np = 0;
            boolean sat = false;
            int yA = (int) Math.floor(Y - ap), yB = (int) Math.ceil(Y + ap), xA = (int) Math.floor(X - ap), xB = (int) Math.ceil(X + ap);
            double[] rr = new double[(yB - yA + 1) * (xB - xA + 1)];
            double[] vv = new double[rr.length];
            for (int y = yA; y <= yB; y++) {
                int par = parity(y);
                if (par < 0) continue;
                int x = xA + (((xA & 1) == par) ? 0 : 1);
                double dy = y + 0.5 - Y;
                for (; x <= xB; x += 2) {
                    double dx = x + 0.5 - X, d = Math.sqrt(dx * dx + dy * dy);
                    if (d > ap) continue;
                    int raw16 = raw[y * W + x] & 0xFFFF;
                    double v = raw16 * invScale - blackG - bgFull(x, y) - lb;
                    if (d < 2.5 && raw16 * invScale >= satLevel) sat = true;
                    sv += v;
                    svr += v * d;
                    rr[np] = d;
                    vv[np++] = v;
                    if (v > peak) peak = v;
                }
            }
            if (sat || sv <= 0 || np == 0) return false;
            double s = Math.max(sig, 0.1);
            double snr = sv / (s * Math.sqrt(np));
            if (snr < minSnr) return false;
            // a hot pixel or a cosmic ray: half its light or more in one pixel (a sharp star puts a quarter there)
            if (peak > 0.5 * sv) return false;
            double h = svr / sv;
            if (h <= 0.3 || h > ap) return false;
            // the HFR's error from the pixels' noise: d(h) = sum((r - h) dv) / F
            double e2 = 0;
            for (int k = 0; k < np; k++) e2 += (rr[k] - h) * (rr[k] - h);
            res[0] = h;
            res[1] = sv;
            res[2] = snr;
            res[3] = s * Math.sqrt(e2) / sv;
            return true;
        }

        /** Which column parity holds the green pixels of RAW row y, or -1 if none does. */
        int parity(int y) {
            if ((y & 1) == gp1 >> 1) return gp1 & 1;
            if ((y & 1) == gp2 >> 1) return gp2 & 1;
            return -1;
        }

        /** The half-res background at full-resolution pixel (x, y). */
        float bgFull(int x, int y) {
            int i = Stats.clamp((x - x0) / 2, 0, w - 1), j = Stats.clamp((y - y0) / 2, 0, w - 1);
            return bg[j * w + i];
        }
    }
}
