package com.brandon.nightsky.processing;

import java.util.Arrays;
import java.util.Locale;

/**
 * How bright a clear night's stars should be for this camera, worked out from its own numbers before it has seen a
 * clear night, and which clear night the haze check compares a frame with.
 *
 * <p>A star of V = 0 sends about {@link #PHOTONS_V0} photons a second through each cm² and Å of light near 545 nm
 * (Bessell, Castelli and Plez 1998: 3.63e-9 erg/s/cm²/Å). The lens takes in its aperture's area (the focal length over
 * the f-number, across). The green pixels turn {@link #GREEN_WIDTH} Å worth of that light into electrons: their
 * quantum efficiency times the colour filter, the lens, the infrared filter and the window, added up over the band
 * they pass (about 0.55 at best over some 110 nm). Half the pixels are green, and the star detector adds up the mean of
 * each 2 x 2 cell's two green pixels over a 5 x 5 box of cells: that box holds a star's core but not the glow the lens
 * scatters around it, which the lens check measures on the same frame ({@link HaloMeter}: the glow 4 to 20 pixels out
 * over the core), so the box holds {@link #FAR_SHARE} / (1 + that ratio) of the light ({@link #BOX_SHARE} when the
 * glow couldn't be measured). The noise profile the camera reports with each frame says how many raw levels an
 * electron makes (its shot-noise slope times the raw range), and a clear sky dims the stars by its Rayleigh
 * scattering, ozone and a clear night's haze at their airmass ({@link #clearExtinction}).
 *
 * <p>The green response is a guess for a phone camera, so the estimate is good to about {@link #UNCERTAINTY}
 * magnitudes: haze is reported from it only {@link #SLACK} magnitudes beyond the usual steps, and a measured clear
 * night replaces it ({@link #choose}).
 */
public final class ClearSky {
    /** Photons a second per cm² per Å from a star of V = 0, near 545 nm. */
    public static final double PHOTONS_V0 = 1000;
    /** The green pixels' response added up over wavelength (Å): what share of each Å's photons become electrons. */
    public static final double GREEN_WIDTH = 600;
    /**
     * Share of a star's light inside the detector's 5 x 5 box of half-res green pixels (10 x 10 RAW pixels) when the
     * lens's glow around the stars isn't measured: a clean phone lens scattering about 7% of it further out.
     */
    public static final double BOX_SHARE = 0.92;
    /** Share of a star's light that isn't in the lens's far wings (beyond the glow the lens check measures). */
    public static final double FAR_SHARE = 0.98;
    /** How far off the estimate may be, about one standard deviation (magnitudes). */
    public static final double UNCERTAINTY = 0.4;
    /** Haze from the estimate must be this much more than haze from a measured clear night to be reported. */
    public static final double SLACK = 0.5;
    /** A measured clear night this far below the estimate (magnitudes) was likely hazy itself. */
    public static final double TRUST = 1.0;
    /** Three clear nights within this of the clearest (magnitudes) outweigh the estimate: then it's the one that's off. */
    static final double AGREE = 0.3;
    /** The frame's own sky noise must be within this factor of the noise profile's for the profile to be believed. */
    static final double NOISE_LO = 0.6, NOISE_HI = 1.7;
    /** Electrons at full scale at ISO 100 that a sensor can plausibly have (outside: the noise profile is wrong). */
    static final double FULL_LO = 300, FULL_HI = 300_000;
    /** Airmass taken when the stars' heights aren't known (a phone looking up). */
    public static final double DEFAULT_AIRMASS = 1.1;

    private ClearSky() {
    }

