package com.brandon.nightsky.processing;

import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * Finding Jupiter in a telephoto RAW frame (3.2): the brightest small light, not the broad glow of a lamp, a lit
 * cloud or the Moon, and not a hot pixel. Over the whole frame (every pixel of one green plane looked at, the bright
 * ones' neighbourhoods then measured closely) or around where it was. The spot's middle is the light's centre of
 * brightness (a tenth of a pixel on Jupiter); its {@code level} is its brightest pixel's share of the RAW range and
 * {@code clipped} the share of its middle at the white level, for {@link JupiterMeter}; {@code r} its half-light
 * radius (px).
 */
public final class PointFinder {
    private PointFinder() {}

    /** The light's middle (r <= CORE px), the ring round it (to RING), the sky beyond (SKY0 to SKY1). */
    static final int CORE = 4, RING = 8, SKY0 = 10, SKY1 = 15;
    /** The most bright samples followed up, and the most places measured closely. */
    static final int MAX_BRIGHT = 60_000, MAX_CANDIDATES = 24;

    /**
     * The brightest small light over the whole frame ({@code near} null) or within {@code reach} px of where it was.
     * {@code rowStride} in samples.
     */
    public static MoonFinder.Spot find(ShortBuffer raw, int rowStride, int w, int h, SensorSpec spec, MoonFinder.Spot near, int reach) {
        MoonFinder.Spot s = new MoonFinder.Spot();
        int gp = MoonFinder.greenPlane(spec), gx = gp & 1, gy = gp >> 1;
        float black = spec.black[gp], range = Math.max(1, spec.white - black);
        int x0 = SKY1 + 1, y0 = SKY1 + 1, x1 = w - SKY1 - 1, y1 = h - SKY1 - 1;
        if (near != null && near.ok) {
            x0 = Math.max(x0, (int) (near.x - reach));
            y0 = Math.max(y0, (int) (near.y - reach));
            x1 = Math.min(x1, (int) (near.x + reach));
            y1 = Math.min(y1, (int) (near.y + reach));
        }
        if (x1 - x0 < 8 || y1 - y0 < 8) return s;
        int sx0 = (x0 & ~1) + gx, sy0 = (y0 & ~1) + gy;
        // the sky: a sparse sample's median and spread
        float[] sm = new float[2048];
        int m = 0;
        int nx = (x1 - sx0) / 2, ny = (y1 - sy0) / 2;
        if (nx < 4 || ny < 4) return s;
        long total = (long) nx * ny;
        long stepS = Math.max(1, total / sm.length);
        for (long k = 0; k < total && m < sm.length; k += stepS) {
            int j = (int) (k / nx), i = (int) (k % nx);
            sm[m++] = (raw.get((sy0 + 2 * j) * rowStride + sx0 + 2 * i) & 0xffff) - black;
        }
        Arrays.sort(sm, 0, m);
        float sky = sm[m / 2];
        for (int k = 0; k < m; k++) sm[k] = Math.abs(sm[k] - sky);
        Arrays.sort(sm, 0, m);
        float sigma = Math.max(0.5f, 1.4826f * sm[m / 2]);
        // every green sample well over the sky: a first threshold, raised if too many (a lit cloud, the Moon)
        float t = sky + Math.max(12 * sigma, 0.006f * range);
        int[] bright = new int[MAX_BRIGHT];
        int nb;
        while (true) {
            nb = 0;
            boolean full = false;
            for (int y = sy0; y < y1 && !full; y += 2) {
                int row = y * rowStride;
                for (int x = sx0; x < x1; x += 2) {
                    if ((raw.get(row + x) & 0xffff) - black > t) {
                        if (nb == MAX_BRIGHT) {
                            full = true;
                            break;
                        }
                        bright[nb++] = y * w + x;
                    }
                }
            }
            if (!full) break;
            t = sky + 2 * (t - sky);
            if (t - sky > range) return s;
        }
        if (nb == 0) return s;
        // the local peaks among them (the brightest green sample within 2 px), the brightest few kept
        int[] cand = new int[MAX_CANDIDATES];
        float[] candV = new float[MAX_CANDIDATES];
        int nc = 0;
        for (int b = 0; b < nb; b++) {
            int x = bright[b] % w, y = bright[b] / w;
            int v = raw.get(y * rowStride + x) & 0xffff;
            boolean peak = true;
            for (int dy = -2; dy <= 2 && peak; dy += 2) {
                for (int dx = -2; dx <= 2; dx += 2) {
                    if (dx == 0 && dy == 0) continue;
                    int xx = x + dx, yy = y + dy;
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue;
                    int u = raw.get(yy * rowStride + xx) & 0xffff;
                    // ties (a clipped plateau) go to the first in reading order
                    if (u > v || (u == v && (dy < 0 || (dy == 0 && dx < 0)))) {
                        peak = false;
                        break;
                    }
                }
            }
            if (!peak) continue;
            float fv = v - black;
            if (nc < MAX_CANDIDATES) {
                cand[nc] = bright[b];
                candV[nc++] = fv;
            } else {
                int lo = 0;
                for (int k = 1; k < nc; k++) if (candV[k] < candV[lo]) lo = k;
                if (fv > candV[lo]) {
                    cand[lo] = bright[b];
                    candV[lo] = fv;
                }
            }
        }
        // each measured closely: the brightest compact one wins
        MoonFinder.Spot best = null;
        double bestFlux = 0;
        for (int c = 0; c < nc; c++) {
            MoonFinder.Spot p = measure(raw, rowStride, w, h, spec, cand[c] % w, cand[c] / w);
            if (p == null) continue;
            if (p.samples > bestFlux) {
                bestFlux = p.samples;
                best = p;
            }
        }
        return best != null ? best : s;
    }

    /**
     * The light round (px, py) measured: null if it isn't a small light (a broad patch, a hot pixel, too faint).
     * {@code samples} carries its light (counts over the sky, green), for choosing.
     */
    static MoonFinder.Spot measure(ShortBuffer raw, int rowStride, int w, int h, SensorSpec spec, int px, int py) {
        if (px < SKY1 || py < SKY1 || px >= w - SKY1 || py >= h - SKY1) return null;
        int gp = MoonFinder.greenPlane(spec);
        float[] skyV = new float[(2 * SKY1 + 1) * (2 * SKY1 + 1)];
        int ns = 0;
        for (int dy = -SKY1; dy <= SKY1; dy++) {
            for (int dx = -SKY1; dx <= SKY1; dx++) {
                int r2 = dx * dx + dy * dy;
                if (r2 < SKY0 * SKY0 || r2 > SKY1 * SKY1) continue;
                int x = px + dx, y = py + dy;
                if (plane(x, y) != gp && plane(x, y) != (gp ^ 3)) continue;
                skyV[ns++] = (raw.get(y * rowStride + x) & 0xffff) - spec.black[gp];
            }
        }
        if (ns < 20) return null;
        Arrays.sort(skyV, 0, ns);
        float sky = skyV[ns / 2];
        for (int k = 0; k < ns; k++) skyV[k] = Math.abs(skyV[k] - sky);
        Arrays.sort(skyV, 0, ns);
        double sigma = Math.max(0.5, 1.4826 * skyV[ns / 2]);
        // the light's middle and the ring round it (green), its brightest pixel, clipped pixels (any colour)
        double core = 0, ring = 0, maxG = 0, sx = 0, sy = 0, sw = 0;
        double peak = 0;
        int clipped = 0, coreN = 0;
        int satAt = (int) spec.white - 2;
        double range = Math.max(1, spec.white - spec.black[gp]);
        for (int dy = -RING; dy <= RING; dy++) {
            for (int dx = -RING; dx <= RING; dx++) {
                int r2 = dx * dx + dy * dy;
                if (r2 > RING * RING) continue;
                int x = px + dx, y = py + dy, p = plane(x, y);
                int v = raw.get(y * rowStride + x) & 0xffff;
                boolean green = spec.cfa[p] == 1;
                if (r2 <= CORE * CORE) {
                    coreN++;
                    if (v >= satAt) clipped++;
                    double share = (v - spec.black[p]) / Math.max(1.0, spec.white - spec.black[p]);
                    if (share > peak) peak = share;
                }
                if (!green) continue;
                double e = v - spec.black[p] - sky;
                if (r2 <= CORE * CORE) {
                    core += e;
                    if (e > maxG) maxG = e;
                    if (e > 0) {
                        sx += e * dx;
                        sy += e * dy;
                        sw += e;
                    }
                } else {
                    ring += e;
                }
            }
        }
        // bright enough to be Jupiter's (forty times the sky's noise in its middle, even when the first exposure is far
        // too short), small (little light in the ring), and not one pixel alone (a hot pixel has its light on one green)
        if (core < 40 * sigma || maxG < 8 * sigma) return null;
        if (ring > 0.6 * core) return null;
        if (maxG > 0.8 * core && clipped < 2) return null;
        if (clipped > 0.8 * coreN) return null;
        // the centre of brightness, twice (the second round the first's middle), on every colour balanced to grey
        double cx = px + (sw > 0 ? sx / sw : 0), cy = py + (sw > 0 ? sy / sw : 0);
        for (int it = 0; it < 2; it++) {
            int ix = (int) Math.round(cx), iy = (int) Math.round(cy);
            double ax = 0, ay = 0, aw = 0;
            for (int dy = -CORE; dy <= CORE; dy++) {
                for (int dx = -CORE; dx <= CORE; dx++) {
                    int x = ix + dx, y = iy + dy;
                    double rx = x - cx, ry = y - cy;
                    if (rx * rx + ry * ry > CORE * CORE || x < 0 || y < 0 || x >= w || y >= h) continue;
                    int p = plane(x, y);
                    double n = spec.camNeutral[spec.cfa[p]];
                    double e = ((raw.get(y * rowStride + x) & 0xffff) - spec.black[p]) / (n > 0 ? n : 1) - sky;
                    if (e <= 3 * sigma) continue;
                    ax += e * x;
                    ay += e * y;
                    aw += e;
                }
            }
            if (aw <= 0) break;
            cx = ax / aw;
            cy = ay / aw;
        }
        // half its light within: its size
        double r = halfLight(raw, rowStride, spec, cx, cy, sky, gp);
        MoonFinder.Spot s = new MoonFinder.Spot();
        s.x = cx;
        s.y = cy;
        s.r = r;
        s.level = peak;
        s.clipped = coreN > 0 ? clipped / (double) coreN : 0;
        s.samples = (int) Math.min(Integer.MAX_VALUE, Math.round(core));
        s.ok = true;
        return s;
    }

    static int plane(int x, int y) {
        return ((y & 1) << 1) | (x & 1);
    }

    /** The radius (px) holding half the green light within CORE of (cx, cy). */
    static double halfLight(ShortBuffer raw, int rowStride, SensorSpec spec, double cx, double cy, float sky, int gp) {
        int ix = (int) Math.round(cx), iy = (int) Math.round(cy);
        double[] rs = new double[(2 * CORE + 1) * (2 * CORE + 1)], es = new double[rs.length];
        int n = 0;
        double tot = 0;
        for (int dy = -CORE; dy <= CORE; dy++) {
            for (int dx = -CORE; dx <= CORE; dx++) {
                int x = ix + dx, y = iy + dy;
                if (spec.cfa[plane(x, y)] != 1) continue;
                double rr = Math.hypot(x - cx, y - cy);
                if (rr > CORE) continue;
                double e = Math.max(0, (raw.get(y * rowStride + x) & 0xffff) - spec.black[gp] - sky);
                rs[n] = rr;
                es[n++] = e;
                tot += e;
            }
        }
        if (tot <= 0) return CORE;
        // in order out from the middle
        Integer[] idx = new Integer[n];
        for (int k = 0; k < n; k++) idx[k] = k;
        final double[] fr = rs;
        Arrays.sort(idx, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(fr[a], fr[b]);
            }
        });
        double acc = 0;
        for (int k = 0; k < n; k++) {
            acc += es[idx[k]];
            if (acc >= tot / 2) return Math.max(0.5, rs[idx[k]]);
        }
        return CORE;
    }

    /**
     * Focus: how gathered Jupiter's light is in a crop (green, over the crop's own sky): the sum of the squares over the
     * square of the sum, within 6 px of its brightest pixel. Bigger is sharper; 0 if there's no light.
     */
    public static double sharpness(short[] crop, int w, int h, int x0, int y0, SensorSpec spec) {
        int gp = MoonFinder.greenPlane(spec);
        float black = spec.black[gp];
        // the sky: the crop's edge
        float[] edge = new float[2 * (w + h)];
        int ne = 0;
        int bi = -1, bj = -1, best = -1;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                if (spec.cfa[plane(i + x0, j + y0)] != 1) continue;
                int v = crop[j * w + i] & 0xffff;
                if ((i < 2 || j < 2 || i >= w - 2 || j >= h - 2) && ne < edge.length) edge[ne++] = v - black;
                if (i >= 6 && j >= 6 && i < w - 6 && j < h - 6 && v > best) {
                    best = v;
                    bi = i;
                    bj = j;
                }
            }
        }
        if (bi < 0 || ne < 8) return 0;
        Arrays.sort(edge, 0, ne);
        float sky = edge[ne / 2];
        double s1 = 0, s2 = 0;
        for (int dj = -6; dj <= 6; dj++) {
            for (int di = -6; di <= 6; di++) {
                if (di * di + dj * dj > 36) continue;
                int i = bi + di, j = bj + dj;
                if (spec.cfa[plane(i + x0, j + y0)] != 1) continue;
                double e = Math.max(0, (crop[j * w + i] & 0xffff) - black - sky);
                s1 += e;
                s2 += e * e;
            }
        }
        return s1 > 0 ? 100 * s2 / (s1 * s1) : 0;
    }

    /**
     * The crop's side for Jupiter of radius {@code rPx} (px): every moon's farthest reach, a margin for the hand and
     * the sky beyond, even (and at least 64).
     */
    public static int side(double rPx) {
        double r = Double.isNaN(rPx) || rPx <= 0 ? 1.15 : rPx;
        int s = (int) Math.ceil(2 * (JupiterGeo.REACH[3] + 3) * r) + 2 * SKY1;
        s = Math.max(64, s + (s & 1));
        return s;
    }
}
