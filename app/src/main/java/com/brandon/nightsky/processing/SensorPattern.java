package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/**
 * The sensor's own pattern, measured from the lights themselves. With the phone lying still the sky drifts
 * across the sensor, so whatever stays put on the sensor (pixels a little warmer than the rest, offsets
 * between rows or columns, faint blotches) slides through the aligned stack along the drift and leaves
 * streaks: walking noise. Darks catch the part of that pattern that is stable, but not what changes with the
 * sensor's temperature during the session, nor banding that differs from frame to frame.
 * <p>
 * After a first stack, every frame's expected values are rebuilt from it (warped back into the frame, its
 * normalisation and the lens shading undone) and subtracted from the frame. Stars are masked, so what is
 * left is the sensor's pattern plus noise; the sky itself, the Milky Way included, is gone. Per frame and
 * row, and per frame and column, the median of that residual gives the banding; per photosite the clipped
 * mean over the frames gives the fixed pattern. Each part is kept only as far as it stands above its noise,
 * judged by measuring it twice from independent halves of the data (left and right half of each row, top
 * and bottom half of each column, odd and even frames): what the two halves agree on is pattern, what they
 * don't is noise, and each part is scaled by its share of pattern (a Wiener filter). A part that is all
 * noise is left alone, so the correction never adds more noise than it removes. Patterns larger than about
 * a hundred pixels are left alone too: they don't streak, and at that scale the residual is mostly the sky
 * model's, not the sensor's. The stack is then made again from the corrected frames.
 * <p>
 * It needs the sky to have moved across the sensor ({@link #MIN_DRIFT_PX}); with less drift the pattern
 * can't be told from the sky, and it doesn't walk anyway. Crowded fixed points (a branch, a roof edge) are
 * left out, so foreground objects are never mistaken for sensor pattern.
 */
public final class SensorPattern {
    /** Fewest frames to estimate a pattern from. */
    public static final int MIN_FRAMES = 8;
    /** The sky must drift at least this far across the sensor (pixels) to be told from the sensor's pattern. */
    public static final double MIN_DRIFT_PX = 10;
    /** Frames whose per-photosite median is the centre the means are clipped against, spread over the session. */
    static final int CENTRE_FRAMES = 24;
    /** Half-width (rows or columns) of the running median that keeps large-scale levels out of the banding. */
    static final int BAND_HALF = 16;
    /** Residuals beyond this many sigma stay out of the means (stars the mask missed, cosmic rays, satellites). */
    static final float CLIP = 4f;
    /** Half-size, in same-colour photosites, of the box splitting the fixed pattern into fine and mid-scale parts. */
    static final int SMOOTH = 4;
    /** Side (pixels) of the blocks whose means make the large scale, which is left alone. */
    static final int LARGE = 64;
    /** Stack pixels this many sigma above the sky background count as starlight and are masked. */
    static final float STAR_SIGMA = 3f;
    /**
     * A photosite whose fine pattern is this many noise sigmas off in both halves, the same way, is taken out in
     * full rather than shrunk; it counts as a warm pixel if it also stands this many pattern sigmas out.
     */
    static final float WARM_SIGMA = 5f;
    /** Pattern kept from a part (Wiener factor) before it counts as worth a second stack. */
    static final float WORTH = 0.3f;

    private final int W, H, nf;
    /** Per photosite, the fixed pattern to subtract (half floats, black-subtracted ADU), or null. */
    private short[] fixed;
    /** Per frame (as in the plan list), the row and column offsets to subtract, or null. */
    private final float[][] rows, cols;

    // what was found, for the log and the report
    /** Wiener factors kept of each part: fine and mid-scale fixed pattern, fixed columns, row and column banding. */
    public float kFine, kMid, kFixedCols, kRows, kCols;
    /** Typical size (ADU) of the fine fixed pattern, of the row and the column banding, before scaling. */
    public float fineSigma, rowSigma, colSigma;
    /** Residual noise of one frame (ADU, green). */
    public float frameNoise;
    public int warmPixels, framesUsed;
    public double driftPx;
    private boolean worth;

    private SensorPattern(int W, int H, int nf) {
        this.W = W;
        this.H = H;
        this.nf = nf;
        rows = new float[nf][];
        cols = new float[nf][];
    }

    public boolean worthRemoving() {
        return worth;
    }

    /** One line for the log. */
    public String describe() {
        return String.format(Locale.US, "sensor pattern from %d frames (sky drifted %.0f px): fine pattern %.2f ADU "
                        + "(kept %.0f%%), %d warm pixels, mid-scale kept %.0f%%, fixed columns %.0f%%; row banding %.2f ADU "
                        + "(kept %.0f%%), column banding %.2f ADU (kept %.0f%%); frame noise %.2f ADU", framesUsed, driftPx,
                fineSigma, 100 * kFine, warmPixels, 100 * kMid, 100 * kFixedCols, rowSigma, 100 * kRows, colSigma,
                100 * kCols, frameNoise);
    }

    /** What was taken out, in a few words: "fixed pattern, 12 warm pixels and row banding". */
    public String parts() {
        List<String> l = new ArrayList<String>();
        if (fixed != null && (kFine >= WORTH || kMid >= WORTH || kFixedCols >= WORTH)) l.add("fixed pattern");
        if (warmPixels > 0) l.add(warmPixels + " warm pixel" + (warmPixels == 1 ? "" : "s"));
        if (kRows >= WORTH) l.add("row banding");
        if (kCols >= WORTH) l.add("column banding");
        return l.isEmpty() ? "faint pattern" : PostProcessor.join(l);
    }

    /** The fixed pattern subtracted at photosite i (0 where there is none). */
    public float fixedAt(int i) {
        if (fixed == null) return 0f;
        short h = fixed[i];
        return Half.isMarker(h) ? 0f : Half.toFloat(h);
    }

    /** Row offsets subtracted from frame f (as in the plan list), or null. */
    public float[] rowsOf(int f) {
        return rows[f];
    }

    /** Gives each plan its correction, or takes it away ({@code on} false). */
    public void attach(List<SharpStacker.Plan> plans, boolean on) {
        for (int f = 0; f < plans.size(); f++) plans.get(f).pattern = on ? new Frame(this, f) : null;
    }

