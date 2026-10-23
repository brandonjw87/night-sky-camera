package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Watching the stars' size during long sessions (F7): over half an hour the lens cools with the night and its
 * focus can creep. When the stars grow steadily (at least 12% over the first minutes' size, most of the frames
 * since getting bigger, not just one soft frame) while the sky stays as clear (a thin cloud also swells stars,
 * and dims them), a quick two-frame focus check is due. At most one check every ten minutes; after a check the
 * stars' size then becomes the new baseline.
 */
public final class FocusWatch {
    /** Checks only in sessions this long or longer (seconds). */
    public static final double MIN_SESSION_S = 30 * 60;
    /** The first minutes, and the minutes after a check, that set the baseline (seconds). */
    static final double BASE_S = 8 * 60;
    /** Least time between checks (seconds). */
    static final double REST_S = 10 * 60;
    /** Growth over the baseline that counts as the focus drifting. */
    static final double RISE = 0.12;
    /** The sky must keep this share of the baseline's starlight (else it's haze, not focus). */
    static final double CLEAR = 0.85;
    /** Frames that make the current size. */
    static final int RECENT = 6;

    private static final class F {
        final double t, hfr, tr;

        F(double t, double hfr, double tr) {
            this.t = t;
            this.hfr = hfr;
            this.tr = tr;
        }
    }

    private final List<F> frames = new ArrayList<F>();
    private double since;
    private double lastCheck = -1e9;
    private String haze = "";

    /** From now on (seconds into the session) is the baseline: at the start, and after a focus check. */
    public void reset(double t) {
        frames.clear();
        since = t;
        lastCheck = t;
    }

    /**
     * One frame's stars: their HFR (px) and the starlight relative to clear frames (NaN if unknown).
     * Frames without a size (no stars matched) are skipped.
     */
    public void add(double t, double hfr, double transparency) {
        if (!(hfr > 0) || t < since) return;
        frames.add(new F(t, hfr, transparency));
    }

    /** Why the stars grew though the focus may be fine (thin cloud), for the log; "" if not. */
    public String haze() {
        return haze;
    }

    /**
     * Whether a focus check is due at {@code t} seconds into a session planned to last {@code sessionS}: the
     * reason for the log if so, else null.
     */
    public String due(double t, double sessionS) {
        haze = "";
        if (sessionS < MIN_SESSION_S || t - lastCheck < REST_S) return null;
        int nb = 0;
        for (F f : frames) if (f.t < since + BASE_S) nb++;
        int n = frames.size();
        if (nb < 4 || n - nb < RECENT) return null;
        float[] bh = new float[nb], bt = new float[nb];
        int kt = 0;
        for (int i = 0; i < nb; i++) {
            bh[i] = (float) frames.get(i).hfr;
            if (!Double.isNaN(frames.get(i).tr)) bt[kt++] = (float) frames.get(i).tr;
        }
        double base = Stats.median(bh, nb);
        float[] rh = new float[RECENT], rt = new float[RECENT];
        int ktr = 0;
        for (int i = 0; i < RECENT; i++) {
            F f = frames.get(n - RECENT + i);
            rh[i] = (float) f.hfr;
            if (!Double.isNaN(f.tr)) rt[ktr++] = (float) f.tr;
        }
        double now = Stats.median(rh, RECENT);
        if (now < (1 + RISE) * base) return null;
        // steady: most pairs of frames since the baseline grow with time (Kendall's tau), not one soft patch
        int up = 0, pairs = 0;
        for (int i = nb; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double d = frames.get(j).hfr - frames.get(i).hfr;
                if (d == 0) continue;
                pairs++;
                if (d > 0) up++;
            }
        }
        // the baseline frames count as one more, earlier, point at the baseline size
        for (int i = nb; i < n; i++) {
            pairs++;
            if (frames.get(i).hfr > base) up++;
        }
        if (pairs == 0 || up < 0.7 * pairs) return null;
        double trBase = kt > 0 ? Stats.median(Arrays.copyOf(bt, kt), kt) : Double.NaN;
        double trNow = ktr > 0 ? Stats.median(Arrays.copyOf(rt, ktr), ktr) : Double.NaN;
        if (!Double.isNaN(trBase) && !Double.isNaN(trNow) && trNow < CLEAR * trBase) {
            haze = String.format(Locale.US, "the stars grew from %.2f to %.2f px, but the sky dimmed to %.0f%% of its starlight: haze, not focus",
                    base, now, 100 * trNow / trBase);
            return null;
        }
        lastCheck = t;
        return String.format(Locale.US, "the stars grew from %.2f to %.2f px over %.0f minutes with the sky as clear%s", base, now,
                (t - since) / 60, Double.isNaN(trNow) || Double.isNaN(trBase) ? "" : String.format(Locale.US, " (%.0f%% of its starlight)",
                        100 * trNow / trBase));
    }
}
