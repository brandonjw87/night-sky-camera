package com.brandon.nightsky.processing;

/**
 * Lens shading (vignetting + color shading) gain map for each CFA plane, on a coarse grid that spans
 * the active area of the RAW image. Gains are >= 1 and are multiplied into black-subtracted RAW values.
 */
public final class ShadingMap {
    public final int cols, rows;
    /** gains[(plane * rows + r) * cols + c] */
    public final float[] gains;
    /** RAW continuous coordinates of the first and last grid samples. */
    public final double x0, y0, x1, y1;

    public ShadingMap(int cols, int rows, float[] gains, double x0, double y0, double x1, double y1) {
        this.cols = cols;
        this.rows = rows;
        this.gains = gains;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    public float gain(int plane, double x, double y) {
        double gx = (x - x0) / (x1 - x0) * (cols - 1);
        double gy = (y - y0) / (y1 - y0) * (rows - 1);
        if (gx < 0) gx = 0;
        if (gy < 0) gy = 0;
        if (gx > cols - 1) gx = cols - 1;
        if (gy > rows - 1) gy = rows - 1;
        int c = Math.min((int) gx, cols - 2);
        int r = Math.min((int) gy, rows - 2);
        if (cols < 2 || rows < 2) return gains[plane * rows * cols];
        double fx = gx - c, fy = gy - r;
        int base = (plane * rows + r) * cols + c;
        double g00 = gains[base], g10 = gains[base + 1], g01 = gains[base + cols], g11 = gains[base + cols + 1];
        return (float) ((g00 * (1 - fx) + g10 * fx) * (1 - fy) + (g01 * (1 - fx) + g11 * fx) * fy);
    }

    public float maxGain() {
        float m = 1f;
        for (float g : gains) if (g > m) m = g;
        return m;
    }

    /** True if every gain is ~1 (a map that does nothing). */
    public boolean isUnity() {
        for (float g : gains) if (Math.abs(g - 1f) > 1e-3f) return false;
        return true;
    }

    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(cols).append(',').append(rows).append(',').append(x0).append(',').append(y0).append(',')
                .append(x1).append(',').append(y1);
        for (float g : gains) sb.append(',').append(g);
        return sb.toString();
    }

    public static ShadingMap decode(String s) {
        if (s == null || s.isEmpty()) return null;
        String[] p = s.split(",");
        int cols = Integer.parseInt(p[0]), rows = Integer.parseInt(p[1]);
        double x0 = Double.parseDouble(p[2]), y0 = Double.parseDouble(p[3]);
        double x1 = Double.parseDouble(p[4]), y1 = Double.parseDouble(p[5]);
        float[] g = new float[4 * cols * rows];
        for (int i = 0; i < g.length; i++) g[i] = Float.parseFloat(p[6 + i]);
        return new ShadingMap(cols, rows, g, x0, y0, x1, y1);
    }
}
