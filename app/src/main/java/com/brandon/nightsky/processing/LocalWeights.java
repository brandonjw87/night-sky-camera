package com.brandon.nightsky.processing;

/**
 * A coarse grid of per-region factors for one frame, in reference-frame pixel coordinates: which parts of a
 * partly cloudy frame to use, and how much to scale them. Values are interpolated bilinearly between cell
 * centres.
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

    /** Value at continuous position (x, y), held constant beyond the outer cell centres. */
    public float at(double x, double y) {
        return BackgroundGrid.interp(w, gx, gy, width, height, x, y);
    }

    /** The smallest of the cell values that {@link #at} interpolates between at (x, y). */
    public float minAt(double x, double y) {
        double fx = x * gx / width - 0.5, fy = y * gy / height - 0.5;
        int xa = Stats.clamp((int) Math.floor(fx), 0, gx - 1), ya = Stats.clamp((int) Math.floor(fy), 0, gy - 1);
        int xb = fx > xa && xa + 1 < gx ? xa + 1 : xa, yb = fy > ya && ya + 1 < gy ? ya + 1 : ya;
        return Math.min(Math.min(w[ya * gx + xa], w[ya * gx + xb]), Math.min(w[yb * gx + xa], w[yb * gx + xb]));
    }
}
