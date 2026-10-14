package com.brandon.nightsky.processing;

/**
 * Linear camera-RGB image (black level removed, lens shading corrected, in per-frame ADU) with a crop
 * rectangle marking the well-covered area.
 */
public final class RgbImage {
    public final int width, height;
    public final float[] r, g, b;
    /** Crop rectangle [x0, x1) x [y0, y1). */
    public int x0, y0, x1, y1;
    /**
     * Optional: per pixel, the fraction (0..255) of frames in which a sample behind it was clipped. Colour is
     * not trustworthy there (green clips first).
     */
    public byte[] clip;

    public RgbImage(int width, int height) {
        this(width, height, new float[width * height], new float[width * height], new float[width * height]);
    }

    public RgbImage(int width, int height, float[] r, float[] g, float[] b) {
        this.width = width;
        this.height = height;
        this.r = r;
        this.g = g;
        this.b = b;
        x1 = width;
        y1 = height;
    }

    public float[] channel(int c) {
        return c == 0 ? r : (c == 1 ? g : b);
    }

    public int cropWidth() {
        return x1 - x0;
    }

    public int cropHeight() {
        return y1 - y0;
    }

    /** Box-averaged copy of the crop area, {@code factor} x {@code factor} pixels per output pixel. */
    public RgbImage binned(final int factor) {
        final int w = cropWidth() / factor, h = cropHeight() / factor;
        final RgbImage o = new RgbImage(w, h);
        if (clip != null) o.clip = new byte[w * h];
        final float inv = 1f / (factor * factor);
        Parallel.forRange(h, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int c = 0; c < 3; c++) {
                    float[] src = channel(c), dst = o.channel(c);
                    for (int j = j0; j < j1; j++) {
                        int oo = j * w;
                        for (int dy = 0; dy < factor; dy++) {
                            int row = (y0 + j * factor + dy) * width + x0;
                            for (int i = 0; i < w; i++) {
                                int s = row + i * factor;
                                float acc = 0;
                                for (int dx = 0; dx < factor; dx++) acc += src[s + dx];
                                dst[oo + i] += acc;
                            }
                        }
                        for (int i = 0; i < w; i++) dst[oo + i] *= inv;
                    }
                }
                if (clip == null) return;
                for (int j = j0; j < j1; j++) {
                    for (int i = 0; i < w; i++) {
                        int mx = 0;
                        for (int dy = 0; dy < factor; dy++) {
                            int row = (y0 + j * factor + dy) * width + x0 + i * factor;
                            for (int dx = 0; dx < factor; dx++) mx = Math.max(mx, clip[row + dx] & 0xFF);
                        }
                        o.clip[j * w + i] = (byte) mx;
                    }
                }
            }
        });
        return o;
    }
}