    /** One frame's correction, as the stacker's loader applies it to black-subtracted data. */
    public static final class Frame {
        private final SensorPattern sp;
        private final int f;

        Frame(SensorPattern sp, int f) {
            this.sp = sp;
            this.f = f;
        }

        /** Subtracts the pattern from rows [rs, re) of a frame, {@code d} holding those rows. */
        public void subtract(float[] d, int rs, int re) {
            int W = sp.W;
            float[] rw = sp.rows[f], cl = sp.cols[f];
            short[] fx = sp.fixed;
            for (int y = rs; y < re; y++) {
                int o = (y - rs) * W, g = y * W;
                float r = rw != null ? rw[y] : 0f;
                for (int x = 0; x < W; x++) {
                    float v = r;
                    if (cl != null) v += cl[x];
                    if (fx != null) {
                        short h = fx[g + x];
                        if (!Half.isMarker(h)) v += Half.toFloat(h);
                    }
                    d[o + x] -= v;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Estimation
    // ------------------------------------------------------------------------------------------------

    /** What the residual of a frame needs: the first stack and how every frame was normalised into it. */
    private static final class Ctx {
        FrameStore store;
        HotPixels hot;
        List<SharpStacker.Plan> plans;
        BackgroundGrid target;
        float minT;
        RgbImage stack;
        WarpGrid[] inv;
        BitSet stars, statics;
        float[][] gain;
        int gnx, gny;
        int W, H;
        float sat;
        int[] cfa;
    }

    /**
     * Measures the pattern against the first stack.
     *
     * @param stack     the first stack, lens shading applied, with its crop set
     * @param statics   fixed points; their crowded cells are left out
     * @param ground    the foreground kept still (G3), left out too: what stands still on the sensor can't be told
     *                  from its pattern (null: none)
     * @param minT      frames are used down to this transparency (as in the stack)
     * @param budget    bytes for the per-band buffers
     */
    public static SensorPattern estimate(SensorSpec spec, CameraModel model, FrameStore store, HotPixels hot,
                                         StaticSources statics, GroundMap ground, List<SharpStacker.Plan> plans,
                                         BackgroundGrid target, float minT, RgbImage stack, double driftPx, long budget,
                                         Progress progress)
            throws IOException {
        final int W = spec.width, H = spec.height, nf = plans.size();
        final SensorPattern sp = new SensorPattern(W, H, nf);
        sp.driftPx = driftPx;
        sp.framesUsed = nf;
        final Ctx cx = new Ctx();
        cx.store = store;
        cx.hot = hot;
        cx.plans = plans;
        cx.target = target;
        cx.minT = minT;
        cx.stack = stack;
        cx.W = W;
        cx.H = H;
        cx.cfa = spec.cfa;
        cx.sat = spec.white - Math.max(3f, 0.01f * spec.range());
        shadingGrid(cx, spec);
        // each frame's map from its pixels back into the stack
        double[] nodeB = WarpGrid.nodeBearings(model, W, H, SharpStacker.STEP);
        cx.inv = new WarpGrid[nf];
        for (int f = 0; f < nf; f++) cx.inv[f] = WarpGrid.build(model, nodeB, W, H, SharpStacker.STEP, Rot.transpose(plans.get(f).R));
        cx.stars = starMask(cx);
        cx.statics = staticMask(statics, W, H);
        if (ground != null) ground.markNear(cx.statics);

        // the frames whose median is the centre
        final int nsel = Math.min(nf, CENTRE_FRAMES);
        final int[] sel = new int[nsel];
        final int[] selOf = new int[nf];
        Arrays.fill(selOf, -1);
        for (int k = 0; k < nsel; k++) {
            sel[k] = nsel == 1 ? 0 : (int) Math.round(k * (nf - 1) / (double) (nsel - 1));
            selOf[sel[k]] = k;
        }
        // per band pixel: the chosen frames' residuals (half floats), one residual, its raw data, the centre,
        // and per half of the frames a sum and a count
        long perPix = 2L * nsel + 4 + 2 + 4 + 8 + 4;
        final int bandRows = (int) Math.max(16, Math.min(H, budget / Math.max(1, perPix * W))) & ~1;
        final short[] buf = new short[bandRows * W * nsel];
        final float[] res = new float[bandRows * W], centre = new float[bandRows * W];
        final short[] raw = new short[bandRows * W];
        final float[] sumE = new float[bandRows * W], sumO = new float[bandRows * W];
        final short[] cntE = new short[bandRows * W], cntO = new short[bandRows * W];
        final float[] rowMed0 = new float[nsel * bandRows];
        final float[] lvl = new float[bandRows];
        final short[] pOdd = new short[W * H], pEven = new short[W * H];
        // per frame: row medians of each half row; column sums and counts of each half column
        final float[][] medL = new float[nf][H], medR = new float[nf][H];
        final float[][] cT = new float[nf][W], cB = new float[nf][W];
        final int[][] nT = new int[nf][W], nB = new int[nf][W];
        // noise of one frame's residual (green), for the clipping: from the frames' own sky noise
        float[] sg = new float[nf];
        for (int f = 0; f < nf; f++) {
            BackgroundGrid bg = plans.get(f).background;
            sg[f] = bg != null ? bg.medianSigma(1) : 1f;
        }
        sp.frameNoise = Stats.median(sg, nf);
        final float clipAt = CLIP * Math.max(1e-3f, sp.frameNoise);

        final int bands = (H + bandRows - 1) / bandRows;
        for (int b = 0; b < bands; b++) {
            final int y0 = b * bandRows, y1 = Math.min(H, y0 + bandRows), nr = y1 - y0, n = nr * W;
            Arrays.fill(buf, 0, n * nsel, Half.MISSING);
            // A) the chosen frames' residuals and row levels
            for (int k = 0; k < nsel; k++) {
                residuals(cx, sel[k], y0, y1, raw, res);
                final int kk = k;
                Parallel.forRange(nr, 4, new Parallel.Range() {
                    @Override
                    public void run(int j0, int j1) {
                        float[] t = new float[W];
                        for (int j = j0; j < j1; j++) {
                            int m = 0;
                            for (int x = 0; x < W; x++) {
                                float v = res[j * W + x];
                                if (v != v) continue;
                                t[m++] = v;
                                buf[(j * W + x) * nsel + kk] = Half.fromFloat(v);
                            }
                            rowMed0[kk * bandRows + j] = m > 0 ? Stats.median(t, m) : 0f;
                        }
                    }
                });
                if (progress.cancelled()) throw new CancelledException();
                progress.update("Measuring the sensor's pattern", (b + 0.3f * (k + 1f) / nsel) / bands);
            }
            // the centre: per photosite, the median of the chosen frames, their row levels taken out
            Parallel.forRange(nr, 2, new Parallel.Range() {
                @Override
                public void run(int j0, int j1) {
                    float[] a = new float[nsel];
                    for (int j = j0; j < j1; j++) {
                        for (int x = 0; x < W; x++) {
                            int o = (j * W + x) * nsel, m = 0;
                            for (int k = 0; k < nsel; k++) {
                                short h = buf[o + k];
                                if (h != Half.MISSING) a[m++] = Half.toFloat(h) - rowMed0[k * bandRows + j];
                            }
                            centre[j * W + x] = m >= Math.min(3, nsel) ? Stats.median(a, m) : Float.NaN;
                        }
                    }
                }
            });
            // B) every frame: its row levels against the centre, its photosites into its half's sums, its columns
            Arrays.fill(sumE, 0, n, 0f);
            Arrays.fill(sumO, 0, n, 0f);
            Arrays.fill(cntE, 0, n, (short) 0);
            Arrays.fill(cntO, 0, n, (short) 0);
            for (int f = 0; f < nf; f++) {
                final int k = selOf[f];
                if (k >= 0) {
                    for (int i = 0; i < n; i++) {
                        short h = buf[i * nsel + k];
                        res[i] = h == Half.MISSING ? Float.NaN : Half.toFloat(h);
                    }
                } else {
                    residuals(cx, f, y0, y1, raw, res);
                }
                final float[] mL = medL[f], mR = medR[f], sT = cT[f], sB = cB[f];
                final int[] kT = nT[f], kB = nB[f];
                final float[] sum = (f & 1) == 0 ? sumE : sumO;
                final short[] cnt = (f & 1) == 0 ? cntE : cntO;
                Parallel.forRange(nr, 4, new Parallel.Range() {
                    @Override
                    public void run(int j0, int j1) {
                        float[] l = new float[W], r = new float[W];
                        for (int j = j0; j < j1; j++) {
                            int y = y0 + j, ml = 0, mr = 0;
                            for (int x = 0; x < W; x++) {
                                float v = res[j * W + x], c = centre[j * W + x];
                                if (v != v || c != c) continue;
                                if (x < W / 2) l[ml++] = v - c;
                                else r[mr++] = v - c;
                            }
                            mL[y] = ml > 0 ? Stats.median(l, ml) : Float.NaN;
                            mR[y] = mr > 0 ? Stats.median(r, mr) : Float.NaN;
                            float lv = ml > 0 && mr > 0 ? 0.5f * (mL[y] + mR[y]) : (ml > 0 ? mL[y] : (mr > 0 ? mR[y] : Float.NaN));
                            lvl[j] = lv;
                            if (lv != lv) continue;
                            for (int x = 0; x < W; x++) {
                                int i = j * W + x;
                                float v = res[i], c = centre[i];
                                if (v != v || c != c) continue;
                                float u = v - lv;
                                if (u - c > clipAt || c - u > clipAt) continue;
                                sum[i] += u;
                                cnt[i]++;
                            }
                        }
                    }
                });
                // column sums, one thread per column range so no two write the same column
                Parallel.forRange(W, 64, new Parallel.Range() {
                    @Override
                    public void run(int x0, int x1) {
                        for (int j = 0; j < nr; j++) {
                            float lv = lvl[j];
                            if (lv != lv) continue;
                            boolean top = y0 + j < H / 2;
                            for (int x = x0; x < x1; x++) {
                                int i = j * W + x;
                                float v = res[i], c = centre[i];
                                if (v != v || c != c) continue;
                                float d = v - c - lv;
                                if (d > clipAt || d < -clipAt) continue;
                                if (top) {
                                    sT[x] += d;
                                    kT[x]++;
                                } else {
                                    sB[x] += d;
                                    kB[x]++;
                                }
                            }
                        }
                    }
                });
                if (progress.cancelled()) throw new CancelledException();
                progress.update("Measuring the sensor's pattern", (b + 0.3f + 0.7f * (f + 1f) / nf) / bands);
            }
            // the halves' means
            Parallel.forRange(nr, 16, new Parallel.Range() {
                @Override
                public void run(int j0, int j1) {
                    for (int i = j0 * W; i < j1 * W; i++) {
                        int g = y0 * W + i;
                        pEven[g] = cntE[i] >= 2 ? Half.fromFloat(sumE[i] / cntE[i]) : Half.MISSING;
                        pOdd[g] = cntO[i] >= 2 ? Half.fromFloat(sumO[i] / cntO[i]) : Half.MISSING;
                    }
                }
            });
        }

        // the stack contains each frame's own pattern once over the number of frames: put that share back
        final float self = nf > 1 ? nf / (nf - 1f) : 1f;

        // rows: banding per frame, high-passed, kept as far as the two half rows agree
        float[][] hl = new float[nf][], hr = new float[nf][];
        for (int f = 0; f < nf; f++) {
            hl[f] = highPass(medL[f], H);
            hr[f] = highPass(medR[f], H);
        }
        float[] kr = wiener(hl, hr);
        sp.kRows = kr[0];
        sp.rowSigma = kr[1];
        // columns: the same with the top and bottom halves
        float[][] ht = new float[nf][], hb = new float[nf][];
        for (int f = 0; f < nf; f++) {
            float[] mt = new float[W], mb = new float[W];
            for (int x = 0; x < W; x++) {
                mt[x] = nT[f][x] > 0 ? cT[f][x] / nT[f][x] : Float.NaN;
                mb[x] = nB[f][x] > 0 ? cB[f][x] / nB[f][x] : Float.NaN;
            }
            ht[f] = highPass(mt, W);
            hb[f] = highPass(mb, W);
        }
        float[] kc = wiener(ht, hb);
        sp.kCols = kc[0];
        sp.colSigma = kc[1];
        for (int f = 0; f < nf; f++) {
            if (sp.kRows > 0) {
                float[] r = new float[H];
                for (int y = 0; y < H; y++) r[y] = self * sp.kRows * 0.5f * (nz(hl[f][y]) + nz(hr[f][y]));
                sp.rows[f] = r;
            }
            if (sp.kCols > 0) {
                float[] c = new float[W];
                for (int x = 0; x < W; x++) c[x] = self * sp.kCols * 0.5f * (nz(ht[f][x]) + nz(hb[f][x]));
                sp.cols[f] = c;
            }
        }

        // fixed columns in the per-photosite pattern: column medians of each half, high-passed
        float[][] co = {highPass(columnMedians(pOdd, W, H), W)}, ce = {highPass(columnMedians(pEven, W, H), W)};
        float[] kfc = wiener(co, ce);
        sp.kFixedCols = kfc[0];
        final float[] fixedCol = new float[W];
        for (int x = 0; x < W; x++) fixedCol[x] = 0.5f * (nz(co[0][x]) + nz(ce[0][x]));
        final float[] colO = co[0], colE = ce[0];
        Parallel.forRange(H, 16, new Parallel.Range() {
            @Override
            public void run(int ya, int yb) {
                for (int y = ya; y < yb; y++) {
                    for (int x = 0; x < W; x++) {
                        int i = y * W + x;
                        if (pOdd[i] != Half.MISSING) pOdd[i] = Half.fromFloat(Half.toFloat(pOdd[i]) - nz(colO[x]));
                        if (pEven[i] != Half.MISSING) pEven[i] = Half.fromFloat(Half.toFloat(pEven[i]) - nz(colE[x]));
                    }
                }
            }
        });

        // fine (photosite) and mid (blotch) scales; the large scale is left alone
        final int[] cfa = spec.cfa;
        final Large lo = Large.of(pOdd, W, H, cfa), le = Large.of(pEven, W, H, cfa);
        final short[] s4 = boxOfMean(pOdd, pEven, W, H);
        double[][] st = splitStats(pOdd, pEven, s4, lo, le, W, H, cfa);
        // per colour: Wiener factors of the fine and mid parts, and the warm-pixel threshold
        final float[] kF = new float[3], kM = new float[3], warmThr = new float[3], warmOut = new float[3];
        for (int c = 0; c < 3; c++) {
            double nzF = st[c][1] / 4, nzM = st[c][3] / 4;
            double sigF = Math.max(0, st[c][0]), sigM = Math.max(0, st[c][2]);
            kF[c] = sigF + nzF > 0 ? (float) (sigF / (sigF + nzF)) : 0f;
            kM[c] = sigM + nzM > 0 ? (float) (sigM / (sigM + nzM)) : 0f;
            // one half's fine pattern has noise variance (difference variance) / 2
            warmThr[c] = (float) (WARM_SIGMA * Math.sqrt(Math.max(1e-12, st[c][1] / 2)));
            // counted as warm (for the log) only if it also stands out from the rest of the pattern
            warmOut[c] = (float) Math.max(warmThr[c], WARM_SIGMA * Math.sqrt(sigF));
        }
        sp.kFine = kF[1];
        sp.kMid = kM[1];
        sp.fineSigma = (float) Math.sqrt(Math.max(0, st[1][0]));
        final float kfcF = sp.kFixedCols;
        final int[] warm = new int[1];
        final BitSet statMask = cx.statics;
        final short[] out = pOdd; // the fixed pattern replaces the odd half in place
        Parallel.forRange(H, 16, new Parallel.Range() {
            @Override
            public void run(int ya, int yb) {
                int w = 0;
                for (int y = ya; y < yb; y++) {
                    for (int x = 0; x < W; x++) {
                        int i = y * W + x;
                        int c = cfa[((y & 1) << 1) | (x & 1)];
                        short ho = out[i], he = pEven[i], hs = s4[i];
                        if (ho == Half.MISSING || he == Half.MISSING || hs == Half.MISSING || statMask.get(i)) {
                            out[i] = Half.MISSING;
                            continue;
                        }
                        float s = Half.toFloat(hs);
                        float fo = Half.toFloat(ho) - s, fe = Half.toFloat(he) - s;
                        float fine = 0.5f * (fo + fe);
                        float mid = s - 0.5f * (lo.at(c, x, y) + le.at(c, x, y));
                        boolean hotOne = Math.abs(fo) > warmThr[c] && Math.abs(fe) > warmThr[c] && fo * fe > 0;
                        if (hotOne && Math.abs(fine) > warmOut[c]) w++;
                        float v = (hotOne ? fine : kF[c] * fine) + kM[c] * mid + kfcF * fixedCol[x];
                        out[i] = Half.fromFloat(self * v);
                    }
                }
                synchronized (warm) {
                    warm[0] += w;
                }
            }
        });
        sp.warmPixels = warm[0];
        boolean anyFixed = sp.kFine >= WORTH || sp.kMid >= WORTH || sp.kFixedCols >= WORTH || sp.warmPixels > 0;
        sp.fixed = anyFixed ? out : null;
        sp.worth = anyFixed || sp.kRows >= WORTH || sp.kCols >= WORTH;
        if (!sp.worth) {
            sp.fixed = null;
            Arrays.fill(sp.rows, null);
            Arrays.fill(sp.cols, null);
        }
        return sp;
    }

    private static float nz(float v) {
        return v == v ? v : 0f;
    }

    /** The shading gain the stack applied, per colour on the warp lattice of the reference frame. */
    private static void shadingGrid(Ctx cx, SensorSpec spec) {
        int W = cx.W, H = cx.H;
        cx.gnx = W / SharpStacker.STEP + 2;
        cx.gny = H / SharpStacker.STEP + 2;
        cx.gain = new float[3][cx.gnx * cx.gny];
        int pR = -1, pG1 = -1, pG2 = -1, pB = -1;
        for (int p = 0; p < 4; p++) {
            int c = spec.cfa[p];
            if (c == 0 && pR < 0) pR = p;
            else if (c == 2 && pB < 0) pB = p;
            else if (c == 1) {
                if (pG1 < 0) pG1 = p;
                else pG2 = p;
            }
        }
        if (pG2 < 0) pG2 = pG1;
        ShadingMap sh = spec.shading;
        boolean flat = sh == null || sh.isUnity() || pR < 0 || pG1 < 0 || pB < 0;
        for (int iy = 0; iy < cx.gny; iy++) {
            for (int ix = 0; ix < cx.gnx; ix++) {
                int k = iy * cx.gnx + ix;
                double X = ix * SharpStacker.STEP, Y = iy * SharpStacker.STEP;
                cx.gain[0][k] = flat ? 1f : sh.gain(pR, X, Y);
                cx.gain[1][k] = flat ? 1f : 0.5f * (sh.gain(pG1, X, Y) + sh.gain(pG2, X, Y));
                cx.gain[2][k] = flat ? 1f : sh.gain(pB, X, Y);
            }
        }
    }

    /**
     * One frame's residual for rows [y0, y1): its black-subtracted value minus what the stack says it should
     * be, NaN where it can't be told (hot, saturated, starlight, outside the stack, cloud or a cloud edge,
     * crowded fixed points). The frame's normalisation is worked out at its warp nodes (every
     * {@link SharpStacker#STEP} pixels along the row) and interpolated between them.
     */
    private static void residuals(final Ctx cx, final int f, final int y0, final int y1, final short[] raw,
                                  final float[] out) throws IOException {
        final SharpStacker.Plan plan = cx.plans.get(f);
        cx.store.readBand(plan.unit, y0, y1, raw);
        final int W = cx.W, STEP = SharpStacker.STEP;
        final WarpGrid g = cx.inv[f];
        final RgbImage st = cx.stack;
        final LocalWeights tr = plan.transparency;
        final BackgroundGrid bg = plan.background, tgt = cx.target;
        final float[][] chan = {st.r, st.g, st.b};
        final float xa0 = st.x0 + 1, xa1 = st.x1 - 2, ya0 = st.y0 + 1, ya1 = st.y1 - 2;
        final float minT = cx.minT;
        Parallel.forRange(y1 - y0, 4, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                int nx = g.nx;
                float[] rx = new float[nx], ry = new float[nx];
                // per node: the clamped transparency (NaN where the frame isn't used), and per colour the
                // stack's shading gain, the target's level and the frame's own level
                float[] nT = new float[nx], nG = new float[nx * 3], nB = new float[nx * 3], nO = new float[nx * 3];
                for (int j = j0; j < j1; j++) {
                    int y = y0 + j;
                    g.row(y + 0.5, rx, ry);
                    for (int ix = 0; ix < nx; ix++) {
                        float X = rx[ix], Y = ry[ix];
                        boolean use = X > -1e5f && Y > -1e5f;
                        float T = 1f;
                        if (use && tr != null) {
                            T = tr.at(X, Y);
                            use = T >= minT && tr.minAt(X, Y) >= minT;
                        }
                        nT[ix] = use ? Stats.clamp(T, minT, SharpStacker.MAX_TRANSPARENCY) : Float.NaN;
                        for (int c = 0; c < 3; c++) {
                            nG[ix * 3 + c] = use ? gainAt(cx, c, X, Y) : 1f;
                            nB[ix * 3 + c] = use && tgt != null ? tgt.level(c, X, Y) : 0f;
                            nO[ix * 3 + c] = bg != null ? bg.level(c, ix * (double) STEP, y + 0.5) : 0f;
                        }
                    }
                    for (int x = 0; x < W; x++) {
                        int o = j * W + x;
                        out[o] = Float.NaN;
                        int ix = x / STEP;
                        float ta = nT[ix], tb = nT[ix + 1];
                        if (ta != ta || tb != tb) continue;
                        int p = ((y & 1) << 1) | (x & 1);
                        int c = cx.cfa[p];
                        int rv = raw[o] & 0xFFFF;
                        if (rv >= cx.sat) continue;
                        if (cx.hot != null && cx.hot.isBad(x, y)) continue;
                        if (cx.statics.get(y * W + x)) continue;
                        float t = (x + 0.5f - ix * STEP) / STEP;
                        float X = rx[ix] + (rx[ix + 1] - rx[ix]) * t, Y = ry[ix] + (ry[ix + 1] - ry[ix]) * t;
                        float sx = X - 0.5f, sy = Y - 0.5f;
                        if (!(sx >= xa0 && sx < xa1 && sy >= ya0 && sy < ya1)) continue;
                        int i0 = (int) sx, jr = (int) sy;
                        if (cx.stars.get(jr * W + i0)) continue;
                        float ax = sx - i0, ay = sy - jr;
                        float[] s = chan[c];
                        int q = jr * W + i0;
                        float sv = (1 - ay) * (s[q] + ax * (s[q + 1] - s[q])) + ay * (s[q + W] + ax * (s[q + W + 1] - s[q + W]));
                        int a = ix * 3 + c, b = a + 3;
                        float gn = nG[a] + (nG[b] - nG[a]) * t;
                        float base = nB[a] + (nB[b] - nB[a]) * t;
                        float own = nO[a] + (nO[b] - nO[a]) * t;
                        float tc = ta + (tb - ta) * t;
                        float pred = (sv / (gn > 1e-3f ? gn : 1f) - base) * tc + own;
                        out[o] = rv * plan.invScale - plan.black[p] - pred;
                    }
                }
            }
        });
    }

    private static float gainAt(Ctx cx, int c, float X, float Y) {
        float gx = Stats.clamp(X / SharpStacker.STEP, 0f, cx.gnx - 1.001f), gy = Stats.clamp(Y / SharpStacker.STEP, 0f, cx.gny - 1.001f);
        int ix = (int) gx, iy = (int) gy;
        float tx = gx - ix, ty = gy - iy;
        float[] a = cx.gain[c];
        int k = iy * cx.gnx + ix;
        float top = a[k] + (a[k + 1] - a[k]) * tx, bot = a[k + cx.gnx] + (a[k + cx.gnx + 1] - a[k + cx.gnx]) * tx;
        float v = top + (bot - top) * ty;
        return v > 1e-3f ? v : 1f;
    }

    /** Stack pixels with starlight (green well above the sky), grown by a pixel, and clipped ones. */
    private static BitSet starMask(Ctx cx) {
        final RgbImage st = cx.stack;
        final int W = cx.W, H = cx.H;
        // the stack's sky noise in green, from a sparse sample of its crop
        int n = 0;
        float[] smp = new float[((st.y1 - st.y0) / 4 + 1) * ((st.x1 - st.x0) / 4 + 1)];
        for (int y = st.y0; y < st.y1; y += 4) {
            for (int x = st.x0; x < st.x1; x += 4) {
                float d = st.g[y * W + x] / gainAt(cx, 1, x + 0.5f, y + 0.5f) - (cx.target != null ? cx.target.level(1, x + 0.5, y + 0.5) : 0f);
                smp[n++] = d;
            }
        }
        float med = Stats.median(smp.clone(), n);
        float sig = Math.max(1e-4f, Stats.madSigma(smp, n, med)); // (smp isn't needed after this)
        final float thr = med + STAR_SIGMA * sig;
        final BitSet raw = new BitSet(W * H);
        for (int y = st.y0; y < st.y1; y++) {
            for (int x = st.x0; x < st.x1; x++) {
                int i = y * W + x;
                float d = st.g[i] / gainAt(cx, 1, x + 0.5f, y + 0.5f) - (cx.target != null ? cx.target.level(1, x + 0.5, y + 0.5) : 0f);
                if (d > thr || (st.clip != null && st.clip[i] != 0)) raw.set(i);
            }
        }
        BitSet grown = new BitSet(W * H);
        for (int i = raw.nextSetBit(0); i >= 0; i = raw.nextSetBit(i + 1)) {
            int x = i % W, y = i / W;
            for (int dy = -1; dy <= 1; dy++) {
                int yy = y + dy;
                if (yy < 0 || yy >= H) continue;
                grown.set(yy * W + Math.max(0, x - 1), yy * W + Math.min(W, x + 2));
            }
        }
        return grown;
    }

    /** Sensor pixels in or next to the cells where fixed points crowd (a branch, a roof edge). */
    private static BitSet staticMask(StaticSources s, int W, int H) {
        BitSet m = new BitSet(W * H);
        if (s == null || s.maskedCells() == 0) return m;
        int cell = StaticSources.CELL, cnx = (W + cell - 1) / cell, cny = (H + cell - 1) / cell;
        for (int cy = 0; cy < cny; cy++) {
            for (int cx = 0; cx < cnx; cx++) {
                boolean near = false;
                for (int dy = -1; dy <= 1 && !near; dy++) {
                    for (int dx = -1; dx <= 1 && !near; dx++) {
                        int ax = cx + dx, ay = cy + dy;
                        if (ax >= 0 && ay >= 0 && ax < cnx && ay < cny) near = s.inMaskedCell(ax * cell + 0.5f, ay * cell + 0.5f);
                    }
                }
                if (!near) continue;
                int x0 = cx * cell, x1 = Math.min(W, x0 + cell);
                for (int y = cy * cell; y < Math.min(H, (cy + 1) * cell); y++) m.set(y * W + x0, y * W + x1);
            }
        }
        return m;
    }

    /** {@code v} minus its running median over +-{@link #BAND_HALF} (NaNs ignored and kept). */
    static float[] highPass(float[] v, int n) {
        float[] out = new float[n], t = new float[2 * BAND_HALF + 1];
        for (int i = 0; i < n; i++) {
            if (v[i] != v[i]) {
                out[i] = Float.NaN;
                continue;
            }
            int m = 0;
            for (int k = Math.max(0, i - BAND_HALF); k <= Math.min(n - 1, i + BAND_HALF); k++) if (v[k] == v[k]) t[m++] = v[k];
            out[i] = v[i] - Stats.median(t, m);
        }
        return out;
    }

    /**
     * The share of pattern in two independent measurements a and b of the same offsets (over all frames and
     * positions): their covariance is the pattern's variance, a quarter of their difference's variance the
     * noise of their mean. Returns {Wiener factor, pattern sigma}. Robust: a few wild values don't decide it.
     */
    static float[] wiener(float[][] a, float[][] b) {
        int n = 0;
        for (int f = 0; f < a.length; f++) n += a[f].length;
        float[] s = new float[n], d = new float[n];
        int m = 0;
        for (int f = 0; f < a.length; f++) {
            for (int i = 0; i < a[f].length; i++) {
                float x = a[f][i], y = b[f][i];
                if (x != x || y != y) continue;
                s[m] = x + y;
                d[m] = x - y;
                m++;
            }
        }
        if (m < 16) return new float[]{0f, 0f};
        // var(a + b) - var(a - b) = 4 cov(a, b); var(a - b) / 4 is the noise of (a + b) / 2
        double vs = robustVar(s, m), vd = robustVar(d, m);
        double signal = Math.max(0, (vs - vd) / 4), noise = vd / 4;
        float k = signal + noise > 0 ? (float) (signal / (signal + noise)) : 0f;
        return new float[]{k, (float) Math.sqrt(signal)};
    }

    /** Variance from the median absolute deviation (so stray outliers don't inflate it). Leaves v as it is. */
    private static double robustVar(float[] v, int n) {
        float[] t = Arrays.copyOf(v, n);
        float med = Stats.median(t, n);
        double s = Stats.madSigma(t, n, med);
        return s * s;
    }

    private static float[] columnMedians(short[] p, int W, int H) {
        float[] out = new float[W], t = new float[H];
        for (int x = 0; x < W; x++) {
            int m = 0;
            for (int y = 0; y < H; y++) {
                short h = p[y * W + x];
                if (h != Half.MISSING) t[m++] = Half.toFloat(h);
            }
            out[x] = m > 0 ? Stats.median(t, m) : Float.NaN;
        }
        return out;
    }

    /**
     * The large scale of a per-photosite map, left alone: per colour, the mean of each block of
     * {@link #LARGE} pixels, interpolated bilinearly between the block centres.
     */
    static final class Large {
        final int bw, bh;
        final float[][] v;

        private Large(int bw, int bh) {
            this.bw = bw;
            this.bh = bh;
            v = new float[3][bw * bh];
        }

        static Large of(short[] p, int W, int H, int[] cfa) {
            int bw = (W + LARGE - 1) / LARGE, bh = (H + LARGE - 1) / LARGE;
            Large l = new Large(bw, bh);
            double[][] s = new double[3][bw * bh];
            int[][] n = new int[3][bw * bh];
            for (int y = 0; y < H; y++) {
                int by = y / LARGE;
                for (int x = 0; x < W; x++) {
                    short h = p[y * W + x];
                    if (h == Half.MISSING) continue;
                    int c = cfa[((y & 1) << 1) | (x & 1)], k = by * bw + x / LARGE;
                    s[c][k] += Half.toFloat(h);
                    n[c][k]++;
                }
            }
            for (int c = 0; c < 3; c++) {
                for (int k = 0; k < bw * bh; k++) l.v[c][k] = n[c][k] > 0 ? (float) (s[c][k] / n[c][k]) : Float.NaN;
                // a block without data takes its neighbours' mean, or 0
                for (int k = 0; k < bw * bh; k++) {
                    if (n[c][k] > 0) continue;
                    int bx = k % bw, byy = k / bw, m = 0;
                    double t = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = bx + dx, yy = byy + dy;
                            if (xx < 0 || yy < 0 || xx >= bw || yy >= bh || n[c][yy * bw + xx] == 0) continue;
                            t += s[c][yy * bw + xx] / n[c][yy * bw + xx];
                            m++;
                        }
                    }
                    l.v[c][k] = m > 0 ? (float) (t / m) : 0f;
                }
            }
            return l;
        }

