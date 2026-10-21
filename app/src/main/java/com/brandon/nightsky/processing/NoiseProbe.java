package com.brandon.nightsky.processing;

import java.util.Arrays;

/**
 * How much the stack really gained over one frame, measured on the stack itself: the noise of the stack, and
 * of one frame put through the same normalisation and interpolation, both on the sky background (stars masked)
 * in blocks of {@link #BIN} x {@link #BIN} pixels of the green channel. Nothing about the noise is assumed.
 * The stack's own noise is found by building it a second time with every other frame's sample turned upside
 * down about the stack (frames of even position +1, odd -1): the sky, the stars and every gradient cancel, and
 * what is left is a fresh copy of the stack's noise, through exactly the stack's estimator (its weights, the
 * blend of drizzled and interpolated samples, the rejected samples left out). And the reference frame minus
 * the interpolated mean leaves (1 - c) times its noise less the other frames' mean, where c is its share of
 * the mean's weight; with that mean's own noise from its sign-flipped copy, the frame's noise follows
 * whatever the weights (see {@link #result}). The effective number of frames is then
 * N_eff = (sigma_frame / sigma_stack)^2: what the stack is worth in frames like the reference, after rejected
 * samples, uneven weights, clouded regions, a brightening sky and the drizzle have taken their share.
 * <p>
 * The stacker feeds it every green sample it adds up ({@link #add}); when a band of rows is finished it hands
 * over its sums, and the band is turned into block sums ({@link #endBand}). Blocks of 32 pixels (16 in an
 * image under 3000 pixels wide, so the same patch of sky) hold hundreds of green photosites of every frame,
 * so the smoothing of the interpolation (a pixel or so) hardly couples neighbouring blocks, and a frame's
 * block mean doesn't depend on how its samples fell between the pixels. The spreads are clipped standard
 * deviations ({@link #sigma}): with some 12000 blocks they are good to under 1%, so the effective number of
 * frames is good to about 2%.
 */
public final class NoiseProbe {
    /** Block size (pixels) for a full-size image; half that below {@link #SMALL_WIDTH}. */
    public static final int BIN = 32;
    static final int SMALL_WIDTH = 3000;
    /** Share of a block's pixels that must be measured (covered by both halves, not near a star). */
    static final float MIN_COVER = 0.5f;
    /** Blocks needed for a measurement. */
    static final int MIN_BLOCKS = 50;

    /** The measurement: noise per block (green, normalised frame units) and the effective number of frames. */
    public static final class Result {
        public double sigmaFrame = Double.NaN, sigmaStack = Double.NaN, nEff = Double.NaN;
        /** Blocks the noise was measured in. */
        public int blocks;
        /** Frames in the even and odd halves, by weight. */
        public double nEven, nOdd;
        /** Per block (row by row, {@link #blocksX} across): the stack's sign-flipped noise copy and the single
         * frame less the stack; NaN where not measured. For tests. */
        public float[] stackBlocks, singleBlocks;
        public int blocksX, blocksY;
        /** Block size (pixels). */
        public int bin;
        /** The measured frame's mean share of the interpolated mean's weight. */
        public double singleShare = Double.NaN;
    }

    /** Block size (pixels) for an image {@code width} pixels wide. */
    public static int binFor(int width) {
        return width >= SMALL_WIDTH ? BIN : BIN / 2;
    }

    private final int W, H, bin, bx, by;
    /** Plan index of the frame measured alone (the reference frame), or -1. */
    final int single;
    // per band, per pixel (green): the sign-flipped sums of the interpolated (value x weight, weight, and the
    // weight unsigned) and of the drizzled samples, and the single frame's interpolated value (NaN where it has
    // none) and weight in the stack (0 where the stack left it out)
    private float[] pIV, pIW, pIA, pDV, pDW, pDA, vS, wS;
    private int bandRows;
    // per block: sums of the stack's noise copy, of the interpolated mean's alone, of (single - stack) and of the
    // single frame's share of the interpolated mean's weight, and counts
    private final double[] dSum, iSum, rSum, cSum;
    private final int[] dN, rN;
    private final double[] planWeight;
    /** Pixels near stars (bit per pixel), left out; null for none. */
    private long[] starMask;

