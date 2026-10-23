package com.brandon.nightsky.processing;

import java.nio.ByteBuffer;

/**
 * Sharpness of a picture by the Tenengrad measure (F7's daylight calibration): the mean squared Sobel gradient
 * over the middle of an 8-bit luminance plane, divided by the mean level squared so that a small change of
 * exposure between frames doesn't count as a change of sharpness. Sharper focus, stronger edges, higher score.
 */
public final class Tenengrad {
    private Tenengrad() {
    }

    /**
     * @param y          luminance samples (unsigned bytes)
     * @param rowStride  bytes from one row to the next
     * @param pixStride  bytes from one sample to the next (1 for a Y plane)
     * @param frac       side of the middle part measured, as a share of the picture's (0.5: the middle quarter)
     * @return the score, 0 for a flat or black picture
     */
    public static double score(ByteBuffer y, int width, int height, int rowStride, int pixStride, double frac) {
        int w = Math.max(8, (int) (width * frac)), h = Math.max(8, (int) (height * frac));
        int x0 = (width - w) / 2, y0 = (height - h) / 2;
        double sum = 0, level = 0;
        long n = 0;
        int base = y.position();
        for (int j = y0 + 1; j < y0 + h - 1; j++) {
            int up = base + (j - 1) * rowStride, mid = base + j * rowStride, dn = base + (j + 1) * rowStride;
            for (int i = x0 + 1; i < x0 + w - 1; i++) {
                int l = (i - 1) * pixStride, c = i * pixStride, r = (i + 1) * pixStride;
                int a = y.get(up + l) & 0xFF, b = y.get(up + c) & 0xFF, cc = y.get(up + r) & 0xFF;
                int d = y.get(mid + l) & 0xFF, f = y.get(mid + r) & 0xFF;
                int g = y.get(dn + l) & 0xFF, hh = y.get(dn + c) & 0xFF, k = y.get(dn + r) & 0xFF;
                int gx = (cc + 2 * f + k) - (a + 2 * d + g);
                int gy = (g + 2 * hh + k) - (a + 2 * b + cc);
                sum += (double) gx * gx + (double) gy * gy;
                level += y.get(mid + c) & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0;
        double mean = level / n;
        if (mean < 2) return 0;
        return sum / n / (mean * mean);
    }

    /** The same on a float image (row-major, width x height), for tests. */
    public static double score(float[] img, int width, int height, double frac) {
        int w = Math.max(8, (int) (width * frac)), h = Math.max(8, (int) (height * frac));
        int x0 = (width - w) / 2, y0 = (height - h) / 2;
        double sum = 0, level = 0;
        long n = 0;
        for (int j = y0 + 1; j < y0 + h - 1; j++) {
            for (int i = x0 + 1; i < x0 + w - 1; i++) {
                double a = img[(j - 1) * width + i - 1], b = img[(j - 1) * width + i], c = img[(j - 1) * width + i + 1];
                double d = img[j * width + i - 1], f = img[j * width + i + 1];
                double g = img[(j + 1) * width + i - 1], hh = img[(j + 1) * width + i], k = img[(j + 1) * width + i + 1];
                double gx = (c + 2 * f + k) - (a + 2 * d + g), gy = (g + 2 * hh + k) - (a + 2 * b + c);
                sum += gx * gx + gy * gy;
                level += img[j * width + i];
                n++;
            }
        }
        if (n == 0) return 0;
        double mean = level / n;
        if (mean <= 0) return 0;
        return sum / n / (mean * mean);
    }
}
