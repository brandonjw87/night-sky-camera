package com.brandon.nightsky.processing;

import java.util.Locale;

/**
 * The Moonshot's exposure (3.0). The Moon is a sunlit landscape, not a night sky: about 1/650 s at ISO 100 and f/2.8
 * puts a full Moon's bright highlands at two thirds of the RAW range (the "Looney 11" rule, 1/ISO at f/11, is the
 * same light). Its first guess comes from the phase (a quarter Moon's brightest parts are about a third as bright
 * as a full Moon's: the full Moon's shadows vanish and its soil glows back towards the Sun) and the air (0.2
 * magnitudes per airmass); then every frame's bright parts, measured on the RAW, correct it. Short exposures win:
 * the hand shakes less in each, so the ISO goes up before the exposure gets longer than 1/1000 s.
 */
public final class MoonMeter {
    private MoonMeter() {}

    /** Where the bright parts should be: a share of the RAW range (room above for the brightest craters). */
    public static final double TARGET = 0.62;
    /** A full Moon's highlands overhead: share of the range per (second x ISO / f-number squared). */
    static final double K = 32.9;
    /** The exposure the ISO rises to first, and the longest the hand can hold (ns). */
    public static final long SHORT_NS = 1_000_000L, LONG_NS = 8_000_000L;
    /** The noisiest ISO worth using. */
    public static final int ISO_CAP = 1600;

    /** Airmass (Kasten and Young): 1 overhead, about 2 at 30 degrees, 5.6 at 10. */
    public static double airmass(double altDeg) {
        double a = Math.max(0.5, altDeg);
        return 1 / (Math.sin(Math.toRadians(a)) + 0.50572 * Math.pow(a + 6.07995, -1.6364));
    }

    /**
     * The brightest parts of the Moon's face relative to a full Moon's highlands overhead: the phase angle
     * (degrees: 0 full, 90 a quarter, 180 new) and the Moon's height.
     */
    public static double brightness(double phaseDeg, double altDeg) {
        double a = Math.abs(phaseDeg);
        double phase = Math.pow(10, -0.0064 * a) * (1 + 0.3 * Math.exp(-a / 5)) / 1.3;
        double air = Math.pow(10, -0.4 * 0.2 * (airmass(altDeg) - 1));
        return phase * air;
    }

    /** An exposure: time (ns) and ISO. */
    public static final class Exposure {
        public long ns;
        public int iso;

        public Exposure(long ns, int iso) {
            this.ns = ns;
            this.iso = iso;
        }

        /** Light gathered: seconds x ISO. */
        public double light() {
            return ns * 1e-9 * iso;
        }

        /** This light scaled, split again within the limits. */
        public Exposure scaled(double k, Limits l) {
            return split(light() * k, l);
        }

        @Override
        public String toString() {
            double s = ns * 1e-9;
            return (s < 0.25 ? String.format(Locale.US, "1/%d s", Math.round(1 / s)) : String.format(Locale.US, "%.1f s", s)) + " at ISO " + iso;
        }
    }

    /** What the camera allows. */
    public static final class Limits {
        public long minNs = 50_000L, maxNs = 100_000_000L;
        public int minIso = 50, maxIso = 3200;
    }

    /**
     * Seconds x ISO split into an exposure: the ISO from its lowest up to {@link #ISO_CAP} at 1/1000 s (shorter if the
     * lowest ISO is too much), then longer up to 1/125 s, then the ISO the rest of the way.
     */
    public static Exposure split(double light, Limits l) {
        long shortNs = Math.max(l.minNs, Math.min(l.maxNs, SHORT_NS)), longNs = Math.max(shortNs, Math.min(l.maxNs, LONG_NS));
        int cap = Math.max(l.minIso, Math.min(l.maxIso, ISO_CAP));
        double iso = light / (shortNs * 1e-9);
        if (iso < l.minIso) {
            long ns = Math.max(l.minNs, Math.round(light / l.minIso * 1e9));
            return new Exposure(ns, l.minIso);
        }
        if (iso <= cap) return new Exposure(shortNs, (int) Math.round(iso));
        long ns = Math.round(light / cap * 1e9);
        if (ns <= longNs) return new Exposure(ns, cap);
        iso = Math.min(l.maxIso, light / (longNs * 1e-9));
        return new Exposure(longNs, (int) Math.round(iso));
    }

    /** The first exposure, before anything is measured. */
    public static Exposure start(double phaseDeg, double altDeg, double fNumber, Limits l) {
        double n = fNumber > 0 ? fNumber : 2.8;
        double light = TARGET * n * n / (K * brightness(phaseDeg, altDeg));
        return split(light, l);
    }

    /**
     * The next exposure after one measured its bright parts at {@code level} (share of the range) with {@code clipped}
     * of them at the white level: towards {@link #TARGET}, halving at least while anything clips.
     */
    public static Exposure adjust(Exposure now, double level, double clipped, Limits l) {
        double ratio;
        if (clipped > 0.05) ratio = 0.15;
        else if (clipped > 0.003 || level > 0.97) ratio = 0.45;
        else ratio = TARGET / Math.max(0.01, level);
        ratio = Math.max(0.2, Math.min(5, ratio));
        // most of the way, not overshooting on a noisy measure
        ratio = Math.pow(ratio, 0.85);
        return split(now.light() * ratio, l);
    }

    /** Close enough to stop adjusting. */
    public static boolean settled(double level, double clipped) {
        return clipped <= 0.003 && Math.abs(level / TARGET - 1) < 0.15;
    }
}
