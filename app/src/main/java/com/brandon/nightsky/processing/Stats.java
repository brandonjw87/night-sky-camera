package com.brandon.nightsky.processing;

/** Small robust-statistics helpers. Methods that take an array may reorder it. */
public final class Stats {
    private Stats() {}

    /** Median of the first n values. Reorders the array. */
    public static float median(float[] a, int n) {
        if (n <= 0) return 0f;
        int k = n >>> 1;
        float m = select(a, 0, n - 1, k);
        if ((n & 1) == 1) return m;
        float lower = a[0];
        for (int i = 1; i < k; i++) if (a[i] > lower) lower = a[i];
        return 0.5f * (m + lower);
    }

    public static double median(double[] a, int n) {
        if (n <= 0) return 0;
        int k = n >>> 1;
        double m = select(a, 0, n - 1, k);
        if ((n & 1) == 1) return m;
        double lower = a[0];
        for (int i = 1; i < k; i++) if (a[i] > lower) lower = a[i];
        return 0.5 * (m + lower);
    }

    /** Value at rank q (0..1) of the first n values. Reorders the array. */
    public static float quantile(float[] a, int n, double q) {
        if (n <= 0) return 0f;
        int k = (int) Math.round(q * (n - 1));
        k = Math.max(0, Math.min(n - 1, k));
        return select(a, 0, n - 1, k);
    }

    /**
     * Weighted median of the first n values: where the cumulative weight, in order of value, reaches half.
     * Weights must be &gt;= 0; with no weight at all it is the plain median. Leaves both arrays as they were.
     *
     * @param scratch at least n long, or null to allocate
     */
    public static float weightedMedian(float[] v, float[] w, int n, long[] scratch) {
        if (n <= 0) return 0f;
        long[] key = scratch != null && scratch.length >= n ? scratch : new long[n];
        double total = 0;
        for (int i = 0; i < n; i++) {
            // float bits made to sort like the floats themselves, with the index in the low half
            int b = Float.floatToIntBits(v[i]);
            b ^= (b >> 31) & 0x7FFFFFFF;
            key[i] = ((long) b << 32) | i;
            total += w[i];
        }
        java.util.Arrays.sort(key, 0, n);
        if (!(total > 0)) {
            int lo = (int) key[(n - 1) >>> 1], hi = (int) key[n >>> 1];
            return 0.5f * (v[lo] + v[hi]);
        }
        double half = 0.5 * total, acc = 0;
        for (int j = 0; j < n; j++) {
            int i = (int) key[j];
            acc += w[i];
            if (acc >= half) {
                // exactly half: halfway to the next value that carries weight, as the plain median does for an
                // even count
                if (acc == half) {
                    for (int q = j + 1; q < n; q++) {
                        int k = (int) key[q];
                        if (w[k] > 0) return 0.5f * (v[i] + v[k]);
                    }
                }
                return v[i];
            }
        }
        return v[(int) key[n - 1]];
    }

    /** Median absolute deviation around {@code center}, scaled to a Gaussian sigma. Reorders the array. */
    public static float madSigma(float[] a, int n, float center) {
        for (int i = 0; i < n; i++) a[i] = Math.abs(a[i] - center);
        return 1.4826f * median(a, n);
    }

    public static float select(float[] a, int lo, int hi, int k) {
        while (lo < hi) {
            float pivot = a[(lo + hi) >>> 1];
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) {
                    float t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else break;
        }
        return a[k];
    }

    public static double select(double[] a, int lo, int hi, int k) {
        while (lo < hi) {
            double pivot = a[(lo + hi) >>> 1];
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) {
                    double t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else break;
        }
        return a[k];
    }

    /** Median of 8 values (average of the 4th and 5th smallest) without allocation. */
    public static float median8(float a0, float a1, float a2, float a3, float a4, float a5, float a6, float a7, float[] scratch) {
        scratch[0] = a0; scratch[1] = a1; scratch[2] = a2; scratch[3] = a3;
        scratch[4] = a4; scratch[5] = a5; scratch[6] = a6; scratch[7] = a7;
        // insertion sort, 8 elements
        for (int i = 1; i < 8; i++) {
            float v = scratch[i];
            int j = i - 1;
            while (j >= 0 && scratch[j] > v) {
                scratch[j + 1] = scratch[j];
                j--;
            }
            scratch[j + 1] = v;
        }
        return 0.5f * (scratch[3] + scratch[4]);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
