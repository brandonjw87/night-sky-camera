package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Purple star halos (2.4). A phone's lens brings blue light (and some red) to a focus a little off the sensor, and the
 * Bayer pattern samples red and blue more sparsely than green, so in the stack a star's blue and red images come out
 * wider than its green one. Its colour, measured over a small window, then changes with the distance from its middle:
 * white or yellow in the core, a purple or blue ring around it (the fringe Siril's Unpurple and the like take out).
 * <p>
 * Measured on the stack's bright single stars (each colour's image size: the second moment of its light, as a Gaussian
 * sigma), the colours get windows that make all three the same size ({@link Renderer.Analysis#colorWindows}): the
 * narrower colours get wider windows, so the ratio of the colours is the star's own all the way out from its middle,
 * and the ring goes. Only the colour is measured that way: the brightness keeps its own sharpness (as LRGB imaging
 * takes its colour from a softer picture than its detail).
 */
public final class StarHalo {
    /** Each colour's star image size (Gaussian sigma, px; camera red, green, blue): the median over the stars measured. */
    public final float[] size;
    /** The colour windows (Gaussian sigma, px; camera red, green, blue) that take the halos out, or null: none needed. */
    public final float[] windows;
    /**
     * The ring's colour against the whole star's (2.5 to 6 px out): its share of the star's red and blue light over its
     * share of the green, less one (+0.2: the ring a fifth redder or bluer than the star), the median over the stars.
     */
    public final float ringRed, ringBlue;
    /** Stars measured. */
    public final int stars;

    /** At least this many single, unclipped, bright stars to measure on. */
    static final int MIN_STARS = 12, MOST_STARS = 400;
    /** A star measured: its peak this many times the noise over the sky around it, or more. */
    static final float PEAK_SNR = 40f;
    /** Light summed out to R_OUT px; the sky around it from R_SKY0 to R_SKY1 px; the ring from R_RING px. */
    static final float R_OUT = 6f, R_SKY0 = 9f, R_SKY1 = 12f, R_RING = 2.5f;
    /** No other light over NEIGHBOUR of the star's peak within ISOLATION px. */
    static final float NEIGHBOUR = 0.1f;
    static final int ISOLATION = 13;
    /** The windows are used when the colours' sizes differ by MIN_SPREAD px or more, and no more than MAX_SPREAD apart. */
    static final float MIN_SPREAD = 0.1f, MAX_SPREAD = 2.5f;

    private StarHalo(float[] size, float[] windows, float ringRed, float ringBlue, int stars) {
        this.size = size;
        this.windows = windows;
        this.ringRed = ringRed;
        this.ringBlue = ringBlue;
        this.stars = stars;
    }

    /** For the log. */
    public String describe() {
        return String.format(Locale.US, "star halos: %d stars, sizes r/g/b %.2f/%.2f/%.2f px, the ring %+.0f%% red %+.0f%% blue; %s", stars,
                size[0], size[1], size[2], 100 * ringRed, 100 * ringBlue, windows == null ? "the colours' sizes match, nothing to take out"
                        : String.format(Locale.US, "colour windows r/g/b %.2f/%.2f/%.2f px", windows[0], windows[1], windows[2]));
    }

    /**
     * Measures the halos of {@code a}'s crop of the stack {@code img} (camera colours), whose colour window is
     * {@code a.colorSigma}; null for star trails or too few stars to tell.
     */
    public static StarHalo measure(RgbImage img, Renderer.Analysis a) {
        if (a == null || a.trails || a.cw < 64 || a.ch < 64) return null;
        int W = img.width;
        int x0 = a.cx0, y0 = a.cy0, x1 = a.cx0 + a.cw, y1 = a.cy0 + a.ch;
        int edge = (int) Math.ceil(R_SKY1) + 2;
        float[] g = img.g;
        // the green's noise, from neighbours' differences (robust to the stars and the glow's slopes)
        int step = Math.max(1, (int) Math.sqrt((double) a.cw * a.ch / 60000));
        float[] d = new float[(a.cw / step + 1) * (a.ch / step + 1)], lv = new float[d.length];
        int nd = 0;
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x + 1 < x1; x += step) {
                int i = y * W + x;
                d[nd] = Math.abs(g[i] - g[i + 1]);
                lv[nd++] = g[i];
            }
        }
        if (nd < 100) return null;
        float noise = 1.4826f * Stats.median(d, nd) / (float) Math.sqrt(2);
        float sky = Stats.median(lv, nd);
        if (!(noise > 0)) return null;
        float thr = sky + PEAK_SNR * 0.5f * noise;
        // peaks: local maxima of the green over the threshold
        List<int[]> peaks = new ArrayList<int[]>();
        for (int y = y0 + edge; y < y1 - edge; y++) {
            int row = y * W;
            for (int x = x0 + edge; x < x1 - edge; x++) {
                int i = row + x;
                float v = g[i];
                if (v <= thr) continue;
                if (v < g[i - 1] || v <= g[i + 1] || v < g[i - W] || v <= g[i + W] || v < g[i - W - 1] || v < g[i - W + 1]
                        || v <= g[i + W - 1] || v <= g[i + W + 1]) continue;
                peaks.add(new int[]{x, y, Float.floatToIntBits(v - sky)});
                if (peaks.size() > 200000) break;
            }
        }
        if (peaks.size() < MIN_STARS) return null;
        // the brightest first; a peak counts as single when nothing over NEIGHBOUR of it is within ISOLATION px
        java.util.Collections.sort(peaks, new java.util.Comparator<int[]>() {
            @Override
            public int compare(int[] p, int[] q) {
                return Float.compare(Float.intBitsToFloat(q[2]), Float.intBitsToFloat(p[2]));
            }
        });
        int cs = 16, gw = a.cw / cs + 1, gh = a.ch / cs + 1;
        List<List<int[]>> grid = new ArrayList<List<int[]>>(gw * gh);
        for (int k = 0; k < gw * gh; k++) grid.add(null);
        for (int[] p : peaks) {
            int k = ((p[1] - y0) / cs) * gw + (p[0] - x0) / cs;
            List<int[]> l = grid.get(k);
            if (l == null) grid.set(k, l = new ArrayList<int[]>(2));
            l.add(p);
        }
        float[][] chan = {img.r, img.g, img.b};
        int R = (int) Math.ceil(R_SKY1);
        float[] ring = new float[(2 * R + 1) * (2 * R + 1)];
        float[] s2r = new float[MOST_STARS], s2g = new float[MOST_STARS], s2b = new float[MOST_STARS];
        float[] rr = new float[MOST_STARS], rb = new float[MOST_STARS];
        int n = 0;
        double[] F = new double[3], M2 = new double[3], RG = new double[3];
        float[] bg = new float[3];
        for (int[] p : peaks) {
            if (n >= MOST_STARS) break;
            int px = p[0], py = p[1];
            float peak = Float.intBitsToFloat(p[2]);
            if (!single(grid, gw, gh, cs, x0, y0, px, py, peak)) continue;
            if (clipped(img, a, px, py)) continue;
            // each colour's sky around it, and the green's noise there
            float gNoise = 0;
            for (int c = 0; c < 3; c++) {
                float[] v = chan[c];
                int k = 0;
                for (int dy = -R; dy <= R; dy++) {
                    for (int dx = -R; dx <= R; dx++) {
                        float rd = (float) Math.sqrt(dx * dx + dy * dy);
                        if (rd < R_SKY0 || rd > R_SKY1) continue;
                        ring[k++] = v[(py + dy) * W + px + dx];
                    }
                }
                float med = Stats.median(ring, k);
                if (c == 1) {
                    k = 0;
                    for (int dy = -R; dy <= R; dy++) {
                        for (int dx = -R; dx <= R; dx++) {
                            float rd = (float) Math.sqrt(dx * dx + dy * dy);
                            if (rd < R_SKY0 || rd > R_SKY1) continue;
                            ring[k++] = v[(py + dy) * W + px + dx];
                        }
                    }
                    gNoise = Stats.madSigma(ring, k, med);
                }
                bg[c] = med;
            }
            if (!(gNoise > 0) || g[py * W + px] - bg[1] < PEAK_SNR * gNoise) continue;
            // the middle, from the green's light near the peak
            double sx = 0, sy = 0, sw = 0;
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    float v = g[(py + dy) * W + px + dx] - bg[1];
                    if (v <= 0) continue;
                    sx += v * dx;
                    sy += v * dy;
                    sw += v;
                }
            }
            if (sw <= 0) continue;
            double cx = px + sx / sw, cy = py + sy / sw;
            int ro = (int) Math.ceil(R_OUT) + 1;
            boolean ok = true;
            for (int c = 0; c < 3 && ok; c++) {
                float[] v = chan[c];
                double f = 0, m2 = 0, rg = 0;
                for (int dy = -ro; dy <= ro; dy++) {
                    for (int dx = -ro; dx <= ro; dx++) {
                        double ex = px + dx - cx, ey = py + dy - cy, d2 = ex * ex + ey * ey;
                        if (d2 > R_OUT * R_OUT) continue;
                        double l = v[(py + dy) * W + px + dx] - bg[c];
                        f += l;
                        m2 += l * d2;
                        if (d2 > R_RING * R_RING) rg += l;
                    }
                }
                if (!(f > 0) || !(m2 > 0)) ok = false;
                F[c] = f;
                M2[c] = m2;
                RG[c] = rg;
            }
            if (!ok) continue;
            s2r[n] = (float) (M2[0] / (2 * F[0]));
            s2g[n] = (float) (M2[1] / (2 * F[1]));
            s2b[n] = (float) (M2[2] / (2 * F[2]));
            double gShare = RG[1] / F[1];
            rr[n] = gShare > 1e-6 ? (float) ((RG[0] / F[0]) / gShare - 1) : 0f;
            rb[n] = gShare > 1e-6 ? (float) ((RG[2] / F[2]) / gShare - 1) : 0f;
            n++;
        }
        if (n < MIN_STARS) return null;
        float[] size = {(float) Math.sqrt(Math.max(0.01f, Stats.median(s2r, n))), (float) Math.sqrt(Math.max(0.01f, Stats.median(s2g, n))),
                (float) Math.sqrt(Math.max(0.01f, Stats.median(s2b, n)))};
        float ringR = Stats.median(rr, n), ringB = Stats.median(rb, n);
        float widest = Math.max(size[0], Math.max(size[1], size[2])), narrowest = Math.min(size[0], Math.min(size[1], size[2]));
        float[] windows = null;
        if (widest - narrowest >= MIN_SPREAD && widest - narrowest <= MAX_SPREAD) {
            float base = Math.max(0.5f, a.colorSigma);
            windows = new float[3];
            for (int c = 0; c < 3; c++) windows[c] = (float) Math.sqrt(base * base + widest * widest - size[c] * size[c]);
        }
        return new StarHalo(size, windows, ringR, ringB, n);
    }

    /** Nothing over NEIGHBOUR of the peak's light within ISOLATION px of it (another star, or its own second peak). */
    private static boolean single(List<List<int[]>> grid, int gw, int gh, int cs, int x0, int y0, int px, int py, float peak) {
        int gx0 = Math.max(0, (px - x0 - ISOLATION) / cs), gx1 = Math.min(gw - 1, (px - x0 + ISOLATION) / cs);
        int gy0 = Math.max(0, (py - y0 - ISOLATION) / cs), gy1 = Math.min(gh - 1, (py - y0 + ISOLATION) / cs);
        for (int gy = gy0; gy <= gy1; gy++) {
            for (int gx = gx0; gx <= gx1; gx++) {
                List<int[]> l = grid.get(gy * gw + gx);
                if (l == null) continue;
                for (int[] q : l) {
                    if (q[0] == px && q[1] == py) continue;
                    int dx = q[0] - px, dy = q[1] - py;
                    if (dx * dx + dy * dy > ISOLATION * ISOLATION) continue;
                    if (Float.intBitsToFloat(q[2]) > NEIGHBOUR * peak) return false;
                }
            }
        }
        return true;
    }

    /** Whether any colour clipped (or nearly) within 3 px of (x, y), by the clip flags or the clip level. */
    private static boolean clipped(RgbImage img, Renderer.Analysis a, int x, int y) {
        int W = img.width;
        float[] gi = new float[3];
        for (int dy = -3; dy <= 3; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                int i = (y + dy) * W + x + dx;
                if (img.clip != null && (img.clip[i] & 0xFF) > 5) return true;
                clipInvAt(a, x + dx - a.cx0 + 0.5f, y + dy - a.cy0 + 0.5f, gi);
                if (img.r[i] * gi[0] > 0.7f || img.g[i] * gi[1] > 0.7f || img.b[i] * gi[2] > 0.7f) return true;
            }
        }
        return false;
    }

    /** 1 / clip level per colour at crop point (x, y) ({@link Renderer.Analysis#clipInv}). */
    static void clipInvAt(Renderer.Analysis a, float x, float y, float[] out) {
        if (a.clipInv == null) {
            float v = 1f / Math.max(1f, a.clip);
            out[0] = out[1] = out[2] = v;
            return;
        }
        int CGX = Renderer.CGX, CGY = Renderer.CGY;
        float fx = Math.min(CGX - 1.001f, Math.max(0f, x / a.cw * (CGX - 1))), fy = Math.min(CGY - 1.001f, Math.max(0f, y / a.ch * (CGY - 1)));
        int i = (int) fx, j = (int) fy;
        float tx = fx - i, ty = fy - j;
        for (int c = 0; c < 3; c++) {
            int b = c * CGY * CGX;
            float p = a.clipInv[b + j * CGX + i], q = a.clipInv[b + j * CGX + i + 1];
            float r = a.clipInv[b + (j + 1) * CGX + i], s = a.clipInv[b + (j + 1) * CGX + i + 1];
            out[c] = (p + tx * (q - p)) + ty * ((r + tx * (s - r)) - (p + tx * (q - p)));
        }
    }
}
