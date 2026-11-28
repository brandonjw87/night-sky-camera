package com.brandon.nightsky.processing;

import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * Finding the Moon in a whole RAW frame from the telephoto and following it frame after frame (3.0, the Moonshot),
 * quick enough for every frame of a burst at 30 a second: the green samples on a sparse grid show the brightest
 * patch, the patch grown from its brightest sample (so a street light elsewhere in the picture doesn't join it), its
 * middle and size; then, at the sensor's own pixels, how bright its bright parts are (for the exposure) and how many
 * are clipped.
 */
public final class MoonFinder {
    private MoonFinder() {}

    /** The Moon in a frame. */
    public static final class Spot {
        /** The lit part's middle (the middle of its extent, RAW px) and half its larger extent (px). */
        public double x, y, r;
        /** The bright parts: the 99.7th percentile of the green samples over the lit part, as a share of the range. */
        public double level;
        /** Share of the lit part's green samples at the white level. */
        public double clipped;
        /** The lit part's samples on the grid. */
        public int samples;
        public boolean ok;

        @Override
        public String toString() {
            return ok ? String.format(java.util.Locale.US, "at %.0f,%.0f, %.0f px across, bright parts at %.0f%%%s", x, y, 2 * r, level * 100,
                    clipped > 0.001 ? String.format(java.util.Locale.US, ", %.1f%% clipped", clipped * 100) : "") : "no Moon";
        }
    }

    /** The first green sample's place in the 2x2 pattern. */
    static int greenPlane(SensorSpec spec) {
        for (int p = 0; p < 4; p++) if (spec.cfa[p] == 1) return p;
        return 1;
    }

    /**
     * Finds the Moon: over the whole frame ({@code near} null) or a square of half-side {@code reach} around where it
     * was. {@code rowStride} is in samples (shorts). {@code rExp} is its expected radius (px; NaN if unknown): a patch
     * much larger is not the Moon (a lit cloud, a lamp).
     */
    public static Spot find(ShortBuffer raw, int rowStride, int w, int h, SensorSpec spec, double rExp, Spot near, int reach) {
        Spot s = new Spot();
        int gp = greenPlane(spec), gx = gp & 1, gy = gp >> 1;
        float black = spec.black[gp], range = Math.max(1, spec.white - black);
        int x0 = 0, y0 = 0, x1 = w, y1 = h;
        if (near != null && near.ok) {
            x0 = Math.max(0, (int) (near.x - reach));
            y0 = Math.max(0, (int) (near.y - reach));
            x1 = Math.min(w, (int) (near.x + reach));
            y1 = Math.min(h, (int) (near.y + reach));
        }
        // a grid of about 200 x 150 samples over the window, on green places
        int span = Math.max(x1 - x0, y1 - y0);
        int step = Math.max(2, (span / 200) & ~1);
        if (!Double.isNaN(rExp)) step = Math.max(2, Math.min(step, ((int) (rExp / 4)) & ~1));
        int sx0 = (x0 & ~1) + gx, sy0 = (y0 & ~1) + gy;
        int nx = Math.max(0, (x1 - 1 - sx0) / step + 1), ny = Math.max(0, (y1 - 1 - sy0) / step + 1);
        if (nx < 3 || ny < 3) return s;
        float[] g = new float[nx * ny];
        float max = -1;
        for (int j = 0; j < ny; j++) {
            int y = sy0 + j * step, row = y * rowStride;
            for (int i = 0; i < nx; i++) {
                float v = ((raw.get(row + sx0 + i * step) & 0xffff) - black) / range;
                g[j * nx + i] = v;
                if (v > max) max = v;
            }
        }
        // the sky: the median of a sample of the grid, and its noise
        float[] sm = new float[Math.min(g.length, 4001)];
        int stepS = Math.max(1, g.length / sm.length), m = 0;
        for (int k = 0; k < g.length && m < sm.length; k += stepS) sm[m++] = g[k];
        Arrays.sort(sm, 0, m);
        float sky = sm[m / 2];
        for (int k = 0; k < m; k++) sm[k] = Math.abs(sm[k] - sky);
        Arrays.sort(sm, 0, m);
        float sigma = 1.4826f * sm[m / 2];
        if (max - sky < 0.02f) return s;
        float t = sky + Math.max(10 * sigma, 0.03f);
        // every bright patch (grown through samples over the threshold, a gap of one allowed); the Moon is the biggest
        // of the right size: a street light is too small, a lit cloud too big
        boolean[] in = new boolean[g.length];
        int[] queue = new int[g.length];
        int bestCount = 0;
        double bestX = 0, bestY = 0, bestR = 0;
        float bestPeak = 0;
        for (int start = 0; start < g.length; start++) {
            if (in[start] || g[start] <= t) continue;
            int qh = 0, qt = 0;
            queue[qt++] = start;
            in[start] = true;
            int minI = nx, maxI = -1, minJ = ny, maxJ = -1, count = 0;
            float peak = 0;
            while (qh < qt) {
                int k = queue[qh++];
                int i = k % nx, j = k / nx;
                count++;
                peak = Math.max(peak, g[k]);
                if (i < minI) minI = i;
                if (i > maxI) maxI = i;
                if (j < minJ) minJ = j;
                if (j > maxJ) maxJ = j;
                for (int dj = -2; dj <= 2; dj++) {
                    int jj = j + dj;
                    if (jj < 0 || jj >= ny) continue;
                    for (int di = -2; di <= 2; di++) {
                        int ii = i + di;
                        if (ii < 0 || ii >= nx) continue;
                        int kk = jj * nx + ii;
                        if (in[kk] || g[kk] <= t) continue;
                        in[kk] = true;
                        queue[qt++] = kk;
                    }
                }
            }
            double r = Math.max(maxI - minI + 1, maxJ - minJ + 1) * step / 2.0;
            if (!Double.isNaN(rExp) && (r < 0.35 * rExp || r > 2.2 * rExp)) continue;
            if (count > bestCount) {
                bestCount = count;
                bestX = sx0 + (minI + maxI) / 2.0 * step;
                bestY = sy0 + (minJ + maxJ) / 2.0 * step;
                bestR = r;
                bestPeak = peak;
            }
        }
        if (bestCount == 0) return s;
        s.x = bestX;
        s.y = bestY;
        s.r = bestR;
        s.samples = bestCount;
        t = sky + 0.3f * (bestPeak - sky);
        // at the sensor's own pixels: the bright parts' level and the clipped share (green, every other row and column)
        int bx0 = Math.max(0, (int) (s.x - s.r - step)), bx1 = Math.min(w, (int) (s.x + s.r + step));
        int by0 = Math.max(0, (int) (s.y - s.r - step)), by1 = Math.min(h, (int) (s.y + s.r + step));
        int pStep = Math.max(2, (int) (s.r / 60) * 2);
        float[] lit = new float[((bx1 - bx0) / pStep + 2) * ((by1 - by0) / pStep + 2)];
        int nl = 0, clipped = 0;
        int satAt = (int) spec.white - 2;
        for (int y = (by0 & ~1) + gy; y < by1; y += pStep) {
            int row = y * rowStride;
            for (int x = (bx0 & ~1) + gx; x < bx1; x += pStep) {
                int raw16 = raw.get(row + x) & 0xffff;
                float v = (raw16 - black) / range;
                if (v <= t) continue;
                if (nl < lit.length) lit[nl++] = v;
                if (raw16 >= satAt) clipped++;
            }
        }
        if (nl < 4) return s;
        Arrays.sort(lit, 0, nl);
        s.level = lit[Math.min(nl - 1, (int) Math.round(0.997 * (nl - 1)))];
        s.clipped = clipped / (double) nl;
        s.ok = true;
        return s;
    }

    /** A crop of the RAW frame (w x h at x0, y0: even, so its colours line up), copied out. */
    public static short[] crop(ShortBuffer raw, int rowStride, int fw, int fh, int x0, int y0, int w, int h) {
        short[] out = new short[w * h];
        for (int j = 0; j < h; j++) {
            int y = Math.max(0, Math.min(fh - 1, y0 + j));
            int row = y * rowStride;
            for (int i = 0; i < w; i++) {
                int x = Math.max(0, Math.min(fw - 1, x0 + i));
                out[j * w + i] = raw.get(row + x);
            }
        }
        return out;
    }

    /** The crop's corner for a Moon at (x, y): {@code side} px square around it, at even places, inside the frame. */
    public static int[] corner(double x, double y, int side, int fw, int fh) {
        int x0 = 2 * (int) Math.round((x - side / 2.0) / 2), y0 = 2 * (int) Math.round((y - side / 2.0) / 2);
        x0 = Math.max(0, Math.min((fw - side) & ~1, x0));
        y0 = Math.max(0, Math.min((fh - side) & ~1, y0));
        return new int[]{x0, y0};
    }

    /** The crop's side for a Moon of radius r (px): room for the hand's shake around it, even. */
    public static int side(double r) {
        int s = (int) Math.ceil(2 * r * 1.45);
        s = Math.max(96, s + (s & 1));
        return s;
    }
}
