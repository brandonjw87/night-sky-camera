package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Per-unit statistics gathered while capturing, with a provisional verdict on each unit, for the remote
 * viewer's counters and charts. The real grading happens when developing ({@link PostProcessor}); this one
 * only uses what is known so far. Thread-safe.
 */
public final class LiveStats {
    public static final class Unit {
        public int index;
        public double t;
        public int stars;
        public float hfr, background, noise, rms;
        /** Starlight relative to the clearer frames so far (1 = clear), NaN if unknown. */
        public float transparency = Float.NaN;
        /** Starlight relative to the reference frame, before normalising. */
        public float rawTransparency = Float.NaN;
        public boolean ok, disturbed;
        /** Why the unit looks unusable so far, or null. */
        public String reason;
        public long wallMs;
    }

    /** Frames this much dimmer than the clear ones count as clouded (the develop step uses the same cut). */
    static final float CLOUD_T = 0.4f;
    /** Stars this much wider than the median count as blurred. */
    static final float BLUR_HFR = 1.6f;

    private final List<Unit> units = new ArrayList<Unit>();

    public synchronized void clear() {
        units.clear();
    }

    public synchronized int size() {
        return units.size();
    }

    /** Adds a processed unit and judges it against the units so far. */
    public synchronized Unit add(UnitRecord rec, Aligner.Result live, long wallMs) {
        Unit u = new Unit();
        u.index = rec.index;
        u.t = rec.t;
        u.stars = rec.stars;
        u.hfr = rec.hfr;
        u.background = rec.background;
        u.noise = rec.noise;
        u.rms = rec.rms;
        u.ok = live.ok;
        u.disturbed = rec.disturbed;
        u.wallMs = wallMs;
        u.rawTransparency = live.ok ? (float) live.transparency : Float.NaN;
        units.add(u);
        int n = units.size();
        // relative to the clearer frames so far (80th percentile), so a hazy reference frame doesn't matter
        float[] tr = new float[n];
        int nt = 0;
        for (Unit x : units) if (!Float.isNaN(x.rawTransparency)) tr[nt++] = x.rawTransparency;
        float norm = nt > 0 ? Math.max(1e-3f, Stats.quantile(tr, nt, 0.8)) : 1f;
        for (Unit x : units) x.transparency = Float.isNaN(x.rawTransparency) ? Float.NaN : x.rawTransparency / norm;
        float[] hf = new float[n];
        int nh = 0;
        for (Unit x : units) if (x.ok && !x.disturbed && x.hfr > 0) hf[nh++] = x.hfr;
        float medHfr = nh >= 3 ? Stats.median(hf, nh) : Float.NaN;
        if (u.disturbed) {
            u.reason = "a bump";
        } else if (!live.ok) {
            u.reason = live.note != null && live.note.startsWith("too few stars") ? "too few stars" : "not lined up";
        } else if (!Float.isNaN(u.transparency) && u.transparency < CLOUD_T) {
            u.reason = String.format(Locale.US, "clouds (%.0f%% light)", 100 * u.transparency);
        } else if (!Float.isNaN(medHfr) && u.hfr > BLUR_HFR * medHfr) {
            u.reason = "blur";
        }
        return u;
    }

    /**
     * What the units add to the stack, in clear units (E5's N_eff, estimated live): a usable unit counts by its
     * starlight squared (haze and thin cloud dim it) times its sky's noise against the darkest clear sky so far,
     * squared (moonlight, dawn or lights make the sky brighter), each at most 1, as the stack weighs frames; an
     * unusable one counts nothing.
     */
    public static double worth(List<Unit> us) {
        float[] nz = new float[us.size()];
        int n = 0;
        for (Unit u : us) {
            if (u.ok && u.reason == null && u.noise > 0 && !(u.transparency < CLEAR_T)) nz[n++] = u.noise;
        }
        float ref = n > 0 ? Stats.quantile(nz, n, 0.2) : Float.NaN;
        double w = 0;
        for (Unit u : us) {
            if (!u.ok || u.reason != null) continue;
            double t = Float.isNaN(u.transparency) ? 1 : Math.min(1, u.transparency);
            double q = !Float.isNaN(ref) && u.noise > 0 ? Math.min(1, ref / u.noise) : 1;
            w += t * t * q * q;
        }
        return w;
    }

    /** Units at least this clear set the darkest sky for {@link #worth}. */
    static final float CLEAR_T = 0.9f;

    /** Copies of the units from position {@code from} on. */
    public synchronized List<Unit> from(int from) {
        List<Unit> out = new ArrayList<Unit>();
        for (int i = Math.max(0, from); i < units.size(); i++) out.add(copy(units.get(i)));
        return out;
    }

    private static Unit copy(Unit u) {
        Unit c = new Unit();
        c.index = u.index;
        c.t = u.t;
        c.stars = u.stars;
        c.hfr = u.hfr;
        c.background = u.background;
        c.noise = u.noise;
        c.rms = u.rms;
        c.transparency = u.transparency;
        c.rawTransparency = u.rawTransparency;
        c.ok = u.ok;
        c.disturbed = u.disturbed;
        c.reason = u.reason;
        c.wallMs = u.wallMs;
        return c;
    }
}