        float at(int c, int x, int y) {
            float gx = Stats.clamp((x + 0.5f) / LARGE - 0.5f, 0f, bw - 1f), gy = Stats.clamp((y + 0.5f) / LARGE - 0.5f, 0f, bh - 1f);
            int ix = Math.min((int) gx, bw - 1), iy = Math.min((int) gy, bh - 1);
            int ix1 = Math.min(ix + 1, bw - 1), iy1 = Math.min(iy + 1, bh - 1);
            float tx = gx - ix, ty = gy - iy;
            float[] a = v[c];
            float top = a[iy * bw + ix] + (a[iy * bw + ix1] - a[iy * bw + ix]) * tx;
            float bot = a[iy1 * bw + ix] + (a[iy1 * bw + ix1] - a[iy1 * bw + ix]) * tx;
            return top + (bot - top) * ty;
        }
    }

    /**
     * Box mean over +-{@link #SMOOTH} photosites of the same colour plane of the two halves' mean (missing
     * photosites left out), made in place on one new map.
     */
    private static short[] boxOfMean(final short[] a, final short[] b, final int W, final int H) {
        final short[] m = new short[W * H];
        for (int i = 0; i < W * H; i++) {
            short x = a[i], y = b[i];
            boolean hx = x != Half.MISSING, hy = y != Half.MISSING;
            m[i] = hx && hy ? Half.fromFloat(0.5f * (Half.toFloat(x) + Half.toFloat(y))) : (hx ? x : (hy ? y : Half.MISSING));
        }
        final int R = SMOOTH;
        // along rows, within each colour plane (every other photosite)
        Parallel.forRange(H, 16, new Parallel.Range() {
            @Override
            public void run(int ya, int yb) {
                float[] t = new float[W];
                for (int y = ya; y < yb; y++) {
                    for (int x = 0; x < W; x++) {
                        short h = m[y * W + x];
                        t[x] = h == Half.MISSING ? Float.NaN : Half.toFloat(h);
                    }
                    for (int o = 0; o < 2; o++) runningMean(t, o, (W - o + 1) / 2, R, m, y * W, 1);
                }
            }
        });
        // along columns
        Parallel.forRange(W, 16, new Parallel.Range() {
            @Override
            public void run(int xa, int xb) {
                float[] t = new float[H];
                for (int x = xa; x < xb; x++) {
                    for (int y = 0; y < H; y++) {
                        short h = m[y * W + x];
                        t[y] = h == Half.MISSING ? Float.NaN : Half.toFloat(h);
                    }
                    for (int o = 0; o < 2; o++) runningMean(t, o, (H - o + 1) / 2, R, m, x, W);
                }
            }
        });
        return m;
    }