    /**
     * @param planWeights each plan's overall weight (frame weight x share of the frame used), for the halves
     */
    public NoiseProbe(int width, int height, int singlePlan, double[] planWeights) {
        W = width;
        H = height;
        bin = binFor(width);
        bx = W / bin;
        by = H / bin;
        single = singlePlan;
        dSum = new double[bx * by];
        iSum = new double[bx * by];
        rSum = new double[bx * by];
        cSum = new double[bx * by];
        dN = new int[bx * by];
        rN = new int[bx * by];
        planWeight = planWeights.clone();
    }

    /** Leaves out the pixels of {@link #starMask}. */
    public void mask(long[] m) {
        starMask = m;
    }

    /**
     * Pixels near the stars of a list (in the stack's coordinates), as a bit per pixel: a disc around each that
     * grows with its brightness, so that its wings and its residue between the halves stay out of the noise.
     */
    public static long[] starMask(StarList s, int W, int H) {
        if (s == null || s.count == 0) return null;
        long[] m = new long[(int) (((long) W * H + 63) >> 6)];
        float[] f = new float[s.count];
        int nf = 0;
        for (int i = 0; i < s.count; i++) if (s.flux[i] > 0) f[nf++] = s.flux[i];
        float faint = nf > 0 ? Stats.quantile(f, nf, 0.1) : 1f;
        for (int i = 0; i < s.count; i++) {
            float h = s.hfr[i] > 0 ? Math.min(s.hfr[i], 6f) : 1.5f;
            double grow = 1 + 0.6 * Math.log10(Math.max(1, s.flux[i] / Math.max(1e-6f, faint)));
            double r = Math.min(40, Math.max(4, 3 * h * grow) * (s.saturated[i] ? 1.6 : 1));
            int x0 = (int) Math.floor(s.x[i] - r), x1 = (int) Math.ceil(s.x[i] + r);
            int y0 = (int) Math.floor(s.y[i] - r), y1 = (int) Math.ceil(s.y[i] + r);
            double r2 = r * r;
            for (int y = Math.max(0, y0); y <= Math.min(H - 1, y1); y++) {
                double dy = y + 0.5 - s.y[i];
                for (int x = Math.max(0, x0); x <= Math.min(W - 1, x1); x++) {
                    double dx = x + 0.5 - s.x[i];
                    if (dx * dx + dy * dy > r2) continue;
                    long p = (long) y * W + x;
                    m[(int) (p >> 6)] |= 1L << (p & 63);
                }
            }
        }
        return m;
    }

    /** Whether pixel (x, y) is near a star in mask m. */
    public static boolean masked(long[] m, int W, int x, int y) {
        if (m == null) return false;
        long p = (long) y * W + x;
        return (m[(int) (p >> 6)] & (1L << (p & 63))) != 0;
    }

    /** Starts over (the stacker calls it at the start of every stack, so a second stack replaces the first). */
    void reset(int rows) {
        Arrays.fill(dSum, 0);
        Arrays.fill(iSum, 0);
        Arrays.fill(rSum, 0);
        Arrays.fill(cSum, 0);
        Arrays.fill(dN, 0);
        Arrays.fill(rN, 0);
        bandRows = rows;
        int n = rows * W;
        if (pIV == null || pIV.length < n) {
            pIV = new float[n];
            pIW = new float[n];
            pIA = new float[n];
            pDA = new float[n];
            pDV = new float[n];
            pDW = new float[n];
            vS = new float[n];
            wS = new float[n];
        }
        clear(n);
    }

    private void clear(int n) {
        Arrays.fill(pIV, 0, n, 0f);
        Arrays.fill(pIW, 0, n, 0f);
        Arrays.fill(pIA, 0, n, 0f);
        Arrays.fill(pDA, 0, n, 0f);
        Arrays.fill(pDV, 0, n, 0f);
        Arrays.fill(pDW, 0, n, 0f);
        Arrays.fill(vS, 0, n, Float.NaN);
        Arrays.fill(wS, 0, n, 0f);
    }

