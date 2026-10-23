package com.brandon.nightsky.processing;

import java.util.Locale;

/**
 * How a rendered photo is cut and turned (G3): a crop to 4:3, 3:2, 16:9 or 1:1, and a turn that puts celestial
 * north up (from the plate solution, D3). It works on the rendered pixels, so the stretch and colours don't change.
 * The crop is the largest rectangle of the ratio, centred, that the photo fills: along the photo's long side, or
 * once the photo is turned, along whichever side gives the bigger picture. A crop alone copies pixels; a turn
 * resamples with a Catmull-Rom cubic held within its four nearest pixels, which keeps stars sharp without dark
 * rings. The same framing of the same pixels gives the same photo, bit for bit.
 */
public final class Framing {
    /** The rendered photo's size, and the framed one's. */
    public final int w, h, outW, outH;
    /** Top left of the crop in the photo, when it isn't turned. */
    final int x0, y0;
    /** North's direction in the photo (unit vector, x right, y down) when it's turned. */
    final double nx, ny;
    final boolean turned;
    /** The ratio as asked ("full", "4:3", ...). */
    public final String crop;

    private Framing(String crop, int w, int h, int outW, int outH, int x0, int y0, double nx, double ny, boolean turned) {
        this.crop = crop;
        this.w = w;
        this.h = h;
        this.outW = outW;
        this.outH = outH;
        this.x0 = x0;
        this.y0 = y0;
        this.nx = nx;
        this.ny = ny;
        this.turned = turned;
    }

    /** True if it leaves the photo as it is. */
    public boolean identity() {
        return !turned && outW == w && outH == h;
    }

    /**
     * The framing of a w x h photo: {@code crop} as a recipe names it ({@link Recipe#CROPS}; anything else is the
     * whole photo), and north's direction in the photo (x right, y down), or null to leave it unturned.
     */
    public static Framing of(String crop, double[] north, int w, int h) {
        if (crop == null) crop = Recipe.CROP_FULL;
        int[] pq = ratio(crop, w, h);
        int p = pq[0], q = pq[1];
        boolean full = !crop.contains(":");
        if (north != null) {
            double n = Math.hypot(north[0], north[1]);
            if (n > 0 && !(Math.abs(north[0] / n) < 1e-9 && north[1] < 0)) {
                double nx = north[0] / n, ny = north[1] / n;
                // the turn takes north to up: cos = -ny, sin = -nx; the largest centred rectangle of the ratio inside it
                double c = Math.abs(ny), s = Math.abs(nx), a = w / 2.0, b = h / 2.0;
                double best = -1, bw = 0, bh = 0;
                boolean landscape = w >= h;
                for (int o = 0; o < 2; o++) {
                    // o = 0: along the photo's own long side; o = 1: across it
                    boolean wide = (o == 0) == landscape;
                    if (o == 1 && p == q) break;
                    double r = wide ? p / (double) q : q / (double) p;
                    double t = Math.min(a / (r * c + s), b / (r * s + c));
                    double area = r * t * t;
                    if (area > best * (1 + 1e-9)) {
                        best = area;
                        bw = 2 * r * t;
                        bh = 2 * t;
                    }
                }
                int ow = Math.max(1, (int) Math.floor(bw + 1e-9)), oh = Math.max(1, (int) Math.floor(bh + 1e-9));
                return new Framing(crop, w, h, ow, oh, 0, 0, nx, ny, true);
            }
        }
        if (full) return new Framing(crop, w, h, w, h, 0, 0, 0, -1, false);
        // the long side of the ratio along the photo's long side
        int rw = w >= h ? p : q, rh = w >= h ? q : p;
        int ow, oh;
        if ((long) w * rh >= (long) h * rw) {
            oh = h;
            ow = (int) Math.min(w, Math.round(h * (double) rw / rh));
        } else {
            ow = w;
            oh = (int) Math.min(h, Math.round(w * (double) rh / rw));
        }
        return new Framing(crop, w, h, ow, oh, (w - ow) / 2, (h - oh) / 2, 0, -1, false);
    }

    /** A crop's ratio as long side : short side; the photo's own for "full". */
    static int[] ratio(String crop, int w, int h) {
        int i = crop.indexOf(':');
        if (i > 0) {
            try {
                int a = Integer.parseInt(crop.substring(0, i).trim()), b = Integer.parseInt(crop.substring(i + 1).trim());
                if (a > 0 && b > 0) return new int[]{Math.max(a, b), Math.min(a, b)};
            } catch (NumberFormatException ignored) {
            }
        }
        return new int[]{Math.max(w, h), Math.max(1, Math.min(w, h))};
    }