    /** The estimate for one frame. */
    public static final class Estimate {
        /**
         * The clear sky's zero point for the frame (2.5 log10 of a V = 0 star's flux in the detector's box, the
         * frame's exposure and ISO) and per second at ISO 100 (what clear nights are learned in); NaN if unknown.
         */
        public double zeroPoint = Double.NaN, zeroPointNorm = Double.NaN;
        /** Electrons per raw level at the frame's ISO, from the noise profile. */
        public double electronsPerLevel = Double.NaN;
        /** The aperture across (mm), the stars' airmass and what a clear sky takes off there (magnitudes). */
        public double apertureMm = Double.NaN, airmass = Double.NaN, extinction = Double.NaN;
        /** Share of a star's light in the detector's box. */
        public double boxShare = Double.NaN;
        /** The frame's own sky noise over the noise profile's (1: just as the profile says); NaN if not measured. */
        public double noiseRatio = Double.NaN;
        /** Why there's no estimate ("" when there is one). */
        public String why = "";

        public boolean ok() {
            return !Double.isNaN(zeroPointNorm);
        }

        /** "zero point 7.61 per second at ISO 100 (lens 4.11 mm across, ...)" or why there's none. */
        public String describe() {
            if (!ok()) return "not worked out: " + why;
            return String.format(Locale.US, "zero point %.2f per second at ISO 100 (lens %.2f mm across, %.2f electrons per "
                            + "level, %.0f%% of a star's light in the detector's box, stars at airmass %.2f, a clear sky takes %.2f mag%s)",
                    zeroPointNorm, apertureMm, electronsPerLevel, 100 * boxShare, airmass, extinction,
                    Double.isNaN(noiseRatio) ? "" : String.format(Locale.US, ", the sky's noise %.2f of the noise profile's", noiseRatio));
        }
    }

    /**
     * The clear sky's zero point for a frame.
     *
     * @param airmass    the stars' airmass (NaN: {@link #DEFAULT_AIRMASS})
     * @param altitudeM  the site's height above the sea (NaN: 100 m)
     * @param background the sky's level above black in a green pixel (levels; NaN: the noise isn't checked)
     * @param noise      a green pixel's noise there (levels), as {@link #greenNoise} measures them
     * @param haloRatio  the lens's glow around the stars over their cores, as {@link HaloMeter} measures it (NaN: not
     *                   measured)
     */
    public static Estimate estimate(SensorSpec spec, double exposureS, int iso, double airmass, double altitudeM,
                                    double background, double noise, double haloRatio) {
        Estimate e = new Estimate();
        if (!(spec.focalLengthMm > 0) || !(spec.fNumber > 0)) {
            e.why = "the camera doesn't say its focal length and aperture";
            return e;
        }
        if (!(exposureS > 0) || iso <= 0) {
            e.why = "the frame's exposure isn't known";
            return e;
        }
        double s = 0, o = 0;
        int n = 0;
        for (int p = 0; p < 4; p++) {
            if (spec.planeColor(p) != 1) continue;
            s += spec.noiseS[p];
            o += spec.noiseO[p];
            n++;
        }
        if (n == 0 || !(s > 0)) {
            e.why = "the camera reports no noise profile";
            return e;
        }
        s /= n;
        o = Math.max(0, o / n);
        double range = spec.range();
        double full100 = iso / (100 * s);
        if (!(range > 0) || full100 < FULL_LO || full100 > FULL_HI) {
            e.why = String.format(Locale.US, "the camera's noise profile isn't believable (%.0f electrons at full scale at ISO 100)", full100);
            return e;
        }
        if (background >= 0 && noise > 0) {
            double x = background / range;
            double predicted = Math.sqrt((s * x + o) * range * range);
            e.noiseRatio = noise / predicted;
            if (e.noiseRatio < NOISE_LO || e.noiseRatio > NOISE_HI) {
                e.why = String.format(Locale.US, "the sky's own noise is %.2f of what the camera's noise profile says", e.noiseRatio);
                return e;
            }
        }
        e.electronsPerLevel = 1 / (s * range);
        e.apertureMm = spec.focalLengthMm / spec.fNumber;
        double areaCm2 = Math.PI * Math.pow(e.apertureMm / 20, 2);
        e.airmass = airmass >= 1 ? airmass : DEFAULT_AIRMASS;
        e.extinction = clearExtinction(Double.isNaN(altitudeM) ? 100 : altitudeM) * e.airmass;
        double electrons = PHOTONS_V0 * GREEN_WIDTH * areaCm2 * Math.pow(10, -0.4 * e.extinction);
        e.boxShare = haloRatio >= 0 ? FAR_SHARE / (1 + haloRatio) : BOX_SHARE;
        // a quarter: half the pixels are green, and the detector takes the mean of each cell's two
        double flux = 0.25 * e.boxShare * electrons / e.electronsPerLevel * exposureS;
        e.zeroPoint = 2.5 * Math.log10(flux);
        e.zeroPointNorm = e.zeroPoint - 2.5 * Math.log10(exposureS * iso / 100.0);
        return e;
    }

