package com.brandon.nightsky.processing;

import java.util.Arrays;

/** Detected stars, as parallel arrays, positions in RAW continuous pixel coordinates. */
public final class StarList {
    public int count;
    public float[] x, y, flux, hfr, elong;
    public boolean[] saturated;
    /** Detection diagnostics: background and noise of the half-res green image (ADU). */
    public float background, noise;

    public StarList(int capacity) {
        capacity = Math.max(capacity, 4);
        x = new float[capacity];
        y = new float[capacity];
        flux = new float[capacity];
        hfr = new float[capacity];
        elong = new float[capacity];
        saturated = new boolean[capacity];
    }

    public void add(float sx, float sy, float sflux, float shfr, float selong, boolean ssat) {
        if (count == x.length) {
            int n = count * 2;
            x = Arrays.copyOf(x, n);
            y = Arrays.copyOf(y, n);
            flux = Arrays.copyOf(flux, n);
            hfr = Arrays.copyOf(hfr, n);
            elong = Arrays.copyOf(elong, n);
            saturated = Arrays.copyOf(saturated, n);
        }
        x[count] = sx;
        y[count] = sy;
        flux[count] = sflux;
        hfr[count] = shfr;
        elong[count] = selong;
        saturated[count] = ssat;
        count++;
    }

    /** Sorts by flux, brightest first, and keeps at most {@code max} entries. */
    public void sortAndTrim(int max) {
        long[] keys = new long[count];
        for (int i = 0; i < count; i++) {
            float f = Math.max(flux[i], 0f);
            keys[i] = ((long) Float.floatToIntBits(f) << 32) | (0xFFFFFFFFL & i);
        }
        Arrays.sort(keys);
        int n = Math.min(max, count);
        StarList t = new StarList(n);
        for (int k = 0; k < n; k++) {
            int i = (int) (keys[count - 1 - k] & 0xFFFFFFFFL);
            t.add(x[i], y[i], flux[i], hfr[i], elong[i], saturated[i]);
        }
        x = t.x;
        y = t.y;
        flux = t.flux;
        hfr = t.hfr;
        elong = t.elong;
        saturated = t.saturated;
        count = t.count;
    }

    /** Median half-flux radius (raw px) of up to {@code maxStars} brightest unsaturated stars. */
    public float medianHfr(int maxStars) {
        float[] v = new float[Math.min(count, maxStars)];
        int n = 0;
        for (int i = 0; i < count && n < v.length; i++) {
            if (!saturated[i]) v[n++] = hfr[i];
        }
        return n == 0 ? 0f : Stats.median(v, n);
    }
}