    /**
     * Running mean over +-R of t[o + 2u] (u = 0..n-1, NaNs left out) into dst[base + (o + 2u) * stride]
     * (missing where the window holds nothing).
     */
    private static void runningMean(float[] t, int o, int n, int R, short[] dst, int base, int stride) {
        double sum = 0;
        int c = 0;
        for (int u = 0; u <= Math.min(R, n - 1); u++) {
            float v = t[o + 2 * u];
            if (v == v) {
                sum += v;
                c++;
            }
        }
        for (int u = 0; u < n; u++) {
            dst[base + (o + 2 * u) * stride] = c > 0 ? Half.fromFloat((float) (sum / c)) : Half.MISSING;
            int add = u + R + 1, rem = u - R;
            if (add < n) {
                float v = t[o + 2 * add];
                if (v == v) {
                    sum += v;
                    c++;
                }
            }
            if (rem >= 0) {
                float v = t[o + 2 * rem];
                if (v == v) {
                    sum -= v;
                    c--;
                }
            }
        }
    }

    /**
     * Per colour, from a sample of photosites: covariance and difference variance of the two halves' fine
     * parts (value minus the box mean of both), and of their mid-scale parts (each half's own box mean minus
     * its large scale).
     */
    private static double[][] splitStats(short[] po, short[] pe, short[] s4, Large lo, Large le, int W, int H, int[] cfa) {
        final int STRIDE = 13, R = SMOOTH;
        double[][] out = new double[3][4];
        int cap = W * H / STRIDE + 16;
        float[][] fs = new float[3][cap], fd = new float[3][cap], ms = new float[3][cap], md = new float[3][cap];
        int[] n = new int[3];
        for (int i = 0; i < W * H; i += STRIDE) {
            short a = po[i], b = pe[i];
            if (a == Half.MISSING || b == Half.MISSING || s4[i] == Half.MISSING) continue;
            int x = i % W, y = i / W;
            int c = cfa[((y & 1) << 1) | (x & 1)];
            float s = Half.toFloat(s4[i]);
            float fa = Half.toFloat(a) - s, fb = Half.toFloat(b) - s;
            // each half's own box mean, here only
            double sa = 0, sb = 0;
            int na = 0, nb = 0;
            for (int dy = -R; dy <= R; dy++) {
                int yy = y + 2 * dy;
                if (yy < 0 || yy >= H) continue;
                for (int dx = -R; dx <= R; dx++) {
                    int xx = x + 2 * dx;
                    if (xx < 0 || xx >= W) continue;
                    short ha = po[yy * W + xx], hb = pe[yy * W + xx];
                    if (ha != Half.MISSING) {
                        sa += Half.toFloat(ha);
                        na++;
                    }
                    if (hb != Half.MISSING) {
                        sb += Half.toFloat(hb);
                        nb++;
                    }
                }
            }
            if (na == 0 || nb == 0) continue;
            float ma = (float) (sa / na) - lo.at(c, x, y), mb = (float) (sb / nb) - le.at(c, x, y);
            int k = n[c]++;
            fs[c][k] = fa + fb;
            fd[c][k] = fa - fb;
            ms[c][k] = ma + mb;
            md[c][k] = ma - mb;
        }
        for (int c = 0; c < 3; c++) {
            if (n[c] < 16) continue;
            double vs = robustVar(fs[c], n[c]), vd = robustVar(fd[c], n[c]);
            out[c][0] = (vs - vd) / 4;
            out[c][1] = vd;
            // the mid parts are spatially correlated, so their plain variances (not a MAD of mostly-equal neighbours)
            out[c][2] = (var(ms[c], n[c]) - var(md[c], n[c])) / 4;
            out[c][3] = var(md[c], n[c]);
        }
        return out;
    }