    /**
     * One green sample added to the stack: plan {@code f}, band pixel {@code bp}, its weight and normalised
     * interpolated value, and its drizzle sums (value x drop weight, drop weight) as the stacker adds them.
     * Called from several threads, each for its own rows.
     */
    void add(int f, int bp, float w, float v, float dzv, float dzw) {
        copy(f, bp, w, v, dzv, dzw);
        if (f == single) {
            vS[bp] = v;
            wS[bp] = w;
        }
    }

    /**
     * A green sample the stack rejected although it is within {@link #NOISE_TAIL} of the noise of the others
     * (the tail of the noise, not an outlier): it goes into the noise copy and the single frame's noise all the
     * same. Clipping the tails off the frames' noise changes the stack's noise very little (it averages fewer
     * samples, and a clipped mean is no steadier), but a copy without them would be some 7% quieter than the
     * stack, and the reference frame's own tails would be missing from its noise.
     */
    void addRejected(int f, int bp, float w, float v, float dzv, float dzw) {
        copy(f, bp, w, v, dzv, dzw);
        if (f == single) {
            vS[bp] = v;
            wS[bp] = 0f;
        }
    }

    /** How far from the stack (in units of the sample's noise) a rejected sample still counts as noise. */
    public static final float NOISE_TAIL = 5f;

    private void copy(int f, int bp, float w, float v, float dzv, float dzw) {
        float sw = (f & 1) == 0 ? w : -w;
        pIV[bp] += sw * v;
        pIW[bp] += sw;
        pIA[bp] += w;
        pDV[bp] += sw * dzv;
        pDW[bp] += sw * dzw;
        pDA[bp] += w * dzw;
    }

    /**
     * The band of rows [y0, y1) is complete, with the stacker's sums for it (per band pixel and channel, as
     * its finishing step gets them: drizzle values and weights s2, s3, interpolated values and weights s4, s5,
     * and the count of frames per pixel): into the block sums, and clear for the next band.
     */
    void endBand(int y0, int y1, float[] s2, float[] s3, float[] s4, float[] s5, short[] icnt) {
        for (int j = 0; j < y1 - y0; j++) {
            int y = y0 + j;
            int byi = y / bin;
            if (byi >= by) break;
            for (int x = 0; x < bx * bin; x++) {
                int bp = j * W + x;
                int k = bp * 3 + 1;
                float wI = s5[k];
                if (!(wI > 0) || masked(starMask, W, x, y)) continue;
                float iMean = s4[k] / wI, aI = pIA[bp];
                if (!(aI > 0)) continue;
                // the sign-flipped copy about the stack: signal gone, noise as the stack's (the halves' weight
                // imbalance s takes 1 - s^2 of the variance; put it back)
                float bI = pIW[bp] / aI;
                double nI = (pIV[bp] - pIW[bp] * iMean) / aI / Math.sqrt(Math.max(0.25, 1 - bI * bI));
                double n = nI;
                float wD = s3[k], aD = pDA[bp];
                if (wD > 0 && aD > 0) {
                    // the stack's own blend of the drizzled and the interpolated mean (SharpStacker.finish)
                    float a = Math.min(1f, wD / wI * icnt[bp] * 0.5f);
                    float dMean = s2[k] / wD, bD = pDW[bp] / aD;
                    double nD = (pDV[bp] - pDW[bp] * dMean) / aD / Math.sqrt(Math.max(0.25, 1 - bD * bD));
                    n = (1 - a) * nI + a * nD;
                }
                int b = byi * bx + x / bin;
                dSum[b] += n;
                iSum[b] += nI;
                dN[b]++;
                float sv = vS[bp];
                if (!Float.isNaN(sv)) {
                    rSum[b] += sv - iMean;
                    cSum[b] += wS[bp] / wI;
                    rN[b]++;
                }
            }
        }
        clear(Math.min(bandRows, y1 - y0) * W);
    }

