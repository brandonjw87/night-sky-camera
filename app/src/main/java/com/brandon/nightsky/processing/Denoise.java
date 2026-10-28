package com.brandon.nightsky.processing;

import java.util.Locale;

/**
 * The sky's noise taken out of the luminance, the stars' light left as it was (1.4).
 * <p>
 * The stars are found first: a matched filter (a Gaussian the size of a star image) standing 2.5 times its noise over
 * the picture smoothed at 8 px, each such pixel given a disc out to where a star that bright falls under the noise.
 * Their pixels are filled in from the sky around them (the rest smoothed with a B3 spline and renormalised, at the
 * finest of three scales that reaches, or from a quarter-size version for the widest gaps), which leaves the starless
 * sky. The starlet (B3-spline smoothing with holes) splits that into detail layers of about 1-2, 2-4 and 4-8 px; what
 * is left, smooth at 8 px, is taken every 4th pixel and split again into five coarser layers. Each layer's noise is
 * measured on the sky between the stars (the median of its absolute details: without the stars in them, faint stars
 * no longer pass for noise), and how the noise changes over the picture (lens shading correction brightens the
 * corners, the stack is thinner at its edges) cell by cell from the finest layer. The fine layers' details are shrunk
 * by the non-negative garrote at 3 times their noise, a little less where the starless sky is bright (the Milky Way);
 * the coarse layers' by a local Wiener gain (their energy around each point against the noise's), which lowers
 * blotches without cutting holes into faint, wide light.
 * <p>
 * What that takes out of the starless sky is the correction, added to every pixel of the picture: stars keep their
 * light and shape, the sky around them loses its grain. Two passes find the stars: the first with a guess of the
 * matched filter's noise, the second with the noise measured on the sky the first one left.
 * <p>
 * A copy binned smaller (Quick look's half-size photo) is matched with its own, smaller star images.
 * <p>
 * The correction is a half float per pixel of the crop, and it depends only on the picture: the work is done in tiles,
 * each with the context around it that it needs (mirrored at the picture's edges), so it comes out the same, bit for
 * bit, however many threads share it.
 */
public final class Denoise {
    private Denoise() {}

    /** Share of the noise found in the fine layers that is taken out, and in the coarse layers. */
    static final float FINE = 0.8f, COARSE = 0.8f;
    /** The fine layers' garrote threshold, in their noise. */
    static final float K = 3f;
    /** A star: the matched filter over the 8 px smooth by this many times its noise. */
    static final float DETECT = 2.5f;
    /**
     * The first guess of the matched filter's noise, as a share of the finest layer's noise where the matched filter is
     * under the smooth. Faint stars swell that layer, so the guess is on the low side: it finds more stars than there
     * are, which keeps them out of the noise measured next.
     */
    static final float MF_RATIO0 = 0.30f;
    /**
     * The fine layers lose less where the starless sky stands BRIGHT_LO to BRIGHT_HI times the finest layer's noise over
     * the fitted background (the Milky Way), down to (1 - BRIGHT_KEEP) of what they'd lose.
     */
    static final float BRIGHT_LO = 4f, BRIGHT_HI = 16f, BRIGHT_KEEP = 0.5f;
    /** The coarse layers' Wiener gain: their energy around a point against KW times the noise's. */
    static final float KW = 1f;
    /** Fine layers; the coarse ones, on every QF-th pixel of the 8 px smooth. */
    public static final int LEVELS = 3, QF = 4, QLEVELS = 5;
    /** Tiles of work, and cells of the noise map (a tile holds 2 x 2 cells, so each cell is one tile's). */
    public static final int TILE = 256, CELL = 128;
    /** Smaller pictures are left as they are. */
    public static final int MIN_SIZE = 64;

    /** Context around a tile: for the fine layers (2 x (1 + 2 + 4)), the widest star disc, filling in the stars. */
    private static final int R_LAYERS = 14, R_DISC = 8, R_FILL = 14;
    /**
     * The matched filter: a Gaussian the size of a star image (in a full-size picture; a smaller copy's stars are
     * smaller), and its peak's signal-to-noise against a star's own peak's (sqrt(pi) times its sigma).
     */
    private static final float MF_SIGMA = 1.2f;
    private static final double MF_GAIN = 2.13;
    private static final int MF_R = 4;
    private static final float[] MF_K = gauss(MF_R, MF_SIGMA);
    /** Median absolute value to standard deviation, for Gaussian noise. */
    private static final float MAD = 1.4826f;
    /** Histograms of |detail| in units of the analysed noise: bins per unit, units covered. */
    private static final int HB = 1024, HU = 8, NB = HB * HU;
    /** A star's pixel is filled in where this much of the B3 window's weight falls on the sky. */
    private static final float FILL_WEIGHT = 0.2f;
    /** Sky pixels a noise map cell needs to be measured (fewer: it's taken from the cells around it). */
    private static final int CELL_MIN = 200;
    /** Levels of the quarter-size fill (B3 steps 1 to 512 quarter pixels). */
    private static final int FILL4_LEVELS = 10;

    /** What it found, and the correction. */
    public static final class Result {
        /** The crop's size, and per pixel of it, row after row, what to add to the luminance (half floats). */
        public int cw, ch;
        public short[] dy;
        /** The fine layers' noise and the coarse layers' (luminance units). */
        public final float[] sigma = new float[LEVELS], sigmaQ = new float[QLEVELS];
        /** The matched filter's noise, measured on the starless sky, and its first guess. */
        public float sigmaMf, guessMf;
        /** Share of the pixels taken as stars. */
        public float starFraction;
        /** Noise over the picture relative to the whole picture's: the quietest cell and the noisiest. */
        public float mapMin, mapMax;
        public long ms;

