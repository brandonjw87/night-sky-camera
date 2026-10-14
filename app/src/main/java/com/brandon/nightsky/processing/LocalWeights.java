package com.brandon.nightsky.processing;

/**
 * A coarse grid of per-region weight factors for one frame, in reference-frame pixel coordinates, used to
 * down-weight only the clouded part of a partly cloudy frame. Values are interpolated bilinearly between
 * cell centres.
 */
public final class LocalWeights {
    public final int gx, gy, width, height;
    public final float[] w;

    public LocalWeights(int gx, int gy, int width, int height) {
        this.gx = gx;
        this.gy = gy;
        this.width = width;
        this.height = height;
        w = new float[gx * gy];
        java.util.Arrays.fill(w, 1f);
    }

    public int cellOf(double x, double y) {
        int cx = Stats.clamp((int) (x * gx / width), 0, gx - 1);
        int cy = Stats.clamp((int) (y * gy / height), 0, gy - 1);
        return cy * gx + cx;
    }

    /** Fills {@code out[x]} for every pixel of row y (continuous centre y + 0.5). */
    public void row(int y, float[] out) {
        double fy = (y + 0.5) * gy / height - 0.5;
        int ya = (int) Math.floor(fy);
        double ty = fy - ya;
        if (ya < 0) {
            ya = 0;
            ty = 0;
        }
        if (ya >= gy - 1) {
            ya = gy - 1;
            ty = 0;
        }
        int yb = Math.min(ya + 1, gy - 1);
        for (int x = 0; x < width; x++) {
            double fx = (x + 0.5) * gx / width - 0.5;
            int xa = (int) Math.floor(fx);
            double tx = fx - xa;
            if (xa < 0) {
                xa = 0;
                tx = 0;
            }
            if (xa >= gx - 1) {
                xa = gx - 1;
                tx = 0;
            }
            int xb = Math.min(xa + 1, gx - 1);
            double top = w[ya * gx + xa] * (1 - tx) + w[ya * gx + xb] * tx;
            double bot = w[yb * gx + xa] * (1 - tx) + w[yb * gx + xb] * tx;
            out[x] = (float) (top * (1 - ty) + bot * ty);
        }
    }

    public float min() {
        float m = Float.MAX_VALUE;
        for (float v : w) m = Math.min(m, v);
        return m;
    }

    public float mean() {
        double s = 0;
        for (float v : w) s += v;
        return (float) (s / w.length);
    }
}
