package com.brandon.nightsky.processing;

import java.util.Arrays;

/**
 * Finds stars in a RAW frame using the half-resolution green image (average of the two green CFA sites):
 * coarse background removal, 3x3 matched filter, local maxima above k sigma, then flux-weighted centroids.
 * Single-pixel spikes (hot pixels), elongated blobs and extended blobs are rejected. The kept stars' positions
 * are then refined with a Gaussian-windowed centroid on the green pixels at full resolution, sized to the
 * frame's stars, which roughly halves the position error of faint stars.
 */
public final class StarDetector {
    public static final int TILE = 32;
    private final int W, H, w2, h2, tnx, tny;
    private final int gp1, gp2;
    private final float blackG, satLevel;
    private final float[] D, S;
    private final float[] tileBg, tileSig;
    public float kSigma = 5f;
    /** Shape limits: elongation, and half-flux radius in RAW pixels (tightened for faint searches). */
    public float maxElong = 2.2f, minHfr = 0f, maxHfr = 5.2f;
    /** Known hot pixels, so a faint search doesn't take them for stars (optional). */
    public HotPixels hot;
    /** Window (sigma, RAW pixels) of the last frame's refined centroids: twice the size of its stars. */
    public float window;

    public StarDetector(SensorSpec spec) {
        W = spec.width;
        H = spec.height;
        w2 = W / 2;
        h2 = H / 2;
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
        blackG = 0.5f * (spec.black[gp1] + spec.black[gp2]);
        satLevel = spec.white - 0.04f * (spec.white - blackG);
        D = new float[w2 * h2];
        S = new float[w2 * h2];
        tnx = (w2 + TILE - 1) / TILE;
        tny = (h2 + TILE - 1) / TILE;
        tileBg = new float[tnx * tny];
        tileSig = new float[tnx * tny];
    }