        public String describe() {
            return String.format(Locale.US, "noise %.3f / %.3f / %.3f in the 1-2, 2-4 and 4-8 px layers and %.4f to %.4f in the "
                            + "coarse ones, measured between the stars (stars on %.0f%% of the pixels; matched filter's noise %.3f, "
                            + "first guess %.3f); %.2f to %.2f times the average over the picture (%d ms)", sigma[0], sigma[1],
                    sigma[2], min(sigmaQ), max(sigmaQ), 100 * starFraction, sigmaMf, guessMf, mapMin, mapMax, ms);
        }

        /** What to add to the luminance of crop pixel (x, y). */
        public float at(int x, int y) {
            return Half.toFloat(dy[y * cw + x]);
        }
    }

    /**
     * Finds the noise of the analysed crop of {@code img} and works out the correction; null if the picture is too
     * small or no noise could be measured. {@code noiseScale}: the image's noise relative to the analysed full-size
     * one (1 / binning factor), which only sets the scale of the counts.
     */
    public static Result run(RgbImage img, Renderer.Analysis a, float noiseScale) {
        return run(img, a, noiseScale, TILE);
    }

    /**
     * {@code r}'s correction binned {@code factor} x {@code factor} as {@link RgbImage#binned} bins its picture's crop,
     * for a w x h copy (the photo's preview shows the full-size photo's noise reduction exactly so).
     */
    public static Result binned(final Result r, final int factor, final int w, final int h) {
        final Result b = new Result();
        b.cw = w;
        b.ch = h;
        System.arraycopy(r.sigma, 0, b.sigma, 0, LEVELS);
        System.arraycopy(r.sigmaQ, 0, b.sigmaQ, 0, QLEVELS);
        b.sigmaMf = r.sigmaMf;
        b.guessMf = r.guessMf;
        b.starFraction = r.starFraction;
        b.mapMin = r.mapMin;
        b.mapMax = r.mapMax;
        final short[] dy = new short[w * h];
        final float inv = 1f / (factor * factor);
        Parallel.forRange(h, 8, new Parallel.Range() {
            @Override
            public void run(int j0, int j1) {
                for (int j = j0; j < j1; j++) {
                    for (int i = 0; i < w; i++) {
                        float s = 0;
                        for (int d = 0; d < factor; d++) {
                            int o = (j * factor + d) * r.cw + i * factor;
                            for (int e = 0; e < factor; e++) s += Half.toFloat(r.dy[o + e]);
                        }
                        dy[j * w + i] = Half.fromFloat(s * inv);
                    }
                }
            }
        });
        b.dy = dy;
        return b;
    }

    /** {@link #run} in tiles of another size (a multiple of {@link #CELL}): the same result, for the tests. */
    public static Result run(RgbImage img, Renderer.Analysis a, float noiseScale, int tile) {
        if (a.cw < MIN_SIZE || a.ch < MIN_SIZE) return null;
        if (tile % CELL != 0) throw new IllegalArgumentException("tiles of " + tile + " px don't hold whole cells");
        long t0 = System.currentTimeMillis();
        Job j = new Job(img, a, noiseScale, tile);
        // a first guess of the matched filter's noise
        float s1 = j.guess();
        if (!(s1 > 0)) return null;
        Result res = new Result();
        res.cw = j.cw;
        res.ch = j.ch;
        res.guessMf = j.ratio0 * s1;
        // stars with the guess, filled in; the noise measured on the sky that leaves
        j.footprints(res.guessMf);
        if (!j.measure(res)) return null;
        j.coarse(res);
        // stars again with the measured noise; the correction from the sky that leaves
        res.starFraction = j.footprints(res.sigmaMf);
        res.dy = j.correct(res);
        res.ms = System.currentTimeMillis() - t0;
        return res;
    }

    /** The whole picture's state between the passes over its tiles. */
    private static final class Job {
        final RgbImage img;
        final Renderer.Analysis a;
        /** The crop's size; tiles' size, how many there are each way, and noise map cells per tile each way. */
        final int cw, ch, tile, ntx, nty, cpt;
        /** The luminance's weights on camera RGB. */
        final float yr, yg, yb;
        /** The counts' unit (the analysed noise) and its inverse. */
        final float unit, inv;
        /** Star pixels, a bit each, rows of wpr words (a tile's columns are whole words: tiles are a multiple of 64). */
        final long[] foot;
        final int wpr;
        /** Per 4 x 4 block: the sum of the luminance over its sky pixels and how many there are; the quarter-size fill. */
        final int fw, fh;
        final float[] bSum, bCnt;
        float[] fill4;
        /** The 8 px smooth of the starless sky every QF-th pixel, and whether that pixel is sky. */
        final int qw, qh;
        final float[] q;
        final boolean[] qSky;
        /** Noise map cells: each one's noise of the finest layer (in units; NaN: too little sky), then relative. */
        final int ncx, ncy;
        final float[] cells;
        float[] map;
        /** The coarse layers' correction, every QF-th pixel. */
        float[] dq;

