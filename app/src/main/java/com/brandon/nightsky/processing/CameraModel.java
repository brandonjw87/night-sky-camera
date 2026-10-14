package com.brandon.nightsky.processing;

/**
 * Pinhole camera with Brown-Conrady distortion, in RAW pixel coordinates.
 * Continuous coordinates put the center of pixel (x, y) at (x + 0.5, y + 0.5), like Camera2.
 * Camera axes: +x right, +y down, +z out of the lens toward the scene.
 * Distortion follows android.lens.distortion: [k1, k2, k3, p1, p2] mapping undistorted
 * normalized coordinates to distorted ones (same convention as OpenCV).
 */
public final class CameraModel {
    public double fx, fy, cx, cy, skew;
    public double k1, k2, k3, p1, p2;
    public int width, height;
    /** Where the numbers came from, for logs. */
    public String source = "nominal";

    public CameraModel copy() {
        CameraModel m = new CameraModel();
        m.fx = fx;
        m.fy = fy;
        m.cx = cx;
        m.cy = cy;
        m.skew = skew;
        m.k1 = k1;
        m.k2 = k2;
        m.k3 = k3;
        m.p1 = p1;
        m.p2 = p2;
        m.width = width;
        m.height = height;
        m.source = source;
        return m;
    }

    public static CameraModel nominal(int width, int height, double fPixels) {
        CameraModel m = new CameraModel();
        m.width = width;
        m.height = height;
        m.fx = fPixels;
        m.fy = fPixels;
        m.cx = width / 2.0;
        m.cy = height / 2.0;
        return m;
    }

    /** Pixel -> unit bearing vector. */
    public void unproject(double u, double v, double[] out3) {
        double yd = (v - cy) / fy;
        double xd = (u - cx - skew * yd) / fx;
        double xn = xd, yn = yd;
        if (k1 != 0 || k2 != 0 || k3 != 0 || p1 != 0 || p2 != 0) {
            for (int it = 0; it < 25; it++) {
                double r2 = xn * xn + yn * yn;
                double rad = 1 + r2 * (k1 + r2 * (k2 + r2 * k3));
                if (rad < 0.2) rad = 0.2;
                double dx = 2 * p1 * xn * yn + p2 * (r2 + 2 * xn * xn);
                double dy = p1 * (r2 + 2 * yn * yn) + 2 * p2 * xn * yn;
                double nx = (xd - dx) / rad, ny = (yd - dy) / rad;
                double ch = Math.abs(nx - xn) + Math.abs(ny - yn);
                xn = nx;
                yn = ny;
                if (ch < 1e-12) break;
            }
        }
        double inv = 1.0 / Math.sqrt(xn * xn + yn * yn + 1.0);
        out3[0] = xn * inv;
        out3[1] = yn * inv;
        out3[2] = inv;
    }

    /** Bearing -> pixel. Returns false if the direction is behind the camera. */
    public boolean project(double X, double Y, double Z, double[] out2) {
        if (Z <= 1e-6) return false;
        double xn = X / Z, yn = Y / Z;
        double r2 = xn * xn + yn * yn;
        double rad = 1 + r2 * (k1 + r2 * (k2 + r2 * k3));
        double xd = xn * rad + 2 * p1 * xn * yn + p2 * (r2 + 2 * xn * xn);
        double yd = yn * rad + p1 * (r2 + 2 * yn * yn) + 2 * p2 * xn * yn;
        out2[0] = fx * xd + skew * yd + cx;
        out2[1] = fy * yd + cy;
        return true;
    }

    /** Approximate image displacement (pixels) of the sky rotation R at the frame center and corners. */
    public double maxDisplacement(double[] R) {
        double[] b = new double[3];
        double[] p = new double[2];
        double max = 0;
        double[][] pts = {{width / 2.0, height / 2.0}, {width * 0.05, height * 0.05}, {width * 0.95, height * 0.05},
                {width * 0.05, height * 0.95}, {width * 0.95, height * 0.95}};
        for (double[] q : pts) {
            unproject(q[0], q[1], b);
            Rot.apply(R, b[0], b[1], b[2], b);
            if (!project(b[0], b[1], b[2], p)) continue;
            double d = Math.hypot(p[0] - q[0], p[1] - q[1]);
            if (d > max) max = d;
        }
        return max;
    }

    /** Displacement of the image center under R, in pixels. */
    public double centerDisplacement(double[] R) {
        double[] b = new double[3];
        double[] p = new double[2];
        unproject(width / 2.0, height / 2.0, b);
        Rot.apply(R, b[0], b[1], b[2], b);
        if (!project(b[0], b[1], b[2], p)) return 0;
        return Math.hypot(p[0] - width / 2.0, p[1] - height / 2.0);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US,
                "fx=%.1f fy=%.1f cx=%.1f cy=%.1f k1=%.4f k2=%.4f k3=%.4f p1=%.5f p2=%.5f (%s)",
                fx, fy, cx, cy, k1, k2, k3, p1, p2, source);
    }
}