    /**
     * @param raw      RAW frame (unsigned 16-bit values, scaled by 1/invScale)
     * @param invScale multiply stored values by this to get ADU
     * @param maxStars how many of the brightest stars to keep
     */
    public StarList detect(final short[] raw, final float invScale, int maxStars) {
        final int ox1 = gp1 & 1, oy1 = gp1 >> 1, ox2 = gp2 & 1, oy2 = gp2 >> 1;
        // 1) half-res green image
        Parallel.forRange(h2, 16, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int j = j0; j < j1; j++) {
                    int r1 = (2 * j + oy1) * W + ox1;
                    int r2 = (2 * j + oy2) * W + ox2;
                    int o = j * w2;
                    for (int i = 0; i < w2; i++) {
                        D[o + i] = ((raw[r1 + 2 * i] & 0xFFFF) + (raw[r2 + 2 * i] & 0xFFFF)) * 0.5f * invScale - blackG;
                    }
                }
            }
        });
        // 2) tile background (median) and noise (MAD)
        Parallel.forRange(tnx * tny, 8, new Parallel.Range() {
            @Override
            public void run(int t0, int t1) {
                float[] buf = new float[(TILE / 2 + 1) * (TILE / 2 + 1)];
                for (int t = t0; t < t1; t++) {
                    int tx = t % tnx, ty = t / tnx;
                    int xa = tx * TILE, xb = Math.min(w2, xa + TILE);
                    int ya = ty * TILE, yb = Math.min(h2, ya + TILE);
                    int n = 0;
                    for (int j = ya; j < yb; j += 2) {
                        int o = j * w2;
                        for (int i = xa; i < xb; i += 2) buf[n++] = D[o + i];
                    }
                    float m = Stats.median(buf, n);
                    // refill: median() reorders, MAD needs the values again (order irrelevant)
                    float sg = Stats.madSigma(buf, n, m);
                    tileBg[t] = m;
                    tileSig[t] = sg;
                }
            }
        });
        medianFilterGrid(tileBg);
        medianFilterGrid(tileSig);
        float sigFloor = 0.15f;
        for (int t = 0; t < tileSig.length; t++) if (tileSig[t] < sigFloor) tileSig[t] = sigFloor;

        // 3) subtract the interpolated background
        Parallel.forRange(h2, 16, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] rowBg = new float[tnx];
                for (int j = j0; j < j1; j++) {
                    double gy = (j + 0.5) / TILE - 0.5;
                    int ty = (int) Math.floor(gy);
                    double fy = gy - ty;
                    if (ty < 0) { ty = 0; fy = 0; }
                    if (ty >= tny - 1) { ty = Math.max(0, tny - 2); fy = tny > 1 ? 1 : 0; }
                    int ty1 = Math.min(ty + 1, tny - 1);
                    for (int tx = 0; tx < tnx; tx++) {
                        rowBg[tx] = (float) (tileBg[ty * tnx + tx] * (1 - fy) + tileBg[ty1 * tnx + tx] * fy);
                    }
                    int o = j * w2;
                    for (int i = 0; i < w2; i++) {
                        double gx = (i + 0.5) / TILE - 0.5;
                        int tx = (int) Math.floor(gx);
                        double fx = gx - tx;
                        if (tx < 0) { tx = 0; fx = 0; }
                        if (tx >= tnx - 1) { tx = Math.max(0, tnx - 2); fx = tnx > 1 ? 1 : 0; }
                        int tx1 = Math.min(tx + 1, tnx - 1);
                        D[o + i] -= (float) (rowBg[tx] * (1 - fx) + rowBg[tx1] * fx);
                    }
                }
            }
        });
        // 4) 3x3 binomial smoothing (matched filter for a ~1 px PSF at half resolution)
        Parallel.forRange(h2, 16, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int j = j0; j < j1; j++) {
                    int o = j * w2;
                    if (j == 0 || j == h2 - 1) {
                        System.arraycopy(D, o, S, o, w2);
                        continue;
                    }
                    S[o] = D[o];
                    S[o + w2 - 1] = D[o + w2 - 1];
                    int u = o - w2, d = o + w2;
                    for (int i = 1; i < w2 - 1; i++) {
                        S[o + i] = (D[u + i - 1] + 2 * D[u + i] + D[u + i + 1]
                                + 2 * D[o + i - 1] + 4 * D[o + i] + 2 * D[o + i + 1]
                                + D[d + i - 1] + 2 * D[d + i] + D[d + i + 1]) * (1f / 16f);
                    }
                }
            }
        });
        // 5) local maxima above threshold
        final int margin = 4;
        final int bands = Math.max(1, Math.min(64, (h2 - 2 * margin) / 32));
        final IntList[] found = new IntList[bands];
        Parallel.forRange(bands, 1, new Parallel.Range() {
            @Override
            public void run(int b0, int b1) {
                int rows = h2 - 2 * margin;
                for (int b = b0; b < b1; b++) {
                    IntList list = new IntList(256);
                    int ja = margin + (int) ((long) rows * b / bands);
                    int jb = margin + (int) ((long) rows * (b + 1) / bands);
                    for (int j = ja; j < jb; j++) {
                        int o = j * w2;
                        int tyRow = Math.min(j / TILE, tny - 1) * tnx;
                        for (int i = margin; i < w2 - margin; i++) {
                            float s = S[o + i];
                            float thr = kSigma * 0.375f * tileSig[tyRow + Math.min(i / TILE, tnx - 1)];
                            if (s <= thr) continue;
                            int u = o - w2, d = o + w2;
                            if (s <= S[o + i - 1] || s < S[o + i + 1] || s <= S[u + i] || s < S[d + i]
                                    || s <= S[u + i - 1] || s <= S[u + i + 1] || s < S[d + i - 1] || s < S[d + i + 1]) {
                                continue;
                            }
                            list.add(o + i);
                        }
                    }
                    found[b] = list;
                }
            }
        });
        int total = 0;
        for (IntList l : found) total += l.size;
        long[] keys = new long[total];
        int k = 0;
        for (IntList l : found) {
            for (int q = 0; q < l.size; q++) {
                int idx = l.data[q];
                keys[k++] = ((long) Float.floatToIntBits(S[idx]) << 32) | (0xFFFFFFFFL & idx);
            }
        }
        Arrays.sort(keys);
        int measure = Math.min(total, Math.max(maxStars * 3, 50));
        StarList out = new StarList(measure);
        for (int q = 0; q < measure; q++) {
            int idx = (int) (keys[total - 1 - q] & 0xFFFFFFFFL);
            measureStar(raw, invScale, idx, out, ox1, oy1, ox2, oy2);
        }
        out.sortAndTrim(maxStars);
        refine(raw, invScale, out);
        // diagnostics: median tile background / noise
        float[] tb = tileBg.clone();
        float[] ts = tileSig.clone();
        out.background = Stats.median(tb, tb.length);
        out.noise = Stats.median(ts, ts.length);
        return out;
    }

    private void measureStar(short[] raw, float invScale, int idx, StarList out, int ox1, int oy1, int ox2, int oy2) {
        int j0 = idx / w2, i0 = idx % w2;
        float sig = tileSig[Math.min(j0 / TILE, tny - 1) * tnx + Math.min(i0 / TILE, tnx - 1)];
        float t = sig;
        double sw = 0, sx = 0, sy = 0, flux = 0;
        float center = 0, ring = 0;
        for (int dj = -2; dj <= 2; dj++) {
            int o = (j0 + dj) * w2;
            for (int di = -2; di <= 2; di++) {
                float v = D[o + i0 + di];
                flux += v;
                if (Math.abs(di) <= 1 && Math.abs(dj) <= 1) {
                    if (di == 0 && dj == 0) center = v;
                    else if (v > 0) ring += v;
                }
                float w = v - t;
                if (w <= 0) continue;
                sw += w;
                sx += w * di;
                sy += w * dj;
            }
        }
        if (sw <= 0 || flux <= 0 || center <= 0) return;
        // single-pixel spike (hot pixel) test
        if (center / (center + ring) > 0.86f) return;
        double cx = sx / sw, cy = sy / sw;
        double mxx = 0, myy = 0, mxy = 0, hr = 0;
        for (int dj = -2; dj <= 2; dj++) {
            int o = (j0 + dj) * w2;
            for (int di = -2; di <= 2; di++) {
                float w = D[o + i0 + di] - t;
                if (w <= 0) continue;
                double ddx = di - cx, ddy = dj - cy;
                mxx += w * ddx * ddx;
                myy += w * ddy * ddy;
                mxy += w * ddx * ddy;
                hr += w * Math.sqrt(ddx * ddx + ddy * ddy);
            }
        }
        mxx /= sw;
        myy /= sw;
        mxy /= sw;
        double tr = mxx + myy, det = mxx * myy - mxy * mxy;
        double disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
        double l1 = tr / 2 + disc, l2 = Math.max(1e-6, tr / 2 - disc);
        double elong = Math.sqrt(l1 / l2);
        double hfrHalf = hr / sw;
        if (elong > maxElong && l1 > 0.5) return;   // streak or cloud edge
        if (2 * hfrHalf > maxHfr || 2 * hfrHalf < minHfr) return; // extended blob, or sharper than a star
        if (hot != null && (hot.isBad(2 * i0 + ox1, 2 * j0 + oy1) || hot.isBad(2 * i0 + ox2, 2 * j0 + oy2))) return;
        // saturation: check the raw green samples at the peak
        int r1 = (2 * j0 + oy1) * W + 2 * i0 + ox1;
        int r2 = (2 * j0 + oy2) * W + 2 * i0 + ox2;
        boolean sat = (raw[r1] & 0xFFFF) * invScale >= satLevel || (raw[r2] & 0xFFFF) * invScale >= satLevel;
        float rx = (float) (2 * (i0 + cx) + 1.0);
        float ry = (float) (2 * (j0 + cy) + 1.0);
        out.add(rx, ry, (float) flux, (float) (2 * hfrHalf), (float) elong, sat);
    }

    /** Most stars used to size the centroid window. */
    static final int SIZE_STARS = 40;
    /** Signal to noise (5 x 5 box) a star needs to help size the window. */
    static final float SIZE_SNR = 6f;

    /**
     * Replaces the moment centroids with Gaussian-windowed ones (SExtractor's XWIN): the frame's star size
     * comes from the windowed second moments of its brightest unsaturated stars, the window is twice that,
     * and each star's position is iterated until it settles. A star whose refinement fails keeps its
     * moment centroid. With too few clear stars to size it, the window stays as the last frame's.
     */
    private void refine(short[] raw, float invScale, StarList s) {
        if (s.count == 0) return;
        float[] ts = tileSig.clone();
        float noise = Stats.median(ts, ts.length);
        float minFlux = SIZE_SNR * 5 * noise;
        // first guess: the last frame's window, or from the moment sizes of the brightest stars
        double sw = window;
        if (sw <= 0) {
            float[] h = new float[Math.min(s.count, SIZE_STARS)];
            int nh = 0;
            for (int i = 0; i < s.count && nh < h.length; i++) if (!s.saturated[i] && s.flux[i] >= minFlux) h[nh++] = s.hfr[i];
            sw = nh > 0 ? Stats.clamp(1.6f * Stats.median(h, nh), 0.9f, 3f) : 1.5;
        }
        double[] xy = new double[2], m2 = new double[1];
        float[] size = new float[SIZE_STARS];
        for (int pass = 0; pass < 2; pass++) {
            int ns = 0;
            double w = sw * sw;
            for (int i = 0; i < s.count && ns < size.length; i++) {
                if (s.saturated[i] || s.flux[i] < minFlux) continue;
                xy[0] = s.x[i];
                xy[1] = s.y[i];
                if (!windowed(raw, invScale, sw, xy, m2)) continue;
                // a Gaussian star of variance v seen through a Gaussian window of variance w: m2 = v w / (v + w).
                // Hot pixels are sharper than any star, flat noise fills the window.
                if (m2[0] <= 0.06 * w || m2[0] >= 0.8 * w) continue;
                size[ns++] = (float) Math.sqrt(m2[0] * w / (w - m2[0]));
            }
            if (ns < 3) break;
            double next = Stats.clamp(2f * Stats.median(size, ns), 0.9f, 3f);
            boolean settled = Math.abs(next - sw) < 0.1 * sw;
            sw = next;
            if (settled) break;
        }
        window = (float) sw;
        for (int i = 0; i < s.count; i++) {
            xy[0] = s.x[i];
            xy[1] = s.y[i];
            if (windowed(raw, invScale, sw, xy, null)) {
                s.x[i] = (float) xy[0];
                s.y[i] = (float) xy[1];
            }
        }
    }

    /**
     * Gaussian-windowed centroid of the green pixels around xy (RAW coordinates, pixel centres at +0.5), with
     * the background of step 3 removed, iterated until it moves less than a thousandth of a pixel. On success
     * xy holds the position and m2 (if given) the windowed second moment per axis.
     */
    private boolean windowed(short[] raw, float invScale, double sw, double[] xy, double[] m2) {
        int r = (int) Math.ceil(3 * sw);
        double x0 = xy[0], y0 = xy[1], cx = x0, cy = y0;
        float bg = bgAt((int) ((x0 - 1) / 2), (int) ((y0 - 1) / 2));
        double inv = 1 / (2 * sw * sw);
        double[] wx = new double[2 * r + 2], wy = new double[2 * r + 2];
        double sv = 0, sxx = 0;
        for (int it = 0; it < 12; it++) {
            int ax = (int) Math.floor(cx) - r, ay = (int) Math.floor(cy) - r;
            if (ax < 0 || ay < 0 || ax + 2 * r + 1 >= W || ay + 2 * r + 1 >= H) return false;
            for (int q = 0; q <= 2 * r + 1; q++) {
                double dx = ax + q + 0.5 - cx, dy = ay + q + 0.5 - cy;
                wx[q] = Math.exp(-dx * dx * inv);
                wy[q] = Math.exp(-dy * dy * inv);
            }
            double sx = 0, sy = 0;
            sv = 0;
            sxx = 0;
            for (int qy = 0; qy <= 2 * r + 1; qy++) {
                int y = ay + qy;
                int o = y * W;
                // the green sites of this row: one per 2 x 2 cell, at x parity ox1 or ox2
                int par = (y & 1) == gp1 >> 1 ? gp1 & 1 : gp2 & 1;
                if ((y & 1) != gp1 >> 1 && (y & 1) != gp2 >> 1) continue;
                int x = ax + (((ax & 1) == par) ? 0 : 1);
                double dy = y + 0.5 - cy;
                for (; x <= ax + 2 * r + 1; x += 2) {
                    double w = wx[x - ax] * wy[qy];
                    double v = ((raw[o + x] & 0xFFFF) * invScale - blackG - bg) * w;
                    double dx = x + 0.5 - cx;
                    sv += v;
                    sx += v * dx;
                    sy += v * dy;
                    sxx += v * (dx * dx + dy * dy);
                }
            }
            if (sv <= 0) return false;
            double ddx = sx / sv, ddy = sy / sv;
            cx += ddx;
            cy += ddy;
            if (Math.abs(cx - x0) > 1.5 * Math.max(1, sw) || Math.abs(cy - y0) > 1.5 * Math.max(1, sw)) return false;
            if (ddx * ddx + ddy * ddy < 1e-6) break;
        }
        xy[0] = cx;
        xy[1] = cy;
        if (m2 != null) m2[0] = 0.5 * sxx / sv;
        return true;
    }

    /** Background (ADU above black) that step 3 subtracted at half-resolution pixel (i, j). */
    private float bgAt(int i, int j) {
        i = Stats.clamp(i, 0, w2 - 1);
        j = Stats.clamp(j, 0, h2 - 1);
        double gy = (j + 0.5) / TILE - 0.5, gx = (i + 0.5) / TILE - 0.5;
        int ty = (int) Math.floor(gy), tx = (int) Math.floor(gx);
        double fy = gy - ty, fx = gx - tx;
        if (ty < 0) { ty = 0; fy = 0; }
        if (ty >= tny - 1) { ty = Math.max(0, tny - 2); fy = tny > 1 ? 1 : 0; }
        if (tx < 0) { tx = 0; fx = 0; }
        if (tx >= tnx - 1) { tx = Math.max(0, tnx - 2); fx = tnx > 1 ? 1 : 0; }
        int ty1 = Math.min(ty + 1, tny - 1), tx1 = Math.min(tx + 1, tnx - 1);
        double a = tileBg[ty * tnx + tx] * (1 - fx) + tileBg[ty * tnx + tx1] * fx;
        double b = tileBg[ty1 * tnx + tx] * (1 - fx) + tileBg[ty1 * tnx + tx1] * fx;
        return (float) (a * (1 - fy) + b * fy);
    }

    private void medianFilterGrid(float[] g) {
        float[] src = g.clone();
        float[] buf = new float[9];
        for (int ty = 0; ty < tny; ty++) {
            for (int tx = 0; tx < tnx; tx++) {
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = ty + dy;
                    if (yy < 0 || yy >= tny) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = tx + dx;
                        if (xx < 0 || xx >= tnx) continue;
                        buf[n++] = src[yy * tnx + xx];
                    }
                }
                g[ty * tnx + tx] = Stats.median(buf, n);
            }
        }
    }

    /** Growable int array. */
    static final class IntList {
        int[] data;
        int size;

        IntList(int cap) {
            data = new int[cap];
        }

        void add(int v) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = v;
        }
    }
}