        /**
         * A star image's sigma in this picture, the matched filter's taps and their reach, its gain ({@link #MF_GAIN})
         * and the first guess's share ({@link #MF_RATIO0}). A copy binned f x f (noiseScale 1 / f) has stars f times
         * smaller, blurred by its bigger pixels: matched as they are, fewer of its pixels go to the stars.
         */
        final float psf, ratio0;
        final double gain;
        final float[] mfK;
        final int mfR;

        Job(RgbImage img, Renderer.Analysis a, float noiseScale, int tile) {
            if (noiseScale >= 1f) {
                psf = MF_SIGMA;
                mfR = MF_R;
                mfK = MF_K;
                gain = MF_GAIN;
                ratio0 = MF_RATIO0;
            } else {
                psf = (float) Math.sqrt(MF_SIGMA * noiseScale * MF_SIGMA * noiseScale + (1 - noiseScale * noiseScale) / 12f);
                mfR = Math.max(2, (int) Math.ceil(3.3f * psf));
                mfK = gauss(mfR, psf);
                double g = 0;
                for (int i = -mfR; i <= mfR; i++) g += Math.exp(-i * i / (double) (psf * psf));
                gain = g;
                // the matched filter's noise against the finest layer's goes as the sum of its taps' squares
                float n0 = 0, n1 = 0;
                for (float v : MF_K) n0 += v * v;
                for (float v : mfK) n1 += v * v;
                ratio0 = MF_RATIO0 * n1 / n0;
            }
            this.img = img;
            this.a = a;
            cw = a.cw;
            ch = a.ch;
            this.tile = tile;
            ntx = (cw + tile - 1) / tile;
            nty = (ch + tile - 1) / tile;
            cpt = tile / CELL;
            float[] m = a.m;
            yr = 0.2126f * m[0] + 0.7152f * m[3] + 0.0722f * m[6];
            yg = 0.2126f * m[1] + 0.7152f * m[4] + 0.0722f * m[7];
            yb = 0.2126f * m[2] + 0.7152f * m[5] + 0.0722f * m[8];
            unit = Math.max(1e-6f, a.noiseY * noiseScale);
            inv = 1f / unit;
            wpr = (cw + 63) >>> 6;
            foot = new long[wpr * ch];
            fw = (cw + 3) / 4;
            fh = (ch + 3) / 4;
            bSum = new float[fw * fh];
            bCnt = new float[fw * fh];
            qw = cw / QF;
            qh = ch / QF;
            q = new float[qw * qh];
            qSky = new boolean[qw * qh];
            ncx = (cw + CELL - 1) / CELL;
            ncy = (ch + CELL - 1) / CELL;
            cells = new float[ncx * ncy];
        }

        /** Workers' buffers, kept for the next pass. */
        private final java.util.ArrayDeque<Work> idle = new java.util.ArrayDeque<Work>();

        Work work() {
            synchronized (idle) {
                Work w = idle.poll();
                return w != null ? w : new Work(this);
            }
        }

        void done(Work w) {
            synchronized (idle) {
                idle.push(w);
            }
        }

        int tiles() {
            return ntx * nty;
        }

        int grain(int n) {
            return Math.max(1, n / Parallel.threads());
        }

        /** The finest layer's noise where the matched filter is under the 8 px smooth. */
        float guess() {
            final int[] hist = new int[NB];
            Parallel.forRange(tiles(), grain(tiles()), new Parallel.Range() {
                @Override
                public void run(int t0, int t1) {
                    Work w = work();
                    int[] h = new int[NB];
                    for (int t = t0; t < t1; t++) {
                        w.at(t % ntx, t / ntx, R_LAYERS);
                        w.load(false);
                        w.layers(w.Y);
                        w.matched(w.Y, w.MF);
                        for (int y = w.y0; y < w.y1; y++) {
                            int o = (y - w.by0) * w.bw - w.bx0;
                            for (int x = w.x0; x < w.x1; x++) {
                                int i = o + x;
                                if (w.MF[i] - w.C3[i] < 0) h[bin(Math.abs(w.Y[i] - w.C1[i]) * inv)]++;
                            }
                        }
                    }
                    add(hist, h);
                    done(w);
                }
            });
            return MAD * unit * median(hist, 0);
        }

        /**
         * The star pixels where the matched filter stands {@link #DETECT} times {@code smf} over the 8 px smooth, and the
         * quarter-size fill of the sky around them; returns the share of the pixels that are stars.
         */
        float footprints(final float smf) {
            final long[] stars = new long[1];
            Parallel.forRange(tiles(), grain(tiles()), new Parallel.Range() {
                @Override
                public void run(int t0, int t1) {
                    Work w = work();
                    long n = 0;
                    for (int t = t0; t < t1; t++) {
                        w.at(t % ntx, t / ntx, R_LAYERS + R_DISC);
                        w.load(false);
                        w.layers(w.Y);
                        w.matched(w.Y, w.MF);
                        n += w.stamp(1f / smf);
                    }
                    synchronized (stars) {
                        stars[0] += n;
                    }
                    done(w);
                }
            });
            buildFill4();
            return stars[0] / (float) ((long) cw * ch);
        }

