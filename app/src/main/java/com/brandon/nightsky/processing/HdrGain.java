package com.brandon.nightsky.processing;

/**
 * How much brighter than white each part of a finished photo may be shown on a screen that has the headroom (2.0): the
 * photo's gain map (see {@link UltraHdr}). A night photo's range is the stars': on an ordinary screen every star from
 * middling to brilliant ends at the same white, and the sky can't go darker to make room. A phone's HDR screen can show
 * white several times brighter than its page white, so the stars are given that: the brighter a pixel of the finished
 * photo, the more it is lifted, up to {@link #MAX_STOPS} at white. The sky, the Milky Way and everything dimmer than
 * {@link #START} stay exactly as they are, so the photo on an ordinary screen and the dark parts on any screen don't
 * change at all.
 *
 * <p>Wide bright areas (the Moon's glare, a lit horizon, a floodlit wall) are held back: a screen-filling patch at four
 * times white is a glare, not a sparkle. What counts as wide is the average level of the surroundings, in cells of
 * {@link #CELL} pixels.
 *
 * <p>The map is made from the photo's own finished pixels, so it fits whatever look, crop and edit the photo was saved
 * with. Two passes: {@link #measure} every row, then {@link #row} for each row.
 */
public final class HdrGain {
    /** The gain at map value 255, in stops: four times white. */
    public static final float MAX_STOPS = 2f;
    /** The display level (0..1) the lift starts from. */
    public static final float START = 0.42f;
    /** The lift's shape above START: a smooth step, raised to this (keeps the lower half gentle). */
    static final float POWER = 1.4f;
    /** A side of the cells the surroundings are averaged over, in photo pixels. */
    public static final int CELL = 32;
    /** Surroundings (average display level) from which the lift is held back, and where it's held back most. */
    static final float AROUND_LOW = 0.50f, AROUND_HIGH = 0.85f;
    /** What's left of the lift in the brightest surroundings. */
    static final float AROUND_LEAST = 0.25f;

    /** The lift (0..1 of MAX_STOPS) for each 8-bit display level. */
    private static final float[] LIFT = new float[256];

    static {
        for (int v = 0; v < 256; v++) LIFT[v] = lift(v / 255f);
    }

    /** The lift (0..1 of MAX_STOPS) at display level {@code v} (0..1), before the surroundings hold it back. */
    public static float lift(float v) {
        float t = (v - START) / (1f - START);
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return (float) Math.pow(t * t * (3f - 2f * t), POWER);
    }

    /** What the surroundings leave of the lift: 1 in the dark, {@link #AROUND_LEAST} where they're bright. */
    public static float held(float around) {
        float t = (around - AROUND_LOW) / (AROUND_HIGH - AROUND_LOW);
        if (t <= 0f) return 1f;
        if (t >= 1f) return AROUND_LEAST;
        return 1f - (1f - AROUND_LEAST) * t * t * (3f - 2f * t);
    }

    private final int w, h, gw, gh;
    /** Per cell: the sum of the levels and how many pixels, then (after the first pass) what's left of the lift there. */
    private final float[] cell;
    private final int[] count;
    private boolean measured;
    /** One row's cells blended down the column, for {@link #row}. */
    private final float[] line;
    private long lifted;
    private int most;

    public HdrGain(int w, int h) {
        if (w < 1 || h < 1) throw new IllegalArgumentException("no picture");
        this.w = w;
        this.h = h;
        gw = (w + CELL - 1) / CELL;
        gh = (h + CELL - 1) / CELL;
        cell = new float[gw * gh];
        count = new int[gw * gh];
        line = new float[gw];
    }

    private static int level(int argb) {
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        return r > g ? (r > b ? r : b) : (g > b ? g : b);
    }

    /** First pass: row {@code y} of the photo ({@code w} ARGB pixels from {@code off}). Every row once, in any order. */
    public void measure(int y, int[] argb, int off) {
        if (measured) throw new IllegalStateException("the rows are being written already");
        int base = (y / CELL) * gw;
        for (int gx = 0; gx < gw; gx++) {
            int x0 = gx * CELL, x1 = Math.min(w, x0 + CELL);
            int s = 0;
            for (int x = x0; x < x1; x++) s += level(argb[off + x]);
            cell[base + gx] += s;
            count[base + gx] += x1 - x0;
        }
    }

    private void finishMeasure() {
        for (int i = 0; i < cell.length; i++) {
            cell[i] = count[i] > 0 ? held(cell[i] / (255f * count[i])) : 1f;
        }
        measured = true;
    }

    /**
     * Second pass: the map's row {@code y} from the photo's ({@code w} ARGB pixels from {@code off}), as {@code w}
     * map values 0..255 from {@code outOff}: the lift is value / 255 of {@link #MAX_STOPS}.
     */
    public void row(int y, int[] argb, int off, byte[] out, int outOff) {
        if (!measured) finishMeasure();
        // the cells' rows above and below this one, blended
        float fy = (y + 0.5f) / CELL - 0.5f;
        int j0 = (int) Math.floor(fy);
        float ty = fy - j0;
        int ja = Math.min(gh - 1, Math.max(0, j0)), jb = Math.min(gh - 1, Math.max(0, j0 + 1));
        for (int i = 0; i < gw; i++) line[i] = cell[ja * gw + i] + ty * (cell[jb * gw + i] - cell[ja * gw + i]);
        final float inv = 1f / CELL;
        for (int x = 0; x < w; x++) {
            float l = LIFT[level(argb[off + x])];
            if (l == 0f) {
                out[outOff + x] = 0;
                continue;
            }
            float fx = (x + 0.5f) * inv - 0.5f;
            int i0 = (int) Math.floor(fx);
            float tx = fx - i0;
            int ia = i0 < 0 ? 0 : (i0 >= gw ? gw - 1 : i0), ib = i0 + 1 < 0 ? 0 : (i0 + 1 >= gw ? gw - 1 : i0 + 1);
            float k = line[ia] + tx * (line[ib] - line[ia]);
            int v = (int) (255f * l * k + 0.5f);
            if (v > 255) v = 255;
            out[outOff + x] = (byte) v;
            if (v >= 32) lifted++;
            if (v > most) most = v;
        }
    }

    /** The lift in stops for a map value. */
    public static float stops(int value) {
        return MAX_STOPS * (value & 255) / 255f;
    }

    /** How many times brighter than on an ordinary screen a map value shows white, on a screen with all the headroom. */
    public static float times(int value) {
        return (float) Math.pow(2, stops(value));
    }

    /** After the second pass: how many of the photo's pixels are lifted by a quarter of a stop or more. */
    public long lifted() {
        return lifted;
    }

    /** After the second pass: the largest map value written. */
    public int most() {
        return most;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    /** The numbers a reader needs to turn this map's values into gains. */
    public static UltraHdr.Meta meta() {
        UltraHdr.Meta m = new UltraHdr.Meta();
        m.gainMapMin = 0;
        m.gainMapMax = MAX_STOPS;
        m.gamma = 1;
        m.capacityMin = 0;
        m.capacityMax = MAX_STOPS;
        return m;
    }

    /** One line for the session's log. */
    public String note() {
        double share = 100.0 * lifted / ((double) w * h);
        return String.format(java.util.Locale.US, "%.2f%% of the photo brightened, the brightest %.1f times white", share, times(most));
    }
}