    /** Cells sampled each way by {@link #greenNoise}, and the fewest it needs. */
    static final int NOISE_STEP = 4, NOISE_MIN_CELLS = 1000;

    /**
     * The sky's level and a green pixel's noise there, from the two green pixels of every fourth 2 x 2 cell each way:
     * their difference carries their own noise and next to none of the sky's texture (faint stars, a gradient, the
     * Milky Way's glow), which the spread between neighbouring cells would count as noise. Clipped cells and stars
     * fall out of the medians. {the sky's level above black, one green pixel's noise}, in raw levels; NaNs if the frame
     * has too few cells or no two green sites.
     */
    public static double[] greenNoise(short[] raw, float invScale, float[] black, SensorSpec spec) {
        int W = spec.width, H = spec.height, ga = -1, gb = -1;
        for (int q = 0; q < 4; q++) {
            if (spec.cfa[q] != 1) continue;
            if (ga < 0) ga = q;
            else gb = q;
        }
        double[] none = {Double.NaN, Double.NaN};
        if (ga < 0 || gb < 0 || raw == null || raw.length < W * H) return none;
        int ax = ga & 1, ay = ga >> 1, bx = gb & 1, by = gb >> 1, cw = W / 2, ch = H / 2;
        float sat = 0.8f * spec.white;
        int max = ((cw + NOISE_STEP - 1) / NOISE_STEP) * ((ch + NOISE_STEP - 1) / NOISE_STEP);
        float[] diff = new float[max], level = new float[max];
        int n = 0;
        for (int j = 0; j < ch; j += NOISE_STEP) {
            int ra = (2 * j + ay) * W + ax, rb = (2 * j + by) * W + bx;
            for (int i = 0; i < cw; i += NOISE_STEP) {
                float a = (raw[ra + 2 * i] & 0xFFFF) * invScale, b = (raw[rb + 2 * i] & 0xFFFF) * invScale;
                if (a >= sat || b >= sat) continue;
                a -= black[ga];
                b -= black[gb];
                diff[n] = a - b;
                level[n] = 0.5f * (a + b);
                n++;
            }
        }
        if (n < NOISE_MIN_CELLS) return none;
        double sky = Stats.median(level, n);
        // about the pairs' median difference (the two green sites can differ a little in gain): a first guess from the
        // median absolute difference, then the spread of the differences within 3 of it (stars' cores fall outside),
        // which unlike the median isn't rounded to the raw values' whole levels; a Gaussian cut at 3 sigma keeps 0.9733
        // of its variance. The difference of two pixels has twice one's variance.
        float[] work = Arrays.copyOf(diff, n);
        float centre = Stats.median(work, n);
        double sig = Stats.madSigma(work, n, centre);
        for (int it = 0; it < 2 && sig > 0; it++) {
            double lim = 3 * sig, ss = 0;
            int m = 0;
            for (int k = 0; k < n; k++) {
                double v = diff[k] - centre;
                if (Math.abs(v) > lim) continue;
                ss += v * v;
                m++;
            }
            if (m < NOISE_MIN_CELLS / 2) break;
            sig = Math.sqrt(ss / m / 0.9733);
        }
        return new double[]{sky, sig / Math.sqrt(2)};
    }

    /**
     * What a clear sky takes off the stars at 545 nm, per airmass (magnitudes), at a height above the sea (m):
     * Rayleigh scattering (0.114 at sea level, falling with the air's 8 km scale height), ozone (0.03) and a clear
     * night's haze (0.07, mostly in the lowest 1.5 km).
     */
    public static double clearExtinction(double altitudeM) {
        double h = Math.max(0, altitudeM);
        return 0.114 * Math.exp(-h / 8000) + 0.03 + 0.07 * Math.exp(-h / 1500);
    }