        /**
         * The noise of each fine layer and of the matched filter on the starless sky, the noise map, and the coarse
         * layers' samples; false if no noise could be measured.
         */
        boolean measure(Result res) {
            final int[] hist = new int[(LEVELS + 1) * NB];
            Parallel.forRange(tiles(), grain(tiles()), new Parallel.Range() {
                @Override
                public void run(int t0, int t1) {
                    Work w = work();
                    int[] h = new int[(LEVELS + 1) * NB];
                    int[] hc = new int[cpt * cpt * NB];
                    for (int t = t0; t < t1; t++) {
                        w.at(t % ntx, t / ntx, R_LAYERS + R_FILL);
                        w.load(true);
                        w.fill();
                        w.layers(w.BG);
                        w.matched(w.BG, w.MF);
                        java.util.Arrays.fill(hc, 0);
                        int cx0 = w.x0 / CELL, cy0 = w.y0 / CELL;
                        for (int y = w.y0; y < w.y1; y++) {
                            int o = (y - w.by0) * w.bw - w.bx0;
                            boolean qRow = y % QF == QF / 2 && y / QF < qh;
                            int cr = (y / CELL - cy0) * cpt;
                            for (int x = w.x0; x < w.x1; x++) {
                                int i = o + x;
                                float c1 = w.C1[i], c2 = w.C2[i], c3 = w.C3[i];
                                boolean sky = !w.F[i];
                                if (qRow && x % QF == QF / 2 && x / QF < qw) {
                                    int k = (y / QF) * qw + x / QF;
                                    q[k] = c3;
                                    qSky[k] = sky;
                                }
                                if (!sky) continue;
                                int b0 = bin(Math.abs(w.BG[i] - c1) * inv);
                                h[b0]++;
                                h[NB + bin(Math.abs(c1 - c2) * inv)]++;
                                h[2 * NB + bin(Math.abs(c2 - c3) * inv)]++;
                                h[3 * NB + bin(Math.abs(w.MF[i] - c3) * inv)]++;
                                hc[(cr + x / CELL - cx0) * NB + b0]++;
                            }
                        }
                        // the tile's own cells
                        for (int cy = cy0; cy < Math.min(ncy, cy0 + cpt); cy++) {
                            for (int cx = cx0; cx < Math.min(ncx, cx0 + cpt); cx++) {
                                int off = ((cy - cy0) * cpt + cx - cx0) * NB;
                                long n = 0;
                                for (int k = 0; k < NB; k++) n += hc[off + k];
                                cells[cy * ncx + cx] = n >= CELL_MIN ? MAD * median(hc, off) : Float.NaN;
                            }
                        }
                    }
                    add(hist, h);
                    done(w);
                }
            });
            for (int l = 0; l < LEVELS; l++) res.sigma[l] = MAD * unit * median(hist, l * NB);
            res.sigmaMf = MAD * unit * median(hist, LEVELS * NB);
            if (!(res.sigma[0] > 0) || !(res.sigmaMf > 0)) return false;
            map = noiseMap(cells, ncx, ncy, res.sigma[0] * inv);
            res.mapMin = min(map);
            res.mapMax = max(map);
            return true;
        }

        /** The coarse layers of the starless sky's 8 px smooth: their noise, and the correction by the local Wiener gain. */
        void coarse(Result res) {
            float[] cur = q.clone(), nxt = new float[q.length], tmp = new float[q.length];
            float[] d = new float[q.length], e = new float[q.length], buf = new float[q.length];
            float[] out = new float[q.length];
            float[] nq = new float[q.length], nrow = new float[ncx];
            for (int y = 0; y < qh; y++) {
                mapRow(y * QF + QF / 2, nrow);
                for (int x = 0; x < qw; x++) nq[y * qw + x] = cellAt(nrow, x * QF + QF / 2);
            }
            for (int l = 0; l < QLEVELS; l++) {
                int step = 1 << l;
                b3Grid(cur, nxt, tmp, qw, qh, step);
                int n = 0;
                for (int k = 0; k < q.length; k++) {
                    d[k] = cur[k] - nxt[k];
                    if (qSky[k]) buf[n++] = Math.abs(d[k]);
                }
                float s = n > 16 ? MAD * Stats.median(buf, n) : 0f;
                res.sigmaQ[l] = s;
                if (s > 0) {
                    // the layer's energy around each point (two B3 windows, this layer's size and twice it)
                    for (int k = 0; k < q.length; k++) e[k] = d[k] * d[k];
                    b3Grid(e, e, tmp, qw, qh, step);
                    b3Grid(e, e, tmp, qw, qh, 2 * step);
                    float nv0 = KW * s * s;
                    for (int k = 0; k < q.length; k++) {
                        float nk = nq[k];
                        float nv = nv0 * nk * nk;
                        float g = e[k] > nv ? (e[k] - nv) / e[k] : 0f;
                        out[k] += COARSE * (g - 1f) * d[k];
                    }
                }
                float[] t = cur;
                cur = nxt;
                nxt = t;
            }
            dq = out;
        }

