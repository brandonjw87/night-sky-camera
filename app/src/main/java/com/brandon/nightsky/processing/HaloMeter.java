package com.brandon.nightsky.processing;

/**
 * How much of the stars' light lands in a halo around them: the light in a ring 3 to 8 star radii out (at
 * least 4 to 20 pixels) over the light in the core (within 2 radii, at least 2.5 pixels), with the sky taken
 * from a ring further out; the median over the brightest unclipped, isolated stars in the middle of the frame.
 * The ring's light is measured as a smooth glow (the median level of each of a few narrower rings), so the
 * faint stars that happen to lie in it don't count: a rich Milky Way field reads the same as a sparse one.
 * A clean lens keeps it small and the same night after night for a given camera; a smudge, a fingerprint or
 * dew scatters light into a glow and raises it.
 */
public final class HaloMeter {
    public static final class Result {
        /** Ring light over core light; NaN if too few stars. */
        public double ratio = Double.NaN;
        public int stars;
        /** Typical half-flux radius of the stars used (pixels). */
        public double hfr = Double.NaN;
    }

    /** Stars used at most, and tried at most. */
    static final int MAX_STARS = 40, MAX_TRIED = 300;
    /** A star's core must outshine the noise of its ring this many times (each ratio then within ~0.07). */
    static final double MIN_SNR = 15;
    /** Narrow rings the halo ring is measured in. */
    static final int RINGS = 4;

    private HaloMeter() {}

    /**
     * @param noiseHalf noise of the half-resolution green image (ADU), as {@link StarList#noise}
     */
    public static Result measure(short[] raw, float invScale, float[] black, SensorSpec spec, StarList stars, float noiseHalf) {
        Result res = new Result();
        int W = spec.width, H = spec.height;
        double cx = W / 2.0, cy = H / 2.0, reach = 0.75 * Math.hypot(cx, cy);
        // typical star size from the brightest unclipped stars
        float[] hs = new float[30];
        int nh = 0;
        for (int i = 0; i < stars.count && nh < hs.length; i++) {
            if (stars.saturated[i] || stars.elong[i] > 1.6f) continue;
            hs[nh++] = stars.hfr[i];
        }
        if (nh < 5) return res;
        double h = Stats.median(hs, nh);
        res.hfr = h;
        double rCore = Math.max(2.5, 2 * h), rIn = Math.max(4, 3 * h), rOut = Math.max(20, 8 * h);
        double bgIn = rOut + 4, bgOut = rOut + 12;
        int R = (int) Math.ceil(bgOut);
        double sigRaw = Math.max(0.3, noiseHalf) * Math.sqrt(2);
        double ringArea = Math.PI * (rOut * rOut - rIn * rIn);
        double minCore = MIN_SNR * sigRaw * Math.sqrt(ringArea);
        double[] ratios = new double[MAX_STARS];
        int n = 0;
        float[][] bgv = new float[4][(2 * R + 1) * (2 * R + 1) / 4 + 8];
        int[] nb = new int[4];
        float[][][] ringv = new float[RINGS][4][(2 * R + 1) * (2 * R + 1) / 4 + 8];
        int[][] nr = new int[RINGS][4];
        float sat = 0.85f * spec.white;
        for (int i = 0; i < stars.count && i < MAX_TRIED && n < MAX_STARS; i++) {
            if (stars.saturated[i] || stars.elong[i] > 1.6f) continue;
            double x = stars.x[i], y = stars.y[i];
            if (Math.hypot(x - cx, y - cy) > reach) continue;
            if (x < R + 2 || y < R + 2 || x >= W - R - 2 || y >= H - R - 2) continue;
            // isolated: no comparable star in its ring, no much brighter one near enough to light it
            boolean crowded = false;
            for (int j = 0; j < stars.count && !crowded; j++) {
                if (j == i) continue;
                double d = Math.hypot(stars.x[j] - x, stars.y[j] - y);
                if ((d < rOut + 2 && stars.flux[j] > 0.2f * stars.flux[i]) || (d < bgOut + rOut && stars.flux[j] > 2f * stars.flux[i])) {
                    crowded = true;
                }
            }
            if (crowded) continue;
            int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
            for (int q = 0; q < 4; q++) nb[q] = 0;
            boolean clipped = false;
            for (int dy = -R; dy <= R; dy++) {
                int yy = iy + dy;
                int o = yy * W;
                for (int dx = -R; dx <= R; dx++) {
                    int xx = ix + dx;
                    double r = Math.hypot(xx + 0.5 - x, yy + 0.5 - y);
                    int q = ((yy & 1) << 1) | (xx & 1);
                    float v = (raw[o + xx] & 0xFFFF) * invScale;
                    if (r < rCore && v >= sat) clipped = true;
                    if (r >= bgIn && r < bgOut) bgv[q][nb[q]++] = v;
                }
            }
            if (clipped) continue;
            float[] bg = new float[4];
            for (int q = 0; q < 4; q++) bg[q] = nb[q] > 0 ? Stats.median(bgv[q], nb[q]) : black[q];
            double core = 0;
            for (int a = 0; a < RINGS; a++) for (int q = 0; q < 4; q++) nr[a][q] = 0;
            for (int dy = -R; dy <= R; dy++) {
                int yy = iy + dy;
                int o = yy * W;
                for (int dx = -R; dx <= R; dx++) {
                    int xx = ix + dx;
                    double r = Math.hypot(xx + 0.5 - x, yy + 0.5 - y);
                    if (r >= rOut) continue;
                    int q = ((yy & 1) << 1) | (xx & 1);
                    double v = (raw[o + xx] & 0xFFFF) * invScale - bg[q];
                    if (r < rCore) {
                        core += v;
                    } else if (r >= rIn) {
                        int a = Math.min(RINGS - 1, (int) (Math.log(r / rIn) / Math.log(rOut / rIn) * RINGS));
                        ringv[a][q][nr[a][q]++] = (float) v;
                    }
                }
            }
            if (core < minCore) continue;
            // each narrow ring's glow: its median level per colour site, times its area
            double ring = 0;
            for (int a = 0; a < RINGS; a++) {
                for (int q = 0; q < 4; q++) {
                    if (nr[a][q] > 0) ring += Stats.median(ringv[a][q], nr[a][q]) * nr[a][q];
                }
            }
            ratios[n++] = ring / core;
        }
        res.stars = n;
        if (n >= 5) res.ratio = Stats.median(ratios, n);
        return res;
    }
}
