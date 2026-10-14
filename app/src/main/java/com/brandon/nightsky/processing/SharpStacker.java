package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Star-aligned integration of the stored units into one full-colour image, Bayer-drizzle style.
 * <p>
 * The sky drifts across the sensor during the session, so over the frames every output pixel is visited by
 * red, green and blue photosites at many sub-pixel offsets. Each output pixel and channel takes, from every
 * frame, the nearest photosite of that colour at the position the sky rotation maps it to, weighted by how
 * much a pixel-sized drop around that photosite overlaps the output pixel. All three colours then share the
 * same star shape (no colour fringes on undersampled stars), there is no interpolation ringing (no dark
 * moats around bright stars) and no per-frame demosaicing. Where the drift is too small to cover all colour
 * phases (very close to the celestial pole), bilinear interpolation of the planes fills in.
 * <p>
 * Integration is a weighted mean with outlier rejection, done in two passes per band of rows so memory
 * stays bounded: pass A gathers per-pixel mean, spread and the two largest values of the interpolated
 * frames; the clipping limits come from the statistics without those two values, so a satellite, plane,
 * meteor or cosmic ray in one or two frames cannot hide itself by inflating the spread. Pass B accumulates
 * the frames inside the limits.
 */
public final class SharpStacker {
    public static final class Plan {
        public int unit;
        /** Rotation taking reference-frame bearings to this unit's bearings. */
        public double[] R;
        public float weight = 1f;
        /** stored value * invScale = per-frame ADU including black level */
        public float invScale = 1f;
        public final float[] black = new float[4];
        /** Optional per-region weight factors (partly cloudy frames). */
        public LocalWeights local;
    }

    public static final class Result {
        public RgbImage image;
        /** Per plan: samples rejected (counted per channel) and valid pixel samples. */
        public long[] rejected, samples;
        /** Per output row: first and one-past-last column with good coverage (-1 when none). */
        public int[] rowL, rowR;
    }

    public static final int STEP = 32;
    public float kHigh = 3f, kLow = 4f, sigmaFloor = 0.5f;
    /** Bytes for the per-band statistics. */
    public long memoryBudget = 48L << 20;
    /** Fraction of the frames a pixel needs to count as well covered (for the crop). */
    public float coverageFraction = 0.7f;

    private static final float NEG = -Float.MAX_VALUE, POS = Float.MAX_VALUE;

    private final SensorSpec spec;
    private final CameraModel model;
    private final FrameStore store;
    private final HotPixels hot;
    private final int W, H;
    private final int pR, pG1, pG2, pB;

    public SharpStacker(SensorSpec spec, CameraModel model, FrameStore store, HotPixels hot) {
        this.spec = spec;
        this.model = model;
        this.store = store;
        this.hot = hot;
        W = spec.width;
        H = spec.height;
        int r = -1, g1 = -1, g2 = -1, b = -1;
        for (int p = 0; p < 4; p++) {
            int c = spec.cfa[p];
            if (c == 0 && r < 0) r = p;
            else if (c == 2 && b < 0) b = p;
            else if (c == 1) {
                if (g1 < 0) g1 = p;
                else g2 = p;
            }
        }
        if (g1 < 0) g1 = 1;
        if (g2 < 0) g2 = g1;
        if (r < 0) r = 0;
        if (b < 0) b = 3;
        pR = r;
        pG1 = g1;
        pG2 = g2;
        pB = b;
    }

    /** One loaded band of a unit: black-subtracted float ADU for rows [rs, re). */
    private static final class Loaded {
        short[] raw = new short[0];
        float[] data = new float[0];
        int rs, re;
        boolean valid;
    }