        /** The correction, a half float per crop pixel. */
        short[] correct(final Result res) {
            final short[] dy = new short[cw * ch];
            Parallel.forRange(tiles(), grain(tiles()), new Parallel.Range() {
                @Override
                public void run(int t0, int t1) {
                    Work w = work();
                    float[] nrow = new float[ncx];
                    double[][] P = a.poly;
                    float s0 = res.sigma[0], s1 = res.sigma[1], s2 = res.sigma[2];
                    float bright = 1f / s0;
                    for (int t = t0; t < t1; t++) {
                        w.at(t % ntx, t / ntx, R_LAYERS + R_FILL);
                        w.load(true);
                        w.fill();
                        w.layers(w.BG);
                        for (int y = w.y0; y < w.y1; y++) {
                            int o = (y - w.by0) * w.bw - w.bx0;
                            mapRow(y, nrow);
                            // the fitted sky background's luminance along the row: c0 + c1 u + c2 u^2
                            double v = ((y + 0.5) / ch) * 2 - 1;
                            double p0 = 0, p1 = 0, p2 = 0;
                            for (int c = 0; c < 3; c++) {
                                double[] k = P[c];
                                double wt = c == 0 ? 0.2126 : c == 1 ? 0.7152 : 0.0722;
                                p0 += wt * (k[0] + k[2] * v + k[5] * v * v);
                                p1 += wt * (k[1] + k[4] * v);
                                p2 += wt * k[3];
                            }
                            // the coarse correction's rows either side of this one
                            float qv = Math.min(qh - 1f, Math.max(0f, (y - QF / 2) / (float) QF));
                            int j0 = Math.min(qh - 2, (int) qv);
                            float tv = Math.min(1f, qv - j0);
                            int q0 = j0 * qw, q1 = q0 + qw;
                            for (int x = w.x0; x < w.x1; x++) {
                                int i = o + x;
                                float n = cellAt(nrow, x);
                                float bg = w.BG[i], c1 = w.C1[i], c2 = w.C2[i], c3 = w.C3[i];
                                float w1 = bg - c1, w2 = c1 - c2, w3 = c2 - c3;
                                float d = garrote(w1, K * s0 * n) - w1 + garrote(w2, K * s1 * n) - w2 + garrote(w3, K * s2 * n) - w3;
                                double u = ((x + 0.5) / cw) * 2 - 1;
                                float above = (float) (c3 - (p0 + u * (p1 + u * p2))) * bright;
                                float amt = FINE * (1f - BRIGHT_KEEP * smooth(above, BRIGHT_LO, BRIGHT_HI));
                                float qu = Math.min(qw - 1f, Math.max(0f, (x - QF / 2) / (float) QF));
                                int i0 = Math.min(qw - 2, (int) qu);
                                float tu = Math.min(1f, qu - i0);
                                float top = dq[q0 + i0] + tu * (dq[q0 + i0 + 1] - dq[q0 + i0]);
                                float bot = dq[q1 + i0] + tu * (dq[q1 + i0 + 1] - dq[q1 + i0]);
                                dy[y * cw + x] = Half.fromFloat(amt * d + top + tv * (bot - top));
                            }
                        }
                    }
                    done(w);
                }
            });
            return dy;
        }

        /**
         * Each 4 x 4 block's sky mean where half of it or more is sky, the rest filled from the blocks around (B3
         * smoothing renormalised, the finest level that reaches); what's still empty takes the mean of all the sky.
         */
        void buildFill4() {
            int n = fw * fh;
            float[] v = new float[n], wt = new float[n], tmp = new float[n];
            float[] out = new float[n];
            boolean[] done = new boolean[n];
            double sum = 0, cnt = 0;
            int left = 0;
            for (int by = 0; by < fh; by++) {
                int ph = Math.min(4, ch - by * 4);
                for (int bx = 0; bx < fw; bx++) {
                    int k = by * fw + bx;
                    float px = ph * Math.min(4, cw - bx * 4);
                    sum += bSum[k];
                    cnt += bCnt[k];
                    v[k] = bSum[k] / px;
                    wt[k] = bCnt[k] / px;
                    if (bCnt[k] >= 0.5f * px) {
                        out[k] = bSum[k] / bCnt[k];
                        done[k] = true;
                    } else {
                        left++;
                    }
                }
            }
            for (int l = 0; l < FILL4_LEVELS && left > 0; l++) {
                b3Grid(v, v, tmp, fw, fh, 1 << l);
                b3Grid(wt, wt, tmp, fw, fh, 1 << l);
                for (int k = 0; k < n; k++) {
                    if (done[k] || wt[k] < FILL_WEIGHT) continue;
                    out[k] = v[k] / wt[k];
                    done[k] = true;
                    left--;
                }
            }
            if (left > 0) {
                float mean = cnt > 0 ? (float) (sum / cnt) : 0f;
                for (int k = 0; k < n; k++) if (!done[k]) out[k] = mean;
            }
            fill4 = out;
        }

        /** The quarter-size fill at crop pixel (x, y), bilinear between the blocks' centres. */
        float fill4At(int x, int y) {
            float u = Math.min(fw - 1f, Math.max(0f, (x - 1.5f) * 0.25f)), v = Math.min(fh - 1f, Math.max(0f, (y - 1.5f) * 0.25f));
            int i0 = Math.min(fw - 2, (int) u), j0 = Math.min(fh - 2, (int) v);
            float tu = Math.min(1f, u - i0), tv = Math.min(1f, v - j0);
            int k = j0 * fw + i0;
            float top = fill4[k] + tu * (fill4[k + 1] - fill4[k]);
            float bot = fill4[k + fw] + tu * (fill4[k + fw + 1] - fill4[k + fw]);
            return top + tv * (bot - top);
        }