    private static double var(float[] v, int n) {
        double s = 0, s2 = 0;
        for (int i = 0; i < n; i++) {
            s += v[i];
            s2 += (double) v[i] * v[i];
        }
        double m = s / n;
        return Math.max(0, s2 / n - m * m);
    }

    // ------------------------------------------------------------------------------------------------
    // Measuring walking noise
    // ------------------------------------------------------------------------------------------------

    /** Segment length (pixels) for {@link #walkingRatio}. */
    public static final int WALK_SEGMENT = 8;

    /**
     * Walking noise in a stack: the sky background's noise averaged over short segments along the drift
     * (unit vector ux, uy), over the same averaged across it. Streaks along the drift survive the first
     * average and not the second; noise without a direction gives 1. Measured on the green channel of the
     * central part of the crop, with the large-scale sky taken out and starlight masked.
     */
    public static double walkingRatio(RgbImage img, double ux, double uy) {
        double nrm = Math.hypot(ux, uy);
        if (!(nrm > 0)) return Double.NaN;
        ux /= nrm;
        uy /= nrm;
        int W = img.width;
        int cw = Math.min(1536, img.x1 - img.x0), ch = Math.min(1536, img.y1 - img.y0);
        if (cw < 64 || ch < 64) return Double.NaN;
        int ox = img.x0 + (img.x1 - img.x0 - cw) / 2, oy = img.y0 + (img.y1 - img.y0 - ch) / 2;
        float[] a = new float[cw * ch];
        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) a[y * cw + x] = img.g[(oy + y) * W + ox + x];
        // the large-scale sky out: minus a 31 x 31 box mean (two separable passes)
        float[] hp = minusBox(a, cw, ch, 15);
        // starlight: well above the noise, grown by two pixels
        float[] t = hp.clone();
        float med = Stats.median(t, t.length);
        float sig = Stats.madSigma(t, t.length, med);
        boolean[] star = new boolean[cw * ch];
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                if (hp[y * cw + x] - med <= 3 * sig) continue;
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int xx = x + dx, yy = y + dy;
                        if (xx >= 0 && yy >= 0 && xx < cw && yy < ch) star[yy * cw + xx] = true;
                    }
                }
            }
        }
        int L = WALK_SEGMENT, cap = (cw / 3 + 1) * (ch / 3 + 1);
        float[] along = new float[cap], across = new float[cap];
        int n = 0;
        double h = (L - 1) / 2.0, margin = L + 2;
        for (int y = (int) margin; y < ch - margin; y += 3) {
            for (int x = (int) margin; x < cw - margin; x += 3) {
                double sa = 0, sc = 0;
                boolean bad = false;
                for (int i = 0; i < L && !bad; i++) {
                    double s = i - h;
                    float va = sample(hp, star, cw, x + s * ux, y + s * uy), vc = sample(hp, star, cw, x - s * uy, y + s * ux);
                    if (va != va || vc != vc) bad = true;
                    sa += va;
                    sc += vc;
                }
                if (bad) continue;
                along[n] = (float) (sa / L);
                across[n] = (float) (sc / L);
                n++;
            }
        }
        if (n < 100) return Double.NaN;
        double va = robustVar(along, n), vc = robustVar(across, n);
        return vc > 0 ? Math.sqrt(va / vc) : Double.NaN;
    }

    private static float sample(float[] a, boolean[] mask, int w, double x, double y) {
        int i = (int) Math.floor(x), j = (int) Math.floor(y);
        float tx = (float) (x - i), ty = (float) (y - j);
        int k = j * w + i;
        if (mask[k] || mask[k + 1] || mask[k + w] || mask[k + w + 1]) return Float.NaN;
        return (1 - ty) * (a[k] + tx * (a[k + 1] - a[k])) + ty * (a[k + w] + tx * (a[k + w + 1] - a[k + w]));
    }

    private static float[] minusBox(float[] a, int w, int h, int r) {
        float[] t = new float[w * h], out = new float[w * h];
        for (int y = 0; y < h; y++) {
            double s = 0;
            int c = 0;
            for (int x = 0; x <= Math.min(r, w - 1); x++) {
                s += a[y * w + x];
                c++;
            }
            for (int x = 0; x < w; x++) {
                t[y * w + x] = (float) (s / c);
                if (x + r + 1 < w) {
                    s += a[y * w + x + r + 1];
                    c++;
                }
                if (x - r >= 0) {
                    s -= a[y * w + x - r];
                    c--;
                }
            }
        }
        for (int x = 0; x < w; x++) {
            double s = 0;
            int c = 0;
            for (int y = 0; y <= Math.min(r, h - 1); y++) {
                s += t[y * w + x];
                c++;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = a[y * w + x] - (float) (s / c);
                if (y + r + 1 < h) {
                    s += t[(y + r + 1) * w + x];
                    c++;
                }
                if (y - r >= 0) {
                    s -= t[(y - r) * w + x];
                    c--;
                }
            }
        }
        return out;
    }
}