    public Result stack(final List<Plan> plans, final Progress progress) throws IOException {
        final int nf = plans.size();
        final double[] nodeB = WarpGrid.nodeBearings(model, W, H, STEP);
        final WarpGrid[] grids = new WarpGrid[nf];
        for (int f = 0; f < nf; f++) grids[f] = WarpGrid.build(model, nodeB, W, H, STEP, plans.get(f).R);

        final Result res = new Result();
        final RgbImage out = new RgbImage(W, H);
        res.image = out;
        res.rejected = new long[nf];
        res.samples = new long[nf];
        res.rowL = new int[H];
        res.rowR = new int[H];

        int bandRows = (int) Math.min(512, Math.max(32, memoryBudget / ((long) W * 76)));
        bandRows &= ~1;
        final int bandPix = bandRows * W;
        final float[] s0 = new float[bandPix * 3];
        final float[] s1 = new float[bandPix * 3];
        final float[] s2 = new float[bandPix * 3];
        final float[] s3 = new float[bandPix * 3];
        final float[] s4 = new float[bandPix * 3];
        final float[] s5 = new float[bandPix * 3];
        final short[] cnt = new short[bandPix];
        final short[] ccnt = new short[bandPix];
        out.clip = new byte[W * H];
        final int covThr = Math.max(1, Math.round(coverageFraction * nf));

        ExecutorService io = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "nightsky-io");
                t.setDaemon(true);
                return t;
            }
        });
        final Loaded[] bufs = {new Loaded(), new Loaded()};
        final int bands = (H + bandRows - 1) / bandRows;
        try {
            for (int b = 0; b < bands; b++) {
                final int y0 = b * bandRows, y1 = Math.min(H, y0 + bandRows);
                final int n = (y1 - y0) * W;
                java.util.Arrays.fill(s0, 0, n * 3, 0f);
                java.util.Arrays.fill(s1, 0, n * 3, 0f);
                java.util.Arrays.fill(s2, 0, n * 3, NEG);
                java.util.Arrays.fill(s3, 0, n * 3, NEG);
                java.util.Arrays.fill(s4, 0, n * 3, 0f);
                java.util.Arrays.fill(s5, 0, n * 3, 0f);
                java.util.Arrays.fill(cnt, 0, n, (short) 0);
                java.util.Arrays.fill(ccnt, 0, n, (short) 0);
                for (int pass = 0; pass < 2; pass++) {
                    Future<Loaded> next = io.submit(loader(plans.get(0), grids[0], y0, y1, bufs[0]));
                    for (int f = 0; f < nf; f++) {
                        Loaded cur = await(next);
                        if (f + 1 < nf) next = io.submit(loader(plans.get(f + 1), grids[f + 1], y0, y1, bufs[(f + 1) & 1]));
                        if (cur.valid) {
                            if (pass == 0) accumulate(cur, grids[f], plans.get(f), y0, y1, s0, s1, s2, s3, cnt, ccnt);
                            else integrate(cur, grids[f], plans.get(f), f, y0, y1, s0, s1, s2, s3, s4, s5, res);
                        }
                        if (progress.cancelled()) throw new CancelledException();
                        progress.update("Stacking", ((b * 2 + pass) + (f + 1f) / nf) / (bands * 2f));
                    }
                    if (pass == 0) limits(n, s0, s1, s2, s3, cnt);
                }
                finish(y0, y1, s0, s1, s2, s3, s4, s5, cnt, ccnt, covThr, out, res);
            }
        } finally {
            io.shutdownNow();
        }
        applyShading(out);
        int[] crop = cropFromSpans(res.rowL, res.rowR, W, H);
        out.x0 = crop[0];
        out.y0 = crop[1];
        out.x1 = crop[2];
        out.y1 = crop[3];
        return res;
    }

    private static Loaded await(Future<Loaded> f) throws IOException {
        try {
            return f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            throw new IOException(c);
        }
    }

    private Callable<Loaded> loader(final Plan plan, final WarpGrid grid, final int y0, final int y1, final Loaded buf) {
        return new Callable<Loaded>() {
            @Override
            public Loaded call() throws IOException {
                buf.valid = false;
                float[] mr = grid.mappedRowRange(y0, y1);
                if (mr[0] > mr[1]) return buf;
                int rs = (int) Math.floor(mr[0]) - 6, re = (int) Math.ceil(mr[1]) + 7;
                rs = Math.max(0, rs);
                re = Math.min(H, re);
                if (re - rs < 8) return buf;
                int need = (re - rs) * W;
                if (buf.raw.length < need) {
                    buf.raw = new short[need];
                    buf.data = new float[need];
                }
                store.readBand(plan.unit, rs, re, buf.raw);
                if (hot != null) hot.fix(buf.raw, rs, re, rs);
                float sc = plan.invScale;
                short[] raw = buf.raw;
                float[] d = buf.data;
                for (int y = rs; y < re; y++) {
                    int o = (y - rs) * W;
                    int py = (y & 1) << 1;
                    float be = plan.black[py], bo = plan.black[py | 1];
                    for (int x = 0; x < W; x += 2) {
                        d[o + x] = (raw[o + x] & 0xFFFF) * sc - be;
                        d[o + x + 1] = (raw[o + x + 1] & 0xFFFF) * sc - bo;
                    }
                }
                buf.rs = rs;
                buf.re = re;
                buf.valid = true;
                return buf;
            }
        };
    }

    /**
     * Bilinear R, G, B of the unit at frame position (mx, my) into rgb[0..2] (green from both green planes).
     * Returns true if one of the samples used is at or above its clip threshold ({@code thr}, may be null).
     * {@code t} is scratch (2 floats).
     */
    private boolean sampleRgb(float[] d, int rs, float mx, float my, float[] t, float[] rgb, float[] thr) {
        boolean clip = false;
        bilinear(d, rs, pR, mx, my, t);
        rgb[0] = t[0];
        if (thr != null && t[1] >= thr[pR]) clip = true;
        bilinear(d, rs, pG1, mx, my, t);
        float g = t[0];
        if (thr != null && t[1] >= thr[pG1]) clip = true;
        if (pG2 != pG1) {
            bilinear(d, rs, pG2, mx, my, t);
            g = 0.5f * (g + t[0]);
            if (thr != null && t[1] >= thr[pG2]) clip = true;
        }
        rgb[1] = g;
        bilinear(d, rs, pB, mx, my, t);
        rgb[2] = t[0];
        if (thr != null && t[1] >= thr[pB]) clip = true;
        return clip;
    }

    /** Bilinear value of plane p at (mx, my) into t[0], the largest of its four samples into t[1]. */
    private void bilinear(float[] d, int rs, int p, float mx, float my, float[] t) {
        int ox = p & 1, oy = p >> 1;
        float fx = (mx - 0.5f - ox) * 0.5f, fy = (my - 0.5f - oy) * 0.5f;
        int iu = (int) fx, iv = (int) fy; // positive here
        float ax = fx - iu, ay = fy - iv;
        int i = (2 * iv + oy - rs) * W + 2 * iu + ox;
        float d00 = d[i], d01 = d[i + 2], d10 = d[i + 2 * W], d11 = d[i + 2 * W + 2];
        t[0] = (1f - ay) * (d00 + ax * (d01 - d00)) + ay * (d10 + ax * (d11 - d10));
        t[1] = Math.max(Math.max(d00, d01), Math.max(d10, d11));
    }

    /**
     * Drizzle contributions of the unit to the output pixel that maps to (mx, my): per channel the weighted
     * value into dz[0..2] and the weight into dz[3..5]. The weight is the overlap of a pixel-sized drop
     * around the nearest photosite of that colour with the (pixel-sized) output pixel.
     */
    private void drizzleRgb(float[] d, int rs, float mx, float my, float[] t, float[] dz) {
        nearest(d, rs, pR, mx, my, t);
        dz[0] = t[0] * t[1];
        dz[3] = t[1];
        nearest(d, rs, pG1, mx, my, t);
        dz[1] = t[0] * t[1];
        dz[4] = t[1];
        if (pG2 != pG1) {
            nearest(d, rs, pG2, mx, my, t);
            dz[1] += t[0] * t[1];
            dz[4] += t[1];
        }
        nearest(d, rs, pB, mx, my, t);
        dz[2] = t[0] * t[1];
        dz[5] = t[1];
    }

    /** Nearest sample of plane p to (mx, my) into t[0], its drop overlap weight into t[1]. */
    private void nearest(float[] d, int rs, int p, float mx, float my, float[] t) {
        int ox = p & 1, oy = p >> 1;
        float fx = (mx - 0.5f - ox) * 0.5f, fy = (my - 0.5f - oy) * 0.5f;
        int iu = (int) (fx + 0.5f), iv = (int) (fy + 0.5f);
        float dx = 2f * Math.abs(fx - iu), dy = 2f * Math.abs(fy - iv);
        t[0] = d[(2 * iv + oy - rs) * W + 2 * iu + ox];
        t[1] = (1f - dx) * (1f - dy);
    }

    /** Per-plane clip threshold in the loaded data's units (black-subtracted per-frame ADU). */
    private float[] clipThresholds(Plan plan) {
        float range = spec.range();
        float level = spec.white - Math.max(3f, 0.01f * range);
        float[] t = new float[4];
        for (int p = 0; p < 4; p++) t[p] = level - plan.black[p];
        return t;
    }

    /** Pass A: Welford mean / M2 and the two largest values per pixel and channel. */
    private void accumulate(final Loaded cur, final WarpGrid grid, final Plan plan, final int y0, final int y1,
                            final float[] s0, final float[] s1, final float[] s2, final float[] s3, final short[] cnt,
                            final short[] ccnt) {
        final float[] thr = clipThresholds(plan);
        Parallel.forRange(y1 - y0, 2, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] rx = new float[grid.nx], ry = new float[grid.nx];
                float[] st = new float[2], rgb = new float[3];
                float[] d = cur.data;
                int rs = cur.rs;
                for (int j = j0; j < j1; j++) {
                    int y = y0 + j;
                    grid.row(y + 0.5, rx, ry);
                    for (int ix = 0; ix < grid.nx - 1; ix++) {
                        int xa = ix * STEP;
                        if (xa >= W) break;
                        int xb = Math.min(W, xa + STEP);
                        float ax = rx[ix], ay = ry[ix];
                        float dx = (rx[ix + 1] - ax) / STEP, dy = (ry[ix + 1] - ay) / STEP;
                        for (int x = xa; x < xb; x++) {
                            float t = x + 0.5f - xa;
                            float mx = ax + dx * t, my = ay + dy * t;
                            if (!(mx >= 4f && my >= 4f && mx < W - 4f && my < H - 4f)) continue;
                            boolean clipped = sampleRgb(d, rs, mx, my, st, rgb, thr);
                            int bp = j * W + x;
                            int c = cnt[bp] + 1;
                            cnt[bp] = (short) c;
                            if (clipped) ccnt[bp]++;
                            float inv = 1f / c;
                            int k = bp * 3;
                            for (int ch = 0; ch < 3; ch++, k++) {
                                float v = rgb[ch];
                                float m = s0[k];
                                float dd = v - m;
                                m += dd * inv;
                                s0[k] = m;
                                s1[k] += dd * (v - m);
                                if (v > s2[k]) {
                                    s3[k] = s2[k];
                                    s2[k] = v;
                                } else if (v > s3[k]) {
                                    s3[k] = v;
                                }
                            }
                        }
                    }
                }
            }
        });
    }

    /** Between the passes: turn the statistics into per-pixel acceptance limits (s0 = low, s1 = high). */
    private void limits(final int n, final float[] s0, final float[] s1, final float[] s2, final float[] s3, final short[] cnt) {
        Parallel.forRange(n, 4096, new Parallel.Range() {
            @Override
            public void run(int a, int b) {
                for (int bp = a; bp < b; bp++) {
                    int c = cnt[bp];
                    for (int ch = 0, k = bp * 3; ch < 3; ch++, k++) {
                        if (c < 4) {
                            s0[k] = NEG;
                            s1[k] = POS;
                        } else {
                            double mean = s0[k], m2 = s1[k];
                            int m = c;
                            // drop the largest value, and for 8+ samples the second largest too
                            double x = s2[k];
                            double mean1 = (m * mean - x) / (m - 1);
                            m2 -= (x - mean) * (x - mean1);
                            mean = mean1;
                            m--;
                            if (c >= 8) {
                                x = s3[k];
                                mean1 = (m * mean - x) / (m - 1);
                                m2 -= (x - mean) * (x - mean1);
                                mean = mean1;
                                m--;
                            }
                            double sd = Math.sqrt(Math.max(m2, 0) / (m - 1)) * (1 + 1.5 / m);
                            if (sd < sigmaFloor) sd = sigmaFloor;
                            s0[k] = (float) (mean - kLow * sd);
                            s1[k] = (float) (mean + kHigh * sd);
                        }
                        s2[k] = 0f;
                        s3[k] = 0f;
                    }
                }
            }
        });
    }

    /**
     * Pass B: for the frames inside the limits, drizzle sums (s2 = weighted values, s3 = weights) and
     * interpolated sums (s4 = values, s5 = frame weights).
     */
    private void integrate(final Loaded cur, final WarpGrid grid, final Plan plan, final int fi, final int y0, final int y1,
                           final float[] s0, final float[] s1, final float[] s2, final float[] s3, final float[] s4,
                           final float[] s5, final Result res) {
        Parallel.forRange(y1 - y0, 2, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                float[] rx = new float[grid.nx], ry = new float[grid.nx];
                float[] st = new float[2], rgb = new float[3], dz = new float[6];
                float[] lw = plan.local != null ? new float[W] : null;
                float[] d = cur.data;
                int rs = cur.rs;
                long rej = 0, smp = 0;
                for (int j = j0; j < j1; j++) {
                    int y = y0 + j;
                    grid.row(y + 0.5, rx, ry);
                    if (lw != null) plan.local.row(y, lw);
                    for (int ix = 0; ix < grid.nx - 1; ix++) {
                        int xa = ix * STEP;
                        if (xa >= W) break;
                        int xb = Math.min(W, xa + STEP);
                        float ax = rx[ix], ay = ry[ix];
                        float dx = (rx[ix + 1] - ax) / STEP, dy = (ry[ix + 1] - ay) / STEP;
                        for (int x = xa; x < xb; x++) {
                            float t = x + 0.5f - xa;
                            float mx = ax + dx * t, my = ay + dy * t;
                            if (!(mx >= 4f && my >= 4f && mx < W - 4f && my < H - 4f)) continue;
                            sampleRgb(d, rs, mx, my, st, rgb, null);
                            drizzleRgb(d, rs, mx, my, st, dz);
                            float wt = lw != null ? plan.weight * lw[x] : plan.weight;
                            int k = (j * W + x) * 3;
                            smp++;
                            for (int ch = 0; ch < 3; ch++, k++) {
                                float v = rgb[ch];
                                if (v < s0[k] || v > s1[k]) {
                                    rej++;
                                    continue;
                                }
                                s2[k] += wt * dz[ch];
                                s3[k] += wt * dz[ch + 3];
                                s4[k] += wt * v;
                                s5[k] += wt;
                            }
                        }
                    }
                }
                synchronized (res) {
                    res.rejected[fi] += rej;
                    res.samples[fi] += smp;
                }
            }
        });
    }

    private void finish(final int y0, final int y1, final float[] s0, final float[] s1, final float[] s2, final float[] s3,
                        final float[] s4, final float[] s5, final short[] cnt, final short[] ccnt, final int covThr,
                        final RgbImage out, final Result res) {
        Parallel.forRange(y1 - y0, 2, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int j = j0; j < j1; j++) {
                    int y = y0 + j;
                    int left = -1, right = -1;
                    for (int x = 0; x < W; x++) {
                        int bp = j * W + x;
                        int c = cnt[bp];
                        if (c >= covThr) {
                            if (left < 0) left = x;
                            right = x + 1;
                        }
                        int o = y * W + x;
                        out.clip[o] = c > 0 ? (byte) Math.min(255, (255 * ccnt[bp] + c - 1) / c) : 0;
                        for (int ch = 0, k = bp * 3; ch < 3; ch++, k++) {
                            float v;
                            if (c == 0) {
                                v = 0f;
                            } else if (s5[k] > 0) {
                                v = s4[k] / s5[k];
                                if (s3[k] > 0) {
                                    // effective number of drizzle samples ~ frames x mean drop weight
                                    float eff = s3[k] / s5[k] * c;
                                    float a = Math.min(1f, eff * 0.5f);
                                    v += a * (s2[k] / s3[k] - v);
                                }
                            } else if (s0[k] > NEG) {
                                v = s0[k] + kLow * (s1[k] - s0[k]) / (kHigh + kLow);
                            } else {
                                v = 0f;
                            }
                            out.channel(ch)[o] = v;
                        }
                    }
                    res.rowL[y] = left;
                    res.rowR[y] = right;
                }
            }
        });
    }

    /** Lens shading (vignetting and colour shading) correction in reference-frame coordinates. */
    private void applyShading(final RgbImage img) {
        final ShadingMap sh = spec.shading;
        if (sh == null || sh.isUnity()) return;
        final int step = 16;
        final int nxs = W / step + 2;
        Parallel.forRange(H, 8, new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                float[] gr = new float[nxs], gg = new float[nxs], gb = new float[nxs];
                for (int y = y0; y < y1; y++) {
                    double yc = y + 0.5;
                    for (int i = 0; i < nxs; i++) {
                        double xc = i * step;
                        gr[i] = sh.gain(pR, xc, yc);
                        gg[i] = pG2 != pG1 ? 0.5f * (sh.gain(pG1, xc, yc) + sh.gain(pG2, xc, yc)) : sh.gain(pG1, xc, yc);
                        gb[i] = sh.gain(pB, xc, yc);
                    }
                    int o = y * W;
                    for (int x = 0; x < W; x++) {
                        float fx = (x + 0.5f) / step;
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

    /**
     * Largest axis-aligned rectangle inside the per-row coverage spans (even-aligned). Falls back to the
     * whole image if coverage is degenerate.
     */
    public static int[] cropFromSpans(int[] rowL, int[] rowR, int W, int H) {
        int mid = H / 2;
        int minSpan = W / 2;
        if (rowL[mid] < 0 || rowR[mid] - rowL[mid] < minSpan) {
            // find any good row
            mid = -1;
            for (int y = 0; y < H; y++) {
                if (rowL[y] >= 0 && rowR[y] - rowL[y] >= minSpan) {
                    mid = y;
                    break;
                }
            }
            if (mid < 0) return new int[]{0, 0, W, H};
        }
        int top = mid, bot = mid;
        while (top > 0 && rowL[top - 1] >= 0 && rowR[top - 1] - rowL[top - 1] >= minSpan) top--;
        while (bot < H - 1 && rowL[bot + 1] >= 0 && rowR[bot + 1] - rowL[bot + 1] >= minSpan) bot++;
        long bestArea = -1;
        int bx0 = 0, by0 = 0, bx1 = W, by1 = H;
        int stride = Math.max(1, (bot - top + 1) / 768);
        for (int yt = top; yt <= bot; yt += stride) {
            int lx = 0, rx = W;
            for (int yb = yt; yb <= bot; yb++) {
                if (rowL[yb] > lx) lx = rowL[yb];
                if (rowR[yb] < rx) rx = rowR[yb];
                if (rx - lx < minSpan) break;
                long area = (long) (rx - lx) * (yb - yt + 1);
                if (area > bestArea) {
                    bestArea = area;
                    bx0 = lx;
                    bx1 = rx;
                    by0 = yt;
                    by1 = yb + 1;
                }
            }
        }
        if (bestArea <= 0) return new int[]{0, 0, W, H};
        bx0 = (bx0 + 1) & ~1;
        by0 = (by0 + 1) & ~1;
        bx1 &= ~1;
        by1 &= ~1;
        if (bx1 - bx0 < 16 || by1 - by0 < 16) return new int[]{0, 0, W, H};
        return new int[]{bx0, by0, bx1, by1};
    }
}