        /** The noise map's values along crop row y, one per cell column. */
        void mapRow(int y, float[] out) {
            float v = (y + 0.5f) / CELL - 0.5f;
            int j0 = Math.min(ncy - 1, Math.max(0, (int) Math.floor(v)));
            int j1 = Math.min(ncy - 1, j0 + 1);
            float t = Math.min(1f, Math.max(0f, v - j0));
            for (int i = 0; i < ncx; i++) out[i] = map[j0 * ncx + i] + t * (map[j1 * ncx + i] - map[j0 * ncx + i]);
        }

        float cellAt(float[] row, int x) {
            float u = (x + 0.5f) / CELL - 0.5f;
            int i0 = Math.min(ncx - 1, Math.max(0, (int) Math.floor(u)));
            int i1 = Math.min(ncx - 1, i0 + 1);
            float t = Math.min(1f, Math.max(0f, u - i0));
            return row[i0] + t * (row[i1] - row[i0]);
        }

        boolean isStar(int x, int y) {
            return (foot[y * wpr + (x >>> 6)] & (1L << (x & 63))) != 0;
        }
    }

    /** One worker's tile: its core [x0, x1) x [y0, y1) and the buffer around it, bx0, by0, bw x bh (crop coordinates). */
    private static final class Work {
        final Job j;
        final int side, cap;
        /** Luminance; filled-in (starless) luminance; the fill's sums; its three smooths; matched filter; scratch. */
        final float[] Y, BG, CV, CW, C1, C2, C3, MF, T;
        /** Star pixels. */
        final boolean[] F;
        /** The crop's column and row of each buffer column and row (mirrored outside it). */
        final int[] xs, ys;
        int x0, y0, x1, y1, bx0, by0, bw, bh;

        Work(Job j) {
            this.j = j;
            side = j.tile + 2 * (R_LAYERS + R_FILL);
            cap = side * side;
            Y = new float[cap];
            BG = new float[cap];
            CV = new float[cap];
            CW = new float[cap];
            C1 = new float[cap];
            C2 = new float[cap];
            C3 = new float[cap];
            MF = new float[cap];
            T = new float[cap];
            F = new boolean[cap];
            xs = new int[side];
            ys = new int[side];
        }

        void at(int tx, int ty, int margin) {
            x0 = tx * j.tile;
            y0 = ty * j.tile;
            x1 = Math.min(j.cw, x0 + j.tile);
            y1 = Math.min(j.ch, y0 + j.tile);
            bx0 = x0 - margin;
            by0 = y0 - margin;
            bw = x1 - x0 + 2 * margin;
            bh = y1 - y0 + 2 * margin;
            for (int x = 0; x < bw; x++) xs[x] = mirror(bx0 + x, j.cw);
            for (int y = 0; y < bh; y++) ys[y] = mirror(by0 + y, j.ch);
        }

        /** The luminance over the buffer (the picture mirrored at its edges), and with {@code stars} which of its pixels are stars. */
        void load(boolean stars) {
            RgbImage im = j.img;
            float[] r = im.r, g = im.g, b = im.b;
            float yr = j.yr, yg = j.yg, yb = j.yb;
            for (int y = 0; y < bh; y++) {
                int src = (j.a.cy0 + ys[y]) * im.width + j.a.cx0;
                int o = y * bw;
                int fo = ys[y] * j.wpr;
                long[] foot = j.foot;
                for (int x = 0; x < bw; x++) {
                    int i = src + xs[x];
                    Y[o + x] = yr * r[i] + yg * g[i] + yb * b[i];
                }
                if (!stars) continue;
                for (int x = 0; x < bw; x++) {
                    int cx = xs[x];
                    F[o + x] = (foot[fo + (cx >>> 6)] & (1L << (cx & 63))) != 0;
                }
            }
        }

        /** C1, C2, C3: the B3-spline smooths of src at steps 1, 2 and 4 (valid R_LAYERS in from the buffer's edge). */
        void layers(float[] src) {
            b3(src, C1, 1);
            b3(C1, C2, 2);
            b3(C2, C3, 4);
        }

        /** The starless luminance: star pixels filled in from the sky around them (valid R_FILL in from the buffer's edge). */
        void fill() {
            int n = bw * bh;
            for (int i = 0; i < n; i++) {
                boolean f = F[i];
                CV[i] = f ? 0f : Y[i];
                CW[i] = f ? 0f : 1f;
                BG[i] = f ? Float.NaN : Y[i];
            }
            for (int l = 0; l < 3; l++) {
                b3(CV, CV, 1 << l);
                b3(CW, CW, 1 << l);
                for (int i = 0; i < n; i++) {
                    float wt = CW[i];
                    if (BG[i] != BG[i] && wt >= FILL_WEIGHT) BG[i] = CV[i] / wt;
                }
            }
            for (int y = 0; y < bh; y++) {
                int o = y * bw;
                for (int x = 0; x < bw; x++) if (BG[o + x] != BG[o + x]) BG[o + x] = j.fill4At(xs[x], ys[y]);
            }
        }

