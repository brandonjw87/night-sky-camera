package com.brandon.nightsky.processing;

/**
 * Frames taken with the lens covered (the camera check), measured: each colour's black level, the noise, hot
 * pixels, how far each row's level wanders (banding); from two frames of the same length, the read noise without the
 * sensor's fixed pattern; and how bright a frame is, to tell whether the lens is covered at all. Raw values, the
 * black level not taken off.
 */
public final class DarkFrames {
    private DarkFrames() {}

    /** A pixel this many noise spreads (and 2% of the range) above its colour's level is hot. */
    public static final double HOT_SIGMA = 8;
    /** Above this share of the range over black, light is reaching the sensor: the lens isn't covered. */
    public static final double LIT_SHARE = 0.02;

    /** One frame. */
    public static final class Frame {
        /** Median of each position in the 2x2 colour pattern (0 top left, 1 top right, 2 bottom left, 3 bottom right). */
        public final double[] level = new double[4];
        /** Robust spread of single pixels about their position's level (ADU): read noise plus the fixed pattern. */
        public double noise;
        /** Robust spread of the rows' levels (ADU): banding. */
        public double rows;
        /** Pixels standing out as hot (see {@link #HOT_SIGMA}). */
        public int hot;
        /** The mean over the level (ADU): what hot and warm pixels add on average. */
        public double excess;
        public long pixels;

        /** The level of all four positions together. */
        public double mean() {
            return (level[0] + level[1] + level[2] + level[3]) / 4;
        }
    }

    /**
     * @param px    the frame, W x H (unsigned 16-bit values)
     * @param white the camera's white level
     * @param black the camera's black level (for the hot pixels' 2% of the range)
     */
    public static Frame measure(short[] px, int W, int H, float white, float black) {
        Frame f = new Frame();
        f.pixels = (long) W * H;
        // each position's level and the spread, from a sample of about 100,000 pixels per position
        int step = Math.max(1, (int) Math.sqrt((double) W * H / 4 / 100_000));
        int per = (W / 2 / step + 1) * (H / 2 / step + 1);
        float[][] s = new float[4][per];
        int[] n = new int[4];
        for (int by = 0; by < H / 2; by += step) {
            for (int bx = 0; bx < W / 2; bx += step) {
                for (int p = 0; p < 4; p++) {
                    int x = 2 * bx + (p & 1), y = 2 * by + (p >> 1);
                    s[p][n[p]++] = px[y * W + x] & 0xFFFF;
                }
            }
        }
        float[] spreads = new float[4];
        for (int p = 0; p < 4; p++) {
            f.level[p] = level(s[p], n[p]);
            spreads[p] = (float) spread(s[p], n[p], f.level[p]);
        }
        f.noise = Stats.median(spreads, 4);
        // hot pixels and the mean, over every pixel
        double thr = Math.max(HOT_SIGMA * Math.max(f.noise, 0.5), LIT_SHARE * (white - black));
        int[] lim = new int[4];
        for (int p = 0; p < 4; p++) lim[p] = (int) Math.ceil(f.level[p] + thr);
        long sum = 0;
        int hot = 0;
        for (int y = 0; y < H; y++) {
            int row = y * W, py = (y & 1) << 1;
            int l0 = lim[py], l1 = lim[py + 1];
            for (int x = 0; x < W; x += 2) {
                int a = px[row + x] & 0xFFFF;
                sum += a;
                if (a > l0) hot++;
                if (x + 1 < W) {
                    int b = px[row + x + 1] & 0xFFFF;
                    sum += b;
                    if (b > l1) hot++;
                }
            }
        }
        f.hot = hot;
        f.excess = (double) sum / f.pixels - f.mean();
        // banding: each row's mean over its position's level (from a sample, hot pixels left out), spread across the
        // rows, less what the pixels' own noise puts in that spread
        int rstep = Math.max(1, W / 512);
        float[] rowLevels = new float[H];
        double perRow = 0;
        for (int y = 0; y < H; y++) {
            int k = 0, py = (y & 1) << 1;
            double rsum = 0;
            for (int x = 0; x < W; x += rstep) {
                int p = py | (x & 1), v = px[y * W + x] & 0xFFFF;
                if (v > lim[p]) continue;
                rsum += v - f.level[p];
                k++;
            }
            rowLevels[y] = k > 0 ? (float) (rsum / k) : 0f;
            perRow += k;
        }
        perRow /= Math.max(1, H);
        double rs = Stats.madSigma(rowLevels, H, Stats.median(rowLevels.clone(), H));
        f.rows = Math.sqrt(Math.max(0, rs * rs - f.noise * f.noise / Math.max(1, perRow)));
        return f;
    }

    /**
     * The read noise from two frames of the same length taken one after the other (ADU): the spread of their
     * difference over the square root of 2, so the fixed pattern and hot pixels, the same in both, cancel.
     */
    public static double pairNoise(short[] a, short[] b, int W, int H) {
        int step = Math.max(1, (int) Math.sqrt((double) W * H / 200_000));
        float[] d = new float[(W / step + 1) * (H / step + 1)];
        int n = 0;
        for (int y = 0; y < H; y += step) {
            for (int x = 0; x < W; x += step) {
                int i = y * W + x;
                d[n++] = (a[i] & 0xFFFF) - (b[i] & 0xFFFF);
            }
        }
        return spread(d, n, level(d, n)) / Math.sqrt(2);
    }

    /**
     * The middle of whole-number values, placed within the middle value's step by how many fall in it (a plain median
     * of whole numbers is off by up to half a level). Reorders the array.
     */
    static double level(float[] v, int n) {
        if (n <= 0) return Double.NaN;
        int mid = (int) Math.floor(Stats.median(v, n));
        final int K = 32;
        int[] hist = new int[2 * K + 1];
        int below = 0;
        for (int i = 0; i < n; i++) {
            int b = (int) Math.floor(v[i]) - mid + K;
            if (b < 0) below++;
            else if (b <= 2 * K) hist[b]++;
        }
        double half = n / 2.0;
        for (int b = 0; b <= 2 * K; b++) {
            if (hist[b] > 0 && below + hist[b] >= half) return mid - K + b - 0.5 + (half - below) / hist[b];
            below += hist[b];
        }
        return mid;
    }

    /**
     * The spread of whole-number values about {@code c}: their standard deviation within 3.5 of it, found in a few
     * rounds from the MAD's (which, of whole numbers, is off by up to a third), so hot pixels are left out.
     */
    static double spread(float[] v, int n, double c) {
        if (n < 2) return Double.NaN;
        float[] copy = new float[n];
        System.arraycopy(v, 0, copy, 0, n);
        double s = Math.max(0.5, Stats.madSigma(copy, n, (float) c));
        for (int round = 0; round < 6; round++) {
            double lim = 3.5 * s, sum = 0;
            int k = 0;
            for (int i = 0; i < n; i++) {
                double d = v[i] - c;
                if (Math.abs(d) <= lim) {
                    sum += d * d;
                    k++;
                }
            }
            if (k < 2) break;
            // a normal spread cut at 3.5 of itself keeps 99.7% of its standard deviation
            double next = Math.sqrt(sum / k) / 0.99694;
            boolean settled = Math.abs(next - s) < 1e-3 * s;
            s = next;
            if (settled) break;
        }
        return s;
    }

    /** How bright a frame is over black, as a share of the range: above {@link #LIT_SHARE}, the lens isn't covered. */
    public static double litShare(Frame f, float white, float black) {
        return (f.mean() - black) / Math.max(1, white - black);
    }
}