    /** The framed photo (row after row, outW x outH); the same array when nothing changes. */
    public int[] apply(final int[] src) {
        if (src.length != w * h) throw new IllegalArgumentException("not a " + w + "x" + h + " photo");
        if (identity()) return src;
        final int[] out = new int[outW * outH];
        if (!turned) {
            for (int y = 0; y < outH; y++) System.arraycopy(src, (y0 + y) * w + x0, out, y * outW, outW);
            return out;
        }
        Parallel.forRange(outH, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] wx = new float[4], wy = new float[4];
                double cx = w / 2.0, cy = h / 2.0, hw = outW / 2.0, hh = outH / 2.0;
                for (int j = j0; j < j1; j++) {
                    double qy = j + 0.5 - hh;
                    for (int i = 0; i < outW; i++) {
                        double qx = i + 0.5 - hw;
                        // the turn's inverse: output offset to the photo's (north up)
                        double sx = cx - ny * qx - nx * qy - 0.5, sy = cy + nx * qx - ny * qy - 0.5;
                        out[j * outW + i] = sample(src, sx, sy, wx, wy);
                    }
                }
            }
        });
        return out;
    }

    /** Catmull-Rom at (x, y) in pixel-index coordinates, each channel held within its four nearest pixels. */
    private int sample(int[] src, double x, double y, float[] wx, float[] wy) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        weights((float) (x - ix), wx);
        weights((float) (y - iy), wy);
        float r = 0, g = 0, b = 0;
        int lr = 255, lg = 255, lb = 255, hr = 0, hg = 0, hb = 0;
        for (int m = 0; m < 4; m++) {
            int yy = Math.min(h - 1, Math.max(0, iy - 1 + m));
            int row = yy * w;
            float ry = 0, gy = 0, by = 0;
            for (int k = 0; k < 4; k++) {
                int xx = Math.min(w - 1, Math.max(0, ix - 1 + k));
                int v = src[row + xx];
                int pr = (v >> 16) & 0xFF, pg = (v >> 8) & 0xFF, pb = v & 0xFF;
                float f = wx[k];
                ry += f * pr;
                gy += f * pg;
                by += f * pb;
                if ((m == 1 || m == 2) && (k == 1 || k == 2)) {
                    lr = Math.min(lr, pr);
                    lg = Math.min(lg, pg);
                    lb = Math.min(lb, pb);
                    hr = Math.max(hr, pr);
                    hg = Math.max(hg, pg);
                    hb = Math.max(hb, pb);
                }
            }
            r += wy[m] * ry;
            g += wy[m] * gy;
            b += wy[m] * by;
        }
        return 0xFF000000 | (held(r, lr, hr) << 16) | (held(g, lg, hg) << 8) | held(b, lb, hb);
    }

    private static int held(float v, int lo, int hi) {
        int q = Math.round(v);
        return q < lo ? lo : (q > hi ? hi : q);
    }

    private static void weights(float t, float[] w) {
        float t2 = t * t, t3 = t2 * t;
        w[0] = 0.5f * (-t3 + 2 * t2 - t);
        w[1] = 0.5f * (3 * t3 - 5 * t2 + 2);
        w[2] = 0.5f * (-3 * t3 + 4 * t2 + t);
        w[3] = 0.5f * (t3 - t2);
    }

    /**
     * Celestial north's direction at pixel (x, y) of the stack (x right, y down; unit length), from the lens model
     * and where the camera pointed (camera bearings to J2000); null if either is unknown, or (x, y) is at a pole.
     */
    public static double[] north(CameraModel model, double[] R, double x, double y) {
        if (model == null || R == null || R.length != 9) return null;
        double[] b = new double[3], d = new double[3];
        model.unproject(x, y, b);
        Rot.apply(R, b[0], b[1], b[2], d);
        double dn = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        if (!(dn > 0)) return null;
        d[0] /= dn;
        d[1] /= dn;
        d[2] /= dn;
        // towards the north celestial pole, across the line of sight
        double px = -d[0] * d[2], py = -d[1] * d[2], pz = 1 - d[2] * d[2];
        double pn = Math.sqrt(px * px + py * py + pz * pz);
        if (pn < 1e-6) return null;
        double e = 1e-3 / pn;
        double[] cb = new double[3], p = new double[2];
        Rot.apply(Rot.transpose(R), d[0] + e * px, d[1] + e * py, d[2] + e * pz, cb);
        if (!model.project(cb[0], cb[1], cb[2], p)) return null;
        double vx = p[0] - x, vy = p[1] - y, vn = Math.hypot(vx, vy);
        if (!(vn > 0)) return null;
        return new double[]{vx / vn, vy / vn};
    }

    /** How far the photo turns to put north up (degrees, clockwise on the screen), for the log. */
    public double turnDegrees() {
        return turned ? Math.toDegrees(Math.atan2(-nx, -ny)) : 0;
    }

    public String describe() {
        if (identity()) return "the whole photo";
        StringBuilder sb = new StringBuilder();
        if (turned) sb.append(String.format(Locale.US, "turned %.1f degrees so north is up, ", turnDegrees()));
        sb.append(crop.contains(":") ? "cropped to " + crop : "cropped to the photo's own shape").append(String.format(Locale.US,
                " (%dx%d of %dx%d)", outW, outH, w, h));
        return sb.toString();
    }
}