        /**
         * Marks the core's star pixels (a disc around every pixel of the core and of R_DISC around it whose matched
         * filter, over the 8 px smooth, is {@link #DETECT} times the noise: {@code invNoise} is 1 / that noise), then
         * adds up its blocks' sky. Returns how many star pixels the core has.
         */
        long stamp(float invNoise) {
            int tw = x1 - x0, th = y1 - y0;
            boolean[] star = new boolean[tw * th];
            int ya = Math.max(0, y0 - R_DISC), yb = Math.min(j.ch, y1 + R_DISC);
            int xa = Math.max(0, x0 - R_DISC), xb = Math.min(j.cw, x1 + R_DISC);
            for (int y = ya; y < yb; y++) {
                int o = (y - by0) * bw - bx0;
                for (int x = xa; x < xb; x++) {
                    int i = o + x;
                    float s = (MF[i] - C3[i]) * invNoise;
                    if (!(s > DETECT)) continue;
                    // out to where a star this bright falls to half the noise
                    float r = Math.min(R_DISC, 1f + j.psf * (float) Math.sqrt(2 * Math.log(Math.max(1.0, 2 * s / j.gain))));
                    int ri = (int) r;
                    float r2 = r * r;
                    for (int dy = -ri; dy <= ri; dy++) {
                        int yy = y + dy;
                        if (yy < y0 || yy >= y1) continue;
                        int so = (yy - y0) * tw - x0;
                        for (int dx = -ri; dx <= ri; dx++) {
                            int xx = x + dx;
                            if (xx < x0 || xx >= x1 || dx * dx + dy * dy > r2) continue;
                            star[so + xx] = true;
                        }
                    }
                }
            }
            long n = 0;
            long[] foot = j.foot;
            for (int y = y0; y < y1; y++) {
                int so = (y - y0) * tw - x0;
                for (int wd = x0 >>> 6; wd < (x1 + 63) >>> 6; wd++) {
                    long bits = 0;
                    for (int x = wd << 6, xe = Math.min(x1, (wd + 1) << 6); x < xe; x++) {
                        if (star[so + x]) {
                            bits |= 1L << (x & 63);
                            n++;
                        }
                    }
                    foot[y * j.wpr + wd] = bits;
                }
            }
            // the blocks' sky (a tile holds whole blocks: tiles are a multiple of 4)
            for (int by = y0 >> 2; by < (y1 + 3) >> 2; by++) {
                for (int bx = x0 >> 2; bx < (x1 + 3) >> 2; bx++) {
                    float s = 0, c = 0;
                    for (int y = by * 4, ye = Math.min(y1, by * 4 + 4); y < ye; y++) {
                        int o = (y - by0) * bw - bx0, so = (y - y0) * tw - x0;
                        for (int x = bx * 4, xe = Math.min(x1, bx * 4 + 4); x < xe; x++) {
                            if (star[so + x]) continue;
                            s += Y[o + x];
                            c++;
                        }
                    }
                    j.bSum[by * j.fw + bx] = s;
                    j.bCnt[by * j.fw + bx] = c;
                }
            }
            return n;
        }

        /** B3-spline smoothing with taps step apart over the buffer (src may be dst); the edges are clamped. */
        void b3(float[] src, float[] dst, int step) {
            int s1 = step, s2 = 2 * step;
            for (int y = 0; y < bh; y++) {
                int o = y * bw;
                int ia = Math.min(bw, s2), ib = Math.max(ia, bw - s2);
                for (int x = 0; x < ia; x++) T[o + x] = b3At(src, o, x, s1, s2, bw);
                for (int x = ia; x < ib; x++) {
                    int i = o + x;
                    T[i] = (6f * src[i] + 4f * (src[i - s1] + src[i + s1]) + src[i - s2] + src[i + s2]) * (1f / 16f);
                }
                for (int x = ib; x < bw; x++) T[o + x] = b3At(src, o, x, s1, s2, bw);
            }
            for (int y = 0; y < bh; y++) {
                int o = y * bw;
                int ya = Math.max(0, y - s2) * bw, yb = Math.max(0, y - s1) * bw, yc = Math.min(bh - 1, y + s1) * bw,
                        yd = Math.min(bh - 1, y + s2) * bw;
                for (int x = 0; x < bw; x++) dst[o + x] = (6f * T[o + x] + 4f * (T[yb + x] + T[yc + x]) + T[ya + x] + T[yd + x]) * (1f / 16f);
            }
        }

        private static float b3At(float[] s, int o, int x, int s1, int s2, int w) {
            return (6f * s[o + x] + 4f * (s[o + Math.max(0, x - s1)] + s[o + Math.min(w - 1, x + s1)]) + s[o + Math.max(0, x - s2)]
                    + s[o + Math.min(w - 1, x + s2)]) * (1f / 16f);
        }

        /** The matched filter of src over the buffer; the edges are clamped. */
        void matched(float[] src, float[] dst) {
            float[] k = j.mfK;
            int r = j.mfR;
            for (int y = 0; y < bh; y++) {
                int o = y * bw;
                for (int x = 0; x < bw; x++) {
                    float acc = 0;
                    if (x >= r && x < bw - r) {
                        for (int t = -r; t <= r; t++) acc += k[t + r] * src[o + x + t];
                    } else {
                        for (int t = -r; t <= r; t++) acc += k[t + r] * src[o + Math.max(0, Math.min(bw - 1, x + t))];
                    }
                    T[o + x] = acc;
                }
            }
            for (int y = 0; y < bh; y++) {
                int o = y * bw;
                for (int x = 0; x < bw; x++) {
                    float acc = 0;
                    for (int t = -r; t <= r; t++) acc += k[t + r] * T[Math.max(0, Math.min(bh - 1, y + t)) * bw + x];
                    dst[o + x] = acc;
                }
            }
        }
    }

