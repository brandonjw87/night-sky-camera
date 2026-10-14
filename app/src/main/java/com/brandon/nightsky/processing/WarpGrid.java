package com.brandon.nightsky.processing;

/**
 * Coarse sampling of the map "pixel in the reference frame -> pixel in another frame" for a sky rotation R
 * (undistort, rotate, re-distort). Values between nodes are interpolated bilinearly; the map is smooth, so a
 * 32 px node spacing is accurate to a few thousandths of a pixel.
 */
public final class WarpGrid {
    public final int step, nx, ny;
    public final float[] mx, my;

    private WarpGrid(int step, int nx, int ny) {
        this.step = step;
        this.nx = nx;
        this.ny = ny;
        mx = new float[nx * ny];
        my = new float[nx * ny];
    }

    public static int nodesX(int width, int step) {
        return width / step + 2;
    }

    public static int nodesY(int height, int step) {
        return height / step + 2;
    }

    /** Bearings of all grid nodes in the reference frame. */
    public static double[] nodeBearings(CameraModel m, int width, int height, int step) {
        int nx = nodesX(width, step), ny = nodesY(height, step);
        double[] b = new double[3 * nx * ny];
        double[] t = new double[3];
        for (int iy = 0; iy < ny; iy++) {
            for (int ix = 0; ix < nx; ix++) {
                m.unproject(ix * (double) step, iy * (double) step, t);
                int k = 3 * (iy * nx + ix);
                b[k] = t[0];
                b[k + 1] = t[1];
                b[k + 2] = t[2];
            }
        }
        return b;
    }

    public static WarpGrid build(CameraModel m, double[] nodeB, int width, int height, int step, double[] R) {
        int nx = nodesX(width, step), ny = nodesY(height, step);
        WarpGrid g = new WarpGrid(step, nx, ny);
        double[] b = new double[3], p = new double[2];
        for (int k = 0; k < nx * ny; k++) {
            Rot.apply(R, nodeB[3 * k], nodeB[3 * k + 1], nodeB[3 * k + 2], b);
            if (m.project(b[0], b[1], b[2], p)) {
                g.mx[k] = (float) p[0];
                g.my[k] = (float) p[1];
            } else {
                g.mx[k] = -1e6f;
                g.my[k] = -1e6f;
            }
        }
        return g;
    }

    /** Fills the per-node mapping for continuous reference row coordinate yc (= y + 0.5). */
    public void row(double yc, float[] rx, float[] ry) {
        double gy = yc / step;
        int iy = (int) gy;
        if (iy > ny - 2) iy = ny - 2;
        if (iy < 0) iy = 0;
        float fy = (float) (gy - iy);
        int a = iy * nx, b = a + nx;
        for (int ix = 0; ix < nx; ix++) {
            rx[ix] = mx[a + ix] + (mx[b + ix] - mx[a + ix]) * fy;
            ry[ix] = my[a + ix] + (my[b + ix] - my[a + ix]) * fy;
        }
    }

    /** Smallest and largest mapped y over reference rows [y0, y1). */
    public float[] mappedRowRange(int y0, int y1) {
        int iy0 = Math.max(0, y0 / step), iy1 = Math.min(ny - 1, y1 / step + 1);
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (int iy = iy0; iy <= iy1; iy++) {
            for (int ix = 0; ix < nx; ix++) {
                float v = my[iy * nx + ix];
                if (v < -1e5f) continue;
                if (v < lo) lo = v;
                if (v > hi) hi = v;
            }
        }
        return new float[]{lo, hi};
    }
}