    /** Airmass at an altitude above the horizon (degrees): Kasten and Young (1989). */
    public static double airmass(double altDeg) {
        if (!(altDeg > 0)) return 40;
        return 1 / (Math.sin(Math.toRadians(altDeg)) + 0.50572 * Math.pow(altDeg + 6.07995, -1.6364));
    }

    /** Which clear night a frame is compared with. */
    public static final class Baseline {
        /** Zero point per second at ISO 100 (NaN: nothing to compare with). */
        public double zeroPoint = Double.NaN;
        /** Whether it is this camera's estimate rather than a measured clear night. */
        public boolean fromSpecs;
        /** Why, for the log ("" if nothing to say). */
        public String note = "";

        public boolean ok() {
            return !Double.isNaN(zeroPoint);
        }
    }

    /**
     * The clear night to compare with: the clearest measured one (the second clearest once there are three, so one
     * odd reading can't make every night look hazy), unless it is more than {@link #TRUST} magnitudes below the
     * camera's estimate, which means it was likely hazy itself. Then the clearest night if it agrees with the estimate
     * (the others were hazy), or else the estimate, until three nights agree within {@link #AGREE} (then it's the
     * estimate that is off). With no measured night, the estimate.
     *
     * @param learned the zero points of the sessions measured so far (per second at ISO 100), any order
     * @param prior   the camera's estimate (may be null or not {@link Estimate#ok})
     */
    public static Baseline choose(double[] learned, Estimate prior) {
        Baseline b = new Baseline();
        boolean specs = prior != null && prior.ok();
        double[] v = learned == null ? new double[0] : learned.clone();
        int n = 0;
        for (double x : v) if (!Double.isNaN(x)) v[n++] = x;
        v = Arrays.copyOf(v, n);
        Arrays.sort(v);
        if (n > 0) {
            double clearest = v[n - 1], pick = n >= 3 ? v[n - 2] : clearest;
            if (!specs || pick >= prior.zeroPointNorm - TRUST) {
                b.zeroPoint = pick;
                return b;
            }
            double lo = prior.zeroPointNorm - TRUST, hi = prior.zeroPointNorm + TRUST;
            if (clearest >= lo && clearest <= hi) {
                b.zeroPoint = clearest;
                b.note = String.format(Locale.US, "the clearest night (%.2f) agrees with the camera's specs (%.2f); the others "
                        + "were hazy", clearest, prior.zeroPointNorm);
                return b;
            }
            int agree = 0;
            for (double x : v) if (x >= clearest - AGREE) agree++;
            if (agree >= 3) {
                b.zeroPoint = pick;
                b.note = String.format(Locale.US, "clear nights at %.2f, %.1f magnitudes below the camera's specs (%.2f): %d nights "
                        + "agree, so it's the specs that are off for this camera", pick, prior.zeroPointNorm - pick, prior.zeroPointNorm, agree);
                return b;
            }
            // (the clearest can be far above the specs only when the second clearest is far below: one odd reading)
            b.note = clearest > hi
                    ? String.format(Locale.US, "the clearest night so far (%.2f) is %.1f magnitudes above the camera's specs (%.2f) and "
                            + "the next clearest (%.2f) %.1f below them, too far apart to trust either: compared with the specs until "
                            + "three nights agree", clearest, clearest - prior.zeroPointNorm, prior.zeroPointNorm, pick,
                    prior.zeroPointNorm - pick)
                    : String.format(Locale.US, "the clearest night so far (%.2f) is %.1f magnitudes below the camera's specs (%.2f), "
                            + "so it was likely hazy: compared with the specs until three nights agree", clearest,
                    prior.zeroPointNorm - clearest, prior.zeroPointNorm);
        }
        if (specs) {
            b.zeroPoint = prior.zeroPointNorm;
            b.fromSpecs = true;
        }
        return b;
    }
}