    /** The non-negative garrote: w (1 - t^2 / w^2) above the threshold t, nothing below it. */
    static float garrote(float w, float t) {
        float w2 = w * w, t2 = t * t;
        return w2 > t2 ? w - t2 / w : 0f;
    }

    /**
     * Each cell's noise relative to the whole picture's ({@code whole}, in the cells' units); cells with too little sky
     * between the stars take it from the cells around them, a ring at a time; then a 3 x 3 binomial smoothing, kept
     * within 0.5 to 4.
     */
    static float[] noiseMap(float[] cells, int nx, int ny, float whole) {
        int n = nx * ny;
        float[] m = new float[n];
        boolean[] ok = new boolean[n];
        int okCount = 0;
        for (int c = 0; c < n; c++) {
            float v = cells[c] / whole;
            if (!(v > 0) || Float.isInfinite(v)) continue;
            m[c] = v;
            ok[c] = true;
            okCount++;
        }
        if (okCount == 0) {
            java.util.Arrays.fill(m, 1f);
            return m;
        }
        while (okCount < n) {
            boolean[] was = ok.clone();
            for (int cy = 0; cy < ny; cy++) {
                for (int cx = 0; cx < nx; cx++) {
                    int c = cy * nx + cx;
                    if (was[c]) continue;
                    float s = 0;
                    int k = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int x = cx + dx, y = cy + dy;
                            if (x < 0 || y < 0 || x >= nx || y >= ny || !was[y * nx + x]) continue;
                            s += m[y * nx + x];
                            k++;
                        }
                    }
                    if (k == 0) continue;
                    m[c] = s / k;
                    ok[c] = true;
                    okCount++;
                }
            }
        }
        float[] s = new float[n];
        for (int cy = 0; cy < ny; cy++) {
            for (int cx = 0; cx < nx; cx++) {
                float acc = 0, ws = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int x = cx + dx, y = cy + dy;
                        if (x < 0 || y < 0 || x >= nx || y >= ny) continue;
                        float wt = (dx == 0 ? 2 : 1) * (dy == 0 ? 2 : 1);
                        acc += wt * m[y * nx + x];
                        ws += wt;
                    }
                }
                s[cy * nx + cx] = Math.min(4f, Math.max(0.5f, acc / ws));
            }
        }
        return s;
    }

    /** B3-spline smoothing of a w x h grid with taps step apart, mirrored at its edges (src may be dst). */
    static void b3Grid(final float[] src, final float[] dst, final float[] tmp, final int w, final int h, final int step) {
        Parallel.forRange(h, Math.max(1, h / Parallel.threads()), new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                for (int y = y0; y < y1; y++) {
                    int o = y * w;
                    for (int x = 0; x < w; x++) {
                        tmp[o + x] = (6f * src[o + x] + 4f * (src[o + mirror(x - step, w)] + src[o + mirror(x + step, w)])
                                + src[o + mirror(x - 2 * step, w)] + src[o + mirror(x + 2 * step, w)]) * (1f / 16f);
                    }
                }
            }
        });
        Parallel.forRange(h, Math.max(1, h / Parallel.threads()), new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                for (int y = y0; y < y1; y++) {
                    int a1 = mirror(y - step, h) * w, b1 = mirror(y + step, h) * w, a2 = mirror(y - 2 * step, h) * w,
                            b2 = mirror(y + 2 * step, h) * w;
                    int o = y * w;
                    for (int x = 0; x < w; x++) {
                        dst[o + x] = (6f * tmp[o + x] + 4f * (tmp[a1 + x] + tmp[b1 + x]) + tmp[a2 + x] + tmp[b2 + x]) * (1f / 16f);
                    }
                }
            }
        });
    }

    /** Whole-sample mirror of an index into [0, n). */
    static int mirror(int i, int n) {
        if (n == 1) return 0;
        while (i < 0 || i >= n) i = i < 0 ? -i : 2 * (n - 1) - i;
        return i;
    }

    private static float smooth(float x, float a, float b) {
        float t = (x - a) / (b - a);
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }

    private static int bin(float v) {
        int b = (int) (v * HB);
        return b < NB ? b : NB - 1;
    }

    private static void add(int[] into, int[] h) {
        synchronized (into) {
            for (int k = 0; k < h.length; k++) into[k] += h[k];
        }
    }

    /** Median of a histogram's counts (in units), interpolated within its bin; 0 if it's empty. */
    private static float median(int[] h, int off) {
        long total = 0;
        for (int k = 0; k < NB; k++) total += h[off + k];
        if (total == 0) return 0f;
        double half = total / 2.0, acc = 0;
        for (int k = 0; k < NB; k++) {
            int c = h[off + k];
            if (acc + c >= half) return (float) ((k + (c > 0 ? (half - acc) / c : 0)) / HB);
            acc += c;
        }
        return HU;
    }

    static float[] gauss(int r, float sigma) {
        float[] k = new float[2 * r + 1];
        float s = 0;
        for (int i = -r; i <= r; i++) {
            k[i + r] = (float) Math.exp(-i * i / (2.0 * sigma * sigma));
            s += k[i + r];
        }
        for (int i = 0; i < k.length; i++) k[i] /= s;
        return k;
    }

    private static float min(float[] v) {
        float m = Float.MAX_VALUE;
        for (float x : v) m = Math.min(m, x);
        return m;
    }

    private static float max(float[] v) {
        float m = -Float.MAX_VALUE;
        for (float x : v) m = Math.max(m, x);
        return m;
    }
}
