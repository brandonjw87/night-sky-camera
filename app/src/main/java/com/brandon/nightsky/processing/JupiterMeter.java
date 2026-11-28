package com.brandon.nightsky.processing;

/**
 * Jupiter's exposure (3.2). Jupiter's face is about eight times dimmer than the full Moon's highlands, and at the
 * telephoto's 17 arcseconds a pixel it is a dot two or three pixels across, so its brightest pixel gets about a fifth
 * of its light: about 1/40 s at ISO 50 puts that pixel at {@link #TARGET} of the RAW range. Unlike the Moonshot's,
 * this exposure gets longer before the ISO rises (to 1/30 s, which the lens's stabiliser holds): the moons are a
 * thousand times fainter than Jupiter and need every photon, and the ISO only amplifies what came in. The moons'
 * burst then takes {@link #MOONS_GAIN} times the light, Jupiter burnt white, the moons well clear of the noise.
 */
public final class JupiterMeter {
    private JupiterMeter() {}

    /** Where Jupiter's brightest pixel should be (the sharpest frames: blurred ones read lower). */
    public static final double TARGET = 0.45;
    /** The longest exposure (ns): a thirtieth of a second, the frame rate's own. */
    public static final long LONG_NS = 33_000_000L;
    /** The moons' burst against Jupiter's, and the least worth taking. */
    public static final double MOONS_GAIN = 16, MOONS_MIN = 3;
    /** The full Moon's highlands, magnitudes per square arcsecond, and Jupiter's brightest pixel's share of its light. */
    static final double HIGHLANDS_MAG = 3.1, PEAK_SHARE = 0.2;

    /** Seconds x ISO as an exposure: the lowest ISO, longer up to {@link #LONG_NS}, then the ISO. */
    public static MoonMeter.Exposure split(double light, MoonMeter.Limits l) {
        long longNs = Math.max(l.minNs, Math.min(l.maxNs, LONG_NS));
        long ns = Math.round(light / l.minIso * 1e9);
        if (ns <= longNs) return new MoonMeter.Exposure(Math.max(l.minNs, ns), l.minIso);
        double iso = Math.min(l.maxIso, Math.max(l.minIso, light / (longNs * 1e-9)));
        return new MoonMeter.Exposure(longNs, (int) Math.round(iso));
    }

    /**
     * The first exposure, before anything is measured: from Jupiter's magnitude, its height (the air dims it 0.2
     * magnitudes an airmass), the f-number and the pixel scale (arcseconds; NaN: 17), against the Moonshot's
     * full-Moon highlands ({@link MoonMeter#K}).
     */
    public static MoonMeter.Exposure start(double mag, double altDeg, double fNumber, double arcsecPerPx, MoonMeter.Limits l) {
        double n = fNumber > 0 ? fNumber : 2.8, s = arcsecPerPx > 0 ? arcsecPerPx : 17;
        double pixMag = HIGHLANDS_MAG - 5 * Math.log10(s);
        double air = Math.pow(10, -0.4 * 0.2 * (MoonMeter.airmass(altDeg) - 1));
        double peak = PEAK_SHARE * Math.pow(10, -0.4 * (mag - pixMag)) * air;
        return split(TARGET * n * n / (MoonMeter.K * peak), l);
    }

    /**
     * The next exposure after frames whose brightest showed Jupiter's brightest pixel at {@code level} with
     * {@code clipped} of its middle at the white level: towards {@link #TARGET}, much less light while anything clips.
     */
    public static MoonMeter.Exposure adjust(MoonMeter.Exposure now, double level, double clipped, MoonMeter.Limits l) {
        double ratio;
        if (clipped > 0.1) ratio = 0.15;
        else if (clipped > 0 || level > 0.97) ratio = 0.4;
        else ratio = TARGET / Math.max(0.004, level);
        ratio = Math.max(0.1, Math.min(10, ratio));
        ratio = Math.pow(ratio, 0.85);
        return split(now.light() * ratio, l);
    }

    /** Close enough to stop adjusting. */
    public static boolean settled(double level, double clipped) {
        return clipped == 0 && Math.abs(level / TARGET - 1) < 0.2;
    }

    /** Off enough, once settled, to adjust again (Jupiter climbing, a haze passing). */
    public static boolean drifted(double level, double clipped) {
        return clipped > 0 || Math.abs(level / TARGET - 1) > 0.4;
    }

    /**
     * The moons' exposure: as long as the frame rate and {@link #LONG_NS} allow, then the ISO, up to
     * {@link #MOONS_GAIN} times Jupiter's light.
     */
    public static MoonMeter.Exposure moons(MoonMeter.Exposure main, MoonMeter.Limits l, long frameNs) {
        long ns = Math.max(main.ns, Math.max(l.minNs, Math.min(Math.min(l.maxNs, LONG_NS), frameNs)));
        double want = main.light() * MOONS_GAIN;
        double iso = Math.min(l.maxIso, Math.max(l.minIso, want / (ns * 1e-9)));
        if (iso <= l.minIso && ns * 1e-9 * l.minIso > want) ns = Math.max(main.ns, Math.round(want / l.minIso * 1e9));
        return new MoonMeter.Exposure(ns, (int) Math.round(iso));
    }
}
