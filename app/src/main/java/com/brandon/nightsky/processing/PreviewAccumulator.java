package com.brandon.nightsky.processing;

/**
 * Quarter-resolution, star-aligned running stack used for the live progress picture and as an instant
 * preview when capture ends (the full-quality stack takes a minute or two longer).
 */
public final class PreviewAccumulator {
    public static final int FACTOR = 4;
    private final SensorSpec spec;
    private final CameraModel model;
    private final int W, H, pw, ph;
    private final float[] sr, sg, sb, cnt;
    private final double[] nodeB;
    private int units;

    public PreviewAccumulator(SensorSpec spec, CameraModel model) {
        this.spec = spec;
        this.model = model;
        W = spec.width;
        H = spec.height;
        pw = W / FACTOR;
        ph = H / FACTOR;
        sr = new float[pw * ph];
        sg = new float[pw * ph];
        sb = new float[pw * ph];
        cnt = new float[pw * ph];
        nodeB = WarpGrid.nodeBearings(model, W, H, SharpStacker.STEP);
    }

    public synchronized int units() {
        return units;
    }

    /** Adds a unit whose rotation (reference -> unit) is R. */
    public synchronized void add(final short[] raw, final float invScale, final float[] black, double[] R) {
        final WarpGrid g = WarpGrid.build(model, nodeB, W, H, SharpStacker.STEP, R);
        final int[] cfa = spec.cfa;
        Parallel.forRange(ph, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] rx = new float[g.nx], ry = new float[g.nx];
                float[] acc = new float[3];
                int[] n = new int[3];
                for (int j = j0; j < j1; j++) {
                    double yc = j * FACTOR + FACTOR * 0.5;
                    g.row(yc, rx, ry);
                    for (int i = 0; i < pw; i++) {
                        double xc = i * FACTOR + FACTOR * 0.5;
                        double gx = xc / g.step;
                        int ix = Math.min((int) gx, g.nx - 2);
                        double t = gx - ix;
                        double mx = rx[ix] + (rx[ix + 1] - rx[ix]) * t;
                        double my = ry[ix] + (ry[ix + 1] - ry[ix]) * t;
                        int x0 = ((int) Math.floor(mx - 2)) & ~1, y0 = ((int) Math.floor(my - 2)) & ~1;
                        if (x0 < 0 || y0 < 0 || x0 + 4 > W || y0 + 4 > H) continue;
                        acc[0] = acc[1] = acc[2] = 0;
                        n[0] = n[1] = n[2] = 0;
                        for (int dy = 0; dy < 4; dy++) {
                            int y = y0 + dy;
                            int o = y * W;
                            int py = (y & 1) << 1;
                            for (int dx = 0; dx < 4; dx++) {
                                int x = x0 + dx;
                                int p = py | (x & 1);
                                int c = cfa[p];
                                acc[c] += (raw[o + x] & 0xFFFF) * invScale - black[p];
                                n[c]++;
                            }
                        }
                        int k = j * pw + i;
                        sr[k] += n[0] > 0 ? acc[0] / n[0] : 0;
                        sg[k] += n[1] > 0 ? acc[1] / n[1] : 0;
                        sb[k] += n[2] > 0 ? acc[2] / n[2] : 0;
                        cnt[k] += 1;
                    }
                }
            }
        });
        units++;
    }

    /** Current average as a camera-RGB image (lens shading applied), or null before the first unit. */
    public synchronized RgbImage image() {
        if (units == 0) return null;
        RgbImage img = new RgbImage(pw, ph);
        ShadingMap sh = spec.shading;
        int pR = 0, pB = 3, pG = 1;
        for (int p = 0; p < 4; p++) {
            if (spec.cfa[p] == 0) pR = p;
            else if (spec.cfa[p] == 2) pB = p;
            else pG = p;
        }
        int minCount = Math.max(1, (int) (units * 0.5f));
        int x0 = pw, x1 = 0, y0 = ph, y1 = 0;
        for (int j = 0; j < ph; j++) {
            for (int i = 0; i < pw; i++) {
                int k = j * pw + i;
                float c = cnt[k];
                if (c <= 0) continue;
                float gr = 1, gg = 1, gb = 1;
                if (sh != null) {
                    double xc = i * FACTOR + FACTOR * 0.5, yc = j * FACTOR + FACTOR * 0.5;
                    gr = sh.gain(pR, xc, yc);
                    gg = sh.gain(pG, xc, yc);
                    gb = sh.gain(pB, xc, yc);
                }
                img.r[k] = sr[k] / c * gr;
                img.g[k] = sg[k] / c * gg;
                img.b[k] = sb[k] / c * gb;
                if (c >= minCount) {
                    if (i < x0) x0 = i;
                    if (i + 1 > x1) x1 = i + 1;
                    if (j < y0) y0 = j;
                    if (j + 1 > y1) y1 = j + 1;
                }
            }
        }
        if (x1 > x0 && y1 > y0) {
            img.x0 = x0;
            img.x1 = x1;
            img.y0 = y0;
            img.y1 = y1;
        }
        return img;
    }
}