    /**
     * The noise of the stack and of the single frame, from the blocks measured over at least
     * {@link #MIN_COVER} of their pixels.
     */
    public Result result() {
        Result r = new Result();
        int need = Math.round(bin * bin * MIN_COVER);
        float[] d = new float[dSum.length], di = new float[iSum.length], s = new float[rSum.length];
        double cs = 0;
        long cn = 0;
        r.blocksX = bx;
        r.blocksY = by;
        r.bin = bin;
        r.stackBlocks = new float[dSum.length];
        r.singleBlocks = new float[dSum.length];
        Arrays.fill(r.stackBlocks, Float.NaN);
        Arrays.fill(r.singleBlocks, Float.NaN);
        int nd = 0, ns = 0;
        for (int b = 0; b < dSum.length; b++) {
            if (dN[b] < need) continue;
            di[nd] = (float) (iSum[b] / dN[b]);
            d[nd++] = r.stackBlocks[b] = (float) (dSum[b] / dN[b]);
            if (rN[b] >= need) {
                s[ns++] = r.singleBlocks[b] = (float) (rSum[b] / rN[b]);
                cs += cSum[b];
                cn += rN[b];
            }
        }
        r.blocks = nd;
        for (int f = 0; f < planWeight.length; f++) {
            if ((f & 1) == 0) r.nEven += planWeight[f];
            else r.nOdd += planWeight[f];
        }
        if (nd < MIN_BLOCKS) return r;
        r.sigmaStack = sigma(d, nd);
        if (ns >= MIN_BLOCKS) {
            // the single frame minus the interpolated mean it is part of, with c its share of the mean's weight:
            // (1 - c) (n - m), m the other frames' mean, of variance (1 - c)^2 (V + Vm); the mean's own variance
            // is c^2 V + (1 - c)^2 Vm; so V = (var(single - mean) - var(mean)) / (1 - 2c)
            double sr = sigma(s, ns), si = sigma(di, nd), c = cn > 0 ? cs / cn : 0;
            r.singleShare = c;
            double v = c < 0.4 ? (sr * sr - si * si) / (1 - 2 * c) : Double.NaN;
            if (v > 0) {
                r.sigmaFrame = Math.sqrt(v);
                r.nEff = v / (r.sigmaStack * r.sigmaStack);
            }
        }
        return r;
    }

    /** Standard deviation from the median absolute deviation (about the median): stars and seams don't count. */
    public static double robustSigma(float[] v, int n) {
        float med = Stats.median(Arrays.copyOf(v, n), n);
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = Math.abs(v[i] - med);
        return 1.4826 * Stats.median(a, n);
    }

    /** Clipping limit of {@link #sigma}, in standard deviations, and the share of a normal variance kept inside it. */
    static final double CLIP = 3.5, CLIP_VAR = 0.99389;

    /**
     * Standard deviation of the values within {@link #CLIP} of their mean (twice over, starting from the median
     * and its absolute deviation), scaled up for what the clipping takes from normal noise: as robust to a few
     * stray blocks (a star's wing, a seam) as the median absolute deviation, and nearly as precise as a plain
     * standard deviation (the median absolute deviation needs 2.7 times the blocks for the same precision).
     */
    public static double sigma(float[] v, int n) {
        if (n < 2) return Double.NaN;
        double c = Stats.median(Arrays.copyOf(v, n), n), s = robustSigma(v, n);
        for (int it = 0; it < 3 && s > 0; it++) {
            double lo = c - CLIP * s, hi = c + CLIP * s, sum = 0, sq = 0;
            int k = 0;
            for (int i = 0; i < n; i++) {
                if (v[i] < lo || v[i] > hi) continue;
                sum += v[i];
                sq += (double) v[i] * v[i];
                k++;
            }
            if (k < 2) break;
            c = sum / k;
            s = Math.sqrt(Math.max(0, (sq - sum * c) / (k - 1)) / CLIP_VAR);
        }
        return s;
    }
}
