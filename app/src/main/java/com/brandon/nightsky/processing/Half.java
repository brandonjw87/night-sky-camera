package com.brandon.nightsky.processing;

/**
 * IEEE 754 half-precision (16-bit) floats, for keeping every frame's sample of a band of pixels in memory
 * while the outlier rejection decides which ones to keep. About three significant digits, which is far
 * finer than the noise of a single frame.
 */
public final class Half {
    private Half() {}

    /** Markers for "no sample" and "sample rejected". Both are NaNs, which {@link #fromFloat} never makes from data. */
    public static final short MISSING = (short) 0xFFFF, REJECTED = (short) 0xFFFE;

    private static final float[] TABLE = new float[65536];

    static {
        for (int h = 0; h < 65536; h++) TABLE[h] = decode(h);
    }

    public static float toFloat(short h) {
        return TABLE[h & 0xFFFF];
    }

    /** True for {@link #MISSING} and {@link #REJECTED} (any NaN). */
    public static boolean isMarker(short h) {
        return (h & 0x7C00) == 0x7C00 && (h & 0x03FF) != 0;
    }

    private static float decode(int h) {
        int sign = (h & 0x8000) << 16;
        int e = (h >>> 10) & 0x1F;
        int m = h & 0x3FF;
        if (e == 0) {
            float v = m * 5.9604645e-8f; // m x 2^-24, exact
            return sign != 0 ? -v : v;
        }
        if (e == 31) return Float.intBitsToFloat(sign | 0x7F800000 | (m << 13));
        return Float.intBitsToFloat(sign | ((e + 112) << 23) | (m << 13));
    }

    /** Nearest half (ties to even); beyond the range it gives infinity, NaN stays NaN. */
    public static short fromFloat(float f) {
        int x = Float.floatToRawIntBits(f);
        int sign = (x >>> 16) & 0x8000;
        int ax = x & 0x7FFFFFFF;
        if (ax >= 0x7F800000) return (short) (sign | 0x7C00 | (ax > 0x7F800000 ? 0x200 : 0));
        if (ax >= 0x477FF000) return (short) (sign | 0x7C00); // 65520 and up round to infinity
        if (ax < 0x38800000) {
            // subnormal half (or zero)
            if (ax < 0x33000000) return (short) sign;
            int e = ax >>> 23;
            int m = (ax & 0x7FFFFF) | 0x800000;
            int shift = 126 - e;
            int mh = m >>> shift;
            int rem = m & ((1 << shift) - 1);
            int half = 1 << (shift - 1);
            if (rem > half || (rem == half && (mh & 1) != 0)) mh++;
            return (short) (sign | mh);
        }
        int r = ax - 0x38000000; // exponent rebias 127 -> 15
        int mh = r >>> 13;
        int rem = r & 0x1FFF;
        if (rem > 0x1000 || (rem == 0x1000 && (mh & 1) != 0)) mh++;
        return (short) (sign | mh);
    }
}
