package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.List;

/**
 * The long-exposure blend (C7): the plain average of the frames, not lined up and with nothing clipped, as one
 * long exposure would record it. Clouds drifting over a partly cloudy sky turn into soft streaks and the stars
 * into short arcs, while the main stack keeps the stars sharp. Made at half size, one pixel for each 2 x 2 block
 * of the sensor (its red, its two greens and its blue), which suits a picture with nothing sharp in it, needs no
 * demosaicing and reads each frame once. Hot pixels are mended and the lens shading taken out, as in the stack;
 * a block that was clipped in any frame is marked, so its colour comes from around it (a bright star's clipped
 * green would otherwise leave its trail magenta).
 */
public final class BlendStacker {
    public static final class Unit {
        public int unit;
        public float invScale = 1f;
        public final float[] black = new float[4];
    }

    private final SensorSpec spec;
    private final FrameStore store;
    private final HotPixels hot;
    private final int W, H;

    public BlendStacker(SensorSpec spec, FrameStore store, HotPixels hot) {
        this.spec = spec;
        this.store = store;
        this.hot = hot;
        W = spec.width;
        H = spec.height;
    }

    /** The average of {@code units}, (W / 2) x (H / 2), in the camera's colours like the stack. */
    public RgbImage stack(List<Unit> units, Progress progress) throws IOException {
        final int w = W / 2, h = H / 2;
        final RgbImage out = new RgbImage(w, h);
        final byte[] clipped = new byte[w * h];
        final float clipLevel = spec.white - Math.max(3f, 0.01f * spec.range());
        final int[] cfa = spec.cfa;
        final short[] raw = new short[W * H];
        final int n = units.size();
        for (int f = 0; f < n; f++) {
            final Unit u = units.get(f);
            store.read(u.unit, raw);
            if (hot != null) hot.fix(raw);
            Parallel.forRange(h, 16, new Parallel.Range() {
                @Override
                public void run(int j0, int j1) {
                    for (int j = j0; j < j1; j++) {
                        int o = j * w;
                        for (int py = 0; py < 2; py++) {
                            int row = (2 * j + py) * W;
                            for (int px = 0; px < 2; px++) {
                                int p = (py << 1) | px;
                                float bl = u.black[p], sc = u.invScale;
                                int c = cfa[p];
                                float[] dst = out.channel(c);
                                float k = c == 1 ? 0.5f : 1f;
                                for (int i = 0; i < w; i++) {
                                    float v = (raw[row + 2 * i + px] & 0xFFFF) * sc;
                                    if (v >= clipLevel) clipped[o + i] = (byte) 255;
                                    dst[o + i] += k * (v - bl);
                                }
                            }
                        }
                    }
                }
            });
            if (progress.cancelled()) throw new CancelledException();
            progress.update("The cloud-blur version", (f + 1f) / n);
        }
        final float inv = 1f / Math.max(1, n);
        for (int c = 0; c < 3; c++) {
            float[] a = out.channel(c);
            for (int i = 0; i < a.length; i++) a[i] *= inv;
        }
        applyShading(out);
        out.clip = clipped;
        // the same margin the stack crops (its edge rows have no neighbours for the demosaic)
        out.x0 = 2;
        out.y0 = 2;
        out.x1 = w - 2;
        out.y1 = h - 2;
        return out;
    }

    /** Lens shading (vignetting and colour shading) out, at each block's centre on the sensor. */
    private void applyShading(final RgbImage img) {
        final ShadingMap sh = spec.shading;
        if (sh == null || sh.isUnity()) return;
        final int[] planeOf = {-1, -1, -1};
        int g2 = -1;
        for (int p = 0; p < 4; p++) {
            int c = spec.cfa[p];
            if (planeOf[c] < 0) planeOf[c] = p;
            else if (c == 1) g2 = p;
        }
        final int pR = planeOf[0] < 0 ? 0 : planeOf[0], pB = planeOf[2] < 0 ? 3 : planeOf[2];
        final int pG1 = planeOf[1] < 0 ? 1 : planeOf[1], pG2 = g2 < 0 ? pG1 : g2;
        final int w = img.width, step = 8;
        final int nxs = w / step + 2;
        Parallel.forRange(img.height, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] gr = new float[nxs], gg = new float[nxs], gb = new float[nxs];
                for (int j = j0; j < j1; j++) {
                    double yc = 2 * j + 1;
                    for (int i = 0; i < nxs; i++) {
                        double xc = 2.0 * i * step + 1;
                        gr[i] = sh.gain(pR, xc, yc);
                        gg[i] = 0.5f * (sh.gain(pG1, xc, yc) + sh.gain(pG2, xc, yc));
                        gb[i] = sh.gain(pB, xc, yc);
                    }
                    int o = j * w;
                    for (int x = 0; x < w; x++) {
                        float fx = x / (float) step;
                        int i = (int) fx;
                        float t = fx - i;
                        img.r[o + x] *= gr[i] + (gr[i + 1] - gr[i]) * t;
                        img.g[o + x] *= gg[i] + (gg[i + 1] - gg[i]) * t;
                        img.b[o + x] *= gb[i] + (gb[i + 1] - gb[i]) * t;
                    }
                }
            }
        });
    }
}
